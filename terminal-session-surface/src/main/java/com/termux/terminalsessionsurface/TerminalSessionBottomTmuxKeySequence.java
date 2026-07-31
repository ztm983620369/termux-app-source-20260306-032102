package com.termux.terminalsessionsurface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Default tmux key sequences used by the bottom control surface.
 *
 * <p>The surface deliberately sends keys through the attached PTY instead of invoking a shell
 * command. This keeps the action bound to the visible client and works for both local and remote
 * sessions. The mapping is kept pure so every control has a deterministic, unit-testable contract.
 * </p>
 */
public final class TerminalSessionBottomTmuxKeySequence {

    private static final String PREFIX = "\u0002";

    private TerminalSessionBottomTmuxKeySequence() {
    }

    @Nullable
    public static String forAction(@NonNull TerminalSessionBottomTmuxAction action) {
        switch (action) {
            case DISPLAY_PANES:
                return PREFIX + "q";
            case SPLIT_VERTICAL:
                return PREFIX + "%";
            case SPLIT_HORIZONTAL:
                return PREFIX + "\"";
            case NEXT_PANE:
                return PREFIX + "o";
            case LAST_PANE:
                return PREFIX + ";";
            case ZOOM_PANE:
                return PREFIX + "z";
            case RESIZE_LEFT:
                return PREFIX + "\u001b[1;5D";
            case RESIZE_RIGHT:
                return PREFIX + "\u001b[1;5C";
            case RESIZE_UP:
                return PREFIX + "\u001b[1;5A";
            case RESIZE_DOWN:
                return PREFIX + "\u001b[1;5B";
            case KILL_PANE:
                return PREFIX + "x";
            case PREVIOUS_WINDOW:
                return PREFIX + "p";
            case NEXT_WINDOW:
                return PREFIX + "n";
            case NEW_WINDOW:
                return PREFIX + "c";
            case COPY_MODE:
                return PREFIX + "[";
            case DETACH:
                return PREFIX + "d";
            default:
                return null;
        }
    }
}
