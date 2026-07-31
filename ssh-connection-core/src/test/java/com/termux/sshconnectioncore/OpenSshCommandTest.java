package com.termux.sshconnectioncore;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class OpenSshCommandTest {

    @Test
    public void stripsPriorRemoteCommandAndPreservesQuotedArguments() {
        OpenSshCommand command = OpenSshCommand.parse(
            "ssh -i '/keys/prod key' -p2222 root@example.com 'old command'");

        Assert.assertEquals("ssh -i '/keys/prod key' -p2222 root@example.com",
            command.renderBaseCommand());
        Assert.assertTrue(command.hasOption("IdentityFile"));
    }

    @Test
    public void insertsOptionsBeforeDestinationAndRemoteCommandAfterIt() {
        OpenSshCommand command = OpenSshCommand.parse("ssh -v -- root@example.com");

        String rendered = command.renderRemoteCommand(
            Arrays.asList("-o", "ConnectTimeout=5"), true, "printf 'ok'");
        Assert.assertTrue(rendered.startsWith("ssh -v -o ConnectTimeout=5 -tt -- root@example.com "));
        Assert.assertTrue(rendered.endsWith(OpenSshCommand.quoteShellToken("printf 'ok'")));
    }

    @Test
    public void detectsSshpassAndExistingOptions() {
        OpenSshCommand command = OpenSshCommand.parse(
            "sshpass -p 'not logged' /data/data/com.termux/files/usr/bin/ssh " +
                "-oControlMaster=no -S none user@host");

        Assert.assertTrue(command.usesSshpass());
        Assert.assertTrue(command.hasOption("ControlMaster"));
        Assert.assertTrue(command.hasOption("ControlPath"));
    }

    @Test
    public void sshpassOptionValuesCannotMasqueradeAsTheSshExecutable() {
        OpenSshCommand command = OpenSshCommand.parse(
            "sshpass -P ssh -p secret /data/data/com.termux/files/usr/bin/ssh user@host");

        Assert.assertTrue(command.usesSshpass());
        Assert.assertEquals(
            "sshpass -P ssh -p secret /data/data/com.termux/files/usr/bin/ssh user@host",
            command.renderBaseCommand());
    }

    @Test
    public void combinedSshOptionsAreParsedWithoutInspectingTheirValuesAsFlags() {
        OpenSshCommand command = OpenSshCommand.parse(
            "ssh -vS/tmp/control -oServerAliveInterval=9 -i -tt-name user@host");

        Assert.assertTrue(command.hasOption("ControlPath"));
        Assert.assertTrue(command.hasOption("ServerAliveInterval"));
        Assert.assertTrue(command.hasOption("IdentityFile"));
        Assert.assertFalse(command.hasForcedTty());
    }

    @Test
    public void keepsEmptyQuotedWrapperArgument() {
        OpenSshCommand command = OpenSshCommand.parse("env EMPTY='' ssh user@host");
        Assert.assertEquals("env EMPTY= ssh user@host", command.renderBaseCommand());
    }

    @Test
    public void quotedTildeRemainsLiteralAfterCanonicalization() {
        OpenSshCommand command = OpenSshCommand.parse("ssh -i '~/literal-key' user@host");

        Assert.assertEquals("ssh -i '~/literal-key' user@host", command.renderBaseCommand());
    }

    @Test
    public void forcedTtyIsNotDuplicated() {
        OpenSshCommand command = OpenSshCommand.parse("ssh -tt user@host");
        Assert.assertEquals("ssh -tt user@host true",
            command.renderRemoteCommand(Collections.emptyList(), true, "true"));
    }

    @Test
    public void wrapperArgumentNamedSshIsNotTreatedAsExecutable() {
        OpenSshCommand command = OpenSshCommand.parse(
            "sshpass -p ssh /data/data/com.termux/files/usr/bin/ssh user@host");

        Assert.assertEquals("user@host", command.destination());
        Assert.assertTrue(command.usesSshpass());
    }

    @Test
    public void replacesQuotedSpaceFormOptionAndKeepsRemoteCommand() {
        OpenSshCommand command = OpenSshCommand.parse(
            "ssh -v -o 'UserKnownHostsFile /old path' user@host 'printf ok'");
        Set<String> replaced = new HashSet<>(Collections.singletonList("UserKnownHostsFile"));
        List<String> replacement = Arrays.asList("-o", "UserKnownHostsFile=/new path");

        String rendered = command.renderReplacingOpenSshOptions(replaced, replacement);
        Assert.assertFalse(rendered.contains("/old path"));
        Assert.assertTrue(rendered.contains("'UserKnownHostsFile=/new path'"));
        Assert.assertTrue(rendered.endsWith("user@host 'printf ok'"));
    }

    @Test
    public void replacementRemovesEveryDuplicateAndPreservesCombinedFlags() {
        OpenSshCommand command = OpenSshCommand.parse(
            "ssh -voStrictHostKeyChecking=no -o StrictHostKeyChecking=accept-new "
                + "user@host 'printf ok'");
        Set<String> replaced = new HashSet<>(Collections.singletonList("StrictHostKeyChecking"));

        String rendered = command.renderReplacingOpenSshOptions(
            replaced, Arrays.asList("-o", "StrictHostKeyChecking=yes"));

        Assert.assertTrue(rendered, rendered.startsWith("ssh -v -o StrictHostKeyChecking=yes"));
        Assert.assertEquals(rendered, 1, countOccurrences(rendered, "StrictHostKeyChecking="));
        Assert.assertTrue(rendered, rendered.endsWith("user@host 'printf ok'"));
    }

    @Test
    public void stableIdUsesDeterministic128BitFingerprint() {
        OpenSshCommand first = OpenSshCommand.parse("ssh -p 2222 user@example.com");
        OpenSshCommand same = OpenSshCommand.parse("ssh   -p 2222   user@example.com");
        OpenSshCommand different = OpenSshCommand.parse("ssh -p 22 user@example.com");

        Assert.assertTrue(first.stableId().matches("[0-9a-f]{32}"));
        Assert.assertEquals(first.stableId(), same.stableId());
        Assert.assertNotEquals(first.stableId(), different.stableId());
    }

    @Test
    public void rejectsShellProgramsAndExpansions() {
        assertRejected("ssh user@host; touch /tmp/injected");
        assertRejected("ssh $TARGET");
        assertRejected("ssh user@host | tee output");
        assertRejected("ssh user@host\\\nnext");
        assertRejected("ssh 'unterminated");
        assertRejected("printf ssh ssh user@host");
        assertRejected("sshpass -Z ssh user@host");
    }

    private static void assertRejected(String value) {
        try {
            OpenSshCommand.parse(value);
            Assert.fail("Expected rejection for: " + value);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
