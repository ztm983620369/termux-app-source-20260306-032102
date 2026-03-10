package com.termux.systemstatussurface;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class SystemStatusSurfaceIntentCodec {

    static final String ACTION_PUBLISH = "com.termux.systemstatussurface.action.PUBLISH";
    static final String ACTION_CANCEL = "com.termux.systemstatussurface.action.CANCEL";
    static final String ACTION_CLEAR_ALL = "com.termux.systemstatussurface.action.CLEAR_ALL";
    static final String ACTION_OPEN_PROMOTION_SETTINGS = "com.termux.systemstatussurface.action.OPEN_PROMOTION_SETTINGS";

    static final String EXTRA_SURFACE_ID = "surface_id";
    static final String EXTRA_CHANNEL_ID = "channel_id";
    static final String EXTRA_CHANNEL_NAME = "channel_name";
    static final String EXTRA_CHANNEL_DESCRIPTION = "channel_description";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_TEXT = "text";
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_SHORT_CRITICAL_TEXT = "short_critical_text";
    static final String EXTRA_CATEGORY = "category";
    static final String EXTRA_ONGOING = "ongoing";
    static final String EXTRA_ALERT_ONCE = "alert_once";
    static final String EXTRA_SHOW_CHRONOMETER = "show_chronometer";
    static final String EXTRA_PROMOTED = "promoted";
    static final String EXTRA_PROGRESS = "progress";
    static final String EXTRA_PROGRESS_MAX = "progress_max";
    static final String EXTRA_PROGRESS_INDETERMINATE = "progress_indeterminate";
    static final String EXTRA_STARTED_AT_MS = "started_at_ms";
    static final String EXTRA_TIMEOUT_AFTER_MS = "timeout_after_ms";
    static final String EXTRA_PRIORITY = "priority";
    static final String EXTRA_COLOR = "color";

    private static final Set<String> RESERVED_KEYS = new HashSet<>(Arrays.asList(
        EXTRA_SURFACE_ID, EXTRA_CHANNEL_ID, EXTRA_CHANNEL_NAME, EXTRA_CHANNEL_DESCRIPTION,
        EXTRA_TITLE, EXTRA_TEXT, EXTRA_STATUS, EXTRA_SHORT_CRITICAL_TEXT, EXTRA_CATEGORY, EXTRA_ONGOING, EXTRA_ALERT_ONCE,
        EXTRA_SHOW_CHRONOMETER, EXTRA_PROMOTED, EXTRA_PROGRESS, EXTRA_PROGRESS_MAX,
        EXTRA_PROGRESS_INDETERMINATE, EXTRA_STARTED_AT_MS, EXTRA_TIMEOUT_AFTER_MS,
        EXTRA_PRIORITY, EXTRA_COLOR
    ));

    enum Command {
        PUBLISH,
        CANCEL,
        CLEAR_ALL,
        OPEN_PROMOTION_SETTINGS
    }

    @NonNull
    static Command resolveCommand(@Nullable Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) return Command.CANCEL;
        if (ACTION_CLEAR_ALL.equals(action)) return Command.CLEAR_ALL;
        if (ACTION_OPEN_PROMOTION_SETTINGS.equals(action)) return Command.OPEN_PROMOTION_SETTINGS;
        return Command.PUBLISH;
    }

    @NonNull
    static String resolveSurfaceId(@Nullable Intent intent) {
        String surfaceId = intent == null ? null : intent.getStringExtra(EXTRA_SURFACE_ID);
        return TextUtils.isEmpty(surfaceId) ? "default" : surfaceId.trim();
    }

    @NonNull
    static SystemStatusSurfaceState decodeState(@Nullable Intent intent) {
        Bundle extras = intent == null || intent.getExtras() == null ? Bundle.EMPTY : intent.getExtras();
        String priority = extras.getString(EXTRA_PRIORITY, "default");
        return new SystemStatusSurfaceState.Builder()
            .setSurfaceId(extras.getString(EXTRA_SURFACE_ID, "default"))
            .setChannelId(extras.getString(EXTRA_CHANNEL_ID, "system-status-surface"))
            .setChannelName(extras.getString(EXTRA_CHANNEL_NAME, "System Status"))
            .setChannelDescription(extras.getString(EXTRA_CHANNEL_DESCRIPTION, "Decoupled system status surfaces"))
            .setTitle(extras.getString(EXTRA_TITLE, "System Status"))
            .setText(extras.getString(EXTRA_TEXT, ""))
            .setStatus(extras.getString(EXTRA_STATUS, ""))
            .setShortCriticalText(extras.getString(EXTRA_SHORT_CRITICAL_TEXT, ""))
            .setCategory(extras.getString(EXTRA_CATEGORY, NotificationCompat.CATEGORY_STATUS))
            .setOngoing(extras.getBoolean(EXTRA_ONGOING, true))
            .setAlertOnce(extras.getBoolean(EXTRA_ALERT_ONCE, true))
            .setShowChronometer(extras.getBoolean(EXTRA_SHOW_CHRONOMETER, false))
            .setPromoted(extras.getBoolean(EXTRA_PROMOTED, true))
            .setProgress(extras.getInt(EXTRA_PROGRESS, 0),
                extras.getInt(EXTRA_PROGRESS_MAX, 0),
                extras.getBoolean(EXTRA_PROGRESS_INDETERMINATE, false))
            .setStartedAtMs(extras.getLong(EXTRA_STARTED_AT_MS, 0L))
            .setTimeoutAfterMs(extras.getLong(EXTRA_TIMEOUT_AFTER_MS, 0L))
            .setPriority(parsePriority(priority))
            .setColorArgb(parseColor(extras.getString(EXTRA_COLOR, null)))
            .setPayload(extractPayload(extras))
            .build();
    }

    @NonNull
    private static Bundle extractPayload(@NonNull Bundle extras) {
        Bundle payload = new Bundle();
        for (String key : extras.keySet()) {
            if (key == null || RESERVED_KEYS.contains(key)) continue;
            Object value = extras.get(key);
            if (value == null) payload.putString(key, null);
            else if (value instanceof Integer) payload.putInt(key, (Integer) value);
            else if (value instanceof Long) payload.putLong(key, (Long) value);
            else if (value instanceof Boolean) payload.putBoolean(key, (Boolean) value);
            else if (value instanceof Double) payload.putDouble(key, (Double) value);
            else payload.putString(key, String.valueOf(value));
        }
        return payload;
    }

    private static int parsePriority(@Nullable String raw) {
        if (raw == null) return NotificationCompat.PRIORITY_DEFAULT;
        String normalized = raw.trim().toLowerCase();
        switch (normalized) {
            case "min":
                return NotificationCompat.PRIORITY_MIN;
            case "low":
                return NotificationCompat.PRIORITY_LOW;
            case "high":
                return NotificationCompat.PRIORITY_HIGH;
            case "max":
                return NotificationCompat.PRIORITY_MAX;
            default:
                return NotificationCompat.PRIORITY_DEFAULT;
        }
    }

    private static int parseColor(@Nullable String raw) {
        if (TextUtils.isEmpty(raw)) return 0;
        try {
            return Color.parseColor(raw.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }
}
