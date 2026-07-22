package com.tencent.shadow.sample.host.platform;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShadowRegistryTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void registryRoundTripPreservesPointersAndState() throws Exception {
        ShadowPluginManifest manifest = manifest();
        ShadowVerificationResult verification = new ShadowVerificationResult(
                manifest,
                repeat('a', 64),
                "5-aaaaaaaaaaaaaaaa",
                ShadowTrustLevel.TRUSTED_SIGNATURE
        );
        ShadowRegistry registry = new ShadowRegistry();
        ShadowRegistry.PluginRecord plugin = new ShadowRegistry.PluginRecord(manifest.pluginId);
        ShadowRegistry.VersionRecord version = new ShadowRegistry.VersionRecord(
                verification,
                "/source/plugin.shadowpkg",
                "operation-1"
        );
        version.transition(ShadowLifecycleState.STAGED, "operation-1", null);
        version.transition(ShadowLifecycleState.VERIFYING, "operation-1", null);
        version.transition(ShadowLifecycleState.VERIFIED, "operation-1", null);
        version.transition(ShadowLifecycleState.INSTALLING, "operation-1", null);
        version.transition(ShadowLifecycleState.INSTALLED, "operation-1", null);
        version.repositoryPath = "/repository/bundle.shadowpkg";
        version.runtimePath = "/runtime/bundle.shadowpkg";
        version.totalLaunchAttempts = 9;
        version.totalLaunchFailures = 2;
        version.consecutiveLaunchFailures = 1;
        version.lastLaunchAttemptAt = 1234L;
        version.lastLaunchSuccessAt = 1200L;
        version.lastLaunchOperationId = "launch-9";
        plugin.versions.put(version.generation, version);
        plugin.candidateGeneration = version.generation;
        plugin.activatingGeneration = version.generation;
        plugin.removalRequested = true;
        plugin.removalOperationId = "remove-1";
        registry.plugins.put(plugin.pluginId, plugin);

        File file = temporaryFolder.newFile("registry.json");
        Files.write(file.toPath(), registry.toJson().toString().getBytes(StandardCharsets.UTF_8));
        ShadowRegistry restored = ShadowRegistry.load(file);

        ShadowRegistry.PluginRecord restoredPlugin = restored.plugins.get(manifest.pluginId);
        assertNotNull(restoredPlugin);
        assertEquals(version.generation, restoredPlugin.candidateGeneration);
        assertEquals(version.generation, restoredPlugin.activatingGeneration);
        assertEquals(
                ShadowLifecycleState.INSTALLED,
                restoredPlugin.versions.get(version.generation).state
        );
        assertEquals("0x7C", restoredPlugin.versions.get(version.generation)
                .manifest.toJson().getString("resourcePackageId"));
        assertTrue(restoredPlugin.removalRequested);
        assertEquals("remove-1", restoredPlugin.removalOperationId);
        ShadowRegistry.VersionRecord restoredVersion = restoredPlugin.versions.get(version.generation);
        assertEquals(9L, restoredVersion.totalLaunchAttempts);
        assertEquals(2L, restoredVersion.totalLaunchFailures);
        assertEquals(1, restoredVersion.consecutiveLaunchFailures);
        assertEquals("launch-9", restoredVersion.lastLaunchOperationId);
    }

    @Test
    public void failedActivationInvalidatesOldRuntimeProof() throws Exception {
        ShadowRegistry.VersionRecord version = version("5-aaaaaaaaaaaaaaaa", 'a');
        moveInstalledToHealthy(version, "health-1");
        assertTrue(version.isRollbackEligible());

        version.invalidateRuntimeHealth();

        assertFalse(version.hasRuntimeHealthProof());
        assertFalse(version.isRollbackEligible());
        assertEquals(0, version.lastHealthyProcessPid);
        assertNull(version.lastHealthOperationId);
    }

    @Test
    public void schemaTwoActivationMigratesWithoutReplacingHealthyActive() throws Exception {
        ShadowRegistry.VersionRecord previous = version("4-bbbbbbbbbbbbbbbb", 'b');
        moveInstalledToHealthy(previous, "health-previous");
        ShadowRegistry.VersionRecord candidate = version("5-aaaaaaaaaaaaaaaa", 'a');
        moveToInstalled(candidate, "install-candidate");
        candidate.transition(ShadowLifecycleState.ACTIVATING, "launch-candidate", null);

        ShadowRegistry registry = new ShadowRegistry();
        ShadowRegistry.PluginRecord plugin = new ShadowRegistry.PluginRecord(
                candidate.manifest.pluginId
        );
        plugin.versions.put(previous.generation, previous);
        plugin.versions.put(candidate.generation, candidate);
        plugin.activeGeneration = candidate.generation;
        plugin.previousGeneration = previous.generation;
        registry.plugins.put(plugin.pluginId, plugin);

        JSONObject legacy = registry.toJson();
        legacy.put("schemaVersion", 2);
        File file = temporaryFolder.newFile("legacy-registry.json");
        Files.write(file.toPath(), legacy.toString().getBytes(StandardCharsets.UTF_8));

        ShadowRegistry.PluginRecord restored = ShadowRegistry.load(file)
                .plugins.get(plugin.pluginId);
        assertNotNull(restored);
        assertEquals(previous.generation, restored.activeGeneration);
        assertEquals(candidate.generation, restored.activatingGeneration);
        assertEquals(candidate.generation, restored.candidateGeneration);
        assertNull(restored.previousGeneration);
    }

    private static ShadowRegistry.VersionRecord version(String generation, char digest) throws Exception {
        return new ShadowRegistry.VersionRecord(
                new ShadowVerificationResult(
                        manifest(),
                        repeat(digest, 64),
                        generation,
                        ShadowTrustLevel.TRUSTED_SIGNATURE
                ),
                "/source/" + generation + ".shadowpkg",
                "install-" + generation
        );
    }

    private static void moveToInstalled(ShadowRegistry.VersionRecord version, String operationId) {
        version.transition(ShadowLifecycleState.STAGED, operationId, null);
        version.transition(ShadowLifecycleState.VERIFYING, operationId, null);
        version.transition(ShadowLifecycleState.VERIFIED, operationId, null);
        version.transition(ShadowLifecycleState.INSTALLING, operationId, null);
        version.transition(ShadowLifecycleState.INSTALLED, operationId, null);
    }

    private static void moveInstalledToHealthy(
            ShadowRegistry.VersionRecord version,
            String operationId
    ) {
        moveToInstalled(version, operationId);
        version.transition(ShadowLifecycleState.ACTIVATING, operationId, null);
        version.recordRuntimeHealth(
                new ShadowRuntimeHealth(
                        ShadowRuntimeHealth.PROTOCOL_VERSION,
                        100L,
                        1_600L,
                        1234,
                        "com.termux:plugin"
                ),
                operationId
        );
        version.transition(ShadowLifecycleState.HEALTHY, operationId, null);
    }

    private static ShadowPluginManifest manifest() throws Exception {
        JSONObject metadata = new JSONObject();
        metadata.put("schemaVersion", 2);
        metadata.put("pluginId", "com.termux.shadow.registry");
        metadata.put("versionCode", 5);
        metadata.put("versionName", "5.0.0");
        metadata.put("displayName", "Registry Test");
        metadata.put("partKey", "registry-part");
        metadata.put("activityClassName", "com.termux.shadow.registry.MainActivity");
        metadata.put("resourcePackageId", "0x7C");

        JSONObject config = new JSONObject();
        config.put("UUID", "REGISTRY-UUID");
        config.put("UUID_NickName", "5.0.0");
        config.put("pluginLoader", file("loader.apk"));
        config.put("runtime", file("runtime.apk"));
        JSONObject plugin = file("plugin.apk");
        plugin.put("partKey", "registry-part");
        JSONArray plugins = new JSONArray();
        plugins.put(plugin);
        config.put("plugins", plugins);
        return ShadowPluginManifest.parse(metadata, config);
    }

    private static JSONObject file(String name) throws Exception {
        JSONObject object = new JSONObject();
        object.put("apkName", name);
        object.put("hash", "placeholder");
        return object;
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
