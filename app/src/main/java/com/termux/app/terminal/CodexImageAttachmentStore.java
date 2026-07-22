package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.luck.pictureselector.TermuxPictureSelectorLauncher;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Durable content-addressed storage and audit journal for Codex image attachments. */
final class CodexImageAttachmentStore {

    interface CancelProbe {
        boolean isCancelled();
    }

    static final class MaterializedImage {
        @NonNull final File file;
        @NonNull final String sha256;
        @NonNull final String extension;
        final long size;
        final int width;
        final int height;
        final boolean reused;

        MaterializedImage(@NonNull File file, @NonNull String sha256,
                          @NonNull String extension, long size, int width, int height,
                          boolean reused) {
            this.file = file;
            this.sha256 = sha256;
            this.extension = extension;
            this.size = size;
            this.width = width;
            this.height = height;
            this.reused = reused;
        }
    }

    static final class DeduplicatedBatch {
        @NonNull final List<MaterializedImage> uniqueImages;
        final int duplicateCount;
        final long duplicateBytes;
        final int reusedUniqueCount;
        final long reusedUniqueBytes;

        DeduplicatedBatch(@NonNull List<MaterializedImage> uniqueImages,
                          int duplicateCount, long duplicateBytes,
                          int reusedUniqueCount, long reusedUniqueBytes) {
            this.uniqueImages = Collections.unmodifiableList(uniqueImages);
            this.duplicateCount = Math.max(0, duplicateCount);
            this.duplicateBytes = Math.max(0L, duplicateBytes);
            this.reusedUniqueCount = Math.max(0, reusedUniqueCount);
            this.reusedUniqueBytes = Math.max(0L, reusedUniqueBytes);
        }
    }

    private static final String LOG_TAG = "CodexImageAttachmentStore";
    private static final Object LOCK = new Object();
    private static final int SCHEMA_VERSION = 2;
    private static final long MAX_INPUT_BYTES = 1024L * 1024L * 1024L;
    private static final int TRANSCODE_MAX_DIMENSION = 4096;
    private static final long MAX_LOG_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_LOG_ARCHIVES = 5;
    private static final int MAX_LOG_TEXT = 512;
    private static final int PATH_MISSING = 0;
    private static final int PATH_REGULAR = 1;
    private static final int PATH_SYMLINK = 2;
    private static final int PATH_OTHER = 3;
    private static final String ROOT_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH +
        "/.termux/codex-image-attachments";
    private static final File OBJECTS_DIR = new File(ROOT_PATH, "objects");
    private static final File STATE_DIR = new File(ROOT_PATH, "state");
    private static final File LOG_DIR = new File(ROOT_PATH, "logs");
    private static final File ACTIVE_STATE = new File(STATE_DIR, "active-operation.json");
    private static final File LAST_STATE = new File(STATE_DIR, "last-operation.json");
    private static final File EVENT_LOG = new File(LOG_DIR, "events.jsonl");
    private static final Object[] OBJECT_LOCKS = new Object[64];
    private static boolean sInitialized;

    static {
        for (int index = 0; index < OBJECT_LOCKS.length; index++) {
            OBJECT_LOCKS[index] = new Object();
        }
    }

    private CodexImageAttachmentStore() {
    }

    static void initialize() {
        synchronized (LOCK) {
            if (sInitialized) return;
            ensurePrivateDirectory(OBJECTS_DIR);
            ensurePrivateDirectory(STATE_DIR);
            ensurePrivateDirectory(LOG_DIR);
            cleanupPartFilesLocked(OBJECTS_DIR);
            JSONObject interrupted = readJsonObject(ACTIVE_STATE);
            if (interrupted != null) {
                appendEventLocked(
                    interrupted.optString("operation_id", ""),
                    "operation_interrupted_on_startup",
                    interrupted.optString("phase", ""),
                    interrupted.optString("route", ""),
                    interrupted.optInt("image_count", 0),
                    "Previous in-flight operation was made terminal after process restart");
                interrupted.remove("message");
                putQuietly(interrupted, "phase", "INTERRUPTED");
                putQuietly(interrupted, "updated_at_ms", System.currentTimeMillis());
                writeJsonAtomicallyLocked(LAST_STATE, interrupted);
                deleteQuietly(ACTIVE_STATE);
            }
            sInitialized = true;
        }
    }

