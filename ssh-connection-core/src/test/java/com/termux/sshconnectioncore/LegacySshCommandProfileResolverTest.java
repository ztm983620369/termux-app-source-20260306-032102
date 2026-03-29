package com.termux.sshconnectioncore;

import org.junit.Assert;
import org.junit.Test;

public class LegacySshCommandProfileResolverTest {

    @Test
    public void resolvesAuthorityUsingPortAndHostKeyAlias() {
        SshProfileResolutionResult result = LegacySshCommandProfileResolver.resolve(
            "p1",
            "ssh -p 2222 -o HostKeyAlias=prod-bastion -o UserKnownHostsFile=/data/known_hosts root@example.com"
        );

        Assert.assertTrue(result.success);
        Assert.assertNotNull(result.endpoint);
        Assert.assertEquals("ssh://prod-bastion:2222", result.endpoint.authorityKey);
        Assert.assertEquals("prod-bastion", result.endpoint.hostIdentity);
        Assert.assertEquals("/data/known_hosts", result.endpoint.userKnownHostsPath);
    }

    @Test
    public void resolvesStrictHostKeyCheckingModeAndIdentityFile() {
        SshProfileResolutionResult result = LegacySshCommandProfileResolver.resolve(
            "p2",
            "ssh -i ~/.ssh/id_ed25519 -o StrictHostKeyChecking=accept-new dev@10.0.0.8"
        );

        Assert.assertTrue(result.success);
        Assert.assertEquals(ResolvedSshEndpoint.HostKeyVerificationMode.ACCEPT_NEW,
            result.endpoint.hostKeyVerificationMode);
        Assert.assertEquals("~/.ssh/id_ed25519", result.endpoint.identityPath);
        Assert.assertEquals("dev", result.endpoint.user);
        Assert.assertEquals("10.0.0.8", result.endpoint.host);
    }

    @Test
    public void missingUserFailsValidation() {
        SshProfileResolutionResult result = LegacySshCommandProfileResolver.resolve(
            "p3",
            "ssh example.com"
        );

        Assert.assertFalse(result.success);
        Assert.assertEquals(SshConnectionFailureCategory.INVALID_PROFILE, result.failureCategory);
    }
}
