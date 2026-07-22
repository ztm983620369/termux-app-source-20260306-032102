package com.tencent.shadow.sample.host.platform;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShadowTrustPolicyTest {

    @Test
    public void migratesOldPolicyToSingleIngressSchema() throws Exception {
        JSONObject policy = new JSONObject();
        policy.put("schemaVersion", 1);
        policy.put("requireSignature", false);
        policy.put("allowLegacyPackages", true);
        policy.put("autoImportLegacy", true);
        policy.put("maxVersionsPerPlugin", 4);

        assertTrue(ShadowTrustPolicy.normalizeSingleIngressPolicy(policy));
        assertEquals(3, policy.getInt("schemaVersion"));
        assertEquals(ShadowPackageContract.INGRESS_MODE, policy.getString("ingressMode"));
        assertEquals(ShadowPackageContract.SCHEMA_VERSION,
                policy.getInt("packageSchemaVersion"));
        assertFalse(policy.has("allowLegacyPackages"));
        assertFalse(policy.has("autoImportLegacy"));
        assertFalse(policy.getBoolean("requireSignature"));
        assertEquals(4, policy.getInt("maxVersionsPerPlugin"));
        assertEquals(15_000L, policy.getLong("launchHealthTimeoutMs"));
        assertEquals(1_500L, policy.getLong("launchStabilityWindowMs"));
        assertFalse(ShadowTrustPolicy.normalizeSingleIngressPolicy(policy));
    }

    @Test
    public void migratesLegacyDefaultTimeoutButPreservesExplicitCustomTimeout() throws Exception {
        JSONObject legacyDefault = new JSONObject();
        legacyDefault.put("schemaVersion", 2);
        legacyDefault.put("launchHealthTimeoutMs", 120_000L);
        assertTrue(ShadowTrustPolicy.normalizeSingleIngressPolicy(legacyDefault));
        assertEquals(15_000L, legacyDefault.getLong("launchHealthTimeoutMs"));

        JSONObject custom = new JSONObject();
        custom.put("schemaVersion", 2);
        custom.put("launchHealthTimeoutMs", 42_000L);
        assertTrue(ShadowTrustPolicy.normalizeSingleIngressPolicy(custom));
        assertEquals(42_000L, custom.getLong("launchHealthTimeoutMs"));
    }
}
