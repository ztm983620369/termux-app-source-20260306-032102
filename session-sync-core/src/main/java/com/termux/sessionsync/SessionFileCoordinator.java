package com.termux.sessionsync;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified entry point for file-page session resolution.
 * UI layers should call this coordinator instead of mixing mount/protocol logic.
 */
public final class SessionFileCoordinator {

    public static final String LOCAL_TARGET_KEY = "__local__";

    private static final SessionFileCoordinator INSTANCE = new SessionFileCoordinator();

    private final Object lock = new Object();
    @Nullable
    private SessionSelectionStore selectionStore;

    private SessionFileCoordinator() {
    }

    @NonNull
    public static SessionFileCoordinator getInstance() {
        return INSTANCE;
    }

    public void initialize(@NonNull Context context) {
        SessionRegistry.getInstance().initialize(context);
        SessionSyncTracer.getInstance().initialize(context);
        synchronized (lock) {
            if (selectionStore == null) {
                selectionStore = new SessionSelectionStore(context);
            }
        }
    }

    @Nullable
    public String getSelectedSessionKey(@NonNull Context context) {
        initialize(context);
        synchronized (lock) {
            return selectionStore == null ? null : selectionStore.getSelectedSessionKey();
        }
    }

    public void setSelectedSessionKey(@NonNull Context context, @Nullable String sessionKey) {
        initialize(context);
        String normalized = trimToNull(sessionKey);
        if (LOCAL_TARGET_KEY.equals(normalized)) normalized = null;
        synchronized (lock) {
            if (selectionStore != null) {
                selectionStore.setSelectedSessionKey(normalized);
            }
        }
        SessionSyncTracer.getInstance().info(context, "SessionFileCoordinator", "setSelectedSessionKey",
            normalized, "\u5df2\u4fdd\u5b58\u9009\u4e2d\u4f1a\u8bdd", normalized == null ? "local" : normalized);
    }

    @NonNull
    public List<SessionFileTarget> listTargets(@NonNull Context context) {
        initialize(context);
        List<SessionEntry> entries = SavedSshProfileStore.loadSessionEntries(context);
        ArrayList<SessionFileTarget> out = new ArrayList<>(entries.size());
        String selectedKey = getSelectedSessionKey(context);
        for (SessionEntry entry : entries) {
            if (entry == null) continue;
            String key = targetKeyForEntry(entry);
            out.add(new SessionFileTarget(key, entry, TextUtils.equals(selectedKey, key), false));
        }
        return out;
    }

    @Nullable
    public SessionEntry resolveSelectedEntry(@NonNull Context context) {
        initialize(context);
        List<SessionEntry> entries = SavedSshProfileStore.loadSessionEntries(context);
        if (entries.isEmpty()) return null;

        String selectedKey = getSelectedSessionKey(context);
        if (LOCAL_TARGET_KEY.equals(selectedKey)) return null;
        if (TextUtils.isEmpty(selectedKey)) return null;

        for (SessionEntry entry : entries) {
            if (entry == null) continue;
            if (selectedKey.equals(targetKeyForEntry(entry))) {
                return entry;
            }
        }
        SessionSyncTracer.getInstance().warn(context, "SessionFileCoordinator", "resolveSelectedEntry",
            selectedKey, "\u9009\u4e2d\u670d\u52a1\u5668\u5df2\u4e0d\u5b58\u5728\uff0c\u5df2\u56de\u9000\u5230\u672c\u5730\u76ee\u5f55", null);
        return null;
    }

    @NonNull
    public SessionFileResolveResult resolveSelectedRoot(@NonNull Context context) {
        initialize(context);
        String localRoot = FileRootResolver.termuxPrivateRoot(context);
        SessionEntry selectedEntry = resolveSelectedEntry(context);

        if (selectedEntry == null || selectedEntry.transport == SessionTransport.LOCAL) {
            SessionSyncTracer.getInstance().debug(context, "SessionFileCoordinator", "resolveSelectedRoot",
                LOCAL_TARGET_KEY, "\u672c\u5730\u6839\u76ee\u5f55\u6a21\u5f0f", localRoot);
            return SessionFileResolveResult.ok(SessionFileMode.LOCAL, localRoot, null, LOCAL_TARGET_KEY, "");
        }

        String sessionKey = FileRootResolver.sessionPathKey(selectedEntry);
        SessionSyncTracer.getInstance().info(context, "SessionFileCoordinator", "resolveSelectedRoot",
            sessionKey, "\u5f00\u59cb\u89e3\u6790\u8fdc\u7a0b\u76ee\u5f55", selectedEntry.displayName);

        // Performance path: do not block UI switch by adding an extra probe RTT.
        // Real connectivity check happens on the first list/materialize operation.
        String virtualRoot = FileRootResolver.resolveVirtualRoot(context, selectedEntry);
        SftpProtocolManager.getInstance().requestPrewarmSession(context, selectedEntry);
        return SessionFileResolveResult.ok(
            SessionFileMode.SFTP_PROTOCOL,
            virtualRoot,
            selectedEntry,
            sessionKey,
            ""
        );
    }

