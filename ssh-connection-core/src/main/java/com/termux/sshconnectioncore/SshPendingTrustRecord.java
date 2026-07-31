package com.termux.sshconnectioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Objects;

public final class SshPendingTrustRecord {

    @NonNull public final String authorityKey;
    @NonNull public final String hostIdentity;
    public final int port;
    public final boolean usesHostKeyAlias;
    @NonNull public final String algorithm;
    @NonNull public final String observedFingerprintSha256;
    @NonNull public final String existingFingerprintSha256;
    public final boolean replacementRequired;
    public final long observedAtMs;

    public SshPendingTrustRecord(@NonNull String authorityKey,
                                 @NonNull String hostIdentity,
                                 int port,
                                 @NonNull String algorithm,
                                 @NonNull String observedFingerprintSha256,
                                 @Nullable String existingFingerprintSha256,
                                 boolean replacementRequired,
                                 long observedAtMs) {
        this(authorityKey, hostIdentity, port, false, algorithm, observedFingerprintSha256,
            existingFingerprintSha256, replacementRequired, observedAtMs);
    }

    public SshPendingTrustRecord(@NonNull String authorityKey,
                                 @NonNull String hostIdentity,
                                 int port,
                                 boolean usesHostKeyAlias,
                                 @NonNull String algorithm,
                                 @NonNull String observedFingerprintSha256,
                                 @Nullable String existingFingerprintSha256,
                                 boolean replacementRequired,
                                 long observedAtMs) {
        this.authorityKey = safe(authorityKey).toLowerCase(Locale.ROOT);
        this.hostIdentity = safe(hostIdentity).toLowerCase(Locale.ROOT);
        this.port = port > 0 && port <= 65535 ? port : 22;
        this.usesHostKeyAlias = usesHostKeyAlias;
        this.algorithm = safe(algorithm);
        this.observedFingerprintSha256 = SshHostKeyFingerprint.normalizeSha256(observedFingerprintSha256);
        this.existingFingerprintSha256 = SshHostKeyFingerprint.normalizeSha256(existingFingerprintSha256);
        this.replacementRequired = replacementRequired;
        this.observedAtMs = Math.max(0L, observedAtMs);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SshPendingTrustRecord)) return false;
        SshPendingTrustRecord that = (SshPendingTrustRecord) other;
        return port == that.port
            && usesHostKeyAlias == that.usesHostKeyAlias
            && replacementRequired == that.replacementRequired
            && observedAtMs == that.observedAtMs
            && Objects.equals(authorityKey, that.authorityKey)
            && Objects.equals(hostIdentity, that.hostIdentity)
            && Objects.equals(algorithm, that.algorithm)
            && Objects.equals(observedFingerprintSha256, that.observedFingerprintSha256)
            && Objects.equals(existingFingerprintSha256, that.existingFingerprintSha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(authorityKey, hostIdentity, port, usesHostKeyAlias, algorithm,
            observedFingerprintSha256, existingFingerprintSha256, replacementRequired, observedAtMs);
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
