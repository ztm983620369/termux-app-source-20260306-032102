package com.termux.sshconnectioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Objects;

public final class ResolvedSshEndpoint {

    public enum HostKeyVerificationMode {
        YES,
        ACCEPT_NEW,
        ASK,
        NO,
        UNKNOWN
    }

    @NonNull public final String profileId;
    @NonNull public final String authorityKey;
    @NonNull public final String hostIdentity;
    @NonNull public final String host;
    public final int port;
    @NonNull public final String user;
    @NonNull public final String identityPath;
    @NonNull public final String canonicalSshCommand;
    @NonNull public final String rawSshCommand;
    @NonNull public final String userKnownHostsPath;
    @NonNull public final HostKeyVerificationMode hostKeyVerificationMode;

    private ResolvedSshEndpoint(@NonNull Builder builder) {
        this.profileId = safe(builder.profileId);
        this.hostIdentity = normalizeHostIdentity(builder.hostIdentity, builder.host);
        this.host = safe(builder.host);
        this.port = normalizePort(builder.port);
        this.user = safe(builder.user);
        this.identityPath = safe(builder.identityPath);
        this.canonicalSshCommand = safe(builder.canonicalSshCommand);
        this.rawSshCommand = safe(builder.rawSshCommand);
        this.userKnownHostsPath = safe(builder.userKnownHostsPath);
        this.hostKeyVerificationMode = builder.hostKeyVerificationMode == null
            ? HostKeyVerificationMode.UNKNOWN
            : builder.hostKeyVerificationMode;
        this.authorityKey = buildAuthorityKey(
            safe(builder.authorityKey),
            this.hostIdentity,
            this.port
        );
    }

    @NonNull
    public Builder buildUpon() {
        return new Builder()
            .setProfileId(profileId)
            .setAuthorityKey(authorityKey)
            .setHostIdentity(hostIdentity)
            .setHost(host)
            .setPort(port)
            .setUser(user)
            .setIdentityPath(identityPath)
            .setCanonicalSshCommand(canonicalSshCommand)
            .setRawSshCommand(rawSshCommand)
            .setUserKnownHostsPath(userKnownHostsPath)
            .setHostKeyVerificationMode(hostKeyVerificationMode);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ResolvedSshEndpoint)) return false;
        ResolvedSshEndpoint that = (ResolvedSshEndpoint) other;
        return port == that.port
            && Objects.equals(profileId, that.profileId)
            && Objects.equals(authorityKey, that.authorityKey)
            && Objects.equals(hostIdentity, that.hostIdentity)
            && Objects.equals(host, that.host)
            && Objects.equals(user, that.user)
            && Objects.equals(identityPath, that.identityPath)
            && Objects.equals(canonicalSshCommand, that.canonicalSshCommand)
            && Objects.equals(rawSshCommand, that.rawSshCommand)
            && Objects.equals(userKnownHostsPath, that.userKnownHostsPath)
            && hostKeyVerificationMode == that.hostKeyVerificationMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(profileId, authorityKey, hostIdentity, host, port, user,
            identityPath, canonicalSshCommand, rawSshCommand, userKnownHostsPath,
            hostKeyVerificationMode);
    }

    public static final class Builder {
        @Nullable private String profileId;
        @Nullable private String authorityKey;
        @Nullable private String hostIdentity;
        @Nullable private String host;
        private int port = 22;
        @Nullable private String user;
        @Nullable private String identityPath;
        @Nullable private String canonicalSshCommand;
        @Nullable private String rawSshCommand;
        @Nullable private String userKnownHostsPath;
        @Nullable private HostKeyVerificationMode hostKeyVerificationMode;

        @NonNull
        public Builder setProfileId(@Nullable String profileId) {
            this.profileId = profileId;
            return this;
        }

        @NonNull
        public Builder setAuthorityKey(@Nullable String authorityKey) {
            this.authorityKey = authorityKey;
            return this;
        }

        @NonNull
        public Builder setHostIdentity(@Nullable String hostIdentity) {
            this.hostIdentity = hostIdentity;
            return this;
        }

        @NonNull
        public Builder setHost(@Nullable String host) {
            this.host = host;
            return this;
        }

        @NonNull
        public Builder setPort(int port) {
            this.port = port;
            return this;
        }

        @NonNull
        public Builder setUser(@Nullable String user) {
            this.user = user;
            return this;
        }

        @NonNull
        public Builder setIdentityPath(@Nullable String identityPath) {
            this.identityPath = identityPath;
            return this;
        }

        @NonNull
        public Builder setCanonicalSshCommand(@Nullable String canonicalSshCommand) {
            this.canonicalSshCommand = canonicalSshCommand;
            return this;
        }

        @NonNull
        public Builder setRawSshCommand(@Nullable String rawSshCommand) {
            this.rawSshCommand = rawSshCommand;
            return this;
        }

        @NonNull
        public Builder setUserKnownHostsPath(@Nullable String userKnownHostsPath) {
            this.userKnownHostsPath = userKnownHostsPath;
            return this;
        }

        @NonNull
        public Builder setHostKeyVerificationMode(@Nullable HostKeyVerificationMode hostKeyVerificationMode) {
            this.hostKeyVerificationMode = hostKeyVerificationMode;
            return this;
        }

        @NonNull
        public ResolvedSshEndpoint build() {
            return new ResolvedSshEndpoint(this);
        }
    }

    @NonNull
    private static String buildAuthorityKey(@NonNull String explicitKey, @NonNull String hostIdentity, int port) {
        if (!explicitKey.isEmpty()) return explicitKey;
        return "ssh://" + hostIdentity + ":" + normalizePort(port);
    }

    @NonNull
    private static String normalizeHostIdentity(@Nullable String hostIdentity, @Nullable String host) {
        String value = safe(hostIdentity);
        if (value.isEmpty()) value = safe(host);
        return value.toLowerCase(Locale.ROOT);
    }

    private static int normalizePort(int port) {
        return port > 0 && port <= 65535 ? port : 22;
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
