package com.termux.cameracapsulesurface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

public final class CameraCapsuleSurfaceStateMachine {

    public enum ControlState {
        IDLE,
        PERMISSION_BLOCKED,
        ACTIVE
    }

    public enum ServiceCommand {
        STOP,
        START_OR_SYNC
    }

    public static final class Snapshot {
        @NonNull public final ControlState controlState;
        @NonNull public final ServiceCommand serviceCommand;
        public final boolean shouldRenderOverlay;
        public final boolean shouldPublishFallback;
        public final int activeSurfaceCount;
        @Nullable public final CameraCapsuleSurfaceState primaryState;
        @NonNull public final String reason;

        private Snapshot(@NonNull ControlState controlState,
                         @NonNull ServiceCommand serviceCommand,
                         boolean shouldRenderOverlay,
                         boolean shouldPublishFallback,
                         int activeSurfaceCount,
                         @Nullable CameraCapsuleSurfaceState primaryState,
                         @NonNull String reason) {
            this.controlState = controlState;
            this.serviceCommand = serviceCommand;
            this.shouldRenderOverlay = shouldRenderOverlay;
            this.shouldPublishFallback = shouldPublishFallback;
            this.activeSurfaceCount = activeSurfaceCount;
            this.primaryState = primaryState;
            this.reason = reason;
        }
    }

    @NonNull
    public Snapshot evaluate(@NonNull List<CameraCapsuleSurfaceState> states, boolean overlayPermissionGranted) {
        CameraCapsuleSurfaceState primaryState = CameraCapsuleSurfaceSelector.selectPrimary(states);
        int activeSurfaceCount = states.size();
        if (primaryState == null || activeSurfaceCount == 0) {
            return new Snapshot(
                ControlState.IDLE,
                ServiceCommand.STOP,
                false,
                false,
                0,
                null,
                "no_active_surfaces");
        }
        if (!overlayPermissionGranted) {
            return new Snapshot(
                ControlState.PERMISSION_BLOCKED,
                ServiceCommand.STOP,
                false,
                true,
                activeSurfaceCount,
                primaryState,
                "overlay_permission_missing");
        }
        return new Snapshot(
            ControlState.ACTIVE,
            ServiceCommand.START_OR_SYNC,
            true,
            true,
            activeSurfaceCount,
            primaryState,
            "overlay_active");
    }
}
