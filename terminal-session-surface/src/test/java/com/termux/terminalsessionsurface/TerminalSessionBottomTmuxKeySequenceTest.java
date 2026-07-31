package com.termux.terminalsessionsurface;

import org.junit.Assert;
import org.junit.Test;

public class TerminalSessionBottomTmuxKeySequenceTest {

    @Test
    public void everyControlHasAStableDefaultSequence() {
        for (TerminalSessionBottomTmuxAction action : TerminalSessionBottomTmuxAction.values()) {
            String sequence = TerminalSessionBottomTmuxKeySequence.forAction(action);
            Assert.assertNotNull(action.name(), sequence);
            Assert.assertTrue(action.name(), sequence.length() >= 2);
            Assert.assertEquals(action.name(), '\u0002', sequence.charAt(0));
        }
    }

    @Test
    public void splitAndNavigationMappingsMatchDefaultTmuxBindings() {
        Assert.assertEquals("\u0002%", TerminalSessionBottomTmuxKeySequence.forAction(
            TerminalSessionBottomTmuxAction.SPLIT_VERTICAL));
        Assert.assertEquals("\u0002\"", TerminalSessionBottomTmuxKeySequence.forAction(
            TerminalSessionBottomTmuxAction.SPLIT_HORIZONTAL));
        Assert.assertEquals("\u0002o", TerminalSessionBottomTmuxKeySequence.forAction(
            TerminalSessionBottomTmuxAction.NEXT_PANE));
        Assert.assertEquals("\u0002;", TerminalSessionBottomTmuxKeySequence.forAction(
            TerminalSessionBottomTmuxAction.LAST_PANE));
        Assert.assertEquals("\u0002q", TerminalSessionBottomTmuxKeySequence.forAction(
            TerminalSessionBottomTmuxAction.DISPLAY_PANES));
    }

    @Test
    public void resizeMappingsUseTmuxDefaultControlArrowBindings() {
        Assert.assertEquals("\u0002\u001b[1;5D", TerminalSessionBottomTmuxKeySequence.forAction(
            TerminalSessionBottomTmuxAction.RESIZE_LEFT));
        Assert.assertEquals("\u0002\u001b[1;5C", TerminalSessionBottomTmuxKeySequence.forAction(
            TerminalSessionBottomTmuxAction.RESIZE_RIGHT));
        Assert.assertEquals("\u0002\u001b[1;5A", TerminalSessionBottomTmuxKeySequence.forAction(
            TerminalSessionBottomTmuxAction.RESIZE_UP));
        Assert.assertEquals("\u0002\u001b[1;5B", TerminalSessionBottomTmuxKeySequence.forAction(
            TerminalSessionBottomTmuxAction.RESIZE_DOWN));
    }
}
