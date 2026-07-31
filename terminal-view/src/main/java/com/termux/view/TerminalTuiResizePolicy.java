package com.termux.view;

/** Protocol-state policy for keeping an inline TUI on its live primary-screen viewport. */
final class TerminalTuiResizePolicy {

    private TerminalTuiResizePolicy() {}

    static boolean isInlinePrimaryScreen(boolean alternateScreen,
                                         boolean mouseTracking, boolean focusEvents,
                                         boolean cursorKeysApplication,
                                         boolean keypadApplication,
                                         boolean cursorEnabled) {
        return !alternateScreen && (mouseTracking || focusEvents ||
            cursorKeysApplication || keypadApplication || !cursorEnabled);
    }

    static boolean shouldPinLiveEdge(boolean atLiveEdge, boolean alternateScreen,
                                     boolean mouseTracking, boolean focusEvents,
                                     boolean cursorKeysApplication, boolean keypadApplication,
                                     boolean cursorEnabled) {
        return atLiveEdge && isInlinePrimaryScreen(alternateScreen, mouseTracking, focusEvents,
            cursorKeysApplication, keypadApplication, cursorEnabled);
    }
}
