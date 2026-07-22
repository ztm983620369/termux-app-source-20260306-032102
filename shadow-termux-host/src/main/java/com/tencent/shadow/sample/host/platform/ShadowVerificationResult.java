package com.tencent.shadow.sample.host.platform;

public final class ShadowVerificationResult {

    public final ShadowPluginManifest manifest;
    public final String bundleSha256;
    public final String generation;
    public final ShadowTrustLevel trustLevel;

    ShadowVerificationResult(
            ShadowPluginManifest manifest,
            String bundleSha256,
            String generation,
            ShadowTrustLevel trustLevel
    ) {
        this.manifest = manifest;
        this.bundleSha256 = bundleSha256;
        this.generation = generation;
        this.trustLevel = trustLevel;
    }
}
