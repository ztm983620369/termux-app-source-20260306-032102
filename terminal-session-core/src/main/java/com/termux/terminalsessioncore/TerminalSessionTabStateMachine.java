package com.termux.terminalsessioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class TerminalSessionTabStateMachine {

    public enum TransportKind {
        LOCAL,
        SSH,
        SSH_PERSIST
    }

    public enum RuntimeState {
        IDLE,
        BUSY,
        RETRY_SCHEDULED,
        FAILED
    }

    public enum LifecycleState {
        RUNNING,
        EXITED_SUCCESS,
        EXITED_ERROR
    }

    public enum AccentTone {
        NEUTRAL,
        ACTIVE,
        REMOTE,
        PERSISTENT,
        BUSY,
        SUCCESS,
        ERROR
    }

    public enum BadgeKind {
        NONE,
        SSH,
        PIN,
        BUSY,
        RETRY,
        DONE,
        ERROR
    }

    public static final class Input {
        public final boolean selected;
        public final boolean pinned;
        public final boolean running;
        public final int exitStatus;
        @Nullable public final String pinnedDisplayName;
        @Nullable public final String sessionName;
        @Nullable public final String terminalTitle;
        @NonNull public final TransportKind transportKind;
        @NonNull public final RuntimeState runtimeState;

        public Input(boolean selected,
                     boolean pinned,
                     boolean running,
                     int exitStatus,
                     @Nullable String pinnedDisplayName,
                     @Nullable String sessionName,
                     @Nullable String terminalTitle,
                     @NonNull TransportKind transportKind,
                     @NonNull RuntimeState runtimeState) {
            this.selected = selected;
            this.pinned = pinned;
            this.running = running;
            this.exitStatus = exitStatus;
            this.pinnedDisplayName = pinnedDisplayName;
            this.sessionName = sessionName;
            this.terminalTitle = terminalTitle;
            this.transportKind = transportKind;
            this.runtimeState = runtimeState;
        }
    }

    public static final class Snapshot {
        @NonNull public final String title;
        @NonNull public final TerminalTopBarStateMachine.TitleState titleState;
        @NonNull public final TransportKind transportKind;
        @NonNull public final RuntimeState runtimeState;
        @NonNull public final LifecycleState lifecycleState;
        @NonNull public final AccentTone accentTone;
        @NonNull public final BadgeKind badgeKind;
        public final boolean selected;
        public final boolean locked;
        public final boolean closable;

        public Snapshot(@NonNull String title,
                        @NonNull TerminalTopBarStateMachine.TitleState titleState,
                        @NonNull TransportKind transportKind,
                        @NonNull RuntimeState runtimeState,
                        @NonNull LifecycleState lifecycleState,
                        @NonNull AccentTone accentTone,
                        @NonNull BadgeKind badgeKind,
                        boolean selected,
                        boolean locked,
                        boolean closable) {
            this.title = title;
            this.titleState = titleState;
            this.transportKind = transportKind;
            this.runtimeState = runtimeState;
            this.lifecycleState = lifecycleState;
            this.accentTone = accentTone;
            this.badgeKind = badgeKind;
            this.selected = selected;
            this.locked = locked;
            this.closable = closable;
        }
    }

    private TerminalSessionTabStateMachine() {
    }

    @NonNull
    public static Snapshot resolve(@NonNull Input input) {
        TerminalTopBarStateMachine.Snapshot titleSnapshot = TerminalTopBarStateMachine.resolveTitle(
            input.pinned, input.pinnedDisplayName, input.sessionName, input.terminalTitle);

        LifecycleState lifecycleState;
        if (input.running) {
            lifecycleState = LifecycleState.RUNNING;
        } else if (input.exitStatus == 0) {
            lifecycleState = LifecycleState.EXITED_SUCCESS;
        } else {
            lifecycleState = LifecycleState.EXITED_ERROR;
        }

        AccentTone accentTone = AccentTone.NEUTRAL;
        BadgeKind badgeKind = BadgeKind.NONE;

        if (input.runtimeState == RuntimeState.FAILED) {
            accentTone = AccentTone.ERROR;
            badgeKind = BadgeKind.ERROR;
        } else if (input.runtimeState == RuntimeState.RETRY_SCHEDULED) {
            accentTone = AccentTone.BUSY;
            badgeKind = BadgeKind.RETRY;
        } else if (input.runtimeState == RuntimeState.BUSY) {
            accentTone = AccentTone.BUSY;
            badgeKind = BadgeKind.BUSY;
        } else if (lifecycleState == LifecycleState.EXITED_ERROR) {
            accentTone = AccentTone.ERROR;
            badgeKind = BadgeKind.ERROR;
        } else if (lifecycleState == LifecycleState.EXITED_SUCCESS) {
            accentTone = AccentTone.SUCCESS;
            badgeKind = BadgeKind.DONE;
        } else if (input.transportKind == TransportKind.SSH_PERSIST || input.pinned) {
            accentTone = AccentTone.PERSISTENT;
            badgeKind = BadgeKind.PIN;
        } else if (input.transportKind == TransportKind.SSH) {
            accentTone = AccentTone.REMOTE;
            badgeKind = BadgeKind.SSH;
        } else if (input.selected) {
            accentTone = AccentTone.ACTIVE;
        }

        return new Snapshot(
            titleSnapshot.title,
            titleSnapshot.state,
            input.transportKind,
            input.runtimeState,
            lifecycleState,
            accentTone,
            badgeKind,
            input.selected,
            input.pinned,
            !input.pinned
        );
    }
}
