package com.tencent.shadow.sample.host.platform;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

public final class ShadowFileOps {

    private static final int BUFFER_SIZE = 64 * 1024;

    private ShadowFileOps() {
    }

    public static void ensurePrivateDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Failed to create directory: " + directory);
        }
        chmod(directory, 0700);
    }

    public static void copyAtomically(File source, File target, boolean readOnly) throws IOException {
        if (!source.isFile() || !source.canRead()) {
            throw new IOException("Source is not readable: " + source);
        }
        ensurePrivateDirectory(target.getParentFile());
        File temporary = new File(
                target.getParentFile(),
                "." + target.getName() + "." + UUID.randomUUID() + ".tmp"
        );
        boolean committed = false;
        try {
            copyAndSync(source, temporary);
            chmod(temporary, readOnly ? 0400 : 0600);
            atomicReplace(temporary, target);
            chmod(target, readOnly ? 0400 : 0600);
            syncDirectory(target.getParentFile());
            committed = true;
        } finally {
            if (!committed && temporary.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
        }
    }

    public static void writeAtomically(File target, byte[] bytes, boolean readOnly) throws IOException {
        ensurePrivateDirectory(target.getParentFile());
        File temporary = new File(
                target.getParentFile(),
                "." + target.getName() + "." + UUID.randomUUID() + ".tmp"
        );
        boolean committed = false;
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(temporary);
            output.write(bytes);
            output.flush();
            output.getFD().sync();
            closeQuietly(output);
            output = null;
            chmod(temporary, readOnly ? 0400 : 0600);
            atomicReplace(temporary, target);
            chmod(target, readOnly ? 0400 : 0600);
            syncDirectory(target.getParentFile());
            committed = true;
        } finally {
            closeQuietly(output);
            if (!committed && temporary.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
        }
    }

    public static String sha256(File file) throws IOException {
        return digest(file, "SHA-256");
    }

    public static String md5(File file) throws IOException {
        return digest(file, "MD5");
    }

    public static byte[] readBounded(File file, int maxBytes) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[Math.min(BUFFER_SIZE, maxBytes)];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("File exceeds " + maxBytes + " bytes: " + file);
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            closeQuietly(input);
        }
    }

    public static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                throw new IOException("Failed to list directory: " + file);
            }
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete() && file.exists()) {
            throw new IOException("Failed to delete: " + file);
        }
    }

    public static String safeSegment(String value) {
        String safe = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() == 0) {
            return "unknown";
        }
        return safe.length() > 128 ? safe.substring(0, 128) : safe;
    }

    private static void copyAndSync(File source, File target) throws IOException {
        InputStream input = null;
        FileOutputStream output = null;
        try {
            input = new FileInputStream(source);
            output = new FileOutputStream(target);
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
            output.getFD().sync();
        } finally {
            closeQuietly(input);
            closeQuietly(output);
        }
    }

    private static String digest(File file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            InputStream input = null;
            try {
                input = new FileInputStream(file);
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            } finally {
                closeQuietly(input);
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static void atomicReplace(File source, File target) throws IOException {
        try {
            Os.rename(source.getAbsolutePath(), target.getAbsolutePath());
        } catch (ErrnoException e) {
            if (target.exists() && !target.delete()) {
                throw new IOException("Failed to replace target: " + target, e);
            }
            if (!source.renameTo(target)) {
                throw new IOException("Failed to atomically move " + source + " to " + target, e);
            }
        }
    }

    private static void syncDirectory(File directory) {
        java.io.FileDescriptor descriptor = null;
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

    private static void chmod(File file, int mode) throws IOException {
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (ErrnoException e) {
            throw new IOException("Failed to chmod " + file, e);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
