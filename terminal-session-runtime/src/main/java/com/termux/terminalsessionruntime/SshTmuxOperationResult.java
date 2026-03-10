package com.termux.terminalsessionruntime;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class SshTmuxOperationResult {

    public enum Code {
        SUCCESS,
        TMUX_MISSING,
        FAILED
    }

    @NonNull public final Code code;
    @NonNull public final String tmuxSession;
    @NonNull public final String displayName;
    @Nullable public final SshPersistenceRecord record;
    @NonNull public final ShellCommandResult commandResult;

    public SshTmuxOperationResult(@NonNull Code code, @NonNull String tmuxSession, @NonNull String displayName,
                                  @Nullable SshPersistenceRecord record, @NonNull ShellCommandResult commandResult) {
        this.code = code;
        this.tmuxSession = tmuxSession;
        this.displayName = displayName;
        this.record = record;
        this.commandResult = commandResult;
    }
}
