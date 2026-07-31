package com.termux.sshconnectioncore;

import org.junit.Assert;
import org.junit.Test;

public class SshCommandKnownHostsOptionsTest {

    @Test
    public void injectsManagedKnownHostsBeforeDestination() {
        String command = "ssh -p 2222 root@example.com";
        String injected = SshCommandKnownHostsOptions.inject(command, "/tmp/managed_known_hosts");

        Assert.assertTrue(injected.contains("UserKnownHostsFile=/tmp/managed_known_hosts"));
        Assert.assertTrue(injected.contains("GlobalKnownHostsFile=/dev/null"));
        Assert.assertTrue(injected.contains("HashKnownHosts=no"));
        Assert.assertTrue(injected.endsWith("root@example.com"));
    }

    @Test
    public void preservesSshpassPrefixAndReplacesExistingKnownHostsOptions() {
        String command = "sshpass -p secret ssh -o UserKnownHostsFile=/old/path -o HashKnownHosts=yes root@example.com";
        String injected = SshCommandKnownHostsOptions.inject(command, "/tmp/new_path");

        Assert.assertTrue(injected.startsWith("sshpass -p secret ssh"));
        Assert.assertTrue(injected.contains("UserKnownHostsFile=/tmp/new_path"));
        Assert.assertFalse(injected.contains("UserKnownHostsFile=/old/path"));
        Assert.assertFalse(injected.contains("HashKnownHosts=yes"));
        Assert.assertTrue(injected.contains("StrictHostKeyChecking=yes"));
        Assert.assertTrue(injected.contains("KnownHostsCommand=none"));
        Assert.assertTrue(injected.contains("VerifyHostKeyDNS=no"));
    }

    @Test
    public void replacesSpaceFormAndUnsafeStrictHostKeyChecking() {
        String command = "ssh -o 'UserKnownHostsFile /old path' "
            + "-o StrictHostKeyChecking=no -o UpdateHostKeys=yes "
            + "-o CheckHostIP=yes root@example.com 'echo ok'";
        String injected = SshCommandKnownHostsOptions.inject(command, "/tmp/managed path");

        Assert.assertFalse(injected.contains("/old path"));
        Assert.assertFalse(injected.contains("StrictHostKeyChecking=no"));
        Assert.assertFalse(injected.contains("UpdateHostKeys=yes"));
        Assert.assertFalse(injected.contains("CheckHostIP=yes"));
        Assert.assertTrue(injected.contains("'UserKnownHostsFile=/tmp/managed path'"));
        Assert.assertTrue(injected.contains("StrictHostKeyChecking=yes"));
        Assert.assertTrue(injected.contains("UpdateHostKeys=no"));
        Assert.assertTrue(injected.contains("CheckHostIP=no"));
        Assert.assertTrue(injected.endsWith("root@example.com 'echo ok'"));
    }

    @Test
    public void rejectsCommandsThatAreNotAStrictOpenSshInvocation() {
        try {
            SshCommandKnownHostsOptions.inject("printf ssh; ssh root@example.com", "/tmp/known_hosts");
            Assert.fail("Expected malformed shell program to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test
    public void failsClosedWhenManagedPathIsMissing() {
        try {
            SshCommandKnownHostsOptions.inject("ssh root@example.com", "");
            Assert.fail("Expected missing trust path to fail closed");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
