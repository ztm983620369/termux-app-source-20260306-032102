package com.termux.terminalsessionsurface;

import org.junit.Assert;
import org.junit.Test;

public class TerminalSessionSwipeGestureStateMachineTest {

    @Test
    public void interceptAndTouchDownShareOneLifecycle() {
        TerminalSessionSwipeGestureStateMachine machine =
            new TerminalSessionSwipeGestureStateMachine();

        Assert.assertEquals(
            TerminalSessionSwipeGestureStateMachine.SIGNAL_TOUCH_DOWN,
            machine.onDown(10L, true)
        );
        Assert.assertEquals(
            TerminalSessionSwipeGestureStateMachine.SIGNAL_NONE,
            machine.onDown(10L, true)
        );
        Assert.assertEquals(
            TerminalSessionSwipeGestureStateMachine.SIGNAL_CAPTURED,
            machine.onCaptured(10L)
        );
        Assert.assertEquals(
            TerminalSessionSwipeGestureStateMachine.SIGNAL_NONE,
            machine.onCaptured(10L)
        );
        Assert.assertEquals(
            TerminalSessionSwipeGestureStateMachine.SIGNAL_FINISHED,
            machine.onFinished(10L)
        );
        Assert.assertEquals(
            TerminalSessionSwipeGestureStateMachine.SIGNAL_NONE,
            machine.onFinished(10L)
        );
    }

    @Test
    public void ignoredGestureNeverEmitsLifecycleSignals() {
        TerminalSessionSwipeGestureStateMachine machine =
            new TerminalSessionSwipeGestureStateMachine();

        Assert.assertEquals(
            TerminalSessionSwipeGestureStateMachine.SIGNAL_NONE,
            machine.onDown(11L, false)
        );
        Assert.assertFalse(machine.isEligible(11L));
        Assert.assertEquals(
            TerminalSessionSwipeGestureStateMachine.SIGNAL_NONE,
            machine.onCaptured(11L)
        );
        Assert.assertEquals(
            TerminalSessionSwipeGestureStateMachine.SIGNAL_NONE,
            machine.onFinished(11L)
        );
    }

    @Test
    public void aNewDownClosesAStaleGestureBeforeStarting() {
        TerminalSessionSwipeGestureStateMachine machine =
            new TerminalSessionSwipeGestureStateMachine();

        machine.onDown(20L, true);

        Assert.assertEquals(
            TerminalSessionSwipeGestureStateMachine.SIGNAL_FINISHED |
                TerminalSessionSwipeGestureStateMachine.SIGNAL_TOUCH_DOWN,
            machine.onDown(21L, true)
        );
        Assert.assertTrue(machine.isEligible(21L));
    }

    @Test
    public void keyboardPreservationStartsOnlyAfterTheOuterPagerCaptures() {
        Assert.assertFalse(ProgrammaticViewPager.shouldStartPagerOwnedSwipe(
            TerminalSessionSwipeGestureStateMachine.SIGNAL_TOUCH_DOWN));
        Assert.assertFalse(ProgrammaticViewPager.shouldStartPagerOwnedSwipe(
            TerminalSessionSwipeGestureStateMachine.SIGNAL_FINISHED));
        Assert.assertTrue(ProgrammaticViewPager.shouldStartPagerOwnedSwipe(
            TerminalSessionSwipeGestureStateMachine.SIGNAL_CAPTURED));
    }

}
