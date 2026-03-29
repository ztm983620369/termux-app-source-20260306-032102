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
import com.termux.sshconnectioncore.ResolvedSshEndpoint;
import com.termux.sshconnectioncore.SshKnownHostsFiles;
import com.termux.sshconnectioncore.SshPendingTrustRecord;
import com.termux.sshconnectioncore.SshTrustRecord;
import com.termux.sshconnectioncore.SshTrustSource;
import com.termux.sshconnectioncore.SshTrustStateMachine;

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
    private static final String PENDING_FILE_NAME = "pending-known-hosts.json";
    private static final SshHostTrustStore INSTANCE = new SshHostTrustStore();

    private final Object lock = new Object();
    @Nullable
    private Context appContext;
    @Nullable
    private File storeFile;
    @Nullable
    private File pendingStoreFile;
    @Nullable
    private File managedKnownHostsFile;
    @Nullable
    private File legacyKnownHostsFile;
    @Nullable
    private ResolvedSshEndpoint activeEndpoint;
    @NonNull
    private final LinkedHashMap<String, HostRecord> records = new LinkedHashMap<>();
    @NonNull
    private final LinkedHashMap<String, PendingRecord> pendingRecords = new LinkedHashMap<>();
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
            pendingStoreFile = new File(root, PENDING_FILE_NAME);
            managedKnownHostsFile = SshKnownHostsFiles.resolveManagedKnownHostsFile(appContext);
            legacyKnownHostsFile = SshKnownHostsFiles.resolveLegacyUserKnownHostsFile(appContext);
            ensureLoadedLocked();
        }
    }

    void setActiveEndpoint(@Nullable ResolvedSshEndpoint endpoint) {
        synchronized (lock) {
            activeEndpoint = endpoint;
        }
    }

    void clearActiveEndpoint() {
        synchronized (lock) {
            activeEndpoint = null;
        }
    }

    @Override
    public int check(String host, byte[] key) {
        if (key == null || key.length == 0) {
            return NOT_INCLUDED;
        }

        synchronized (lock) {
            ensureLoadedLocked();
            HostKey hostKey = createHostKey(normalizeHostForHostKey(host), key);
            String algorithm = hostKey == null ? "unknown" : safe(hostKey.getType());
            String fingerprint = fingerprintSha256(key);
            String keyBase64 = Base64.encodeToString(key, Base64.NO_WRAP);
            ResolvedSshEndpoint endpoint = resolveEndpointLocked(host);

            HostRecord existing = records.get(buildRecordKey(endpoint.authorityKey, algorithm));
            SshTrustStateMachine machine = new SshTrustStateMachine();
            machine.apply(SshTrustStateMachine.Event.beginEvaluation(endpoint,
                existing == null ? null : existing.toTrustRecord(), System.currentTimeMillis()));
            machine.apply(SshTrustStateMachine.Event.observeHostKey(algorithm, fingerprint, System.currentTimeMillis()));

            SshTrustStateMachine.Snapshot snapshot = machine.snapshot();
            if (snapshot.state == SshTrustStateMachine.State.TRUST_MATCHED && snapshot.effectiveRecord != null) {
                upsertRecordLocked(snapshot.effectiveRecord, endpoint.host, keyBase64);
                clearPendingAuthorityLocked(endpoint.authorityKey);
                return OK;
            }

            if (snapshot.state == SshTrustStateMachine.State.TRUST_PENDING_APPROVAL) {
                pendingRecords.put(
                    endpoint.authorityKey,
                    new PendingRecord(
                        endpoint.authorityKey,
                        endpoint.hostIdentity,
                        endpoint.port,
                        algorithm,
                        keyBase64,
                        fingerprint,
                        "",
                        false,
                        System.currentTimeMillis()
                    )
                );
                persistPendingLocked();
            }

            if (snapshot.state == SshTrustStateMachine.State.TRUST_CONFLICT) {
                pendingRecords.put(
                    endpoint.authorityKey,
                    new PendingRecord(
                        endpoint.authorityKey,
                        endpoint.hostIdentity,
                        endpoint.port,
                        algorithm,
                        keyBase64,
                        fingerprint,
                        existing == null ? "" : existing.fingerprintSha256,
                        true,
                        System.currentTimeMillis()
                    )
                );
                persistPendingLocked();
                traceWarn("host-key-changed", endpoint.authorityKey, snapshot.detail);
                return CHANGED;
            }

            return NOT_INCLUDED;
        }
    }

    @Override
    public void add(HostKey hostkey, UserInfo ui) {
        if (hostkey == null) return;
        synchronized (lock) {
            ensureLoadedLocked();
            ResolvedSshEndpoint endpoint = resolveEndpointLocked(hostkey.getHost());
            String algorithm = safe(hostkey.getType());
            String keyBase64 = safe(hostkey.getKey());
            if (algorithm.isEmpty() || keyBase64.isEmpty()) return;

            SshTrustRecord record = new SshTrustRecord(
                endpoint.authorityKey,
                endpoint.hostIdentity,
                endpoint.port,
                algorithm,
                fingerprintSha256(decodeBase64(keyBase64)),
                SshTrustSource.LEGACY_AUTO_TRUSTED,
                System.currentTimeMillis(),
                System.currentTimeMillis()
            );
            upsertRecordLocked(record, endpoint.host, keyBase64);
            clearPendingAuthorityLocked(endpoint.authorityKey);
        }
    }

    @Override
    public void remove(String host, String type) {
        synchronized (lock) {
            ensureLoadedLocked();
            ResolvedSshEndpoint endpoint = resolveEndpointLocked(host);
            String normalizedType = safe(type);
            if (normalizedType.isEmpty()) {
                ArrayList<String> toRemove = new ArrayList<>();
                for (Map.Entry<String, HostRecord> item : records.entrySet()) {
                    HostRecord record = item.getValue();
                    if (record != null && (
                        TextUtils.equals(record.authorityKey, endpoint.authorityKey)
                            || TextUtils.equals(record.host, normalizeHostForHostKey(host))
                    )) {
                        toRemove.add(item.getKey());
                    }
                }
                for (String keyItem : toRemove) {
                    records.remove(keyItem);
                }
            } else {
                records.remove(buildRecordKey(endpoint.authorityKey, normalizedType));
            }
            persistLocked();
        }
    }

    @Override
    public void remove(String host, String type, byte[] key) {
        synchronized (lock) {
            ensureLoadedLocked();
            ResolvedSshEndpoint endpoint = resolveEndpointLocked(host);
            String normalizedType = safe(type);
            String keyBase64 = key == null ? "" : Base64.encodeToString(key, Base64.NO_WRAP);
            HostRecord record = records.get(buildRecordKey(endpoint.authorityKey, normalizedType));
            if (record != null && TextUtils.equals(record.keyBase64, keyBase64)) {
                records.remove(buildRecordKey(endpoint.authorityKey, normalizedType));
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
            String normalizedHost = normalizeHostForHostKey(host);
            ResolvedSshEndpoint endpoint = resolveEndpointLocked(host);
            String normalizedType = safe(type);
            ArrayList<HostKey> out = new ArrayList<>();
            for (HostRecord record : records.values()) {
                if (record == null) continue;
                if (!normalizedHost.isEmpty()
                    && !TextUtils.equals(record.host, normalizedHost)
                    && !TextUtils.equals(record.authorityKey, endpoint.authorityKey)) {
                    continue;
                }
                if (!normalizedType.isEmpty() && !TextUtils.equals(record.type, normalizedType)) continue;
                HostKey hostKey = record.toHostKey();
                if (hostKey != null) out.add(hostKey);
            }
            return out.toArray(new HostKey[0]);
        }
    }

    @Nullable
    SshTrustRecord findByAuthority(@Nullable String authorityKey, @Nullable String algorithm) {
        synchronized (lock) {
            ensureLoadedLocked();
            if (TextUtils.isEmpty(authorityKey) || TextUtils.isEmpty(algorithm)) return null;
            HostRecord record = records.get(buildRecordKey(authorityKey, algorithm));
            return record == null ? null : record.toTrustRecord();
        }
    }

    @NonNull
    List<SshTrustRecord> snapshotRecords() {
        synchronized (lock) {
            ensureLoadedLocked();
            ArrayList<SshTrustRecord> out = new ArrayList<>(records.size());
            for (HostRecord record : records.values()) {
                if (record == null) continue;
                out.add(record.toTrustRecord());
            }
            return out;
        }
    }

    boolean clearAuthority(@Nullable String authorityKey) {
        synchronized (lock) {
            ensureLoadedLocked();
            String normalized = safe(authorityKey).toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) return false;
            ArrayList<String> toRemove = new ArrayList<>();
            for (Map.Entry<String, HostRecord> item : records.entrySet()) {
                HostRecord record = item.getValue();
                if (record != null && TextUtils.equals(record.authorityKey, normalized)) {
                    toRemove.add(item.getKey());
                }
            }
            if (toRemove.isEmpty()) return false;
            for (String key : toRemove) {
                records.remove(key);
            }
            persistLocked();
            clearPendingAuthorityLocked(normalized);
            return true;
        }
    }

    @Nullable
    SshPendingTrustRecord findPendingByAuthority(@Nullable String authorityKey) {
        synchronized (lock) {
            ensureLoadedLocked();
            if (TextUtils.isEmpty(authorityKey)) return null;
            PendingRecord record = pendingRecords.get(safe(authorityKey).toLowerCase(Locale.ROOT));
            return record == null ? null : record.toPendingTrustRecord();
        }
    }

    @NonNull
    List<SshPendingTrustRecord> snapshotPendingRecords() {
        synchronized (lock) {
            ensureLoadedLocked();
            ArrayList<SshPendingTrustRecord> out = new ArrayList<>(pendingRecords.size());
            for (PendingRecord record : pendingRecords.values()) {
                if (record == null) continue;
                out.add(record.toPendingTrustRecord());
            }
            return out;
        }
    }

    boolean approvePendingAuthority(@Nullable String authorityKey, @NonNull SshTrustSource trustSource) {
        synchronized (lock) {
            ensureLoadedLocked();
            String normalized = safe(authorityKey).toLowerCase(Locale.ROOT);
            PendingRecord pending = pendingRecords.get(normalized);
            if (pending == null) return false;

            SshTrustRecord record = new SshTrustRecord(
                pending.authorityKey,
                pending.hostIdentity,
                pending.port,
                pending.algorithm,
                pending.observedFingerprintSha256,
                trustSource,
                pending.observedAtMs,
                pending.observedAtMs
            );
            HostRecord existing = records.get(buildRecordKey(pending.authorityKey, pending.algorithm));
            String hostForRecord = existing == null ? pending.hostIdentity : existing.host;
            upsertRecordLocked(record, hostForRecord, pending.keyBase64);
            clearPendingAuthorityLocked(normalized);
            return true;
        }
    }

    boolean dismissPendingAuthority(@Nullable String authorityKey) {
        synchronized (lock) {
            ensureLoadedLocked();
            return clearPendingAuthorityLocked(safe(authorityKey).toLowerCase(Locale.ROOT));
        }
    }

    private void ensureLoadedLocked() {
        if (loaded) return;
        loaded = true;
        records.clear();
        pendingRecords.clear();
        if (storeFile != null && storeFile.exists()) {
            try {
                String raw = readTextFile(storeFile);
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    HostRecord record = HostRecord.fromJson(item);
                    if (record == null) continue;
                    records.put(buildRecordKey(record.authorityKey, record.type), record);
                }
            } catch (Throwable ignored) {
                records.clear();
            }
        }
        if (pendingStoreFile != null && pendingStoreFile.exists()) {
            try {
                String raw = readTextFile(pendingStoreFile);
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    PendingRecord record = PendingRecord.fromJson(item);
                    if (record == null) continue;
                    pendingRecords.put(record.authorityKey, record);
                }
            } catch (Throwable ignored) {
                pendingRecords.clear();
            }
        }
        importKnownHostsFileLocked(managedKnownHostsFile, true);
        importKnownHostsFileLocked(legacyKnownHostsFile, shouldLegacyOverrideManagedLocked());
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
        rewriteManagedKnownHostsLocked();
        persistPendingLocked();
    }

    private boolean shouldLegacyOverrideManagedLocked() {
        if (legacyKnownHostsFile == null || !legacyKnownHostsFile.exists()) return false;
        if (managedKnownHostsFile == null || !managedKnownHostsFile.exists()) return true;
        return legacyKnownHostsFile.lastModified() > managedKnownHostsFile.lastModified();
    }

    private void importKnownHostsFileLocked(@Nullable File file, boolean overwriteExisting) {
        if (file == null || !file.exists() || !file.isFile()) return;
        try {
            String raw = readTextFile(file);
            String[] lines = raw.split("\\r?\\n");
            long importedAtMs = Math.max(0L, file.lastModified());
            for (String line : lines) {
                OpenSshKnownHostsEntry entry = OpenSshKnownHostsEntry.parse(line);
                if (entry == null) continue;
                HostRecord record = new HostRecord(
                    entry.authorityKey,
                    entry.hostIdentity,
                    entry.port,
                    entry.host,
                    entry.algorithm,
                    entry.keyBase64,
                    fingerprintSha256(decodeBase64(entry.keyBase64)),
                    importedAtMs,
                    importedAtMs
                );
                String key = buildRecordKey(record.authorityKey, record.type);
                if (overwriteExisting || !records.containsKey(key)) {
                    records.put(key, record);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void rewriteManagedKnownHostsLocked() {
        if (managedKnownHostsFile == null) return;
        File parent = managedKnownHostsFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream outputStream = new FileOutputStream(managedKnownHostsFile, false)) {
            for (HostRecord record : records.values()) {
                String line = record.toOpenSshKnownHostsLine();
                if (line.isEmpty()) continue;
                outputStream.write(line.getBytes(StandardCharsets.UTF_8));
                outputStream.write('\n');
            }
            outputStream.flush();
        } catch (Throwable ignored) {
        }
    }

    private void upsertRecordLocked(@NonNull SshTrustRecord trustRecord,
                                    @NonNull String host,
                                    @NonNull String keyBase64) {
        HostRecord record = new HostRecord(
            trustRecord.authorityKey,
            trustRecord.hostIdentity,
            trustRecord.port,
            host,
            trustRecord.algorithm,
            keyBase64,
            trustRecord.fingerprintSha256,
            trustRecord.trustedAtMs,
            trustRecord.lastSeenAtMs
        );
        records.put(buildRecordKey(record.authorityKey, record.type), record);
        persistLocked();
    }

    private boolean clearPendingAuthorityLocked(@Nullable String authorityKey) {
        String normalized = safe(authorityKey).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return false;
        PendingRecord removed = pendingRecords.remove(normalized);
        if (removed != null) {
            persistPendingLocked();
            return true;
        }
        return false;
    }

    private void persistPendingLocked() {
        if (pendingStoreFile == null) return;
        JSONArray array = new JSONArray();
        for (PendingRecord record : pendingRecords.values()) {
            if (record == null) continue;
            array.put(record.toJson());
        }
        try (FileOutputStream outputStream = new FileOutputStream(pendingStoreFile, false)) {
            outputStream.write(array.toString().getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    private ResolvedSshEndpoint resolveEndpointLocked(@Nullable String hostQuery) {
        if (activeEndpoint != null) {
            return activeEndpoint;
        }
        HostSpec spec = HostSpec.parse(hostQuery);
        return new ResolvedSshEndpoint.Builder()
            .setHost(spec.host)
            .setHostIdentity(spec.hostIdentity)
            .setPort(spec.port)
            .setUser("")
            .setHostKeyVerificationMode(ResolvedSshEndpoint.HostKeyVerificationMode.YES)
            .build();
    }

    @NonNull
    private static String buildRecordKey(@NonNull String authorityKey, @NonNull String type) {
        return safe(authorityKey).toLowerCase(Locale.ROOT) + "|" + safe(type).toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static String normalizeHostForHostKey(@Nullable String host) {
        return HostSpec.parse(host).host;
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
            return "sha256:" + Base64.encodeToString(sha, Base64.NO_WRAP).toLowerCase(Locale.ROOT);
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
            "已信任主机指纹", detail);
    }

    private void traceWarn(@NonNull String action, @Nullable String sessionKey, @Nullable String detail) {
        Context context = appContext;
        SessionSyncTracer.getInstance().warn(context, "SshHostTrustStore", action, sessionKey,
            "主机指纹发生变更", detail);
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

    private static final class HostSpec {
        @NonNull final String host;
        @NonNull final String hostIdentity;
        final int port;

        HostSpec(@NonNull String host, @NonNull String hostIdentity, int port) {
            this.host = safe(host).toLowerCase(Locale.ROOT);
            this.hostIdentity = safe(hostIdentity).toLowerCase(Locale.ROOT);
            this.port = port > 0 && port <= 65535 ? port : 22;
        }

        @NonNull
        static HostSpec parse(@Nullable String rawHost) {
            String value = safe(rawHost).toLowerCase(Locale.ROOT);
            if (value.isEmpty()) return new HostSpec("", "", 22);

            String host = value;
            int port = 22;
            if (host.startsWith("[") && host.contains("]:")) {
                int end = host.indexOf("]:");
                String candidateHost = host.substring(1, end);
                String candidatePort = host.substring(end + 2);
                host = candidateHost;
                try {
                    port = Integer.parseInt(candidatePort);
                } catch (Exception ignored) {
                    port = 22;
                }
            } else {
                int colon = host.lastIndexOf(':');
                if (colon > 0 && colon == host.indexOf(':')) {
                    String candidatePort = host.substring(colon + 1);
                    if (candidatePort.matches("\\d+")) {
                        host = host.substring(0, colon);
                        try {
                            port = Integer.parseInt(candidatePort);
                        } catch (Exception ignored) {
                            port = 22;
                        }
                    }
                }
                if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
                    host = host.substring(1, host.length() - 1);
                }
            }
            return new HostSpec(host, host, port);
        }
    }

    private static final class HostRecord {
        @NonNull final String authorityKey;
        @NonNull final String hostIdentity;
        final int port;
        @NonNull final String host;
        @NonNull final String type;
        @NonNull final String keyBase64;
        @NonNull final String fingerprintSha256;
        final long trustedAtMs;
        final long lastSeenAtMs;

        HostRecord(@NonNull String authorityKey,
                   @NonNull String hostIdentity,
                   int port,
                   @NonNull String host,
                   @NonNull String type,
                   @NonNull String keyBase64,
                   @NonNull String fingerprintSha256,
                   long trustedAtMs,
                   long lastSeenAtMs) {
            this.authorityKey = safe(authorityKey).toLowerCase(Locale.ROOT);
            this.hostIdentity = safe(hostIdentity).toLowerCase(Locale.ROOT);
            this.port = port > 0 && port <= 65535 ? port : 22;
            this.host = safe(host).toLowerCase(Locale.ROOT);
            this.type = safe(type);
            this.keyBase64 = safe(keyBase64);
            this.fingerprintSha256 = safe(fingerprintSha256).toLowerCase(Locale.ROOT);
            this.trustedAtMs = Math.max(0L, trustedAtMs);
            this.lastSeenAtMs = Math.max(0L, lastSeenAtMs);
        }

        @Nullable
        static HostRecord fromJson(@Nullable JSONObject json) {
            if (json == null) return null;
            String type = safe(json.optString("type", ""));
            String keyBase64 = safe(json.optString("keyBase64", ""));
            if (type.isEmpty() || keyBase64.isEmpty()) return null;

            String host = safe(json.optString("host", ""));
            String authorityKey = safe(json.optString("authorityKey", ""));
            String hostIdentity = safe(json.optString("hostIdentity", ""));
            int port = json.optInt("port", 22);
            if (authorityKey.isEmpty()) {
                HostSpec spec = HostSpec.parse(host);
                host = spec.host;
                hostIdentity = hostIdentity.isEmpty() ? spec.hostIdentity : hostIdentity;
                port = port > 0 ? port : spec.port;
                authorityKey = "ssh://" + hostIdentity.toLowerCase(Locale.ROOT) + ":" + (port > 0 ? port : 22);
            }
            if (host.isEmpty()) {
                HostSpec spec = HostSpec.parse(hostIdentity);
                host = spec.host;
            }
            if (hostIdentity.isEmpty()) {
                hostIdentity = HostSpec.parse(host).hostIdentity;
            }

            return new HostRecord(
                authorityKey,
                hostIdentity,
                port,
                host,
                type,
                keyBase64,
                safe(json.optString("fingerprintSha256",
                    json.optString("fingerprint", ""))),
                json.optLong("trustedAtMs", 0L),
                json.optLong("lastSeenAtMs", 0L)
            );
        }

        @NonNull
        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("authorityKey", authorityKey);
                json.put("hostIdentity", hostIdentity);
                json.put("port", port);
                json.put("host", host);
                json.put("type", type);
                json.put("keyBase64", keyBase64);
                json.put("fingerprintSha256", fingerprintSha256);
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

        @NonNull
        SshTrustRecord toTrustRecord() {
            return new SshTrustRecord(
                authorityKey,
                hostIdentity,
                port,
                type,
                fingerprintSha256,
                SshTrustSource.IMPORTED_APP_STORE,
                trustedAtMs,
                lastSeenAtMs
            );
        }

        @NonNull
        String toOpenSshKnownHostsLine() {
            ResolvedSshEndpoint endpoint = new ResolvedSshEndpoint.Builder()
                .setAuthorityKey(authorityKey)
                .setHostIdentity(hostIdentity)
                .setHost(host)
                .setPort(port)
                .setUser("")
                .setHostKeyVerificationMode(ResolvedSshEndpoint.HostKeyVerificationMode.YES)
                .build();
            String hostPattern = SshKnownHostsFiles.buildKnownHostsHostPattern(endpoint);
            if (hostPattern.isEmpty() || type.isEmpty() || keyBase64.isEmpty()) return "";
            return hostPattern + " " + type + " " + keyBase64;
        }
    }

    private static final class OpenSshKnownHostsEntry {
        @NonNull final String authorityKey;
        @NonNull final String hostIdentity;
        final int port;
        @NonNull final String host;
        @NonNull final String algorithm;
        @NonNull final String keyBase64;

        OpenSshKnownHostsEntry(@NonNull String authorityKey,
                               @NonNull String hostIdentity,
                               int port,
                               @NonNull String host,
                               @NonNull String algorithm,
                               @NonNull String keyBase64) {
            this.authorityKey = authorityKey;
            this.hostIdentity = hostIdentity;
            this.port = port;
            this.host = host;
            this.algorithm = algorithm;
            this.keyBase64 = keyBase64;
        }

        @Nullable
        static OpenSshKnownHostsEntry parse(@Nullable String rawLine) {
            String line = safe(rawLine);
            if (line.isEmpty() || line.startsWith("#")) return null;
            String[] parts = line.split("\\s+");
            if (parts.length < 3) return null;
            String hostToken = safe(parts[0]);
            if (hostToken.isEmpty() || hostToken.startsWith("|1|")) return null;

            String firstHost = hostToken;
            int comma = hostToken.indexOf(',');
            if (comma > 0) firstHost = hostToken.substring(0, comma);

            HostSpec spec = HostSpec.parse(firstHost);
            if (spec.hostIdentity.isEmpty()) return null;

            return new OpenSshKnownHostsEntry(
                "ssh://" + spec.hostIdentity + ":" + spec.port,
                spec.hostIdentity,
                spec.port,
                spec.host,
                safe(parts[1]),
                safe(parts[2])
            );
        }
    }

    private static final class PendingRecord {
        @NonNull final String authorityKey;
        @NonNull final String hostIdentity;
        final int port;
        @NonNull final String algorithm;
        @NonNull final String keyBase64;
        @NonNull final String observedFingerprintSha256;
        @NonNull final String existingFingerprintSha256;
        final boolean replacementRequired;
        final long observedAtMs;

        PendingRecord(@NonNull String authorityKey,
                      @NonNull String hostIdentity,
                      int port,
                      @NonNull String algorithm,
                      @NonNull String keyBase64,
                      @NonNull String observedFingerprintSha256,
                      @Nullable String existingFingerprintSha256,
                      boolean replacementRequired,
                      long observedAtMs) {
            this.authorityKey = safe(authorityKey).toLowerCase(Locale.ROOT);
            this.hostIdentity = safe(hostIdentity).toLowerCase(Locale.ROOT);
            this.port = port > 0 && port <= 65535 ? port : 22;
            this.algorithm = safe(algorithm);
            this.keyBase64 = safe(keyBase64);
            this.observedFingerprintSha256 = safe(observedFingerprintSha256).toLowerCase(Locale.ROOT);
            this.existingFingerprintSha256 = safe(existingFingerprintSha256).toLowerCase(Locale.ROOT);
            this.replacementRequired = replacementRequired;
            this.observedAtMs = Math.max(0L, observedAtMs);
        }

        @Nullable
        static PendingRecord fromJson(@Nullable JSONObject json) {
            if (json == null) return null;
            String authorityKey = safe(json.optString("authorityKey", ""));
            String algorithm = safe(json.optString("algorithm", ""));
            String keyBase64 = safe(json.optString("keyBase64", ""));
            String observedFingerprintSha256 = safe(json.optString("observedFingerprintSha256", ""));
            if (authorityKey.isEmpty() || algorithm.isEmpty() || keyBase64.isEmpty() || observedFingerprintSha256.isEmpty()) {
                return null;
            }
            return new PendingRecord(
                authorityKey,
                safe(json.optString("hostIdentity", "")),
                json.optInt("port", 22),
                algorithm,
                keyBase64,
                observedFingerprintSha256,
                safe(json.optString("existingFingerprintSha256", "")),
                json.optBoolean("replacementRequired", false),
                json.optLong("observedAtMs", 0L)
            );
        }

        @NonNull
        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("authorityKey", authorityKey);
                json.put("hostIdentity", hostIdentity);
                json.put("port", port);
                json.put("algorithm", algorithm);
                json.put("keyBase64", keyBase64);
                json.put("observedFingerprintSha256", observedFingerprintSha256);
                json.put("existingFingerprintSha256", existingFingerprintSha256);
                json.put("replacementRequired", replacementRequired);
                json.put("observedAtMs", observedAtMs);
            } catch (Throwable ignored) {
            }
            return json;
        }

        @NonNull
        SshPendingTrustRecord toPendingTrustRecord() {
            return new SshPendingTrustRecord(
                authorityKey,
                hostIdentity,
                port,
                algorithm,
                observedFingerprintSha256,
                existingFingerprintSha256,
                replacementRequired,
                observedAtMs
            );
        }
    }
}
