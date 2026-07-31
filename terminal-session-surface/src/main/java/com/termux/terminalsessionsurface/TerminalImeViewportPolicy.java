package com.termux.terminalsessionsurface;

/** Pure geometry for presenting a stable terminal grid around an Android IME. */
public final class TerminalImeViewportPolicy {

    private TerminalImeViewportPolicy() {}

    public static boolean shouldLockTerminalGeometry(int imeBottomInset,
                                                     boolean animationRunning) {
        return imeBottomInset > 0 || animationRunning;
    }

    /** Returns the IME top in window coordinates without inventing any extra chrome height. */
    public static int computeImeTopInWindow(int windowBottomInWindow, int imeBottomInset) {
        if (windowBottomInWindow <= 0 || imeBottomInset <= 0) return windowBottomInWindow;
        return windowBottomInWindow - Math.min(windowBottomInWindow, imeBottomInset);
    }

    /**
     * Moves a bottom-anchored chrome view just far enough to end at {@code boundaryInWindow}.
     *
     * <p>The result is never positive: an IME must not push chrome downward. Flooring a negative
     * fractional delta keeps the chrome entirely above the boundary instead of leaving a one-pixel
     * overlap.</p>
     */
    public static int computeAnchoredChromeTranslation(float untransformedBottomInWindow,
                                                         int boundaryInWindow,
                                                         boolean imeActive) {
        if (!imeActive || boundaryInWindow <= 0) return 0;
        int translation = (int) Math.floor(boundaryInWindow - untransformedBottomInWindow);
        return Math.min(0, translation);
    }

    /**
     * Pans terminal pixels only far enough to keep the cursor row above bottom chrome.
     *
     * <p>A sparse shell can have its cursor near the top of a tall grid. Anchoring the terminal
     * bottom would throw that prompt out of the visible clip, so the cursor is the semantic input
     * anchor. This result is presentation-only and must never be fed into measurement or PTY
     * geometry.</p>
     */
    public static int computeCursorPanTranslation(float untransformedCursorBottomInWindow,
                                                  int boundaryInWindow,
                                                  int terminalHeight,
                                                  boolean imeActive) {
        if (!imeActive || boundaryInWindow <= 0 || terminalHeight <= 0 ||
            untransformedCursorBottomInWindow <= 0f) {
            return 0;
        }
        int translation = (int) Math.floor(
            boundaryInWindow - untransformedCursorBottomInWindow);
        return Math.max(-terminalHeight, Math.min(0, translation));
    }

    /**
     * Returns the portion of the untransformed terminal surface covered by the IME.
     *
     * The returned value is a visual translation only. It must never be fed back into Android
     * measurement or terminal rows/columns. Rounding upward guarantees that a fractional display
     * coordinate cannot leave a one-pixel strip hidden below the keyboard.
     */
    public static int computeOccludedHeight(float untransformedSurfaceBottomInWindow,
                                            int surfaceHeight, int windowBottomInWindow,
                                            int imeBottomInset) {
        if (surfaceHeight <= 0 || windowBottomInWindow <= 0 || imeBottomInset <= 0) return 0;
        int boundedImeInset = Math.min(windowBottomInWindow, imeBottomInset);
        float imeTopInWindow = windowBottomInWindow - boundedImeInset;
        int overlap = (int) Math.ceil(untransformedSurfaceBottomInWindow - imeTopInWindow);
        return Math.max(0, Math.min(surfaceHeight, overlap));
    }
}
