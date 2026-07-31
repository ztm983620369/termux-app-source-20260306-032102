package com.termux.terminalsessionsurface;

import org.junit.Assert;
import org.junit.Test;

public class TerminalSessionSurfaceRenderPolicyTest {

    @Test
    public void idleOnlyRendersTheCommittedPage() {
        Assert.assertTrue(TerminalSessionSurfaceRenderPolicy.shouldRender(
            2, 2, TerminalSessionSurfacePagerStateMachine.State.IDLE, 3));
        Assert.assertFalse(TerminalSessionSurfaceRenderPolicy.shouldRender(
            1, 2, TerminalSessionSurfacePagerStateMachine.State.IDLE, 3));
        Assert.assertFalse(TerminalSessionSurfaceRenderPolicy.shouldRender(
            3, 2, TerminalSessionSurfacePagerStateMachine.State.IDLE, 3));
    }

    @Test
    public void transitionRendersOnlyCurrentAndActualTarget() {
        Assert.assertTrue(TerminalSessionSurfaceRenderPolicy.shouldRender(
            2, 2, TerminalSessionSurfacePagerStateMachine.State.DRAGGING, 3));
        Assert.assertTrue(TerminalSessionSurfaceRenderPolicy.shouldRender(
            3, 2, TerminalSessionSurfacePagerStateMachine.State.SETTLING, 3));
        Assert.assertFalse(TerminalSessionSurfaceRenderPolicy.shouldRender(
            1, 2, TerminalSessionSurfacePagerStateMachine.State.DRAGGING, 3));
        Assert.assertFalse(TerminalSessionSurfaceRenderPolicy.shouldRender(
            4, 2, TerminalSessionSurfacePagerStateMachine.State.SETTLING, 3));
    }

    @Test
    public void unknownTransitionTargetDoesNotWakeAdjacentTuis() {
        Assert.assertTrue(TerminalSessionSurfaceRenderPolicy.shouldRender(
            50, 50, TerminalSessionSurfacePagerStateMachine.State.DRAGGING, -1));
        Assert.assertFalse(TerminalSessionSurfaceRenderPolicy.shouldRender(
            49, 50, TerminalSessionSurfacePagerStateMachine.State.DRAGGING, -1));
        Assert.assertFalse(TerminalSessionSurfaceRenderPolicy.shouldRender(
            51, 50, TerminalSessionSurfacePagerStateMachine.State.DRAGGING, -1));
    }

    @Test
    public void largeTabSetAllowsAtMostTwoLiveRenderConsumers() {
        int renderConsumers = 0;
        for (int page = 0; page < 128; page++) {
            if (TerminalSessionSurfaceRenderPolicy.shouldRender(
                page, 63, TerminalSessionSurfacePagerStateMachine.State.SETTLING, 64)) {
                renderConsumers++;
            }
        }
        Assert.assertEquals(2, renderConsumers);
    }

    @Test
    public void programmaticTabSelectionCommitsImmediately() {
        Assert.assertFalse(TerminalSessionSurfaceRenderPolicy.shouldAnimateProgrammaticTransition(
            40, 41, true));
        Assert.assertFalse(TerminalSessionSurfaceRenderPolicy.shouldAnimateProgrammaticTransition(
            2, 97, true));
        Assert.assertFalse(TerminalSessionSurfaceRenderPolicy.shouldAnimateProgrammaticTransition(
            40, 41, false));
    }
}
