package com.termux.terminalsessioncore;

import org.junit.Assert;
import org.junit.Test;

public class CodexRestoreStateMachineTest {

    @Test
    public void durableReadyUpsertsMapping() {
        CodexRestoreStateMachine.HostAction action = CodexRestoreStateMachine.resolveHostEvent(
            new CodexRestoreStateMachine.HostEventInput(
                CodexRestoreStateMachine.HostEvent.READY,
                true,
                true,
                false,
                false));

        Assert.assertEquals(CodexRestoreStateMachine.HostAction.UPSERT, action);
    }

    @Test
    public void readyBeforeRolloutDurabilityIsIgnored() {
        CodexRestoreStateMachine.HostAction action = CodexRestoreStateMachine.resolveHostEvent(
            new CodexRestoreStateMachine.HostEventInput(
                CodexRestoreStateMachine.HostEvent.READY,
                true,
                false,
                false,
                false));

        Assert.assertEquals(CodexRestoreStateMachine.HostAction.IGNORE, action);
    }

    @Test
    public void matchingClosedRemovesMapping() {
        CodexRestoreStateMachine.HostAction action = CodexRestoreStateMachine.resolveHostEvent(
            new CodexRestoreStateMachine.HostEventInput(
                CodexRestoreStateMachine.HostEvent.CLOSED,
                true,
                true,
                true,
                true));

        Assert.assertEquals(CodexRestoreStateMachine.HostAction.REMOVE, action);
    }

    @Test
    public void staleClosedCannotRemoveNewThreadMapping() {
        CodexRestoreStateMachine.HostAction action = CodexRestoreStateMachine.resolveHostEvent(
            new CodexRestoreStateMachine.HostEventInput(
                CodexRestoreStateMachine.HostEvent.CLOSED,
                true,
                true,
                true,
                false));

        Assert.assertEquals(CodexRestoreStateMachine.HostAction.IGNORE, action);
    }

    @Test
    public void processLossKeepsPersistedMapping() {
        CodexRestoreStateMachine.RecoveryAction action = CodexRestoreStateMachine.resolveRecovery(
            new CodexRestoreStateMachine.RecoveryInput(
                CodexRestoreStateMachine.RecoveryEvent.PROCESS_LOST,
                true,
                true,
                true));

        Assert.assertEquals(CodexRestoreStateMachine.RecoveryAction.KEEP_MAPPING, action);
    }

    @Test
    public void coldStartResumesExactThread() {
        CodexRestoreStateMachine.RecoveryAction action = CodexRestoreStateMachine.resolveRecovery(
            new CodexRestoreStateMachine.RecoveryInput(
                CodexRestoreStateMachine.RecoveryEvent.COLD_START,
                true,
                true,
                true));

        Assert.assertEquals(CodexRestoreStateMachine.RecoveryAction.START_CODEX, action);
    }

    @Test
    public void coldStartDefersWhenCodexIsMissing() {
        CodexRestoreStateMachine.RecoveryAction action = CodexRestoreStateMachine.resolveRecovery(
            new CodexRestoreStateMachine.RecoveryInput(
                CodexRestoreStateMachine.RecoveryEvent.COLD_START,
                true,
                true,
                false));

        Assert.assertEquals(CodexRestoreStateMachine.RecoveryAction.DEFER_RETRY, action);
    }

    @Test
    public void userRemovalDeletesMapping() {
        CodexRestoreStateMachine.RecoveryAction action = CodexRestoreStateMachine.resolveRecovery(
            new CodexRestoreStateMachine.RecoveryInput(
                CodexRestoreStateMachine.RecoveryEvent.USER_REMOVE,
                true,
                true,
                true));

        Assert.assertEquals(CodexRestoreStateMachine.RecoveryAction.REMOVE_MAPPING, action);
    }

    @Test
    public void staleCodexNamedLoginShellProjectionIsDropped() {
        boolean drop = CodexRestoreStateMachine.shouldDropStaleCodexShellProjection(
            new CodexRestoreStateMachine.ShellProjectionInput(
                "shell",
                "codex ~",
                "codex ~",
                "/data/data/com.termux/files/usr/bin/login",
                new String[]{"-login"},
                false,
                false,
                false));

        Assert.assertTrue(drop);
    }

    @Test
    public void codexNamedProjectionWithAuthorityIsKept() {
        boolean drop = CodexRestoreStateMachine.shouldDropStaleCodexShellProjection(
            new CodexRestoreStateMachine.ShellProjectionInput(
                "shell",
                "codex ~",
                "codex ~",
                "/data/data/com.termux/files/usr/bin/login",
                new String[]{"-login"},
                true,
                false,
                false));

        Assert.assertFalse(drop);
    }

    @Test
    public void trailingGeneratedShellAfterManagedSessionsIsDropped() {
        boolean drop = CodexRestoreStateMachine.shouldDropDisposableGeneratedShellProjection(
            new CodexRestoreStateMachine.DisposableShellProjectionInput(
                "shell",
                "Terminal 5",
                "",
                "/data/data/com.termux/files/usr/bin/login",
                new String[]{"-login"},
                false,
                false,
                false,
                true,
                4,
                3));

        Assert.assertTrue(drop);
    }

    @Test
    public void generatedShellWithoutManagedSessionsIsKept() {
        boolean drop = CodexRestoreStateMachine.shouldDropDisposableGeneratedShellProjection(
            new CodexRestoreStateMachine.DisposableShellProjectionInput(
                "shell",
                "Terminal 1",
                "",
                "/data/data/com.termux/files/usr/bin/login",
                new String[]{"-login"},
                false,
                false,
                false,
                false,
                0,
                Integer.MAX_VALUE));

        Assert.assertFalse(drop);
    }
}
