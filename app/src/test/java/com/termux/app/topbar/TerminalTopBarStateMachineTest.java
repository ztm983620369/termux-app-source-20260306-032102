package com.termux.app.topbar;

import org.junit.Assert;
import org.junit.Test;

public class TerminalTopBarStateMachineTest {

    @Test
    public void pinnedRunningSessionUsesPersistentToneAndBadge() {
        TerminalTopBarStateMachine.Snapshot snapshot = TerminalTopBarStateMachine.resolve(
            new TerminalTopBarStateMachine.Input(
                false,
                true,
                true,
                0,
                "prod-api",
                "ssh-persistent-1",
                "bash",
                TerminalTopBarStateMachine.TransportKind.SSH_PERSIST,
                TerminalTopBarRuntimeState.IDLE));

        Assert.assertEquals("prod-api", snapshot.title);
        Assert.assertEquals(TerminalTopBarStateMachine.Tone.PERSISTENT, snapshot.tone);
        Assert.assertEquals(TerminalTopBarStateMachine.BadgeKind.PIN, snapshot.badgeKind);
        Assert.assertFalse(snapshot.closable);
        Assert.assertTrue(snapshot.locked);
    }

    @Test
    public void busyRuntimeOverridesRemoteBadge() {
        TerminalTopBarStateMachine.Snapshot snapshot = TerminalTopBarStateMachine.resolve(
            new TerminalTopBarStateMachine.Input(
                true,
                false,
                true,
                0,
                null,
                "ssh",
                "server",
                TerminalTopBarStateMachine.TransportKind.SSH,
                TerminalTopBarRuntimeState.BUSY));

        Assert.assertEquals(TerminalTopBarStateMachine.Tone.BUSY, snapshot.tone);
        Assert.assertEquals(TerminalTopBarStateMachine.BadgeKind.BUSY, snapshot.badgeKind);
        Assert.assertEquals(TerminalTopBarStateMachine.LifecycleState.RUNNING, snapshot.lifecycleState);
    }

    @Test
    public void exitedZeroUsesDoneBadge() {
        TerminalTopBarStateMachine.Snapshot snapshot = TerminalTopBarStateMachine.resolve(
            new TerminalTopBarStateMachine.Input(
                false,
                false,
                false,
                0,
                null,
                "build",
                "build",
                TerminalTopBarStateMachine.TransportKind.LOCAL,
                TerminalTopBarRuntimeState.IDLE));

        Assert.assertEquals(TerminalTopBarStateMachine.LifecycleState.EXITED_SUCCESS, snapshot.lifecycleState);
        Assert.assertEquals(TerminalTopBarStateMachine.Tone.SUCCESS, snapshot.tone);
        Assert.assertEquals(TerminalTopBarStateMachine.BadgeKind.DONE, snapshot.badgeKind);
    }

    @Test
    public void runtimeFailureHasPriorityOverPersistentState() {
        TerminalTopBarStateMachine.Snapshot snapshot = TerminalTopBarStateMachine.resolve(
            new TerminalTopBarStateMachine.Input(
                false,
                true,
                true,
                0,
                "prod-api",
                "ssh-persistent-1",
                "bash",
                TerminalTopBarStateMachine.TransportKind.SSH_PERSIST,
                TerminalTopBarRuntimeState.FAILED));

        Assert.assertEquals(TerminalTopBarStateMachine.Tone.ERROR, snapshot.tone);
        Assert.assertEquals(TerminalTopBarStateMachine.BadgeKind.ERROR, snapshot.badgeKind);
    }
}
