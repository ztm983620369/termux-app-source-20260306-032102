package com.termux.sshconnectioncore;

import org.junit.Assert;
import org.junit.Test;

public class SshConnectionOrchestratorStateMachineTest {

    @Test
    public void readyPathCarriesIntentEngineAndEndpoint() {
        SshConnectionOrchestratorStateMachine machine = new SshConnectionOrchestratorStateMachine();
        ResolvedSshEndpoint endpoint = new ResolvedSshEndpoint.Builder()
            .setProfileId("p-terminal")
            .setHost("host-a")
            .setHostIdentity("host-a")
            .setUser("root")
            .setPort(22)
            .build();

        machine.apply(SshConnectionOrchestratorStateMachine.Event.begin(
            SshConnectionIntent.TERMINAL_INTERACTIVE, SshConnectionEngine.OPENSSH, 10L));
        machine.apply(SshConnectionOrchestratorStateMachine.Event.profileResolved(endpoint, 20L));
        machine.apply(SshConnectionOrchestratorStateMachine.Event.trustEvaluating(endpoint, 30L));
        machine.apply(SshConnectionOrchestratorStateMachine.Event.trustReady(40L));
        machine.apply(SshConnectionOrchestratorStateMachine.Event.engineSelected(
            SshConnectionEngine.OPENSSH, "terminal path", 50L));
        machine.apply(SshConnectionOrchestratorStateMachine.Event.connecting(1, "dialing", 60L));
        machine.apply(SshConnectionOrchestratorStateMachine.Event.ready("connected", 70L));

        SshConnectionOrchestratorStateMachine.Snapshot snapshot = machine.snapshot();
        Assert.assertEquals(SshConnectionOrchestratorStateMachine.Phase.READY, snapshot.phase);
        Assert.assertEquals(SshConnectionIntent.TERMINAL_INTERACTIVE, snapshot.intent);
        Assert.assertEquals(SshConnectionEngine.OPENSSH, snapshot.activeEngine);
        Assert.assertEquals(endpoint, snapshot.endpoint);
    }

    @Test
    public void trustConflictBlocksBeforeConnect() {
        SshConnectionOrchestratorStateMachine machine = new SshConnectionOrchestratorStateMachine();
        ResolvedSshEndpoint endpoint = new ResolvedSshEndpoint.Builder()
            .setProfileId("p-file")
            .setHost("host-b")
            .setHostIdentity("host-b")
            .setUser("root")
            .setPort(2222)
            .build();

        machine.apply(SshConnectionOrchestratorStateMachine.Event.begin(
            SshConnectionIntent.FILE_BROWSE, SshConnectionEngine.JSCH_SFTP, 10L));
        machine.apply(SshConnectionOrchestratorStateMachine.Event.profileResolved(endpoint, 20L));
        machine.apply(SshConnectionOrchestratorStateMachine.Event.trustEvaluating(endpoint, 30L));
        machine.apply(SshConnectionOrchestratorStateMachine.Event.trustBlocked(
            SshConnectionFailureCategory.TRUST_CONFLICT,
            "fingerprint mismatch",
            SshControlAction.REPLACE_TRUST,
            40L
        ));

        SshConnectionOrchestratorStateMachine.Snapshot snapshot = machine.snapshot();
        Assert.assertEquals(SshConnectionOrchestratorStateMachine.Phase.TRUST_BLOCKED, snapshot.phase);
        Assert.assertEquals(SshConnectionFailureCategory.TRUST_CONFLICT, snapshot.failureCategory);
        Assert.assertEquals(SshControlAction.REPLACE_TRUST, snapshot.suggestedAction);
    }

    @Test
    public void engineMismatchFailureSuggestsFallbackToOpenSsh() {
        SshConnectionOrchestratorStateMachine machine = new SshConnectionOrchestratorStateMachine();

        machine.apply(SshConnectionOrchestratorStateMachine.Event.begin(
            SshConnectionIntent.FILE_BROWSE, SshConnectionEngine.JSCH_SFTP, 10L));
        machine.apply(SshConnectionOrchestratorStateMachine.Event.failed(
            SshConnectionFailureCategory.ENGINE_MISMATCH,
            "profile uses unsupported openSSH options",
            SshControlAction.FALLBACK_TO_OPENSSH,
            1,
            20L
        ));

        SshConnectionOrchestratorStateMachine.Snapshot snapshot = machine.snapshot();
        Assert.assertEquals(SshConnectionOrchestratorStateMachine.Phase.FAILED, snapshot.phase);
        Assert.assertEquals(SshConnectionFailureCategory.ENGINE_MISMATCH, snapshot.failureCategory);
        Assert.assertEquals(SshControlAction.FALLBACK_TO_OPENSSH, snapshot.suggestedAction);
    }

    @Test
    public void invalidProfileFailureSuggestsEditor() {
        SshConnectionOrchestratorStateMachine machine = new SshConnectionOrchestratorStateMachine();

        machine.apply(SshConnectionOrchestratorStateMachine.Event.begin(
            SshConnectionIntent.REMOTE_MOUNT, SshConnectionEngine.SSHFS, 10L));
        machine.apply(SshConnectionOrchestratorStateMachine.Event.profileInvalid(
            SshConnectionFailureCategory.INVALID_PROFILE,
            "missing username",
            SshControlAction.OPEN_PROFILE_EDITOR,
            20L
        ));

        Assert.assertEquals(SshConnectionOrchestratorStateMachine.Phase.FAILED, machine.snapshot().phase);
        Assert.assertEquals(SshControlAction.OPEN_PROFILE_EDITOR, machine.snapshot().suggestedAction);
    }
}
