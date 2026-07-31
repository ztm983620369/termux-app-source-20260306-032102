package com.termux.view;

/** Allocation-free geometry for keeping the terminal cell under a pinch focus stable. */
final class TerminalPinchViewportAnchor {

    private static final float PIXEL_EPSILON = 0.001f;

    private TerminalPinchViewportAnchor() {
    }

    static int cellColumn(float focusX, float cellWidth, int columns) {
        int safeColumns = Math.max(1, columns);
        int column = (int) Math.floor(Math.max(0f, focusX) / Math.max(1f, cellWidth));
        return Math.max(0, Math.min(safeColumns - 1, column));
    }

    static float continuousViewportRow(float focusY, int contentTop, float lineHeight,
                                       float pixelOffset) {
        return (focusY - contentTop + pixelOffset) / Math.max(1f, lineHeight);
    }

    static int cellRow(float continuousRow, int rows) {
        int safeRows = Math.max(1, rows);
        int row = (int) Math.floor(continuousRow);
        return Math.max(0, Math.min(safeRows - 1, row));
    }

    /**
     * Row containing the focal cell, including the single overscan row that smooth scrolling may
     * expose above or below Ghostty's integer viewport. Native converts these two sentinel rows to
     * the full-screen coordinate space before tracking them.
     */
    static int trackedCellRow(float continuousRow, int rows) {
        int safeRows = Math.max(1, rows);
        int row = (int) Math.floor(continuousRow);
        return Math.max(-1, Math.min(safeRows, row));
    }

    static float cellFraction(float continuousRow, int cellRow) {
        return Math.max(0f, Math.min(0.999999f, continuousRow - cellRow));
    }

    static int targetCellRow(float focusY, int contentTop, float lineHeight, int rows) {
        return cellRow(continuousViewportRow(focusY, contentTop, lineHeight, 0f), rows);
    }

    static float targetPixelOffset(float focusY, int contentTop, float lineHeight,
                                   int targetCellRow, float cellFraction) {
        return contentTop + (targetCellRow + cellFraction) * Math.max(1f, lineHeight) - focusY;
    }

    static float reportedFocusDrift(float lockedX, float lockedY,
                                    float reportedX, float reportedY) {
        return (float) Math.hypot(reportedX - lockedX, reportedY - lockedY);
    }

    /**
     * Keep the native integer viewport row authoritative while applying the fractional-cell
     * component of a pinch anchor. Only the two physical transcript boundaries may remove the
     * fraction; normalizing it into a neighbouring top row would overwrite the native tracked-cell
     * transaction on the next render packet.
     */
    static float committedPixelOffset(int topRow, int transcriptRows, float lineHeight,
                                      float requestedOffset) {
        float maximumMagnitude = Math.max(0f, Math.max(1f, lineHeight) - PIXEL_EPSILON);
        float offset = Math.max(-maximumMagnitude,
            Math.min(maximumMagnitude, requestedOffset));
        int oldestTopRow = -Math.max(0, transcriptRows);
        if ((topRow >= 0 && offset > 0f) ||
            (topRow <= oldestTopRow && offset < 0f)) {
            return 0f;
        }
        return Math.abs(offset) < PIXEL_EPSILON ? 0f : offset;
    }
}
