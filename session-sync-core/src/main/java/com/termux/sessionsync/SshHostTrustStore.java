package com.termux.sessionsync;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.UserInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SshHostTrustStore implements HostKeyRepository {

    private static final String FILE_NAME = "known-hosts.json";
    private static final SshHostTrustStore INSTANCE = new SshHostTrustStore();

    private final Object lock = new Object();
    @Nullable
    private Context appContext;
    @Nullable
    private File storeFile;
    @NonNull
    private final LinkedHashMap<String, HostRecord> records = new LinkedHashMap<>();
    private boolean loaded;

    private SshHostTrustStore() {
    }

    @NonNull
    static SshHostTrustStore getInstance() {
        return INSTANCE;
    }

    void initialize(@NonNull Context context) {
        synchronized (lock) {
            appContext = context.getApplicationContext();
            File root = new File(FileRootResolver.resolveTransferRoot(appContext));
            if (!root.exists() && !root.mkdirs() && !root.exists()) {
                return;
            }
            storeFile = new File(root, FILE_NAME);
            ensureLoadedLocked();
        }
    }

    @Override
    public int check(String host, byte[] key) {
        if (key == null || key.length == 0) {
            return NOT_INCLUDED;
        }

        synchronized (lock) {
            ensureLoadedLocked();
            String normalizedHost = normalizeHost(host);
            if (normalizedHost.isEmpty()) {
                return NOT_INCLUDED;
            }

            String keyBase64 = Base64.encodeToString(key, Base64.NO_WRAP);
            HostKey hostKey = createHostKey(normalizedHost, key);
            String type = hostKey == null ? "unknown" : safe(hostKey.getType());
            String recordKey = buildRecordKey(normalizedHost, type);
            HostRecord existing = records.get(recordKey);
            if (existing == null) {
                HostRecord trusted = new HostRecord(
                    normalizedHost,
                    type,
                    keyBase64,
                    fingerprintSha256(key),
                    System.currentTimeMillis(),
                    System.currentTimeMillis()
                );
                records.put(recordKey, trusted);
                persistLocked();
                traceInfo("trust-first-use", normalizedHost, trusted.fingerprint);
                return OK;
            }

            if (TextUtils.equals(existing.keyBase64, keyBase64)) {
                existing.lastSeenAtMs = System.currentTimeMillis();
                persistLocked();
                return OK;
            }

            traceWarn(
                "host-key-changed",
                normalizedHost,
                "stored=" + existing.fingerprint + " incoming=" + fingerprintSha256(key)
            );
            return CHANGED;
        }
    }

    @Override
    public void add(HostKey hostkey, UserInfo ui) {
        if (hostkey == null) return;
        synchronized (lock) {
            ensureLoadedLocked();
            String normalizedHost = normalizeHost(hostkey.getHost());
            if (normalizedHost.isEmpty()) {
                return;
            }
            String type = safe(hostkey.getType());
            String keyBase64 = safe(hostkey.getKey());
            if (keyBase64.isEmpty()) {
                return;
            }
            String recordKey = buildRecordKey(normalizedHost, type);
            HostRecord existing = records.get(recordKey);
            if (existing != null && TextUtils.equals(existing.keyBase64, keyBase64)) {
                existing.lastSeenAtMs = System.currentTimeMillis();
                persistLocked();
                return;
            }
            HostRecord trusted = new HostRecord(
                normalizedHost,
                type,
                keyBase64,
                fingerprintSha256(decodeBase64(keyBase64)),
                System.currentTimeMillis(),
                System.currentTimeMillis()
            );
            records.put(recordKey, trusted);
            persistLocked();
        }
    }

    @Override
    public void remove(String host, String type) {
        synchronized (lock) {
            ensureLoadedLocked();
            String normalizedHost = normalizeHost(host);
            if (normalizedHost.isEmpty()) {
                return;
            }
            if (TextUtils.isEmpty(type)) {
                ArrayList<String> toRemove = new ArrayList<>();
                for (Map.Entry<String, HostRecord> item : records.entrySet()) {
                    HostRecord record = item.getValue();
                    if (record != null && TextUtils.equals(record.host, normalizedHost)) {
                        toRemove.add(item.getKey());
                    }
                }
                for (String keyItem : toRemove) {
                    records.remove(keyItem);
                }
            } else {
                records.remove(buildRecordKey(normalizedHost, type));
            }
            persistLocked();
        }
    }

    @Override
    public void remove(String host, String type, byte[] key) {
        synchronized (lock) {
            ensureLoadedLocked();
            String normalizedHost = normalizeHost(host);
            if (normalizedHost.isEmpty()) {
                return;
            }
            String normalizedType = safe(type);
            String keyBase64 = key == null ? "" : Base64.encodeToString(key, Base64.NO_WRAP);
            HostRecord record = records.get(buildRecordKey(normalizedHost, normalizedType));
            if (record != null && TextUtils.equals(record.keyBase64, keyBase64)) {
                records.remove(buildRecordKey(normalizedHost, normalizedType));
                persistLocked();
            }
        }
    }

    @Override
    @Nullable
    public String getKnownHostsRepositoryID() {
        synchronized (lock) {
            return storeFile == null ? null : storeFile.getAbsolutePath();
        }
    }

    @Override
    @NonNull
    public HostKey[] getHostKey() {
        synchronized (lock) {
            ensureLoadedLocked();
            ArrayList<HostKey> out = new ArrayList<>(records.size());
            for (HostRecord record : records.values()) {
                HostKey hostKey = record.toHostKey();
                if (hostKey != null) out.add(hostKey);
            }
            return out.toArray(new HostKey[0]);
        }
    }

    @Override
    @NonNull
    public HostKey[] getHostKey(String host, String type) {
        synchronized (lock) {
            ensureLoadedLocked();
            String normalizedHost = normalizeHost(host);
            String normalizedType = safe(type);
            ArrayList<HostKey> out = new ArrayList<>();
            for (HostRecord record : records.values()) {
                if (record == null) continue;
                if (!normalizedHost.isEmpty() && !TextUtils.equals(record.host, normalizedHost)) continue;
                if (!normalizedType.isEmpty() && !TextUtils.equals(record.type, normalizedType)) continue;
                HostKey hostKey = record.toHostKey();
                if (hostKey != null) out.add(hostKey);
            }
            return out.toArray(new HostKey[0]);
        }
    }

    private void ensureLoadedLocked() {
        if (loaded) return;
        loaded = true;
        records.clear();
        if (storeFile == null || !storeFile.exists()) {
            return;
        }

        try {
            String raw = readTextFile(storeFile);
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                HostRecord record = HostRecord.fromJson(item);
                if (record == null) continue;
                records.put(buildRecordKey(record.host, record.type), record);
            }
        } catch (Throwable ignored) {
            records.clear();
        }
    }

    private void persistLocked() {
        if (storeFile == null) return;
        JSONArray array = new JSONArray();
        for (HostRecord record : records.values()) {
            if (record == null) continue;
            array.put(record.toJson());
        }
        try (FileOutputStream outputStream = new FileOutputStream(storeFile, false)) {
            outputStream.write(array.toString().getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            try {
                outputStream.getFD().sync();
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    private static String buildRecordKey(@NonNull String host, @NonNull String type) {
        return normalizeHost(host) + "|" + safe(type).toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static String normalizeHost(@Nullable String host) {
        if (host == null) return "";
        String out = host.trim().toLowerCase(Locale.ROOT);
        if (out.startsWith("[") && out.endsWith("]") && !out.contains("]:")) {
            out = out.substring(1, out.length() - 1);
        }
        return out;
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    @Nullable
    private static HostKey createHostKey(@NonNull String host, @NonNull byte[] key) {
        try {
            return new HostKey(host, key);
        } catch (JSchException ignored) {
            return null;
        }
    }

    @NonNull
    private static String fingerprintSha256(@Nullable byte[] rawKey) {
        if (rawKey == null || rawKey.length == 0) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] sha = digest.digest(rawKey);
            return "sha256:" + Base64.encodeToString(sha, Base64.NO_WRAP);
        } catch (Throwable ignored) {
            return "";
        }
    }

    @Nullable
    private static byte[] decodeBase64(@Nullable String value) {
        if (TextUtils.isEmpty(value)) return null;
        try {
            return Base64.decode(value, Base64.DEFAULT);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void traceInfo(@NonNull String action, @Nullable String sessionKey, @Nullable String detail) {
        Context context = appContext;
        SessionSyncTracer.getInstance().info(context, "SshHostTrustStore", action, sessionKey,
            "\u5df2\u4fe1\u4efb\u4e3b\u673a\u6307\u7eb9", detail);
    }

    private void traceWarn(@NonNull String action, @Nullable String sessionKey, @Nullable String detail) {
        Context context = appContext;
        SessionSyncTracer.getInstance().warn(context, "SshHostTrustStore", action, sessionKey,
            "\u4e3b\u673a\u6307\u7eb9\u53d1\u751f\u53d8\u66f4", detail);
    }

    @NonNull
    private static String readTextFile(@NonNull File file) throws Exception {
        try (FileInputStream inputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.max(0L, Math.min(file.length(), 1024L * 1024L))];
            int offset = 0;
            while (offset < buffer.length) {
                int read = inputStream.read(buffer, offset, buffer.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return new String(buffer, 0, offset, StandardCharsets.UTF_8);
        }
    }

    private static final class HostRecord {
        @NonNull
        final String host;
        @NonNull
        final String type;
        @NonNull
        final String keyBase64;
        @NonNull
        final String fingerprint;
        final long trustedAtMs;
        long lastSeenAtMs;

        HostRecord(@NonNull String host,
                   @NonNull String type,
                   @NonNull String keyBase64,
                   @NonNull String fingerprint,
                   long trustedAtMs,
                   long lastSeenAtMs) {
            this.host = normalizeHost(host);
            this.type = safe(type);
            this.keyBase64 = safe(keyBase64);
            this.fingerprint = safe(fingerprint);
            this.trustedAtMs = Math.max(0L, trustedAtMs);
            this.lastSeenAtMs = Math.max(0L, lastSeenAtMs);
        }

        @Nullable
        static HostRecord fromJson(@Nullable JSONObject json) {
            if (json == null) return null;
            String host = normalizeHost(json.optString("host", ""));
            String type = safe(json.optString("type", ""));
            String keyBase64 = safe(json.optString("keyBase64", ""));
            if (host.isEmpty() || type.isEmpty() || keyBase64.isEmpty()) {
                return null;
            }
            return new HostRecord(
                host,
                type,
                keyBase64,
                safe(json.optString("fingerprint", "")),
                json.optLong("trustedAtMs", 0L),
                json.optLong("lastSeenAtMs", 0L)
            );
        }

        @NonNull
        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("host", host);
                json.put("type", type);
                json.put("keyBase64", keyBase64);
                json.put("fingerprint", fingerprint);
                json.put("trustedAtMs", trustedAtMs);
                json.put("lastSeenAtMs", lastSeenAtMs);
            } catch (Throwable ignored) {
            }
            return json;
        }

        @Nullable
        HostKey toHostKey() {
            byte[] rawKey = decodeBase64(keyBase64);
            if (rawKey == null || rawKey.length == 0) {
                return null;
            }
            return createHostKey(host, rawKey);
        }
    }
}
