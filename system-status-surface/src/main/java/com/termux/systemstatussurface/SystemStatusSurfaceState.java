package com.termux.systemstatussurface;

import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

public final class SystemStatusSurfaceState {

    @NonNull public final String surfaceId;
    @NonNull public final String channelId;
    @NonNull public final String channelName;
    @NonNull public final String channelDescription;
    @NonNull public final String title;
    @NonNull public final String text;
    @NonNull public final String status;
    @NonNull public final String shortCriticalText;
    @NonNull public final String category;
    public final boolean ongoing;
    public final boolean alertOnce;
    public final boolean showChronometer;
    public final boolean promoted;
    public final int progressCurrent;
    public final int progressMax;
    public final boolean progressIndeterminate;
    public final long startedAtMs;
    public final long timeoutAfterMs;
    public final int priority;
    @ColorInt public final int colorArgb;
    @NonNull public final Bundle payload;

    private SystemStatusSurfaceState(@NonNull Builder builder) {
        surfaceId = normalizeOrDefault(builder.surfaceId, "default");
        channelId = normalizeOrDefault(builder.channelId, "system-status-surface");
        channelName = normalizeOrDefault(builder.channelName, "System Status");
        channelDescription = normalizeOrDefault(builder.channelDescription, "Decoupled system status surfaces");
        title = normalizeOrDefault(builder.title, "System Status");
        text = safe(builder.text);
        status = safe(builder.status);
        shortCriticalText = safe(builder.shortCriticalText);
        category = normalizeOrDefault(builder.category, NotificationCompat.CATEGORY_STATUS);
        ongoing = builder.ongoing;
        alertOnce = builder.alertOnce;
        showChronometer = builder.showChronometer;
        promoted = builder.promoted;
        progressCurrent = Math.max(0, builder.progressCurrent);
        progressMax = Math.max(0, builder.progressMax);
        progressIndeterminate = builder.progressIndeterminate;
        startedAtMs = Math.max(0L, builder.startedAtMs);
        timeoutAfterMs = Math.max(0L, builder.timeoutAfterMs);
        priority = builder.priority;
        colorArgb = builder.colorArgb;
        payload = builder.payload == null ? Bundle.EMPTY : new Bundle(builder.payload);
    }

