package com.termux.sessionsync;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Properties;

/**
 * Private registry for local files that realize an SFTP virtual file.
 *
 * The registry is intentionally owned by session-sync-core so UI callers can
 * ask whether a virtual file has a safe local realization without knowing
 * SFTP cache roots, durable download paths, or freshness implementation details.
 */
public final class VirtualLocalFileRegistry {

    public static final String KIND_MATERIALIZED_CACHE = "MATERIALIZED_CACHE";
    public static final String KIND_DURABLE_DOWNLOAD = "DURABLE_DOWNLOAD";

    public static final String LEVEL_UNKNOWN = "UNKNOWN";
    public static final String LEVEL_WEAK_STAT = "WEAK_STAT";
    public static final String LEVEL_STRONG_CONTENT = "STRONG_CONTENT";

    public static final String METHOD_WEAK_STAT = "stat-only";
    public static final String METHOD_LOCAL_SHA256 = "java-local-sha256";
    public static final String METHOD_REMOTE_UNKNOWN = "remote-unknown";

    private static final String REGISTRY_DIR = "virtual-local-registry";
    private static final String VERSION_1 = "1";
    private static final String VERSION_2 = "2";

    private VirtualLocalFileRegistry() {
    }

    @NonNull
    public static Entry register(@NonNull Context context,
                                 @NonNull String virtualPath,
                                 @NonNull String localPath,
                                 @NonNull String kind,
                                 long remoteModifiedMs,
                                 long remoteSize) {
        return register(
            context,
            virtualPath,
            localPath,
            kind,
            "",
            "",
            remoteModifiedMs,
            remoteSize,
            "",
            METHOD_REMOTE_UNKNOWN,
            LEVEL_WEAK_STAT,
            "",
            METHOD_WEAK_STAT,
            LEVEL_WEAK_STAT
        );
    }

    @NonNull
    public static Entry register(@NonNull Context context,
                                 @NonNull String virtualPath,
                                 @NonNull String localPath,
                                 @NonNull String kind,
                                 @Nullable String authorityKey,
                                 @Nullable String remotePath,
                                 long remoteModifiedMs,
                                 long remoteSize,
                                 @Nullable String remoteSha256,
                                 @Nullable String remoteFingerprintMethod,
                                 @Nullable String remoteFingerprintLevel,
                                 @Nullable String localSha256,
                                 @Nullable String localFingerprintMethod,
                                 @Nullable String localFingerprintLevel) {
        String safeVirtualPath = virtualPath.trim();
        String safeLocalPath = localPath.trim();
        long now = System.currentTimeMillis();
        File localFile = new File(safeLocalPath);
        Entry previous = read(context, safeVirtualPath, kind);
        Entry entry = new Entry(
            VERSION_2,
            safeVirtualPath,
            trimToEmpty(authorityKey),
            trimToEmpty(remotePath),
            safeLocalPath,
            kind,
            remoteModifiedMs,
            remoteSize,
            normalizeSha256(remoteSha256),
            trimToEmpty(remoteFingerprintMethod),
            normalizeLevel(remoteFingerprintLevel),
            localFile.exists() ? localFile.lastModified() : -1L,
            localFile.exists() ? localFile.length() : -1L,
            normalizeSha256(localSha256),
            trimToEmpty(localFingerprintMethod),
            normalizeLevel(localFingerprintLevel),
            previous == null ? now : previous.createdAtMs,
            now
        );
        write(context, entry);
        return entry;
    }

