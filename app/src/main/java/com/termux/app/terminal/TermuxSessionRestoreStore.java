package com.termux.app.terminal;

import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminalsessioncore.CodexRestoreStateMachine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The single durable reader/writer for Termux terminal restoration state. */
final class TermuxSessionRestoreStore {

    static final int SCHEMA_VERSION = 2;
    static final String STATE_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH +
        "/.termux/session-restore-state.json";
    static final String AUDIT_LOG_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH +
        "/.termux/session-restore.log";

    enum UpdateResult {
        APPLIED,
        UNCHANGED,
        IGNORED,
        FAILED
    }

    static final class CodexLease {
        @NonNull final String threadId;
        @NonNull final String handle;
        @NonNull final String workingDirectory;
        @NonNull final String rolloutPath;
        @NonNull final String title;
        final int processId;
        final int order;
        final long updatedAt;
        final boolean foreground;

        CodexLease(@NonNull String threadId, @NonNull String handle,
                   @NonNull String workingDirectory, @NonNull String rolloutPath,
                   @NonNull String title, int processId, int order, long updatedAt,
                   boolean foreground) {
            this.threadId = threadId;
            this.handle = handle;
            this.workingDirectory = workingDirectory;
            this.rolloutPath = rolloutPath;
            this.title = title;
            this.processId = processId > 0 ? processId : -1;
            this.order = order < 0 ? Integer.MAX_VALUE : order;
            this.updatedAt = updatedAt;
            this.foreground = foreground;
        }
    }

    private static final String LOG_TAG = "TermuxSessionRestoreStore";
    private static final Object LOCK = new Object();
    private static final long MAX_STATE_FILE_BYTES = 1024 * 1024;
    private static final long MAX_AUDIT_LOG_BYTES = 512 * 1024;
    private static final int MAX_AUDIT_DETAIL_LENGTH = 512;

    private TermuxSessionRestoreStore() {
    }

    @Nullable
    static String readStateJson() {
        synchronized (LOCK) {
            return readValidJsonWithBackupLocked(new File(STATE_PATH));
        }
    }

    static boolean writeStateJson(@NonNull String json) {
        synchronized (LOCK) {
            try {
                Object parsed = new JSONTokener(json).nextValue();
                if (!(parsed instanceof JSONObject)) return false;
                JSONObject candidate = (JSONObject) parsed;
                retainMissingCodexLeasesInMemory(candidate, readRootLocked());
                return writeFileAtomicallyLocked(new File(STATE_PATH), candidate.toString());
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Failed merging durable Codex leases: " + e.getMessage());
                return false;
            }
        }
    }

    @NonNull
    static ArrayList<CodexLease> listCodexLeases() {
        synchronized (LOCK) {
            return readCodexLeasesFromRoot(readRootLocked());
        }
    }

    @Nullable
    static CodexLease findCodexLeaseByThread(@Nullable String threadId) {
        String normalizedThreadId = normalizeThreadId(threadId);
        if (TextUtils.isEmpty(normalizedThreadId)) return null;
        synchronized (LOCK) {
            for (CodexLease lease : readCodexLeasesFromRoot(readRootLocked())) {
                if (TextUtils.equals(normalizedThreadId, lease.threadId)) return lease;
            }
            return null;
        }
    }

    @Nullable
    static CodexLease findCodexLeaseByHandle(@Nullable String handle) {
        String normalizedHandle = normalizeText(handle);
        if (TextUtils.isEmpty(normalizedHandle)) return null;
        synchronized (LOCK) {
            for (CodexLease lease : readCodexLeasesFromRoot(readRootLocked())) {
                if (TextUtils.equals(normalizedHandle, lease.handle)) return lease;
            }
            return null;
        }
    }

