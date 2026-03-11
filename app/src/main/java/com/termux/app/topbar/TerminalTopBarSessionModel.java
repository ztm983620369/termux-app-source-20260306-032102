package com.termux.app.topbar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class TerminalTopBarSessionModel {

    @NonNull public final String key;
    public final boolean selected;
    public final boolean pinned;
    public final boolean running;
    public final int exitStatus;
    @Nullable public final String pinnedDisplayName;
    @Nullable public final String sessionName;
    @Nullable public final String terminalTitle;
    @NonNull public final TerminalTopBarStateMachine.TransportKind transportKind;
    @NonNull public final TerminalTopBarRuntimeState runtimeState;

    private TerminalTopBarSessionModel(@NonNull Builder builder) {
        this.key = builder.key;
        this.selected = builder.selected;
        this.pinned = builder.pinned;
        this.running = builder.running;
        this.exitStatus = builder.exitStatus;
        this.pinnedDisplayName = builder.pinnedDisplayName;
        this.sessionName = builder.sessionName;
        this.terminalTitle = builder.terminalTitle;
        this.transportKind = builder.transportKind;
        this.runtimeState = builder.runtimeState;
    }

    public static final class Builder {
        @NonNull private final String key;
        private boolean selected;
        private boolean pinned;
        private boolean running = true;
        private int exitStatus;
        @Nullable private String pinnedDisplayName;
        @Nullable private String sessionName;
        @Nullable private String terminalTitle;
        @NonNull private TerminalTopBarStateMachine.TransportKind transportKind =
            TerminalTopBarStateMachine.TransportKind.LOCAL;
        @NonNull private TerminalTopBarRuntimeState runtimeState = TerminalTopBarRuntimeState.IDLE;

        public Builder(@NonNull String key) {
            this.key = key;
        }

        @NonNull
        public Builder setSelected(boolean selected) {
            this.selected = selected;
            return this;
        }

        @NonNull
        public Builder setPinned(boolean pinned) {
            this.pinned = pinned;
            return this;
        }

        @NonNull
        public Builder setRunning(boolean running) {
            this.running = running;
            return this;
        }

        @NonNull
        public Builder setExitStatus(int exitStatus) {
            this.exitStatus = exitStatus;
            return this;
        }

        @NonNull
        public Builder setPinnedDisplayName(@Nullable String pinnedDisplayName) {
            this.pinnedDisplayName = pinnedDisplayName;
            return this;
        }

        @NonNull
        public Builder setSessionName(@Nullable String sessionName) {
            this.sessionName = sessionName;
            return this;
        }

        @NonNull
        public Builder setTerminalTitle(@Nullable String terminalTitle) {
            this.terminalTitle = terminalTitle;
            return this;
        }

        @NonNull
        public Builder setTransportKind(@NonNull TerminalTopBarStateMachine.TransportKind transportKind) {
            this.transportKind = transportKind;
            return this;
        }

        @NonNull
        public Builder setRuntimeState(@NonNull TerminalTopBarRuntimeState runtimeState) {
            this.runtimeState = runtimeState;
            return this;
        }

        @NonNull
        public TerminalTopBarSessionModel build() {
            return new TerminalTopBarSessionModel(this);
        }
    }
}
