package com.termux.sshconnectioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SshCommandKnownHostsOptions {

    private static final String OPTION_USER_KNOWN_HOSTS_FILE = "UserKnownHostsFile";
    private static final String OPTION_GLOBAL_KNOWN_HOSTS_FILE = "GlobalKnownHostsFile";
    private static final String OPTION_HASH_KNOWN_HOSTS = "HashKnownHosts";
    private static final String GLOBAL_KNOWN_HOSTS_DISABLED = "/dev/null";

    private SshCommandKnownHostsOptions() {
    }

    @NonNull
    public static String inject(@Nullable String rawSshCommand, @Nullable String managedKnownHostsPath) {
        String command = rawSshCommand == null ? "" : rawSshCommand.trim();
        String knownHostsPath = managedKnownHostsPath == null ? "" : managedKnownHostsPath.trim();
        if (command.isEmpty() || knownHostsPath.isEmpty()) return command;

        List<String> tokens = splitShell(command);
        if (tokens.isEmpty()) return command;

        int sshIndex = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (isSshExecutable(tokens.get(i))) {
                sshIndex = i;
                break;
            }
        }
        if (sshIndex < 0) return command;

        ArrayList<String> rebuilt = new ArrayList<>();
        for (int i = 0; i < sshIndex + 1; i++) {
            rebuilt.add(tokens.get(i));
        }

        int destinationIndex = tokens.size();
        for (int i = sshIndex + 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token == null || token.trim().isEmpty()) continue;

            if ("-o".equals(token) && i + 1 < tokens.size()) {
                ParsedOption option = parseOption(tokens.get(i + 1));
                if (shouldSkipOption(option.name)) {
                    i++;
                    continue;
                }
                rebuilt.add(token);
                rebuilt.add(tokens.get(i + 1));
                i++;
                continue;
            }
            if (token.startsWith("-o") && token.length() > 2) {
                ParsedOption option = parseOption(token.substring(2));
                if (shouldSkipOption(option.name)) {
                    continue;
                }
                rebuilt.add(token);
                continue;
            }

            if (token.startsWith("-")) {
                rebuilt.add(token);
                if (hasSeparateValue(token) && i + 1 < tokens.size()) {
                    rebuilt.add(tokens.get(i + 1));
                    i++;
                }
                continue;
            }

            destinationIndex = i;
            break;
        }

        rebuilt.add("-o");
        rebuilt.add(OPTION_USER_KNOWN_HOSTS_FILE + "=" + knownHostsPath);
        rebuilt.add("-o");
        rebuilt.add(OPTION_GLOBAL_KNOWN_HOSTS_FILE + "=" + GLOBAL_KNOWN_HOSTS_DISABLED);
        rebuilt.add("-o");
        rebuilt.add(OPTION_HASH_KNOWN_HOSTS + "=no");

        for (int i = destinationIndex; i < tokens.size(); i++) {
            rebuilt.add(tokens.get(i));
        }

        return joinShell(rebuilt);
    }

    private static boolean shouldSkipOption(@Nullable String name) {
        String normalized = safe(name).toLowerCase(Locale.ROOT);
        return OPTION_USER_KNOWN_HOSTS_FILE.toLowerCase(Locale.ROOT).equals(normalized)
            || OPTION_GLOBAL_KNOWN_HOSTS_FILE.toLowerCase(Locale.ROOT).equals(normalized)
            || OPTION_HASH_KNOWN_HOSTS.toLowerCase(Locale.ROOT).equals(normalized);
    }

    private static boolean hasSeparateValue(@NonNull String token) {
        return "-b".equals(token) || "-c".equals(token) || "-D".equals(token) || "-E".equals(token)
            || "-F".equals(token) || "-I".equals(token) || "-i".equals(token) || "-J".equals(token)
            || "-L".equals(token) || "-l".equals(token) || "-m".equals(token) || "-O".equals(token)
            || "-p".equals(token) || "-Q".equals(token) || "-R".equals(token) || "-S".equals(token)
            || "-W".equals(token) || "-w".equals(token);
    }

    private static boolean isSshExecutable(@Nullable String token) {
        if (token == null) return false;
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        return "ssh".equals(normalized) || normalized.endsWith("/ssh") || "ssh.exe".equals(normalized);
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
    private static String joinShell(@NonNull List<String> tokens) {
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            if (token == null) continue;
            String value = token.trim();
            if (value.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(quoteArg(value));
        }
        return out.toString();
    }

    @NonNull
    private static String quoteArg(@NonNull String value) {
        if (value.isEmpty()) return "''";
        if (value.matches("[A-Za-z0-9_./:@%+=,-]+")) return value;
        return "'" + value.replace("'", "'\"'\"'") + "'";
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
