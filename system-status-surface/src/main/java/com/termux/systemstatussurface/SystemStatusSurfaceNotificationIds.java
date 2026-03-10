package com.termux.systemstatussurface;

import androidx.annotation.NonNull;

public final class SystemStatusSurfaceNotificationIds {

    private SystemStatusSurfaceNotificationIds() {
    }

    public static int notificationIdFor(@NonNull String surfaceId) {
        return 0x4F000000 + Math.abs(surfaceId.hashCode() % 0x00FFFFFF);
    }
}
