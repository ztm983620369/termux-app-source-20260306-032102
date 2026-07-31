package com.termux.view;

/**
 * Keeps an IME focus transaction distinct from terminal-pixel changes.
 *
 * <p>An already-presented live frame can become newly relevant when the keyboard opens. In that
 * case the host still needs one camera callback even when its revision, cursor and viewport are
 * identical to the prior callback.</p>
 */
final class TerminalImeCameraFrameRequestPolicy {

    private long mRequestedGeneration;
    private long mNotifiedGeneration = Long.MIN_VALUE;

    void reset() {
        mRequestedGeneration = 0L;
        mNotifiedGeneration = Long.MIN_VALUE;
    }

    void request() {
        mRequestedGeneration++;
    }

    boolean shouldNotify(boolean frameIdentityChanged) {
        return frameIdentityChanged || mRequestedGeneration != mNotifiedGeneration;
    }

    void markNotified() {
        mNotifiedGeneration = mRequestedGeneration;
    }

    long getRequestedGeneration() {
        return mRequestedGeneration;
    }

    long getNotifiedGeneration() {
        return mNotifiedGeneration;
    }
}
