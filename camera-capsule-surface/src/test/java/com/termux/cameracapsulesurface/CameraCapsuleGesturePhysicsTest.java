package com.termux.cameracapsulesurface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CameraCapsuleGesturePhysicsTest {

    private final CameraCapsuleGesturePhysics physics = new CameraCapsuleGesturePhysics(3f);
    private final CameraCapsulePlacementEngine.DisplayProfile displayProfile =
        new CameraCapsulePlacementEngine.DisplayProfile(1200, 2600, 126, 520, 0, 680, 96, 54, 54);
    @Test
    public void update_farFromEdgesStaysFloating() {
        physics.beginGesture(600f, 260f, 0L);

        CameraCapsuleGesturePhysics.Snapshot snapshot =
            physics.update(displayProfile, placementAt(213, 143), 560f, 280f, 16L);

        assertFalse(snapshot.isActive());
        assertEquals(CameraCapsulePlacementEngine.SurfaceMode.FLOATING, physics.resolveReleaseSurfaceMode());
    }

    @Test
    public void update_leftApproachBuildsContinuousAttachment() {
        physics.beginGesture(600f, 236f, 0L);

        CameraCapsuleGesturePhysics.Snapshot snapshot1 =
            physics.update(displayProfile, placementAt(150, 143), 540f, 236f, 16L);
        CameraCapsuleGesturePhysics.Snapshot snapshot2 =
            physics.update(displayProfile, placementAt(90, 143), 480f, 236f, 32L);
        CameraCapsuleGesturePhysics.Snapshot snapshot3 =
            physics.update(displayProfile, placementAt(58, 143), 440f, 236f, 48L);
        CameraCapsuleGesturePhysics.Snapshot snapshot4 =
            physics.update(displayProfile, placementAt(44, 146), 410f, 236f, 64L);
        CameraCapsuleGesturePhysics.Snapshot snapshot5 =
            physics.update(displayProfile, placementAt(30, 150), 380f, 236f, 80L);
        CameraCapsuleGesturePhysics.Snapshot snapshot6 =
            physics.update(displayProfile, placementAt(24, 154), 360f, 236f, 96L);
        CameraCapsuleGesturePhysics.Snapshot snapshot7 =
            physics.update(displayProfile, placementAt(20, 158), 340f, 236f, 112L);
        CameraCapsuleGesturePhysics.Snapshot snapshot8 =
            physics.update(displayProfile, placementAt(18, 162), 320f, 236f, 128L);
        CameraCapsuleGesturePhysics.Snapshot snapshot9 =
            physics.update(displayProfile, placementAt(18, 166), 300f, 236f, 160L);
        CameraCapsuleGesturePhysics.Snapshot snapshot10 =
            physics.update(displayProfile, placementAt(18, 170), 280f, 236f, 192L);
        CameraCapsuleGesturePhysics.Snapshot snapshot11 =
            physics.update(displayProfile, placementAt(18, 174), 260f, 236f, 224L);
        CameraCapsuleGesturePhysics.Snapshot snapshot12 =
            physics.update(displayProfile, placementAt(18, 178), 240f, 236f, 256L);
        CameraCapsuleGesturePhysics.Snapshot snapshot13 =
            physics.update(displayProfile, placementAt(18, 182), 220f, 236f, 288L);
        CameraCapsuleGesturePhysics.Snapshot snapshot14 =
            physics.update(displayProfile, placementAt(18, 186), 200f, 236f, 320L);

        assertEquals(
            "mode=" + snapshot14.dominantMode
                + " c3=" + snapshot3.compressionProgress
                + " c4=" + snapshot4.compressionProgress
                + " c5=" + snapshot5.compressionProgress
                + " c14=" + snapshot14.compressionProgress
                + " p14=" + snapshot14.pageProgress
                + " rc14=" + snapshot14.releaseConfidence,
            CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT,
            snapshot14.dominantMode);
        assertTrue(snapshot1.compressionProgress <= snapshot2.compressionProgress);
        assertTrue(snapshot2.compressionProgress <= snapshot3.compressionProgress);
        assertTrue(snapshot3.compressionProgress <= snapshot4.compressionProgress);
        assertTrue(snapshot4.compressionProgress <= snapshot5.compressionProgress);
        assertTrue(snapshot5.bodySqueezeProgress <= snapshot14.bodySqueezeProgress);
        assertTrue(snapshot5.bodyAttachProgress <= snapshot14.bodyAttachProgress);
        assertTrue(snapshot14.contentVisibilityProgress < 1f);
        assertTrue(
            "snapshot14Compression=" + snapshot14.compressionProgress + " page=" + snapshot14.pageProgress,
            snapshot14.pageProgress > 0.04f);
        assertEquals(
            "releaseMode=" + physics.resolveReleaseSurfaceMode()
                + " c1=" + snapshot1.compressionProgress
                + " c2=" + snapshot2.compressionProgress
                + " c3=" + snapshot3.compressionProgress
                + " c4=" + snapshot4.compressionProgress
                + " c14=" + snapshot14.compressionProgress
                + " p14=" + snapshot14.pageProgress
                + " rc14=" + snapshot14.releaseConfidence,
            CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT,
            physics.resolveReleaseSurfaceMode());
    }

    @Test
    public void update_retreatDecaysInsteadOfSnappingImmediately() {
        physics.beginGesture(600f, 236f, 0L);
        physics.update(displayProfile, placementAt(88, 143), 500f, 236f, 16L);
        physics.update(displayProfile, placementAt(54, 146), 450f, 236f, 32L);
        CameraCapsuleGesturePhysics.Snapshot engaged =
            physics.update(displayProfile, placementAt(30, 150), 400f, 236f, 48L);
        CameraCapsuleGesturePhysics.Snapshot retreat1 =
            physics.update(displayProfile, placementAt(54, 149), 450f, 236f, 64L);
        CameraCapsuleGesturePhysics.Snapshot retreat2 =
            physics.update(displayProfile, placementAt(78, 146), 500f, 236f, 80L);
        CameraCapsuleGesturePhysics.Snapshot retreat3 =
            physics.update(displayProfile, placementAt(128, 143), 560f, 236f, 96L);

        assertTrue(
            "mode=" + engaged.dominantMode
                + " engaged=" + engaged.compressionProgress + " retreat1=" + retreat1.compressionProgress
                + " retreat2=" + retreat2.compressionProgress + " retreat3=" + retreat3.compressionProgress,
            engaged.compressionProgress > 0.02f);
        assertTrue("retreat1=" + retreat1.compressionProgress, retreat1.compressionProgress > 0.02f);
        assertTrue(
            "retreat1=" + retreat1.compressionProgress + " retreat3=" + retreat3.compressionProgress,
            retreat3.compressionProgress < retreat1.compressionProgress);
    }

    @Test
    public void update_halfEdgeTouchDoesNotCommit() {
        physics.beginGesture(600f, 236f, 0L);
        physics.update(displayProfile, placementAt(88, 143), 510f, 236f, 16L);
        CameraCapsuleGesturePhysics.Snapshot snapshot =
            physics.update(displayProfile, placementAt(62, 143), 470f, 236f, 32L);

        assertTrue("page=" + snapshot.pageProgress + " confidence=" + snapshot.releaseConfidence,
            snapshot.releaseConfidence < 0.60f);
        assertEquals(CameraCapsulePlacementEngine.SurfaceMode.FLOATING, physics.resolveReleaseSurfaceMode());
    }

    @Test
    public void update_edgeHoldCommitsWithoutNeedingOffscreenOvershoot() {
        physics.beginGesture(600f, 236f, 0L);
        physics.update(displayProfile, placementAt(128, 143), 520f, 236f, 16L);
        physics.update(displayProfile, placementAt(98, 143), 470f, 236f, 32L);
        physics.update(displayProfile, placementAt(78, 144), 420f, 236f, 48L);

        CameraCapsuleGesturePhysics.Snapshot hold1 =
            physics.update(displayProfile, placementAt(70, 145), 390f, 236f, 64L);
        CameraCapsuleGesturePhysics.Snapshot hold2 =
            physics.update(displayProfile, placementAt(70, 146), 390f, 236f, 96L);
        CameraCapsuleGesturePhysics.Snapshot hold3 =
            physics.update(displayProfile, placementAt(70, 147), 390f, 236f, 128L);
        CameraCapsuleGesturePhysics.Snapshot hold4 =
            physics.update(displayProfile, placementAt(70, 148), 390f, 236f, 160L);

        assertTrue("page=" + hold4.pageProgress, hold4.pageProgress >= hold1.pageProgress);
        assertTrue("attach=" + hold4.bodyAttachProgress, hold4.bodyAttachProgress >= hold2.bodyAttachProgress);
        assertEquals(
            "hold2Page=" + hold2.pageProgress
                + " hold4Page=" + hold4.pageProgress
                + " hold4Attach=" + hold4.bodyAttachProgress
                + " release=" + physics.resolveReleaseSurfaceMode(),
            CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT,
            physics.resolveReleaseSurfaceMode());
    }

    private CameraCapsulePlacementEngine.PlacementResult placementAt(int xPx, int yPx) {
        return new CameraCapsulePlacementEngine.PlacementResult(
            CameraCapsulePlacementEngine.SurfaceMode.FLOATING,
            CameraCapsuleSurfaceState.DOCK_EDGE_NONE,
            xPx,
            yPx,
            774,
            186,
            0,
            0);
    }
}
