package com.termux.cameracapsulesurface;

import androidx.annotation.NonNull;

final class CameraCapsulePlacementEngine {

    enum SurfaceMode {
        FLOATING,
        DOCKED_TOP,
        DOCKED_LEFT,
        DOCKED_RIGHT
    }

    static final class DisplayProfile {
        final int displayWidthPx;
        final int displayHeightPx;
        final int statusBarBottomPx;
        final int cutoutLeftPx;
        final int cutoutTopPx;
        final int cutoutRightPx;
        final int cutoutBottomPx;
        final int systemGestureInsetLeftPx;
        final int systemGestureInsetRightPx;

        DisplayProfile(int displayWidthPx,
                       int displayHeightPx,
                       int statusBarBottomPx,
                       int cutoutLeftPx,
                       int cutoutTopPx,
                       int cutoutRightPx,
                       int cutoutBottomPx,
                       int systemGestureInsetLeftPx,
                       int systemGestureInsetRightPx) {
            this.displayWidthPx = displayWidthPx;
            this.displayHeightPx = displayHeightPx;
            this.statusBarBottomPx = statusBarBottomPx;
            this.cutoutLeftPx = cutoutLeftPx;
            this.cutoutTopPx = cutoutTopPx;
            this.cutoutRightPx = cutoutRightPx;
            this.cutoutBottomPx = cutoutBottomPx;
            this.systemGestureInsetLeftPx = systemGestureInsetLeftPx;
            this.systemGestureInsetRightPx = systemGestureInsetRightPx;
        }

        boolean hasCutout() {
            return cutoutRightPx > cutoutLeftPx && cutoutBottomPx > cutoutTopPx;
        }

        int cutoutWidthPx() {
            return Math.max(0, cutoutRightPx - cutoutLeftPx);
        }

        int cutoutCenterXPx() {
            return hasCutout() ? (cutoutLeftPx + cutoutRightPx) / 2 : displayWidthPx / 2;
        }
    }

    static final class ContentMetrics {
        final int floatingWidthPx;
        final int floatingHeightPx;
        final int dockedTopWidthPx;
        final int dockedTopHeightPx;
        final int dockedSideWidthPx;
        final int dockedSideHeightPx;

        ContentMetrics(int floatingWidthPx,
                       int floatingHeightPx,
                       int dockedTopWidthPx,
                       int dockedTopHeightPx,
                       int dockedSideWidthPx,
                       int dockedSideHeightPx) {
            this.floatingWidthPx = floatingWidthPx;
            this.floatingHeightPx = floatingHeightPx;
            this.dockedTopWidthPx = dockedTopWidthPx;
            this.dockedTopHeightPx = dockedTopHeightPx;
            this.dockedSideWidthPx = dockedSideWidthPx;
            this.dockedSideHeightPx = dockedSideHeightPx;
        }
    }

    static final class PlacementResult {
        @NonNull final SurfaceMode surfaceMode;
        @NonNull final String dockEdge;
        final int xPx;
        final int yPx;
        final int widthPx;
        final int heightPx;
        final int committedOffsetXPx;
        final int committedOffsetYPx;

        PlacementResult(@NonNull SurfaceMode surfaceMode,
                        @NonNull String dockEdge,
                        int xPx,
                        int yPx,
                        int widthPx,
                        int heightPx,
                        int committedOffsetXPx,
                        int committedOffsetYPx) {
            this.surfaceMode = surfaceMode;
            this.dockEdge = dockEdge;
            this.xPx = xPx;
            this.yPx = yPx;
            this.widthPx = widthPx;
            this.heightPx = heightPx;
            this.committedOffsetXPx = committedOffsetXPx;
            this.committedOffsetYPx = committedOffsetYPx;
        }
    }

    private final float density;

    CameraCapsulePlacementEngine(float density) {
        this.density = Math.max(0.75f, density);
    }

    @NonNull
    PlacementResult resolve(@NonNull DisplayProfile displayProfile,
                            @NonNull CameraCapsuleSurfaceState state,
                            @NonNull ContentMetrics contentMetrics,
                            int transientDragXPx,
                            int transientDragYPx) {
        return resolve(displayProfile, state, contentMetrics, transientDragXPx, transientDragYPx, true, null);
    }

