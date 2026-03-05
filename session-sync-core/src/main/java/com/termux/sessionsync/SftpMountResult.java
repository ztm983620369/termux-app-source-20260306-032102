package com.termux.sessionsync;

import androidx.annotation.NonNull;

public final class SftpMountResult {

    public final boolean success;
    public final boolean mounted;
    @NonNull
    public final String mountPath;
    public final int exitCode;
    public final boolean timedOut;
    @NonNull
    public final String stdout;
    @NonNull
    public final String stderr;
    @NonNull
    public final String command;
    @NonNull
    public final String messageCn;

    private SftpMountResult(boolean success, boolean mounted, @NonNull String mountPath, int exitCode,
                            boolean timedOut, @NonNull String stdout, @NonNull String stderr,
                            @NonNull String command, @NonNull String messageCn) {
        this.success = success;
        this.mounted = mounted;
        this.mountPath = mountPath;
        this.exitCode = exitCode;
        this.timedOut = timedOut;
        this.stdout = stdout;
        this.stderr = stderr;
        this.command = command;
        this.messageCn = messageCn;
    }

    @NonNull
    public static SftpMountResult ok(@NonNull String mountPath, @NonNull String messageCn) {
        return new SftpMountResult(true, true, mountPath, 0, false, "", "", "", messageCn);
    }

    @NonNull
    public static SftpMountResult fail(@NonNull String mountPath, int exitCode, boolean timedOut,
                                       @NonNull String stdout, @NonNull String stderr,
                                       @NonNull String command, @NonNull String messageCn) {
        return new SftpMountResult(false, false, mountPath, exitCode, timedOut, stdout, stderr, command, messageCn);
    }

    @NonNull
    public String toUserMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append(messageCn);
        if (!stdout.trim().isEmpty()) {
            sb.append("\n[stdout]\n").append(stdout.trim());
        }
        if (!stderr.trim().isEmpty()) {
            sb.append("\n[stderr]\n").append(stderr.trim());
        }
        if (!command.trim().isEmpty()) {
            sb.append("\n[cmd]\n").append(command.trim());
        }
        if (!success) {
            sb.append("\n[code] ").append(exitCode);
            if (timedOut) sb.append(" (timeout)");
        }
        return sb.toString();
    }
}

