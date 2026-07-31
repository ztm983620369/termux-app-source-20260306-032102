package com.termux.terminalsessionsurface;

import org.junit.Assert;
import org.junit.Test;

public class TerminalSessionTransitionFrameStateTest {

    @Test
    public void dragDirectionMapsToThePageThatWillEnterTheViewport() {
        Assert.assertEquals(1,
            TerminalSessionTransitionFrameState.pageDeltaForDrag(500f, 300f));
        Assert.assertEquals(-1,
            TerminalSessionTransitionFrameState.pageDeltaForDrag(500f, 700f));
        Assert.assertEquals(0,
            TerminalSessionTransitionFrameState.pageDeltaForDrag(500f, 500f));
    }

    @Test
    public void gestureTargetsAreAdjacentAndRespectBoundaries() {
        TerminalSessionTransitionFrameState state = new TerminalSessionTransitionFrameState();
        state.begin(4);
        Assert.assertEquals(5, state.resolveGestureTarget(1, 10));
        Assert.assertEquals(3, state.resolveGestureTarget(-1, 10));

        state.begin(0);
        Assert.assertEquals(-1, state.resolveGestureTarget(-1, 10));
        state.begin(9);
        Assert.assertEquals(-1, state.resolveGestureTarget(1, 10));
    }

    @Test
    public void repeatedCallbacksPrepareOneFramePerTarget() {
        TerminalSessionTransitionFrameState state = new TerminalSessionTransitionFrameState();
        state.begin(4);
        state.selectTarget("next");
        Assert.assertTrue(state.markPrepared("next"));
        Assert.assertFalse(state.markPrepared("next"));
        Assert.assertTrue(state.isTarget("next"));
    }

    @Test
    public void rapidReverseWarmsEachRealDirectionOnlyOnce() {
        TerminalSessionTransitionFrameState state = new TerminalSessionTransitionFrameState();
        state.begin(4);

        state.selectTarget("next");
        Assert.assertTrue(state.markPrepared("next"));
        state.selectTarget("previous");
        Assert.assertTrue(state.markPrepared("previous"));
        state.selectTarget("next");
        Assert.assertFalse(state.markPrepared("next"));
        Assert.assertTrue(state.isTarget("next"));
    }

    @Test
    public void revisitedTargetIsOnlySkippedWhenItsRetainedFrameIsStillClean() {
        Assert.assertFalse(TerminalSessionTransitionFrameState.shouldPrepareTarget(
            false, true, false));
        Assert.assertTrue(TerminalSessionTransitionFrameState.shouldPrepareTarget(
            false, true, true));
        Assert.assertTrue(TerminalSessionTransitionFrameState.shouldPrepareTarget(
            false, false, false));
        Assert.assertTrue(TerminalSessionTransitionFrameState.shouldPrepareTarget(
            true, true, false));
    }

    @Test
    public void finishMakesTheNextTransitionIndependent() {
        TerminalSessionTransitionFrameState state = new TerminalSessionTransitionFrameState();
        state.begin(2);
        state.selectTarget("tab-3");
        Assert.assertTrue(state.markPrepared("tab-3"));

        state.finish();
        Assert.assertFalse(state.isActive());
        Assert.assertNull(state.getTargetKey());

        state.begin(3);
        state.selectTarget("tab-3");
        Assert.assertTrue(state.markPrepared("tab-3"));
    }

    @Test
    public void interruptedSettlingReanchorsWithoutRepeatingPreparedWork() {
        TerminalSessionTransitionFrameState state = new TerminalSessionTransitionFrameState();
        state.begin(4);
        state.selectTarget("tab-5");
        Assert.assertTrue(state.markPrepared("tab-5"));

        state.reanchor(5);
        Assert.assertEquals(4, state.resolveGestureTarget(-1, 10));
        state.selectTarget("tab-5");
        Assert.assertFalse(state.markPrepared("tab-5"));
    }
}