    @NonNull
    static UpdateResult rebindCodexLease(@NonNull String threadId, @NonNull String handle, int order) {
        synchronized (LOCK) {
            JSONObject root = readRootLocked();
            UpdateResult result;
            try {
                result = rebindCodexLeaseInMemory(root, threadId, handle, order);
                if (result == UpdateResult.APPLIED &&
                    !writeFileAtomicallyLocked(new File(STATE_PATH), root.toString())) {
                    result = UpdateResult.FAILED;
                }
            } catch (JSONException e) {
                result = UpdateResult.FAILED;
            }
            appendCodexAuditLocked("lease_rebound", threadId, handle,
                result.name().toLowerCase(Locale.ROOT));
            return result;
        }
    }

    @NonNull
    static UpdateResult revokeCodexLease(@Nullable String threadId, @Nullable String handle,
                                         @Nullable String detail) {
        synchronized (LOCK) {
            JSONObject root = readRootLocked();
            UpdateResult result;
            try {
                result = removeCodexLeaseInMemory(root, threadId, handle);
                if (result == UpdateResult.APPLIED &&
                    !writeFileAtomicallyLocked(new File(STATE_PATH), root.toString())) {
                    result = UpdateResult.FAILED;
                }
            } catch (JSONException e) {
                result = UpdateResult.FAILED;
            }
            appendCodexAuditLocked("user_remove", threadId, handle,
                normalizeText(detail) + ":" + result.name().toLowerCase(Locale.ROOT));
            return result;
        }
    }

    @NonNull
    static UpdateResult applyCodexEvent(@NonNull String handle, int order,
                                        @NonNull CodexSessionHostProtocol.Event event) {
        if (TextUtils.isEmpty(handle)) return UpdateResult.IGNORED;

        synchronized (LOCK) {
            JSONObject root = readRootLocked();
            UpdateResult result;
            try {
                result = updateCodexEventInMemory(root, handle, order, event);
                if (result == UpdateResult.APPLIED &&
                    !writeFileAtomicallyLocked(new File(STATE_PATH), root.toString())) {
                    result = UpdateResult.FAILED;
                }
            } catch (JSONException e) {
                Logger.logWarn(LOG_TAG, "Failed applying Codex restore-state event: " + e.getMessage());
                result = UpdateResult.FAILED;
            }
            appendCodexAuditLocked("host_" + event.type.name().toLowerCase(Locale.ROOT), event.threadId,
                handle, result.name().toLowerCase(Locale.ROOT));
            return result;
        }
    }

    static void appendCodexAudit(@NonNull String event, @Nullable String threadId,
                                 @Nullable String handle, @Nullable String detail) {
        synchronized (LOCK) {
            appendCodexAuditLocked(event, threadId, handle, detail);
        }
    }

    @NonNull
    static UpdateResult updateCodexEventInMemory(@NonNull JSONObject root,
                                                  @NonNull String handle,
                                                  int order,
                                                  @NonNull CodexSessionHostProtocol.Event event) throws JSONException {
        JSONArray source = root.optJSONArray("sessions");
        if (source == null) source = new JSONArray();

        JSONObject matchedCodex = null;
        String matchedKey = "";
        int matchedOrder = Integer.MAX_VALUE;
        JSONArray retained = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;

            boolean codex = "codex".equals(item.optString("type", "").trim());
            boolean sameHandle = TextUtils.equals(handle, item.optString("handle", "").trim());
            boolean sameThread = codex && TextUtils.equals(
                event.threadId, item.optString("codex_thread_id", "").trim());
            boolean exactCodex = codex && sameHandle && sameThread;

            if (event.type == CodexRestoreStateMachine.HostEvent.CLOSED) {
                if (exactCodex) {
                    matchedCodex = item;
                    matchedKey = item.optString("key", "").trim();
                    matchedOrder = item.optInt("order", Integer.MAX_VALUE);
                }
                retained.put(item);
                continue;
            }

            if (sameThread) {
                if (codex && matchedCodex == null) {
                    matchedCodex = item;
                    matchedKey = item.optString("key", "").trim();
                    matchedOrder = item.optInt("order", Integer.MAX_VALUE);
                }
                continue;
            }
            if (sameHandle) {
                if (codex) {
                    JSONObject displacedLease = new JSONObject(item.toString());
                    displacedLease.put("handle", "");
                    retained.put(displacedLease);
                }
                continue;
            }
            retained.put(item);
        }

