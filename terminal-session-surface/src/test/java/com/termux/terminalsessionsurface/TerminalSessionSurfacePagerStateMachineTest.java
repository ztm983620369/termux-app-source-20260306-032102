package com.termux.terminalsessionsurface;

import org.junit.Assert;
import org.junit.Test;

public class TerminalSessionSurfacePagerStateMachineTest {

    @Test
    public void stateTracksDragSettleIdle() {
        TerminalSessionSurfacePagerStateMachine machine =
            new TerminalSessionSurfacePagerStateMachine();

        machine.onDragStarted();
        Assert.assertEquals(TerminalSessionSurfacePagerStateMachine.State.DRAGGING, machine.getState());

        machine.onSettlingStarted();
        Assert.assertEquals(TerminalSessionSurfacePagerStateMachine.State.SETTLING, machine.getState());

        machine.onIdle();
        Assert.assertEquals(TerminalSessionSurfacePagerStateMachine.State.IDLE, machine.getState());
    }
}
