package com.termux.terminalsessionsurface;

import org.junit.Assert;
import org.junit.Test;

public class TerminalImeViewportPolicyTest {

    @Test
    public void computesOnlyActualSurfaceOcclusion() {
        Assert.assertEquals(944,
            TerminalImeViewportPolicy.computeOccludedHeight(2144f, 2000, 2200, 1000));
        Assert.assertEquals(0,
            TerminalImeViewportPolicy.computeOccludedHeight(1100f, 1000, 2200, 1000));
    }

    @Test
    public void clampsPathologicalInsetsWithoutMovingPastTheSurface() {
        Assert.assertEquals(800,
            TerminalImeViewportPolicy.computeOccludedHeight(2100.25f, 800, 2200, 4000));
        Assert.assertEquals(0,
            TerminalImeViewportPolicy.computeOccludedHeight(2100f, 800, 2200, 0));
    }

    @Test
    public void geometryRemainsLockedAcrossAnimationEndpointFrames() {
        Assert.assertTrue(TerminalImeViewportPolicy.shouldLockTerminalGeometry(0, true));
        Assert.assertTrue(TerminalImeViewportPolicy.shouldLockTerminalGeometry(900, false));
        Assert.assertFalse(TerminalImeViewportPolicy.shouldLockTerminalGeometry(0, false));
    }

    @Test
    public void keepsImeAndAppChromeCoordinatesSeparate() {
        Assert.assertEquals(1768,
            TerminalImeViewportPolicy.computeImeTopInWindow(2664, 896));

        // A panel ending at 2506 moves to the visible navigation top at 1613. The 155 px
        // navigation height is a boundary position, never an addition to the IME inset.
        Assert.assertEquals(-893,
            TerminalImeViewportPolicy.computeAnchoredChromeTranslation(2506f, 1613, true));
        Assert.assertEquals(0,
            TerminalImeViewportPolicy.computeAnchoredChromeTranslation(1600f, 1613, true));
        Assert.assertEquals(0,
            TerminalImeViewportPolicy.computeAnchoredChromeTranslation(2506f, 1613, false));
    }

    @Test
    public void cursorPanIsMinimalAndNeverExceedsTheTerminal() {
        Assert.assertEquals(0,
            TerminalImeViewportPolicy.computeCursorPanTranslation(1300f, 1400, 2073, true));
        Assert.assertEquals(-111,
            TerminalImeViewportPolicy.computeCursorPanTranslation(1510.25f, 1400, 2073, true));
        Assert.assertEquals(-2073,
            TerminalImeViewportPolicy.computeCursorPanTranslation(5000f, 1400, 2073, true));
        Assert.assertEquals(0,
            TerminalImeViewportPolicy.computeCursorPanTranslation(1510f, 1400, 2073, false));
    }
}
