package com.termux.sshconnectioncore;

import org.junit.Assert;
import org.junit.Test;

public class SshTrustStateMachineTest {

    private static final String FINGERPRINT_OLD =
        "SHA256:rksygOVuL6+D9BSm49q+nV++GJdlRMBf7RIazLhbU/w";
    private static final String FINGERPRINT_NEW =
        "SHA256:KEhpiqSzQx49sGw0PKLLBFX4qvFshc3YKMkt333BNPg";

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

        machine.apply(SshTrustStateMachine.Event.observeHostKey("ssh-ed25519", FINGERPRINT_NEW, 120L));
        Assert.assertEquals(SshTrustStateMachine.State.TRUST_PENDING_APPROVAL, machine.snapshot().state);
        Assert.assertEquals(SshControlAction.APPROVE_TRUST, machine.snapshot().suggestedAction);

        machine.apply(SshTrustStateMachine.Event.approvePending(SshTrustSource.USER_APPROVED, 140L));
        Assert.assertEquals(SshTrustStateMachine.State.TRUST_MATCHED, machine.snapshot().state);
        Assert.assertNotNull(machine.snapshot().effectiveRecord);
        Assert.assertEquals(FINGERPRINT_NEW, machine.snapshot().effectiveRecord.fingerprintSha256);
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
            FINGERPRINT_OLD,
            SshTrustSource.IMPORTED_OPENSSH,
            10L,
            20L
        );

        machine.apply(SshTrustStateMachine.Event.beginEvaluation(endpoint, stored, 100L));
        machine.apply(SshTrustStateMachine.Event.observeHostKey("ssh-ed25519", FINGERPRINT_NEW, 120L));

        Assert.assertEquals(SshTrustStateMachine.State.TRUST_CONFLICT, machine.snapshot().state);
        Assert.assertEquals(SshControlAction.REPLACE_TRUST, machine.snapshot().suggestedAction);

        machine.apply(SshTrustStateMachine.Event.replaceTrust(SshTrustSource.USER_REPLACED, 150L));
        Assert.assertEquals(SshTrustStateMachine.State.TRUST_MATCHED, machine.snapshot().state);
        Assert.assertEquals(FINGERPRINT_NEW, machine.snapshot().effectiveRecord.fingerprintSha256);
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
            FINGERPRINT_OLD,
            SshTrustSource.IMPORTED_APP_STORE,
            10L,
            20L
        );

        machine.apply(SshTrustStateMachine.Event.beginEvaluation(endpoint, stored, 100L));
        machine.apply(SshTrustStateMachine.Event.observeHostKey("ssh-ed25519", FINGERPRINT_NEW, 120L));
        machine.apply(SshTrustStateMachine.Event.clearTrust("manual clear", 130L));

        Assert.assertEquals(SshTrustStateMachine.State.TRUST_CLEARED, machine.snapshot().state);
        Assert.assertNull(machine.snapshot().effectiveRecord);
        Assert.assertEquals(SshControlAction.RETRY, machine.snapshot().suggestedAction);
    }

    @Test
    public void ignoresStoredRecordFromAnotherEndpoint() {
        SshTrustStateMachine machine = new SshTrustStateMachine();
        ResolvedSshEndpoint endpoint = new ResolvedSshEndpoint.Builder()
            .setHost("server-a.example.com")
            .setHostIdentity("server-a.example.com")
            .setUser("root")
            .build();
        SshTrustRecord wrongEndpoint = new SshTrustRecord(
            "ssh://server-b.example.com:22",
            "server-b.example.com",
            22,
            "ssh-ed25519",
            FINGERPRINT_OLD,
            SshTrustSource.IMPORTED_OPENSSH,
            1L,
            1L
        );

        machine.apply(SshTrustStateMachine.Event.beginEvaluation(endpoint, wrongEndpoint, 10L));
        Assert.assertEquals(SshTrustStateMachine.State.TRUST_ABSENT, machine.snapshot().state);
        machine.apply(SshTrustStateMachine.Event.observeHostKey("ssh-ed25519", FINGERPRINT_OLD, 20L));
        Assert.assertEquals(SshTrustStateMachine.State.TRUST_PENDING_APPROVAL, machine.snapshot().state);
    }

    @Test
    public void doesNotMatchEmptyHostKeyEvidence() {
        SshTrustStateMachine machine = new SshTrustStateMachine();
        ResolvedSshEndpoint endpoint = new ResolvedSshEndpoint.Builder()
            .setHost("server.example.com")
            .setHostIdentity("server.example.com")
            .setUser("root")
            .build();
        SshTrustRecord empty = new SshTrustRecord(
            endpoint.authorityKey,
            endpoint.hostIdentity,
            endpoint.port,
            "",
            "",
            SshTrustSource.IMPORTED_OPENSSH,
            1L,
            1L
        );

        machine.apply(SshTrustStateMachine.Event.beginEvaluation(endpoint, empty, 10L));
        machine.apply(SshTrustStateMachine.Event.observeHostKey("", "", 20L));
        Assert.assertEquals(SshTrustStateMachine.State.TRUST_ABSENT, machine.snapshot().state);
        Assert.assertNull(machine.snapshot().effectiveRecord);
    }

    @Test
    public void base64FingerprintPayloadRemainsCaseSensitive() {
        SshTrustRecord record = new SshTrustRecord(
            "ssh://server.example.com:22", "server.example.com", 22,
            "ssh-ed25519", FINGERPRINT_OLD, SshTrustSource.USER_APPROVED, 1L, 1L);

        String caseChanged = "SHA256:RksygOVuL6+D9BSm49q+nV++GJdlRMBf7RIazLhbU/w";
        Assert.assertTrue(SshHostKeyFingerprint.isValidSha256(caseChanged));
        Assert.assertFalse(record.matchesObserved("ssh-ed25519", caseChanged));
    }
}
