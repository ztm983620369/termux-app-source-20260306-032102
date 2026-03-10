package com.termux.terminalsessionruntime;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.termux.terminalsessioncore.SshTmuxSessionStateMachine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

public final class SshTmuxPersistenceStore {

    private static final String SSH_PERSIST_PREFS = "ssh_persistence_prefs";
    private static final String KEY_SSH_PERSIST_ENABLED = "ssh_persist.enabled";
    private static final String KEY_SSH_COMMAND = "ssh_persist.command";
    private static final String KEY_SSH_TMUX_SESSION = "ssh_persist.tmux_session";
    private static final String KEY_SSH_SHELL_NAME = "ssh_persist.shell_name";
    private static final String KEY_SSH_LOCKED_HANDLE = "ssh_persist.locked_handle";
    private static final String KEY_SSH_PERSIST_RECORDS_JSON = "ssh_persist.records_json";
    private static final String SSH_PERSIST_SHELL_NAME_PREFIX = "ssh-persistent-";

    private final Object lock = new Object();
    private final Context context;
    private final SshTmuxCommandFactory commandFactory;
    private ArrayList<SshPersistenceRecord> cache;

    public SshTmuxPersistenceStore(@NonNull Context context, @NonNull SshTmuxCommandFactory commandFactory) {
        this.context = context.getApplicationContext();
        this.commandFactory = commandFactory;
    }

