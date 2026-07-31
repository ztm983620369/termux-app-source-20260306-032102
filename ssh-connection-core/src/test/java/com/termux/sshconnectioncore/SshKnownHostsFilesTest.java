package com.termux.sshconnectioncore;

import org.junit.Assert;
import org.junit.Test;

public class SshKnownHostsFilesTest {

    @Test
    public void rejectsPatternSyntaxInHostIdentity() {
        ResolvedSshEndpoint endpoint = new ResolvedSshEndpoint.Builder()
            .setHost("example.com")
            .setHostIdentity("*.example.com")
            .setUser("root")
            .build();

        Assert.assertEquals("", SshKnownHostsFiles.buildKnownHostsHostPattern(endpoint));
    }

    @Test
    public void formatsIpv6AndNonDefaultPortAsLiteralHostPattern() {
        ResolvedSshEndpoint endpoint = new ResolvedSshEndpoint.Builder()
            .setHost("2001:db8::1")
            .setHostIdentity("2001:db8::1")
            .setPort(2222)
            .setUser("root")
            .build();

        Assert.assertEquals("[2001:db8::1]:2222",
            SshKnownHostsFiles.buildKnownHostsHostPattern(endpoint));
    }

    @Test
    public void leavesDefaultPortIpv6UnbracketedLikeOpenSsh() {
        ResolvedSshEndpoint endpoint = new ResolvedSshEndpoint.Builder()
            .setHost("2001:db8::1")
            .setHostIdentity("2001:db8::1")
            .setPort(22)
            .build();

        Assert.assertEquals("2001:db8::1",
            SshKnownHostsFiles.buildKnownHostsHostPattern(endpoint));
    }

    @Test
    public void hostKeyAliasIsVerbatimAndNotPortQualified() {
        ResolvedSshEndpoint endpoint = new ResolvedSshEndpoint.Builder()
            .setHost("example.com")
            .setHostIdentity("prod-bastion")
            .setPort(2222)
            .setUsesHostKeyAlias(true)
            .build();

        Assert.assertEquals("prod-bastion",
            SshKnownHostsFiles.buildKnownHostsHostPattern(endpoint));
        Assert.assertEquals("ssh-hostkeyalias://prod-bastion", endpoint.authorityKey);
    }
}