    public boolean isVirtualPath(@NonNull Context context, @Nullable String path) {
        initialize(context);
        return SftpProtocolManager.getInstance().isVirtualPath(context, path);
    }

    public boolean isStaleVirtualPath(@NonNull Context context, @Nullable String path) {
        if (TextUtils.isEmpty(path)) return false;
        String normalized = normalizePath(path);
        String prefix = normalizePath(
            FileRootResolver.termuxPrivateRoot(context) + "/" + FileRootResolver.SFTP_VIRTUAL_RELATIVE_ROOT
        );
        if (!(normalized.equals(prefix) || normalized.startsWith(prefix + "/"))) return false;
        return !isVirtualPath(context, normalized);
    }

    @NonNull
    public String getDisplayPath(@NonNull Context context, @Nullable String path) {
        if (isVirtualPath(context, path)) {
            return SftpProtocolManager.getInstance().getDisplayPath(context, path);
        }
        return path == null ? "" : path;
    }

    @NonNull
    public SftpProtocolManager.ListResult listVirtualPath(@NonNull Context context, @Nullable String path) {
        initialize(context);
        SftpProtocolManager.ListResult result = SftpProtocolManager.getInstance().listVirtualPath(context, path);
        if (!result.success) {
            String safe = normalizeFailureMessage(result.messageCn,
                "\u8bfb\u53d6\u8fdc\u7a0b\u76ee\u5f55\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u4e0e\u8ba4\u8bc1\u72b6\u6001\u3002");
            SessionSyncTracer.getInstance().warn(context, "SessionFileCoordinator", "listVirtualPath",
                null, "\u5217\u51fa\u865a\u62df\u76ee\u5f55\u5931\u8d25", result.messageCn);
            return SftpProtocolManager.ListResult.fail(safe);
        }
        return result;
    }

    @NonNull
    public SftpProtocolManager.MaterializeResult materializeVirtualFile(@NonNull Context context,
                                                                        @Nullable String path) {
        initialize(context);
        SftpProtocolManager.MaterializeResult result = SftpProtocolManager.getInstance()
            .materializeFile(context, path);
        if (!result.success) {
            String safe = normalizeFailureMessage(result.messageCn,
                "\u8bfb\u53d6\u8fdc\u7a0b\u6587\u4ef6\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u4e0e\u8ba4\u8bc1\u72b6\u6001\u3002");
            SessionSyncTracer.getInstance().warn(context, "SessionFileCoordinator", "materializeVirtualFile",
                null, "\u62c9\u53d6\u865a\u62df\u6587\u4ef6\u5931\u8d25", result.messageCn);
            return SftpProtocolManager.MaterializeResult.fail(safe);
        }
        return result;
    }

    @NonNull
    public SftpProtocolManager.DownloadResult downloadVirtualPaths(@NonNull Context context,
                                                                   @NonNull List<String> paths,
                                                                   @NonNull String destinationDir,
                                                                   @Nullable SftpProtocolManager.DownloadProgressListener listener,
                                                                   @Nullable SftpProtocolManager.DownloadControl control) {
        initialize(context);
        SftpProtocolManager.DownloadResult result = SftpProtocolManager.getInstance()
            .downloadVirtualPaths(context, paths, destinationDir, listener, control);
        if (!result.success) {
            SessionSyncTracer.getInstance().warn(context, "SessionFileCoordinator", "downloadVirtualPaths",
                null, "\u4e0b\u8f7d\u865a\u62df\u6587\u4ef6\u5931\u8d25", result.messageCn);
        }
        return result;
    }

