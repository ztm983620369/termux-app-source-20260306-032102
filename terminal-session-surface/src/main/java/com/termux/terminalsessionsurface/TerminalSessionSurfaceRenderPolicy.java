package com.termux.terminalsessionsurface;

import androidx.annotation.NonNull;

/** Limits terminal rendering to pages that can contribute pixels to the current transition. */
public final class TerminalSessionSurfaceRenderPolicy {

    private TerminalSessionSurfaceRenderPolicy() {
    }

    public static boolean shouldRender(int pagePosition,
                                       int currentPosition,
                                       @NonNull TerminalSessionSurfacePagerStateMachine.State pagerState,
                                       int transitionTargetPosition) {
        if (pagePosition < 0 || currentPosition < 0) return false;
        if (pagerState == TerminalSessionSurfacePagerStateMachine.State.IDLE) {
            return pagePosition == currentPosition;
        }
        return pagePosition == currentPosition || pagePosition == transitionTargetPosition;
    }

    public static boolean shouldAnimateProgrammaticTransition(int currentPosition,
                                                               int targetPosition,
                                                               boolean requested) {
        // A tab press is a selection commit, not a swipe. ViewPager's long settle animation can
        // expose a target before its terminal composition is committed, which is observable as a
        // black or partially composed page. Native finger swipes still use ViewPager animation.
        return false;
    }
}
