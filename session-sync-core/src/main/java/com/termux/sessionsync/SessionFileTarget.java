package com.termux.sessionsync;

import androidx.annotation.NonNull;

public final class SessionFileTarget {

    @NonNull
    public final String key;
    @NonNull
    public final SessionEntry entry;
    public final boolean active;
    public final boolean mounted;

    public SessionFileTarget(@NonNull String key, @NonNull SessionEntry entry,
                             boolean active, boolean mounted) {
        this.key = key;
        this.entry = entry;
        this.active = active;
        this.mounted = mounted;
    }
}

