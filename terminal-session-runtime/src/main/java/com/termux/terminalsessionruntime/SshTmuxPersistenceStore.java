package com.termux.terminalsessionruntime;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.termux.sshconnectioncore.SshCommandKnownHostsOptions;
import com.termux.sshconnectioncore.SshKnownHostsFiles;
import com.termux.terminalsessioncore.SshTmuxSessionStateMachine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;

public final class SshTmuxPersistenceStore {

    private static final String SSH_PERSIST_PREFS = "ssh_persistence_prefs";
    private static final String KEY_SSH_PERSIST_ENABLED = "ssh_persist.enabled";
    private static final String KEY_SSH_COMMAND = "ssh_persist.command";
    private static final String KEY_SSH_TMUX_SESSION = "ssh_persist.tmux_session";
    private static final String KEY_SSH_SHELL_NAME = "ssh_persist.shell_name";
    private static final String KEY_SSH_LOCKED_HANDLE = "ssh_persist.locked_handle";
    private static final String KEY_SSH_PERSIST_RECORDS_JSON = "ssh_persist.records_json";
    private static final String SSH_PERSIST_SHELL_NAME_PREFIX = "ssh-persistent-";

    // SharedPreferences is process-global, so all store instances must share the same lock. Activity
    // recreation can briefly leave an old and a new runtime engine alive at the same time.
    private static final Object STORE_LOCK = new Object();
    private final Context context;
    private final SshTmuxCommandFactory commandFactory;

    public SshTmuxPersistenceStore(@NonNull Context context, @NonNull SshTmuxCommandFactory commandFactory) {
        this.context = context.getApplicationContext();
        this.commandFactory = commandFactory;
    }

