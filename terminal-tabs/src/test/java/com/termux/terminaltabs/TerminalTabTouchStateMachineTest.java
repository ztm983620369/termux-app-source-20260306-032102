package com.termux.terminaltabs;

import org.junit.Assert;
import org.junit.Test;

public class TerminalTabTouchStateMachineTest {

    @Test
    public void shortPressProducesClick() {
        TerminalTabTouchStateMachine machine = new TerminalTabTouchStateMachine(8f);

        machine.onDown(10f, 10f);

        Assert.assertEquals(TerminalTabTouchStateMachine.State.PRESSED, machine.getState());
        Assert.assertEquals(TerminalTabTouchStateMachine.ReleaseAction.CLICK, machine.onUp());
        Assert.assertEquals(TerminalTabTouchStateMachine.State.IDLE, machine.getState());
    }

    @Test
    public void longPressConsumesRelease() {
        TerminalTabTouchStateMachine machine = new TerminalTabTouchStateMachine(8f);

        machine.onDown(10f, 10f);

        Assert.assertTrue(machine.onLongPressTimeout());
        Assert.assertEquals(TerminalTabTouchStateMachine.State.LONG_PRESS_TRIGGERED, machine.getState());
        Assert.assertEquals(TerminalTabTouchStateMachine.ReleaseAction.CONSUME, machine.onUp());
        Assert.assertEquals(TerminalTabTouchStateMachine.State.IDLE, machine.getState());
    }

    @Test
    public void movingPastThresholdCancelsPress() {
        TerminalTabTouchStateMachine machine = new TerminalTabTouchStateMachine(8f);

        machine.onDown(10f, 10f);

        Assert.assertTrue(machine.onMove(30f, 10f));
        Assert.assertFalse(machine.onLongPressTimeout());
        Assert.assertEquals(TerminalTabTouchStateMachine.ReleaseAction.NONE, machine.onUp());
        Assert.assertEquals(TerminalTabTouchStateMachine.State.IDLE, machine.getState());
    }
}
