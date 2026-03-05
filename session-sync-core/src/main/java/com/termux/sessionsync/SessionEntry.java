package com.termux.sessionsync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.Objects;

public final class SessionEntry {

    @NonNull
    public final String id;
    @NonNull
    public final String displayName;
    @NonNull
    public final SessionTransport transport;
    @Nullable
    public final String terminalHandle;
    @Nullable
    public final String sshCommand;
    @Nullable
    public final String tmuxSession;
    public final boolean active;
    public final boolean running;
    public final long updatedAtMs;

    private SessionEntry(@NonNull Builder builder) {
        this.id = builder.id;
        this.displayName = builder.displayName;
        this.transport = builder.transport;
        this.terminalHandle = builder.terminalHandle;
        this.sshCommand = builder.sshCommand;
        this.tmuxSession = builder.tmuxSession;
        this.active = builder.active;
        this.running = builder.running;
        this.updatedAtMs = builder.updatedAtMs;
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("displayName", displayName);
            json.put("transport", transport.name());
            json.put("terminalHandle", terminalHandle == null ? JSONObject.NULL : terminalHandle);
            json.put("sshCommand", sshCommand == null ? JSONObject.NULL : sshCommand);
            json.put("tmuxSession", tmuxSession == null ? JSONObject.NULL : tmuxSession);
            json.put("active", active);
            json.put("running", running);
            json.put("updatedAtMs", updatedAtMs);
        } catch (Exception ignored) {
        }
        return json;
    }

    @Nullable
    public static SessionEntry fromJson(@Nullable JSONObject json) {
        if (json == null) return null;
        String id = json.optString("id", "").trim();
        if (id.isEmpty()) return null;

        String displayName = json.optString("displayName", "").trim();
        if (displayName.isEmpty()) displayName = "terminal";

        SessionTransport transport = SessionTransport.LOCAL;
        String transportRaw = json.optString("transport", SessionTransport.LOCAL.name());
        try {
            transport = SessionTransport.valueOf(transportRaw);
        } catch (Exception ignored) {
        }

        Builder builder = new Builder(id, displayName)
            .setTransport(transport)
            .setTerminalHandle(safeOptString(json, "terminalHandle"))
            .setSshCommand(safeOptString(json, "sshCommand"))
            .setTmuxSession(safeOptString(json, "tmuxSession"))
            .setActive(json.optBoolean("active", false))
            .setRunning(json.optBoolean("running", false))
            .setUpdatedAtMs(json.optLong("updatedAtMs", 0L));

        return builder.build();
    }

    @Nullable
    private static String safeOptString(@NonNull JSONObject json, @NonNull String key) {
        String value = json.optString(key, "").trim();
        return value.isEmpty() ? null : value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SessionEntry)) return false;
        SessionEntry that = (SessionEntry) o;
        return active == that.active
            && running == that.running
            && id.equals(that.id)
            && displayName.equals(that.displayName)
            && transport == that.transport
            && Objects.equals(terminalHandle, that.terminalHandle)
            && Objects.equals(sshCommand, that.sshCommand)
            && Objects.equals(tmuxSession, that.tmuxSession);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, displayName, transport, terminalHandle, sshCommand, tmuxSession, active, running);
    }

    public static final class Builder {
        @NonNull
        private final String id;
        @NonNull
        private final String displayName;
        @NonNull
        private SessionTransport transport = SessionTransport.LOCAL;
        @Nullable
        private String terminalHandle;
        @Nullable
        private String sshCommand;
        @Nullable
        private String tmuxSession;
        private boolean active;
        private boolean running;
        private long updatedAtMs;

        public Builder(@NonNull String id, @NonNull String displayName) {
            this.id = id.trim();
            this.displayName = displayName.trim().isEmpty() ? "terminal" : displayName.trim();
        }

        @NonNull
        public Builder setTransport(@NonNull SessionTransport transport) {
            this.transport = transport;
            return this;
        }

        @NonNull
        public Builder setTerminalHandle(@Nullable String terminalHandle) {
            this.terminalHandle = trimToNull(terminalHandle);
            return this;
        }

        @NonNull
        public Builder setSshCommand(@Nullable String sshCommand) {
            this.sshCommand = trimToNull(sshCommand);
            return this;
        }

        @NonNull
        public Builder setTmuxSession(@Nullable String tmuxSession) {
            this.tmuxSession = trimToNull(tmuxSession);
            return this;
        }

        @NonNull
        public Builder setActive(boolean active) {
            this.active = active;
            return this;
        }

        @NonNull
        public Builder setRunning(boolean running) {
            this.running = running;
            return this;
        }

        @NonNull
        public Builder setUpdatedAtMs(long updatedAtMs) {
            this.updatedAtMs = updatedAtMs;
            return this;
        }

        @NonNull
        public SessionEntry build() {
            return new SessionEntry(this);
        }

        @Nullable
        private static String trimToNull(@Nullable String value) {
            if (value == null) return null;
            String out = value.trim();
            return out.isEmpty() ? null : out;
        }
    }
}
