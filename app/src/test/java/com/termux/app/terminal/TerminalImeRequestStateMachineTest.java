package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

public class TerminalImeRequestStateMachineTest {

    @Test
    public void requestWaitsUntilEveryInputReadinessConditionIsMet() {
        TerminalImeRequestStateMachine machine = new TerminalImeRequestStateMachine();
        long token = machine.requestShow();

        Assert.assertFalse(machine.consumeIfReady(token, false, true, true, true, true));
        Assert.assertFalse(machine.consumeIfReady(token, true, false, true, true, true));
        Assert.assertFalse(machine.consumeIfReady(token, true, true, false, true, true));
        Assert.assertFalse(machine.consumeIfReady(token, true, true, true, false, true));
        Assert.assertFalse(machine.consumeIfReady(token, true, true, true, true, false));
        Assert.assertEquals(token, machine.getPendingToken());

        Assert.assertTrue(machine.consumeIfReady(token, true, true, true, true, true));
        Assert.assertEquals(0L, machine.getPendingToken());
    }

    @Test
    public void newerRequestInvalidatesPostedWorkForOlderRequest() {
        TerminalImeRequestStateMachine machine = new TerminalImeRequestStateMachine();
        long oldToken = machine.requestShow();
        long currentToken = machine.requestShow();

        Assert.assertFalse(machine.consumeIfReady(oldToken, true, true, true, true, true));
        Assert.assertTrue(machine.consumeIfReady(currentToken, true, true, true, true, true));
    }

    @Test
    public void cancellationPreventsDeferredShow() {
        TerminalImeRequestStateMachine machine = new TerminalImeRequestStateMachine();
        long token = machine.requestShow();

        machine.cancelShow();

        Assert.assertFalse(machine.consumeIfReady(token, true, true, true, true, true));
        Assert.assertEquals(0L, machine.getPendingToken());
    }

    @Test
    public void requestCanOnlyBeConsumedOnce() {
        TerminalImeRequestStateMachine machine = new TerminalImeRequestStateMachine();
        long token = machine.requestShow();

        Assert.assertTrue(machine.consumeIfReady(token, true, true, true, true, true));
        Assert.assertFalse(machine.consumeIfReady(token, true, true, true, true, true));
    }
}
