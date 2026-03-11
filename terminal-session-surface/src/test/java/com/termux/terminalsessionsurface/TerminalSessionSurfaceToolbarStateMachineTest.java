package com.termux.terminalsessionsurface;

import org.junit.Assert;
import org.junit.Test;

public class TerminalSessionSurfaceToolbarStateMachineTest {

    @Test
    public void disablingTextInputForcesPrimaryPage() {
        TerminalSessionSurfaceToolbarStateMachine machine =
            new TerminalSessionSurfaceToolbarStateMachine();

        machine.setTextInputEnabled(true);
        machine.onPageSelected(1);
        machine.setTextInputEnabled(false);

        Assert.assertTrue(machine.isTerminalPageSelected());
        Assert.assertFalse(machine.isTextInputPageSelected());
    }
}
