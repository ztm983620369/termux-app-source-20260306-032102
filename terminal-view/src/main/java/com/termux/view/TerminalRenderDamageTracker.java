package com.termux.view;

/** Allocation-free union of native row damage produced while preparing one Android frame. */
final class TerminalRenderDamageTracker {

    private boolean active;
    private boolean full;
    private int start = Integer.MAX_VALUE;
    private int end = Integer.MIN_VALUE;

    void begin() {
        active = true;
        full = false;
        start = Integer.MAX_VALUE;
        end = Integer.MIN_VALUE;
    }

    void markFull() {
        if (active) full = true;
    }

    void markRow(int row) {
        if (!active || full || row < 0) return;
        start = Math.min(start, row);
        end = Math.max(end, row + 1);
    }

    void finish(boolean success) {
        active = false;
        if (!success) full = true;
    }

    boolean isFull() {
        return full;
    }

    int start() {
        return start;
    }

    int end() {
        return end;
    }
}
