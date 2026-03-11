package com.termux.app.topbar;

import org.junit.Assert;
import org.junit.Test;

public class TerminalTopBarTouchStateMachineTest {

    @Test
    public void shortPressProducesClick() {
        TerminalTopBarTouchStateMachine machine = new TerminalTopBarTouchStateMachine(8f);

        machine.onDown(10f, 10f);

        Assert.assertEquals(TerminalTopBarTouchStateMachine.State.PRESSED, machine.getState());
        Assert.assertEquals(TerminalTopBarTouchStateMachine.ReleaseAction.CLICK, machine.onUp());
        Assert.assertEquals(TerminalTopBarTouchStateMachine.State.IDLE, machine.getState());
    }

    @Test
    public void longPressConsumesRelease() {
        TerminalTopBarTouchStateMachine machine = new TerminalTopBarTouchStateMachine(8f);

        machine.onDown(10f, 10f);

        Assert.assertTrue(machine.onLongPressTimeout());
        Assert.assertEquals(TerminalTopBarTouchStateMachine.State.LONG_PRESS_TRIGGERED, machine.getState());
        Assert.assertEquals(TerminalTopBarTouchStateMachine.ReleaseAction.CONSUME, machine.onUp());
        Assert.assertEquals(TerminalTopBarTouchStateMachine.State.IDLE, machine.getState());
    }

    @Test
    public void movingPastThresholdCancelsPress() {
        TerminalTopBarTouchStateMachine machine = new TerminalTopBarTouchStateMachine(8f);

        machine.onDown(10f, 10f);

        Assert.assertTrue(machine.onMove(30f, 10f));
        Assert.assertFalse(machine.onLongPressTimeout());
        Assert.assertEquals(TerminalTopBarTouchStateMachine.ReleaseAction.NONE, machine.onUp());
        Assert.assertEquals(TerminalTopBarTouchStateMachine.State.IDLE, machine.getState());
    }
}
