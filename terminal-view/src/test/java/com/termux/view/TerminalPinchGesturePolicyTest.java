package com.termux.view;

import junit.framework.TestCase;

public final class TerminalPinchGesturePolicyTest extends TestCase {

    public void testRequiresMeaningfulStablePinchEvidence() {
        assertFalse(TerminalPinchGesturePolicy.qualifies(1.25f, 1, 40L));
        assertFalse(TerminalPinchGesturePolicy.qualifies(1.25f, 2, 10L));
        assertFalse(TerminalPinchGesturePolicy.qualifies(1.05f, 3, 40L));
        assertTrue(TerminalPinchGesturePolicy.qualifies(1.10f, 2, 24L));
        assertTrue(TerminalPinchGesturePolicy.qualifies(0.90f, 2, 24L));
        assertFalse(TerminalPinchGesturePolicy.qualifies(Float.NaN, 3, 40L));
    }

    public void testCancelledOrNoOpPinchNeverPersists() {
        assertTrue(TerminalPinchGesturePolicy.shouldCommit(true, false, 11, 21));
        assertFalse(TerminalPinchGesturePolicy.shouldCommit(true, true, 11, 21));
        assertFalse(TerminalPinchGesturePolicy.shouldCommit(false, false, 11, 21));
        assertFalse(TerminalPinchGesturePolicy.shouldCommit(true, false, 11, 11));
    }
}