    @NonNull
    PlacementResult resolve(@NonNull DisplayProfile displayProfile,
                            @NonNull CameraCapsuleSurfaceState state,
                            @NonNull ContentMetrics contentMetrics,
                            int transientDragXPx,
                            int transientDragYPx,
                            boolean allowDocking) {
        return resolve(displayProfile, state, contentMetrics, transientDragXPx, transientDragYPx, allowDocking, null);
    }

    @NonNull
    PlacementResult resolveForcedSurfaceMode(@NonNull DisplayProfile displayProfile,
                                             @NonNull CameraCapsuleSurfaceState state,
                                             @NonNull ContentMetrics contentMetrics,
                                             int transientDragXPx,
                                             int transientDragYPx,
                                             @NonNull SurfaceMode forcedSurfaceMode) {
        return resolve(displayProfile, state, contentMetrics, transientDragXPx, transientDragYPx, true, forcedSurfaceMode);
    }

    @NonNull
    private PlacementResult resolve(@NonNull DisplayProfile displayProfile,
                                    @NonNull CameraCapsuleSurfaceState state,
                                    @NonNull ContentMetrics contentMetrics,
                                    int transientDragXPx,
                                    int transientDragYPx,
                                    boolean allowDocking,
                                    @androidx.annotation.Nullable SurfaceMode forcedSurfaceMode) {
        int baseTopYPx = resolveBaseTopYPx(displayProfile, state);
        int requestedOffsetXPx = dp(state.offsetXDp) + transientDragXPx;
        int requestedOffsetYPx = dp(state.offsetYDp) + transientDragYPx;

        int floatingOriginXPx = resolveFloatingOriginXPx(displayProfile, state, contentMetrics.floatingWidthPx);
        int floatingXPx = clamp(
            floatingOriginXPx + requestedOffsetXPx,
            horizontalMarginPx(),
            Math.max(horizontalMarginPx(), displayProfile.displayWidthPx - contentMetrics.floatingWidthPx - horizontalMarginPx()));
        int floatingYPx = clamp(
            baseTopYPx + requestedOffsetYPx,
            0,
            Math.max(0, displayProfile.displayHeightPx - contentMetrics.floatingHeightPx));

        SurfaceMode targetMode = forcedSurfaceMode != null
            ? forcedSurfaceMode
            : (allowDocking
                ? resolveTargetMode(displayProfile, state, baseTopYPx, floatingXPx, floatingYPx, contentMetrics)
                : SurfaceMode.FLOATING);
        if (targetMode == SurfaceMode.FLOATING) {
            return buildFloatingPlacementResult(contentMetrics, baseTopYPx, floatingOriginXPx, floatingXPx, floatingYPx);
        }

        String dockEdge = resolveDockEdge(displayProfile, state, targetMode, floatingXPx, floatingYPx, contentMetrics);
        int dockedXPx = resolveDockedXPx(displayProfile, targetMode, dockEdge, contentMetrics);
        int dockedYPx = resolveDockedYPx(displayProfile, targetMode, floatingYPx, contentMetrics);
        int dockedWidthPx = targetMode == SurfaceMode.DOCKED_TOP
            ? contentMetrics.dockedTopWidthPx
            : contentMetrics.dockedSideWidthPx;
        int dockedHeightPx = targetMode == SurfaceMode.DOCKED_TOP
            ? contentMetrics.dockedTopHeightPx
            : contentMetrics.dockedSideHeightPx;
        return new PlacementResult(
            targetMode,
            dockEdge,
            dockedXPx,
            dockedYPx,
            dockedWidthPx,
            dockedHeightPx,
            dockedXPx - floatingOriginXPx,
            dockedYPx - baseTopYPx);
    }

