package com.termux.terminalsessionruntime;

import androidx.annotation.NonNull;

import java.util.ArrayList;

public final class RemoteTmuxListResult {
    public final boolean tmuxMissing;
    public final boolean listDone;
    @NonNull public final ArrayList<RemoteTmuxSessionInfo> sessions;
    @NonNull public final ShellCommandResult commandResult;

    public RemoteTmuxListResult(boolean tmuxMissing, boolean listDone,
                                @NonNull ArrayList<RemoteTmuxSessionInfo> sessions,
                                @NonNull ShellCommandResult commandResult) {
        this.tmuxMissing = tmuxMissing;
        this.listDone = listDone;
        this.sessions = sessions;
        this.commandResult = commandResult;
    }
}