        boolean tracked = matchedCodex != null;
        CodexRestoreStateMachine.HostAction action = CodexRestoreStateMachine.resolveHostEvent(
            new CodexRestoreStateMachine.HostEventInput(
                event.type,
                true,
                event.type != CodexRestoreStateMachine.HostEvent.READY || event.hasDurableRollout(),
                tracked,
                tracked));
        if (action == CodexRestoreStateMachine.HostAction.IGNORE) return UpdateResult.IGNORED;
        if (action == CodexRestoreStateMachine.HostAction.REMOVE) {
            return removeCodexLeaseInMemory(root, event.threadId, handle);
        }

        long now = System.currentTimeMillis() / 1000L;
        String foregroundKey = root.optString("foreground_key", "").trim();
        String foregroundHandle = root.optString("foreground_handle", "").trim();
        int foregroundOrder = root.optInt("foreground_order", Integer.MAX_VALUE);

        int safeOrder = order < 0 ? matchedOrder : order;
        if (safeOrder < 0) safeOrder = Integer.MAX_VALUE;
        String key = "codex:" + event.threadId;
        String title = TextUtils.isEmpty(event.title)
            ? "Codex " + event.threadId.substring(0, 8)
            : event.title;

        JSONObject record = new JSONObject();
        record.put("key", key);
        record.put("type", "codex");
        record.put("handle", handle);
        record.put("display_name", title);
        record.put("cwd", event.workingDirectory);
        record.put("shell_name", "");
        record.put("executable", "");
        record.put("codex_thread_id", event.threadId);
        record.put("codex_rollout_path", event.rolloutPath);
        record.put("codex_pid", event.processId);
        record.put("ssh_persist_record_id", "");
        record.put("ssh_command", "");
        record.put("tmux_session", "");
        if (safeOrder != Integer.MAX_VALUE) record.put("order", safeOrder);
        record.put("updated_at", now);
        retained.put(record);

