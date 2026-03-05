package com.termux.sessionsync;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class FileRootResolver {

    public static final String SFTP_MOUNT_RELATIVE_ROOT = ".termux/sftp-mounts";
    public static final String SFTP_VIRTUAL_RELATIVE_ROOT = ".termux/sftp-virtual";
    public static final String SFTP_CACHE_RELATIVE_ROOT = ".termux/sftp-cache";

    private FileRootResolver() {
    }

    @NonNull
    public static String termuxPrivateRoot(@NonNull Context context) {
        return context.getFilesDir().getAbsolutePath().trim();
    }

    @NonNull
    public static String resolveBrowseRoot(@NonNull Context context, @Nullable SessionEntry entry) {
        String privateRoot = termuxPrivateRoot(context);
        if (entry == null) return privateRoot;
        if (entry.transport == SessionTransport.LOCAL) return privateRoot;

        String key = sessionPathKey(entry);
        return privateRoot + "/" + SFTP_MOUNT_RELATIVE_ROOT + "/" + key;
    }

    @NonNull
    public static String resolveVirtualRoot(@NonNull Context context, @NonNull SessionEntry entry) {
        return termuxPrivateRoot(context) + "/" + SFTP_VIRTUAL_RELATIVE_ROOT + "/" + sessionPathKey(entry);
    }

    @NonNull
    public static String resolveCacheRoot(@NonNull Context context, @NonNull SessionEntry entry) {
        return termuxPrivateRoot(context) + "/" + SFTP_CACHE_RELATIVE_ROOT + "/" + sessionPathKey(entry);
    }

    @NonNull
    public static String sessionPathKey(@NonNull SessionEntry entry) {
        if (SavedSshProfileStore.isProfileEntry(entry)) {
            return sessionPathKey(entry.id);
        }

        StringBuilder seed = new StringBuilder();
        if (!TextUtils.isEmpty(entry.sshCommand)) {
            seed.append(entry.sshCommand);
        } else {
            seed.append(entry.id);
        }
        if (!TextUtils.isEmpty(entry.tmuxSession)) {
            seed.append('\n').append(entry.tmuxSession);
        }

        return "session-" + shortSha1(seed.toString());
    }

    @NonNull
    public static String sessionPathKey(@NonNull String raw) {
        String out = raw.trim();
        if (out.isEmpty()) return "session";
        out = out.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (out.length() > 80) out = out.substring(0, 80);
        return out;
    }

    @NonNull
    private static String shortSha1(@NonNull String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] data = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(data.length * 2);
            for (byte b : data) {
                hex.append(String.format("%02x", b));
            }
            if (hex.length() > 12) return hex.substring(0, 12);
            return hex.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
