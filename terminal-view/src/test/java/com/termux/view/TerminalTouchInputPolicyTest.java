package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TerminalTouchInputPolicyTest {

    @Test
    public void remoteWheelPreferenceWinsRegardlessOfLocalTranscript() {
        assertEquals(TerminalTouchInputPolicy.ScrollRoute.REMOTE_MOUSE_WHEEL,
            TerminalTouchInputPolicy.resolveScrollRoute(true, true, 0));
        assertEquals(TerminalTouchInputPolicy.ScrollRoute.REMOTE_MOUSE_WHEEL,
            TerminalTouchInputPolicy.resolveScrollRoute(true, true, 400));
    }

    @Test
    public void mouseOptOutPreservesTheNoLocalHistoryFallback() {
        assertEquals(TerminalTouchInputPolicy.ScrollRoute.REMOTE_MOUSE_WHEEL,
            TerminalTouchInputPolicy.resolveScrollRoute(true, false, 0));
        assertEquals(TerminalTouchInputPolicy.ScrollRoute.LOCAL_VIEWPORT,
            TerminalTouchInputPolicy.resolveScrollRoute(true, false, 1));
    }

    @Test
    public void noTouchScrollRouteSynthesizesArrowKeys() {
        assertEquals(TerminalTouchInputPolicy.ScrollRoute.LOCAL_VIEWPORT,
            TerminalTouchInputPolicy.resolveScrollRoute(false, true, 0));
    }

    @Test
    public void touchClicksCanBeDisabledIndependentlyFromRemoteWheel() {
        assertTrue(TerminalTouchInputPolicy.shouldSendMouseClick(true, true));
        assertFalse(TerminalTouchInputPolicy.shouldSendMouseClick(true, false));
        assertFalse(TerminalTouchInputPolicy.shouldSendMouseClick(false, true));
    }
}
