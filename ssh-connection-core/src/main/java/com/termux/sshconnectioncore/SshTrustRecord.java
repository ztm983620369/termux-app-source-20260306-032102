package com.termux.sshconnectioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Objects;

public final class SshTrustRecord {

    @NonNull public final String authorityKey;
    @NonNull public final String hostIdentity;
    public final int port;
    @NonNull public final String algorithm;
    @NonNull public final String fingerprintSha256;
    @NonNull public final SshTrustSource source;
    public final long trustedAtMs;
    public final long lastSeenAtMs;

    public SshTrustRecord(@NonNull String authorityKey,
                          @NonNull String hostIdentity,
                          int port,
                          @NonNull String algorithm,
                          @NonNull String fingerprintSha256,
                          @NonNull SshTrustSource source,
                          long trustedAtMs,
                          long lastSeenAtMs) {
        this.authorityKey = safe(authorityKey);
        this.hostIdentity = safe(hostIdentity).toLowerCase(Locale.ROOT);
        this.port = normalizePort(port);
        this.algorithm = safe(algorithm);
        this.fingerprintSha256 = safe(fingerprintSha256).toLowerCase(Locale.ROOT);
        this.source = source == null ? SshTrustSource.LEGACY_AUTO_TRUSTED : source;
        this.trustedAtMs = Math.max(0L, trustedAtMs);
        this.lastSeenAtMs = Math.max(0L, lastSeenAtMs);
    }

    @NonNull
    public SshTrustRecord withLastSeenAtMs(long atMs) {
        return new SshTrustRecord(
            authorityKey,
            hostIdentity,
            port,
            algorithm,
            fingerprintSha256,
            source,
            trustedAtMs,
            Math.max(0L, atMs)
        );
    }

    @NonNull
    public SshTrustRecord replaceWith(@NonNull String newAlgorithm,
                                      @NonNull String newFingerprintSha256,
                                      @NonNull SshTrustSource newSource,
                                      long atMs) {
        long normalizedAtMs = Math.max(0L, atMs);
        return new SshTrustRecord(
            authorityKey,
            hostIdentity,
            port,
            newAlgorithm,
            newFingerprintSha256,
            newSource,
            normalizedAtMs,
            normalizedAtMs
        );
    }

    public boolean matchesObserved(@Nullable String observedAlgorithm,
                                   @Nullable String observedFingerprintSha256) {
        return algorithm.equals(safe(observedAlgorithm))
            && fingerprintSha256.equals(safe(observedFingerprintSha256).toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SshTrustRecord)) return false;
        SshTrustRecord that = (SshTrustRecord) other;
        return port == that.port
            && trustedAtMs == that.trustedAtMs
            && lastSeenAtMs == that.lastSeenAtMs
            && Objects.equals(authorityKey, that.authorityKey)
            && Objects.equals(hostIdentity, that.hostIdentity)
            && Objects.equals(algorithm, that.algorithm)
            && Objects.equals(fingerprintSha256, that.fingerprintSha256)
            && source == that.source;
    }

    @Override
    public int hashCode() {
        return Objects.hash(authorityKey, hostIdentity, port, algorithm, fingerprintSha256,
            source, trustedAtMs, lastSeenAtMs);
    }

    private static int normalizePort(int port) {
        return port > 0 && port <= 65535 ? port : 22;
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
