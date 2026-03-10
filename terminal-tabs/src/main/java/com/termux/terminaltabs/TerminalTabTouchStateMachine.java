package com.termux.terminaltabs;

import androidx.annotation.NonNull;

public final class TerminalTabTouchStateMachine {

    public enum State {
        IDLE,
        PRESSED,
        LONG_PRESS_TRIGGERED
    }

    public enum ReleaseAction {
        NONE,
        CLICK,
        CONSUME
    }

    private final float moveThresholdPx;
    private float downX;
    private float downY;
    @NonNull
    private State state = State.IDLE;

    public TerminalTabTouchStateMachine(float moveThresholdPx) {
        this.moveThresholdPx = Math.max(0f, moveThresholdPx);
    }

    public void onDown(float x, float y) {
        downX = x;
        downY = y;
        state = State.PRESSED;
    }

    public boolean onMove(float x, float y) {
        if (state != State.PRESSED) return false;
        if (Math.abs(x - downX) <= moveThresholdPx &&
            Math.abs(y - downY) <= moveThresholdPx) {
            return false;
        }

        state = State.IDLE;
        return true;
    }

    public boolean onLongPressTimeout() {
        if (state != State.PRESSED) return false;
        state = State.LONG_PRESS_TRIGGERED;
        return true;
    }

    @NonNull
    public ReleaseAction onUp() {
        if (state == State.PRESSED) {
            state = State.IDLE;
            return ReleaseAction.CLICK;
        }

        if (state == State.LONG_PRESS_TRIGGERED) {
            state = State.IDLE;
            return ReleaseAction.CONSUME;
        }

        state = State.IDLE;
        return ReleaseAction.NONE;
    }

    public void onCancel() {
        state = State.IDLE;
    }

    @NonNull
    public State getState() {
        return state;
    }
}