    @NonNull
    public SftpProtocolManager.UploadResult uploadLocalPathsToVirtual(@NonNull Context context,
                                                                      @NonNull List<String> localPaths,
                                                                      @NonNull String destinationVirtualDir,
                                                                      @Nullable SftpProtocolManager.UploadProgressListener listener,
                                                                      @Nullable SftpProtocolManager.UploadControl control) {
        initialize(context);
        SftpProtocolManager.UploadResult result = SftpProtocolManager.getInstance()
            .uploadLocalPathsToVirtual(context, localPaths, destinationVirtualDir, listener, control);
        if (!result.success) {
            SessionSyncTracer.getInstance().warn(context, "SessionFileCoordinator", "uploadLocalPathsToVirtual",
                null, "\u4e0a\u4f20\u5230\u865a\u62df\u76ee\u5f55\u5931\u8d25", result.messageCn);
        }
        return result;
    }

    @NonNull
    public SftpProtocolManager.CreateResult createVirtualItem(@NonNull Context context,
                                                              @NonNull String virtualDirectoryPath,
                                                              @NonNull String name,
                                                              boolean directory) {
        initialize(context);
        SftpProtocolManager.CreateResult result = SftpProtocolManager.getInstance()
            .createVirtualItem(context, virtualDirectoryPath, name, directory);
        if (!result.success) {
            String safe = normalizeFailureMessage(
                result.messageCn,
                directory
                    ? "\u521b\u5efa\u8fdc\u7a0b\u6587\u4ef6\u5939\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u4e0e\u8ba4\u8bc1\u72b6\u6001\u3002"
                    : "\u521b\u5efa\u8fdc\u7a0b\u6587\u4ef6\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u4e0e\u8ba4\u8bc1\u72b6\u6001\u3002"
            );
            SessionSyncTracer.getInstance().warn(context, "SessionFileCoordinator", "createVirtualItem",
                null, "\u521b\u5efa\u865a\u62df\u76ee\u6807\u5931\u8d25", result.messageCn);
            return SftpProtocolManager.CreateResult.fail(safe);
        }
        return result;
    }

    @NonNull
    public String dumpRecentTrace(@NonNull Context context, int maxCount) {
        initialize(context);
        return SessionSyncTracer.getInstance().dumpRecent(maxCount);
    }

    @NonNull
    public String dumpState(@NonNull Context context) {
        initialize(context);
        JSONObject root = new JSONObject();
        try {
            String selected = getSelectedSessionKey(context);
            SessionSnapshot snapshot = SessionRegistry.getInstance().getSnapshot(context);
            root.put("selectedSessionKey", selected == null ? JSONObject.NULL : selected);
            root.put("activeSessionId", snapshot.getActiveSessionId() == null ? JSONObject.NULL : snapshot.getActiveSessionId());
            root.put("updatedAtMs", snapshot.getUpdatedAtMs());

            JSONArray targets = new JSONArray();
            for (SessionFileTarget target : listTargets(context)) {
                JSONObject row = new JSONObject();
                row.put("key", target.key);
                row.put("entryId", target.entry.id);
                row.put("displayName", target.entry.displayName);
                row.put("transport", target.entry.transport.name());
                row.put("active", target.active);
                row.put("mounted", target.mounted);
                targets.put(row);
            }
            root.put("targets", targets);
            root.put("traceRecent", SessionSyncTracer.getInstance().dumpRecent(80));
        } catch (Exception ignored) {
        }
        return root.toString();
    }

    @NonNull
    private static String normalizeFailureMessage(@Nullable String message, @NonNull String fallback) {
        if (TextUtils.isEmpty(message)) return fallback;
        String out = message.trim();
        if (out.isEmpty()) return fallback;
        if (looksMojibake(out)) return fallback;
        return out;
    }

    private static boolean looksMojibake(@NonNull String text) {
        if (text.indexOf('\uFFFD') >= 0) return true;
        // Keep heuristic conservative to avoid false positives on valid Chinese text.
        return false;
    }

    @NonNull
    private static String normalizePath(@Nullable String rawPath) {
        String value = rawPath == null ? "" : rawPath.trim().replace('\\', '/');
        while (value.contains("//")) value = value.replace("//", "/");
        if (value.endsWith("/") && value.length() > 1) value = value.substring(0, value.length() - 1);
        return value.isEmpty() ? "/" : value;
    }

    @NonNull
    private static String targetKeyForEntry(@NonNull SessionEntry entry) {
        if (SavedSshProfileStore.isProfileEntry(entry)) {
            return "profile:" + entry.id;
        }
        return FileRootResolver.sessionPathKey(entry);
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) return null;
        String out = value.trim();
        return out.isEmpty() ? null : out;
    }
}
