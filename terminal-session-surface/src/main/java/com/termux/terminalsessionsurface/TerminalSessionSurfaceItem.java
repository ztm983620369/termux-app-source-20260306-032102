package com.termux.terminalsessionsurface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

public final class TerminalSessionSurfaceItem {

    @NonNull public final String key;
    @Nullable public final TerminalSession session;

    public TerminalSessionSurfaceItem(@NonNull String key, @Nullable TerminalSession session) {
        this.key = key;
        this.session = session;
    }
}
