package com.termux.sshconnectioncore;

public enum SshControlAction {
    NONE,
    APPROVE_TRUST,
    REPLACE_TRUST,
    CLEAR_TRUST,
    RETRY,
    FALLBACK_TO_OPENSSH,
    OPEN_PROFILE_EDITOR
}
