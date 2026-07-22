package com.termux.app.terminal;

/**
 * Serializes asynchronous requests to show the terminal IME.
 *
 * A request is consumed only when the terminal view is ready for input. Newer
 * requests invalidate already-posted work for older requests, and cancellation
 * prevents lifecycle or session changes from showing the IME later.
 */
public final class TerminalImeRequestStateMachine {

    private long mNextToken = 1L;
    private long mPendingToken;

    public synchronized long requestShow() {
        long token = mNextToken++;
        if (token == 0L) token = mNextToken++;
        mPendingToken = token;
        return token;
    }

    public synchronized void cancelShow() {
        mPendingToken = 0L;
    }

    public synchronized long getPendingToken() {
        return mPendingToken;
    }

    public synchronized boolean consumeIfReady(long token, boolean attached, boolean visible,
                                               boolean windowFocused, boolean viewFocused,
                                               boolean hasWindowToken) {
        if (token == 0L || token != mPendingToken) return false;
        if (!attached || !visible || !windowFocused || !viewFocused || !hasWindowToken) return false;

        mPendingToken = 0L;
        return true;
    }
}
