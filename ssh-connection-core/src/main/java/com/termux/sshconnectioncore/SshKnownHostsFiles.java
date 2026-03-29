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
        if (identity.isEmpty()) return "";
        if (endpoint.port != 22 || identity.contains(":")) {
            return "[" + identity + "]:" + endpoint.port;
        }
        return identity;
    }
}
