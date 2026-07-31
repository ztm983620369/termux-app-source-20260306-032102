package com.termux.view;

/** Absolute touch-to-viewport mapping used to avoid accumulated scroll drift. */
final class TerminalFingerScrollTracker {

    private boolean active;
    private boolean dragging;
    private int pointerId = -1;
    private float downY;
    private float startViewport;
    private float lastTarget;

    void start(int pointerId, float y, float viewport) {
        active = true;
        dragging = false;
        this.pointerId = pointerId;
        downY = y;
        startViewport = viewport;
        lastTarget = viewport;
    }

    float update(float y, float captureSlop) {
        if (!active) return Float.NaN;
        float distance = downY - y;
        if (!dragging && Math.abs(distance) < Math.max(0f, captureSlop)) {
            return Float.NaN;
        }
        dragging = true;
        lastTarget = startViewport + distance;
        return lastTarget;
    }

    /** Keep the absolute touch mapping aligned when retained history shifts under the finger. */
    void rebase(float viewportDelta) {
        if (!active || viewportDelta == 0f) return;
        startViewport += viewportDelta;
        lastTarget += viewportDelta;
    }

    void cancel() {
        active = false;
        dragging = false;
        pointerId = -1;
    }

    boolean isActive() {
        return active;
    }

    boolean isDragging() {
        return dragging;
    }

    int getPointerId() {
        return pointerId;
    }

    float getStartViewport() {
        return startViewport;
    }

    float getLastTarget() {
        return lastTarget;
    }
}
