package com.tencent.shadow.sample.host.platform;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ShadowRegistry {

    private static final int SCHEMA_VERSION = 3;
    private static final int MAX_REGISTRY_BYTES = 4 * 1024 * 1024;

    long revision;
    long updatedAt;
    final Map<String, PluginRecord> plugins = new LinkedHashMap<>();

    static ShadowRegistry load(File file) throws Exception {
        if (!file.isFile()) {
            ShadowRegistry registry = new ShadowRegistry();
            registry.updatedAt = System.currentTimeMillis();
            return registry;
        }
        JSONObject root = new JSONObject(new String(readBounded(file), StandardCharsets.UTF_8));
        int schemaVersion = root.optInt("schemaVersion", -1);
        if (schemaVersion < 1 || schemaVersion > SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported Shadow registry schema");
        }
        ShadowRegistry registry = new ShadowRegistry();
        registry.revision = root.optLong("revision", 0L);
        registry.updatedAt = root.optLong("updatedAt", 0L);
        JSONArray plugins = root.optJSONArray("plugins");
        if (plugins != null) {
            for (int index = 0; index < plugins.length(); index++) {
                PluginRecord record = PluginRecord.fromJson(plugins.getJSONObject(index));
                if (registry.plugins.put(record.pluginId, record) != null) {
                    throw new IllegalStateException("Duplicate plugin in Shadow registry: " + record.pluginId);
                }
            }
        }
        if (schemaVersion < 3) {
            migrateLegacyActivationPointers(registry);
        }
        return registry;
    }

    private static void migrateLegacyActivationPointers(ShadowRegistry registry) {
        for (PluginRecord plugin : registry.plugins.values()) {
            VersionRecord active = plugin.activeVersion();
            if (active == null || (active.state != ShadowLifecycleState.ACTIVATING
                    && active.state != ShadowLifecycleState.ROLLING_BACK)) {
                continue;
            }
            VersionRecord fallback = plugin.previousHealthyVersion();
            plugin.activatingGeneration = active.generation;
            plugin.candidateGeneration = active.generation;
            plugin.activeGeneration = fallback == null ? null : fallback.generation;
            plugin.previousGeneration = null;
        }
    }

    void persist(File file) throws Exception {
        revision++;
        updatedAt = System.currentTimeMillis();
        ShadowFileOps.writeAtomically(
                file,
                toJson().toString(2).getBytes(StandardCharsets.UTF_8),
                false
        );
    }

    JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("revision", revision);
        root.put("updatedAt", updatedAt);
        JSONArray array = new JSONArray();
        List<PluginRecord> sorted = new ArrayList<>(plugins.values());
        Collections.sort(sorted, Comparator.comparing(record -> record.pluginId));
        for (PluginRecord record : sorted) {
            array.put(record.toJson());
        }
        root.put("plugins", array);
        return root;
    }

    static final class PluginRecord {
        final String pluginId;
        boolean enabled = true;
        String activeGeneration;
        String previousGeneration;
        String candidateGeneration;
        String activatingGeneration;
        boolean removalRequested;
        String removalOperationId;
        long createdAt;
        long updatedAt;
        final Map<String, VersionRecord> versions = new LinkedHashMap<>();

        PluginRecord(String pluginId) {
            this.pluginId = pluginId;
            createdAt = System.currentTimeMillis();
            updatedAt = createdAt;
        }

        VersionRecord activeVersion() {
            return activeGeneration == null ? null : versions.get(activeGeneration);
        }

        VersionRecord previousVersion() {
            return previousGeneration == null ? null : versions.get(previousGeneration);
        }

        VersionRecord previousHealthyVersion() {
            VersionRecord previous = previousVersion();
            return previous != null && previous.isRollbackEligible() ? previous : null;
        }

        VersionRecord candidateVersion() {
            return candidateGeneration == null ? null : versions.get(candidateGeneration);
        }

        VersionRecord activatingVersion() {
            return activatingGeneration == null ? null : versions.get(activatingGeneration);
        }

        JSONObject toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("pluginId", pluginId);
            object.put("enabled", enabled);
            object.put("activeGeneration", nullable(activeGeneration));
            object.put("previousGeneration", nullable(previousGeneration));
            object.put("candidateGeneration", nullable(candidateGeneration));
            object.put("activatingGeneration", nullable(activatingGeneration));
            object.put("removalRequested", removalRequested);
            object.put("removalOperationId", nullable(removalOperationId));
            object.put("createdAt", createdAt);
            object.put("updatedAt", updatedAt);
            JSONArray array = new JSONArray();
            List<VersionRecord> sorted = new ArrayList<>(versions.values());
            Collections.sort(sorted, (left, right) -> Long.compare(right.installedAt, left.installedAt));
            for (VersionRecord version : sorted) {
                array.put(version.toJson());
            }
            object.put("versions", array);
            return object;
        }

        static PluginRecord fromJson(JSONObject object) throws Exception {
            PluginRecord record = new PluginRecord(object.getString("pluginId"));
            record.enabled = object.optBoolean("enabled", true);
            record.activeGeneration = nullableString(object, "activeGeneration");
            record.previousGeneration = nullableString(object, "previousGeneration");
            record.candidateGeneration = nullableString(object, "candidateGeneration");
            record.activatingGeneration = nullableString(object, "activatingGeneration");
            record.removalRequested = object.optBoolean("removalRequested", false);
            record.removalOperationId = nullableString(object, "removalOperationId");
            record.createdAt = object.optLong("createdAt", System.currentTimeMillis());
            record.updatedAt = object.optLong("updatedAt", record.createdAt);
            JSONArray versions = object.optJSONArray("versions");
            if (versions != null) {
                for (int index = 0; index < versions.length(); index++) {
                    VersionRecord version = VersionRecord.fromJson(versions.getJSONObject(index));
                    if (record.versions.put(version.generation, version) != null) {
                        throw new IllegalStateException(
                                "Duplicate plugin generation in registry: " + version.generation
                        );
                    }
                }
            }
            validatePointer(record.activeGeneration, record, "activeGeneration");
            validatePointer(record.previousGeneration, record, "previousGeneration");
            validatePointer(record.candidateGeneration, record, "candidateGeneration");
            validatePointer(record.activatingGeneration, record, "activatingGeneration");
            return record;
        }

        private static void validatePointer(String generation, PluginRecord record, String field) {
            if (generation != null && !record.versions.containsKey(generation)) {
                throw new IllegalStateException(field + " points to a missing generation: " + generation);
            }
        }
    }

    static final class VersionRecord {
        final String generation;
        final String bundleSha256;
        final ShadowPluginManifest manifest;
        final ShadowTrustLevel trustLevel;
        ShadowLifecycleState state;
        long installedAt;
        long lastTransitionAt;
        long healthConfirmedAt;
        String repositoryPath;
        String runtimePath;
        String sourcePath;
        String lastOperationId;
        String lastError;
        long totalLaunchAttempts;
        long totalLaunchFailures;
        int consecutiveLaunchFailures;
        long lastLaunchAttemptAt;
        long lastLaunchSuccessAt;
        String lastLaunchOperationId;
        int runtimeHealthProtocolVersion;
        long firstFrameElapsedMs;
        long stableElapsedMs;
        long runtimeReadyAt;
        long runtimeStableAt;
        int lastHealthyProcessPid;
        String lastHealthyProcessName;
        String lastHealthOperationId;

        VersionRecord(ShadowVerificationResult verification, String sourcePath, String operationId) {
            generation = verification.generation;
            bundleSha256 = verification.bundleSha256;
            manifest = verification.manifest;
            trustLevel = verification.trustLevel;
            state = ShadowLifecycleState.DISCOVERED;
            installedAt = System.currentTimeMillis();
            lastTransitionAt = installedAt;
            this.sourcePath = sourcePath;
            lastOperationId = operationId;
        }

        void transition(ShadowLifecycleState target, String operationId, String error) {
            ShadowStateMachine.requireTransition(state, target);
            state = target;
            lastTransitionAt = System.currentTimeMillis();
            lastOperationId = operationId;
            lastError = error;
            if (target == ShadowLifecycleState.HEALTHY) {
                healthConfirmedAt = lastTransitionAt;
            }
        }

        void recordRuntimeHealth(ShadowRuntimeHealth health, String operationId) {
            if (!health.isStable()) {
                throw new IllegalArgumentException("runtime health proof is not stable");
            }
            runtimeHealthProtocolVersion = health.protocolVersion;
            firstFrameElapsedMs = health.firstFrameElapsedMs;
            stableElapsedMs = health.stableElapsedMs;
            runtimeReadyAt = System.currentTimeMillis()
                    - Math.max(0L, stableElapsedMs - firstFrameElapsedMs);
            runtimeStableAt = System.currentTimeMillis();
            lastHealthyProcessPid = health.pluginProcessPid;
            lastHealthyProcessName = health.pluginProcessName;
            lastHealthOperationId = operationId;
        }

        boolean hasRuntimeHealthProof() {
            return runtimeHealthProtocolVersion >= ShadowRuntimeHealth.PROTOCOL_VERSION
                    && firstFrameElapsedMs > 0L
                    && stableElapsedMs >= firstFrameElapsedMs
                    && runtimeStableAt > 0L
                    && lastHealthyProcessPid > 0;
        }

        boolean isRollbackEligible() {
            return hasRuntimeHealthProof()
                    && (state == ShadowLifecycleState.HEALTHY
                    || state == ShadowLifecycleState.SUPERSEDED
                    || state == ShadowLifecycleState.ROLLED_BACK);
        }

        void invalidateRuntimeHealth() {
            runtimeHealthProtocolVersion = 0;
            firstFrameElapsedMs = 0L;
            stableElapsedMs = 0L;
            runtimeReadyAt = 0L;
            runtimeStableAt = 0L;
            lastHealthyProcessPid = 0;
            lastHealthyProcessName = null;
            lastHealthOperationId = null;
        }

        JSONObject toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("generation", generation);
            object.put("bundleSha256", bundleSha256);
            object.put("manifest", manifest.toJson());
            object.put("trustLevel", trustLevel.name());
            object.put("state", state.name());
            object.put("installedAt", installedAt);
            object.put("lastTransitionAt", lastTransitionAt);
            object.put("healthConfirmedAt", healthConfirmedAt);
            object.put("repositoryPath", nullable(repositoryPath));
            object.put("runtimePath", nullable(runtimePath));
            object.put("sourcePath", nullable(sourcePath));
            object.put("lastOperationId", nullable(lastOperationId));
            object.put("lastError", nullable(lastError));
            object.put("totalLaunchAttempts", totalLaunchAttempts);
            object.put("totalLaunchFailures", totalLaunchFailures);
            object.put("consecutiveLaunchFailures", consecutiveLaunchFailures);
            object.put("lastLaunchAttemptAt", lastLaunchAttemptAt);
            object.put("lastLaunchSuccessAt", lastLaunchSuccessAt);
            object.put("lastLaunchOperationId", nullable(lastLaunchOperationId));
            object.put("runtimeHealthProtocolVersion", runtimeHealthProtocolVersion);
            object.put("firstFrameElapsedMs", firstFrameElapsedMs);
            object.put("stableElapsedMs", stableElapsedMs);
            object.put("runtimeReadyAt", runtimeReadyAt);
            object.put("runtimeStableAt", runtimeStableAt);
            object.put("lastHealthyProcessPid", lastHealthyProcessPid);
            object.put("lastHealthyProcessName", nullable(lastHealthyProcessName));
            object.put("lastHealthOperationId", nullable(lastHealthOperationId));
            return object;
        }

        static VersionRecord fromJson(JSONObject object) throws Exception {
            JSONObject manifestJson = object.getJSONObject("manifest");
            ShadowPluginManifest manifest = manifestFromRegistry(manifestJson);
            ShadowVerificationResult verification = new ShadowVerificationResult(
                    manifest,
                    object.getString("bundleSha256"),
                    object.getString("generation"),
                    ShadowTrustLevel.valueOf(object.getString("trustLevel"))
            );
            VersionRecord record = new VersionRecord(
                    verification,
                    nullableString(object, "sourcePath"),
                    nullableString(object, "lastOperationId")
            );
            record.state = ShadowLifecycleState.valueOf(object.getString("state"));
            record.installedAt = object.optLong("installedAt", System.currentTimeMillis());
            record.lastTransitionAt = object.optLong("lastTransitionAt", record.installedAt);
            record.healthConfirmedAt = object.optLong("healthConfirmedAt", 0L);
            record.repositoryPath = nullableString(object, "repositoryPath");
            record.runtimePath = nullableString(object, "runtimePath");
            record.lastError = nullableString(object, "lastError");
            record.totalLaunchAttempts = object.optLong("totalLaunchAttempts", 0L);
            record.totalLaunchFailures = object.optLong("totalLaunchFailures", 0L);
            record.consecutiveLaunchFailures = object.optInt("consecutiveLaunchFailures", 0);
            record.lastLaunchAttemptAt = object.optLong("lastLaunchAttemptAt", 0L);
            record.lastLaunchSuccessAt = object.optLong("lastLaunchSuccessAt", 0L);
            record.lastLaunchOperationId = nullableString(object, "lastLaunchOperationId");
            record.runtimeHealthProtocolVersion = object.optInt(
                    "runtimeHealthProtocolVersion",
                    0
            );
            record.firstFrameElapsedMs = object.optLong("firstFrameElapsedMs", 0L);
            record.stableElapsedMs = object.optLong("stableElapsedMs", 0L);
            record.runtimeReadyAt = object.optLong("runtimeReadyAt", 0L);
            record.runtimeStableAt = object.optLong("runtimeStableAt", 0L);
            record.lastHealthyProcessPid = object.optInt("lastHealthyProcessPid", 0);
            record.lastHealthyProcessName = nullableString(object, "lastHealthyProcessName");
            record.lastHealthOperationId = nullableString(object, "lastHealthOperationId");
            return record;
        }

        private static ShadowPluginManifest manifestFromRegistry(JSONObject object) throws Exception {
            JSONObject metadata = new JSONObject();
            metadata.put("schemaVersion", object.getInt("schemaVersion"));
            metadata.put("pluginId", object.getString("pluginId"));
            metadata.put("versionCode", object.getLong("versionCode"));
            metadata.put("versionName", object.getString("versionName"));
            metadata.put("displayName", object.getString("displayName"));
            metadata.put("description", object.optString("description", ""));
            metadata.put("partKey", object.getString("partKey"));
            metadata.put("activityClassName", object.getString("activityClassName"));
            if (object.has("resourcePackageId")) {
                metadata.put("resourcePackageId", object.get("resourcePackageId"));
            }
            metadata.put("minHostVersionCode", object.optLong("minHostVersionCode", 0L));
            metadata.put("maxHostVersionCode", object.optLong("maxHostVersionCode", Long.MAX_VALUE));

            JSONObject config = new JSONObject();
            config.put("UUID", object.getString("shadowUuid"));
            config.put("UUID_NickName", object.getString("versionName"));
            if (!object.isNull("loaderApkName")) {
                config.put("pluginLoader", fileConfig(object.getString("loaderApkName")));
            }
            if (!object.isNull("runtimeApkName")) {
                config.put("runtime", fileConfig(object.getString("runtimeApkName")));
            }
            JSONObject plugin = fileConfig(object.getString("pluginApkName"));
            plugin.put("partKey", object.getString("partKey"));
            JSONArray plugins = new JSONArray();
            plugins.put(plugin);
            config.put("plugins", plugins);
            return ShadowPluginManifest.parse(metadata, config);
        }

        private static JSONObject fileConfig(String name) throws JSONException {
            JSONObject object = new JSONObject();
            object.put("apkName", name);
            object.put("hash", "registry-placeholder");
            return object;
        }
    }

    private static Object nullable(String value) {
        return value == null ? JSONObject.NULL : value;
    }

    private static String nullableString(JSONObject object, String key) {
        return object.isNull(key) ? null : object.optString(key, null);
    }

    private static byte[] readBounded(File file) throws IOException {
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_REGISTRY_BYTES) {
                    throw new IOException("Shadow registry is too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            if (input != null) {
                input.close();
            }
        }
    }
}
