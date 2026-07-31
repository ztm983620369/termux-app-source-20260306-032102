package com.termux.sshconnectioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SshCommandKnownHostsOptions {

    private static final String OPTION_USER_KNOWN_HOSTS_FILE = "UserKnownHostsFile";
    private static final String OPTION_GLOBAL_KNOWN_HOSTS_FILE = "GlobalKnownHostsFile";
    private static final String OPTION_HASH_KNOWN_HOSTS = "HashKnownHosts";
    private static final String OPTION_STRICT_HOST_KEY_CHECKING = "StrictHostKeyChecking";
    private static final String OPTION_KNOWN_HOSTS_COMMAND = "KnownHostsCommand";
    private static final String OPTION_VERIFY_HOST_KEY_DNS = "VerifyHostKeyDNS";
    private static final String OPTION_UPDATE_HOST_KEYS = "UpdateHostKeys";
    private static final String OPTION_CHECK_HOST_IP = "CheckHostIP";
    private static final String GLOBAL_KNOWN_HOSTS_DISABLED = "/dev/null";

    private SshCommandKnownHostsOptions() {
    }

    /**
     * Add the app-owned host-key database and remove every profile-supplied value for the same
     * options. Command-line options are first-wins in OpenSSH, so leaving an earlier option in
     * place would silently bypass the managed trust boundary.
     */
    @NonNull
    public static String inject(@Nullable String rawSshCommand, @Nullable String managedKnownHostsPath) {
        String command = rawSshCommand == null ? "" : rawSshCommand.trim();
        String knownHostsPath = managedKnownHostsPath == null ? "" : managedKnownHostsPath.trim();
        if (command.isEmpty()) return command;
        if (knownHostsPath.isEmpty()) {
            throw new IllegalArgumentException("Managed known-hosts path is required");
        }
        if (containsLineBreakOrNul(knownHostsPath)) {
            throw new IllegalArgumentException("Managed known-hosts path must be one line");
        }

        OpenSshCommand parsed = OpenSshCommand.parse(command);
        Set<String> replaced = new HashSet<>(Arrays.asList(
            OPTION_USER_KNOWN_HOSTS_FILE,
            OPTION_GLOBAL_KNOWN_HOSTS_FILE,
            OPTION_HASH_KNOWN_HOSTS,
            OPTION_STRICT_HOST_KEY_CHECKING,
            OPTION_KNOWN_HOSTS_COMMAND,
            OPTION_VERIFY_HOST_KEY_DNS,
            OPTION_UPDATE_HOST_KEYS,
            OPTION_CHECK_HOST_IP
        ));
        List<String> managed = Arrays.asList(
            "-o", OPTION_USER_KNOWN_HOSTS_FILE + "=" + knownHostsPath,
            "-o", OPTION_GLOBAL_KNOWN_HOSTS_FILE + "=" + GLOBAL_KNOWN_HOSTS_DISABLED,
            "-o", OPTION_HASH_KNOWN_HOSTS + "=no",
            "-o", OPTION_STRICT_HOST_KEY_CHECKING + "=yes",
            "-o", OPTION_KNOWN_HOSTS_COMMAND + "=none",
            "-o", OPTION_VERIFY_HOST_KEY_DNS + "=no",
            "-o", OPTION_UPDATE_HOST_KEYS + "=no",
            "-o", OPTION_CHECK_HOST_IP + "=no"
        );
        return parsed.renderReplacingOpenSshOptions(replaced, managed);
    }

    private static boolean containsLineBreakOrNul(@NonNull String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\0' || ch == '\n' || ch == '\r') return true;
        }
        return false;
    }
}
