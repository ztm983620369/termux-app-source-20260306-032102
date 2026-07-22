package com.termux.terminal;

import androidx.annotation.NonNull;

import java.util.Arrays;

/**
 * Immutable text snapshot plus the active selection offsets within that snapshot.
 */
public final class TerminalSelectionContext {

    private final String mText;
    private final int mSelectionStart;
    private final int mSelectionEnd;
    private final int[] mHardWrapHintOffsets;
    private final boolean mRequiresConfirmation;

    public TerminalSelectionContext(@NonNull String text, int selectionStart, int selectionEnd) {
        this(text, selectionStart, selectionEnd, new int[0]);
    }

    public TerminalSelectionContext(@NonNull String text, int selectionStart, int selectionEnd,
                                    @NonNull int[] hardWrapHintOffsets) {
        this(text, selectionStart, selectionEnd, hardWrapHintOffsets, false);
    }

    public TerminalSelectionContext(@NonNull String text, int selectionStart, int selectionEnd,
                                    @NonNull int[] hardWrapHintOffsets,
                                    boolean requiresConfirmation) {
        if (text == null) throw new IllegalArgumentException("text == null");
        if (selectionStart < 0 || selectionEnd < selectionStart || selectionEnd > text.length()) {
            throw new IllegalArgumentException("Invalid selection: start=" + selectionStart +
                ", end=" + selectionEnd + ", textLength=" + text.length());
        }

        mText = text;
        mSelectionStart = selectionStart;
        mSelectionEnd = selectionEnd;
        mHardWrapHintOffsets = sanitizeHintOffsets(hardWrapHintOffsets, text.length());
        mRequiresConfirmation = requiresConfirmation;
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
    public int[] getHardWrapHintOffsets() {
        return Arrays.copyOf(mHardWrapHintOffsets, mHardWrapHintOffsets.length);
    }

    public boolean requiresConfirmation() {
        return mRequiresConfirmation;
    }

    @NonNull
    public TerminalSelectionContext withSelection(int selectionStart, int selectionEnd) {
        return new TerminalSelectionContext(
            mText, selectionStart, selectionEnd, mHardWrapHintOffsets, mRequiresConfirmation);
    }

    private static int[] sanitizeHintOffsets(int[] offsets, int textLength) {
        if (offsets == null || offsets.length == 0) return new int[0];
        int[] copy = Arrays.copyOf(offsets, offsets.length);
        Arrays.sort(copy);
        int count = 0;
        int previous = -1;
        for (int offset : copy) {
            if (offset < 0 || offset >= textLength || offset == previous) continue;
            copy[count++] = offset;
            previous = offset;
        }
        return Arrays.copyOf(copy, count);
    }
}
