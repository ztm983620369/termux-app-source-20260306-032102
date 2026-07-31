package com.termux.terminalsessionsurface;

/**
 * Per-terminal visual camera that keeps the writable cursor visible around an Android IME.
 *
 * <p>The reducer owns presentation only. It never changes View measurement, terminal rows or
 * columns, PTY state, scrollback, or application output. The cursor rectangle is the generic
 * writable-focus signal. A frame-committed protected bottom may additionally represent a
 * full-screen TUI footer, but it can only tighten the camera upward. A renderer's dynamic
 * bottommost painted row must never release or re-anchor the camera.</p>
 */
final class TerminalImeFocusCamera {

    enum Availability {
        READY,
        FRAME_PENDING,
        HISTORY_OWNED
    }

    enum Phase {
        HIDDEN,
        WAITING_READY,
        TRACKING,
        HISTORY_OWNED
    }

    enum Cause {
        HIDDEN,
        FRAME_PENDING_HELD,
        HISTORY_HELD,
        INITIAL_FOCUS,
        GEOMETRY_CHANGED,
        CURSOR_TRACKED,
        STABLE
    }

    static final class Request {
        final boolean imeActive;
        final boolean animationRunning;
        final Availability availability;
        final int terminalTopInWindow;
        final int terminalHeight;
        final int boundaryInWindow;
        final int cursorTopPx;
        final int cursorBottomPx;
        final int protectedBottomPx;
        final float currentTranslationY;

        Request(boolean imeActive, boolean animationRunning, Availability availability,
                int terminalTopInWindow, int terminalHeight, int boundaryInWindow,
                int cursorTopPx, int cursorBottomPx, float currentTranslationY) {
            this(imeActive, animationRunning, availability, terminalTopInWindow,
                terminalHeight, boundaryInWindow, cursorTopPx, cursorBottomPx,
                cursorBottomPx, currentTranslationY);
        }

        Request(boolean imeActive, boolean animationRunning, Availability availability,
                int terminalTopInWindow, int terminalHeight, int boundaryInWindow,
                int cursorTopPx, int cursorBottomPx, int protectedBottomPx,
                float currentTranslationY) {
            this.imeActive = imeActive;
            this.animationRunning = animationRunning;
            this.availability = availability;
            this.terminalTopInWindow = terminalTopInWindow;
            this.terminalHeight = terminalHeight;
            this.boundaryInWindow = boundaryInWindow;
            this.cursorTopPx = cursorTopPx;
            this.cursorBottomPx = cursorBottomPx;
            this.protectedBottomPx = protectedBottomPx;
            this.currentTranslationY = currentTranslationY;
        }
    }

    static final class Decision {
        final int translationY;
        final int focusTargetBottomInWindow;
        final Phase phase;
        final Cause cause;

        Decision(int translationY, int focusTargetBottomInWindow, Phase phase, Cause cause) {
            this.translationY = translationY;
            this.focusTargetBottomInWindow = focusTargetBottomInWindow;
            this.phase = phase;
            this.cause = cause;
        }
    }

    private Phase mPhase = Phase.HIDDEN;
    private Cause mLastCause = Cause.HIDDEN;
    private boolean mExplicitLiveFocusRequested;
    private int mFocusTargetBottomInWindow = -1;
    private int mLastTerminalTopInWindow = Integer.MIN_VALUE;
    private int mLastTerminalHeight = -1;
    private int mLastBoundaryInWindow = Integer.MIN_VALUE;
    private int mLastTranslationY;
    private long mReanchors;
    private long mCursorTracks;
    private long mPendingFramesHeld;
    private long mHistoryHolds;

    void requestExplicitLiveFocus() {
        mExplicitLiveFocusRequested = true;
    }

    void resetForBinding() {
        resetState();
    }

