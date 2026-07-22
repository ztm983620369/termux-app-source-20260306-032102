package com.termux.terminalsessionsurface;

import androidx.annotation.NonNull;

/** Limits terminal rendering to pages that can contribute pixels to the current transition. */
public final class TerminalSessionSurfaceRenderPolicy {

    private TerminalSessionSurfaceRenderPolicy() {
    }

    public static boolean shouldRender(int pagePosition,
                                       int currentPosition,
                                       @NonNull TerminalSessionSurfacePagerStateMachine.State pagerState) {
        if (pagePosition < 0 || currentPosition < 0) return false;
        if (pagerState == TerminalSessionSurfacePagerStateMachine.State.IDLE) {
            return pagePosition == currentPosition;
        }
        return Math.abs(pagePosition - currentPosition) <= 1;
    }
}
