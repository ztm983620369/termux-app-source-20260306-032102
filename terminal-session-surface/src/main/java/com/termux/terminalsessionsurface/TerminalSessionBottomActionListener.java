package com.termux.terminalsessionsurface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

public interface TerminalSessionBottomActionListener {
    void onTmuxActionRequested(@Nullable TerminalSession session,
                               @NonNull TerminalSessionBottomTmuxAction action);
}
