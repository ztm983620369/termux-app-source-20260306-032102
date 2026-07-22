package com.termux.terminalsessionsurface;

import org.junit.Assert;
import org.junit.Test;

public class TerminalSessionSurfaceRenderPolicyTest {

    @Test
    public void idleOnlyRendersTheCommittedPage() {
        Assert.assertTrue(TerminalSessionSurfaceRenderPolicy.shouldRender(
            2, 2, TerminalSessionSurfacePagerStateMachine.State.IDLE));
        Assert.assertFalse(TerminalSessionSurfaceRenderPolicy.shouldRender(
            1, 2, TerminalSessionSurfacePagerStateMachine.State.IDLE));
        Assert.assertFalse(TerminalSessionSurfaceRenderPolicy.shouldRender(
            3, 2, TerminalSessionSurfacePagerStateMachine.State.IDLE));
    }

    @Test
    public void transitionRendersOnlyCurrentAndAdjacentPages() {
        Assert.assertTrue(TerminalSessionSurfaceRenderPolicy.shouldRender(
            1, 2, TerminalSessionSurfacePagerStateMachine.State.DRAGGING));
        Assert.assertTrue(TerminalSessionSurfaceRenderPolicy.shouldRender(
            3, 2, TerminalSessionSurfacePagerStateMachine.State.SETTLING));
        Assert.assertFalse(TerminalSessionSurfaceRenderPolicy.shouldRender(
            4, 2, TerminalSessionSurfacePagerStateMachine.State.SETTLING));
    }
}