    @NonNull
    public ArrayList<SshPersistenceRecord> load() {
        synchronized (STORE_LOCK) {
            ArrayList<SshPersistenceRecord> records = new ArrayList<>();
            SharedPreferences prefs = prefs();
            String raw = prefs.getString(KEY_SSH_PERSIST_RECORDS_JSON, "[]");
            boolean requiresRewrite = false;
            if (!TextUtils.isEmpty(raw)) {
                try {
                    JSONArray array = new JSONArray(raw);
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.optJSONObject(i);
                        if (item == null) {
                            requiresRewrite = true;
                            continue;
                        }
                        boolean missingId = item.optString("id", "").trim().isEmpty();
                        SshPersistenceRecord parsed = SshPersistenceRecord.fromJson(item);
                        if (parsed == null) {
                            requiresRewrite = true;
                            continue;
                        }
                        SshPersistenceRecord unnormalized = parsed;
                        parsed = normalize(parsed);
                        if (!TextUtils.equals(unnormalized.sshCommand, parsed.sshCommand)
                            || !TextUtils.equals(unnormalized.tmuxSession, parsed.tmuxSession)
                            || !TextUtils.equals(unnormalized.displayName, parsed.displayName)
                            || !TextUtils.equals(unnormalized.shellName, parsed.shellName)) {
                            requiresRewrite = true;
                        }
                        if (TextUtils.isEmpty(parsed.sshCommand)) {
                            requiresRewrite = true;
                            continue;
                        }
                        if (missingId) {
                            parsed = new SshPersistenceRecord(
                                buildLegacyRecordId(parsed), parsed.sshCommand, parsed.tmuxSession,
                                parsed.displayName, parsed.shellName, parsed.lockedHandle);
                            requiresRewrite = true;
                        }
                        records.add(parsed);
                    }
                } catch (Exception ignored) {
                    requiresRewrite = true;
                }
            }

            if (!records.isEmpty()) {
                ArrayList<SshPersistenceRecord> deduped = dedupe(records);
                if (requiresRewrite || !areEqual(records, deduped)) {
                    saveLocked(deduped, prefs, true);
                }
                return new ArrayList<>(deduped);
            }

            if (prefs.getBoolean(KEY_SSH_PERSIST_ENABLED, false)) {
                String sshCommand = prefs.getString(KEY_SSH_COMMAND, null);
                if (!TextUtils.isEmpty(sshCommand)) {
                    String tmuxSession = commandFactory.normalizeTmuxSessionName(
                        prefs.getString(KEY_SSH_TMUX_SESSION, SshTmuxCommandFactory.DEFAULT_SSH_TMUX_SESSION));
                    String displayName = SshTmuxSessionStateMachine.resolveExistingRemote(
                        tmuxSession, null, null, null, null).displayName;
                    SshPersistenceRecord legacyRecord = new SshPersistenceRecord(
                        "", sshCommand.trim(), tmuxSession, displayName, "", null);
                    String id = buildLegacyRecordId(legacyRecord);
                    String shellName = prefs.getString(KEY_SSH_SHELL_NAME, null);
                    if (TextUtils.isEmpty(shellName)) shellName = buildShellName(id);
                    String lockedHandle = prefs.getString(KEY_SSH_LOCKED_HANDLE, null);
                    records.add(new SshPersistenceRecord(
                        id, sshCommand.trim(), tmuxSession, displayName, shellName, lockedHandle));
                    records = dedupe(records);
                    saveLocked(records, prefs, true);
                    return new ArrayList<>(records);
                }
            }

            return records;
        }
    }

    public void save(@NonNull ArrayList<SshPersistenceRecord> records) {
        synchronized (STORE_LOCK) {
            saveLocked(records, prefs());
        }
    }

    @NonNull
    public SshPersistenceRecord normalize(@NonNull SshPersistenceRecord record) {
        String id = record.id.trim();
        if (id.isEmpty()) id = buildLegacyRecordId(record);
        String tmuxSession = commandFactory.normalizeTmuxSessionName(record.tmuxSession);
        String displayName = SshTmuxSessionStateMachine.resolveExistingRemote(
            tmuxSession, null, record.displayName, null, null).displayName;
        String shellName = record.shellName == null ? "" : record.shellName.trim();
        if (shellName.isEmpty()) shellName = buildShellName(id);
        String sshCommand = commandFactory.sanitizeSshBootstrapCommand(record.sshCommand);
        if (!sshCommand.isEmpty()) {
            try {
                sshCommand = SshCommandKnownHostsOptions.inject(
                    sshCommand, SshKnownHostsFiles.resolveManagedKnownHostsPath(context));
            } catch (IllegalArgumentException ignored) {
                sshCommand = "";
            }
        }
        return new SshPersistenceRecord(id, sshCommand, tmuxSession, displayName, shellName, record.lockedHandle);
    }

    @NonNull
    public String buildShellName(@NonNull String id) {
        StringBuilder tail = new StringBuilder(12);
        for (int i = 0; i < id.length() && tail.length() < 12; i++) {
            char value = id.charAt(i);
            if ((value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z') ||
                (value >= '0' && value <= '9')) {
                tail.append(value);
            }
        }
        if (tail.length() == 0) tail.append(buildStableShellNameTail(id));
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
            if (id.isEmpty()) id = buildLegacyRecordId(record);
            int idDisambiguator = 0;
            while (usedIds.contains(id)) {
                id = buildDeterministicRecordId(record, ++idDisambiguator);
            }
            usedIds.add(id);

            String shellName = record.shellName == null ? "" : record.shellName.trim();
            if (shellName.isEmpty() || usedShellNames.contains(shellName)) {
                String baseShellName = buildShellName(id);
                shellName = baseShellName;
                int shellDisambiguator = 0;
                while (usedShellNames.contains(shellName)) {
                    shellName = baseShellName + "-" + ++shellDisambiguator;
                }
            }
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
        saveLocked(records, prefs, false);
    }

    private void saveLocked(@NonNull ArrayList<SshPersistenceRecord> records, @NonNull SharedPreferences prefs,
                            boolean commitSynchronously) {
        ArrayList<SshPersistenceRecord> deduped = dedupe(records);
        JSONArray json = new JSONArray();
        for (SshPersistenceRecord record : deduped) {
            json.put(record.toJson());
        }
        SharedPreferences.Editor editor = prefs.edit()
            .putString(KEY_SSH_PERSIST_RECORDS_JSON, json.toString())
            .putBoolean(KEY_SSH_PERSIST_ENABLED, !deduped.isEmpty());
        if (commitSynchronously) editor.commit();
        else editor.apply();
    }

    @NonNull
    private String buildLegacyRecordId(@NonNull SshPersistenceRecord record) {
        return buildDeterministicRecordId(record, 0);
    }

    @NonNull
    private String buildDeterministicRecordId(@NonNull SshPersistenceRecord record, int disambiguator) {
        MessageDigest digest = newSha256Digest();
        updateLengthPrefixed(digest, "termux:ssh-persistence-record:v1");
        updateLengthPrefixed(digest, commandFactory.sanitizeSshBootstrapCommand(record.sshCommand));
        updateLengthPrefixed(digest, commandFactory.normalizeTmuxSessionName(record.tmuxSession));
        updateLengthPrefixed(digest, Integer.toString(disambiguator));
        byte[] value = digest.digest();
        StringBuilder out = new StringBuilder(32);
        for (int i = 0; i < 16; i++) {
            out.append(Character.forDigit((value[i] >>> 4) & 0x0f, 16));
            out.append(Character.forDigit(value[i] & 0x0f, 16));
        }
        return out.toString();
    }

    @NonNull
    private String buildStableShellNameTail(@NonNull String id) {
        MessageDigest digest = newSha256Digest();
        updateLengthPrefixed(digest, "termux:ssh-persistence-shell:v1");
        updateLengthPrefixed(digest, id);
        byte[] value = digest.digest();
        StringBuilder out = new StringBuilder(12);
        for (int i = 0; i < 6; i++) {
            out.append(Character.forDigit((value[i] >>> 4) & 0x0f, 16));
            out.append(Character.forDigit(value[i] & 0x0f, 16));
        }
        return out.toString();
    }

    @NonNull
    private MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void updateLengthPrefixed(@NonNull MessageDigest digest, @NonNull String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
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
