package com.termux.cameracapsulesurface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CameraCapsulePlacementEngineTest {

    private final CameraCapsulePlacementEngine engine = new CameraCapsulePlacementEngine(3f);
    private final CameraCapsulePlacementEngine.DisplayProfile displayProfile =
        new CameraCapsulePlacementEngine.DisplayProfile(1200, 2600, 126, 520, 0, 680, 96, 54, 54);

    @Test
    public void resolve_keepsDefaultSurfaceFloating() {
        CameraCapsuleSurfaceState state = new CameraCapsuleSurfaceState.Builder()
            .setSurfaceId("default")
            .setTitle("任务")
            .build();

        CameraCapsulePlacementEngine.PlacementResult result = engine.resolve(
            displayProfile,
            state,
            metrics(state),
            0,
            0);

        assertEquals(CameraCapsulePlacementEngine.SurfaceMode.FLOATING, result.surfaceMode);
        assertEquals(CameraCapsuleSurfaceState.DOCK_EDGE_NONE, result.dockEdge);
    }

    @Test
    public void resolve_docksWhenDraggedIntoTopBand() {
        CameraCapsuleSurfaceState state = new CameraCapsuleSurfaceState.Builder()
            .setSurfaceId("dock-top")
            .setTitle("任务")
            .build();

        CameraCapsulePlacementEngine.PlacementResult result = engine.resolve(
            displayProfile,
            state,
            metrics(state),
            0,
            -72);

        assertEquals(CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP, result.surfaceMode);
        assertEquals(CameraCapsuleSurfaceState.DOCK_EDGE_CENTER, result.dockEdge);
    }

    @Test
    public void resolve_preservesPersistedDockedStateUntilDraggedDown() {
        CameraCapsuleSurfaceState dockedState = new CameraCapsuleSurfaceState.Builder()
            .setSurfaceId("persisted")
            .setTitle("任务")
            .setPlacementMode(CameraCapsuleSurfaceState.PLACEMENT_DOCKED_TOP)
            .setDockEdge(CameraCapsuleSurfaceState.DOCK_EDGE_RIGHT)
            .build();

        CameraCapsulePlacementEngine.PlacementResult anchoredResult = engine.resolve(
            displayProfile,
            dockedState,
            metrics(dockedState),
            0,
            0);
        CameraCapsulePlacementEngine.PlacementResult releasedResult = engine.resolve(
            displayProfile,
            dockedState,
            metrics(dockedState),
            0,
            120);

        assertEquals(CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP, anchoredResult.surfaceMode);
        assertEquals(CameraCapsuleSurfaceState.DOCK_EDGE_RIGHT, anchoredResult.dockEdge);
        assertEquals(CameraCapsulePlacementEngine.SurfaceMode.FLOATING, releasedResult.surfaceMode);
    }

    @Test
    public void resolve_docksToLeftWhenReleasedAwayFromCutout() {
        CameraCapsuleSurfaceState state = new CameraCapsuleSurfaceState.Builder()
            .setSurfaceId("left")
            .setTitle("任务")
            .build();

        CameraCapsulePlacementEngine.PlacementResult result = engine.resolve(
            displayProfile,
            state,
            metrics(state),
            -320,
            60);

        assertEquals(CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT, result.surfaceMode);
        assertEquals(CameraCapsuleSurfaceState.DOCK_EDGE_LEFT, result.dockEdge);
        assertTrue(result.xPx > displayProfile.systemGestureInsetLeftPx);
    }

    @Test
    public void resolve_staysFloatingWhenDockingIsSuspended() {
        CameraCapsuleSurfaceState state = new CameraCapsuleSurfaceState.Builder()
            .setSurfaceId("left-early")
            .setTitle("任务")
            .build();

        CameraCapsulePlacementEngine.PlacementResult result = engine.resolve(
            displayProfile,
            state,
            metrics(state),
            -160,
            60,
            false);

        assertEquals(CameraCapsulePlacementEngine.SurfaceMode.FLOATING, result.surfaceMode);
    }

    @Test
    public void resolve_docksToRightWhenDraggedToRightEdge() {
        CameraCapsuleSurfaceState state = new CameraCapsuleSurfaceState.Builder()
            .setSurfaceId("right")
            .setTitle("任务")
            .build();

        CameraCapsulePlacementEngine.PlacementResult result = engine.resolve(
            displayProfile,
            state,
            metrics(state),
            360,
            40);

        assertEquals(CameraCapsulePlacementEngine.SurfaceMode.DOCKED_RIGHT, result.surfaceMode);
        assertEquals(CameraCapsuleSurfaceState.DOCK_EDGE_RIGHT, result.dockEdge);
        int rightGapPx = displayProfile.displayWidthPx - (result.xPx + result.widthPx);
        assertTrue(rightGapPx > displayProfile.systemGestureInsetRightPx);
    }

    private CameraCapsulePlacementEngine.ContentMetrics metrics(CameraCapsuleSurfaceState state) {
        int floatingWidth = engine.resolveFloatingWidthPx(state, displayProfile);
        int dockedTopWidth = engine.resolveDockedWidthPx(state, displayProfile);
        int dockedSideWidth = engine.resolveDockedSideWidthPx();
        int dockedSideHeight = engine.resolveDockedSideHeightPx(state, displayProfile);
        return new CameraCapsulePlacementEngine.ContentMetrics(
            floatingWidth,
            168,
            dockedTopWidth,
            33,
            dockedSideWidth,
            dockedSideHeight);
    }
}
