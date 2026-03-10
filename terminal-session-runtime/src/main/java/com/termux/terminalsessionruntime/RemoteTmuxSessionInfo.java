package com.termux.terminalsessionruntime;

import androidx.annotation.NonNull;

public final class RemoteTmuxSessionInfo {
    @NonNull public final String name;
    @NonNull public final String displayName;
    public final int windows;
    public final boolean attached;

    public RemoteTmuxSessionInfo(@NonNull String name, @NonNull String displayName,
                                 int windows, boolean attached) {
        this.name = name;
        this.displayName = displayName;
        this.windows = windows <= 0 ? 1 : windows;
        this.attached = attached;
    }
}
