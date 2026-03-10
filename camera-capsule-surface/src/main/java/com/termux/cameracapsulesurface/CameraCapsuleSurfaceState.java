package com.termux.cameracapsulesurface;

import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

public final class CameraCapsuleSurfaceState {

    public static final String ANCHOR_CUTOUT = "cutout";
    public static final String ANCHOR_TOP_CENTER = "top_center";
    public static final String ANCHOR_MANUAL = "manual";
    public static final String PLACEMENT_FLOATING = "floating";
    public static final String PLACEMENT_DOCKED_TOP = "docked_top";
    public static final String PLACEMENT_DOCKED_LEFT = "docked_left";
    public static final String PLACEMENT_DOCKED_RIGHT = "docked_right";
    public static final String DOCK_EDGE_NONE = "none";
    public static final String DOCK_EDGE_LEFT = "left";
    public static final String DOCK_EDGE_CENTER = "center";
    public static final String DOCK_EDGE_RIGHT = "right";

    @NonNull public final String surfaceId;
    @NonNull public final String title;
    @NonNull public final String text;
    @NonNull public final String status;
    @NonNull public final String shortText;
    public final boolean ongoing;
    public final boolean expanded;
    public final boolean touchable;
    public final boolean draggable;
    public final boolean controlStatusBar;
    public final boolean hideStatusBarSystemIcons;
    public final boolean hideStatusBarClock;
    public final boolean hideStatusBarNotificationIcons;
    @NonNull public final String statusBarPrivilegeMode;
    public final int progressCurrent;
    public final int progressMax;
    public final boolean progressIndeterminate;
    public final int priority;
    @ColorInt public final int colorArgb;
    @NonNull public final String anchorMode;
    @NonNull public final String placementMode;
    @NonNull public final String dockEdge;
    public final int offsetXDp;
    public final int offsetYDp;
    public final int widthDp;
    public final int heightDp;
    public final long updatedAtMs;
    @NonNull public final Bundle payload;

    private CameraCapsuleSurfaceState(@NonNull Builder builder) {
        surfaceId = normalizeOrDefault(builder.surfaceId, "default");
        title = normalizeOrDefault(builder.title, "Camera Capsule");
        text = safe(builder.text);
        status = safe(builder.status);
        shortText = safe(builder.shortText);
        ongoing = builder.ongoing;
        expanded = builder.expanded;
        draggable = builder.draggable;
        touchable = builder.touchable || draggable;
        controlStatusBar = builder.controlStatusBar;
        hideStatusBarSystemIcons = builder.hideStatusBarSystemIcons;
        hideStatusBarClock = builder.hideStatusBarClock;
        hideStatusBarNotificationIcons = builder.hideStatusBarNotificationIcons;
        statusBarPrivilegeMode = StatusBarPrivilegeMode.normalize(builder.statusBarPrivilegeMode);
        progressCurrent = Math.max(0, builder.progressCurrent);
        progressMax = Math.max(0, builder.progressMax);
        progressIndeterminate = builder.progressIndeterminate;
        priority = builder.priority;
        colorArgb = builder.colorArgb;
        anchorMode = normalizeAnchorMode(builder.anchorMode);
        placementMode = normalizePlacementMode(builder.placementMode);
        dockEdge = normalizeDockEdge(builder.dockEdge);
        offsetXDp = builder.offsetXDp;
        offsetYDp = builder.offsetYDp;
        widthDp = Math.max(0, builder.widthDp);
        heightDp = Math.max(0, builder.heightDp);
        updatedAtMs = builder.updatedAtMs > 0L ? builder.updatedAtMs : System.currentTimeMillis();
        payload = builder.payload == null ? Bundle.EMPTY : new Bundle(builder.payload);
    }

