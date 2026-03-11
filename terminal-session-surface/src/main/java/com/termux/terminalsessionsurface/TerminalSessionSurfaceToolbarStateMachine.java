package com.termux.terminalsessionsurface;

import androidx.annotation.NonNull;

public final class TerminalSessionSurfaceToolbarStateMachine {

    public enum Page {
        EXTRA_KEYS,
        TEXT_INPUT
    }

    private boolean textInputEnabled;
    private Page page = Page.EXTRA_KEYS;

    public void setTextInputEnabled(boolean textInputEnabled) {
        this.textInputEnabled = textInputEnabled;
        if (!textInputEnabled && page == Page.TEXT_INPUT) {
            page = Page.EXTRA_KEYS;
        }
    }

    public void onPageSelected(int position) {
        if (position == 1 && textInputEnabled) {
            page = Page.TEXT_INPUT;
        } else {
            page = Page.EXTRA_KEYS;
        }
    }

    public void setPage(@NonNull Page page) {
        if (page == Page.TEXT_INPUT && !textInputEnabled) {
            this.page = Page.EXTRA_KEYS;
            return;
        }
        this.page = page;
    }

    public boolean isTextInputEnabled() {
        return textInputEnabled;
    }

    public boolean isTerminalPageSelected() {
        return page == Page.EXTRA_KEYS;
    }

    public boolean isTextInputPageSelected() {
        return page == Page.TEXT_INPUT;
    }
}
