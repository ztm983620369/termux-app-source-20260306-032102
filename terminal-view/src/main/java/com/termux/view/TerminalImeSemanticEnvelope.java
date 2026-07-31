package com.termux.view;

/**
 * Resolves which presented terminal row may participate in IME occlusion avoidance.
 *
 * <p>An ordinary primary screen is an append-only shell surface. Text, colours and decorations
 * below the cursor are not enough to prove a footer and must not move a sparse prompt offscreen.
 * An alternate screen or a protocol-identified inline TUI owns a composed screen, so rows below
 * its cursor may be a status line, help line or input footer that belongs with the focused field.</p>
 */
final class TerminalImeSemanticEnvelope {

    private TerminalImeSemanticEnvelope() {}

    static int resolveProtectedBottomScreenRow(boolean alternateScreen,
                                                boolean inlinePrimaryScreen,
                                                int cursorScreenRow,
                                                int semanticTailScreenRow,
                                                int screenRows) {
        if (cursorScreenRow < 0 || cursorScreenRow >= screenRows) return cursorScreenRow;
        if (!alternateScreen && !inlinePrimaryScreen) return cursorScreenRow;
        if (semanticTailScreenRow < cursorScreenRow || semanticTailScreenRow >= screenRows) {
            return cursorScreenRow;
        }
        return semanticTailScreenRow;
    }
}
