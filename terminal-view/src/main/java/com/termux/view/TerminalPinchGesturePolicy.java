package com.termux.view;

/** Rejects short or interrupted multi-touch sequences before they can persist terminal zoom. */
final class TerminalPinchGesturePolicy {
    static final int MIN_SCALE_SAMPLES = 2;
    static final long MIN_SCALE_DURATION_MILLIS = 24L;
    static final float MIN_SCALE_FACTOR = 0.90f;
    static final float MAX_SCALE_FACTOR = 1.10f;

    private TerminalPinchGesturePolicy() {
    }

    static boolean qualifies(float cumulativeFactor, int samples, long elapsedMillis) {
        return Float.isFinite(cumulativeFactor) && cumulativeFactor > 0f &&
            samples >= MIN_SCALE_SAMPLES && elapsedMillis >= MIN_SCALE_DURATION_MILLIS &&
            (cumulativeFactor <= MIN_SCALE_FACTOR || cumulativeFactor >= MAX_SCALE_FACTOR);
    }

    static boolean shouldCommit(boolean qualified, boolean cancelled,
                                int startTextSize, int targetTextSize) {
        return qualified && !cancelled && startTextSize > 0 &&
            targetTextSize > 0 && startTextSize != targetTextSize;
    }
}
