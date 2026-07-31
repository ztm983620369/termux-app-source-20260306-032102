package com.termux.terminalsessionruntime;

import com.termux.terminalsessioncore.SshTmuxSessionStateMachine;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SshTmuxCommandFactoryTest {

    private static final String PROFILE = "ssh -p 6223 root@119.45.226.7";
    private static final String TRANSPORT_SCOPE_A = "06a81567-0748-4454-af39-55d8d991ed40";
    private static final String TRANSPORT_SCOPE_B = "59ce4037-c79f-46bc-a56c-c7d8b5715b12";
    private static final int OPENSSH_TEMPORARY_CONTROL_PATH_SUFFIX_BYTES = 17;
    private static final int UNIX_DOMAIN_SOCKET_PATH_BYTES = 108;
    private static final Pattern CONTROL_PATH_PATTERN = Pattern.compile("ControlPath=([^ ']+)");

    private final SshTmuxCommandFactory factory = new SshTmuxCommandFactory();

    @Test
    public void mouseEnableCommandUsesThePortableVerifiedTargetContract() {
        Assert.assertEquals(
            "tmux set-option -t work mouse on >/dev/null 2>&1",
            factory.buildTmuxMouseEnableCommand("work"));
    }

    @Test
    public void exactSessionCheckRejectsTmuxPrefixMatches() {
        Assert.assertEquals(
            "[ \"$(tmux display-message -p -t work '#{session_name}' 2>/dev/null)\" = work ]",
            factory.buildTmuxExactSessionCheck("work"));
    }

    @Test
    public void managementOptionsAreBeforeDestination() {
        String command = factory.buildSshRemoteExecCommand(
            "ssh -p 2222 root@example.com old-command", "printf ready");

        int destination = command.indexOf("root@example.com");
        Assert.assertTrue(command.indexOf("ConnectTimeout=5") < destination);
        Assert.assertTrue(command.indexOf("ControlMaster=auto") < destination);
        Assert.assertTrue(command.endsWith("root@example.com 'printf ready'"));
        Assert.assertFalse(command.contains("old-command"));
    }

    @Test
    public void sshpassDoesNotEnableBatchMode() {
        String command = factory.buildSshRemoteExecCommand(
            "sshpass -p secret ssh user@example.com", "true");

        Assert.assertFalse(command.contains("BatchMode=yes"));
        Assert.assertTrue(command.contains("ControlPersist=300"));
    }

    @Test
    public void reconnectUsesMuxSyncFramesAndBoundedBackoff() {
        String command = factory.buildReconnectLoopCommand(
            "ssh user@example.com", "work", "Work", 50000, TRANSPORT_SCOPE_A);

        Assert.assertTrue(command.contains("ssh_loop_protocol=8"));
        Assert.assertTrue(command.contains("ControlMaster=auto"));
        Assert.assertTrue(command.contains("delay=0.25"));
        Assert.assertTrue(command.contains("delay=10"));
        Assert.assertTrue(command.contains("tmux set-option -t work mouse on"));
        Assert.assertTrue(command.contains("tmux set-option -t work mouse on >/dev/null 2>&1 || exit $?"));
        Assert.assertTrue(command.contains("tmux set-window-option -t work history-limit 50000"));
        Assert.assertTrue(command.contains("tmux -T sync attach-session -t work"));
        Assert.assertTrue(command.contains("tmux set-window-option -t work alternate-screen on"));
        Assert.assertTrue(command.contains("tmux capture-pane -p -t \"$pane\" -S -50000"));
        Assert.assertEquals(1, countOccurrences(command,
            "tmux capture-pane -p -t \"$pane\" -S -50000"));
        Assert.assertTrue(command.contains("preload=1"));
        Assert.assertTrue(command.contains("preload=0"));
        Assert.assertEquals(2, countOccurrences(command, " -tt "));
    }

    @Test
    public void reconnectDoesNotRestoreAStaleDisplayNameAfterRename() {
        String command = factory.buildReconnectLoopCommand(
            "ssh user@example.com", "work", "Original name", 50000, TRANSPORT_SCOPE_A);

        Assert.assertEquals(1, countOccurrences(
            command, SshTmuxSessionStateMachine.TMUX_DISPLAY_NAME_OPTION));
    }

    @Test
    public void tmuxTargetsUsePortableNamesAfterAnExactSessionCheck() {
        String create = factory.buildTmuxCreateSessionCommand(
            "ssh user@example.com", "work", "Work");
        String reconnect = factory.buildReconnectLoopCommand(
            "ssh user@example.com", "work", "Work", 50000, TRANSPORT_SCOPE_A);

        Assert.assertTrue(create, create.contains("tmux display-message -p -t work"));
        Assert.assertTrue(create, create.contains("#{session_name}"));
        Assert.assertTrue(create, create.contains("= work"));
        Assert.assertTrue(create, create.contains("tmux new-session -d -s work"));
        Assert.assertFalse(create, create.contains("tmux new-session -d -s =work"));
        Assert.assertFalse(reconnect, reconnect.contains("tmux set-option -t =work"));
        Assert.assertTrue(reconnect, reconnect.contains("tmux new-session -d -s work"));
        Assert.assertTrue(reconnect, reconnect.contains("tmux attach-session -t work"));
    }

    @Test
    public void exactTargetMarkerRoundTripsWhenInspectingReconnectScripts() {
        Assert.assertEquals("work", factory.unquoteShellToken("=work"));
        Assert.assertEquals("=work", factory.unquoteShellToken("==work"));
    }

    @Test
    public void restoredReconnectNeverRecreatesADeletedRemoteSession() throws Exception {
        String command = factory.buildReconnectLoopCommand(
            "ssh user@example.com", "work", "Work", 50000, TRANSPORT_SCOPE_A, false);

        Assert.assertTrue(command, command.contains("bootstrap_create=0"));
        Assert.assertFalse(command, command.contains("tmux new-session -d -s work"));
        Assert.assertTrue(command, command.contains("tmux display-message -p -t work"));
        Assert.assertTrue(command, command.contains("#{session_name}"));
        Assert.assertTrue(command, command.contains("= work"));
        Assert.assertTrue(command, command.contains("__TMUX_GONE__; exit 43"));
        Assert.assertTrue(command, command.contains("stop reconnect loop\"; exit 43"));
        Assert.assertTrue(command, command.contains("remote tmux session removed, stop reconnect loop"));
        assertValidBash(command);
    }

    @Test
    public void explicitReconnectBootstrapMayCreateTheRequestedSession() {
        String command = factory.buildReconnectLoopCommand(
            "ssh user@example.com", "work", "Work", 50000, TRANSPORT_SCOPE_A, true);

        Assert.assertTrue(command, command.contains("bootstrap_create=1"));
        Assert.assertTrue(command, command.contains("tmux new-session -d -s work"));
    }

    @Test
    public void defaultControlPathLeavesRoomForOpenSshTemporarySuffix() {
        String command = factory.buildSshRemoteExecCommand(
            "sshpass -p secret ssh -p 6223 root@119.45.226.7", "true");

        String controlPath = onlyControlPath(command);
        assertManagedControlPathFitsOpenSsh(controlPath);
        Assert.assertFalse(controlPath.contains("%C"));
        Assert.assertTrue(controlPath.matches(".*/tmx-[0-9a-f]{32}"));
    }

    @Test
    public void scopedControlPathLeavesRoomForOpenSshTemporarySuffix() {
        String command = factory.buildReconnectLoopCommand(
            PROFILE, "work", "Work", 50000, TRANSPORT_SCOPE_A);

        List<String> controlPaths = controlPaths(command);
        Assert.assertEquals(command, 3, controlPaths.size());
        for (String controlPath : controlPaths) {
            assertManagedControlPathFitsOpenSsh(controlPath);
            Assert.assertTrue(controlPath, controlPath.matches(".*/tmx-[0-9a-f]{32}"));
        }
    }

    @Test
    public void sameProfileUsesDifferentTransportForDifferentPersistentScope() {
        String first = factory.buildReconnectLoopCommand(
            PROFILE, "work-a", "Work A", 50000, TRANSPORT_SCOPE_A);
        String second = factory.buildReconnectLoopCommand(
            PROFILE, "work-b", "Work B", 50000, TRANSPORT_SCOPE_B);

        String firstPath = onlyDistinctControlPath(first);
        String secondPath = onlyDistinctControlPath(second);
        Assert.assertNotEquals(firstPath, secondPath);
    }

    @Test
    public void sameScopeUsesOneDeterministicTransportForEnsureAndAttach() {
        String first = factory.buildReconnectLoopCommand(
            PROFILE, "work", "Work", 50000, TRANSPORT_SCOPE_A);
        String second = factory.buildReconnectLoopCommand(
            PROFILE, "work", "Renamed display", 120000, TRANSPORT_SCOPE_A);

        List<String> firstPaths = controlPaths(first);
        List<String> secondPaths = controlPaths(second);
        Assert.assertEquals(first, 3, firstPaths.size());
        Assert.assertEquals(second, 3, secondPaths.size());
        Assert.assertEquals(Arrays.asList(firstPaths.get(0), firstPaths.get(0), firstPaths.get(0)), firstPaths);
        Assert.assertEquals(Arrays.asList(firstPaths.get(0), firstPaths.get(0), firstPaths.get(0)), secondPaths);
    }

    @Test
    public void differentProfilesRemainIsolatedWithinSameTransportScope() {
        String first = factory.buildReconnectLoopCommand(
            "ssh -p 22 alice@example.com", "work", "Work", 50000, TRANSPORT_SCOPE_A);
        String second = factory.buildReconnectLoopCommand(
            "ssh -p 2222 -i /keys/production bob@example.com", "work", "Work", 50000,
            TRANSPORT_SCOPE_A);

        Assert.assertNotEquals(onlyDistinctControlPath(first), onlyDistinctControlPath(second));
    }

    @Test
    public void reconnectIdentityRequiresBothProfileAndTransportScope() {
        String command = factory.buildReconnectLoopCommand(
            PROFILE, "work", "Work", 50000, TRANSPORT_SCOPE_A);

        Assert.assertTrue(factory.isReconnectTransportIdentity(command, PROFILE, TRANSPORT_SCOPE_A));
        Assert.assertFalse(factory.isReconnectTransportIdentity(
            command, "ssh -p 6223 other@119.45.226.7", TRANSPORT_SCOPE_A));
        Assert.assertFalse(factory.isReconnectTransportIdentity(command, PROFILE, TRANSPORT_SCOPE_B));
        Assert.assertFalse(factory.isReconnectTransportIdentity(command, "not-ssh", TRANSPORT_SCOPE_A));
    }

    @Test
    public void unscopedControlPlaneCommandsShareTheProfileTransport() {
        String check = factory.buildTmuxCheckCommand(PROFILE);
        String list = factory.buildTmuxListSessionsCommand(PROFILE);
        String sync = factory.buildTmuxDisplaySyncRemoteExecCommand(PROFILE, "work", "Work");
        String expected = onlyControlPath(check);

        Assert.assertEquals(expected, onlyControlPath(list));
        Assert.assertEquals(expected, onlyControlPath(sync));
        Assert.assertNotEquals(expected, onlyDistinctControlPath(factory.buildReconnectLoopCommand(
            PROFILE, "work", "Work", 50000, TRANSPORT_SCOPE_A)));
    }

    @Test
    public void transportScopeCannotInjectShellOrOpenSshConfiguration() {
        String hostileScope = "scope'; printf __SCOPE_INJECTED__; #\n" +
            "-o ControlPath=/tmp/injected -o ProxyCommand=malicious";
        String command = factory.buildReconnectLoopCommand(
            PROFILE, "work", "Work", 50000, hostileScope);

        Assert.assertFalse(command, command.contains("__SCOPE_INJECTED__"));
        Assert.assertFalse(command, command.contains("/tmp/injected"));
        Assert.assertFalse(command, command.contains("ProxyCommand=malicious"));
        List<String> paths = controlPaths(command);
        Assert.assertEquals(command, 3, paths.size());
        Assert.assertEquals(paths.get(0), paths.get(1));
        Assert.assertEquals(paths.get(0), paths.get(2));
        Assert.assertTrue(paths.get(0), paths.get(0).matches(".*/tmx-[0-9a-f]{32}"));
    }

    @Test
    public void explicitControlMasterDisablesManagedControlConfiguration() {
        assertCustomControlConfigurationRemainsAuthoritative(
            "ssh -o ControlMaster=no user@example.com", "ControlMaster=no");
    }

    @Test
    public void explicitControlPersistDisablesManagedControlConfiguration() {
        assertCustomControlConfigurationRemainsAuthoritative(
            "ssh -o ControlPersist=47 user@example.com", "ControlPersist=47");
    }

    @Test
    public void explicitControlPathDisablesManagedControlConfiguration() {
        assertCustomControlConfigurationRemainsAuthoritative(
            "ssh -o 'ControlPath=/tmp/custom mux' user@example.com",
            "'ControlPath=/tmp/custom mux'");
    }

    @Test
    public void currentReconnectProtocolAcceptsV8AndRejectsOlderVersions() {
        Assert.assertTrue(factory.isCurrentReconnectProtocol("ssh_loop_protocol=8"));
        Assert.assertTrue(factory.isCurrentReconnectProtocol("ssh_loop_protocol=8; while true; do :; done"));
        Assert.assertFalse(factory.isCurrentReconnectProtocol("ssh_loop_protocol=50; while true; do :; done"));
        Assert.assertFalse(factory.isCurrentReconnectProtocol("ssh_loop_protocol=7; while true; do :; done"));
        Assert.assertFalse(factory.isCurrentReconnectProtocol("ssh_loop_protocol=6; while true; do :; done"));
        Assert.assertFalse(factory.isCurrentReconnectProtocol("ssh_loop_protocol=5; while true; do :; done"));
        Assert.assertFalse(factory.isCurrentReconnectProtocol("ssh_loop_protocol=4; while true; do :; done"));
        Assert.assertFalse(factory.isCurrentReconnectProtocol("ssh_loop_protocol=3; while true; do :; done"));
        Assert.assertFalse(factory.isCurrentReconnectProtocol(
            "ssh_loop_protocol=7; profile_name=ssh_loop_protocol=8; while true; do :; done"));
    }

    @Test
    public void embeddedBaseCommandRoundTrips() {
        String base = "ssh -i '/keys/prod key' root@example.com";
        String loop = factory.buildReconnectLoopCommand(
            base, "work", "Work", 50000, TRANSPORT_SCOPE_A);

        Assert.assertEquals(base, factory.extractSshCommandFromReconnectLoop(loop));
        Assert.assertEquals(base, factory.sanitizeSshBootstrapCommand(loop));
    }

    @Test
    public void invalidShellProgramIsNeverExecuted() {
        String command = factory.buildSshRemoteExecCommand(
            "ssh root@example.com; touch /tmp/injected", "true");

        Assert.assertTrue(command.contains("invalid SSH profile"));
        Assert.assertFalse(command.contains("touch /tmp/injected"));
    }

    @Test
    public void generatedReconnectLoopIsValidBash() throws Exception {
        String command = factory.buildReconnectLoopCommand(
            "env LANG=C sshpass -P 'Password:' -p 'secret with spaces' ssh " +
                "-i '/keys/prod key' user@example.com",
            "work", "Production shell", 50000, TRANSPORT_SCOPE_A);

        assertValidBash(command);
    }

    private static void assertValidBash(String command) throws Exception {
        File bash = new File("/bin/bash");
        Assume.assumeTrue("Host bash is unavailable", bash.canExecute());

        Process process = new ProcessBuilder(bash.getAbsolutePath(), "-n")
            .redirectErrorStream(true)
            .start();
        try (OutputStream input = process.getOutputStream()) {
            input.write(command.getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder diagnostics = new StringBuilder();
        try (BufferedReader output = new BufferedReader(new InputStreamReader(
            process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null) diagnostics.append(line).append('\n');
        }
        int exitCode = process.waitFor();
        Assert.assertEquals(diagnostics.toString(), 0, exitCode);
    }

    private void assertCustomControlConfigurationRemainsAuthoritative(String profile,
                                                                       String expectedToken) {
        String command = factory.buildReconnectLoopCommand(
            profile, "work", "Work", 50000, TRANSPORT_SCOPE_A);

        Assert.assertEquals(command, 3, countOccurrences(command, expectedToken));
        Assert.assertFalse(command, command.contains("ControlMaster=auto"));
        Assert.assertFalse(command, command.contains("ControlPersist=300"));
        Assert.assertFalse(command, command.contains("/tmx-"));
    }

    private static void assertManagedControlPathFitsOpenSsh(String controlPath) {
        int configuredBytes = controlPath.getBytes(StandardCharsets.UTF_8).length;
        Assert.assertTrue("configured mux path is " + configuredBytes + " bytes: " + controlPath,
            configuredBytes <= SshTmuxCommandFactory.MAX_CONTROL_PATH_BYTES);
        Assert.assertTrue("OpenSSH temporary mux path must fit sockaddr_un.sun_path: " + controlPath,
            configuredBytes + OPENSSH_TEMPORARY_CONTROL_PATH_SUFFIX_BYTES <
                UNIX_DOMAIN_SOCKET_PATH_BYTES);
    }

    private static String onlyControlPath(String command) {
        List<String> paths = controlPaths(command);
        Assert.assertEquals(command, 1, paths.size());
        return paths.get(0);
    }

    private static String onlyDistinctControlPath(String command) {
        List<String> paths = controlPaths(command);
        Assert.assertEquals(command, 3, paths.size());
        Assert.assertEquals(command, paths.get(0), paths.get(1));
        Assert.assertEquals(command, paths.get(0), paths.get(2));
        return paths.get(0);
    }

    private static List<String> controlPaths(String command) {
        ArrayList<String> paths = new ArrayList<>();
        Matcher matcher = CONTROL_PATH_PATTERN.matcher(command);
        while (matcher.find()) paths.add(matcher.group(1));
        return paths;
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
