package com.termux.sessionsync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class SessionFileResolveResult {

    public final boolean success;
    @NonNull
    public final SessionFileMode mode;
    @NonNull
    public final String rootPath;
    @Nullable
    public final SessionEntry entry;
    @Nullable
    public final String sessionKey;
    @NonNull
    public final String messageCn;
    @NonNull
    public final String diagnostic;

    private SessionFileResolveResult(boolean success, @NonNull SessionFileMode mode,
                                     @NonNull String rootPath, @Nullable SessionEntry entry,
                                     @Nullable String sessionKey, @NonNull String messageCn,
                                     @NonNull String diagnostic) {
        this.success = success;
        this.mode = mode;
        this.rootPath = rootPath;
        this.entry = entry;
        this.sessionKey = sessionKey;
        this.messageCn = messageCn;
        this.diagnostic = diagnostic;
    }

    @NonNull
    public static SessionFileResolveResult ok(@NonNull SessionFileMode mode, @NonNull String rootPath,
                                              @Nullable SessionEntry entry, @Nullable String sessionKey,
                                              @NonNull String messageCn) {
        return new SessionFileResolveResult(true, mode, rootPath, entry, sessionKey, messageCn, "");
    }

    @NonNull
    public static SessionFileResolveResult fail(@NonNull SessionFileMode mode, @NonNull String rootPath,
                                                @Nullable SessionEntry entry, @Nullable String sessionKey,
                                                @NonNull String messageCn, @NonNull String diagnostic) {
        return new SessionFileResolveResult(false, mode, rootPath, entry, sessionKey, messageCn, diagnostic);
    }
}

