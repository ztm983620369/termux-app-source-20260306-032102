package com.termux.view;

import junit.framework.TestCase;

public class TerminalRenderDamageTrackerTest extends TestCase {

    public void testUnionsMultipleDeltaPacketsWithoutExpandingToFullFrame() {
        TerminalRenderDamageTracker tracker = new TerminalRenderDamageTracker();
        tracker.begin();
        tracker.markRow(7);
        tracker.markRow(2);
        tracker.markRow(5);
        tracker.finish(true);

        assertFalse(tracker.isFull());
        assertEquals(2, tracker.start());
        assertEquals(8, tracker.end());
    }

    public void testFullFrameDominatesPartialRows() {
        TerminalRenderDamageTracker tracker = new TerminalRenderDamageTracker();
        tracker.begin();
        tracker.markRow(3);
        tracker.markFull();
        tracker.markRow(9);
        tracker.finish(true);

        assertTrue(tracker.isFull());
    }

    public void testFailedPreparationRequiresFullRecoveryFrame() {
        TerminalRenderDamageTracker tracker = new TerminalRenderDamageTracker();
        tracker.begin();
        tracker.markRow(1);
        tracker.finish(false);

        assertTrue(tracker.isFull());
    }
}