    @NonNull
    private PlacementResult buildFloatingPlacementResult(@NonNull ContentMetrics contentMetrics,
                                                         int baseTopYPx,
                                                         int floatingOriginXPx,
                                                         int floatingXPx,
                                                         int floatingYPx) {
        return new PlacementResult(
            SurfaceMode.FLOATING,
            CameraCapsuleSurfaceState.DOCK_EDGE_NONE,
            floatingXPx,
            floatingYPx,
            contentMetrics.floatingWidthPx,
            contentMetrics.floatingHeightPx,
            floatingXPx - floatingOriginXPx,
            floatingYPx - baseTopYPx);
    }

    int resolveFloatingWidthPx(@NonNull CameraCapsuleSurfaceState state, @NonNull DisplayProfile displayProfile) {
        int requestedWidthPx = state.widthDp > 0 ? dp(state.widthDp) : 0;
        int fallbackWidthPx = dp(state.expanded || state.progressMax > 0 || state.progressIndeterminate ? 280 : 224);
        if (displayProfile.hasCutout() && CameraCapsuleSurfaceState.ANCHOR_CUTOUT.equals(state.anchorMode)) {
            fallbackWidthPx = Math.max(fallbackWidthPx, displayProfile.cutoutWidthPx() + dp(72));
        }
        int desiredWidthPx = requestedWidthPx > 0 ? requestedWidthPx : fallbackWidthPx;
        int maxWidthPx = Math.max(dp(180), displayProfile.displayWidthPx - dp(24));
        return clamp(desiredWidthPx, dp(180), maxWidthPx);
    }

    int resolveDockedWidthPx(@NonNull CameraCapsuleSurfaceState state, @NonNull DisplayProfile displayProfile) {
        int requestedWidthPx = state.widthDp > 0 ? dp(state.widthDp) : 0;
        int floatingWidthPx = resolveFloatingWidthPx(state, displayProfile);
        int fallbackWidthPx = Math.max(
            dp(state.progressMax > 0 || state.progressIndeterminate ? 120 : 108),
            Math.round(floatingWidthPx * 0.48f));
        if (displayProfile.hasCutout() && CameraCapsuleSurfaceState.ANCHOR_CUTOUT.equals(state.anchorMode)) {
            fallbackWidthPx = Math.max(fallbackWidthPx, displayProfile.cutoutWidthPx() + dp(24));
        }
        int desiredWidthPx = requestedWidthPx > 0 ? requestedWidthPx : fallbackWidthPx;
        int maxWidthPx = Math.max(dp(132), displayProfile.displayWidthPx - dp(12));
        return clamp(desiredWidthPx, dp(132), maxWidthPx);
    }

    int resolveDockedSideWidthPx() {
        return dp(12);
    }

    int resolveDockedSideHeightPx(@NonNull CameraCapsuleSurfaceState state, @NonNull DisplayProfile displayProfile) {
        int floatingWidthPx = resolveFloatingWidthPx(state, displayProfile);
        int desiredHeightPx = Math.max(dp(72), Math.round(floatingWidthPx * 0.38f));
        int maxHeightPx = Math.max(dp(96), displayProfile.displayHeightPx - dp(96));
        return clamp(desiredHeightPx, dp(72), maxHeightPx);
    }

    int resolveFloatingOriginXPx(@NonNull DisplayProfile displayProfile,
                                 @NonNull CameraCapsuleSurfaceState state,
                                 int floatingWidthPx) {
        return resolveBaseCenterXPx(displayProfile, state) - (floatingWidthPx / 2);
    }

    int resolveBaseTopPx(@NonNull DisplayProfile displayProfile, @NonNull CameraCapsuleSurfaceState state) {
        return resolveBaseTopYPx(displayProfile, state);
    }

