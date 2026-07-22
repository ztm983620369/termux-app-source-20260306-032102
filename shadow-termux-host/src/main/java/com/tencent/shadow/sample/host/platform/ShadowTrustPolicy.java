package com.tencent.shadow.sample.host.platform;

import android.util.Base64;

import com.tencent.shadow.sample.host.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class ShadowTrustPolicy {

    private static final int POLICY_SCHEMA_VERSION = 3;
    private static final int MAX_CONFIG_BYTES = 256 * 1024;
    private static final long DEFAULT_MAX_BUNDLE_BYTES = 256L * 1024L * 1024L;
    private static final long LEGACY_LAUNCH_HEALTH_TIMEOUT_MS = 120_000L;
    private static final long DEFAULT_LAUNCH_HEALTH_TIMEOUT_MS = 15_000L;
    private static final long DEFAULT_LAUNCH_STABILITY_WINDOW_MS = 1_500L;

    private final boolean requireSignature;
    private final int maxVersionsPerPlugin;
    private final long maxBundleBytes;
    private final int launchFailureThreshold;
    private final long launchHealthTimeoutMs;
    private final long launchStabilityWindowMs;
    private final Map<String, PublicKey> trustedKeys;

    private ShadowTrustPolicy(
            boolean requireSignature,
            int maxVersionsPerPlugin,
            long maxBundleBytes,
            int launchFailureThreshold,
            long launchHealthTimeoutMs,
            long launchStabilityWindowMs,
            Map<String, PublicKey> trustedKeys
    ) {
        this.requireSignature = requireSignature;
        this.maxVersionsPerPlugin = maxVersionsPerPlugin;
        this.maxBundleBytes = maxBundleBytes;
        this.launchFailureThreshold = launchFailureThreshold;
        this.launchHealthTimeoutMs = launchHealthTimeoutMs;
        this.launchStabilityWindowMs = launchStabilityWindowMs;
        this.trustedKeys = Collections.unmodifiableMap(new HashMap<>(trustedKeys));
    }

    public static ShadowTrustPolicy load(ShadowPaths paths) throws Exception {
        File policyFile = new File(paths.configDir(), "policy.json");
        if (!policyFile.isFile()) {
            JSONObject defaults = new JSONObject();
            defaults.put("schemaVersion", POLICY_SCHEMA_VERSION);
            defaults.put("ingressMode", ShadowPackageContract.INGRESS_MODE);
            defaults.put("packageSchemaVersion", ShadowPackageContract.SCHEMA_VERSION);
            defaults.put("requireSignature", !BuildConfig.DEBUG);
            defaults.put("maxVersionsPerPlugin", 3);
            defaults.put("maxBundleBytes", DEFAULT_MAX_BUNDLE_BYTES);
            defaults.put("launchFailureThreshold", 3);
            defaults.put("launchHealthTimeoutMs", DEFAULT_LAUNCH_HEALTH_TIMEOUT_MS);
            defaults.put("launchStabilityWindowMs", DEFAULT_LAUNCH_STABILITY_WINDOW_MS);
            ShadowFileOps.writeAtomically(
                    policyFile,
                    defaults.toString(2).getBytes(StandardCharsets.UTF_8),
                    false
            );
        }

        File trustedKeysFile = new File(paths.configDir(), "trusted-keys.json");
        if (!trustedKeysFile.isFile()) {
            JSONObject emptyKeys = new JSONObject();
            emptyKeys.put("schemaVersion", 1);
            emptyKeys.put("keys", new JSONArray());
            ShadowFileOps.writeAtomically(
                    trustedKeysFile,
                    emptyKeys.toString(2).getBytes(StandardCharsets.UTF_8),
                    false
            );
        }

        JSONObject policy = new JSONObject(new String(readSmallFile(policyFile), StandardCharsets.UTF_8));
        int policySchema = policy.optInt("schemaVersion", -1);
        if (policySchema < 1 || policySchema > POLICY_SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported Shadow policy schema");
        }
        if (normalizeSingleIngressPolicy(policy)) {
            ShadowFileOps.writeAtomically(
                    policyFile,
                    policy.toString(2).getBytes(StandardCharsets.UTF_8),
                    false
            );
        }

        int maxVersions = policy.optInt("maxVersionsPerPlugin", 3);
        maxVersions = Math.max(2, Math.min(maxVersions, 20));
        long maxBytes = policy.optLong("maxBundleBytes", DEFAULT_MAX_BUNDLE_BYTES);
        maxBytes = Math.max(1024L * 1024L, Math.min(maxBytes, 2L * 1024L * 1024L * 1024L));
        int failureThreshold = policy.optInt("launchFailureThreshold", 3);
        failureThreshold = Math.max(1, Math.min(failureThreshold, 20));
        long healthTimeoutMs = policy.optLong(
                "launchHealthTimeoutMs",
                DEFAULT_LAUNCH_HEALTH_TIMEOUT_MS
        );
        healthTimeoutMs = Math.max(5_000L, Math.min(healthTimeoutMs, 600_000L));
        long stabilityWindowMs = policy.optLong(
                "launchStabilityWindowMs",
                DEFAULT_LAUNCH_STABILITY_WINDOW_MS
        );
        stabilityWindowMs = Math.max(500L, Math.min(stabilityWindowMs, 10_000L));

        Map<String, PublicKey> keys = loadTrustedKeys(trustedKeysFile);
        return new ShadowTrustPolicy(
                policy.optBoolean("requireSignature", !BuildConfig.DEBUG),
                maxVersions,
                maxBytes,
                failureThreshold,
                healthTimeoutMs,
                stabilityWindowMs,
                keys
        );
    }

    static ShadowTrustPolicy forTesting(
            boolean requireSignature,
            long maxBundleBytes
    ) {
        return new ShadowTrustPolicy(
                requireSignature,
                3,
                maxBundleBytes,
                3,
                DEFAULT_LAUNCH_HEALTH_TIMEOUT_MS,
                DEFAULT_LAUNCH_STABILITY_WINDOW_MS,
                Collections.<String, PublicKey>emptyMap()
        );
    }

    public boolean requireSignature() {
        return requireSignature;
    }

    public int maxVersionsPerPlugin() {
        return maxVersionsPerPlugin;
    }

    public long maxBundleBytes() {
        return maxBundleBytes;
    }

    public int launchFailureThreshold() {
        return launchFailureThreshold;
    }

    public long launchHealthTimeoutMs() {
        return launchHealthTimeoutMs;
    }

    public long launchStabilityWindowMs() {
        return launchStabilityWindowMs;
    }

    static boolean normalizeSingleIngressPolicy(JSONObject policy) throws Exception {
        int sourceSchema = policy.optInt("schemaVersion", -1);
        boolean changed = sourceSchema != POLICY_SCHEMA_VERSION;
        changed |= policy.remove("allowLegacyPackages") != null;
        changed |= policy.remove("autoImportLegacy") != null;
        if (!ShadowPackageContract.INGRESS_MODE.equals(policy.optString("ingressMode", ""))) {
            policy.put("ingressMode", ShadowPackageContract.INGRESS_MODE);
            changed = true;
        }
        if (policy.optInt("packageSchemaVersion", -1) != ShadowPackageContract.SCHEMA_VERSION) {
            policy.put("packageSchemaVersion", ShadowPackageContract.SCHEMA_VERSION);
            changed = true;
        }
        if (sourceSchema < 3) {
            long existingTimeout = policy.optLong(
                    "launchHealthTimeoutMs",
                    LEGACY_LAUNCH_HEALTH_TIMEOUT_MS
            );
            if (!policy.has("launchHealthTimeoutMs")
                    || existingTimeout == LEGACY_LAUNCH_HEALTH_TIMEOUT_MS) {
                policy.put("launchHealthTimeoutMs", DEFAULT_LAUNCH_HEALTH_TIMEOUT_MS);
                changed = true;
            }
            if (!policy.has("launchStabilityWindowMs")) {
                policy.put(
                        "launchStabilityWindowMs",
                        DEFAULT_LAUNCH_STABILITY_WINDOW_MS
                );
                changed = true;
            }
        }
        if (changed) {
            policy.put("schemaVersion", POLICY_SCHEMA_VERSION);
        }
        return changed;
    }

    public ShadowTrustLevel verifySignature(byte[] signedBytes, JSONObject signatureObject)
            throws Exception {
        if (signatureObject == null) {
            if (requireSignature) {
                throw new SecurityException("Shadow package signature is required");
            }
            return ShadowTrustLevel.INTEGRITY_VERIFIED;
        }

        if (signatureObject.optInt("schemaVersion", -1) != 1) {
            throw new SecurityException("Unsupported Shadow signature schema");
        }
        String algorithm = required(signatureObject, "algorithm");
        if (!"SHA256withRSA".equals(algorithm)) {
            throw new SecurityException("Unsupported Shadow signature algorithm: " + algorithm);
        }
        String keyId = required(signatureObject, "keyId");
        PublicKey key = trustedKeys.get(keyId);
        if (key == null) {
            throw new SecurityException("Untrusted Shadow signing key: " + keyId);
        }
        byte[] signatureBytes = Base64.decode(required(signatureObject, "signature"), Base64.DEFAULT);
        Signature verifier = Signature.getInstance(algorithm);
        verifier.initVerify(key);
        verifier.update(signedBytes);
        if (!verifier.verify(signatureBytes)) {
            throw new SecurityException("Invalid Shadow package signature for key: " + keyId);
        }
        return ShadowTrustLevel.TRUSTED_SIGNATURE;
    }

    private static Map<String, PublicKey> loadTrustedKeys(File file) throws Exception {
        JSONObject root = new JSONObject(new String(readSmallFile(file), StandardCharsets.UTF_8));
        if (root.optInt("schemaVersion", -1) != 1) {
            throw new IllegalStateException("Unsupported trusted-keys schema");
        }
        JSONArray array = root.optJSONArray("keys");
        Map<String, PublicKey> keys = new HashMap<>();
        if (array == null) {
            return keys;
        }
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index);
            String keyId = required(item, "keyId");
            if (keys.containsKey(keyId)) {
                throw new IllegalStateException("Duplicate trusted key id: " + keyId);
            }
            byte[] encoded = Base64.decode(required(item, "publicKey"), Base64.DEFAULT);
            keys.put(keyId, keyFactory.generatePublic(new X509EncodedKeySpec(encoded)));
        }
        return keys;
    }

    private static String required(JSONObject object, String key) {
        String value = object.optString(key, "").trim();
        if (value.length() == 0) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }

    private static byte[] readSmallFile(File file) throws IOException {
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_CONFIG_BYTES) {
                    throw new IOException("Shadow config is too large: " + file);
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
