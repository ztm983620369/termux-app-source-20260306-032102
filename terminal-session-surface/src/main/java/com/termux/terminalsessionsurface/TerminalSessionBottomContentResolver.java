package com.termux.terminalsessionsurface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

public interface TerminalSessionBottomContentResolver {
    @NonNull
    TerminalSessionBottomPrimaryContent resolve(@Nullable TerminalSession session);
}
