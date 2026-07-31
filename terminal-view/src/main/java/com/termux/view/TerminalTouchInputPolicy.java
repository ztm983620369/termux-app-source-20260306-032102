package com.termux.view;

/** Selects the touch input route before TerminalView mutates local viewport state. */
final class TerminalTouchInputPolicy {

    enum ScrollRoute {
        LOCAL_VIEWPORT,
        REMOTE_MOUSE_WHEEL
    }

    private TerminalTouchInputPolicy() {
    }

    static ScrollRoute resolveScrollRoute(boolean mouseTrackingActive,
                                          boolean shouldSendMouseWheel,
                                          int activeTranscriptRows) {
        if (mouseTrackingActive &&
            (shouldSendMouseWheel || activeTranscriptRows <= 0)) {
            return ScrollRoute.REMOTE_MOUSE_WHEEL;
        }
        return ScrollRoute.LOCAL_VIEWPORT;
    }

    static boolean shouldSendMouseClick(boolean mouseTrackingActive,
                                        boolean shouldSendMouseClick) {
        return mouseTrackingActive && shouldSendMouseClick;
    }
}
