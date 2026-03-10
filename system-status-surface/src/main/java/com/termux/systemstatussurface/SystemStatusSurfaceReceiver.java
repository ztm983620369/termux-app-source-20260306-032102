package com.termux.systemstatussurface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class SystemStatusSurfaceReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "SystemStatusSurface";

    @Override
    public void onReceive(@NonNull Context context, @Nullable Intent intent) {
        SystemStatusSurfaceEngine engine = new SystemStatusSurfaceEngine(context);
        SystemStatusSurfaceIntentCodec.Command command = SystemStatusSurfaceIntentCodec.resolveCommand(intent);
        switch (command) {
            case CANCEL:
                String surfaceId = SystemStatusSurfaceIntentCodec.resolveSurfaceId(intent);
                engine.cancel(surfaceId);
                Log.i(LOG_TAG, "Cancelled surface: " + surfaceId);
                return;
            case CLEAR_ALL:
                engine.cancelAll();
                Log.i(LOG_TAG, "Cleared all surfaces");
                return;
            case OPEN_PROMOTION_SETTINGS:
                new NotificationStatusSurfaceRenderer(context).openPromotionSettings();
                Log.i(LOG_TAG, "Opened promotion settings");
                return;
            case PUBLISH:
            default:
                SystemStatusSurfaceState state = SystemStatusSurfaceIntentCodec.decodeState(intent);
                engine.publish(state);
                Log.i(LOG_TAG, "Published surface: " + state.surfaceId);
        }
    }
}
