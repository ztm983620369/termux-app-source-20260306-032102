package com.termux.terminalsessioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class TerminalTopBarStateMachine {

    public enum TitleState {
        PINNED_DISPLAY_NAME,
        SESSION_NAME,
        TERMINAL_TITLE,
        FALLBACK
    }

    public static final class Snapshot {
        @NonNull public final String title;
        public final boolean locked;
        @NonNull public final TitleState state;

        public Snapshot(@NonNull String title, boolean locked, @NonNull TitleState state) {
            this.title = title;
            this.locked = locked;
            this.state = state;
        }
    }

    private TerminalTopBarStateMachine() {
    }

    @NonNull
    public static Snapshot resolveTitle(boolean pinned,
                                        @Nullable String pinnedDisplayName,
                                        @Nullable String sessionName,
                                        @Nullable String terminalTitle) {
        String pinnedTitle = SshTmuxSessionStateMachine.normalizeDisplayName(pinnedDisplayName, null);
        if (pinned && !pinnedTitle.isEmpty() && !"tmux".equals(pinnedTitle)) {
            return new Snapshot(pinnedTitle, true, TitleState.PINNED_DISPLAY_NAME);
        }

        String normalizedSessionName = SshTmuxSessionStateMachine.normalizeDisplayName(sessionName, null);
        if (!normalizedSessionName.isEmpty() &&
            !"tmux".equals(normalizedSessionName) &&
            !SshTmuxSessionStateMachine.looksLikeOpaqueInternalName(normalizedSessionName)) {
            return new Snapshot(normalizedSessionName, pinned, TitleState.SESSION_NAME);
        }

        String normalizedTerminalTitle = SshTmuxSessionStateMachine.normalizeDisplayName(terminalTitle, null);
        if (!normalizedTerminalTitle.isEmpty()) {
            return new Snapshot(normalizedTerminalTitle, pinned, TitleState.TERMINAL_TITLE);
        }

        return new Snapshot("terminal", pinned, TitleState.FALLBACK);
    }
}
