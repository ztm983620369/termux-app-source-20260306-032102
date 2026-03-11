package com.termux.terminalsessionsurface;

import androidx.annotation.NonNull;

public final class TerminalSessionSurfacePagerStateMachine {

    public enum State {
        IDLE,
        DRAGGING,
        SETTLING
    }

    @NonNull
    private State state = State.IDLE;

    public void onDragStarted() {
        state = State.DRAGGING;
    }

    public void onSettlingStarted() {
        state = State.SETTLING;
    }

    public void onIdle() {
        state = State.IDLE;
    }

    @NonNull
    public State getState() {
        return state;
    }
}
