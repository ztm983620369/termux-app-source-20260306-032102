package com.termux.terminalsessionruntime;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;

public interface SshTmuxRuntimeBridge {

    @NonNull
    Context getApplicationContext();

    @Nullable
    TerminalSession getCurrentSession();

    void setCurrentSession(@Nullable TerminalSession session);

    @NonNull
    String getDefaultWorkingDirectory();

    void onTermuxSessionListUpdated();

    void runOnUiThread(@NonNull Runnable runnable);

    void postDelayedOnUi(@NonNull Runnable runnable, long delayMs);

    @Nullable
    TermuxSession getTermuxSession(int index);

    int getTermuxSessionsSize();

    @NonNull
    ArrayList<TermuxSession> getTermuxSessionsSnapshot();

    @Nullable
    TermuxSession getTermuxSessionForTerminalSession(@Nullable TerminalSession session);

    @Nullable
    TermuxSession getTermuxSessionForShellName(@Nullable String shellName);

    @Nullable
    TerminalSession getTerminalSessionForHandle(@Nullable String handle);

    @Nullable
    TermuxSession createTermuxSession(@Nullable String executablePath, @Nullable String[] arguments,
                                      @Nullable String stdin, @NonNull String workingDirectory,
                                      boolean isFailSafe, @Nullable String sessionName);

    void removeTermuxSession(@NonNull TerminalSession session);

    void onRuntimeStateChanged(@NonNull SshTmuxRuntimeStateMachine.Snapshot snapshot);
}
