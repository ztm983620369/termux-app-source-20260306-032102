package com.termux.terminalsessionsurface;

import org.junit.Assert;
import org.junit.Test;

public class TerminalSessionSelectionOriginStateMachineTest {

    @Test
    public void matchingSettledPagePreservesProgrammaticOrigin() {
        TerminalSessionSelectionOriginStateMachine machine =
            new TerminalSessionSelectionOriginStateMachine();
        machine.beginProgrammaticSelection("session-b");

        TerminalSessionSelectionOriginStateMachine.Resolution resolution =
            machine.resolveIdleSelection("session-b");

        Assert.assertEquals(
            TerminalSessionSelectionOriginStateMachine.Origin.PROGRAMMATIC,
            resolution.origin
        );
        Assert.assertNull(resolution.abandonedProgrammaticKey);
    }

    @Test
    public void interruptedProgrammaticTransitionBecomesUserOwned() {
        TerminalSessionSelectionOriginStateMachine machine =
            new TerminalSessionSelectionOriginStateMachine();
        machine.beginProgrammaticSelection("session-b");

        TerminalSessionSelectionOriginStateMachine.Resolution resolution =
            machine.resolveIdleSelection("session-a");

        Assert.assertEquals(
            TerminalSessionSelectionOriginStateMachine.Origin.USER,
            resolution.origin
        );
        Assert.assertEquals("session-b", resolution.abandonedProgrammaticKey);
    }

    @Test
    public void directProgrammaticCompletionDoesNotLeakIntoNextGesture() {
        TerminalSessionSelectionOriginStateMachine machine =
            new TerminalSessionSelectionOriginStateMachine();
        machine.beginProgrammaticSelection("session-b");
        machine.completeProgrammaticSelection("session-b");

        Assert.assertEquals(
            TerminalSessionSelectionOriginStateMachine.Origin.USER,
            machine.resolveIdleSelection("session-b").origin
        );
    }

    @Test
    public void newerProgrammaticTargetReportsTheSupersededKey() {
        TerminalSessionSelectionOriginStateMachine machine =
            new TerminalSessionSelectionOriginStateMachine();
        Assert.assertNull(machine.beginProgrammaticSelection("session-b"));

        Assert.assertEquals(
            "session-b",
            machine.beginProgrammaticSelection("session-c")
        );
    }
}