    @NonNull
    static MaterializedImage materialize(@NonNull Context context,
                                         @NonNull TermuxPictureSelectorLauncher.SelectedImage selected,
                                         @Nullable CancelProbe cancelProbe) throws Exception {
        initialize();
        ensureNotCancelled(cancelProbe);

        File incoming = new File(OBJECTS_DIR, ".incoming-" + UUID.randomUUID() + ".part");
        Exception lastOpenError = null;
        boolean copied = false;
        String copiedSha256 = "";
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, selected.getAvailablePath());
        addCandidate(candidates, selected.getSourcePath());
        addCandidate(candidates, selected.getRealPath());
        addCandidate(candidates, selected.getOriginalPath());

        for (String candidate : candidates) {
            ensureNotCancelled(cancelProbe);
            try (InputStream input = openInput(context, candidate)) {
                if (input == null) continue;
                copiedSha256 = copySynced(input, incoming, cancelProbe);
                copied = true;
                break;
            } catch (InterruptedIOException e) {
                deleteQuietly(incoming);
                throw e;
            } catch (Exception e) {
                lastOpenError = e;
                deleteQuietly(incoming);
            }
        }

        if (!copied || !incoming.isFile() || incoming.length() <= 0L) {
            deleteQuietly(incoming);
            throw new IOException(lastOpenError == null
                ? "Selected image cannot be opened"
                : "Selected image cannot be opened: " + safeError(lastOpenError));
        }

