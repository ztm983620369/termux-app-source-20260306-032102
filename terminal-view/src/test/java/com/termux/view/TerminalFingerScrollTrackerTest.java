package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TerminalFingerScrollTrackerTest {

    @Test
    public void capturesAfterSlopAndMapsAbsoluteDistance() {
        TerminalFingerScrollTracker tracker = new TerminalFingerScrollTracker();
        tracker.start(4, 500f, -240f);

        assertTrue(Float.isNaN(tracker.update(494f, 8f)));
        assertEquals(-228f, tracker.update(488f, 8f), 0f);
        assertTrue(tracker.isDragging());
        assertEquals(4, tracker.getPointerId());
    }

    @Test
    public void coalescedMovesDoNotAccumulateDrift() {
        TerminalFingerScrollTracker tracker = new TerminalFingerScrollTracker();
        tracker.start(0, 900f, -1000f);

        assertEquals(-920f, tracker.update(820f, 1f), 0f);
        assertEquals(-625f, tracker.update(525f, 1f), 0f);
        assertEquals(-625f, tracker.getLastTarget(), 0f);
    }

    @Test
    public void reversingToTouchOriginReturnsToViewportOrigin() {
        TerminalFingerScrollTracker tracker = new TerminalFingerScrollTracker();
        tracker.start(0, 700f, -360f);

        assertEquals(-210f, tracker.update(550f, 4f), 0f);
        assertEquals(-410f, tracker.update(750f, 4f), 0f);
        assertEquals(-360f, tracker.update(700f, 4f), 0f);
    }

    @Test
    public void rebaseKeepsTheSameFingerPositionOnOutputStabilizedHistory() {
        TerminalFingerScrollTracker tracker = new TerminalFingerScrollTracker();
        tracker.start(0, 700f, -360f);

        assertEquals(-210f, tracker.update(550f, 4f), 0f);
        tracker.rebase(-18f);

        assertEquals(-228f, tracker.update(550f, 4f), 0f);
        assertEquals(-378f, tracker.update(700f, 4f), 0f);
    }

    @Test
    public void cancelStopsFurtherUpdates() {
        TerminalFingerScrollTracker tracker = new TerminalFingerScrollTracker();
        tracker.start(0, 100f, -20f);
        tracker.update(80f, 1f);
        tracker.cancel();

        assertFalse(tracker.isActive());
        assertFalse(tracker.isDragging());
        assertTrue(Float.isNaN(tracker.update(40f, 1f)));
    }
}