    @NonNull
    public Builder buildUpon() {
        return new Builder()
            .setSurfaceId(surfaceId)
            .setChannelId(channelId)
            .setChannelName(channelName)
            .setChannelDescription(channelDescription)
            .setTitle(title)
            .setText(text)
            .setStatus(status)
            .setShortCriticalText(shortCriticalText)
            .setCategory(category)
            .setOngoing(ongoing)
            .setAlertOnce(alertOnce)
            .setShowChronometer(showChronometer)
            .setPromoted(promoted)
            .setProgress(progressCurrent, progressMax, progressIndeterminate)
            .setStartedAtMs(startedAtMs)
            .setTimeoutAfterMs(timeoutAfterMs)
            .setPriority(priority)
            .setColorArgb(colorArgb)
            .setPayload(payload);
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("surfaceId", surfaceId);
            json.put("channelId", channelId);
            json.put("channelName", channelName);
            json.put("channelDescription", channelDescription);
            json.put("title", title);
            json.put("text", text);
            json.put("status", status);
            json.put("shortCriticalText", shortCriticalText);
            json.put("category", category);
            json.put("ongoing", ongoing);
            json.put("alertOnce", alertOnce);
            json.put("showChronometer", showChronometer);
            json.put("promoted", promoted);
            json.put("progressCurrent", progressCurrent);
            json.put("progressMax", progressMax);
            json.put("progressIndeterminate", progressIndeterminate);
            json.put("startedAtMs", startedAtMs);
            json.put("timeoutAfterMs", timeoutAfterMs);
            json.put("priority", priority);
            json.put("colorArgb", colorArgb);
            json.put("payload", bundleToJson(payload));
        } catch (JSONException ignored) {
        }
        return json;
    }

    @NonNull
    public static SystemStatusSurfaceState fromJson(@Nullable JSONObject json) {
        if (json == null) return new Builder().build();
        return new Builder()
            .setSurfaceId(json.optString("surfaceId", "default"))
            .setChannelId(json.optString("channelId", "system-status-surface"))
            .setChannelName(json.optString("channelName", "System Status"))
            .setChannelDescription(json.optString("channelDescription", "Decoupled system status surfaces"))
            .setTitle(json.optString("title", "System Status"))
            .setText(json.optString("text", ""))
            .setStatus(json.optString("status", ""))
            .setShortCriticalText(json.optString("shortCriticalText", ""))
            .setCategory(json.optString("category", NotificationCompat.CATEGORY_STATUS))
            .setOngoing(json.optBoolean("ongoing", true))
            .setAlertOnce(json.optBoolean("alertOnce", true))
            .setShowChronometer(json.optBoolean("showChronometer", false))
            .setPromoted(json.optBoolean("promoted", false))
            .setProgress(json.optInt("progressCurrent", 0), json.optInt("progressMax", 0),
                json.optBoolean("progressIndeterminate", false))
            .setStartedAtMs(json.optLong("startedAtMs", 0L))
            .setTimeoutAfterMs(json.optLong("timeoutAfterMs", 0L))
            .setPriority(json.optInt("priority", NotificationCompat.PRIORITY_DEFAULT))
            .setColorArgb(json.optInt("colorArgb", 0))
            .setPayload(jsonToBundle(json.optJSONObject("payload")))
            .build();
    }

    @NonNull
    private static String normalizeOrDefault(@Nullable String value, @NonNull String fallback) {
        String normalized = safe(value).trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }

    @NonNull
    private static JSONObject bundleToJson(@NonNull Bundle bundle) throws JSONException {
        JSONObject json = new JSONObject();
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            if (value == null) {
                json.put(key, JSONObject.NULL);
            } else if (value instanceof Integer || value instanceof Long ||
                value instanceof Double || value instanceof Float ||
                value instanceof Boolean || value instanceof String) {
                json.put(key, value);
            } else {
                json.put(key, String.valueOf(value));
            }
        }
        return json;
    }

    @NonNull
    private static Bundle jsonToBundle(@Nullable JSONObject json) {
        Bundle bundle = new Bundle();
        if (json == null) return bundle;
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = json.opt(key);
            if (value == null || value == JSONObject.NULL) {
                bundle.putString(key, null);
            } else if (value instanceof Integer) {
                bundle.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                bundle.putLong(key, (Long) value);
            } else if (value instanceof Double) {
                bundle.putDouble(key, (Double) value);
            } else if (value instanceof Boolean) {
                bundle.putBoolean(key, (Boolean) value);
            } else {
                bundle.putString(key, String.valueOf(value));
            }
        }
        return bundle;
    }

    public static final class Builder {
        private String surfaceId;
        private String channelId;
        private String channelName;
        private String channelDescription;
        private String title;
        private String text;
        private String status;
        private String shortCriticalText;
        private String category;
        private boolean ongoing = true;
        private boolean alertOnce = true;
        private boolean showChronometer;
        private boolean promoted;
        private int progressCurrent;
        private int progressMax;
        private boolean progressIndeterminate;
        private long startedAtMs;
        private long timeoutAfterMs;
        private int priority = NotificationCompat.PRIORITY_DEFAULT;
        private int colorArgb;
        private Bundle payload = new Bundle();

        @NonNull
        public Builder setSurfaceId(@Nullable String surfaceId) {
            this.surfaceId = surfaceId;
            return this;
        }

        @NonNull
        public Builder setChannelId(@Nullable String channelId) {
            this.channelId = channelId;
            return this;
        }

        @NonNull
        public Builder setChannelName(@Nullable String channelName) {
            this.channelName = channelName;
            return this;
        }

        @NonNull
        public Builder setChannelDescription(@Nullable String channelDescription) {
            this.channelDescription = channelDescription;
            return this;
        }

        @NonNull
        public Builder setTitle(@Nullable String title) {
            this.title = title;
            return this;
        }

        @NonNull
        public Builder setText(@Nullable String text) {
            this.text = text;
            return this;
        }

        @NonNull
        public Builder setStatus(@Nullable String status) {
            this.status = status;
            return this;
        }

        @NonNull
        public Builder setShortCriticalText(@Nullable String shortCriticalText) {
            this.shortCriticalText = shortCriticalText;
            return this;
        }

        @NonNull
        public Builder setCategory(@Nullable String category) {
            this.category = category;
            return this;
        }

        @NonNull
        public Builder setOngoing(boolean ongoing) {
            this.ongoing = ongoing;
            return this;
        }

        @NonNull
        public Builder setAlertOnce(boolean alertOnce) {
            this.alertOnce = alertOnce;
            return this;
        }

        @NonNull
        public Builder setShowChronometer(boolean showChronometer) {
            this.showChronometer = showChronometer;
            return this;
        }

        @NonNull
        public Builder setPromoted(boolean promoted) {
            this.promoted = promoted;
            return this;
        }

        @NonNull
        public Builder setProgress(int progressCurrent, int progressMax, boolean progressIndeterminate) {
            this.progressCurrent = progressCurrent;
            this.progressMax = progressMax;
            this.progressIndeterminate = progressIndeterminate;
            return this;
        }

        @NonNull
        public Builder setStartedAtMs(long startedAtMs) {
            this.startedAtMs = startedAtMs;
            return this;
        }

        @NonNull
        public Builder setTimeoutAfterMs(long timeoutAfterMs) {
            this.timeoutAfterMs = timeoutAfterMs;
            return this;
        }

        @NonNull
        public Builder setPriority(int priority) {
            this.priority = priority;
            return this;
        }

        @NonNull
        public Builder setColorArgb(@ColorInt int colorArgb) {
            this.colorArgb = colorArgb;
            return this;
        }

        @NonNull
        public Builder setPayload(@Nullable Bundle payload) {
            this.payload = payload == null ? new Bundle() : new Bundle(payload);
            return this;
        }

        @NonNull
        public Builder putPayload(@NonNull String key, @Nullable String value) {
            if (!TextUtils.isEmpty(key)) payload.putString(key, value);
            return this;
        }

        @NonNull
        public SystemStatusSurfaceState build() {
            return new SystemStatusSurfaceState(this);
        }
    }
}
