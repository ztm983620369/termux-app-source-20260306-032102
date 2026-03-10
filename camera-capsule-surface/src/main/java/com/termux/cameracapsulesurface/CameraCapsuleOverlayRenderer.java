package com.termux.cameracapsulesurface;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.Choreographer;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

final class CameraCapsuleOverlayRenderer {

    private static final String LOG_TAG = "CameraCapsuleOverlay";

    interface DragStatePersistence {
        void persist(@NonNull CameraCapsuleSurfaceState state);
    }

    @NonNull
    private final Context context;
    @NonNull
    private final WindowManager windowManager;
    @NonNull
    private final CameraCapsulePlacementEngine placementEngine;
    @NonNull
    private final CameraCapsuleGesturePhysics gesturePhysics;
    @NonNull
    private final Choreographer choreographer;
    @NonNull
    private final Choreographer.FrameCallback physicsFrameCallback;
    @Nullable
    private final DragStatePersistence dragStatePersistence;

    @Nullable
    private CameraCapsuleSurfaceView capsuleView;
    @Nullable
    private WindowManager.LayoutParams layoutParams;
    @Nullable
    private CameraCapsuleSurfaceState currentState;
    @Nullable
    private CameraCapsulePlacementEngine.PlacementResult currentPlacement;
    @Nullable
    private CameraCapsulePlacementEngine.PlacementResult currentVisualPlacement;
    @Nullable
    private CameraCapsulePlacementEngine.PlacementResult currentDragBasePlacement;
    @Nullable
    private DockPreview currentDockPreview;
    @Nullable
    private CameraCapsuleSurfaceView.RenderDeformation currentRenderDeformation;
    private boolean attached;
    private int dragOffsetXPx;
    private int dragOffsetYPx;
    private float downRawX;
    private float downRawY;
    private float lastRawX;
    private float lastRawY;
    private float gestureMinRawX;
    private float gestureMaxRawX;
    private float gestureMinRawY;
    private long lastGestureEventTimeMs;
    private int startDragOffsetX;
    private int startDragOffsetY;
    private boolean dragging;
    private boolean suppressDockingUntilGestureEnd;
    private boolean physicsFrameScheduled;
    @Nullable
    private ValueAnimator placementAnimator;
    @Nullable
    private Rect stableCutoutRect;
    private int stableDisplayWidthPx;
    private int stableDisplayHeightPx;

