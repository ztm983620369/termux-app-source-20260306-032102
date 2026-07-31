package com.termux.terminalsessionsurface;

import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;

/** Tracks the terminal frames that can actually enter one page transition. */
public final class TerminalSessionTransitionFrameState {

    private final Set<String> preparedKeys = new HashSet<>(2);
    private int anchorPosition = -1;
    @Nullable private String targetKey;

    public void begin(int anchorPosition) {
        this.anchorPosition = anchorPosition;
        targetKey = null;
        preparedKeys.clear();
    }

    public boolean isActive() {
        return anchorPosition >= 0;
    }

    public void reanchor(int anchorPosition) {
        this.anchorPosition = anchorPosition;
        targetKey = null;
    }

    public int resolveGestureTarget(int pageDelta, int pageCount) {
        if (!isActive() || pageDelta == 0 || pageCount <= 0) return -1;
        int target = anchorPosition + (pageDelta < 0 ? -1 : 1);
        return target >= 0 && target < pageCount ? target : -1;
    }

    public boolean selectTarget(@Nullable String key) {
        boolean changed = targetKey == null ? key != null : !targetKey.equals(key);
        targetKey = key;
        return changed;
    }

    public boolean markPrepared(@Nullable String key) {
        return isTarget(key) && preparedKeys.add(key);
    }

    /** A revisited transition target must be refreshed when output made its retained frame stale. */
    public static boolean shouldPrepareTarget(boolean firstPreparation, boolean completeFrame,
                                              boolean dirtyFrame) {
        return firstPreparation || !completeFrame || dirtyFrame;
    }

    public boolean isTarget(@Nullable String key) {
        return key != null && key.equals(targetKey);
    }

    @Nullable
    public String getTargetKey() {
        return targetKey;
    }

    public int getAnchorPosition() {
        return anchorPosition;
    }

    public void finish() {
        anchorPosition = -1;
        targetKey = null;
        preparedKeys.clear();
    }

    public static int pageDeltaForDrag(float downX, float currentX) {
        if (currentX < downX) return 1;
        if (currentX > downX) return -1;
        return 0;
    }
}