        root.put("version", SCHEMA_VERSION);
        root.put("updated_at", now);
        root.put("sessions", retained);
        if (TextUtils.equals(foregroundHandle, handle) || TextUtils.equals(foregroundKey, matchedKey) ||
            (TextUtils.isEmpty(foregroundKey) && TextUtils.isEmpty(foregroundHandle) && safeOrder == 0)) {
            root.put("foreground_key", key);
            root.put("foreground_handle", handle);
            if (safeOrder != Integer.MAX_VALUE) root.put("foreground_order", safeOrder);
        }
        return UpdateResult.APPLIED;
    }

    static int retainMissingCodexLeasesInMemory(@NonNull JSONObject candidate,
                                                 @NonNull JSONObject durable) throws JSONException {
        JSONArray candidateSessions = candidate.optJSONArray("sessions");
        if (candidateSessions == null) candidateSessions = new JSONArray();
        JSONArray durableSessions = durable.optJSONArray("sessions");
        if (durableSessions == null) durableSessions = new JSONArray();

        Map<String, JSONObject> candidateByThread = new HashMap<>();
        for (int i = 0; i < candidateSessions.length(); i++) {
            JSONObject item = candidateSessions.optJSONObject(i);
            String threadId = getCodexThreadId(item);
            if (!TextUtils.isEmpty(threadId)) {
                candidateByThread.put(threadId, item);
            }
        }

        int retained = 0;
        Set<String> appendedThreads = new HashSet<>();
        for (int i = 0; i < durableSessions.length(); i++) {
            JSONObject item = durableSessions.optJSONObject(i);
            String threadId = getCodexThreadId(item);
            if (TextUtils.isEmpty(threadId)) continue;
            JSONObject candidateItem = candidateByThread.get(threadId);
            if (candidateItem != null) {
                int candidateProcessId = candidateItem.optInt("codex_pid", -1);
                int durableProcessId = item.optInt("codex_pid", -1);
                if (candidateProcessId <= 0 && durableProcessId > 0) {
                    candidateItem.put("codex_pid", durableProcessId);
                }
                continue;
            }
            if (!appendedThreads.add(threadId)) {
                continue;
            }
            candidateSessions.put(new JSONObject(item.toString()));
            retained++;
        }

        candidate.put("version", SCHEMA_VERSION);
        candidate.put("sessions", candidateSessions);
        if (retained > 0 && TextUtils.isEmpty(candidate.optString("foreground_key", "")) &&
            TextUtils.isEmpty(candidate.optString("foreground_handle", ""))) {
            String durableForegroundKey = normalizeText(durable.optString("foreground_key", ""));
            if (durableForegroundKey.startsWith("codex:") &&
                appendedThreads.contains(normalizeThreadId(durableForegroundKey.substring("codex:".length())))) {
                candidate.put("foreground_key", durableForegroundKey);
                candidate.put("foreground_handle", normalizeText(durable.optString("foreground_handle", "")));
                int foregroundOrder = durable.optInt("foreground_order", Integer.MAX_VALUE);
                if (foregroundOrder != Integer.MAX_VALUE) candidate.put("foreground_order", foregroundOrder);
            }
        }
        return retained;
    }

    @NonNull
    static UpdateResult rebindCodexLeaseInMemory(@NonNull JSONObject root,
                                                  @NonNull String threadId,
                                                  @NonNull String handle,
                                                  int order) throws JSONException {
        String normalizedThreadId = normalizeThreadId(threadId);
        String normalizedHandle = normalizeText(handle);
        if (TextUtils.isEmpty(normalizedThreadId) || TextUtils.isEmpty(normalizedHandle)) {
            return UpdateResult.IGNORED;
        }

        JSONArray sessions = root.optJSONArray("sessions");
        if (sessions == null) return UpdateResult.IGNORED;
        JSONObject matched = null;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject item = sessions.optJSONObject(i);
            if (TextUtils.equals(normalizedThreadId, getCodexThreadId(item))) {
                matched = item;
                break;
            }
        }
        if (matched == null) return UpdateResult.IGNORED;

        String oldHandle = normalizeText(matched.optString("handle", ""));
        int oldOrder = matched.optInt("order", Integer.MAX_VALUE);
        int safeOrder = order < 0 ? Integer.MAX_VALUE : order;
        if (TextUtils.equals(oldHandle, normalizedHandle) &&
            (safeOrder == Integer.MAX_VALUE || safeOrder == oldOrder)) {
            return UpdateResult.UNCHANGED;
        }

        long now = System.currentTimeMillis() / 1000L;
        matched.put("handle", normalizedHandle);
        if (safeOrder != Integer.MAX_VALUE) matched.put("order", safeOrder);
        matched.put("updated_at", now);
        root.put("version", SCHEMA_VERSION);
        root.put("updated_at", now);

        String key = "codex:" + normalizedThreadId;
        if (TextUtils.equals(key, normalizeText(root.optString("foreground_key", ""))) ||
            (!TextUtils.isEmpty(oldHandle) &&
                TextUtils.equals(oldHandle, normalizeText(root.optString("foreground_handle", ""))))) {
            root.put("foreground_key", key);
            root.put("foreground_handle", normalizedHandle);
            if (safeOrder != Integer.MAX_VALUE) root.put("foreground_order", safeOrder);
        }
        return UpdateResult.APPLIED;
    }

    @NonNull
    static UpdateResult removeCodexLeaseInMemory(@NonNull JSONObject root,
                                                  @Nullable String threadId,
                                                  @Nullable String handle) throws JSONException {
        String normalizedThreadId = normalizeThreadId(threadId);
        String normalizedHandle = normalizeText(handle);
        if (TextUtils.isEmpty(normalizedThreadId) && TextUtils.isEmpty(normalizedHandle)) {
            return UpdateResult.IGNORED;
        }

        JSONArray source = root.optJSONArray("sessions");
        if (source == null) return UpdateResult.UNCHANGED;
        JSONArray retained = new JSONArray();
        boolean removed = false;
        String removedKey = "";
        String removedHandle = "";
        int removedOrder = Integer.MAX_VALUE;
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;
            String itemThreadId = getCodexThreadId(item);
            boolean matches = !TextUtils.isEmpty(itemThreadId) &&
                ((!TextUtils.isEmpty(normalizedThreadId) && TextUtils.equals(normalizedThreadId, itemThreadId)) ||
                    (TextUtils.isEmpty(normalizedThreadId) && !TextUtils.isEmpty(normalizedHandle) &&
                        TextUtils.equals(normalizedHandle, normalizeText(item.optString("handle", "")))));
            if (matches) {
                removed = true;
                removedKey = normalizeText(item.optString("key", ""));
                removedHandle = normalizeText(item.optString("handle", ""));
                removedOrder = item.optInt("order", Integer.MAX_VALUE);
            } else {
                retained.put(item);
            }
        }
        if (!removed) return UpdateResult.UNCHANGED;

        long now = System.currentTimeMillis() / 1000L;
        root.put("version", SCHEMA_VERSION);
        root.put("updated_at", now);
        root.put("sessions", retained);
        if (TextUtils.equals(removedKey, normalizeText(root.optString("foreground_key", ""))) ||
            TextUtils.equals(removedHandle, normalizeText(root.optString("foreground_handle", "")))) {
            root.put("foreground_key", "");
            root.put("foreground_handle", "");
            if (removedOrder != Integer.MAX_VALUE) root.put("foreground_order", removedOrder);
        }
        return UpdateResult.APPLIED;
    }

    @NonNull
    private static ArrayList<CodexLease> readCodexLeasesFromRoot(@NonNull JSONObject root) {
        ArrayList<CodexLease> leases = new ArrayList<>();
        JSONArray sessions = root.optJSONArray("sessions");
        if (sessions == null) return leases;

        String foregroundKey = normalizeText(root.optString("foreground_key", ""));
        String foregroundHandle = normalizeText(root.optString("foreground_handle", ""));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject item = sessions.optJSONObject(i);
            String threadId = getCodexThreadId(item);
            if (TextUtils.isEmpty(threadId) || !seen.add(threadId)) continue;
            String handle = normalizeText(item.optString("handle", ""));
            String key = "codex:" + threadId;
            leases.add(new CodexLease(
                threadId,
                handle,
                normalizeText(item.optString("cwd", "")),
                normalizeText(item.optString("codex_rollout_path", "")),
                normalizeText(item.optString("display_name", "")),
                item.optInt("codex_pid", -1),
                item.optInt("order", Integer.MAX_VALUE),
                item.optLong("updated_at", 0L),
                TextUtils.equals(key, foregroundKey) ||
                    (!TextUtils.isEmpty(handle) && TextUtils.equals(handle, foregroundHandle))));
        }
        return leases;
    }

    @NonNull
    private static String getCodexThreadId(@Nullable JSONObject item) {
        if (item == null || !"codex".equals(normalizeText(item.optString("type", "")))) return "";
        return normalizeThreadId(item.optString("codex_thread_id", ""));
    }

    @NonNull
    private static String normalizeThreadId(@Nullable String value) {
        String normalized = normalizeText(value);
        if (TextUtils.isEmpty(normalized)) return "";
        try {
            return UUID.fromString(normalized).toString();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    @NonNull
    private static String normalizeText(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    @NonNull
    private static JSONObject readRootLocked() {
        String raw = readValidJsonWithBackupLocked(new File(STATE_PATH));
        if (!TextUtils.isEmpty(raw)) {
            try {
                return new JSONObject(raw);
            } catch (Exception ignored) {
            }
        }
        return new JSONObject();
    }

    @Nullable
    private static String readValidJsonWithBackupLocked(@NonNull File target) {
        String primary = readFileText(target);
        if (isJsonObject(primary)) return primary;
        String backup = readFileText(new File(target.getAbsolutePath() + ".bak"));
        return isJsonObject(backup) ? backup : null;
    }

    private static boolean isJsonObject(@Nullable String raw) {
        if (TextUtils.isEmpty(raw)) return false;
        try {
            return new JSONTokener(raw).nextValue() instanceof JSONObject;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Nullable
    private static String readFileText(@NonNull File file) {
        if (!file.isFile()) return null;
        if (file.length() > MAX_STATE_FILE_BYTES) {
            Logger.logWarn(LOG_TAG, "Rejecting oversized restore-state file " + file);
            return null;
        }
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read > 0) out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed reading " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean writeFileAtomicallyLocked(@NonNull File target, @NonNull String content) {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            Logger.logWarn(LOG_TAG, "Failed creating restore-state directory " + parent);
            return false;
        }

        String current = readFileText(target);
        if (TextUtils.equals(current, content)) return true;

        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try {
            writeSyncedFile(tmp, content.getBytes(StandardCharsets.UTF_8));
            Os.rename(tmp.getAbsolutePath(), target.getAbsolutePath());
            syncDirectory(parent);
            writeBackupLocked(new File(target.getAbsolutePath() + ".bak"), content);
            return true;
        } catch (Exception e) {
            tmp.delete();
            Logger.logWarn(LOG_TAG, "Failed writing " + target + ": " + e.getMessage());
            return false;
        }
    }

    private static void writeBackupLocked(@NonNull File backup, @NonNull String content) {
        File tmp = new File(backup.getAbsolutePath() + ".tmp");
        try {
            writeSyncedFile(tmp, content.getBytes(StandardCharsets.UTF_8));
            Os.rename(tmp.getAbsolutePath(), backup.getAbsolutePath());
            syncDirectory(backup.getParentFile());
        } catch (Exception e) {
            tmp.delete();
            Logger.logWarn(LOG_TAG, "Failed writing restore-state backup: " + e.getMessage());
        }
    }

    private static void appendCodexAuditLocked(@NonNull String event, @Nullable String threadId,
                                               @Nullable String handle, @Nullable String detail) {
        File target = new File(AUDIT_LOG_PATH);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            Logger.logWarn(LOG_TAG, "Failed creating restore audit directory " + parent);
            return;
        }

        try {
            JSONObject record = new JSONObject();
            record.put("timestamp_ms", System.currentTimeMillis());
            record.put("event", boundedAuditText(event));
            record.put("thread_id", boundedAuditText(threadId));
            record.put("handle", boundedAuditText(handle));
            record.put("detail", boundedAuditText(detail));
            byte[] bytes = (record.toString() + "\n").getBytes(StandardCharsets.UTF_8);

            if (target.isFile() && target.length() + bytes.length > MAX_AUDIT_LOG_BYTES) {
                File archived = new File(AUDIT_LOG_PATH + ".1");
                Os.rename(target.getAbsolutePath(), archived.getAbsolutePath());
                syncDirectory(parent);
            }

            boolean created = !target.exists();
            try (FileOutputStream out = new FileOutputStream(target, true)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }
            if (created) syncDirectory(parent);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed appending restore audit log: " + e.getMessage());
        }
    }

    @NonNull
    private static String boundedAuditText(@Nullable String value) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.length() <= MAX_AUDIT_DETAIL_LENGTH
            ? normalized
            : normalized.substring(0, MAX_AUDIT_DETAIL_LENGTH);
    }

    private static void writeSyncedFile(@NonNull File target, @NonNull byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }
    }

    private static void syncDirectory(@Nullable File directory) throws Exception {
        if (directory == null) return;
        FileDescriptor descriptor = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY, 0);
        try {
            Os.fsync(descriptor);
        } finally {
            Os.close(descriptor);
        }
    }
}
