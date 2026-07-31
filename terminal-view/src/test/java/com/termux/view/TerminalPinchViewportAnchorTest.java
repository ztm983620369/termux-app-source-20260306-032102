package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class TerminalPinchViewportAnchorTest {

    @Test
    public void focusCellAndFractionRoundTripAcrossMetricChange() {
        float focusY = 487f;
        float continuous = TerminalPinchViewportAnchor.continuousViewportRow(
            focusY, 11, 24f, -5f);
        int oldRow = TerminalPinchViewportAnchor.cellRow(continuous, 40);
        float fraction = TerminalPinchViewportAnchor.cellFraction(continuous, oldRow);

        int targetRow = TerminalPinchViewportAnchor.targetCellRow(focusY, 14, 37f, 28);
        float targetOffset = TerminalPinchViewportAnchor.targetPixelOffset(
            focusY, 14, 37f, targetRow, fraction);

        assertEquals(focusY,
            14f + (targetRow + fraction) * 37f - targetOffset, 0.0001f);
    }

    @Test
    public void focusCoordinatesClampToVisibleGrid() {
        assertEquals(0, TerminalPinchViewportAnchor.cellColumn(-50f, 10f, 80));
        assertEquals(79, TerminalPinchViewportAnchor.cellColumn(900f, 10f, 80));
        assertEquals(0, TerminalPinchViewportAnchor.cellRow(-2.5f, 24));
        assertEquals(23, TerminalPinchViewportAnchor.cellRow(200f, 24));
    }

    @Test
    public void pixelOffsetParticipatesInCellSelection() {
        float withoutOffset = TerminalPinchViewportAnchor.continuousViewportRow(
            100f, 4, 20f, 0f);
        float withOffset = TerminalPinchViewportAnchor.continuousViewportRow(
            100f, 4, 20f, 10f);

        assertEquals(4, TerminalPinchViewportAnchor.cellRow(withoutOffset, 30));
        assertEquals(5, TerminalPinchViewportAnchor.cellRow(withOffset, 30));
    }

    @Test
    public void trackedCellIncludesExactlyOneRetainedOverscanRow() {
        assertEquals(-1, TerminalPinchViewportAnchor.trackedCellRow(-0.25f, 30));
        assertEquals(0, TerminalPinchViewportAnchor.trackedCellRow(0.25f, 30));
        assertEquals(29, TerminalPinchViewportAnchor.trackedCellRow(29.75f, 30));
        assertEquals(30, TerminalPinchViewportAnchor.trackedCellRow(30.25f, 30));
        assertEquals(-1, TerminalPinchViewportAnchor.trackedCellRow(-200f, 30));
        assertEquals(30, TerminalPinchViewportAnchor.trackedCellRow(200f, 30));
    }

    @Test
    public void asymmetricPinchReportsCentroidDriftWithoutMovingLockedPivot() {
        float lockedX = 450f;
        float lockedY = 800f;
        float reportedX = 450f;
        float reportedY = 320f;
        float fraction = 0.25f;
        int targetRow = TerminalPinchViewportAnchor.targetCellRow(
            lockedY, 12, 30f, 50);
        float targetOffset = TerminalPinchViewportAnchor.targetPixelOffset(
            lockedY, 12, 30f, targetRow, fraction);
        float renderedAnchorY = 12f + (targetRow + fraction) * 30f - targetOffset;

        assertEquals(480f, TerminalPinchViewportAnchor.reportedFocusDrift(
            lockedX, lockedY, reportedX, reportedY), 0.0001f);
        assertEquals(lockedY, renderedAnchorY, 0.0001f);
        assertEquals(480f, Math.abs(reportedY - renderedAnchorY), 0.0001f);
    }

    @Test
    public void nativeIntegerAnchorKeepsFractionAtEveryInteriorHistoryPosition() {
        assertEquals(7f, TerminalPinchViewportAnchor.committedPixelOffset(
            -1, 2000, 24f, 7f), 0f);
        assertEquals(-7f, TerminalPinchViewportAnchor.committedPixelOffset(
            -1999, 2000, 24f, -7f), 0f);
    }

    @Test
    public void onlyUnrepresentableBoundaryFractionIsClamped() {
        assertEquals(0f, TerminalPinchViewportAnchor.committedPixelOffset(
            0, 2000, 24f, 7f), 0f);
        assertEquals(-7f, TerminalPinchViewportAnchor.committedPixelOffset(
            0, 2000, 24f, -7f), 0f);
        assertEquals(0f, TerminalPinchViewportAnchor.committedPixelOffset(
            -2000, 2000, 24f, -7f), 0f);
        assertEquals(7f, TerminalPinchViewportAnchor.committedPixelOffset(
            -2000, 2000, 24f, 7f), 0f);
    }

    @Test
    public void oversizedFractionCannotAliasAnotherIntegerViewportRow() {
        assertEquals(23.999f, TerminalPinchViewportAnchor.committedPixelOffset(
            -1000, 2000, 24f, 200f), 0.0001f);
        assertEquals(-23.999f, TerminalPinchViewportAnchor.committedPixelOffset(
            -1000, 2000, 24f, -200f), 0.0001f);
    }
}
