package com.termux.app.editor;

import androidx.annotation.NonNull;

public final class EditorTerminalWorkspaceStateMachine {

    public enum State {
        CODE,
        TERMINAL_WORKSPACE
    }

    private State mState = State.CODE;

    @NonNull
    public State getState() {
        return mState;
    }

    public boolean isTerminalWorkspaceVisible() {
        return mState == State.TERMINAL_WORKSPACE;
    }

    public boolean openTerminalWorkspace() {
        if (mState == State.TERMINAL_WORKSPACE) return false;
        mState = State.TERMINAL_WORKSPACE;
        return true;
    }

    public boolean closeTerminalWorkspace() {
        if (mState == State.CODE) return false;
        mState = State.CODE;
        return true;
    }

    public boolean onBackPressed() {
        return closeTerminalWorkspace();
    }
}
