package com.termux.cameracapsulesurface;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.systemstatussurface.SystemStatusSurfaceEngine;

import java.util.ArrayList;

public final class CameraCapsuleSurfaceEngine {

    @NonNull
    private final Context appContext;
    @NonNull
    private final CameraCapsuleSurfaceStore store;
    @NonNull
    private final CameraCapsuleSurfaceStateMachine stateMachine;
    @NonNull
    private final CameraCapsuleSurfaceFallbackBridge fallbackBridge;
    @NonNull
    private final StatusBarControlCoordinator statusBarCoordinator;

    public CameraCapsuleSurfaceEngine(@NonNull Context context) {
        appContext = context.getApplicationContext();
        store = new CameraCapsuleSurfaceStore(appContext);
        stateMachine = new CameraCapsuleSurfaceStateMachine();
        fallbackBridge = new CameraCapsuleSurfaceFallbackBridge(new SystemStatusSurfaceEngine(appContext));
        statusBarCoordinator = new StatusBarControlCoordinator(appContext);
    }

    @NonNull
    public CameraCapsuleSurfaceStateMachine.Snapshot publish(@NonNull CameraCapsuleSurfaceState state) {
        store.save(preserveStablePlacement(state));
        return reconcile();
    }

    @NonNull
    public CameraCapsuleSurfaceStateMachine.Snapshot cancel(@NonNull String surfaceId) {
        store.remove(surfaceId);
        return reconcile();
    }

    @NonNull
    public CameraCapsuleSurfaceStateMachine.Snapshot cancelAll() {
        store.clear();
        return reconcile();
    }

    @NonNull
    public CameraCapsuleSurfaceStateMachine.Snapshot restore() {
        return reconcile();
    }

    public void openOverlaySettings() {
        OverlayPermissionGate.openOverlaySettings(appContext);
    }

    public void requestShizukuPermission() {
        CameraCapsuleSurfacePermissionActivity.start(appContext);
    }

    public void restoreStatusBar() {
        statusBarCoordinator.restoreForce();
    }

    @NonNull
    public String describePrivilegeCapabilities() {
        return statusBarCoordinator.describeCapabilities();
    }

    @Nullable
    public CameraCapsuleSurfaceState get(@NonNull String surfaceId) {
        return store.load(surfaceId);
    }

    @NonNull
    public ArrayList<CameraCapsuleSurfaceState> list() {
        return store.list();
    }

    @NonNull
    public CameraCapsuleSurfaceStateMachine.Snapshot getRuntimeSnapshot() {
        return stateMachine.evaluate(store.list(), OverlayPermissionGate.canDrawOverlays(appContext));
    }

    @NonNull
    private CameraCapsuleSurfaceStateMachine.Snapshot reconcile() {
        CameraCapsuleSurfaceStateMachine.Snapshot snapshot = stateMachine.evaluate(
            store.list(), OverlayPermissionGate.canDrawOverlays(appContext));
        switch (snapshot.controlState) {
            case IDLE:
                CameraCapsuleSurfaceService.stop(appContext);
                fallbackBridge.cancel();
                statusBarCoordinator.restoreIfNeeded();
                break;
            case PERMISSION_BLOCKED:
                CameraCapsuleSurfaceService.stop(appContext);
                if (snapshot.primaryState != null) fallbackBridge.publish(snapshot.primaryState);
                statusBarCoordinator.restoreIfNeeded();
                break;
            case ACTIVE:
            default:
                if (snapshot.primaryState != null) fallbackBridge.publish(snapshot.primaryState);
                if (snapshot.primaryState != null) statusBarCoordinator.applyFor(snapshot.primaryState);
                if (snapshot.primaryState != null) CameraCapsuleSurfaceService.startOrSync(appContext, snapshot.primaryState);
                break;
        }
        return snapshot;
    }

    @NonNull
    private CameraCapsuleSurfaceState preserveStablePlacement(@NonNull CameraCapsuleSurfaceState incomingState) {
        CameraCapsuleSurfaceState storedState = store.load(incomingState.surfaceId);
        if (storedState == null || !shouldReusePlacement(incomingState)) return incomingState;
        return incomingState.buildUpon()
            .setAnchorMode(storedState.anchorMode)
            .setPlacementMode(storedState.placementMode)
            .setDockEdge(storedState.dockEdge)
            .setOffsetXDp(storedState.offsetXDp)
            .setOffsetYDp(storedState.offsetYDp)
            .setWidthDp(storedState.widthDp)
            .setHeightDp(storedState.heightDp)
            .build();
    }

    private boolean shouldReusePlacement(@NonNull CameraCapsuleSurfaceState state) {
        return CameraCapsuleSurfaceState.ANCHOR_CUTOUT.equals(state.anchorMode)
            && state.offsetXDp == 0
            && state.offsetYDp == 0
            && state.widthDp == 0
            && state.heightDp == 0;
    }
}