    @NonNull
    public Builder buildUpon() {
        return new Builder()
            .setSurfaceId(surfaceId)
            .setTitle(title)
            .setText(text)
            .setStatus(status)
            .setShortText(shortText)
            .setOngoing(ongoing)
            .setExpanded(expanded)
            .setTouchable(touchable)
            .setDraggable(draggable)
            .setControlStatusBar(controlStatusBar)
            .setHideStatusBarSystemIcons(hideStatusBarSystemIcons)
            .setHideStatusBarClock(hideStatusBarClock)
            .setHideStatusBarNotificationIcons(hideStatusBarNotificationIcons)
            .setStatusBarPrivilegeMode(statusBarPrivilegeMode)
            .setProgress(progressCurrent, progressMax, progressIndeterminate)
            .setPriority(priority)
            .setColorArgb(colorArgb)
            .setAnchorMode(anchorMode)
            .setPlacementMode(placementMode)
            .setDockEdge(dockEdge)
            .setOffsetXDp(offsetXDp)
            .setOffsetYDp(offsetYDp)
            .setWidthDp(widthDp)
            .setHeightDp(heightDp)
            .setUpdatedAtMs(updatedAtMs)
            .setPayload(payload);
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("surfaceId", surfaceId);
            json.put("title", title);
            json.put("text", text);
            json.put("status", status);
            json.put("shortText", shortText);
            json.put("ongoing", ongoing);
            json.put("expanded", expanded);
            json.put("touchable", touchable);
            json.put("draggable", draggable);
            json.put("controlStatusBar", controlStatusBar);
            json.put("hideStatusBarSystemIcons", hideStatusBarSystemIcons);
            json.put("hideStatusBarClock", hideStatusBarClock);
            json.put("hideStatusBarNotificationIcons", hideStatusBarNotificationIcons);
            json.put("statusBarPrivilegeMode", statusBarPrivilegeMode);
            json.put("progressCurrent", progressCurrent);
            json.put("progressMax", progressMax);
            json.put("progressIndeterminate", progressIndeterminate);
            json.put("priority", priority);
            json.put("colorArgb", colorArgb);
            json.put("anchorMode", anchorMode);
            json.put("placementMode", placementMode);
            json.put("dockEdge", dockEdge);
            json.put("offsetXDp", offsetXDp);
            json.put("offsetYDp", offsetYDp);
            json.put("widthDp", widthDp);
            json.put("heightDp", heightDp);
            json.put("updatedAtMs", updatedAtMs);
            json.put("payload", bundleToJson(payload));
        } catch (JSONException ignored) {
        }
        return json;
    }

    @NonNull
    public static CameraCapsuleSurfaceState fromJson(@Nullable JSONObject json) {
        if (json == null) return new Builder().build();
        return new Builder()
            .setSurfaceId(json.optString("surfaceId", "default"))
            .setTitle(json.optString("title", "Camera Capsule"))
            .setText(json.optString("text", ""))
            .setStatus(json.optString("status", ""))
            .setShortText(json.optString("shortText", ""))
            .setOngoing(json.optBoolean("ongoing", true))
            .setExpanded(json.optBoolean("expanded", false))
            .setTouchable(json.optBoolean("touchable", true))
            .setDraggable(json.optBoolean("draggable", true))
            .setControlStatusBar(json.optBoolean("controlStatusBar", true))
            .setHideStatusBarSystemIcons(json.optBoolean("hideStatusBarSystemIcons", true))
            .setHideStatusBarClock(json.optBoolean("hideStatusBarClock", true))
            .setHideStatusBarNotificationIcons(json.optBoolean("hideStatusBarNotificationIcons", true))
            .setStatusBarPrivilegeMode(json.optString("statusBarPrivilegeMode", StatusBarPrivilegeMode.AUTO))
            .setProgress(
                json.optInt("progressCurrent", 0),
                json.optInt("progressMax", 0),
                json.optBoolean("progressIndeterminate", false))
            .setPriority(json.optInt("priority", NotificationCompat.PRIORITY_DEFAULT))
            .setColorArgb(json.optInt("colorArgb", 0))
            .setAnchorMode(json.optString("anchorMode", ANCHOR_CUTOUT))
            .setPlacementMode(json.optString("placementMode", PLACEMENT_FLOATING))
            .setDockEdge(json.optString("dockEdge", DOCK_EDGE_NONE))
            .setOffsetXDp(json.optInt("offsetXDp", 0))
            .setOffsetYDp(json.optInt("offsetYDp", 0))
            .setWidthDp(json.optInt("widthDp", 0))
            .setHeightDp(json.optInt("heightDp", 0))
            .setUpdatedAtMs(json.optLong("updatedAtMs", 0L))
            .setPayload(jsonToBundle(json.optJSONObject("payload")))
            .build();
    }

    @NonNull
    private static String normalizeAnchorMode(@Nullable String raw) {
        String normalized = safe(raw).trim().toLowerCase();
        if (ANCHOR_MANUAL.equals(normalized)) return ANCHOR_MANUAL;
        if (ANCHOR_TOP_CENTER.equals(normalized)) return ANCHOR_TOP_CENTER;
        return ANCHOR_CUTOUT;
    }

    @NonNull
    private static String normalizePlacementMode(@Nullable String raw) {
        String normalized = safe(raw).trim().toLowerCase();
        if (PLACEMENT_DOCKED_TOP.equals(normalized)) return PLACEMENT_DOCKED_TOP;
        if (PLACEMENT_DOCKED_LEFT.equals(normalized)) return PLACEMENT_DOCKED_LEFT;
        if (PLACEMENT_DOCKED_RIGHT.equals(normalized)) return PLACEMENT_DOCKED_RIGHT;
        return PLACEMENT_FLOATING;
    }

    @NonNull
    private static String normalizeDockEdge(@Nullable String raw) {
        String normalized = safe(raw).trim().toLowerCase();
        if (DOCK_EDGE_LEFT.equals(normalized)) return DOCK_EDGE_LEFT;
        if (DOCK_EDGE_CENTER.equals(normalized)) return DOCK_EDGE_CENTER;
        if (DOCK_EDGE_RIGHT.equals(normalized)) return DOCK_EDGE_RIGHT;
        return DOCK_EDGE_NONE;
    }

    @NonNull
    private static String normalizeOrDefault(@Nullable String value, @NonNull String defaultValue) {
        String safeValue = safe(value).trim();
        return safeValue.isEmpty() ? defaultValue : safeValue;
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }

    @NonNull
    private static JSONObject bundleToJson(@NonNull Bundle bundle) throws JSONException {
        JSONObject json = new JSONObject();
        for (String key : bundle.keySet()) {
            if (key == null) continue;
            Object value = bundle.get(key);
            if (value == null) json.put(key, JSONObject.NULL);
            else json.put(key, value);
        }
        return json;
    }

    @NonNull
    private static Bundle jsonToBundle(@Nullable JSONObject json) {
        if (json == null) return Bundle.EMPTY;
        Bundle bundle = new Bundle();
        Iterator<String> iterator = json.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            Object value = json.opt(key);
            if (JSONObject.NULL.equals(value) || value == null) {
                bundle.putString(key, null);
            } else if (value instanceof Integer) {
                bundle.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                bundle.putLong(key, (Long) value);
            } else if (value instanceof Boolean) {
                bundle.putBoolean(key, (Boolean) value);
            } else if (value instanceof Double) {
                bundle.putDouble(key, (Double) value);
            } else {
                bundle.putString(key, String.valueOf(value));
            }
        }
        return bundle;
    }

    public static final class Builder {
        private String surfaceId;
        private String title;
        private String text;
        private String status;
        private String shortText;
        private boolean ongoing = true;
        private boolean expanded;
        private boolean touchable = true;
        private boolean draggable = true;
        private boolean controlStatusBar = true;
        private boolean hideStatusBarSystemIcons = true;
        private boolean hideStatusBarClock = true;
        private boolean hideStatusBarNotificationIcons = true;
        private String statusBarPrivilegeMode = StatusBarPrivilegeMode.AUTO;
        private int progressCurrent;
        private int progressMax;
        private boolean progressIndeterminate;
        private int priority = NotificationCompat.PRIORITY_DEFAULT;
        private int colorArgb;
        private String anchorMode = ANCHOR_CUTOUT;
        private String placementMode = PLACEMENT_FLOATING;
        private String dockEdge = DOCK_EDGE_NONE;
        private int offsetXDp;
        private int offsetYDp;
        private int widthDp;
        private int heightDp;
        private long updatedAtMs;
        private Bundle payload = new Bundle();

        @NonNull
        public Builder setSurfaceId(@Nullable String surfaceId) {
            this.surfaceId = surfaceId;
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
        public Builder setShortText(@Nullable String shortText) {
            this.shortText = shortText;
            return this;
        }

        @NonNull
        public Builder setOngoing(boolean ongoing) {
            this.ongoing = ongoing;
            return this;
        }

        @NonNull
        public Builder setExpanded(boolean expanded) {
            this.expanded = expanded;
            return this;
        }

        @NonNull
        public Builder setTouchable(boolean touchable) {
            this.touchable = touchable;
            return this;
        }

        @NonNull
        public Builder setDraggable(boolean draggable) {
            this.draggable = draggable;
            return this;
        }

        @NonNull
        public Builder setControlStatusBar(boolean controlStatusBar) {
            this.controlStatusBar = controlStatusBar;
            return this;
        }

        @NonNull
        public Builder setHideStatusBarSystemIcons(boolean hideStatusBarSystemIcons) {
            this.hideStatusBarSystemIcons = hideStatusBarSystemIcons;
            return this;
        }

        @NonNull
        public Builder setHideStatusBarClock(boolean hideStatusBarClock) {
            this.hideStatusBarClock = hideStatusBarClock;
            return this;
        }

        @NonNull
        public Builder setHideStatusBarNotificationIcons(boolean hideStatusBarNotificationIcons) {
            this.hideStatusBarNotificationIcons = hideStatusBarNotificationIcons;
            return this;
        }

        @NonNull
        public Builder setStatusBarPrivilegeMode(@Nullable String statusBarPrivilegeMode) {
            this.statusBarPrivilegeMode = statusBarPrivilegeMode;
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
        public Builder setAnchorMode(@Nullable String anchorMode) {
            this.anchorMode = anchorMode;
            return this;
        }

        @NonNull
        public Builder setPlacementMode(@Nullable String placementMode) {
            this.placementMode = placementMode;
            return this;
        }

        @NonNull
        public Builder setDockEdge(@Nullable String dockEdge) {
            this.dockEdge = dockEdge;
            return this;
        }

        @NonNull
        public Builder setOffsetXDp(int offsetXDp) {
            this.offsetXDp = offsetXDp;
            return this;
        }

        @NonNull
        public Builder setOffsetYDp(int offsetYDp) {
            this.offsetYDp = offsetYDp;
            return this;
        }

        @NonNull
        public Builder setWidthDp(int widthDp) {
            this.widthDp = widthDp;
            return this;
        }

        @NonNull
        public Builder setHeightDp(int heightDp) {
            this.heightDp = heightDp;
            return this;
        }

        @NonNull
        public Builder setUpdatedAtMs(long updatedAtMs) {
            this.updatedAtMs = updatedAtMs;
            return this;
        }

        @NonNull
        public Builder setPayload(@Nullable Bundle payload) {
            this.payload = payload == null ? new Bundle() : new Bundle(payload);
            return this;
        }

        @NonNull
        public CameraCapsuleSurfaceState build() {
            return new CameraCapsuleSurfaceState(this);
        }
    }
}
