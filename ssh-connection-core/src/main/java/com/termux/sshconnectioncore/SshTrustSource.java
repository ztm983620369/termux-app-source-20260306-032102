package com.termux.sshconnectioncore;

public enum SshTrustSource {
    USER_APPROVED,
    USER_REPLACED,
    IMPORTED_OPENSSH,
    IMPORTED_APP_STORE,
    LEGACY_AUTO_TRUSTED
}
