package com.termux.terminalsessionsurface;

import androidx.annotation.NonNull;

/** Exact-once lifecycle for a touch stream seen by both intercept and touch dispatch. */
public final class TerminalSessionSwipeGestureStateMachine {

    public static final int SIGNAL_NONE = 0;
    public static final int SIGNAL_TOUCH_DOWN = 1;
    public static final int SIGNAL_CAPTURED = 1 << 1;
    public static final int SIGNAL_FINISHED = 1 << 2;

    public enum State {
        IDLE,
        IGNORED,
        TRACKING,
        CAPTURED
    }

    @NonNull private State state = State.IDLE;
    private long gestureId = Long.MIN_VALUE;

    public int onDown(long newGestureId, boolean eligible) {
        if (gestureId == newGestureId && state != State.IDLE) return SIGNAL_NONE;

        int signals = isActive() ? SIGNAL_FINISHED : SIGNAL_NONE;
        gestureId = newGestureId;
        state = eligible ? State.TRACKING : State.IGNORED;
        return eligible ? signals | SIGNAL_TOUCH_DOWN : signals;
    }

    public int onCaptured(long currentGestureId) {
        if (gestureId != currentGestureId || state != State.TRACKING) return SIGNAL_NONE;
        state = State.CAPTURED;
        return SIGNAL_CAPTURED;
    }

    public int onFinished(long currentGestureId) {
        if (gestureId != currentGestureId || state == State.IDLE) return SIGNAL_NONE;
        boolean notify = isActive();
        state = State.IDLE;
        gestureId = Long.MIN_VALUE;
        return notify ? SIGNAL_FINISHED : SIGNAL_NONE;
    }

    public boolean isEligible(long currentGestureId) {
        return gestureId == currentGestureId && isActive();
    }

    @NonNull
    public State getState() {
        return state;
    }

    private boolean isActive() {
        return state == State.TRACKING || state == State.CAPTURED;
    }
}
