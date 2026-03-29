package com.termux.sshconnectioncore;

import org.junit.Assert;
import org.junit.Test;

public class SshTrustStateMachineTest {

    @Test
    public void newHostTransitionsToPendingApprovalUntilApproved() {
        SshTrustStateMachine machine = new SshTrustStateMachine();
        ResolvedSshEndpoint endpoint = new ResolvedSshEndpoint.Builder()
            .setProfileId("p1")
            .setHost("server.example.com")
            .setHostIdentity("server.example.com")
            .setUser("root")
            .setPort(22)
            .build();

        machine.apply(SshTrustStateMachine.Event.beginEvaluation(endpoint, null, 100L));
        Assert.assertEquals(SshTrustStateMachine.State.TRUST_ABSENT, machine.snapshot().state);

        machine.apply(SshTrustStateMachine.Event.observeHostKey("ssh-ed25519", "sha256:new", 120L));
        Assert.assertEquals(SshTrustStateMachine.State.TRUST_PENDING_APPROVAL, machine.snapshot().state);
        Assert.assertEquals(SshControlAction.APPROVE_TRUST, machine.snapshot().suggestedAction);

        machine.apply(SshTrustStateMachine.Event.approvePending(SshTrustSource.USER_APPROVED, 140L));
        Assert.assertEquals(SshTrustStateMachine.State.TRUST_MATCHED, machine.snapshot().state);
        Assert.assertNotNull(machine.snapshot().effectiveRecord);
        Assert.assertEquals("sha256:new", machine.snapshot().effectiveRecord.fingerprintSha256);
    }

    @Test
    public void changedHostKeyTransitionsToConflictAndSupportsReplacement() {
        SshTrustStateMachine machine = new SshTrustStateMachine();
        ResolvedSshEndpoint endpoint = new ResolvedSshEndpoint.Builder()
            .setProfileId("p1")
            .setHost("server.example.com")
            .setHostIdentity("server.example.com")
            .setUser("root")
            .setPort(22)
            .build();
        SshTrustRecord stored = new SshTrustRecord(
            endpoint.authorityKey,
            endpoint.hostIdentity,
            endpoint.port,
            "ssh-ed25519",
            "sha256:old",
            SshTrustSource.IMPORTED_OPENSSH,
            10L,
            20L
        );

        machine.apply(SshTrustStateMachine.Event.beginEvaluation(endpoint, stored, 100L));
        machine.apply(SshTrustStateMachine.Event.observeHostKey("ssh-ed25519", "sha256:new", 120L));

        Assert.assertEquals(SshTrustStateMachine.State.TRUST_CONFLICT, machine.snapshot().state);
        Assert.assertEquals(SshControlAction.REPLACE_TRUST, machine.snapshot().suggestedAction);

        machine.apply(SshTrustStateMachine.Event.replaceTrust(SshTrustSource.USER_REPLACED, 150L));
        Assert.assertEquals(SshTrustStateMachine.State.TRUST_MATCHED, machine.snapshot().state);
        Assert.assertEquals("sha256:new", machine.snapshot().effectiveRecord.fingerprintSha256);
        Assert.assertEquals(SshTrustSource.USER_REPLACED, machine.snapshot().effectiveRecord.source);
    }

    @Test
    public void clearingTrustAfterConflictSuggestsRetry() {
        SshTrustStateMachine machine = new SshTrustStateMachine();
        ResolvedSshEndpoint endpoint = new ResolvedSshEndpoint.Builder()
            .setProfileId("p1")
            .setHost("server.example.com")
            .setHostIdentity("server.example.com")
            .setUser("root")
            .setPort(22)
            .build();
        SshTrustRecord stored = new SshTrustRecord(
            endpoint.authorityKey,
            endpoint.hostIdentity,
            endpoint.port,
            "ssh-ed25519",
            "sha256:old",
            SshTrustSource.IMPORTED_APP_STORE,
            10L,
            20L
        );

        machine.apply(SshTrustStateMachine.Event.beginEvaluation(endpoint, stored, 100L));
        machine.apply(SshTrustStateMachine.Event.observeHostKey("ssh-ed25519", "sha256:new", 120L));
        machine.apply(SshTrustStateMachine.Event.clearTrust("manual clear", 130L));

        Assert.assertEquals(SshTrustStateMachine.State.TRUST_CLEARED, machine.snapshot().state);
        Assert.assertNull(machine.snapshot().effectiveRecord);
        Assert.assertEquals(SshControlAction.RETRY, machine.snapshot().suggestedAction);
    }
}