        File normalized = incoming;
        try {
            String extension = detectImageExtension(readPrefix(incoming, 16));
            if (extension.isEmpty()) {
                normalized = transcodeToPng(incoming, cancelProbe);
                extension = "png";
                deleteQuietly(incoming);
            }

            int[] dimensions = readDimensions(normalized);
            if (dimensions[0] <= 0 || dimensions[1] <= 0) {
                throw new IOException("Selected file is not a decodable image");
            }

            String sha256 = normalized.equals(incoming)
                ? copiedSha256
                : computeSha256(normalized, cancelProbe);
            File target = new File(OBJECTS_DIR, sha256 + "." + extension);
            boolean reused = commitObject(normalized, target, sha256, cancelProbe);
            return new MaterializedImage(
                target,
                sha256,
                extension,
                Math.max(0L, target.length()),
                dimensions[0],
                dimensions[1],
                reused);
        } catch (Exception e) {
            deleteQuietly(incoming);
            if (!normalized.equals(incoming)) deleteQuietly(normalized);
            throw e;
        }
    }

    /** Keeps first-selection order and collapses only byte-identical SHA-256 content. */
    @NonNull
    static DeduplicatedBatch deduplicateByContent(@Nullable List<MaterializedImage> images) {
        LinkedHashMap<String, MaterializedImage> unique = new LinkedHashMap<>();
        int duplicateCount = 0;
        long duplicateBytes = 0L;
        if (images != null) {
            for (MaterializedImage image : images) {
                if (image == null || image.sha256.isEmpty()) continue;
                if (unique.containsKey(image.sha256)) {
                    duplicateCount++;
                    duplicateBytes = saturatedAdd(duplicateBytes, image.size);
                } else {
                    unique.put(image.sha256, image);
                }
            }
        }

        int reusedUniqueCount = 0;
        long reusedUniqueBytes = 0L;
        for (MaterializedImage image : unique.values()) {
            if (!image.reused) continue;
            reusedUniqueCount++;
            reusedUniqueBytes = saturatedAdd(reusedUniqueBytes, image.size);
        }
        return new DeduplicatedBatch(
            new ArrayList<>(unique.values()), duplicateCount, duplicateBytes,
            reusedUniqueCount, reusedUniqueBytes);
    }

    static void persistOperation(@NonNull String operationId,
                                 @NonNull String phase,
                                 @NonNull String route,
                                 @Nullable String targetHandle,
                                 @Nullable String codexThreadId,
                                 int imageCount,
                                 int selectedCount,
                                 int duplicateCount,
                                 int localReusedCount,
                                 int remoteReusedCount,
                                 long localAvoidedBytes,
                                 long remoteAvoidedBytes,
                                 long uploadedBytes,
                                 @Nullable String message,
                                 boolean terminal) {
        synchronized (LOCK) {
            JSONObject json = new JSONObject();
            putQuietly(json, "schema_version", SCHEMA_VERSION);
            putQuietly(json, "operation_id", bounded(operationId));
            putQuietly(json, "phase", bounded(phase));
            putQuietly(json, "route", bounded(route));
            putQuietly(json, "target_handle", bounded(targetHandle));
            putQuietly(json, "codex_thread_id", bounded(codexThreadId));
            putQuietly(json, "image_count", Math.max(0, imageCount));
            putQuietly(json, "selected_count", Math.max(0, selectedCount));
            putQuietly(json, "duplicate_count", Math.max(0, duplicateCount));
            putQuietly(json, "local_reused_count", Math.max(0, localReusedCount));
            putQuietly(json, "remote_reused_count", Math.max(0, remoteReusedCount));
            putQuietly(json, "local_avoided_bytes", Math.max(0L, localAvoidedBytes));
            putQuietly(json, "remote_avoided_bytes", Math.max(0L, remoteAvoidedBytes));
            putQuietly(json, "uploaded_bytes", Math.max(0L, uploadedBytes));
            putQuietly(json, "message", bounded(redact(message)));
            putQuietly(json, "updated_at_ms", System.currentTimeMillis());
            writeJsonAtomicallyLocked(terminal ? LAST_STATE : ACTIVE_STATE, json);
            if (terminal) deleteQuietly(ACTIVE_STATE);
        }
    }

    static void appendEvent(@NonNull String operationId,
                            @NonNull String event,
                            @NonNull String phase,
                            @NonNull String route,
                            int imageCount,
                            @Nullable String detail) {
        synchronized (LOCK) {
            appendEventLocked(operationId, event, phase, route, imageCount, detail);
        }
    }

    @NonNull
    static String detectImageExtension(@Nullable byte[] prefix) {
        if (prefix == null) return "";
        if (prefix.length >= 8 &&
            (prefix[0] & 0xff) == 0x89 && prefix[1] == 'P' && prefix[2] == 'N' && prefix[3] == 'G' &&
            prefix[4] == 0x0d && prefix[5] == 0x0a && prefix[6] == 0x1a && prefix[7] == 0x0a) {
            return "png";
        }
        if (prefix.length >= 3 && (prefix[0] & 0xff) == 0xff &&
            (prefix[1] & 0xff) == 0xd8 && (prefix[2] & 0xff) == 0xff) {
            return "jpg";
        }
        if (prefix.length >= 6 && prefix[0] == 'G' && prefix[1] == 'I' && prefix[2] == 'F' &&
            prefix[3] == '8' && (prefix[4] == '7' || prefix[4] == '9') && prefix[5] == 'a') {
            return "gif";
        }
        if (prefix.length >= 12 && prefix[0] == 'R' && prefix[1] == 'I' && prefix[2] == 'F' &&
            prefix[3] == 'F' && prefix[8] == 'W' && prefix[9] == 'E' && prefix[10] == 'B' && prefix[11] == 'P') {
            return "webp";
        }
        return "";
    }

    private static boolean commitObject(@NonNull File source,
                                        @NonNull File target,
                                        @NonNull String expectedSha256,
                                        @Nullable CancelProbe cancelProbe) throws Exception {
        Object objectLock = OBJECT_LOCKS[(expectedSha256.hashCode() & Integer.MAX_VALUE) % OBJECT_LOCKS.length];
        synchronized (objectLock) {
            ensureNotCancelled(cancelProbe);
            int targetType = noFollowPathType(target);
            if (targetType == PATH_REGULAR) {
                String actual = computeSha256(target, cancelProbe);
                if (expectedSha256.equals(actual)) {
                    setPrivateFilePermissions(target);
                    deleteQuietly(source);
                    return true;
                }
                File corrupt = new File(target.getAbsolutePath() + ".corrupt-" + System.currentTimeMillis());
                Os.rename(target.getAbsolutePath(), corrupt.getAbsolutePath());
                syncDirectory(OBJECTS_DIR);
            } else if (targetType == PATH_SYMLINK) {
                File corrupt = new File(target.getAbsolutePath() + ".symlink-" + System.currentTimeMillis());
                Os.rename(target.getAbsolutePath(), corrupt.getAbsolutePath());
                syncDirectory(OBJECTS_DIR);
            } else if (targetType != PATH_MISSING) {
                throw new IOException("Content-addressed image target is not a regular file");
            }

            if (noFollowPathType(source) != PATH_REGULAR) {
                throw new IOException("Staged image object is not a regular file");
            }
            setPrivateFilePermissions(source);
            try {
                Os.rename(source.getAbsolutePath(), target.getAbsolutePath());
            } catch (Exception renameError) {
                if (noFollowPathType(target) == PATH_REGULAR &&
                    expectedSha256.equals(computeSha256(target, cancelProbe))) {
                    deleteQuietly(source);
                    return true;
                }
                throw new IOException("Atomic image object commit failed", renameError);
            }
            if (noFollowPathType(target) != PATH_REGULAR) {
                throw new IOException("Committed image object is not a regular file");
            }
            setPrivateFilePermissions(target);
            syncDirectory(OBJECTS_DIR);
            String committedSha = computeSha256(target, cancelProbe);
            if (!expectedSha256.equals(committedSha)) {
                File corrupt = new File(target.getAbsolutePath() + ".corrupt-commit-" + System.currentTimeMillis());
                try {
                    Os.rename(target.getAbsolutePath(), corrupt.getAbsolutePath());
                    syncDirectory(OBJECTS_DIR);
                } catch (Exception ignored) {
                    deleteQuietly(target);
                }
                throw new IOException("Committed image digest mismatch");
            }
            return false;
        }
    }

    @NonNull
    private static File transcodeToPng(@NonNull File source,
                                       @Nullable CancelProbe cancelProbe) throws Exception {
        ensureNotCancelled(cancelProbe);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Unsupported image format");
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, TRANSCODE_MAX_DIMENSION);
        Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
        if (bitmap == null) throw new IOException("Image decode failed");

        File output = new File(OBJECTS_DIR, ".normalized-" + UUID.randomUUID() + ".part");
        try (FileOutputStream out = new FileOutputStream(output, false)) {
            ensureNotCancelled(cancelProbe);
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("Image PNG conversion failed");
            }
            out.flush();
            out.getFD().sync();
        } finally {
            bitmap.recycle();
        }
        if (output.length() <= 0L || output.length() > MAX_INPUT_BYTES) {
            deleteQuietly(output);
            throw new IOException("Converted image size is invalid");
        }
        return output;
    }

    private static int calculateSampleSize(int width, int height, int maxDimension) {
        int sample = 1;
        while (Math.max(width / sample, height / sample) > maxDimension && sample <= 64) {
            sample *= 2;
        }
        return Math.max(1, sample);
    }

    @NonNull
    private static int[] readDimensions(@NonNull File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        return new int[]{Math.max(0, bounds.outWidth), Math.max(0, bounds.outHeight)};
    }

    @Nullable
    private static InputStream openInput(@NonNull Context context, @NonNull String raw) throws Exception {
        String value = raw.trim();
        if (value.isEmpty()) return null;
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if ("content".equalsIgnoreCase(scheme) || "android.resource".equalsIgnoreCase(scheme)) {
            return context.getContentResolver().openInputStream(uri);
        }
        if ("file".equalsIgnoreCase(scheme)) {
            String path = uri.getPath();
            return TextUtils.isEmpty(path) ? null : new FileInputStream(path);
        }
        if (!TextUtils.isEmpty(scheme)) return null;
        return new FileInputStream(value);
    }

    @NonNull
    private static String copySynced(@NonNull InputStream input,
                                     @NonNull File output,
                                     @Nullable CancelProbe cancelProbe) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0L;
        try (FileOutputStream out = new FileOutputStream(output, false)) {
            byte[] buffer = new byte[64 * 1024];
            while (true) {
                ensureNotCancelled(cancelProbe);
                int read = input.read(buffer);
                if (read < 0) break;
                if (read == 0) continue;
                total += read;
                if (total > MAX_INPUT_BYTES) throw new IOException("Image exceeds Codex 1 GiB input limit");
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
            out.flush();
            out.getFD().sync();
        }
        if (total <= 0L) throw new IOException("Selected image is empty");
        setPrivateFilePermissions(output);
        return toHex(digest.digest());
    }

    @NonNull
    private static String computeSha256(@NonNull File file,
                                        @Nullable CancelProbe cancelProbe) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            while (true) {
                ensureNotCancelled(cancelProbe);
                int read = input.read(buffer);
                if (read < 0) break;
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    @NonNull
    private static byte[] readPrefix(@NonNull File file, int maxBytes) throws IOException {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream(maxBytes)) {
            byte[] buffer = new byte[Math.max(1, maxBytes)];
            int read = input.read(buffer);
            if (read > 0) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static void ensureNotCancelled(@Nullable CancelProbe cancelProbe) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || (cancelProbe != null && cancelProbe.isCancelled())) {
            throw new InterruptedIOException("Image attachment operation cancelled");
        }
    }

    private static void appendEventLocked(@NonNull String operationId,
                                          @NonNull String event,
                                          @NonNull String phase,
                                          @NonNull String route,
                                          int imageCount,
                                          @Nullable String detail) {
        ensurePrivateDirectory(LOG_DIR);
        rotateLogsLocked();
        try {
            JSONObject json = new JSONObject();
            json.put("schema_version", SCHEMA_VERSION);
            json.put("timestamp_ms", System.currentTimeMillis());
            json.put("operation_id", bounded(operationId));
            json.put("event", bounded(event));
            json.put("phase", bounded(phase));
            json.put("route", bounded(route));
            json.put("image_count", Math.max(0, imageCount));
            json.put("detail", bounded(redact(detail)));
            byte[] bytes = (json.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            boolean created = !EVENT_LOG.exists();
            try (FileOutputStream out = new FileOutputStream(EVENT_LOG, true)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }
            if (created) {
                setPrivateFilePermissions(EVENT_LOG);
                syncDirectory(LOG_DIR);
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed appending attachment audit: " + safeError(e));
        }
    }

    private static void rotateLogsLocked() {
        if (!EVENT_LOG.isFile() || EVENT_LOG.length() < MAX_LOG_BYTES) return;
        for (int index = MAX_LOG_ARCHIVES; index >= 1; index--) {
            File source = index == 1 ? EVENT_LOG : new File(EVENT_LOG.getAbsolutePath() + "." + (index - 1));
            File target = new File(EVENT_LOG.getAbsolutePath() + "." + index);
            if (!source.exists()) continue;
            if (target.exists()) deleteQuietly(target);
            try {
                Os.rename(source.getAbsolutePath(), target.getAbsolutePath());
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Failed rotating attachment log: " + safeError(e));
            }
        }
        syncDirectory(LOG_DIR);
    }

    private static void writeJsonAtomicallyLocked(@NonNull File target, @NonNull JSONObject json) {
        ensurePrivateDirectory(target.getParentFile());
        File temp = new File(target.getAbsolutePath() + ".tmp");
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
            setPrivateFilePermissions(temp);
            Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
            syncDirectory(target.getParentFile());
        } catch (Exception e) {
            deleteQuietly(temp);
            Logger.logWarn(LOG_TAG, "Failed writing attachment state: " + safeError(e));
        }
    }

    @Nullable
    private static JSONObject readJsonObject(@NonNull File file) {
        if (!file.isFile() || file.length() <= 0L || file.length() > 1024L * 1024L) return null;
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void cleanupPartFilesLocked(@NonNull File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file != null && file.isFile() && file.getName().endsWith(".part")) {
                deleteQuietly(file);
            }
        }
    }

    private static void ensurePrivateDirectory(@Nullable File directory) {
        if (directory == null) return;
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) return;
        directory.setReadable(false, false);
        directory.setWritable(false, false);
        directory.setExecutable(false, false);
        directory.setReadable(true, true);
        directory.setWritable(true, true);
        directory.setExecutable(true, true);
    }

    private static void setPrivateFilePermissions(@NonNull File file) {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    private static void syncDirectory(@Nullable File directory) {
        if (directory == null || !directory.isDirectory()) return;
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY, 0);
            Os.fsync(descriptor);
        } catch (Throwable ignored) {
        } finally {
            if (descriptor != null) {
                try {
                    Os.close(descriptor);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void addCandidate(@NonNull LinkedHashSet<String> candidates, @Nullable String raw) {
        if (raw == null) return;
        String value = raw.trim();
        if (!value.isEmpty()) candidates.add(value);
    }

    private static int noFollowPathType(@NonNull File file) throws IOException {
        try {
            StructStat stat = Os.lstat(file.getAbsolutePath());
            if (OsConstants.S_ISREG(stat.st_mode)) return PATH_REGULAR;
            if (OsConstants.S_ISLNK(stat.st_mode)) return PATH_SYMLINK;
            return PATH_OTHER;
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT) return PATH_MISSING;
            throw new IOException("Cannot inspect image object path", e);
        }
    }

    private static long saturatedAdd(long first, long second) {
        if (first >= Long.MAX_VALUE - Math.max(0L, second)) return Long.MAX_VALUE;
        return first + Math.max(0L, second);
    }

    private static void putQuietly(@NonNull JSONObject json, @NonNull String key, Object value) {
        try {
            json.put(key, value == null ? JSONObject.NULL : value);
        } catch (Exception ignored) {
        }
    }

    private static void deleteQuietly(@Nullable File file) {
        if (file == null || !file.exists()) return;
        try {
            file.delete();
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    private static String redact(@Nullable String raw) {
        if (raw == null) return "";
        return raw
            .replaceAll("(?i)(sshpass\\s+-p\\s+)(?:'[^']*'|\"[^\"]*\"|\\S+)", "$1<redacted>")
            .replaceAll("(?i)(password|token|authorization)(\\s*[=:]\\s*)(?:'[^']*'|\"[^\"]*\"|\\S+)", "$1$2<redacted>");
    }

    @NonNull
    private static String bounded(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim();
        return value.length() <= MAX_LOG_TEXT ? value : value.substring(0, MAX_LOG_TEXT);
    }

    @NonNull
    private static String safeError(@Nullable Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return bounded(redact(TextUtils.isEmpty(message) ? error.getClass().getSimpleName() : message));
    }

    @NonNull
    private static String toHex(@NonNull byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.US, "%02x", value));
        return out.toString();
    }
}