    int resolveUndockedFloatingXPx(@NonNull DisplayProfile displayProfile,
                                   @NonNull SurfaceMode previousMode,
                                   int floatingWidthPx) {
        int minXPx = horizontalMarginPx();
        int maxXPx = Math.max(minXPx, displayProfile.displayWidthPx - floatingWidthPx - horizontalMarginPx());
        if (previousMode == SurfaceMode.DOCKED_LEFT) {
            return clamp(resolveLeftDockInsetPx(displayProfile) + sideUndockReleaseDistancePx(), minXPx, maxXPx);
        }
        if (previousMode == SurfaceMode.DOCKED_RIGHT) {
            return clamp(
                displayProfile.displayWidthPx - floatingWidthPx - resolveRightDockInsetPx(displayProfile) - sideUndockReleaseDistancePx(),
                minXPx,
                maxXPx);
        }
        return clamp(
            (displayProfile.displayWidthPx - floatingWidthPx) / 2,
            minXPx,
            maxXPx);
    }

    int resolveUndockedFloatingYPx(@NonNull DisplayProfile displayProfile,
                                   @NonNull CameraCapsuleSurfaceState state,
                                   @NonNull SurfaceMode previousMode,
                                   int floatingHeightPx,
                                   int currentFloatingYPx) {
        int minYPx = 0;
        int maxYPx = Math.max(0, displayProfile.displayHeightPx - floatingHeightPx);
        if (previousMode == SurfaceMode.DOCKED_TOP) {
            return clamp(resolveBaseTopYPx(displayProfile, state) + topUndockReleaseDistancePx(), minYPx, maxYPx);
        }
        return clamp(currentFloatingYPx, minYPx, maxYPx);
    }

    @NonNull
    private SurfaceMode resolveTargetMode(@NonNull DisplayProfile displayProfile,
                                          @NonNull CameraCapsuleSurfaceState state,
                                          int baseTopYPx,
                                          int floatingXPx,
                                          int floatingYPx,
                                          @NonNull ContentMetrics contentMetrics) {
        if (shouldDockTop(state, baseTopYPx, floatingYPx)) return SurfaceMode.DOCKED_TOP;
        if (shouldDockLeft(displayProfile, state, floatingXPx)) return SurfaceMode.DOCKED_LEFT;
        if (shouldDockRight(displayProfile, state, floatingXPx, contentMetrics.floatingWidthPx)) {
            return SurfaceMode.DOCKED_RIGHT;
        }
        return SurfaceMode.FLOATING;
    }

    @NonNull
    private String resolveDockEdge(@NonNull DisplayProfile displayProfile,
                                   @NonNull CameraCapsuleSurfaceState state,
                                   @NonNull SurfaceMode surfaceMode,
                                   int floatingXPx,
                                   int floatingYPx,
                                   @NonNull ContentMetrics contentMetrics) {
        if (surfaceMode == SurfaceMode.DOCKED_LEFT) return CameraCapsuleSurfaceState.DOCK_EDGE_LEFT;
        if (surfaceMode == SurfaceMode.DOCKED_RIGHT) return CameraCapsuleSurfaceState.DOCK_EDGE_RIGHT;
        int candidateCenterXPx = floatingXPx + (contentMetrics.floatingWidthPx / 2);
        if (CameraCapsuleSurfaceState.PLACEMENT_DOCKED_TOP.equals(state.placementMode)
            && transientlyStableDockEdge(state.dockEdge)) {
            int leftBoundaryPx = displayProfile.displayWidthPx / 3;
            int rightBoundaryPx = displayProfile.displayWidthPx - leftBoundaryPx;
            if (CameraCapsuleSurfaceState.DOCK_EDGE_LEFT.equals(state.dockEdge) && candidateCenterXPx <= rightBoundaryPx) {
                return CameraCapsuleSurfaceState.DOCK_EDGE_LEFT;
            }
            if (CameraCapsuleSurfaceState.DOCK_EDGE_RIGHT.equals(state.dockEdge) && candidateCenterXPx >= leftBoundaryPx) {
                return CameraCapsuleSurfaceState.DOCK_EDGE_RIGHT;
            }
            if (CameraCapsuleSurfaceState.DOCK_EDGE_CENTER.equals(state.dockEdge)) {
                return CameraCapsuleSurfaceState.DOCK_EDGE_CENTER;
            }
        }

        int centerAnchorXPx = displayProfile.hasCutout()
            ? displayProfile.cutoutCenterXPx()
            : displayProfile.displayWidthPx / 2;
        int centerBandHalfWidthPx = displayProfile.hasCutout()
            ? Math.max(dp(44), displayProfile.cutoutWidthPx() / 2 + dp(18))
            : dp(88);
        if (Math.abs(candidateCenterXPx - centerAnchorXPx) <= centerBandHalfWidthPx) {
            return CameraCapsuleSurfaceState.DOCK_EDGE_CENTER;
        }
        return candidateCenterXPx < centerAnchorXPx
            ? CameraCapsuleSurfaceState.DOCK_EDGE_LEFT
            : CameraCapsuleSurfaceState.DOCK_EDGE_RIGHT;
    }

