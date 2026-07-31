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
        Assert.assertEquals("ssh-hostkeyalias://prod-bastion", result.endpoint.authorityKey);
        Assert.assertEquals("prod-bastion", result.endpoint.hostIdentity);
        Assert.assertTrue(result.endpoint.usesHostKeyAlias);
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
        Assert.assertFalse(result.endpoint.usesHostKeyAlias);
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

    @Test
    public void doesNotTreatSshpassPasswordAsTheSshExecutable() {
        SshProfileResolutionResult result = LegacySshCommandProfileResolver.resolve(
            "p4", "sshpass -p ssh ssh -p 2200 dev@example.com");

        Assert.assertTrue(result.success);
        Assert.assertEquals("example.com", result.endpoint.host);
        Assert.assertEquals(2200, result.endpoint.port);
        Assert.assertEquals("dev", result.endpoint.user);
    }

    @Test
    public void parsesQuotedOpenSshOptionValue() {
        SshProfileResolutionResult result = LegacySshCommandProfileResolver.resolve(
            "p5", "ssh -o 'StrictHostKeyChecking no' -o 'HostKeyAlias bastion' root@example.com");

        Assert.assertTrue(result.success);
        Assert.assertEquals(ResolvedSshEndpoint.HostKeyVerificationMode.NO,
            result.endpoint.hostKeyVerificationMode);
        Assert.assertEquals("bastion", result.endpoint.hostIdentity);
    }

    @Test
    public void rejectsShellExpansionAndWildcardHostIdentity() {
        Assert.assertFalse(LegacySshCommandProfileResolver.resolve(
            "p6", "ssh root@$TARGET").success);
        Assert.assertFalse(LegacySshCommandProfileResolver.resolve(
            "p7", "ssh -o HostKeyAlias='*' root@example.com").success);
    }
}
