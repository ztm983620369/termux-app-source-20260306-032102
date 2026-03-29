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
    }
}
