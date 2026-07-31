package com.termux.app.terminal.io;

import com.termux.app.TermuxActivity;
import com.termux.app.terminal.TermuxActivityRootView;

/**
 * Compatibility entry point for the historical fullscreen IME workaround.
 *
 * The old implementation installed an unbounded global-layout listener and rewrote the activity
 * content height whenever the keyboard moved. For a terminal, that is a process-visible PTY resize
 * and causes SSH/tmux reflow storms. The root/surface insets transaction now handles fullscreen and
 * non-fullscreen windows identically without changing measured terminal geometry.
 */
public final class FullScreenWorkAround {

    public static void apply(TermuxActivity activity) {
        if (activity == null) return;
        TermuxActivityRootView root = activity.getTermuxActivityRootView();
        if (root != null) root.dispatchCurrentImeViewportState();
    }

    private FullScreenWorkAround() {}
}