    @Nullable
    public static Entry read(@NonNull Context context,
                             @Nullable String virtualPath,
                             @NonNull String kind) {
        if (TextUtils.isEmpty(virtualPath)) return null;
        File file = entryFile(context, virtualPath, kind);
        if (!file.exists()) return null;

        Properties props = new Properties();
        try (FileInputStream inputStream = new FileInputStream(file)) {
            props.load(inputStream);
            String version = props.getProperty("schemaVersion", props.getProperty("version", ""));
            if (!VERSION_1.equals(version) && !VERSION_2.equals(version)) return null;
            String storedVirtualPath = props.getProperty("virtualPath", "");
            String localPath = props.getProperty("localPath", "");
            String storedKind = props.getProperty("kind", "");
            if (TextUtils.isEmpty(storedVirtualPath) || TextUtils.isEmpty(localPath) || !kind.equals(storedKind)) {
                return null;
            }
            boolean v2 = VERSION_2.equals(version);
            return new Entry(
                version,
                storedVirtualPath,
                props.getProperty("authorityKey", ""),
                props.getProperty("remotePath", ""),
                localPath,
                storedKind,
                parseLong(props.getProperty("remoteModifiedMs"), -1L),
                parseLong(props.getProperty("remoteSize"), -1L),
                v2 ? normalizeSha256(props.getProperty("remoteSha256", "")) : "",
                v2 ? props.getProperty("remoteFingerprintMethod", METHOD_WEAK_STAT) : METHOD_WEAK_STAT,
                v2 ? normalizeLevel(props.getProperty("remoteFingerprintLevel", LEVEL_WEAK_STAT)) : LEVEL_WEAK_STAT,
                parseLong(props.getProperty("recordedLocalModifiedMs"), -1L),
                parseLong(props.getProperty("recordedLocalSize"), -1L),
                v2 ? normalizeSha256(props.getProperty("localSha256", "")) : "",
                v2 ? props.getProperty("localFingerprintMethod", METHOD_WEAK_STAT) : METHOD_WEAK_STAT,
                v2 ? normalizeLevel(props.getProperty("localFingerprintLevel", LEVEL_WEAK_STAT)) : LEVEL_WEAK_STAT,
                parseLong(props.getProperty("createdAtMs"), -1L),
                parseLong(props.getProperty("updatedAtMs"), -1L)
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void clear(@NonNull Context context,
                             @Nullable String virtualPath,
                             @NonNull String kind) {
        if (TextUtils.isEmpty(virtualPath)) return;
        File file = entryFile(context, virtualPath, kind);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    public static boolean isReusable(@Nullable Entry entry,
                                     long actualRemoteModifiedMs,
                                     long actualRemoteSize) {
        if (entry == null || TextUtils.isEmpty(entry.localPath)) return false;
        File localFile = new File(entry.localPath);
        if (!localFile.exists() || !localFile.isFile()) return false;
        if (entry.remoteSize >= 0L && localFile.length() != entry.remoteSize) return false;
        if (entry.recordedLocalSize >= 0L && localFile.length() != entry.recordedLocalSize) return false;
        if (actualRemoteSize >= 0L && entry.remoteSize >= 0L && actualRemoteSize != entry.remoteSize) return false;
        return true;
    }

    public static boolean hasStrongFingerprints(@Nullable Entry entry) {
        return entry != null &&
            LEVEL_STRONG_CONTENT.equals(entry.remoteFingerprintLevel) &&
            LEVEL_STRONG_CONTENT.equals(entry.localFingerprintLevel) &&
            !TextUtils.isEmpty(entry.remoteSha256) &&
            !TextUtils.isEmpty(entry.localSha256);
    }

    public static boolean strongContentMatches(@Nullable Entry entry,
                                               @Nullable String currentRemoteSha256,
                                               @Nullable String currentLocalSha256) {
        if (!hasStrongFingerprints(entry)) return false;
        String remote = normalizeSha256(currentRemoteSha256);
        String local = normalizeSha256(currentLocalSha256);
        return !TextUtils.isEmpty(remote) &&
            !TextUtils.isEmpty(local) &&
            TextUtils.equals(entry.remoteSha256, remote) &&
            TextUtils.equals(entry.localSha256, local) &&
            TextUtils.equals(remote, local);
    }

    @NonNull
    static File entryFile(@NonNull Context context,
                          @NonNull String virtualPath,
                          @NonNull String kind) {
        File root = new File(FileRootResolver.resolveTransferRoot(context), REGISTRY_DIR);
        if (!root.exists()) {
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
        }
        return new File(root, kind + "-" + sha1(virtualPath) + ".properties");
    }

    private static void write(@NonNull Context context, @NonNull Entry entry) {
        File file = entryFile(context, entry.virtualPath, entry.kind);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        Properties props = new Properties();
        props.setProperty("schemaVersion", VERSION_2);
        props.setProperty("version", VERSION_2);
        props.setProperty("virtualPath", entry.virtualPath);
        props.setProperty("authorityKey", entry.authorityKey);
        props.setProperty("remotePath", entry.remotePath);
        props.setProperty("localPath", entry.localPath);
        props.setProperty("kind", entry.kind);
        props.setProperty("remoteModifiedMs", Long.toString(entry.remoteModifiedMs));
        props.setProperty("remoteSize", Long.toString(entry.remoteSize));
        props.setProperty("remoteSha256", entry.remoteSha256);
        props.setProperty("remoteFingerprintMethod", entry.remoteFingerprintMethod);
        props.setProperty("remoteFingerprintLevel", entry.remoteFingerprintLevel);
        props.setProperty("recordedLocalModifiedMs", Long.toString(entry.recordedLocalModifiedMs));
        props.setProperty("recordedLocalSize", Long.toString(entry.recordedLocalSize));
        props.setProperty("localSha256", entry.localSha256);
        props.setProperty("localFingerprintMethod", entry.localFingerprintMethod);
        props.setProperty("localFingerprintLevel", entry.localFingerprintLevel);
        props.setProperty("createdAtMs", Long.toString(entry.createdAtMs));
        props.setProperty("updatedAtMs", Long.toString(entry.updatedAtMs));
        try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
            props.store(outputStream, "Termux SFTP virtual local file registry");
        } catch (Exception ignored) {
        }
    }

    private static long parseLong(@Nullable String raw, long fallback) {
        if (TextUtils.isEmpty(raw)) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @NonNull
    private static String normalizeSha256(@Nullable String raw) {
        String value = trimToEmpty(raw).toLowerCase(java.util.Locale.US);
        if (value.length() != 64) return "";
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
            if (!hex) return "";
        }
        return value;
    }

    @NonNull
    private static String normalizeLevel(@Nullable String raw) {
        String value = trimToEmpty(raw);
        if (LEVEL_STRONG_CONTENT.equals(value) || LEVEL_WEAK_STAT.equals(value) || LEVEL_UNKNOWN.equals(value)) {
            return value;
        }
        return LEVEL_UNKNOWN;
    }

    @NonNull
    private static String trimToEmpty(@Nullable String raw) {
        return raw == null ? "" : raw.trim();
    }

    @NonNull
    private static String sha1(@NonNull String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            }
            return out.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(input.hashCode());
        }
    }

    public static final class Entry {
        @NonNull
        public final String schemaVersion;
        @NonNull
        public final String virtualPath;
        @NonNull
        public final String authorityKey;
        @NonNull
        public final String remotePath;
        @NonNull
        public final String localPath;
        @NonNull
        public final String kind;
        public final long remoteModifiedMs;
        public final long remoteSize;
        @NonNull
        public final String remoteSha256;
        @NonNull
        public final String remoteFingerprintMethod;
        @NonNull
        public final String remoteFingerprintLevel;
        public final long recordedLocalModifiedMs;
        public final long recordedLocalSize;
        @NonNull
        public final String localSha256;
        @NonNull
        public final String localFingerprintMethod;
        @NonNull
        public final String localFingerprintLevel;
        public final long createdAtMs;
        public final long updatedAtMs;

        Entry(@NonNull String schemaVersion,
              @NonNull String virtualPath,
              @NonNull String authorityKey,
              @NonNull String remotePath,
              @NonNull String localPath,
              @NonNull String kind,
              long remoteModifiedMs,
              long remoteSize,
              @NonNull String remoteSha256,
              @NonNull String remoteFingerprintMethod,
              @NonNull String remoteFingerprintLevel,
              long recordedLocalModifiedMs,
              long recordedLocalSize,
              @NonNull String localSha256,
              @NonNull String localFingerprintMethod,
              @NonNull String localFingerprintLevel,
              long createdAtMs,
              long updatedAtMs) {
            this.schemaVersion = schemaVersion;
            this.virtualPath = virtualPath;
            this.authorityKey = authorityKey;
            this.remotePath = remotePath;
            this.localPath = localPath;
            this.kind = kind;
            this.remoteModifiedMs = remoteModifiedMs;
            this.remoteSize = remoteSize;
            this.remoteSha256 = remoteSha256;
            this.remoteFingerprintMethod = remoteFingerprintMethod;
            this.remoteFingerprintLevel = remoteFingerprintLevel;
            this.recordedLocalModifiedMs = recordedLocalModifiedMs;
            this.recordedLocalSize = recordedLocalSize;
            this.localSha256 = localSha256;
            this.localFingerprintMethod = localFingerprintMethod;
            this.localFingerprintLevel = localFingerprintLevel;
            this.createdAtMs = createdAtMs;
            this.updatedAtMs = updatedAtMs;
        }
    }
}
