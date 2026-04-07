package com.termux.app.terminal.workspace;

import androidx.annotation.Nullable;

/**
 * A minimal API to present/dismiss the shared terminal workspace UI.
 *
 * <p>Implementation is expected to reuse the same terminal surface (sessions, view clients, etc)
 * and mount/present it consistently regardless of call site (editor, files, etc).</p>
 */
public interface TerminalWorkspaceController {

    /** Present terminal workspace, optionally using {@code currentFilePath} to choose a workdir. */
    boolean show(@Nullable String currentFilePath);

    /** Dismiss terminal workspace. */
    boolean hide();

    /** Whether terminal workspace is currently presented. */
    boolean isVisible();
}