    private boolean transientlyStableDockEdge(@NonNull String dockEdge) {
        return CameraCapsuleSurfaceState.DOCK_EDGE_LEFT.equals(dockEdge)
            || CameraCapsuleSurfaceState.DOCK_EDGE_CENTER.equals(dockEdge)
            || CameraCapsuleSurfaceState.DOCK_EDGE_RIGHT.equals(dockEdge);
    }

    private int resolveDockedXPx(@NonNull DisplayProfile displayProfile,
                                 @NonNull SurfaceMode surfaceMode,
                                 @NonNull String dockEdge,
                                 @NonNull ContentMetrics contentMetrics) {
        if (surfaceMode == SurfaceMode.DOCKED_LEFT) return resolveLeftDockInsetPx(displayProfile);
        if (surfaceMode == SurfaceMode.DOCKED_RIGHT) {
            return clamp(
                displayProfile.displayWidthPx - contentMetrics.dockedSideWidthPx - resolveRightDockInsetPx(displayProfile),
                resolveLeftDockInsetPx(displayProfile),
                Math.max(
                    resolveLeftDockInsetPx(displayProfile),
                    displayProfile.displayWidthPx - contentMetrics.dockedSideWidthPx - resolveRightDockInsetPx(displayProfile)));
        }
        int dockedWidthPx = contentMetrics.dockedTopWidthPx;
        if (CameraCapsuleSurfaceState.DOCK_EDGE_LEFT.equals(dockEdge)) {
            return horizontalMarginPx();
        }
        if (CameraCapsuleSurfaceState.DOCK_EDGE_RIGHT.equals(dockEdge)) {
            return Math.max(horizontalMarginPx(), displayProfile.displayWidthPx - dockedWidthPx - horizontalMarginPx());
        }
        int centerAnchorXPx = displayProfile.hasCutout()
            ? displayProfile.cutoutCenterXPx()
            : displayProfile.displayWidthPx / 2;
        return clamp(
            centerAnchorXPx - (dockedWidthPx / 2),
            horizontalMarginPx(),
            Math.max(horizontalMarginPx(), displayProfile.displayWidthPx - dockedWidthPx - horizontalMarginPx()));
    }

    private int resolveDockedYPx(@NonNull DisplayProfile displayProfile,
                                 @NonNull SurfaceMode surfaceMode,
                                 int floatingYPx,
                                 @NonNull ContentMetrics contentMetrics) {
        if (surfaceMode == SurfaceMode.DOCKED_LEFT || surfaceMode == SurfaceMode.DOCKED_RIGHT) {
            int minTopPx = Math.max(displayProfile.statusBarBottomPx + dp(8), dp(32));
            int centerYPx = floatingYPx + (contentMetrics.floatingHeightPx / 2);
            int proposedTopPx = centerYPx - (contentMetrics.dockedSideHeightPx / 2);
            return clamp(
                proposedTopPx,
                minTopPx,
                Math.max(minTopPx, displayProfile.displayHeightPx - contentMetrics.dockedSideHeightPx - dp(24)));
        }
        int attachmentTopPx = Math.max(displayProfile.statusBarBottomPx, displayProfile.cutoutBottomPx);
        return Math.max(0, attachmentTopPx);
    }

