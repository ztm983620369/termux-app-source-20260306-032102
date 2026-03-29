package com.termux.sshconnectioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LegacySshCommandProfileResolver {

    private static final String[] SSH_OPTIONS_WITH_VALUE = new String[] {
        "-b", "-c", "-D", "-E", "-F", "-I", "-i", "-J", "-L", "-l",
        "-m", "-o", "-p", "-Q", "-R", "-S", "-W", "-w"
    };

    private LegacySshCommandProfileResolver() {
    }

    @NonNull
    public static SshProfileResolutionResult resolve(@Nullable String profileId,
                                                     @Nullable String rawSshCommand) {
        String command = rawSshCommand == null ? "" : rawSshCommand.trim();
        if (command.isEmpty()) {
            return SshProfileResolutionResult.failure(
                "missing ssh command",
                SshConnectionFailureCategory.INVALID_PROFILE
            );
        }

        List<String> tokens = splitShell(command);
        if (tokens.isEmpty()) {
            return SshProfileResolutionResult.failure(
                "empty ssh command",
                SshConnectionFailureCategory.INVALID_PROFILE
            );
        }

        int sshIndex = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (isSshExecutable(tokens.get(i))) {
                sshIndex = i;
                break;
            }
        }
        if (sshIndex < 0) {
            return SshProfileResolutionResult.failure(
                "ssh executable not found in command",
                SshConnectionFailureCategory.INVALID_PROFILE
            );
        }

        int port = 22;
        String user = "";
        String host = "";
        String identityPath = "";
        String hostKeyAlias = "";
        String userKnownHostsPath = "";
        ResolvedSshEndpoint.HostKeyVerificationMode verificationMode =
            ResolvedSshEndpoint.HostKeyVerificationMode.UNKNOWN;

        for (int i = sshIndex + 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token == null || token.trim().isEmpty()) continue;

            if ("-p".equals(token) && i + 1 < tokens.size()) {
                i++;
                port = parsePort(tokens.get(i), port);
                continue;
            }
            if (token.startsWith("-p") && token.length() > 2) {
                port = parsePort(token.substring(2), port);
                continue;
            }

            if ("-l".equals(token) && i + 1 < tokens.size()) {
                i++;
                user = safe(tokens.get(i));
                continue;
            }
            if (token.startsWith("-l") && token.length() > 2) {
                user = safe(token.substring(2));
                continue;
            }

            if ("-i".equals(token) && i + 1 < tokens.size()) {
                i++;
                identityPath = safe(tokens.get(i));
                continue;
            }
            if (token.startsWith("-i") && token.length() > 2) {
                identityPath = safe(token.substring(2));
                continue;
            }

            if ("-o".equals(token) && i + 1 < tokens.size()) {
                i++;
                ParsedOption option = parseOption(tokens.get(i));
                if ("IdentityFile".equalsIgnoreCase(option.name) && !option.value.isEmpty()) {
                    identityPath = option.value;
                } else if ("UserKnownHostsFile".equalsIgnoreCase(option.name) && !option.value.isEmpty()) {
                    userKnownHostsPath = option.value;
                } else if ("HostKeyAlias".equalsIgnoreCase(option.name) && !option.value.isEmpty()) {
                    hostKeyAlias = option.value;
                } else if ("StrictHostKeyChecking".equalsIgnoreCase(option.name) && !option.value.isEmpty()) {
                    verificationMode = parseVerificationMode(option.value);
                }
                continue;
            }
            if (token.startsWith("-o") && token.length() > 2) {
                ParsedOption option = parseOption(token.substring(2));
                if ("IdentityFile".equalsIgnoreCase(option.name) && !option.value.isEmpty()) {
                    identityPath = option.value;
                } else if ("UserKnownHostsFile".equalsIgnoreCase(option.name) && !option.value.isEmpty()) {
                    userKnownHostsPath = option.value;
                } else if ("HostKeyAlias".equalsIgnoreCase(option.name) && !option.value.isEmpty()) {
                    hostKeyAlias = option.value;
                } else if ("StrictHostKeyChecking".equalsIgnoreCase(option.name) && !option.value.isEmpty()) {
                    verificationMode = parseVerificationMode(option.value);
                }
                continue;
            }

            if (token.startsWith("-")) {
                if (hasOptionValue(token) && i + 1 < tokens.size()) i++;
                continue;
            }

            host = safe(token);
            break;
        }

        if (host.isEmpty()) {
            return SshProfileResolutionResult.failure(
                "missing target host",
                SshConnectionFailureCategory.INVALID_PROFILE
            );
        }

        if (host.contains("@")) {
            int at = host.indexOf('@');
            if (at > 0 && user.isEmpty()) user = safe(host.substring(0, at));
            host = safe(host.substring(at + 1));
        }

        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1);
        }

        if (host.isEmpty() || user.isEmpty()) {
            return SshProfileResolutionResult.failure(
                "missing host or user",
                SshConnectionFailureCategory.INVALID_PROFILE
            );
        }

        String hostIdentity = hostKeyAlias.isEmpty() ? host : hostKeyAlias;
        ResolvedSshEndpoint endpoint = new ResolvedSshEndpoint.Builder()
            .setProfileId(profileId)
            .setHost(host)
            .setHostIdentity(hostIdentity)
            .setPort(port)
            .setUser(user)
            .setIdentityPath(identityPath)
            .setCanonicalSshCommand(command)
            .setRawSshCommand(command)
            .setUserKnownHostsPath(userKnownHostsPath)
            .setHostKeyVerificationMode(verificationMode)
            .build();
        return SshProfileResolutionResult.success(endpoint);
    }

    @NonNull
    private static ParsedOption parseOption(@Nullable String raw) {
        String option = safe(raw);
        int idx = option.indexOf('=');
        if (idx <= 0 || idx >= option.length() - 1) {
            return new ParsedOption(option, "");
        }
        return new ParsedOption(option.substring(0, idx).trim(), option.substring(idx + 1).trim());
    }

    @NonNull
    private static ResolvedSshEndpoint.HostKeyVerificationMode parseVerificationMode(@Nullable String raw) {
        String value = safe(raw).toLowerCase(Locale.ROOT);
        if ("yes".equals(value)) return ResolvedSshEndpoint.HostKeyVerificationMode.YES;
        if ("accept-new".equals(value)) return ResolvedSshEndpoint.HostKeyVerificationMode.ACCEPT_NEW;
        if ("ask".equals(value)) return ResolvedSshEndpoint.HostKeyVerificationMode.ASK;
        if ("no".equals(value) || "off".equals(value)) return ResolvedSshEndpoint.HostKeyVerificationMode.NO;
        return ResolvedSshEndpoint.HostKeyVerificationMode.UNKNOWN;
    }

    private static boolean hasOptionValue(@NonNull String token) {
        for (String candidate : SSH_OPTIONS_WITH_VALUE) {
            if (candidate.equals(token)) return true;
        }
        return false;
    }

    private static int parsePort(@Nullable String raw, int fallback) {
        try {
            int parsed = Integer.parseInt(safe(raw));
            return parsed > 0 && parsed <= 65535 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean isSshExecutable(@Nullable String token) {
        if (token == null) return false;
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        return "ssh".equals(normalized) || normalized.endsWith("/ssh") || "ssh.exe".equals(normalized);
    }

    @NonNull
    private static List<String> splitShell(@NonNull String input) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (escaped) {
                current.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\' && !inSingle) {
                escaped = true;
                continue;
            }
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (Character.isWhitespace(ch) && !inSingle && !inDouble) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }

        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static final class ParsedOption {
        @NonNull final String name;
        @NonNull final String value;

        ParsedOption(@Nullable String name, @Nullable String value) {
            this.name = safe(name);
            this.value = safe(value);
        }
    }
}