    Decision update(Request request) {
        int currentTranslation = Math.round(request.currentTranslationY);
        if (!request.imeActive) {
            resetState();
            return decision(0, -1, Phase.HIDDEN, Cause.HIDDEN);
        }

        if (request.availability == Availability.HISTORY_OWNED) {
            // Explicit input is a transaction that only an authoritative live frame may consume.
            // A long jump from old scrollback can legitimately publish one or more intervening
            // history snapshots while Ghostty rebuilds the live viewport. Treating any of those
            // snapshots as a cancellation loses the request and leaves a plain shell behind the
            // IME. Hold the committed transform until READY proves that the live cursor exists.
            if (mExplicitLiveFocusRequested) {
                mPendingFramesHeld++;
                return decision(currentTranslation, mFocusTargetBottomInWindow,
                    Phase.WAITING_READY, Cause.FRAME_PENDING_HELD);
            }
            // Scrollback owns which rows are rendered, but it does not own IME occlusion. While
            // the keyboard and bottom chrome remain visible, dropping the already-committed pan
            // to zero lets history pixels run underneath both layers. Preserve the exact camera
            // transform across finger scroll, fling and long-press selection. Explicit input will
            // later replace it only after an authoritative live frame is presented.
            mHistoryHolds++;
            return decision(currentTranslation, mFocusTargetBottomInWindow,
                Phase.HISTORY_OWNED, Cause.HISTORY_HELD);
        }

        if (mPhase == Phase.HISTORY_OWNED && !mExplicitLiveFocusRequested) {
            mHistoryHolds++;
            return decision(currentTranslation, mFocusTargetBottomInWindow,
                Phase.HISTORY_OWNED, Cause.HISTORY_HELD);
        }

        if (request.availability == Availability.FRAME_PENDING || !hasUsableGeometry(request)) {
            mPendingFramesHeld++;
            return decision(currentTranslation, mFocusTargetBottomInWindow,
                Phase.WAITING_READY, Cause.FRAME_PENDING_HELD);
        }

        boolean firstFocus = mFocusTargetBottomInWindow < 0;
        boolean explicitFocus = mExplicitLiveFocusRequested;
        boolean geometryChanged = request.terminalTopInWindow != mLastTerminalTopInWindow ||
            request.terminalHeight != mLastTerminalHeight ||
            request.boundaryInWindow != mLastBoundaryInWindow;

        if (firstFocus || geometryChanged) {
            mFocusTargetBottomInWindow = computeFocusTargetBottom(request);
            mReanchors++;
        }
        mExplicitLiveFocusRequested = false;

        int protectedBottomPx = resolveProtectedBottomPx(request);
        int untransformedProtectedBottom = request.terminalTopInWindow + protectedBottomPx;
        int desiredTranslation = mFocusTargetBottomInWindow - untransformedProtectedBottom;
        int cursorVisibleTopLimit = -request.cursorTopPx;
        int targetTranslation = clamp(desiredTranslation,
            Math.max(-request.terminalHeight, cursorVisibleTopLimit), 0);

        // A newly committed full-screen footer or a cursor moving toward chrome may tighten the
        // camera immediately. Neither an output repaint nor a cursor moving upward may pull it
        // downward again while the IME still occludes the terminal. Only a real boundary change or
        // explicit user return to the live input edge may release a prior safety translation.
        if (!firstFocus && !geometryChanged && !explicitFocus) {
            targetTranslation = Math.min(currentTranslation, targetTranslation);
        }

        Cause cause;
        if (firstFocus) {
            cause = Cause.INITIAL_FOCUS;
        } else if (geometryChanged) {
            cause = Cause.GEOMETRY_CHANGED;
        } else if (targetTranslation != currentTranslation) {
            cause = Cause.CURSOR_TRACKED;
            mCursorTracks++;
        } else {
            cause = Cause.STABLE;
        }

        mLastTerminalTopInWindow = request.terminalTopInWindow;
        mLastTerminalHeight = request.terminalHeight;
        mLastBoundaryInWindow = request.boundaryInWindow;
        return decision(targetTranslation, mFocusTargetBottomInWindow,
            Phase.TRACKING, cause);
    }

    int getFocusTargetBottomInWindow() {
        return mFocusTargetBottomInWindow;
    }

    String getDiagnostics() {
        return "phase=" + mPhase + " cause=" + mLastCause + " target=" +
            mFocusTargetBottomInWindow + " translation=" + mLastTranslationY +
            " reanchors=" + mReanchors + " cursorTracks=" + mCursorTracks +
            " pendingHeld=" + mPendingFramesHeld + " historyHolds=" +
            mHistoryHolds;
    }

    private static boolean hasUsableGeometry(Request request) {
        int cursorHeight = request.cursorBottomPx - request.cursorTopPx;
        return request.terminalHeight > 0 && cursorHeight > 0 &&
            request.boundaryInWindow - request.terminalTopInWindow >= cursorHeight &&
            request.cursorTopPx >= 0 &&
            request.cursorBottomPx > request.cursorTopPx &&
            request.cursorBottomPx <= request.terminalHeight;
    }

    private static int computeFocusTargetBottom(Request request) {
        // The lower safe edge is the only target that both preserves maximum terminal context and
        // guarantees that translating a bottom cursor cannot expose an artificial gap below the
        // finite terminal pixel layer. If the cursor is already above it, the zero clamp leaves the
        // application's own layout untouched.
        return request.boundaryInWindow;
    }

    private static int resolveProtectedBottomPx(Request request) {
        if (request.protectedBottomPx >= request.cursorBottomPx &&
            request.protectedBottomPx <= request.terminalHeight) {
            return request.protectedBottomPx;
        }
        return request.cursorBottomPx;
    }

    private Decision decision(int translationY, int targetBottom, Phase phase, Cause cause) {
        mLastTranslationY = translationY;
        mFocusTargetBottomInWindow = targetBottom;
        mPhase = phase;
        mLastCause = cause;
        return new Decision(translationY, targetBottom, phase, cause);
    }

    private void resetState() {
        mPhase = Phase.HIDDEN;
        mLastCause = Cause.HIDDEN;
        mExplicitLiveFocusRequested = false;
        mFocusTargetBottomInWindow = -1;
        mLastTerminalTopInWindow = Integer.MIN_VALUE;
        mLastTerminalHeight = -1;
        mLastBoundaryInWindow = Integer.MIN_VALUE;
        mLastTranslationY = 0;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
