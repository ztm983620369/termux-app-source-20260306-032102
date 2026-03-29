package com.termux.sessionsync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.sshconnectioncore.LegacySshCommandProfileResolver;
import com.termux.sshconnectioncore.ResolvedSshEndpoint;
import com.termux.sshconnectioncore.SshProfileResolutionResult;

final class SessionEntrySshEndpointResolver {

    private SessionEntrySshEndpointResolver() {
    }

    @Nullable
    static ResolvedSshEndpoint resolve(@Nullable SessionEntry entry) {
        if (entry == null) return null;
        SshProfileResolutionResult result = LegacySshCommandProfileResolver.resolve(entry.id, entry.sshCommand);
        return result.success ? result.endpoint : null;
    }

    @NonNull
    static ResolvedSshEndpoint fallback(@NonNull SessionEntry entry,
                                        @NonNull String host,
                                        int port,
                                        @NonNull String user,
                                        @Nullable String identityPath) {
        return new ResolvedSshEndpoint.Builder()
            .setProfileId(entry.id)
            .setHost(host)
            .setHostIdentity(host)
            .setPort(port)
            .setUser(user)
            .setIdentityPath(identityPath)
            .setCanonicalSshCommand(entry.sshCommand)
            .setRawSshCommand(entry.sshCommand)
            .setHostKeyVerificationMode(ResolvedSshEndpoint.HostKeyVerificationMode.YES)
            .build();
    }
}
