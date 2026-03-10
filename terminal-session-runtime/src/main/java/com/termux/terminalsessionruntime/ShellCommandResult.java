package com.termux.terminalsessionruntime;

import androidx.annotation.NonNull;

public final class ShellCommandResult {

    public final int exitCode;
    @NonNull public final String stdout;
    @NonNull public final String stderr;

    public ShellCommandResult(int exitCode, @NonNull String stdout, @NonNull String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }
}
