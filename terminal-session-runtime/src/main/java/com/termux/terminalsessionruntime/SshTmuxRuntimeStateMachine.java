package com.termux.terminalsessionruntime;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class SshTmuxRuntimeStateMachine {

    public enum Phase {
        IDLE,
        LISTING_REMOTE,
        INSTALLING_REMOTE,
        CREATING_REMOTE,
        DESTROYING_REMOTE,
        CONNECTING_REMOTE,
        CHECKING_REMOTE,
        ENSURING_LOCAL_BINDING,
        SYNCING_DISPLAY_NAME,
        RETRY_SCHEDULED,
        FAILED
    }

    public static final class Snapshot {
        @NonNull public final Phase phase;
        @Nullable public final String tmuxSession;
        @Nullable public final String displayName;
        public final int attempt;
        @Nullable public final String detail;
        @Nullable public final String operationId;
        @Nullable public final String sessionHandle;
        public final long updatedAtMs;

        public Snapshot(@NonNull Phase phase, @Nullable String tmuxSession, @Nullable String displayName,
                        int attempt, @Nullable String detail, @Nullable String operationId,
                        @Nullable String sessionHandle, long updatedAtMs) {
            this.phase = phase;
            this.tmuxSession = tmuxSession;
            this.displayName = displayName;
            this.attempt = attempt;
            this.detail = detail;
            this.operationId = operationId;
            this.sessionHandle = sessionHandle;
            this.updatedAtMs = updatedAtMs;
        }
    }

    @NonNull
    public Snapshot next(@NonNull Phase phase, @Nullable String tmuxSession,
                         @Nullable String displayName, int attempt, @Nullable String detail) {
        return next(phase, tmuxSession, displayName, attempt, detail, null, null);
    }

    @NonNull
    public Snapshot next(@NonNull Phase phase, @Nullable String tmuxSession,
                         @Nullable String displayName, int attempt, @Nullable String detail,
                         @Nullable String operationId, @Nullable String sessionHandle) {
        return new Snapshot(phase, tmuxSession, displayName, attempt, detail,
            operationId, sessionHandle, System.currentTimeMillis());
    }
}
