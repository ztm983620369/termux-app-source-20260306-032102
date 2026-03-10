package com.termux.sessionsync;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class SftpTransferJournal {

    enum TaskKind {
        DOWNLOAD,
        UPLOAD,
        RELAY
    }

    enum TaskStatus {
        ACTIVE,
        RECOVERING,
        COMPLETED,
        FAILED,
        CANCELLED,
        INTERRUPTED
    }

    static final class TaskHandle {
        @NonNull
        final String id;
        @NonNull
        final TaskKind kind;

        TaskHandle(@NonNull String id, @NonNull TaskKind kind) {
            this.id = id;
            this.kind = kind;
        }
    }

    static final class RecoverableTask {
        @NonNull
        final String id;
        @NonNull
        final TaskKind kind;
        @Nullable
        final String sessionKey;
        @Nullable
        final String destinationPath;
        @NonNull
        final ArrayList<String> sourcePaths;
        @Nullable
        final String stageDirectoryPath;

        RecoverableTask(@NonNull String id,
                        @NonNull TaskKind kind,
                        @Nullable String sessionKey,
                        @Nullable String destinationPath,
                        @NonNull List<String> sourcePaths,
                        @Nullable String stageDirectoryPath) {
            this.id = id;
            this.kind = kind;
            this.sessionKey = sessionKey;
            this.destinationPath = destinationPath;
            this.sourcePaths = new ArrayList<>(sourcePaths);
            this.stageDirectoryPath = stageDirectoryPath;
        }

        @NonNull
        TaskHandle toHandle() {
            return new TaskHandle(id, kind);
        }
    }

    private static final String FILE_NAME = "transfer-journal.json";
    private static final int MAX_FINISHED_RECORDS = 48;
    private static final long PROGRESS_PERSIST_INTERVAL_MS = 450L;
    private static final SftpTransferJournal INSTANCE = new SftpTransferJournal();

    private final Object lock = new Object();
    @Nullable
    private Context appContext;
    @Nullable
    private File rootDirectory;
    @Nullable
    private File journalFile;
    @NonNull
    private final LinkedHashMap<String, TaskRecord> records = new LinkedHashMap<>();
    private boolean loaded;
    private boolean initialized;

    private SftpTransferJournal() {
    }

    @NonNull
    static SftpTransferJournal getInstance() {
        return INSTANCE;
    }

    void initialize(@NonNull Context context) {
        synchronized (lock) {
            appContext = context.getApplicationContext();
            rootDirectory = new File(FileRootResolver.resolveTransferRoot(appContext));
            if (!rootDirectory.exists() && !rootDirectory.mkdirs() && !rootDirectory.exists()) {
                return;
            }
            journalFile = new File(rootDirectory, FILE_NAME);
            if (initialized) {
                return;
            }
            ensureLoadedLocked();
            boolean changed = recoverInterruptedTasksLocked();
            changed |= cleanupOrphanRelayDirectoriesLocked();
            if (changed) {
                persistLocked();
            }
            initialized = true;
        }
    }

    @Nullable
    TaskHandle startTask(@NonNull Context context,
                         @NonNull TaskKind kind,
                         @Nullable String sessionKey,
                         @Nullable String detail,
                         int totalFiles,
                         long totalBytes) {
        synchronized (lock) {
            initialize(context);
            if (journalFile == null) return null;
            String id = kind.name().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID();
            TaskRecord record = new TaskRecord(id, kind);
            record.status = TaskStatus.ACTIVE;
            record.sessionKey = trimToNull(sessionKey);
            record.detail = trimToNull(detail);
            record.totalFiles = Math.max(0, totalFiles);
            record.totalBytes = Math.max(0L, totalBytes);
            record.createdAtMs = System.currentTimeMillis();
            record.updatedAtMs = record.createdAtMs;
            records.put(id, record);
            pruneFinishedLocked();
            persistLocked();
            traceInfo("startTask", record.sessionKey, record.kind.name() + " " + safe(detail));
            return new TaskHandle(id, kind);
        }
    }

    void attachStageDirectory(@NonNull Context context,
                              @Nullable TaskHandle handle,
                              @Nullable String stageDirectoryPath) {
        if (handle == null) return;
        synchronized (lock) {
            initialize(context);
            TaskRecord record = records.get(handle.id);
            if (record == null) return;
            record.stageDirectoryPath = trimToNull(stageDirectoryPath);
            record.updatedAtMs = System.currentTimeMillis();
            persistLocked();
        }
    }

    void configureTask(@NonNull Context context,
                       @Nullable TaskHandle handle,
                       @Nullable List<String> sourcePaths,
                       @Nullable String destinationPath) {
        if (handle == null) return;
        synchronized (lock) {
            initialize(context);
            TaskRecord record = records.get(handle.id);
            if (record == null) return;
            record.destinationPath = trimToNull(destinationPath);
            record.sourcePaths.clear();
            if (sourcePaths != null) {
                for (String path : sourcePaths) {
                    String normalized = trimToNull(path);
                    if (normalized != null) {
                        record.sourcePaths.add(normalized);
                    }
                }
            }
            record.updatedAtMs = System.currentTimeMillis();
            persistLocked();
        }
    }

    void addLocalTempPath(@NonNull Context context,
                          @Nullable TaskHandle handle,
                          @Nullable String localTempPath) {
        if (handle == null) return;
        synchronized (lock) {
            initialize(context);
            TaskRecord record = records.get(handle.id);
            if (record == null) return;
            String normalized = trimToNull(localTempPath);
            if (normalized == null || record.localTempPaths.contains(normalized)) return;
            record.localTempPaths.add(normalized);
            record.updatedAtMs = System.currentTimeMillis();
            persistLocked();
        }
    }

    void clearLocalTempPath(@NonNull Context context,
                            @Nullable TaskHandle handle,
                            @Nullable String localTempPath) {
        if (handle == null) return;
        synchronized (lock) {
            initialize(context);
            TaskRecord record = records.get(handle.id);
            if (record == null) return;
            String normalized = trimToNull(localTempPath);
            if (normalized == null) return;
            if (record.localTempPaths.remove(normalized)) {
                record.updatedAtMs = System.currentTimeMillis();
                persistLocked();
            }
        }
    }

    void addRemoteTempPath(@NonNull Context context,
                           @Nullable TaskHandle handle,
                           @Nullable String sessionKey,
                           @Nullable String remoteTempPath) {
        if (handle == null) return;
        synchronized (lock) {
            initialize(context);
            TaskRecord record = records.get(handle.id);
            if (record == null) return;
            String normalizedSessionKey = trimToNull(sessionKey);
            String normalizedPath = trimToNull(remoteTempPath);
            if (normalizedSessionKey == null || normalizedPath == null) return;
            RemoteTempPath recordPath = new RemoteTempPath(normalizedSessionKey, normalizedPath);
            if (record.remoteTempPaths.contains(recordPath)) return;
            record.remoteTempPaths.add(recordPath);
            record.updatedAtMs = System.currentTimeMillis();
            persistLocked();
        }
    }

    void clearRemoteTempPath(@NonNull Context context,
                             @Nullable TaskHandle handle,
                             @Nullable String sessionKey,
                             @Nullable String remoteTempPath) {
        if (handle == null) return;
        synchronized (lock) {
            initialize(context);
            TaskRecord record = records.get(handle.id);
            if (record == null) return;
            String normalizedSessionKey = trimToNull(sessionKey);
            String normalizedPath = trimToNull(remoteTempPath);
            if (normalizedSessionKey == null || normalizedPath == null) return;
            if (record.remoteTempPaths.remove(new RemoteTempPath(normalizedSessionKey, normalizedPath))) {
                record.updatedAtMs = System.currentTimeMillis();
                persistLocked();
            }
        }
    }

    void updateProgress(@NonNull Context context,
                        @Nullable TaskHandle handle,
                        int totalFiles,
                        int completedFiles,
                        int failedFiles,
                        long totalBytes,
                        long transferredBytes,
                        @Nullable String currentFile) {
        if (handle == null) return;
        synchronized (lock) {
            initialize(context);
            TaskRecord record = records.get(handle.id);
            if (record == null) return;
            long now = System.currentTimeMillis();
            record.totalFiles = Math.max(record.totalFiles, totalFiles);
            record.completedFiles = Math.max(0, completedFiles);
            record.failedFiles = Math.max(0, failedFiles);
            record.totalBytes = Math.max(record.totalBytes, totalBytes);
            record.transferredBytes = Math.max(0L, transferredBytes);
            record.currentFile = trimToNull(currentFile);
            record.updatedAtMs = now;
            if (now - record.lastPersistAtMs >= PROGRESS_PERSIST_INTERVAL_MS) {
                record.lastPersistAtMs = now;
                persistLocked();
            }
        }
    }

    void finishTask(@NonNull Context context,
                    @Nullable TaskHandle handle,
                    @NonNull TaskStatus status,
                    @Nullable String message,
                    int totalFiles,
                    int completedFiles,
                    int failedFiles,
                    long totalBytes,
                    long transferredBytes) {
        if (handle == null) return;
        synchronized (lock) {
            initialize(context);
            TaskRecord record = records.get(handle.id);
            if (record == null) return;
            record.status = status;
            record.message = trimToNull(message);
            record.totalFiles = Math.max(record.totalFiles, totalFiles);
            record.completedFiles = Math.max(0, completedFiles);
            record.failedFiles = Math.max(0, failedFiles);
            record.totalBytes = Math.max(record.totalBytes, totalBytes);
            record.transferredBytes = Math.max(0L, transferredBytes);
            record.currentFile = null;
            record.updatedAtMs = System.currentTimeMillis();
            record.finishedAtMs = record.updatedAtMs;
            if (status != TaskStatus.ACTIVE) {
                if (status != TaskStatus.INTERRUPTED) {
                    for (String localTempPath : new ArrayList<>(record.localTempPaths)) {
                        String normalizedPath = trimToNull(localTempPath);
                        if (normalizedPath != null) {
                            deletePathRecursively(new File(normalizedPath));
                        }
                    }
                    String stageDirectoryPath = trimToNull(record.stageDirectoryPath);
                    if (stageDirectoryPath != null) {
                        deletePathRecursively(new File(stageDirectoryPath));
                    }
                }
                record.localTempPaths.clear();
                record.stageDirectoryPath = null;
            }
            pruneFinishedLocked();
            persistLocked();
            traceInfo("finishTask", record.sessionKey, record.kind.name() + " " + status.name() + " " + safe(message));
        }
    }

    @NonNull
    List<String> listPendingRemoteTempPaths(@NonNull Context context, @Nullable String sessionKey) {
        synchronized (lock) {
            initialize(context);
            String normalizedSessionKey = trimToNull(sessionKey);
            ArrayList<String> out = new ArrayList<>();
            if (normalizedSessionKey == null) return out;
            for (TaskRecord record : records.values()) {
                if (record == null) continue;
                if (record.status == TaskStatus.ACTIVE
                    || record.status == TaskStatus.RECOVERING
                    || record.status == TaskStatus.INTERRUPTED) {
                    continue;
                }
                for (RemoteTempPath remoteTempPath : record.remoteTempPaths) {
                    if (remoteTempPath == null) continue;
                    if (TextUtils.equals(remoteTempPath.sessionKey, normalizedSessionKey)) {
                        out.add(remoteTempPath.path);
                    }
                }
            }
            return out;
        }
    }

    @NonNull
    List<RecoverableTask> snapshotRecoverableTasks(@NonNull Context context) {
        synchronized (lock) {
            initialize(context);
            ArrayList<RecoverableTask> out = new ArrayList<>();
            for (TaskRecord record : records.values()) {
                if (record == null) continue;
                if (record.status != TaskStatus.INTERRUPTED) continue;
                if (record.sourcePaths.isEmpty() || TextUtils.isEmpty(record.destinationPath)) continue;
                out.add(new RecoverableTask(
                    record.id,
                    record.kind,
                    record.sessionKey,
                    record.destinationPath,
                    record.sourcePaths,
                    record.stageDirectoryPath
                ));
            }
            return out;
        }
    }

    boolean hasRecoverableTasks(@NonNull Context context) {
        synchronized (lock) {
            initialize(context);
            for (TaskRecord record : records.values()) {
                if (record == null) continue;
                if (record.status != TaskStatus.INTERRUPTED) continue;
                if (!record.sourcePaths.isEmpty() && !TextUtils.isEmpty(record.destinationPath)) {
                    return true;
                }
            }
            return false;
        }
    }

    void handoffToRecovery(@NonNull Context context,
                           @Nullable TaskHandle handle,
                           @Nullable String message) {
        if (handle == null) return;
        synchronized (lock) {
            initialize(context);
            TaskRecord record = records.get(handle.id);
            if (record == null) return;
            record.status = TaskStatus.CANCELLED;
            record.message = trimToNull(message);
            record.currentFile = null;
            record.localTempPaths.clear();
            record.remoteTempPaths.clear();
            record.stageDirectoryPath = null;
            record.finishedAtMs = System.currentTimeMillis();
            record.updatedAtMs = record.finishedAtMs;
            persistLocked();
        }
    }

    void markRecovering(@NonNull Context context,
                        @Nullable TaskHandle handle,
                        @Nullable String message) {
        if (handle == null) return;
        synchronized (lock) {
            initialize(context);
            TaskRecord record = records.get(handle.id);
            if (record == null) return;
            record.status = TaskStatus.RECOVERING;
            record.message = trimToNull(message);
            record.updatedAtMs = System.currentTimeMillis();
            persistLocked();
        }
    }

    void acknowledgeRemoteTempPath(@NonNull Context context,
                                   @Nullable String sessionKey,
                                   @Nullable String remoteTempPath) {
        synchronized (lock) {
            initialize(context);
            String normalizedSessionKey = trimToNull(sessionKey);
            String normalizedPath = trimToNull(remoteTempPath);
            if (normalizedSessionKey == null || normalizedPath == null) return;
            boolean changed = false;
            for (TaskRecord record : records.values()) {
                if (record == null) continue;
                if (record.remoteTempPaths.remove(new RemoteTempPath(normalizedSessionKey, normalizedPath))) {
                    changed = true;
                    record.updatedAtMs = System.currentTimeMillis();
                }
            }
            if (changed) {
                persistLocked();
            }
        }
    }

    private void ensureLoadedLocked() {
        if (loaded) return;
        loaded = true;
        records.clear();
        if (journalFile == null || !journalFile.exists()) return;
        try {
            String raw = readTextFile(journalFile);
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                TaskRecord record = TaskRecord.fromJson(item);
                if (record == null) continue;
                records.put(record.id, record);
            }
        } catch (Throwable ignored) {
            records.clear();
        }
    }

    private boolean recoverInterruptedTasksLocked() {
        boolean changed = false;
        for (TaskRecord record : records.values()) {
            if (record == null) continue;
            if (record.status != TaskStatus.ACTIVE && record.status != TaskStatus.RECOVERING) continue;
            record.status = TaskStatus.INTERRUPTED;
            record.message = "\u5e94\u7528\u6216\u8fdb\u7a0b\u4e2d\u65ad\uff0c\u4efb\u52a1\u88ab\u6807\u8bb0\u4e3a interrupted";
            record.finishedAtMs = System.currentTimeMillis();
            record.updatedAtMs = record.finishedAtMs;
            changed = true;
            traceWarn("recoverInterrupted", record.sessionKey, record.id);
        }
        return changed;
    }

    private boolean cleanupOrphanRelayDirectoriesLocked() {
        if (rootDirectory == null || !rootDirectory.exists()) {
            return false;
        }
        boolean changed = false;
        File[] children = rootDirectory.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (child == null || !child.isDirectory()) continue;
            String name = child.getName();
            if (!name.startsWith("relay-")) continue;
            boolean referenced = false;
            for (TaskRecord record : records.values()) {
                if (record == null) continue;
                if (TextUtils.equals(record.stageDirectoryPath, child.getAbsolutePath())) {
                    referenced = true;
                    break;
                }
            }
            if (!referenced) {
                deletePathRecursively(child);
                changed = true;
            }
        }
        return changed;
    }

    private void pruneFinishedLocked() {
        int finishedCount = 0;
        for (TaskRecord record : records.values()) {
            if (record == null) continue;
            if (record.status != TaskStatus.ACTIVE && record.status != TaskStatus.RECOVERING) {
                finishedCount++;
            }
        }
        if (finishedCount <= MAX_FINISHED_RECORDS) return;

        Iterator<Map.Entry<String, TaskRecord>> iterator = records.entrySet().iterator();
        while (iterator.hasNext() && finishedCount > MAX_FINISHED_RECORDS) {
            Map.Entry<String, TaskRecord> item = iterator.next();
            TaskRecord record = item.getValue();
            if (record == null
                || record.status == TaskStatus.ACTIVE
                || record.status == TaskStatus.RECOVERING) {
                continue;
            }
            iterator.remove();
            finishedCount--;
        }
    }

    private void persistLocked() {
        if (journalFile == null) return;
        JSONArray array = new JSONArray();
        for (TaskRecord record : records.values()) {
            if (record == null) continue;
            array.put(record.toJson());
        }
        try (FileOutputStream outputStream = new FileOutputStream(journalFile, false)) {
            outputStream.write(array.toString().getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            try {
                outputStream.getFD().sync();
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deletePathRecursively(@Nullable File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deletePathRecursively(child);
                }
            }
        }
        try {
            file.delete();
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    private static String readTextFile(@NonNull File file) throws Exception {
        try (FileInputStream inputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.max(0L, Math.min(file.length(), 2L * 1024L * 1024L))];
            int offset = 0;
            while (offset < buffer.length) {
                int read = inputStream.read(buffer, offset, buffer.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return new String(buffer, 0, offset, StandardCharsets.UTF_8);
        }
    }

    private void traceInfo(@NonNull String action, @Nullable String sessionKey, @Nullable String detail) {
        Context context = appContext;
        SessionSyncTracer.getInstance().info(context, "SftpTransferJournal", action, sessionKey,
            "\u4f20\u8f93\u8d26\u672c\u5df2\u66f4\u65b0", detail);
    }

    private void traceWarn(@NonNull String action, @Nullable String sessionKey, @Nullable String detail) {
        Context context = appContext;
        SessionSyncTracer.getInstance().warn(context, "SftpTransferJournal", action, sessionKey,
            "\u4f20\u8f93\u8d26\u672c\u6062\u590d\u6216\u6e05\u7406", detail);
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) return null;
        String out = value.trim();
        return out.isEmpty() || "null".equalsIgnoreCase(out) ? null : out;
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static final class RemoteTempPath {
        @NonNull
        final String sessionKey;
        @NonNull
        final String path;

        RemoteTempPath(@NonNull String sessionKey, @NonNull String path) {
            this.sessionKey = sessionKey;
            this.path = path;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof RemoteTempPath)) return false;
            RemoteTempPath other = (RemoteTempPath) obj;
            return TextUtils.equals(sessionKey, other.sessionKey)
                && TextUtils.equals(path, other.path);
        }

        @Override
        public int hashCode() {
            return (sessionKey + "|" + path).hashCode();
        }

        @NonNull
        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("sessionKey", sessionKey);
                json.put("path", path);
            } catch (Throwable ignored) {
            }
            return json;
        }

        @Nullable
        static RemoteTempPath fromJson(@Nullable JSONObject json) {
            if (json == null) return null;
            String sessionKey = trimToNull(json.optString("sessionKey", ""));
            String path = trimToNull(json.optString("path", ""));
            if (sessionKey == null || path == null) return null;
            return new RemoteTempPath(sessionKey, path);
        }
    }

    private static final class TaskRecord {
        @NonNull
        final String id;
        @NonNull
        final TaskKind kind;
        @NonNull
        TaskStatus status = TaskStatus.ACTIVE;
        @Nullable
        String sessionKey;
        @Nullable
        String detail;
        @Nullable
        String currentFile;
        @Nullable
        String message;
        @Nullable
        String destinationPath;
        @Nullable
        String stageDirectoryPath;
        int totalFiles;
        int completedFiles;
        int failedFiles;
        long totalBytes;
        long transferredBytes;
        long createdAtMs;
        long updatedAtMs;
        long finishedAtMs;
        long lastPersistAtMs;
        @NonNull
        final ArrayList<String> sourcePaths = new ArrayList<>();
        @NonNull
        final ArrayList<String> localTempPaths = new ArrayList<>();
        @NonNull
        final ArrayList<RemoteTempPath> remoteTempPaths = new ArrayList<>();

        TaskRecord(@NonNull String id, @NonNull TaskKind kind) {
            this.id = id;
            this.kind = kind;
        }

        @Nullable
        static TaskRecord fromJson(@Nullable JSONObject json) {
            if (json == null) return null;
            String id = trimToNull(json.optString("id", ""));
            String kindRaw = trimToNull(json.optString("kind", ""));
            if (id == null || kindRaw == null) return null;
            TaskKind kind;
            try {
                kind = TaskKind.valueOf(kindRaw);
            } catch (Throwable ignored) {
                return null;
            }

            TaskRecord record = new TaskRecord(id, kind);
            String statusRaw = trimToNull(json.optString("status", TaskStatus.INTERRUPTED.name()));
            if (statusRaw != null) {
                try {
                    record.status = TaskStatus.valueOf(statusRaw);
                } catch (Throwable ignored) {
                    record.status = TaskStatus.INTERRUPTED;
                }
            }
            record.sessionKey = trimToNull(json.optString("sessionKey", ""));
            record.detail = trimToNull(json.optString("detail", ""));
            record.currentFile = trimToNull(json.optString("currentFile", ""));
            record.message = trimToNull(json.optString("message", ""));
            record.destinationPath = trimToNull(json.optString("destinationPath", ""));
            record.stageDirectoryPath = trimToNull(json.optString("stageDirectoryPath", ""));
            record.totalFiles = Math.max(0, json.optInt("totalFiles", 0));
            record.completedFiles = Math.max(0, json.optInt("completedFiles", 0));
            record.failedFiles = Math.max(0, json.optInt("failedFiles", 0));
            record.totalBytes = Math.max(0L, json.optLong("totalBytes", 0L));
            record.transferredBytes = Math.max(0L, json.optLong("transferredBytes", 0L));
            record.createdAtMs = Math.max(0L, json.optLong("createdAtMs", 0L));
            record.updatedAtMs = Math.max(0L, json.optLong("updatedAtMs", 0L));
            record.finishedAtMs = Math.max(0L, json.optLong("finishedAtMs", 0L));
            JSONArray sourcePaths = json.optJSONArray("sourcePaths");
            if (sourcePaths != null) {
                for (int index = 0; index < sourcePaths.length(); index++) {
                    String path = trimToNull(sourcePaths.optString(index, ""));
                    if (path != null) {
                        record.sourcePaths.add(path);
                    }
                }
            }
            JSONArray localTemps = json.optJSONArray("localTempPaths");
            if (localTemps != null) {
                for (int index = 0; index < localTemps.length(); index++) {
                    String path = trimToNull(localTemps.optString(index, ""));
                    if (path != null) {
                        record.localTempPaths.add(path);
                    }
                }
            }
            JSONArray remoteTemps = json.optJSONArray("remoteTempPaths");
            if (remoteTemps != null) {
                for (int index = 0; index < remoteTemps.length(); index++) {
                    RemoteTempPath remoteTempPath = RemoteTempPath.fromJson(remoteTemps.optJSONObject(index));
                    if (remoteTempPath != null) {
                        record.remoteTempPaths.add(remoteTempPath);
                    }
                }
            }
            return record;
        }

        @NonNull
        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id);
                json.put("kind", kind.name());
                json.put("status", status.name());
                json.put("sessionKey", sessionKey == null ? JSONObject.NULL : sessionKey);
                json.put("detail", detail == null ? JSONObject.NULL : detail);
                json.put("currentFile", currentFile == null ? JSONObject.NULL : currentFile);
                json.put("message", message == null ? JSONObject.NULL : message);
                json.put("destinationPath", destinationPath == null ? JSONObject.NULL : destinationPath);
                json.put("stageDirectoryPath", stageDirectoryPath == null ? JSONObject.NULL : stageDirectoryPath);
                json.put("totalFiles", totalFiles);
                json.put("completedFiles", completedFiles);
                json.put("failedFiles", failedFiles);
                json.put("totalBytes", totalBytes);
                json.put("transferredBytes", transferredBytes);
                json.put("createdAtMs", createdAtMs);
                json.put("updatedAtMs", updatedAtMs);
                json.put("finishedAtMs", finishedAtMs);

                JSONArray sourceArray = new JSONArray();
                for (String path : sourcePaths) {
                    sourceArray.put(path);
                }
                json.put("sourcePaths", sourceArray);

                JSONArray localTemps = new JSONArray();
                for (String path : localTempPaths) {
                    localTemps.put(path);
                }
                json.put("localTempPaths", localTemps);

                JSONArray remoteTemps = new JSONArray();
                for (RemoteTempPath path : remoteTempPaths) {
                    if (path == null) continue;
                    remoteTemps.put(path.toJson());
                }
                json.put("remoteTempPaths", remoteTemps);
            } catch (Throwable ignored) {
            }
            return json;
        }
    }
}
