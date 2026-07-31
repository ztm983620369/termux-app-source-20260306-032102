package com.termux.sshconnectioncore;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.File;

public final class SshKnownHostsFiles {

    private static final String MANAGED_DIR_RELATIVE = ".termux/ssh-trust";
    private static final String MANAGED_KNOWN_HOSTS_NAME = "known_hosts";

    private SshKnownHostsFiles() {
    }

    @NonNull
    public static File resolveManagedKnownHostsFile(@NonNull Context context) {
        File root = new File(context.getApplicationContext().getFilesDir(), MANAGED_DIR_RELATIVE);
        if (!root.exists()) root.mkdirs();
        return new File(root, MANAGED_KNOWN_HOSTS_NAME);
    }

    @NonNull
    public static String resolveManagedKnownHostsPath(@NonNull Context context) {
        return resolveManagedKnownHostsFile(context).getAbsolutePath();
    }

    @NonNull
    public static File resolveLegacyUserKnownHostsFile(@NonNull Context context) {
        return new File(new File(context.getApplicationContext().getFilesDir(), "home/.ssh"), "known_hosts");
    }

    @NonNull
    public static String buildKnownHostsHostPattern(@NonNull ResolvedSshEndpoint endpoint) {
        String identity = endpoint.hostIdentity.isEmpty() ? endpoint.host : endpoint.hostIdentity;
        if (!isSafeKnownHostsIdentity(identity)) return "";
        // OpenSSH's get_hostfile_hostname_ipaddr() uses HostKeyAlias verbatim. Without an alias,
        // put_host_port() adds brackets and a port only for non-default ports; a default-port IPv6
        // literal remains unbracketed. These details must match exactly or strict checking fails.
        if (endpoint.usesHostKeyAlias) return identity;
        if (endpoint.port != 22) {
            return "[" + identity + "]:" + endpoint.port;
        }
        return identity;
    }

    /**
     * Host fields in known_hosts are pattern lists, not opaque strings. Reject syntax that would
     * turn one trusted key into a wildcard, negated, multi-host, or multi-line entry.
     */
    public static boolean isSafeKnownHostsIdentity(@NonNull String identity) {
        if (identity.isEmpty()) return false;
        for (int i = 0; i < identity.length(); i++) {
            char ch = identity.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) return false;
            if (ch == ',' || ch == '*' || ch == '?' || ch == '!' || ch == '[' || ch == ']'
                || ch == '\\' || ch == '|') return false;
        }
        return true;
    }
}
