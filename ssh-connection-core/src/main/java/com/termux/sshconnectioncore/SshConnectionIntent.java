package com.termux.sshconnectioncore;

public enum SshConnectionIntent {
    TERMINAL_INTERACTIVE,
    TERMINAL_PERSISTENT,
    FILE_BROWSE,
    FILE_DOWNLOAD,
    FILE_UPLOAD,
    REMOTE_MOUNT
}
