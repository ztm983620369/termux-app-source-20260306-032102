package com.termux.terminal;

import androidx.annotation.NonNull;

/**
 * Immutable text snapshot plus the active selection offsets within that snapshot.
 */
public final class TerminalSelectionContext {

    private final String mText;
    private final int mSelectionStart;
    private final int mSelectionEnd;

    public TerminalSelectionContext(@NonNull String text, int selectionStart, int selectionEnd) {
        if (text == null) throw new IllegalArgumentException("text == null");
        if (selectionStart < 0 || selectionEnd < selectionStart || selectionEnd > text.length()) {
            throw new IllegalArgumentException("Invalid selection: start=" + selectionStart +
                ", end=" + selectionEnd + ", textLength=" + text.length());
        }

        mText = text;
        mSelectionStart = selectionStart;
        mSelectionEnd = selectionEnd;
    }

    @NonNull
    public String getText() {
        return mText;
    }

    public int getSelectionStart() {
        return mSelectionStart;
    }

    public int getSelectionEnd() {
        return mSelectionEnd;
    }

    @NonNull
    public String getSelectedText() {
        return mText.substring(mSelectionStart, mSelectionEnd);
    }

    public boolean isEmpty() {
        return mText.isEmpty() || mSelectionStart == mSelectionEnd;
    }

    @NonNull
    public TerminalSelectionContext withSelection(int selectionStart, int selectionEnd) {
        return new TerminalSelectionContext(mText, selectionStart, selectionEnd);
    }
}
