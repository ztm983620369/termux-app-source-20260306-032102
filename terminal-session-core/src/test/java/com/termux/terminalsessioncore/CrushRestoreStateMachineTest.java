package com.termux.terminalsessioncore;

import org.junit.Assert;
import org.junit.Test;

public class CrushRestoreStateMachineTest {

    @Test
    public void liveProcessIsStrongestAuthority() {
        CrushRestoreStateMachine.Authority authority = CrushRestoreStateMachine.resolveAuthority(
            new CrushRestoreStateMachine.AuthorityInput(true, true, true, true));

        Assert.assertEquals(CrushRestoreStateMachine.Authority.LIVE_PROCESS, authority);
    }

    @Test
    public void stateFileBeatsTermuxShellProjection() {
        CrushRestoreStateMachine.Authority authority = CrushRestoreStateMachine.resolveAuthority(
            new CrushRestoreStateMachine.AuthorityInput(false, true, true, true));

        Assert.assertEquals(CrushRestoreStateMachine.Authority.CRUSH_STATE_FILE, authority);
    }

    @Test
    public void closingCrushFrontendDetachesByDefault() {
        CrushRestoreStateMachine.TabCloseAction action = CrushRestoreStateMachine.resolveTabClose(
            new CrushRestoreStateMachine.TabCloseInput(
                CrushRestoreStateMachine.Authority.CRUSH_STATE_FILE,
                false));

        Assert.assertEquals(CrushRestoreStateMachine.TabCloseAction.DETACH_FRONTEND, action);
    }

    @Test
    public void explicitDestroyRemovesCrushAuthority() {
        CrushRestoreStateMachine.TabCloseAction action = CrushRestoreStateMachine.resolveTabClose(
            new CrushRestoreStateMachine.TabCloseInput(
                CrushRestoreStateMachine.Authority.LIVE_PROCESS,
                true));

        Assert.assertEquals(CrushRestoreStateMachine.TabCloseAction.DESTROY_AUTHORITY, action);
    }

    @Test
    public void detachedCrushRecordIsMaterializedIntoRestoreSnapshot() {
        CrushRestoreStateMachine.SnapshotAction action = CrushRestoreStateMachine.resolveSnapshot(
            new CrushRestoreStateMachine.SnapshotInput(
                CrushRestoreStateMachine.Authority.CRUSH_STATE_FILE,
                false,
                false,
                true,
                CrushRestoreStateMachine.StoredRecordState.DETACHED));

        Assert.assertEquals(CrushRestoreStateMachine.SnapshotAction.MATERIALIZE_DETACHED_CRUSH_RECORD, action);
    }

    @Test
    public void staleActiveCrushRecordWithoutFrontendIsDetached() {
        CrushRestoreStateMachine.SnapshotAction action = CrushRestoreStateMachine.resolveSnapshot(
            new CrushRestoreStateMachine.SnapshotInput(
                CrushRestoreStateMachine.Authority.CRUSH_STATE_FILE,
                false,
                false,
                true,
                CrushRestoreStateMachine.StoredRecordState.ACTIVE));

        Assert.assertEquals(CrushRestoreStateMachine.SnapshotAction.DETACH_STALE_CRUSH_RECORD, action);
    }

    @Test
    public void attachedCrushProjectionIsWrittenAsCrushNotShell() {
        CrushRestoreStateMachine.SnapshotAction action = CrushRestoreStateMachine.resolveSnapshot(
            new CrushRestoreStateMachine.SnapshotInput(
                CrushRestoreStateMachine.Authority.EXECUTION_COMMAND,
                true,
                true,
                false));

        Assert.assertEquals(CrushRestoreStateMachine.SnapshotAction.WRITE_CRUSH_RECORD, action);
    }

    @Test
    public void restoreFallsBackWhenCrushExecutableIsMissing() {
        CrushRestoreStateMachine.RestoreAction action = CrushRestoreStateMachine.resolveRestore(
            new CrushRestoreStateMachine.RestoreInput(
                CrushRestoreStateMachine.Authority.TERMUX_RESTORE_RECORD,
                false,
                true));

        Assert.assertEquals(CrushRestoreStateMachine.RestoreAction.START_FALLBACK_SHELL, action);
    }

    @Test
    public void staleCrushNamedLoginShellProjectionIsDropped() {
        boolean drop = CrushRestoreStateMachine.shouldDropStaleCrushShellProjection(
            new CrushRestoreStateMachine.ShellProjectionInput(
                "shell",
                "crush ~",
                "crush ~",
                "/data/data/com.termux/files/usr/bin/login",
                new String[]{"-login"},
                false,
                false,
                false));

        Assert.assertTrue(drop);
    }

    @Test
    public void crushNamedProjectionWithAuthorityIsKept() {
        boolean drop = CrushRestoreStateMachine.shouldDropStaleCrushShellProjection(
            new CrushRestoreStateMachine.ShellProjectionInput(
                "shell",
                "crush ~",
                "crush ~",
                "/data/data/com.termux/files/usr/bin/login",
                new String[]{"-login"},
                true,
                false,
                false));

        Assert.assertFalse(drop);
    }

    @Test
    public void ordinaryShellProjectionIsKept() {
        boolean drop = CrushRestoreStateMachine.shouldDropStaleCrushShellProjection(
            new CrushRestoreStateMachine.ShellProjectionInput(
                "shell",
                "Terminal 3",
                "",
                "/data/data/com.termux/files/usr/bin/login",
                new String[]{"-login"},
                false,
                false,
                false));

        Assert.assertFalse(drop);
    }

    @Test
    public void trailingGeneratedShellAfterManagedSessionsIsDropped() {
        boolean drop = CrushRestoreStateMachine.shouldDropDisposableGeneratedShellProjection(
            new CrushRestoreStateMachine.DisposableShellProjectionInput(
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
        boolean drop = CrushRestoreStateMachine.shouldDropDisposableGeneratedShellProjection(
            new CrushRestoreStateMachine.DisposableShellProjectionInput(
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

    @Test
    public void generatedShellWithAuthorityIsKept() {
        boolean drop = CrushRestoreStateMachine.shouldDropDisposableGeneratedShellProjection(
            new CrushRestoreStateMachine.DisposableShellProjectionInput(
                "shell",
                "Terminal 5",
                "",
                "/data/data/com.termux/files/usr/bin/login",
                new String[]{"-login"},
                true,
                false,
                false,
                true,
                4,
                3));

        Assert.assertFalse(drop);
    }
}