    @NonNull
    public ArrayList<SshPersistenceRecord> load() {
        synchronized (lock) {
            if (cache != null) return new ArrayList<>(cache);

            ArrayList<SshPersistenceRecord> records = new ArrayList<>();
            SharedPreferences prefs = prefs();
            String raw = prefs.getString(KEY_SSH_PERSIST_RECORDS_JSON, "[]");
            if (!TextUtils.isEmpty(raw)) {
                try {
                    JSONArray array = new JSONArray(raw);
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.optJSONObject(i);
                        if (item == null) continue;
                        SshPersistenceRecord parsed = SshPersistenceRecord.fromJson(item);
                        if (parsed == null) continue;
                        records.add(normalize(parsed));
                    }
                } catch (Exception ignored) {
                }
            }

            if (!records.isEmpty()) {
                ArrayList<SshPersistenceRecord> deduped = dedupe(records);
                if (!areEqual(records, deduped)) {
                    saveLocked(deduped, prefs);
                } else {
                    cache = new ArrayList<>(deduped);
                }
                return new ArrayList<>(deduped);
            }

            if (prefs.getBoolean(KEY_SSH_PERSIST_ENABLED, false)) {
                String sshCommand = prefs.getString(KEY_SSH_COMMAND, null);
                if (!TextUtils.isEmpty(sshCommand)) {
                    String id = UUID.randomUUID().toString();
                    String tmuxSession = commandFactory.normalizeTmuxSessionName(
                        prefs.getString(KEY_SSH_TMUX_SESSION, SshTmuxCommandFactory.DEFAULT_SSH_TMUX_SESSION));
                    String shellName = prefs.getString(KEY_SSH_SHELL_NAME, null);
                    if (TextUtils.isEmpty(shellName)) shellName = buildShellName(id);
                    String lockedHandle = prefs.getString(KEY_SSH_LOCKED_HANDLE, null);
                    String displayName = SshTmuxSessionStateMachine.resolveExistingRemote(
                        tmuxSession, null, null, null, null).displayName;
                    records.add(new SshPersistenceRecord(id, sshCommand.trim(), tmuxSession, displayName, shellName, lockedHandle));
                    records = dedupe(records);
                    saveLocked(records, prefs);
                    return new ArrayList<>(records);
                }
            }

            cache = new ArrayList<>();
            return records;
        }
    }

    public void save(@NonNull ArrayList<SshPersistenceRecord> records) {
        synchronized (lock) {
            saveLocked(records, prefs());
        }
    }

    @NonNull
    public SshPersistenceRecord normalize(@NonNull SshPersistenceRecord record) {
        String id = record.id.trim();
        if (id.isEmpty()) id = UUID.randomUUID().toString();
        String tmuxSession = commandFactory.normalizeTmuxSessionName(record.tmuxSession);
        String displayName = SshTmuxSessionStateMachine.resolveExistingRemote(
            tmuxSession, null, record.displayName, null, null).displayName;
        String shellName = record.shellName == null ? "" : record.shellName.trim();
        if (shellName.isEmpty()) shellName = buildShellName(id);
        String sshCommand = commandFactory.sanitizeSshBootstrapCommand(record.sshCommand);
        return new SshPersistenceRecord(id, sshCommand, tmuxSession, displayName, shellName, record.lockedHandle);
    }

    @NonNull
    public String buildShellName(@NonNull String id) {
        String tail = id.replaceAll("[^A-Za-z0-9]", "");
        if (tail.isEmpty()) tail = Long.toHexString(System.currentTimeMillis());
        if (tail.length() > 12) tail = tail.substring(0, 12);
        return SSH_PERSIST_SHELL_NAME_PREFIX + tail;
    }

    @NonNull
    public ArrayList<SshPersistenceRecord> dedupe(@NonNull ArrayList<SshPersistenceRecord> records) {
        ArrayList<SshPersistenceRecord> deduped = new ArrayList<>();
        for (SshPersistenceRecord raw : records) {
            SshPersistenceRecord normalized = normalize(raw);
            if (TextUtils.isEmpty(normalized.sshCommand)) continue;
            int existing = findByRemote(deduped, normalized.sshCommand, normalized.tmuxSession);
            if (existing < 0) {
                deduped.add(normalized);
            } else {
                deduped.set(existing, merge(deduped.get(existing), normalized));
            }
        }

        HashSet<String> usedIds = new HashSet<>();
        HashSet<String> usedShellNames = new HashSet<>();
        ArrayList<SshPersistenceRecord> normalizedList = new ArrayList<>(deduped.size());
        for (SshPersistenceRecord record : deduped) {
            String id = record.id == null ? "" : record.id.trim();
            if (id.isEmpty() || usedIds.contains(id)) id = UUID.randomUUID().toString();
            usedIds.add(id);

            String shellName = record.shellName == null ? "" : record.shellName.trim();
            if (shellName.isEmpty() || usedShellNames.contains(shellName)) shellName = buildShellName(id);
            usedShellNames.add(shellName);
            normalizedList.add(new SshPersistenceRecord(
                id, record.sshCommand, record.tmuxSession, record.displayName, shellName, record.lockedHandle));
        }
        return normalizedList;
    }

    public int findByRemote(@NonNull ArrayList<SshPersistenceRecord> records, @NonNull String sshCommand,
                            @NonNull String tmuxSession) {
        String targetKey = commandFactory.sanitizeSshBootstrapCommand(sshCommand) + "\n" +
            commandFactory.normalizeTmuxSessionName(tmuxSession);
        for (int i = 0; i < records.size(); i++) {
            SshPersistenceRecord normalized = normalize(records.get(i));
            String existingKey = commandFactory.sanitizeSshBootstrapCommand(normalized.sshCommand) + "\n" +
                commandFactory.normalizeTmuxSessionName(normalized.tmuxSession);
            if (targetKey.equals(existingKey)) return i;
        }
        return -1;
    }

    private void saveLocked(@NonNull ArrayList<SshPersistenceRecord> records, @NonNull SharedPreferences prefs) {
        ArrayList<SshPersistenceRecord> deduped = dedupe(records);
        JSONArray json = new JSONArray();
        for (SshPersistenceRecord record : deduped) {
            json.put(record.toJson());
        }
        prefs.edit()
            .putString(KEY_SSH_PERSIST_RECORDS_JSON, json.toString())
            .putBoolean(KEY_SSH_PERSIST_ENABLED, !deduped.isEmpty())
            .apply();
        cache = new ArrayList<>(deduped);
    }

    private boolean areEqual(@NonNull ArrayList<SshPersistenceRecord> first, @NonNull ArrayList<SshPersistenceRecord> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            SshPersistenceRecord a = first.get(i);
            SshPersistenceRecord b = second.get(i);
            if (!TextUtils.equals(a.id, b.id)) return false;
            if (!TextUtils.equals(a.sshCommand, b.sshCommand)) return false;
            if (!TextUtils.equals(a.tmuxSession, b.tmuxSession)) return false;
            if (!TextUtils.equals(a.displayName, b.displayName)) return false;
            if (!TextUtils.equals(a.shellName, b.shellName)) return false;
            if (!TextUtils.equals(a.lockedHandle, b.lockedHandle)) return false;
        }
        return true;
    }

    @NonNull
    private SshPersistenceRecord merge(@NonNull SshPersistenceRecord a, @NonNull SshPersistenceRecord b) {
        SshPersistenceRecord left = normalize(a);
        SshPersistenceRecord right = normalize(b);
        int leftScore = score(left);
        int rightScore = score(right);
        SshPersistenceRecord primary = rightScore >= leftScore ? right : left;
        SshPersistenceRecord secondary = primary == left ? right : left;
        String displayName = !TextUtils.isEmpty(primary.displayName) ? primary.displayName : secondary.displayName;
        String shellName = !TextUtils.isEmpty(primary.shellName) ? primary.shellName : secondary.shellName;
        String lockedHandle = !TextUtils.isEmpty(primary.lockedHandle) ? primary.lockedHandle : secondary.lockedHandle;
        return new SshPersistenceRecord(primary.id, primary.sshCommand, primary.tmuxSession, displayName, shellName, lockedHandle);
    }

    private int score(@NonNull SshPersistenceRecord record) {
        int score = 0;
        if (!TextUtils.isEmpty(record.displayName)) score += 1;
        if (!TextUtils.isEmpty(record.shellName)) score += 1;
        if (!TextUtils.isEmpty(record.lockedHandle)) score += 2;
        return score;
    }

    @NonNull
    private SharedPreferences prefs() {
        return context.getSharedPreferences(SSH_PERSIST_PREFS, Context.MODE_PRIVATE);
    }
}