    private boolean shouldDockTop(@NonNull CameraCapsuleSurfaceState state, int baseTopYPx, int floatingYPx) {
        if (CameraCapsuleSurfaceState.PLACEMENT_DOCKED_TOP.equals(state.placementMode)) {
            return floatingYPx <= baseTopYPx + dp(20);
        }
        int dockActivationYPx = Math.max(0, baseTopYPx - dp(16));
        return floatingYPx <= dockActivationYPx;
    }

    private boolean shouldDockLeft(@NonNull DisplayProfile displayProfile,
                                   @NonNull CameraCapsuleSurfaceState state,
                                   int floatingXPx) {
        int stickyThresholdPx = resolveLeftDockInsetPx(displayProfile) + edgeDockStickyThresholdPx();
        int activationThresholdPx = resolveLeftDockInsetPx(displayProfile) + edgeDockActivationThresholdPx();
        if (CameraCapsuleSurfaceState.PLACEMENT_DOCKED_LEFT.equals(state.placementMode)) {
            return floatingXPx <= stickyThresholdPx;
        }
        return floatingXPx <= activationThresholdPx;
    }

    private boolean shouldDockRight(@NonNull DisplayProfile displayProfile,
                                    @NonNull CameraCapsuleSurfaceState state,
                                    int floatingXPx,
                                    int floatingWidthPx) {
        int distanceToRightPx = displayProfile.displayWidthPx - (floatingXPx + floatingWidthPx);
        int stickyThresholdPx = resolveRightDockInsetPx(displayProfile) + edgeDockStickyThresholdPx();
        int activationThresholdPx = resolveRightDockInsetPx(displayProfile) + edgeDockActivationThresholdPx();
        if (CameraCapsuleSurfaceState.PLACEMENT_DOCKED_RIGHT.equals(state.placementMode)) {
            return distanceToRightPx <= stickyThresholdPx;
        }
        return distanceToRightPx <= activationThresholdPx;
    }

    private int resolveLeftGestureReservePx(@NonNull DisplayProfile displayProfile) {
        return Math.max(dp(16), displayProfile.systemGestureInsetLeftPx);
    }

    private int resolveRightGestureReservePx(@NonNull DisplayProfile displayProfile) {
        return Math.max(dp(16), displayProfile.systemGestureInsetRightPx);
    }

    private int resolveLeftDockInsetPx(@NonNull DisplayProfile displayProfile) {
        return resolveLeftGestureReservePx(displayProfile) + sideDockVisualGapPx();
    }

    private int resolveRightDockInsetPx(@NonNull DisplayProfile displayProfile) {
        return resolveRightGestureReservePx(displayProfile) + sideDockVisualGapPx();
    }

    private int sideDockVisualGapPx() {
        return dp(2);
    }

    private int edgeDockActivationThresholdPx() {
        return dp(14);
    }

    private int edgeDockStickyThresholdPx() {
        return dp(22);
    }

    private int sideUndockReleaseDistancePx() {
        return dp(56);
    }

    private int topUndockReleaseDistancePx() {
        return dp(36);
    }

    private int resolveBaseCenterXPx(@NonNull DisplayProfile displayProfile, @NonNull CameraCapsuleSurfaceState state) {
        if (CameraCapsuleSurfaceState.ANCHOR_CUTOUT.equals(state.anchorMode) && displayProfile.hasCutout()) {
            return displayProfile.cutoutCenterXPx();
        }
        return displayProfile.displayWidthPx / 2;
    }

    private int resolveBaseTopYPx(@NonNull DisplayProfile displayProfile, @NonNull CameraCapsuleSurfaceState state) {
        int baseTopYPx = Math.max(topInsetMarginPx(), displayProfile.statusBarBottomPx + dp(6));
        if (CameraCapsuleSurfaceState.ANCHOR_CUTOUT.equals(state.anchorMode) && displayProfile.hasCutout()) {
            baseTopYPx = Math.max(baseTopYPx, displayProfile.cutoutBottomPx + dp(6));
        }
        return baseTopYPx;
    }

    private int horizontalMarginPx() {
        return dp(8);
    }

    private int topInsetMarginPx() {
        return dp(6);
    }

    private int dp(int valueDp) {
        return Math.round(valueDp * density);
    }

    private int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }
}
