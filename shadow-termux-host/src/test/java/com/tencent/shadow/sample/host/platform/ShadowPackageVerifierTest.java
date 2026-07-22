package com.tencent.shadow.sample.host.platform;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ShadowPackageVerifierTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void verifiesSchemaTwoPackage() throws Exception {
        File bundle = createBundle(false, false, ShadowPackageContract.SCHEMA_VERSION);
        ShadowVerificationResult result = verifier().verify(bundle);

        assertEquals("com.termux.shadow.test", result.manifest.pluginId);
        assertEquals(7L, result.manifest.versionCode);
        assertEquals(0x7c, result.manifest.resourcePackageId);
        assertEquals(ShadowTrustLevel.INTEGRITY_VERIFIED, result.trustLevel);
        assertTrue(result.generation.startsWith("7-"));
    }

    @Test(expected = SecurityException.class)
    public void rejectsTamperedChecksum() throws Exception {
        verifier().verify(createBundle(true, false, ShadowPackageContract.SCHEMA_VERSION));
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsUnsafeZipPath() throws Exception {
        verifier().verify(createBundle(false, true, ShadowPackageContract.SCHEMA_VERSION));
    }

    @Test(expected = org.json.JSONException.class)
    public void rejectsSchemaOnePackage() throws Exception {
        verifier().verify(createBundle(false, false, 1));
    }

    private ShadowPackageVerifier verifier() {
        ShadowTrustPolicy policy = ShadowTrustPolicy.forTesting(
                false,
                32L * 1024L * 1024L
        );
        return new ShadowPackageVerifier(policy, 118L);
    }

    private File createBundle(boolean tamperChecksum, boolean unsafePath, int schemaVersion)
            throws Exception {
        byte[] loader = "loader-apk".getBytes(StandardCharsets.UTF_8);
        byte[] runtime = "runtime-apk".getBytes(StandardCharsets.UTF_8);
        byte[] plugin = "plugin-apk".getBytes(StandardCharsets.UTF_8);

        JSONObject config = new JSONObject();
        config.put("UUID", "TEST-UUID-7");
        config.put("UUID_NickName", "7.0.0");
        config.put("version", 4);
        config.put("pluginLoader", fileConfig("loader.apk", loader));
        config.put("runtime", fileConfig("runtime.apk", runtime));
        JSONObject pluginConfig = fileConfig("plugin.apk", plugin);
        pluginConfig.put("partKey", "test-part");
        JSONArray plugins = new JSONArray();
        plugins.put(pluginConfig);
        config.put("plugins", plugins);

        JSONObject metadata = new JSONObject();
        metadata.put("schemaVersion", schemaVersion);
        metadata.put("pluginId", "com.termux.shadow.test");
        metadata.put("versionCode", 7);
        metadata.put("versionName", "7.0.0");
        metadata.put("displayName", "Test Plugin");
        metadata.put("partKey", "test-part");
        metadata.put("activityClassName", "com.termux.shadow.test.MainActivity");
        metadata.put("resourcePackageId", "0x7C");
        metadata.put("minHostVersionCode", 100);
        metadata.put("maxHostVersionCode", 200);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("config.json", (config.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        entries.put("termux-shadow.json", (metadata.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        entries.put("loader.apk", loader);
        entries.put("runtime.apk", runtime);
        entries.put("plugin.apk", plugin);
        StringBuilder checksums = new StringBuilder();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String digest = sha256(entry.getValue());
            if (tamperChecksum && entry.getKey().equals("plugin.apk")) {
                digest = repeat('0', 64);
            }
            checksums.append(digest).append("  ").append(entry.getKey()).append('\n');
        }
        entries.put("checksums.sha256", checksums.toString().getBytes(StandardCharsets.UTF_8));
        if (unsafePath) {
            entries.put("../escape.txt", "bad".getBytes(StandardCharsets.UTF_8));
        }

        File bundle = temporaryFolder.newFile("plugin.shadowpkg");
        ZipOutputStream output = new ZipOutputStream(new FileOutputStream(bundle));
        try {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        } finally {
            output.close();
        }
        return bundle;
    }

    private static JSONObject fileConfig(String name, byte[] contents) throws Exception {
        JSONObject object = new JSONObject();
        object.put("apkName", name);
        object.put("hash", digest(contents, "MD5").toUpperCase(Locale.US));
        return object;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return digest(bytes, "SHA-256");
    }

    private static String digest(byte[] bytes, String algorithm) throws Exception {
        byte[] digest = MessageDigest.getInstance(algorithm).digest(bytes);
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
