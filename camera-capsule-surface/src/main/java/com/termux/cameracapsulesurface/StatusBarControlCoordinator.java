package com.termux.cameracapsulesurface;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class StatusBarControlCoordinator {

    private static final String LOG_TAG = "CapsuleStatusBar";

    @NonNull
    private final DirectStatusBarControlBackend directBackend;
    @NonNull
    private final RootStatusBarControlBackend rootBackend;
    @NonNull
    private final ShizukuStatusBarControlBackend shizukuBackend;
    @NonNull
    private final StatusBarControlStore store;

    StatusBarControlCoordinator(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        directBackend = new DirectStatusBarControlBackend(appContext);
        rootBackend = new RootStatusBarControlBackend();
        shizukuBackend = new ShizukuStatusBarControlBackend(appContext);
        store = new StatusBarControlStore(appContext);
    }

    void applyFor(@NonNull CameraCapsuleSurfaceState state) {
        if (!state.controlStatusBar) {
            restoreIfNeeded();
            return;
        }
        StatusBarDisableSpec spec = StatusBarDisableSpec.from(state);
        if (spec.isEmpty()) {
            restoreIfNeeded();
            return;
        }
        if (store.isApplied()
            && spec.signature().equals(store.getSpecSignature())
            && state.statusBarPrivilegeMode.equals(store.getBackend())) {
            return;
        }
        StatusBarControlResult result = dispatchApply(state.statusBarPrivilegeMode, spec);
        logResult("apply", result);
        if (result.success) {
            store.saveApplied(result.backendName, spec.signature());
        }
    }

    void restoreIfNeeded() {
        if (!store.isApplied()) return;
        StatusBarControlResult result = dispatchRestore(store.getBackend());
        logResult("restore", result);
        if (result.success) store.clear();
    }

    void restoreForce() {
        StatusBarControlResult result = dispatchRestore(StatusBarPrivilegeMode.AUTO);
        logResult("restore_force", result);
        if (result.success) store.clear();
    }

    boolean canRequestShizukuPermission() {
        return shizukuBackend.isSupported() && !shizukuBackend.isPermissionGranted();
    }

    @NonNull
    String describeCapabilities() {
        return "shizuku=" + shizukuBackend.isSupported() +
            ", shizukuPermission=" + shizukuBackend.isPermissionGranted() +
            ", root=" + rootBackend.isSupported() +
            ", directWriteSecureSettings=" + directBackend.hasElevatedAppPermission();
    }

    @Nullable
    ShizukuStatusBarControlBackend getShizukuBackend() {
        return shizukuBackend;
    }

    @NonNull
    private StatusBarControlResult dispatchApply(@NonNull String requestedMode,
                                                 @NonNull StatusBarDisableSpec spec) {
        String mode = StatusBarPrivilegeMode.normalize(requestedMode);
        switch (mode) {
            case StatusBarPrivilegeMode.SHIZUKU:
                return shizukuBackend.apply(spec);
            case StatusBarPrivilegeMode.ROOT:
                return rootBackend.apply(spec);
            case StatusBarPrivilegeMode.DIRECT:
                return directBackend.apply(spec);
            case StatusBarPrivilegeMode.NONE:
                return StatusBarControlResult.success(StatusBarPrivilegeMode.NONE, "status bar control disabled");
            case StatusBarPrivilegeMode.AUTO:
            default:
                if (shizukuBackend.isSupported()) {
                    StatusBarControlResult shizukuResult = shizukuBackend.apply(spec);
                    if (shizukuResult.success || shizukuResult.permissionRequired) return shizukuResult;
                }
                if (rootBackend.isSupported()) {
                    StatusBarControlResult rootResult = rootBackend.apply(spec);
                    if (rootResult.success) return rootResult;
                }
                return directBackend.apply(spec);
        }
    }

    @NonNull
    private StatusBarControlResult dispatchRestore(@NonNull String requestedMode) {
        String mode = StatusBarPrivilegeMode.normalize(requestedMode);
        switch (mode) {
            case StatusBarPrivilegeMode.SHIZUKU:
                return shizukuBackend.restore();
            case StatusBarPrivilegeMode.ROOT:
                return rootBackend.restore();
            case StatusBarPrivilegeMode.DIRECT:
                return directBackend.restore();
            case StatusBarPrivilegeMode.NONE:
                return StatusBarControlResult.success(StatusBarPrivilegeMode.NONE, "status bar control disabled");
            case StatusBarPrivilegeMode.AUTO:
            default:
                if (shizukuBackend.isSupported()) {
                    StatusBarControlResult shizukuResult = shizukuBackend.restore();
                    if (shizukuResult.success || shizukuResult.permissionRequired) return shizukuResult;
                }
                if (rootBackend.isSupported()) {
                    StatusBarControlResult rootResult = rootBackend.restore();
                    if (rootResult.success) return rootResult;
                }
                return directBackend.restore();
        }
    }

    private void logResult(@NonNull String phase, @NonNull StatusBarControlResult result) {
        if (result.success) {
            Log.i(LOG_TAG, phase + ": backend=" + result.backendName + ", message=" + result.message);
            return;
        }
        if (result.permissionRequired) {
            Log.w(LOG_TAG, phase + ": backend=" + result.backendName + ", permission required, message=" + result.message);
            return;
        }
        Log.w(LOG_TAG, phase + ": backend=" + result.backendName + ", failed, message=" + result.message);
    }
}
