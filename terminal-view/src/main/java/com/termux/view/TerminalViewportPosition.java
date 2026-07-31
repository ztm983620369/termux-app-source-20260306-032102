package com.termux.view;

/**
 * Represents a continuous transcript viewport without allocating on each touch move.
 *
 * <p>Moving toward history uses a positive bottom overscan offset. Moving toward the live
 * edge uses a negative top overscan offset. Both forms keep the newly exposed partial row in
 * the retained cache from the preceding frame.</p>
 */
final class TerminalViewportPosition {

    private static final float EPSILON_PX = 0.01f;

    static final class Result {
        int topRow;
        float pixelOffset;
    }

    private TerminalViewportPosition() {
    }

    static void resolve(float requestedPosition, int transcriptRows, float lineHeight,
                        float previousPosition, int currentTopRow, float currentPixelOffset,
                        Result result) {
        float safeLineHeight = Math.max(1f, lineHeight);
        int safeTranscriptRows = Math.max(0, transcriptRows);
        float minimum = -safeTranscriptRows * safeLineHeight;
        float clamped = Math.max(minimum, Math.min(0f, requestedPosition));
        if (clamped >= -EPSILON_PX) {
            result.topRow = 0;
            result.pixelOffset = 0f;
            return;
        }

        float represented = currentTopRow * safeLineHeight + currentPixelOffset;
        if (Math.abs(represented - clamped) < EPSILON_PX) {
            result.topRow = currentTopRow;
            result.pixelOffset = currentPixelOffset;
            return;
        }

        boolean movingTowardLiveEdge = clamped > previousPosition + EPSILON_PX;
        int topRow = movingTowardLiveEdge
            ? (int) Math.ceil(clamped / safeLineHeight)
            : (int) Math.floor(clamped / safeLineHeight);
        topRow = Math.min(0, Math.max(-safeTranscriptRows, topRow));
        float offset = clamped - topRow * safeLineHeight;
        if (Math.abs(offset) < EPSILON_PX) offset = 0f;

        result.topRow = topRow;
        result.pixelOffset = offset;
    }
}
