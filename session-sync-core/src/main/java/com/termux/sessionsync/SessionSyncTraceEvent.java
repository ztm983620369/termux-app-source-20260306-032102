package com.termux.sessionsync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

public final class SessionSyncTraceEvent {

    public final long timestampMs;
    @NonNull
    public final SessionSyncTraceLevel level;
    @NonNull
    public final String source;
    @NonNull
    public final String action;
    @Nullable
    public final String sessionKey;
    @NonNull
    public final String message;
    @NonNull
    public final String detail;

    public SessionSyncTraceEvent(long timestampMs, @NonNull SessionSyncTraceLevel level,
                                 @NonNull String source, @NonNull String action,
                                 @Nullable String sessionKey, @NonNull String message,
                                 @NonNull String detail) {
        this.timestampMs = timestampMs;
        this.level = level;
        this.source = source;
        this.action = action;
        this.sessionKey = sessionKey;
        this.message = message;
        this.detail = detail;
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("ts", timestampMs);
            json.put("level", level.name());
            json.put("source", source);
            json.put("action", action);
            json.put("sessionKey", sessionKey == null ? JSONObject.NULL : sessionKey);
            json.put("message", message);
            json.put("detail", detail);
        } catch (Exception ignored) {
        }
        return json;
    }
}

