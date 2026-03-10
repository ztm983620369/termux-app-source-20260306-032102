package com.termux.cameracapsulesurface;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class CameraCapsuleSurfaceIntentCodec {

    static final String ACTION_PUBLISH = "com.termux.cameracapsulesurface.action.PUBLISH";
    static final String ACTION_CANCEL = "com.termux.cameracapsulesurface.action.CANCEL";
    static final String ACTION_CLEAR_ALL = "com.termux.cameracapsulesurface.action.CLEAR_ALL";
    static final String ACTION_RESTORE = "com.termux.cameracapsulesurface.action.RESTORE";
    static final String ACTION_OPEN_OVERLAY_SETTINGS = "com.termux.cameracapsulesurface.action.OPEN_OVERLAY_SETTINGS";
    static final String ACTION_SHOW_DEMO = "com.termux.cameracapsulesurface.action.SHOW_DEMO";
    static final String ACTION_REQUEST_SHIZUKU_PERMISSION = "com.termux.cameracapsulesurface.action.REQUEST_SHIZUKU_PERMISSION";
    static final String ACTION_RESTORE_STATUS_BAR = "com.termux.cameracapsulesurface.action.RESTORE_STATUS_BAR";

    static final String EXTRA_STATE_JSON = "state_json";
    static final String EXTRA_SURFACE_ID = "surface_id";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_TEXT = "text";
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_SHORT_TEXT = "short_text";
    static final String EXTRA_ONGOING = "ongoing";
    static final String EXTRA_EXPANDED = "expanded";
    static final String EXTRA_TOUCHABLE = "touchable";
    static final String EXTRA_DRAGGABLE = "draggable";
    static final String EXTRA_PROGRESS = "progress";
    static final String EXTRA_PROGRESS_MAX = "progress_max";
    static final String EXTRA_PROGRESS_INDETERMINATE = "progress_indeterminate";
    static final String EXTRA_PRIORITY = "priority";
    static final String EXTRA_COLOR = "color";
    static final String EXTRA_CONTROL_STATUS_BAR = "control_status_bar";
    static final String EXTRA_HIDE_STATUS_BAR_SYSTEM_ICONS = "hide_status_bar_system_icons";
    static final String EXTRA_HIDE_STATUS_BAR_CLOCK = "hide_status_bar_clock";
    static final String EXTRA_HIDE_STATUS_BAR_NOTIFICATION_ICONS = "hide_status_bar_notification_icons";
    static final String EXTRA_STATUS_BAR_PRIVILEGE_MODE = "status_bar_privilege_mode";
    static final String EXTRA_ANCHOR_MODE = "anchor_mode";
    static final String EXTRA_PLACEMENT_MODE = "placement_mode";
    static final String EXTRA_DOCK_EDGE = "dock_edge";
    static final String EXTRA_OFFSET_X_DP = "offset_x_dp";
    static final String EXTRA_OFFSET_Y_DP = "offset_y_dp";
    static final String EXTRA_WIDTH_DP = "width_dp";
    static final String EXTRA_HEIGHT_DP = "height_dp";
    static final String EXTRA_UPDATED_AT_MS = "updated_at_ms";

    private static final Set<String> RESERVED_KEYS = new HashSet<>(Arrays.asList(
        EXTRA_STATE_JSON, EXTRA_SURFACE_ID, EXTRA_TITLE, EXTRA_TEXT, EXTRA_STATUS, EXTRA_SHORT_TEXT,
        EXTRA_ONGOING, EXTRA_EXPANDED, EXTRA_TOUCHABLE, EXTRA_DRAGGABLE, EXTRA_PROGRESS,
        EXTRA_PROGRESS_MAX, EXTRA_PROGRESS_INDETERMINATE, EXTRA_PRIORITY, EXTRA_COLOR,
        EXTRA_CONTROL_STATUS_BAR, EXTRA_HIDE_STATUS_BAR_SYSTEM_ICONS, EXTRA_HIDE_STATUS_BAR_CLOCK,
        EXTRA_HIDE_STATUS_BAR_NOTIFICATION_ICONS, EXTRA_STATUS_BAR_PRIVILEGE_MODE,
        EXTRA_ANCHOR_MODE, EXTRA_PLACEMENT_MODE, EXTRA_DOCK_EDGE, EXTRA_OFFSET_X_DP, EXTRA_OFFSET_Y_DP, EXTRA_WIDTH_DP,
        EXTRA_HEIGHT_DP, EXTRA_UPDATED_AT_MS
    ));

    enum Command {
        PUBLISH,
        CANCEL,
        CLEAR_ALL,
        RESTORE,
        OPEN_OVERLAY_SETTINGS,
        SHOW_DEMO,
        REQUEST_SHIZUKU_PERMISSION,
        RESTORE_STATUS_BAR
    }

    private CameraCapsuleSurfaceIntentCodec() {
    }

    @NonNull
    static Command resolveCommand(@Nullable Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) return Command.CANCEL;
        if (ACTION_CLEAR_ALL.equals(action)) return Command.CLEAR_ALL;
        if (ACTION_RESTORE.equals(action)) return Command.RESTORE;
        if (ACTION_OPEN_OVERLAY_SETTINGS.equals(action)) return Command.OPEN_OVERLAY_SETTINGS;
        if (ACTION_SHOW_DEMO.equals(action)) return Command.SHOW_DEMO;
        if (ACTION_REQUEST_SHIZUKU_PERMISSION.equals(action)) return Command.REQUEST_SHIZUKU_PERMISSION;
        if (ACTION_RESTORE_STATUS_BAR.equals(action)) return Command.RESTORE_STATUS_BAR;
        return Command.PUBLISH;
    }

    @NonNull
    static String resolveSurfaceId(@Nullable Intent intent) {
        String surfaceId = intent == null ? null : intent.getStringExtra(EXTRA_SURFACE_ID);
        return TextUtils.isEmpty(surfaceId) ? "default" : surfaceId.trim();
    }

    @NonNull
    static CameraCapsuleSurfaceState decodeState(@Nullable Intent intent) {
        if (intent != null) {
            String rawJson = intent.getStringExtra(EXTRA_STATE_JSON);
            if (!TextUtils.isEmpty(rawJson)) {
                try {
                    return CameraCapsuleSurfaceState.fromJson(new JSONObject(rawJson));
                } catch (Exception ignored) {
                }
            }
        }
        Bundle extras = intent == null || intent.getExtras() == null ? Bundle.EMPTY : intent.getExtras();
        String priority = extras.getString(EXTRA_PRIORITY, "default");
        return new CameraCapsuleSurfaceState.Builder()
            .setSurfaceId(extras.getString(EXTRA_SURFACE_ID, "default"))
            .setTitle(extras.getString(EXTRA_TITLE, "Camera Capsule"))
            .setText(extras.getString(EXTRA_TEXT, ""))
            .setStatus(extras.getString(EXTRA_STATUS, ""))
            .setShortText(extras.getString(EXTRA_SHORT_TEXT, ""))
            .setOngoing(extras.getBoolean(EXTRA_ONGOING, true))
            .setExpanded(extras.getBoolean(EXTRA_EXPANDED, false))
            .setTouchable(extras.getBoolean(EXTRA_TOUCHABLE, true))
            .setDraggable(extras.getBoolean(EXTRA_DRAGGABLE, true))
            .setControlStatusBar(extras.getBoolean(EXTRA_CONTROL_STATUS_BAR, true))
            .setHideStatusBarSystemIcons(extras.getBoolean(EXTRA_HIDE_STATUS_BAR_SYSTEM_ICONS, true))
            .setHideStatusBarClock(extras.getBoolean(EXTRA_HIDE_STATUS_BAR_CLOCK, true))
            .setHideStatusBarNotificationIcons(extras.getBoolean(EXTRA_HIDE_STATUS_BAR_NOTIFICATION_ICONS, true))
            .setStatusBarPrivilegeMode(extras.getString(EXTRA_STATUS_BAR_PRIVILEGE_MODE, StatusBarPrivilegeMode.AUTO))
            .setProgress(
                extras.getInt(EXTRA_PROGRESS, 0),
                extras.getInt(EXTRA_PROGRESS_MAX, 0),
                extras.getBoolean(EXTRA_PROGRESS_INDETERMINATE, false))
            .setPriority(parsePriority(priority))
            .setColorArgb(parseColor(extras.getString(EXTRA_COLOR, null)))
            .setAnchorMode(extras.getString(EXTRA_ANCHOR_MODE, CameraCapsuleSurfaceState.ANCHOR_CUTOUT))
            .setPlacementMode(extras.getString(EXTRA_PLACEMENT_MODE, CameraCapsuleSurfaceState.PLACEMENT_FLOATING))
            .setDockEdge(extras.getString(EXTRA_DOCK_EDGE, CameraCapsuleSurfaceState.DOCK_EDGE_NONE))
            .setOffsetXDp(extras.getInt(EXTRA_OFFSET_X_DP, 0))
            .setOffsetYDp(extras.getInt(EXTRA_OFFSET_Y_DP, 0))
            .setWidthDp(extras.getInt(EXTRA_WIDTH_DP, 0))
            .setHeightDp(extras.getInt(EXTRA_HEIGHT_DP, 0))
            .setUpdatedAtMs(extras.getLong(EXTRA_UPDATED_AT_MS, 0L))
            .setPayload(extractPayload(extras))
            .build();
    }

    static void encodeState(@NonNull Intent intent, @NonNull CameraCapsuleSurfaceState state) {
        intent.putExtra(EXTRA_STATE_JSON, state.toJson().toString());
        intent.putExtra(EXTRA_SURFACE_ID, state.surfaceId);
    }

    @NonNull
    static CameraCapsuleSurfaceState buildDemoState() {
        return new CameraCapsuleSurfaceState.Builder()
            .setSurfaceId("demo")
            .setTitle("任务进行中")
            .setText("已切回桌面，悬浮胶囊仍保持可见")
            .setStatus("42%")
            .setShortText("42%")
            .setExpanded(true)
            .setTouchable(true)
            .setDraggable(true)
            .setControlStatusBar(true)
            .setHideStatusBarSystemIcons(true)
            .setHideStatusBarClock(true)
            .setHideStatusBarNotificationIcons(true)
            .setStatusBarPrivilegeMode(StatusBarPrivilegeMode.AUTO)
            .setProgress(42, 100, false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColorArgb(0xFF4CAF50)
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
