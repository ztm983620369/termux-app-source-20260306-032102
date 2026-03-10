package com.termux.terminalsessioncore;

import org.junit.Assert;
import org.junit.Test;

public class TerminalTopBarStateMachineTest {

    @Test
    public void resolveTitlePrefersPinnedDisplayName() {
        TerminalTopBarStateMachine.Snapshot snapshot =
            TerminalTopBarStateMachine.resolveTitle(true, "中文后台", "ssh-persistent-abc", "bash");

        Assert.assertEquals("中文后台", snapshot.title);
        Assert.assertEquals(TerminalTopBarStateMachine.TitleState.PINNED_DISPLAY_NAME, snapshot.state);
        Assert.assertTrue(snapshot.locked);
    }

    @Test
    public void resolveTitleSkipsOpaqueInternalSessionNames() {
        TerminalTopBarStateMachine.Snapshot snapshot =
            TerminalTopBarStateMachine.resolveTitle(false, null, "ssh-persistent-abc", "server-title");

        Assert.assertEquals("server-title", snapshot.title);
        Assert.assertEquals(TerminalTopBarStateMachine.TitleState.TERMINAL_TITLE, snapshot.state);
    }
}
