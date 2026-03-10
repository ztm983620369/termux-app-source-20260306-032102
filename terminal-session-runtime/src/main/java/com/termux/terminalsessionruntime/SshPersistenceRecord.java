package com.termux.terminalsessionruntime;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.UUID;

public final class SshPersistenceRecord {

    @NonNull public final String id;
    @NonNull public final String sshCommand;
    @NonNull public final String tmuxSession;
    @NonNull public final String displayName;
    @NonNull public final String shellName;
    @Nullable public final String lockedHandle;

    public SshPersistenceRecord(@NonNull String id, @NonNull String sshCommand, @NonNull String tmuxSession,
                                @NonNull String displayName, @NonNull String shellName,
                                @Nullable String lockedHandle) {
        this.id = id;
        this.sshCommand = sshCommand;
        this.tmuxSession = tmuxSession;
        this.displayName = displayName;
        this.shellName = shellName;
        this.lockedHandle = lockedHandle;
    }

    @Nullable
    public static SshPersistenceRecord fromJson(@NonNull JSONObject json) {
        String sshCommand = json.optString("sshCommand", "").trim();
        if (sshCommand.isEmpty()) return null;

        String id = json.optString("id", "").trim();
        if (id.isEmpty()) id = UUID.randomUUID().toString();

        String tmuxSession = json.optString("tmuxSession", "").trim();
        String displayName = json.optString("displayName", "").trim();
        String shellName = json.optString("shellName", "").trim();
        String lockedHandle = json.optString("lockedHandle", "").trim();
        if (lockedHandle.isEmpty()) lockedHandle = null;

        return new SshPersistenceRecord(id, sshCommand, tmuxSession, displayName, shellName, lockedHandle);
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("sshCommand", sshCommand);
            json.put("tmuxSession", tmuxSession);
            json.put("displayName", displayName);
            json.put("shellName", shellName);
            json.put("lockedHandle", lockedHandle == null ? JSONObject.NULL : lockedHandle);
        } catch (Exception ignored) {
        }
        return json;
    }
}
