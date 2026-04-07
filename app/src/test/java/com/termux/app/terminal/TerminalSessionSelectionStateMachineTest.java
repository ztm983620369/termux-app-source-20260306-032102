package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class TerminalSessionSelectionStateMachineTest {

    @Test
    public void requestSessionSelectionCreatesPendingTokenAndCurrentHandle() {
        TerminalSessionSelectionStateMachine machine = createMachine("local", "ssh-a");

        long token = machine.requestSessionSelection("ssh-a");
        TerminalSessionSelectionStateMachine.Snapshot snapshot = machine.snapshot();

        Assert.assertTrue(token > 0L);
        Assert.assertEquals(TerminalSessionSelectionStateMachine.PendingKind.SESSION, snapshot.pendingKind);
        Assert.assertEquals(token, snapshot.pendingToken);
        Assert.assertEquals("ssh-a", snapshot.pendingSessionHandle);
        Assert.assertEquals("ssh-a", snapshot.currentSessionHandle);
        Assert.assertFalse(snapshot.configSelected);
    }

    @Test
    public void newerRequestIgnoresStaleProgrammaticCommit() {
        TerminalSessionSelectionStateMachine machine = createMachine("local", "ssh-a", "ssh-b");

        long firstToken = machine.requestSessionSelection("ssh-a");
        long secondToken = machine.requestSessionSelection("ssh-b");

        Assert.assertFalse(machine.commitSessionSelection("ssh-a", firstToken, false));

        TerminalSessionSelectionStateMachine.Snapshot snapshot = machine.snapshot();
        Assert.assertEquals(TerminalSessionSelectionStateMachine.PendingKind.SESSION, snapshot.pendingKind);
        Assert.assertEquals(secondToken, snapshot.pendingToken);
        Assert.assertEquals("ssh-b", snapshot.currentSessionHandle);
    }

    @Test
    public void matchingProgrammaticCommitClearsPendingAndCommitsSelection() {
        TerminalSessionSelectionStateMachine machine = createMachine("local", "ssh-a");

        long token = machine.requestSessionSelection("ssh-a");

        Assert.assertTrue(machine.commitSessionSelection("ssh-a", token, false));

        TerminalSessionSelectionStateMachine.Snapshot snapshot = machine.snapshot();
        Assert.assertEquals(TerminalSessionSelectionStateMachine.PendingKind.NONE, snapshot.pendingKind);
        Assert.assertEquals("ssh-a", snapshot.selectedSessionHandle);
        Assert.assertEquals("ssh-a", snapshot.committedSessionHandle);
        Assert.assertEquals("ssh-a", snapshot.currentSessionHandle);
    }

    @Test
    public void userCommitOverridesPendingProgrammaticSelection() {
        TerminalSessionSelectionStateMachine machine = createMachine("local", "ssh-a", "ssh-b");

        machine.requestSessionSelection("ssh-a");

        Assert.assertTrue(machine.commitSessionSelection("ssh-b", 0L, true));

        TerminalSessionSelectionStateMachine.Snapshot snapshot = machine.snapshot();
        Assert.assertEquals(TerminalSessionSelectionStateMachine.PendingKind.NONE, snapshot.pendingKind);
        Assert.assertEquals("ssh-b", snapshot.selectedSessionHandle);
        Assert.assertEquals("ssh-b", snapshot.currentSessionHandle);
    }

    @Test
    public void configRequestKeepsUnderlyingSelectionButMarksConfigSelected() {
        TerminalSessionSelectionStateMachine machine = createMachine("local", "ssh-a");
        machine.bootstrapSessionSelection("ssh-a");

        long token = machine.requestConfigSelection();
        TerminalSessionSelectionStateMachine.Snapshot snapshot = machine.snapshot();

        Assert.assertEquals(token, snapshot.pendingToken);
        Assert.assertEquals(TerminalSessionSelectionStateMachine.PendingKind.CONFIG, snapshot.pendingKind);
        Assert.assertTrue(snapshot.configSelected);
        Assert.assertTrue(snapshot.topBarConfigSelected);
        Assert.assertNull(snapshot.topBarSelectedSessionHandle);
        Assert.assertEquals("ssh-a", snapshot.currentSessionHandle);
    }

    @Test
    public void staleSessionCommitIgnoredWhileConfigSelectionPending() {
        TerminalSessionSelectionStateMachine machine = createMachine("local", "ssh-a");
        machine.bootstrapSessionSelection("ssh-a");
        machine.requestConfigSelection();

        Assert.assertFalse(machine.commitSessionSelection("ssh-a", 77L, false));

        TerminalSessionSelectionStateMachine.Snapshot snapshot = machine.snapshot();
        Assert.assertTrue(snapshot.configSelected);
        Assert.assertEquals(TerminalSessionSelectionStateMachine.PendingKind.CONFIG, snapshot.pendingKind);
    }

    @Test
    public void syncSessionsFallsBackToAvailablePreferredOrFirstHandle() {
        TerminalSessionSelectionStateMachine machine = new TerminalSessionSelectionStateMachine();
        machine.restore("missing", false);

        machine.syncSessions(Arrays.asList("alpha", "beta"), "beta");

        TerminalSessionSelectionStateMachine.Snapshot snapshot = machine.snapshot();
        Assert.assertEquals("beta", snapshot.selectedSessionHandle);
        Assert.assertEquals("beta", snapshot.currentSessionHandle);
    }

    @Test
    public void previewOnlyAffectsTopBarSelection() {
        TerminalSessionSelectionStateMachine machine = createMachine("local", "ssh-a");
        machine.bootstrapSessionSelection("local");
        machine.previewSession("ssh-a");

        TerminalSessionSelectionStateMachine.Snapshot snapshot = machine.snapshot();
        Assert.assertEquals("local", snapshot.currentSessionHandle);
        Assert.assertEquals("ssh-a", snapshot.topBarSelectedSessionHandle);
        Assert.assertFalse(snapshot.topBarConfigSelected);
    }

    @Test
    public void requestReturnToSessionLeavesConfigModeWithoutSessions() {
        TerminalSessionSelectionStateMachine machine = new TerminalSessionSelectionStateMachine();
        machine.restore(null, true);
        machine.syncSessions(Collections.emptyList(), null);

        long token = machine.requestReturnToSessionSelection();
        TerminalSessionSelectionStateMachine.Snapshot snapshot = machine.snapshot();

        Assert.assertEquals(0L, token);
        Assert.assertFalse(snapshot.configSelected);
        Assert.assertEquals(TerminalSessionSelectionStateMachine.PendingKind.NONE, snapshot.pendingKind);
    }

    private TerminalSessionSelectionStateMachine createMachine(String... handles) {
        TerminalSessionSelectionStateMachine machine = new TerminalSessionSelectionStateMachine();
        machine.syncSessions(Arrays.asList(handles), null);
        return machine;
    }
}
