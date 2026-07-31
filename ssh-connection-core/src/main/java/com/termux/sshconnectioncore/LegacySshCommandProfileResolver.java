package com.termux.sshconnectioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/** Resolves the endpoint represented by a legacy OpenSSH profile command. */
public final class LegacySshCommandProfileResolver {

    private LegacySshCommandProfileResolver() {
    }

    @NonNull
    public static SshProfileResolutionResult resolve(@Nullable String profileId,
                                                     @Nullable String rawSshCommand) {
        String command = rawSshCommand == null ? "" : rawSshCommand.trim();
        if (command.isEmpty()) {
            return failure("missing ssh command");
        }

        final OpenSshCommand parsed;
        try {
            parsed = OpenSshCommand.parse(command);
        } catch (IllegalArgumentException error) {
            return failure("invalid ssh command: " + safe(error.getMessage()));
        }

        String destination = parsed.destination();
        String user = valueOrEmpty(parsed.firstOptionValue("User", 'l'));
        String identityPath = valueOrEmpty(parsed.firstOptionValue("IdentityFile", 'i'));
        String rawPort = parsed.firstOptionValue("Port", 'p');
        int port = 22;
        if (rawPort != null) {
            port = parsePort(rawPort);
            if (port < 0) return failure("invalid SSH port");
        }

        int at = destination.indexOf('@');
        String host = destination;
        if (at >= 0) {
            if (at == 0 || at == destination.length() - 1 || destination.indexOf('@', at + 1) >= 0) {
                return failure("invalid SSH destination");
            }
            if (user.isEmpty()) user = destination.substring(0, at);
            host = destination.substring(at + 1);
        }
        if (host.startsWith("[") || host.endsWith("]")) {
            if (!(host.startsWith("[") && host.endsWith("]") && host.length() > 2)) {
                return failure("invalid bracketed SSH destination");
            }
            host = host.substring(1, host.length() - 1);
        }

        String hostKeyAlias = valueOrEmpty(parsed.firstOpenSshOptionValue("HostKeyAlias"));
        String userKnownHostsPath = valueOrEmpty(parsed.firstOpenSshOptionValue("UserKnownHostsFile"));
        String rawVerificationMode = parsed.firstOpenSshOptionValue("StrictHostKeyChecking");
        ResolvedSshEndpoint.HostKeyVerificationMode verificationMode =
            ResolvedSshEndpoint.HostKeyVerificationMode.UNKNOWN;
        if (rawVerificationMode != null) {
            verificationMode = parseVerificationMode(rawVerificationMode);
            if (verificationMode == ResolvedSshEndpoint.HostKeyVerificationMode.UNKNOWN) {
                return failure("invalid StrictHostKeyChecking value");
            }
        }

        if (user.isEmpty() || host.isEmpty()) return failure("missing host or user");
        if (!isSafeEndpointPart(user) || !isSafeEndpointPart(host)) {
            return failure("unsafe host or user value");
        }
        if (!SshKnownHostsFiles.isSafeKnownHostsIdentity(host)) {
            return failure("unsafe host value");
        }
        if (!hostKeyAlias.isEmpty() && !SshKnownHostsFiles.isSafeKnownHostsIdentity(hostKeyAlias)) {
            return failure("unsafe HostKeyAlias");
        }

        String hostIdentity = hostKeyAlias.isEmpty() ? host : hostKeyAlias;
        ResolvedSshEndpoint endpoint = new ResolvedSshEndpoint.Builder()
            .setProfileId(profileId)
            .setHost(host)
            .setHostIdentity(hostIdentity)
            .setPort(port)
            .setUsesHostKeyAlias(!hostKeyAlias.isEmpty())
            .setUser(user)
            .setIdentityPath(identityPath)
            // The canonical command is the non-evaluating form consumed by callers that launch
            // profile commands. The original text remains available for diagnostics/migration.
            .setCanonicalSshCommand(parsed.renderBaseCommand())
            .setRawSshCommand(command)
            .setUserKnownHostsPath(userKnownHostsPath)
            .setHostKeyVerificationMode(verificationMode)
            .build();
        return SshProfileResolutionResult.success(endpoint);
    }

    @NonNull
    private static SshProfileResolutionResult failure(@NonNull String detail) {
        return SshProfileResolutionResult.failure(detail, SshConnectionFailureCategory.INVALID_PROFILE);
    }

    private static int parsePort(@Nullable String raw) {
        try {
            int parsed = Integer.parseInt(valueOrEmpty(raw));
            return parsed > 0 && parsed <= 65535 ? parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    @NonNull
    private static ResolvedSshEndpoint.HostKeyVerificationMode parseVerificationMode(
        @Nullable String raw) {
        String value = valueOrEmpty(raw).toLowerCase(Locale.ROOT);
        if ("yes".equals(value)) return ResolvedSshEndpoint.HostKeyVerificationMode.YES;
        if ("accept-new".equals(value)) return ResolvedSshEndpoint.HostKeyVerificationMode.ACCEPT_NEW;
        if ("ask".equals(value)) return ResolvedSshEndpoint.HostKeyVerificationMode.ASK;
        if ("no".equals(value) || "off".equals(value)) return ResolvedSshEndpoint.HostKeyVerificationMode.NO;
        return ResolvedSshEndpoint.HostKeyVerificationMode.UNKNOWN;
    }

    private static boolean isSafeEndpointPart(@NonNull String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch) || ch == '@') return false;
        }
        return true;
    }

    @NonNull
    private static String valueOrEmpty(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
