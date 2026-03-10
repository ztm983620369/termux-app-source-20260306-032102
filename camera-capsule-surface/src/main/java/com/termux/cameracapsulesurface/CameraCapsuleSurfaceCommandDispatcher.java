package com.termux.cameracapsulesurface;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class CameraCapsuleSurfaceCommandDispatcher {

    private CameraCapsuleSurfaceCommandDispatcher() {
    }

    @NonNull
    static String dispatch(@NonNull Context context, @Nullable Intent intent) {
        CameraCapsuleSurfaceEngine engine = new CameraCapsuleSurfaceEngine(context);
        CameraCapsuleSurfaceIntentCodec.Command command = CameraCapsuleSurfaceIntentCodec.resolveCommand(intent);
        switch (command) {
            case CANCEL:
                String surfaceId = CameraCapsuleSurfaceIntentCodec.resolveSurfaceId(intent);
                return "Cancelled surface: " + surfaceId + " -> " + engine.cancel(surfaceId).reason;
            case CLEAR_ALL:
                return "Cleared all surfaces -> " + engine.cancelAll().reason;
            case RESTORE:
                return "Restored surfaces -> " + engine.restore().reason + ", capabilities=" + engine.describePrivilegeCapabilities();
            case OPEN_OVERLAY_SETTINGS:
                engine.openOverlaySettings();
                return "Opened overlay settings";
            case SHOW_DEMO:
                return "Published demo surface -> " + engine.publish(
                    CameraCapsuleSurfaceIntentCodec.buildDemoState()).reason + ", capabilities=" + engine.describePrivilegeCapabilities();
            case REQUEST_SHIZUKU_PERMISSION:
                engine.requestShizukuPermission();
                return "Requested Shizuku permission, capabilities=" + engine.describePrivilegeCapabilities();
            case RESTORE_STATUS_BAR:
                engine.restoreStatusBar();
                return "Restored status bar, capabilities=" + engine.describePrivilegeCapabilities();
            case PUBLISH:
            default:
                CameraCapsuleSurfaceState state = CameraCapsuleSurfaceIntentCodec.decodeState(intent);
                return "Published surface: " + state.surfaceId + " -> " + engine.publish(state).reason
                    + ", capabilities=" + engine.describePrivilegeCapabilities();
        }
    }
}
