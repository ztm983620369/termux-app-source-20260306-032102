package com.termux.terminalsessioncore;

import org.junit.Assert;
import org.junit.Test;

public class TerminalSessionTabStateMachineTest {

    @Test
    public void pinnedRunningSessionUsesPersistentToneAndBadge() {
        TerminalSessionTabStateMachine.Snapshot snapshot = TerminalSessionTabStateMachine.resolve(
            new TerminalSessionTabStateMachine.Input(
                false,
                true,
                true,
                0,
                "prod-api",
                "ssh-persistent-1",
                "bash",
                TerminalSessionTabStateMachine.TransportKind.SSH_PERSIST,
                TerminalSessionTabStateMachine.RuntimeState.IDLE));

        Assert.assertEquals("prod-api", snapshot.title);
        Assert.assertEquals(TerminalSessionTabStateMachine.AccentTone.PERSISTENT, snapshot.accentTone);
        Assert.assertEquals(TerminalSessionTabStateMachine.BadgeKind.PIN, snapshot.badgeKind);
        Assert.assertFalse(snapshot.closable);
        Assert.assertTrue(snapshot.locked);
    }

    @Test
    public void busyRuntimeOverridesRemoteBadge() {
        TerminalSessionTabStateMachine.Snapshot snapshot = TerminalSessionTabStateMachine.resolve(
            new TerminalSessionTabStateMachine.Input(
                true,
                false,
                true,
                0,
                null,
                "ssh",
                "server",
                TerminalSessionTabStateMachine.TransportKind.SSH,
                TerminalSessionTabStateMachine.RuntimeState.BUSY));

        Assert.assertEquals(TerminalSessionTabStateMachine.AccentTone.BUSY, snapshot.accentTone);
        Assert.assertEquals(TerminalSessionTabStateMachine.BadgeKind.BUSY, snapshot.badgeKind);
        Assert.assertEquals(TerminalSessionTabStateMachine.LifecycleState.RUNNING, snapshot.lifecycleState);
    }

    @Test
    public void exitedZeroUsesDoneBadge() {
        TerminalSessionTabStateMachine.Snapshot snapshot = TerminalSessionTabStateMachine.resolve(
            new TerminalSessionTabStateMachine.Input(
                false,
                false,
                false,
                0,
                null,
                "build",
                "build",
                TerminalSessionTabStateMachine.TransportKind.LOCAL,
                TerminalSessionTabStateMachine.RuntimeState.IDLE));

        Assert.assertEquals(TerminalSessionTabStateMachine.LifecycleState.EXITED_SUCCESS, snapshot.lifecycleState);
        Assert.assertEquals(TerminalSessionTabStateMachine.AccentTone.SUCCESS, snapshot.accentTone);
        Assert.assertEquals(TerminalSessionTabStateMachine.BadgeKind.DONE, snapshot.badgeKind);
    }

    @Test
    public void runtimeFailureHasPriorityOverPersistentState() {
        TerminalSessionTabStateMachine.Snapshot snapshot = TerminalSessionTabStateMachine.resolve(
            new TerminalSessionTabStateMachine.Input(
                false,
                true,
                true,
                0,
                "prod-api",
                "ssh-persistent-1",
                "bash",
                TerminalSessionTabStateMachine.TransportKind.SSH_PERSIST,
                TerminalSessionTabStateMachine.RuntimeState.FAILED));

        Assert.assertEquals(TerminalSessionTabStateMachine.AccentTone.ERROR, snapshot.accentTone);
        Assert.assertEquals(TerminalSessionTabStateMachine.BadgeKind.ERROR, snapshot.badgeKind);
    }
}
