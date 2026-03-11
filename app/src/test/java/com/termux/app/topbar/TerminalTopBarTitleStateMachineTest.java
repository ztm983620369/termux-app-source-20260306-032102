package com.termux.app.topbar;

import org.junit.Assert;
import org.junit.Test;

public class TerminalTopBarTitleStateMachineTest {

    @Test
    public void pinnedDisplayNameHasHighestPriority() {
        TerminalTopBarTitleStateMachine.Snapshot snapshot = TerminalTopBarTitleStateMachine.resolveTitle(
            true,
            "  prod api  ",
            "ssh-persistent-1",
            "bash");

        Assert.assertEquals("prod api", snapshot.title);
        Assert.assertEquals(TerminalTopBarTitleStateMachine.TitleState.PINNED_DISPLAY_NAME, snapshot.state);
        Assert.assertTrue(snapshot.locked);
    }

    @Test
    public void opaqueSessionNameFallsBackToTerminalTitle() {
        TerminalTopBarTitleStateMachine.Snapshot snapshot = TerminalTopBarTitleStateMachine.resolveTitle(
            false,
            null,
            "ssh-persistent-1234",
            "htop");

        Assert.assertEquals("htop", snapshot.title);
        Assert.assertEquals(TerminalTopBarTitleStateMachine.TitleState.TERMINAL_TITLE, snapshot.state);
        Assert.assertFalse(snapshot.locked);
    }

    @Test
    public void emptyInputsUseStableFallbackTitle() {
        TerminalTopBarTitleStateMachine.Snapshot snapshot = TerminalTopBarTitleStateMachine.resolveTitle(
            false,
            null,
            " \n\t ",
            null);

        Assert.assertEquals("terminal", snapshot.title);
        Assert.assertEquals(TerminalTopBarTitleStateMachine.TitleState.FALLBACK, snapshot.state);
    }
}
