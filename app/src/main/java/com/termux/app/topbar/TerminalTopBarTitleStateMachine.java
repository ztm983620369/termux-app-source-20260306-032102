package com.termux.app.topbar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class TerminalTopBarTitleStateMachine {

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

    private TerminalTopBarTitleStateMachine() {
    }

    @NonNull
    public static Snapshot resolveTitle(boolean pinned,
                                        @Nullable String pinnedDisplayName,
                                        @Nullable String sessionName,
                                        @Nullable String terminalTitle) {
        String pinnedTitle = normalizeDisplayName(pinnedDisplayName, null);
        if (pinned && !pinnedTitle.isEmpty() && !"tmux".equals(pinnedTitle)) {
            return new Snapshot(pinnedTitle, true, TitleState.PINNED_DISPLAY_NAME);
        }

        String normalizedSessionName = normalizeDisplayName(sessionName, null);
        if (!normalizedSessionName.isEmpty() &&
            !"tmux".equals(normalizedSessionName) &&
            !looksLikeOpaqueInternalName(normalizedSessionName)) {
            return new Snapshot(normalizedSessionName, pinned, TitleState.SESSION_NAME);
        }

        String normalizedTerminalTitle = normalizeDisplayName(terminalTitle, null);
        if (!normalizedTerminalTitle.isEmpty()) {
            return new Snapshot(normalizedTerminalTitle, pinned, TitleState.TERMINAL_TITLE);
        }

        return new Snapshot("terminal", pinned, TitleState.FALLBACK);
    }

    @NonNull
    private static String normalizeDisplayName(@Nullable String raw, @Nullable String fallback) {
        String display = normalizeHumanName(raw);
        if (!display.isEmpty()) return display;
        return normalizeHumanName(fallback);
    }

    private static boolean looksLikeOpaqueInternalName(@Nullable String value) {
        String normalized = normalizeHumanName(value);
        return normalized.startsWith("ssh-persistent-") ||
            normalized.startsWith("termux-persist-v2-") ||
            normalized.startsWith("termux-persist-");
    }

    @NonNull
    private static String normalizeHumanName(@Nullable String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        boolean previousSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == 0) continue;
            if (Character.isISOControl(ch) || Character.isWhitespace(ch)) {
                if (!previousSpace && sb.length() > 0) {
                    sb.append(' ');
                    previousSpace = true;
                }
            } else {
                sb.append(ch);
                previousSpace = false;
            }
        }
        return sb.toString().trim();
    }
}
