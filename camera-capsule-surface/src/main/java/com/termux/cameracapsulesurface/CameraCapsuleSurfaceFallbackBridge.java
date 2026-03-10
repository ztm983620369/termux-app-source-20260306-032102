package com.termux.cameracapsulesurface;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.termux.systemstatussurface.SystemStatusSurfaceEngine;
import com.termux.systemstatussurface.SystemStatusSurfaceNotificationIds;
import com.termux.systemstatussurface.SystemStatusSurfaceState;

final class CameraCapsuleSurfaceFallbackBridge {

    static final String FALLBACK_SURFACE_ID = "camera_capsule_overlay";
    static final String CHANNEL_ID = "camera-capsule-surface";
    static final String CHANNEL_NAME = "Camera Capsule Surface";
    static final String CHANNEL_DESCRIPTION = "Fallback notification bridge for the camera capsule overlay";

    @NonNull
    private final SystemStatusSurfaceEngine engine;

    CameraCapsuleSurfaceFallbackBridge(@NonNull SystemStatusSurfaceEngine engine) {
        this.engine = engine;
    }

    void publish(@NonNull CameraCapsuleSurfaceState state) {
        engine.publish(toSystemStatusState(state));
    }

    void cancel() {
        engine.cancel(FALLBACK_SURFACE_ID);
    }

    int notificationId() {
        return SystemStatusSurfaceNotificationIds.notificationIdFor(FALLBACK_SURFACE_ID);
    }

    @NonNull
    String channelId() {
        return CHANNEL_ID;
    }

    @NonNull
    private SystemStatusSurfaceState toSystemStatusState(@NonNull CameraCapsuleSurfaceState state) {
        return new SystemStatusSurfaceState.Builder()
            .setSurfaceId(FALLBACK_SURFACE_ID)
            .setChannelId(CHANNEL_ID)
            .setChannelName(CHANNEL_NAME)
            .setChannelDescription(CHANNEL_DESCRIPTION)
            .setTitle(state.title)
            .setText(state.text)
            .setStatus(resolveStatus(state))
            .setShortCriticalText(resolveShortText(state))
            .setOngoing(state.ongoing)
            .setAlertOnce(true)
            .setPromoted(state.ongoing)
            .setProgress(state.progressCurrent, state.progressMax, state.progressIndeterminate)
            .setPriority(state.priority)
            .setColorArgb(state.colorArgb)
            .setPayload(state.payload)
            .build();
    }

    @NonNull
    private String resolveStatus(@NonNull CameraCapsuleSurfaceState state) {
        if (!TextUtils.isEmpty(state.status)) return state.status;
        if (state.progressMax > 0 && !state.progressIndeterminate) {
            return state.progressCurrent + "/" + state.progressMax;
        }
        return "";
    }

    @NonNull
    private String resolveShortText(@NonNull CameraCapsuleSurfaceState state) {
        if (!TextUtils.isEmpty(state.shortText)) return state.shortText;
        if (!TextUtils.isEmpty(state.status)) return state.status;
        if (state.progressMax > 0 && !state.progressIndeterminate) {
            int percent = Math.max(0, Math.min(100,
                (int) Math.round(state.progressCurrent * 100.0d / Math.max(1, state.progressMax))));
            return percent + "%";
        }
        return "";
    }
}
