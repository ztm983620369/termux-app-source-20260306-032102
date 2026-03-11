package com.termux.app.topbar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class TerminalTopBarStateMachine {

    public enum TransportKind {
        LOCAL,
        SSH,
        SSH_PERSIST
    }

    public enum LifecycleState {
        RUNNING,
        EXITED_SUCCESS,
        EXITED_ERROR
    }

    public enum Tone {
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
        @NonNull public final TerminalTopBarRuntimeState runtimeState;

        public Input(boolean selected,
                     boolean pinned,
                     boolean running,
                     int exitStatus,
                     @Nullable String pinnedDisplayName,
                     @Nullable String sessionName,
                     @Nullable String terminalTitle,
                     @NonNull TransportKind transportKind,
                     @NonNull TerminalTopBarRuntimeState runtimeState) {
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
        @NonNull public final TerminalTopBarTitleStateMachine.TitleState titleState;
        @NonNull public final TransportKind transportKind;
        @NonNull public final TerminalTopBarRuntimeState runtimeState;
        @NonNull public final LifecycleState lifecycleState;
        @NonNull public final Tone tone;
        @NonNull public final BadgeKind badgeKind;
        public final boolean selected;
        public final boolean locked;
        public final boolean closable;

        public Snapshot(@NonNull String title,
                        @NonNull TerminalTopBarTitleStateMachine.TitleState titleState,
                        @NonNull TransportKind transportKind,
                        @NonNull TerminalTopBarRuntimeState runtimeState,
                        @NonNull LifecycleState lifecycleState,
                        @NonNull Tone tone,
                        @NonNull BadgeKind badgeKind,
                        boolean selected,
                        boolean locked,
                        boolean closable) {
            this.title = title;
            this.titleState = titleState;
            this.transportKind = transportKind;
            this.runtimeState = runtimeState;
            this.lifecycleState = lifecycleState;
            this.tone = tone;
            this.badgeKind = badgeKind;
            this.selected = selected;
            this.locked = locked;
            this.closable = closable;
        }
    }

    private TerminalTopBarStateMachine() {
    }

    @NonNull
    public static Snapshot resolve(@NonNull Input input) {
        TerminalTopBarTitleStateMachine.Snapshot titleSnapshot = TerminalTopBarTitleStateMachine.resolveTitle(
            input.pinned, input.pinnedDisplayName, input.sessionName, input.terminalTitle);

        LifecycleState lifecycleState;
        if (input.running) {
            lifecycleState = LifecycleState.RUNNING;
        } else if (input.exitStatus == 0) {
            lifecycleState = LifecycleState.EXITED_SUCCESS;
        } else {
            lifecycleState = LifecycleState.EXITED_ERROR;
        }

        Tone tone = Tone.NEUTRAL;
        BadgeKind badgeKind = BadgeKind.NONE;

        if (input.runtimeState == TerminalTopBarRuntimeState.FAILED) {
            tone = Tone.ERROR;
            badgeKind = BadgeKind.ERROR;
        } else if (input.runtimeState == TerminalTopBarRuntimeState.RETRY_SCHEDULED) {
            tone = Tone.BUSY;
            badgeKind = BadgeKind.RETRY;
        } else if (input.runtimeState == TerminalTopBarRuntimeState.BUSY) {
            tone = Tone.BUSY;
            badgeKind = BadgeKind.BUSY;
        } else if (lifecycleState == LifecycleState.EXITED_ERROR) {
            tone = Tone.ERROR;
            badgeKind = BadgeKind.ERROR;
        } else if (lifecycleState == LifecycleState.EXITED_SUCCESS) {
            tone = Tone.SUCCESS;
            badgeKind = BadgeKind.DONE;
        } else if (input.transportKind == TransportKind.SSH_PERSIST || input.pinned) {
            tone = Tone.PERSISTENT;
            badgeKind = BadgeKind.PIN;
        } else if (input.transportKind == TransportKind.SSH) {
            tone = Tone.REMOTE;
            badgeKind = BadgeKind.SSH;
        } else if (input.selected) {
            tone = Tone.ACTIVE;
        }

        return new Snapshot(
            titleSnapshot.title,
            titleSnapshot.state,
            input.transportKind,
            input.runtimeState,
            lifecycleState,
            tone,
            badgeKind,
            input.selected,
            input.pinned,
            !input.pinned
        );
    }
}