    CameraCapsuleOverlayRenderer(@NonNull Context context, @Nullable DragStatePersistence dragStatePersistence) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.dragStatePersistence = dragStatePersistence;
        float density = this.context.getResources().getDisplayMetrics().density;
        this.placementEngine = new CameraCapsulePlacementEngine(density);
        this.gesturePhysics = new CameraCapsuleGesturePhysics(density);
        this.choreographer = Choreographer.getInstance();
        this.physicsFrameCallback = frameTimeNanos -> {
            physicsFrameScheduled = false;
            if (!dragging || suppressDockingUntilGestureEnd || currentState == null) return;
            long frameTimeMs = Math.max(lastGestureEventTimeMs, frameTimeNanos / 1_000_000L);
            lastGestureEventTimeMs = frameTimeMs;
            updatePlacementAndRender(false);
            if (dragging && !suppressDockingUntilGestureEnd) schedulePhysicsFrame();
        };
    }

    void render(@NonNull CameraCapsuleSurfaceState state) {
        ensureView();
        if (capsuleView == null) return;
        cancelPlacementAnimator();
        if (currentState == null || !TextUtils.equals(currentState.surfaceId, state.surfaceId)) {
            dragOffsetXPx = 0;
            dragOffsetYPx = 0;
            lastGestureEventTimeMs = 0L;
            currentPlacement = null;
            currentVisualPlacement = null;
            currentDragBasePlacement = null;
            currentDockPreview = null;
            currentRenderDeformation = null;
            cancelPhysicsFrame();
            gesturePhysics.cancelGesture();
        }
        currentState = state;
        updatePlacementAndRender(true);
    }

    void hide() {
        cancelPlacementAnimator();
        currentState = null;
        currentPlacement = null;
        currentVisualPlacement = null;
        currentDragBasePlacement = null;
        currentDockPreview = null;
        currentRenderDeformation = null;
        dragOffsetXPx = 0;
        dragOffsetYPx = 0;
        lastGestureEventTimeMs = 0L;
        suppressDockingUntilGestureEnd = false;
        cancelPhysicsFrame();
        gesturePhysics.cancelGesture();
        if (!attached || capsuleView == null) return;
        try {
            windowManager.removeView(capsuleView);
        } catch (Exception ignored) {
        } finally {
            attached = false;
            layoutParams = null;
            stableCutoutRect = null;
            stableDisplayWidthPx = 0;
            stableDisplayHeightPx = 0;
        }
    }

    private void ensureView() {
        if (capsuleView != null) return;
        capsuleView = new CameraCapsuleSurfaceView(context);
        capsuleView.setClickable(true);
        capsuleView.setFocusable(false);
        capsuleView.setOnClickListener(v -> openApp());
        capsuleView.setOnTouchListener(this::handleTouch);
    }

    private boolean handleTouch(@NonNull View view, @NonNull MotionEvent event) {
        CameraCapsuleSurfaceState state = currentState;
        if (state == null || !state.touchable) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                Log.i(LOG_TAG, "touch DOWN raw=(" + event.getRawX() + "," + event.getRawY() + ")");
                cancelPlacementAnimator();
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                lastRawX = downRawX;
                lastRawY = downRawY;
                gestureMinRawX = downRawX;
                gestureMaxRawX = downRawX;
                gestureMinRawY = downRawY;
                lastGestureEventTimeMs = event.getEventTime();
                startDragOffsetX = dragOffsetXPx;
                startDragOffsetY = dragOffsetYPx;
                dragging = false;
                suppressDockingUntilGestureEnd = false;
                currentDockPreview = null;
                gesturePhysics.beginGesture(downRawX, downRawY, lastGestureEventTimeMs);
                return true;
            case MotionEvent.ACTION_MOVE:
                Log.i(LOG_TAG, "touch MOVE raw=(" + event.getRawX() + "," + event.getRawY() + ") dragging=" + dragging);
                if (!state.draggable) return true;
                recordGesturePosition(event.getRawX(), event.getRawY());
                lastGestureEventTimeMs = event.getEventTime();
                int deltaX = Math.round(event.getRawX() - downRawX);
                int deltaY = Math.round(event.getRawY() - downRawY);
                if (currentPlacement != null
                    && currentPlacement.surfaceMode != CameraCapsulePlacementEngine.SurfaceMode.FLOATING
                    && shouldUndockByDrag(currentPlacement, deltaX, deltaY)) {
                    if (beginDragUndock(state, event)) {
                        suppressDockingUntilGestureEnd = true;
                    }
                    return true;
                }
                if (currentPlacement != null
                    && currentPlacement.surfaceMode != CameraCapsulePlacementEngine.SurfaceMode.FLOATING) {
                    return true;
                }
                if (Math.abs(deltaX) > dp(4) || Math.abs(deltaY) > dp(4)) dragging = true;
                dragOffsetXPx = startDragOffsetX + deltaX;
                dragOffsetYPx = startDragOffsetY + deltaY;
                if (dragging && !suppressDockingUntilGestureEnd) schedulePhysicsFrame();
                reapplyCurrentLayout();
                return true;
            case MotionEvent.ACTION_UP:
                Log.i(LOG_TAG, "touch UP raw=(" + event.getRawX() + "," + event.getRawY() + ") dragging=" + dragging);
                recordGesturePosition(event.getRawX(), event.getRawY());
                lastGestureEventTimeMs = event.getEventTime();
                if (dragging) {
                    commitDragPosition(event.getRawX(), event.getRawY(), !suppressDockingUntilGestureEnd);
                    suppressDockingUntilGestureEnd = false;
                    return true;
                }
                cancelPhysicsFrame();
                gesturePhysics.cancelGesture();
                if (isDockedPlacement()) {
                    return undockCurrentPlacement();
                }
                if (state.touchable) {
                    openApp();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_CANCEL:
                Log.i(LOG_TAG, "touch CANCEL raw=(" + event.getRawX() + "," + event.getRawY() + ") dragging=" + dragging);
                recordGesturePosition(event.getRawX(), event.getRawY());
                lastGestureEventTimeMs = event.getEventTime();
                if (dragging) {
                    commitDragPosition(event.getRawX(), event.getRawY(), !suppressDockingUntilGestureEnd);
                }
                dragging = false;
                suppressDockingUntilGestureEnd = false;
                cancelPhysicsFrame();
                gesturePhysics.cancelGesture();
                return true;
            default:
                return false;
        }
    }

    private void reapplyCurrentLayout() {
        updatePlacementAndRender(false);
    }

    private void updatePlacementAndRender(boolean forceRebind) {
        if (capsuleView == null || currentState == null) return;
        try {
            CameraCapsulePlacementEngine.DisplayProfile displayProfile = resolveDisplayProfile();
            CameraCapsulePlacementEngine.ContentMetrics contentMetrics = buildContentMetrics(currentState, displayProfile);
            CameraCapsulePlacementEngine.PlacementResult placementResult = placementEngine.resolve(
                displayProfile,
                currentState,
                contentMetrics,
                dragOffsetXPx,
                dragOffsetYPx,
                !dragging && !suppressDockingUntilGestureEnd);
            currentDragBasePlacement = dragging ? placementResult : null;
            CameraCapsulePlacementEngine.PlacementResult layoutPlacement = placementResult;
            CameraCapsulePlacementEngine.PlacementResult drawPlacement = placementResult;
            CameraCapsuleSurfaceView.RenderDeformation renderDeformation = null;
            if (dragging && !suppressDockingUntilGestureEnd) {
                DockPreview dockPreview = resolveDockPreview(displayProfile, placementResult);
                currentDockPreview = dockPreview;
                if (dockPreview != null) {
                    if (dockPreview.isSidePreview()) {
                        renderDeformation = dockPreview.toRenderDeformation();
                        layoutPlacement = buildSidePreviewContainerPlacement(placementResult, dockPreview);
                    } else {
                        CameraCapsulePlacementEngine.PlacementResult dockedPlacement = placementEngine.resolveForcedSurfaceMode(
                            displayProfile,
                            currentState,
                            contentMetrics,
                            dragOffsetXPx,
                            dragOffsetYPx,
                            dockPreview.surfaceMode);
                        drawPlacement = interpolatePreviewPlacement(placementResult, dockedPlacement, dockPreview);
                        layoutPlacement = buildPreviewContainerPlacement(placementResult, drawPlacement);
                    }
                }
                schedulePhysicsFrame();
            } else if (!dragging) {
                currentDockPreview = null;
                currentRenderDeformation = null;
                cancelPhysicsFrame();
                gesturePhysics.cancelGesture();
            }
            presentPlacement(currentState, layoutPlacement, drawPlacement, renderDeformation, forceRebind);
        } catch (Exception ignored) {
        }
    }

    @NonNull
    private CameraCapsulePlacementEngine.ContentMetrics buildContentMetrics(@NonNull CameraCapsuleSurfaceState state,
                                                                            @NonNull CameraCapsulePlacementEngine.DisplayProfile displayProfile) {
        CameraCapsuleSurfaceView surfaceView = capsuleView;
        if (surfaceView == null) {
            return new CameraCapsulePlacementEngine.ContentMetrics(dp(224), dp(54), dp(132), dp(12), dp(12), dp(96));
        }
        int floatingWidthPx = placementEngine.resolveFloatingWidthPx(state, displayProfile);
        int dockedTopWidthPx = placementEngine.resolveDockedWidthPx(state, displayProfile);
        int dockedSideWidthPx = placementEngine.resolveDockedSideWidthPx();
        int dockedSideHeightPx = placementEngine.resolveDockedSideHeightPx(state, displayProfile);
        return surfaceView.buildContentMetrics(
            state,
            floatingWidthPx,
            dockedTopWidthPx,
            dockedSideWidthPx,
            dockedSideHeightPx);
    }

    private void presentPlacement(@NonNull CameraCapsuleSurfaceState state,
                                  @NonNull CameraCapsulePlacementEngine.PlacementResult layoutPlacement,
                                  @NonNull CameraCapsulePlacementEngine.PlacementResult drawPlacement,
                                  @Nullable CameraCapsuleSurfaceView.RenderDeformation renderDeformation,
                                  boolean forceRebind) {
        CameraCapsuleSurfaceView surfaceView = capsuleView;
        if (surfaceView == null) return;
        boolean needsRebind = forceRebind
            || currentPlacement == null
            || currentPlacement.widthPx != layoutPlacement.widthPx
            || currentPlacement.heightPx != layoutPlacement.heightPx
            || currentVisualPlacement == null
            || !samePlacementGeometry(currentVisualPlacement, drawPlacement)
            || currentPlacement.xPx != layoutPlacement.xPx
            || currentPlacement.yPx != layoutPlacement.yPx
            || !sameRenderDeformation(currentRenderDeformation, renderDeformation);
        if (needsRebind) surfaceView.bind(state, layoutPlacement, drawPlacement, renderDeformation);

        WindowManager.LayoutParams previousParams = layoutParams;
        WindowManager.LayoutParams nextParams = buildLayoutParams(state, layoutPlacement);
        if (!attached) {
            windowManager.addView(surfaceView, nextParams);
            attached = true;
            layoutParams = nextParams;
            refreshStableDisplayProfile();
        } else if (needsRebind || hasLayoutChanged(previousParams, nextParams)) {
            windowManager.updateViewLayout(surfaceView, nextParams);
            layoutParams = nextParams;
        }
        currentPlacement = layoutPlacement;
        currentVisualPlacement = drawPlacement;
        currentRenderDeformation = renderDeformation;
    }

    @NonNull
    private WindowManager.LayoutParams buildLayoutParams(@NonNull CameraCapsuleSurfaceState state,
                                                         @NonNull CameraCapsulePlacementEngine.PlacementResult placementResult) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.width = placementResult.widthPx;
        params.height = placementResult.heightPx;
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = placementResult.xPx;
        params.y = placementResult.yPx;
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        params.flags = resolveFlags(state);
        params.format = android.graphics.PixelFormat.TRANSLUCENT;
        params.packageName = context.getPackageName();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        return params;
    }

    private void commitDragPosition(float releaseRawX, float releaseRawY, boolean allowDockingOnRelease) {
        CameraCapsuleSurfaceState state = currentState;
        CameraCapsulePlacementEngine.PlacementResult placementResult =
            currentDragBasePlacement != null ? currentDragBasePlacement : currentPlacement;
        if (state == null || placementResult == null) return;
        CameraCapsulePlacementEngine.DisplayProfile displayProfile = resolveDisplayProfile();
        CameraCapsulePlacementEngine.ContentMetrics contentMetrics = buildContentMetrics(state, displayProfile);
        CameraCapsuleGesturePhysics.Snapshot snapshotBeforeRelease = gesturePhysics.currentSnapshot();
        if (allowDockingOnRelease) {
            gesturePhysics.update(
                displayProfile,
                placementResult,
                releaseRawX,
                releaseRawY,
                lastGestureEventTimeMs);
        }
        CameraCapsuleSurfaceState floatingState = state.buildUpon()
            .setPlacementMode(CameraCapsuleSurfaceState.PLACEMENT_FLOATING)
            .setDockEdge(CameraCapsuleSurfaceState.DOCK_EDGE_NONE)
            .setOffsetXDp(pxToDpRounded(placementResult.committedOffsetXPx))
            .setOffsetYDp(pxToDpRounded(placementResult.committedOffsetYPx))
            .build();
        CameraCapsulePlacementEngine.SurfaceMode releaseMode = CameraCapsulePlacementEngine.SurfaceMode.FLOATING;
        if (allowDockingOnRelease) {
            CameraCapsulePlacementEngine.SurfaceMode currentReleaseMode = resolveReleaseSurfaceMode(displayProfile);
            CameraCapsulePlacementEngine.SurfaceMode previousReleaseMode =
                gesturePhysics.resolveReleaseSurfaceMode(snapshotBeforeRelease);
            releaseMode = currentReleaseMode != CameraCapsulePlacementEngine.SurfaceMode.FLOATING
                ? currentReleaseMode
                : previousReleaseMode;
            CameraCapsuleGesturePhysics.Snapshot snapshotAfterRelease = gesturePhysics.currentSnapshot();
            Log.i(
                LOG_TAG,
                "commitDragPosition release=(" + releaseRawX + "," + releaseRawY + ")"
                    + " placement=(" + placementResult.xPx + "," + placementResult.yPx
                    + " " + placementResult.widthPx + "x" + placementResult.heightPx + ")"
                    + " before={mode=" + snapshotBeforeRelease.dominantMode
                    + ",comp=" + snapshotBeforeRelease.compressionProgress
                    + ",page=" + snapshotBeforeRelease.pageProgress
                    + ",attach=" + snapshotBeforeRelease.bodyAttachProgress
                    + ",memory=" + snapshotBeforeRelease.contactMemoryProgress
                    + ",memVis=" + snapshotBeforeRelease.contentVisibilityProgress
                    + ",conf=" + snapshotBeforeRelease.releaseConfidence + "}"
                    + " after={mode=" + snapshotAfterRelease.dominantMode
                    + ",comp=" + snapshotAfterRelease.compressionProgress
                    + ",page=" + snapshotAfterRelease.pageProgress
                    + ",attach=" + snapshotAfterRelease.bodyAttachProgress
                    + ",memory=" + snapshotAfterRelease.contactMemoryProgress
                    + ",memVis=" + snapshotAfterRelease.contentVisibilityProgress
                    + ",conf=" + snapshotAfterRelease.releaseConfidence + "}"
                    + " resolvedCurrent=" + currentReleaseMode
                    + " resolvedPrevious=" + previousReleaseMode
                    + " final=" + releaseMode);
        }
        CameraCapsulePlacementEngine.PlacementResult targetPlacement = releaseMode == CameraCapsulePlacementEngine.SurfaceMode.FLOATING
            ? placementEngine.resolve(displayProfile, floatingState, contentMetrics, 0, 0, false)
            : placementEngine.resolveForcedSurfaceMode(displayProfile, floatingState, contentMetrics, 0, 0, releaseMode);
        currentState = floatingState.buildUpon()
            .setPlacementMode(resolvePlacementMode(targetPlacement.surfaceMode))
            .setDockEdge(targetPlacement.dockEdge)
            .setOffsetXDp(pxToDpRounded(targetPlacement.committedOffsetXPx))
            .setOffsetYDp(pxToDpRounded(targetPlacement.committedOffsetYPx))
            .build();
        CameraCapsulePlacementEngine.PlacementResult fromPlacement =
            currentVisualPlacement != null
                ? currentVisualPlacement
                : (currentPlacement != null ? currentPlacement : placementResult);
        dragOffsetXPx = 0;
        dragOffsetYPx = 0;
        dragging = false;
        currentDragBasePlacement = null;
        currentDockPreview = null;
        currentRenderDeformation = null;
        cancelPhysicsFrame();
        gesturePhysics.cancelGesture();
        if (dragStatePersistence != null && currentState != null) dragStatePersistence.persist(currentState);
        transitionToPlacement(currentState, fromPlacement, targetPlacement);
    }

    private boolean isDockedPlacement() {
        return currentPlacement != null
            && currentPlacement.surfaceMode != CameraCapsulePlacementEngine.SurfaceMode.FLOATING;
    }

    private boolean shouldUndockByDrag(@NonNull CameraCapsulePlacementEngine.PlacementResult placementResult,
                                       int deltaX,
                                       int deltaY) {
        switch (placementResult.surfaceMode) {
            case DOCKED_LEFT:
                return deltaX >= dp(10) && Math.abs(deltaX) >= Math.abs(deltaY);
            case DOCKED_RIGHT:
                return deltaX <= -dp(10) && Math.abs(deltaX) >= Math.abs(deltaY);
            case DOCKED_TOP:
                return deltaY >= dp(10);
            case FLOATING:
            default:
                return false;
        }
    }

    private boolean beginDragUndock(@NonNull CameraCapsuleSurfaceState state, @NonNull MotionEvent event) {
        CameraCapsulePlacementEngine.PlacementResult placementResult = currentPlacement;
        if (placementResult == null) return false;
        CameraCapsulePlacementEngine.DisplayProfile displayProfile = resolveDisplayProfile();
        CameraCapsulePlacementEngine.ContentMetrics contentMetrics = buildContentMetrics(state, displayProfile);
        int targetFloatingXPx = clamp(
            Math.round(event.getRawX() - (contentMetrics.floatingWidthPx / 2f)),
            dp(8),
            Math.max(dp(8), displayProfile.displayWidthPx - contentMetrics.floatingWidthPx - dp(8)));
        int targetFloatingYPx = clamp(
            Math.round(event.getRawY() - (contentMetrics.floatingHeightPx / 2f)),
            0,
            Math.max(0, displayProfile.displayHeightPx - contentMetrics.floatingHeightPx));
        int floatingOriginXPx = placementEngine.resolveFloatingOriginXPx(displayProfile, state, contentMetrics.floatingWidthPx);
        int baseTopYPx = placementEngine.resolveBaseTopPx(displayProfile, state);
        currentState = state.buildUpon()
            .setPlacementMode(CameraCapsuleSurfaceState.PLACEMENT_FLOATING)
            .setDockEdge(CameraCapsuleSurfaceState.DOCK_EDGE_NONE)
            .setOffsetXDp(pxToDpRounded(targetFloatingXPx - floatingOriginXPx))
            .setOffsetYDp(pxToDpRounded(targetFloatingYPx - baseTopYPx))
            .build();
        dragOffsetXPx = 0;
        dragOffsetYPx = 0;
        dragging = true;
        currentDragBasePlacement = null;
        currentDockPreview = null;
        lastRawX = event.getRawX();
        lastRawY = event.getRawY();
        lastGestureEventTimeMs = event.getEventTime();
        gesturePhysics.beginGesture(lastRawX, lastRawY, lastGestureEventTimeMs);
        schedulePhysicsFrame();
        downRawX = event.getRawX();
        downRawY = event.getRawY();
        startDragOffsetX = 0;
        startDragOffsetY = 0;
        presentPlacement(
            currentState,
            placementEngine.resolve(displayProfile, currentState, contentMetrics, 0, 0, false),
            placementEngine.resolve(displayProfile, currentState, contentMetrics, 0, 0, false),
            null,
            true);
        return true;
    }

    private boolean undockCurrentPlacement() {
        CameraCapsuleSurfaceState state = currentState;
        CameraCapsulePlacementEngine.PlacementResult placementResult = currentPlacement;
        if (state == null || placementResult == null) return false;
        CameraCapsulePlacementEngine.DisplayProfile displayProfile = resolveDisplayProfile();
        CameraCapsulePlacementEngine.ContentMetrics contentMetrics = buildContentMetrics(state, displayProfile);
        int targetFloatingXPx = resolveUndockTargetFloatingXPx(displayProfile, placementResult, contentMetrics);
        int targetFloatingYPx = resolveUndockTargetFloatingYPx(displayProfile, state, placementResult, contentMetrics);
        int floatingOriginXPx = placementEngine.resolveFloatingOriginXPx(displayProfile, state, contentMetrics.floatingWidthPx);
        int baseTopYPx = placementEngine.resolveBaseTopPx(displayProfile, state);
        CameraCapsuleSurfaceState floatingState = state.buildUpon()
            .setPlacementMode(CameraCapsuleSurfaceState.PLACEMENT_FLOATING)
            .setDockEdge(CameraCapsuleSurfaceState.DOCK_EDGE_NONE)
            .setOffsetXDp(pxToDpRounded(targetFloatingXPx - floatingOriginXPx))
            .setOffsetYDp(pxToDpRounded(targetFloatingYPx - baseTopYPx))
            .build();
        CameraCapsulePlacementEngine.PlacementResult targetPlacement =
            placementEngine.resolve(displayProfile, floatingState, contentMetrics, 0, 0, false);
        currentState = floatingState.buildUpon()
            .setOffsetXDp(pxToDpRounded(targetPlacement.committedOffsetXPx))
            .setOffsetYDp(pxToDpRounded(targetPlacement.committedOffsetYPx))
            .build();
        dragOffsetXPx = 0;
        dragOffsetYPx = 0;
        dragging = false;
        currentDragBasePlacement = null;
        currentDockPreview = null;
        currentRenderDeformation = null;
        cancelPhysicsFrame();
        gesturePhysics.cancelGesture();
        if (dragStatePersistence != null && currentState != null) dragStatePersistence.persist(currentState);
        transitionToPlacement(currentState, placementResult, targetPlacement);
        return true;
    }

    @Nullable
    private DockPreview resolveDockPreview(@NonNull CameraCapsulePlacementEngine.DisplayProfile displayProfile,
                                           @NonNull CameraCapsulePlacementEngine.PlacementResult floatingPlacement) {
        CameraCapsuleGesturePhysics.Snapshot snapshot = gesturePhysics.update(
            displayProfile,
            floatingPlacement,
            lastRawX,
            lastRawY,
            lastGestureEventTimeMs);
        if (!snapshot.isActive()) return null;
        return new DockPreview(
            snapshot.dominantMode,
            snapshot.compressionProgress,
            snapshot.pageProgress,
            snapshot.pressProgress,
            snapshot.bodySqueezeProgress,
            snapshot.bodyStretchProgress,
            snapshot.bodyAttachProgress,
            snapshot.contentVisibilityProgress,
            snapshot.distancePx);
    }

    private int resolveUndockTargetFloatingXPx(@NonNull CameraCapsulePlacementEngine.DisplayProfile displayProfile,
                                               @NonNull CameraCapsulePlacementEngine.PlacementResult placementResult,
                                               @NonNull CameraCapsulePlacementEngine.ContentMetrics contentMetrics) {
        if (placementResult.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT
            || placementResult.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_RIGHT) {
            return placementEngine.resolveUndockedFloatingXPx(
                displayProfile,
                placementResult.surfaceMode,
                contentMetrics.floatingWidthPx);
        }
        int projectedXPx = placementResult.xPx + ((placementResult.widthPx - contentMetrics.floatingWidthPx) / 2);
        return clamp(
            projectedXPx,
            dp(8),
            Math.max(dp(8), displayProfile.displayWidthPx - contentMetrics.floatingWidthPx - dp(8)));
    }

    private int resolveUndockTargetFloatingYPx(@NonNull CameraCapsulePlacementEngine.DisplayProfile displayProfile,
                                               @NonNull CameraCapsuleSurfaceState state,
                                               @NonNull CameraCapsulePlacementEngine.PlacementResult placementResult,
                                               @NonNull CameraCapsulePlacementEngine.ContentMetrics contentMetrics) {
        int projectedYPx = placementResult.yPx + ((placementResult.heightPx - contentMetrics.floatingHeightPx) / 2);
        return placementEngine.resolveUndockedFloatingYPx(
            displayProfile,
            state,
            placementResult.surfaceMode,
            contentMetrics.floatingHeightPx,
            projectedYPx);
    }

    @NonNull
    private CameraCapsulePlacementEngine.SurfaceMode resolveReleaseSurfaceMode(@NonNull CameraCapsulePlacementEngine.DisplayProfile displayProfile) {
        return gesturePhysics.resolveReleaseSurfaceMode();
    }

    private void recordGesturePosition(float rawX, float rawY) {
        lastRawX = rawX;
        lastRawY = rawY;
        gestureMinRawX = Math.min(gestureMinRawX, rawX);
        gestureMaxRawX = Math.max(gestureMaxRawX, rawX);
        gestureMinRawY = Math.min(gestureMinRawY, rawY);
    }

    private void transitionToPlacement(@NonNull CameraCapsuleSurfaceState state,
                                       @NonNull CameraCapsulePlacementEngine.PlacementResult fromPlacement,
                                       @NonNull CameraCapsulePlacementEngine.PlacementResult toPlacement) {
        cancelPlacementAnimator();
        if (capsuleView == null || !attached || samePlacementGeometry(fromPlacement, toPlacement)) {
            presentPlacement(state, toPlacement, toPlacement, null, true);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(resolveTransitionDurationMs(fromPlacement, toPlacement));
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            CameraCapsulePlacementEngine.PlacementResult interpolatedPlacement =
                interpolatePlacement(fromPlacement, toPlacement, progress);
            presentPlacement(state, interpolatedPlacement, interpolatedPlacement, null, true);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (placementAnimator != animation) return;
                placementAnimator = null;
                presentPlacement(state, toPlacement, toPlacement, null, true);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                if (placementAnimator == animation) placementAnimator = null;
            }
        });
        placementAnimator = animator;
        animator.start();
    }

    private void cancelPlacementAnimator() {
        ValueAnimator animator = placementAnimator;
        if (animator == null) return;
        placementAnimator = null;
        animator.cancel();
    }

    @NonNull
    private CameraCapsulePlacementEngine.PlacementResult interpolatePreviewPlacement(@NonNull CameraCapsulePlacementEngine.PlacementResult fromPlacement,
                                                                                     @NonNull CameraCapsulePlacementEngine.PlacementResult toPlacement,
                                                                                     @NonNull DockPreview dockPreview) {
        float compressionProgress = Math.max(0f, Math.min(1f, dockPreview.compressionProgress));
        float pageProgress = Math.max(0f, Math.min(1f, dockPreview.pageProgress));
        if (fromPlacement.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.FLOATING
            && (toPlacement.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT
                || toPlacement.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_RIGHT)) {
            return buildSideDockPreviewPlacement(fromPlacement, toPlacement, dockPreview);
        }
        float easedProgress = Math.max(compressionProgress, pageProgress);
        return new CameraCapsulePlacementEngine.PlacementResult(
            resolvePreviewSurfaceMode(fromPlacement.surfaceMode, toPlacement.surfaceMode, compressionProgress, pageProgress),
            easedProgress >= 0.12f ? toPlacement.dockEdge : fromPlacement.dockEdge,
            lerpInt(fromPlacement.xPx, toPlacement.xPx, easedProgress),
            lerpInt(fromPlacement.yPx, toPlacement.yPx, easedProgress),
            lerpInt(fromPlacement.widthPx, toPlacement.widthPx, easedProgress),
            lerpInt(fromPlacement.heightPx, toPlacement.heightPx, easedProgress),
            lerpInt(fromPlacement.committedOffsetXPx, toPlacement.committedOffsetXPx, easedProgress),
            lerpInt(fromPlacement.committedOffsetYPx, toPlacement.committedOffsetYPx, easedProgress));
    }

    @NonNull
    private CameraCapsulePlacementEngine.PlacementResult buildSideDockPreviewPlacement(@NonNull CameraCapsulePlacementEngine.PlacementResult fromPlacement,
                                                                                       @NonNull CameraCapsulePlacementEngine.PlacementResult toPlacement,
                                                                                       @NonNull DockPreview dockPreview) {
        float squeezePhase = smoothStep01(dockPreview.compressionProgress);
        float commitPhase = smoothStep01(dockPreview.pageProgress);
        int squeezedWidthPx = Math.max(
            Math.max(toPlacement.widthPx * 5, dp(76)),
            Math.round(fromPlacement.widthPx * (1f - (0.68f * squeezePhase))));
        int squeezedHeightPx = Math.max(
            fromPlacement.heightPx,
            Math.round(fromPlacement.heightPx + (dp(34) * squeezePhase)));
        int pressureDropPx = Math.round(dp(18) * dockPreview.pressProgress);
        boolean dockLeft = toPlacement.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT;
        int squeezedXPx = dockLeft
            ? fromPlacement.xPx
            : fromPlacement.xPx + Math.max(0, fromPlacement.widthPx - squeezedWidthPx);
        int squeezedYPx = fromPlacement.yPx + pressureDropPx;
        return new CameraCapsulePlacementEngine.PlacementResult(
            resolvePreviewSurfaceMode(fromPlacement.surfaceMode, toPlacement.surfaceMode, dockPreview.compressionProgress, dockPreview.pageProgress),
            dockPreview.pageProgress >= 0.24f ? toPlacement.dockEdge : fromPlacement.dockEdge,
            lerpInt(squeezedXPx, toPlacement.xPx, commitPhase),
            lerpInt(squeezedYPx, toPlacement.yPx, commitPhase),
            lerpInt(squeezedWidthPx, toPlacement.widthPx, commitPhase),
            lerpInt(squeezedHeightPx, toPlacement.heightPx, commitPhase),
            lerpInt(fromPlacement.committedOffsetXPx, toPlacement.committedOffsetXPx, commitPhase),
            lerpInt(fromPlacement.committedOffsetYPx, toPlacement.committedOffsetYPx, commitPhase));
    }

    @NonNull
    private CameraCapsulePlacementEngine.PlacementResult buildPreviewContainerPlacement(@NonNull CameraCapsulePlacementEngine.PlacementResult basePlacement,
                                                                                        @NonNull CameraCapsulePlacementEngine.PlacementResult drawPlacement) {
        int leftPx = Math.min(basePlacement.xPx, drawPlacement.xPx);
        int topPx = Math.min(basePlacement.yPx, drawPlacement.yPx);
        int rightPx = Math.max(basePlacement.xPx + basePlacement.widthPx, drawPlacement.xPx + drawPlacement.widthPx);
        int bottomPx = Math.max(basePlacement.yPx + basePlacement.heightPx, drawPlacement.yPx + drawPlacement.heightPx);
        return new CameraCapsulePlacementEngine.PlacementResult(
            basePlacement.surfaceMode,
            basePlacement.dockEdge,
            leftPx,
            topPx,
            Math.max(basePlacement.widthPx, rightPx - leftPx),
            Math.max(basePlacement.heightPx, bottomPx - topPx),
            basePlacement.committedOffsetXPx,
            basePlacement.committedOffsetYPx);
    }

    @NonNull
    private CameraCapsulePlacementEngine.PlacementResult buildSidePreviewContainerPlacement(@NonNull CameraCapsulePlacementEngine.PlacementResult basePlacement,
                                                                                            @NonNull DockPreview dockPreview) {
        int visibleWidthPx = resolveSidePreviewVisibleWidthPx(basePlacement, dockPreview);
        int extraBottomPx = Math.max(
            0,
            Math.round(dp(22) * dockPreview.pressProgress * (0.70f + (0.30f * dockPreview.bodyStretchProgress))));
        boolean dockRight = dockPreview.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_RIGHT;
        int containerXPx = dockRight
            ? basePlacement.xPx + Math.max(0, basePlacement.widthPx - visibleWidthPx)
            : basePlacement.xPx;
        return new CameraCapsulePlacementEngine.PlacementResult(
            basePlacement.surfaceMode,
            basePlacement.dockEdge,
            containerXPx,
            basePlacement.yPx,
            visibleWidthPx,
            basePlacement.heightPx + extraBottomPx,
            basePlacement.committedOffsetXPx,
            basePlacement.committedOffsetYPx);
    }

    private int resolveSidePreviewVisibleWidthPx(@NonNull CameraCapsulePlacementEngine.PlacementResult basePlacement,
                                                 @NonNull DockPreview dockPreview) {
        float squeeze = smoothStep01(dockPreview.bodySqueezeProgress);
        float attach = smoothStep01(dockPreview.bodyAttachProgress);
        float minimumWidthPx = dp(26);
        float widthFactor = 1f - Math.min(0.92f, (0.18f * squeeze) + (0.72f * attach));
        return Math.max(Math.round(minimumWidthPx), Math.round(basePlacement.widthPx * widthFactor));
    }

    @NonNull
    private CameraCapsulePlacementEngine.PlacementResult interpolatePlacement(@NonNull CameraCapsulePlacementEngine.PlacementResult fromPlacement,
                                                                              @NonNull CameraCapsulePlacementEngine.PlacementResult toPlacement,
                                                                              float progress) {
        float easedProgress = Math.max(0f, Math.min(1f, progress));
        return new CameraCapsulePlacementEngine.PlacementResult(
            resolveAnimatedSurfaceMode(fromPlacement.surfaceMode, toPlacement.surfaceMode, easedProgress),
            easedProgress < 0.5f ? fromPlacement.dockEdge : toPlacement.dockEdge,
            lerpInt(fromPlacement.xPx, toPlacement.xPx, easedProgress),
            lerpInt(fromPlacement.yPx, toPlacement.yPx, easedProgress),
            lerpInt(fromPlacement.widthPx, toPlacement.widthPx, easedProgress),
            lerpInt(fromPlacement.heightPx, toPlacement.heightPx, easedProgress),
            lerpInt(fromPlacement.committedOffsetXPx, toPlacement.committedOffsetXPx, easedProgress),
            lerpInt(fromPlacement.committedOffsetYPx, toPlacement.committedOffsetYPx, easedProgress));
    }

    @NonNull
    private CameraCapsulePlacementEngine.SurfaceMode resolvePreviewSurfaceMode(@NonNull CameraCapsulePlacementEngine.SurfaceMode fromMode,
                                                                               @NonNull CameraCapsulePlacementEngine.SurfaceMode toMode,
                                                                               float compressionProgress,
                                                                               float pageProgress) {
        if (fromMode == toMode) return toMode;
        if (toMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT
            || toMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_RIGHT) {
            return pageProgress >= sideDockLatchThreshold() ? toMode : fromMode;
        }
        return Math.max(compressionProgress, pageProgress) >= 0.34f ? toMode : fromMode;
    }

    @NonNull
    private CameraCapsulePlacementEngine.SurfaceMode resolveAnimatedSurfaceMode(@NonNull CameraCapsulePlacementEngine.SurfaceMode fromMode,
                                                                                @NonNull CameraCapsulePlacementEngine.SurfaceMode toMode,
                                                                                float progress) {
        if (fromMode == toMode) return toMode;
        return progress >= 0.5f ? toMode : fromMode;
    }

    private boolean samePlacementGeometry(@NonNull CameraCapsulePlacementEngine.PlacementResult first,
                                          @NonNull CameraCapsulePlacementEngine.PlacementResult second) {
        return first.surfaceMode == second.surfaceMode
            && TextUtils.equals(first.dockEdge, second.dockEdge)
            && first.xPx == second.xPx
            && first.yPx == second.yPx
            && first.widthPx == second.widthPx
            && first.heightPx == second.heightPx;
    }

    private boolean sameRenderDeformation(@Nullable CameraCapsuleSurfaceView.RenderDeformation first,
                                          @Nullable CameraCapsuleSurfaceView.RenderDeformation second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        return first.contactSurfaceMode == second.contactSurfaceMode
            && almostEqual(first.compressionProgress, second.compressionProgress)
            && almostEqual(first.pageProgress, second.pageProgress)
            && almostEqual(first.pressProgress, second.pressProgress)
            && almostEqual(first.bodySqueezeProgress, second.bodySqueezeProgress)
            && almostEqual(first.bodyStretchProgress, second.bodyStretchProgress)
            && almostEqual(first.bodyAttachProgress, second.bodyAttachProgress)
            && almostEqual(first.contentVisibilityProgress, second.contentVisibilityProgress);
    }

    private long resolveTransitionDurationMs(@NonNull CameraCapsulePlacementEngine.PlacementResult fromPlacement,
                                             @NonNull CameraCapsulePlacementEngine.PlacementResult toPlacement) {
        if (fromPlacement.surfaceMode == toPlacement.surfaceMode) return 180L;
        return 220L;
    }

    private int lerpInt(int start, int end, float progress) {
        return start + Math.round((end - start) * progress);
    }

    private boolean almostEqual(float first, float second) {
        return Math.abs(first - second) < 0.004f;
    }

    private float smoothStep01(float value) {
        float x = Math.max(0f, Math.min(1f, value));
        return x * x * (3f - (2f * x));
    }

    private float sideDockLatchThreshold() {
        return 0.58f;
    }

    private void schedulePhysicsFrame() {
        if (physicsFrameScheduled) return;
        physicsFrameScheduled = true;
        choreographer.postFrameCallback(physicsFrameCallback);
    }

    private void cancelPhysicsFrame() {
        if (!physicsFrameScheduled) return;
        physicsFrameScheduled = false;
        choreographer.removeFrameCallback(physicsFrameCallback);
    }

    private static final class DockPreview {
        @NonNull final CameraCapsulePlacementEngine.SurfaceMode surfaceMode;
        final float compressionProgress;
        final float pageProgress;
        final float pressProgress;
        final float bodySqueezeProgress;
        final float bodyStretchProgress;
        final float bodyAttachProgress;
        final float contentVisibilityProgress;
        final int distancePx;

        DockPreview(@NonNull CameraCapsulePlacementEngine.SurfaceMode surfaceMode,
                    float compressionProgress,
                    float pageProgress,
                    float pressProgress,
                    float bodySqueezeProgress,
                    float bodyStretchProgress,
                    float bodyAttachProgress,
                    float contentVisibilityProgress,
                    int distancePx) {
            this.surfaceMode = surfaceMode;
            this.compressionProgress = Math.max(0f, Math.min(1f, compressionProgress));
            this.pageProgress = Math.max(0f, Math.min(1f, pageProgress));
            this.pressProgress = Math.max(0f, Math.min(1f, pressProgress));
            this.bodySqueezeProgress = Math.max(0f, Math.min(1f, bodySqueezeProgress));
            this.bodyStretchProgress = Math.max(0f, Math.min(1f, bodyStretchProgress));
            this.bodyAttachProgress = Math.max(0f, Math.min(1f, bodyAttachProgress));
            this.contentVisibilityProgress = Math.max(0f, Math.min(1f, contentVisibilityProgress));
            this.distancePx = distancePx;
        }

        boolean isSidePreview() {
            return surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT
                || surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_RIGHT;
        }

        @NonNull
        CameraCapsuleSurfaceView.RenderDeformation toRenderDeformation() {
            return new CameraCapsuleSurfaceView.RenderDeformation(
                surfaceMode,
                compressionProgress,
                pageProgress,
                pressProgress,
                bodySqueezeProgress,
                bodyStretchProgress,
                bodyAttachProgress,
                contentVisibilityProgress);
        }
    }

    @NonNull
    private String resolvePlacementMode(@NonNull CameraCapsulePlacementEngine.SurfaceMode surfaceMode) {
        switch (surfaceMode) {
            case DOCKED_TOP:
                return CameraCapsuleSurfaceState.PLACEMENT_DOCKED_TOP;
            case DOCKED_LEFT:
                return CameraCapsuleSurfaceState.PLACEMENT_DOCKED_LEFT;
            case DOCKED_RIGHT:
                return CameraCapsuleSurfaceState.PLACEMENT_DOCKED_RIGHT;
            case FLOATING:
            default:
                return CameraCapsuleSurfaceState.PLACEMENT_FLOATING;
        }
    }

    private boolean hasLayoutChanged(@Nullable WindowManager.LayoutParams previous,
                                     @NonNull WindowManager.LayoutParams next) {
        if (previous == null) return true;
        return previous.width != next.width
            || previous.height != next.height
            || previous.x != next.x
            || previous.y != next.y
            || previous.flags != next.flags
            || previous.gravity != next.gravity
            || previous.type != next.type
            || previous.layoutInDisplayCutoutMode != next.layoutInDisplayCutoutMode;
    }

    private int resolveFlags(@NonNull CameraCapsuleSurfaceState state) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (!state.touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        return flags;
    }

    @NonNull
    private CameraCapsulePlacementEngine.DisplayProfile resolveDisplayProfile() {
        Point displaySize = resolveDisplaySize();
        Rect cutoutRect = resolveCutoutRect(displaySize);
        return new CameraCapsulePlacementEngine.DisplayProfile(
            displaySize.x,
            displaySize.y,
            resolveStatusBarBottomPx(),
            cutoutRect == null ? 0 : cutoutRect.left,
            cutoutRect == null ? 0 : cutoutRect.top,
            cutoutRect == null ? 0 : cutoutRect.right,
            cutoutRect == null ? 0 : cutoutRect.bottom,
            resolveSystemGestureInsetLeftPx(),
            resolveSystemGestureInsetRightPx());
    }

    @Nullable
    private Rect resolveCutoutRect(@NonNull Point displaySize) {
        if (stableCutoutRect != null
            && stableDisplayWidthPx == displaySize.x
            && stableDisplayHeightPx == displaySize.y) {
            return new Rect(stableCutoutRect);
        }
        Rect resolved = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics windowMetrics = windowManager.getMaximumWindowMetrics();
            WindowInsets windowInsets = windowMetrics.getWindowInsets();
            DisplayCutout cutout = windowInsets == null ? null : windowInsets.getDisplayCutout();
            resolved = selectTopCutoutRect(cutout, windowMetrics.getBounds().width());
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && capsuleView != null && attached) {
            WindowInsets insets = capsuleView.getRootWindowInsets();
            DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();
            resolved = selectTopCutoutRect(cutout, displaySize.x);
        }
        stableDisplayWidthPx = displaySize.x;
        stableDisplayHeightPx = displaySize.y;
        stableCutoutRect = resolved == null ? null : new Rect(resolved);
        return resolved == null ? null : new Rect(resolved);
    }

    @Nullable
    private Rect selectTopCutoutRect(@Nullable DisplayCutout cutout, int displayWidth) {
        if (cutout == null) return null;
        List<Rect> rects = cutout.getBoundingRects();
        if (rects == null || rects.isEmpty()) return null;
        int displayCenter = displayWidth / 2;
        int maxTopBand = Math.max(dp(36), cutout.getSafeInsetTop() + dp(12));
        Rect best = null;
        for (Rect rect : rects) {
            if (rect == null || rect.isEmpty()) continue;
            if (rect.top > maxTopBand && rect.bottom > maxTopBand) continue;
            if (best == null || isBetterTopCutout(rect, best, displayCenter)) best = rect;
        }
        if (best != null) return new Rect(best);
        for (Rect rect : rects) {
            if (rect == null || rect.isEmpty()) continue;
            if (best == null || isBetterTopCutout(rect, best, displayCenter)) best = rect;
        }
        return best == null ? null : new Rect(best);
    }

    private boolean isBetterTopCutout(@NonNull Rect candidate, @NonNull Rect incumbent, int displayCenter) {
        if (candidate.top != incumbent.top) return candidate.top < incumbent.top;
        int candidateDistance = Math.abs(candidate.centerX() - displayCenter);
        int incumbentDistance = Math.abs(incumbent.centerX() - displayCenter);
        if (candidateDistance != incumbentDistance) return candidateDistance < incumbentDistance;
        if (candidate.width() != incumbent.width()) return candidate.width() > incumbent.width();
        return candidate.height() > incumbent.height();
    }

    private void refreshStableDisplayProfile() {
        Point displaySize = resolveDisplaySize();
        stableDisplayWidthPx = displaySize.x;
        stableDisplayHeightPx = displaySize.y;
        stableCutoutRect = resolveCutoutRect(displaySize);
    }

    private Point resolveDisplaySize() {
        Point point = new Point();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            point.x = bounds.width();
            point.y = bounds.height();
            return point;
        }
        windowManager.getDefaultDisplay().getRealSize(point);
        return point;
    }

    private int resolveStatusBarHeight() {
        int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resId != 0 ? context.getResources().getDimensionPixelSize(resId) : dp(24);
    }

    private int resolveStatusBarBottomPx() {
        int resourceHeight = resolveStatusBarHeight();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                WindowInsets insets = windowManager.getCurrentWindowMetrics().getWindowInsets();
                if (insets != null) {
                    int insetTop = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top;
                    return Math.max(resourceHeight, insetTop);
                }
            } catch (Exception ignored) {
            }
        }
        return resourceHeight;
    }

    private int resolveSystemGestureInsetLeftPx() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                WindowInsets insets = windowManager.getCurrentWindowMetrics().getWindowInsets();
                if (insets != null) {
                    return insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemGestures()).left;
                }
            } catch (Exception ignored) {
            }
        }
        return dp(6);
    }

    private int resolveSystemGestureInsetRightPx() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                WindowInsets insets = windowManager.getCurrentWindowMetrics().getWindowInsets();
                if (insets != null) {
                    return insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemGestures()).right;
                }
            } catch (Exception ignored) {
            }
        }
        return dp(6);
    }

    private void openApp() {
        try {
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launchIntent == null) return;
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(launchIntent);
        } catch (Exception ignored) {
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private int pxToDpRounded(int valuePx) {
        return Math.round(valuePx / context.getResources().getDisplayMetrics().density);
    }

    private int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }
}
