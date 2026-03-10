package com.termux.cameracapsulesurface;

import androidx.annotation.NonNull;

final class SystemCommandResult {

    final int exitCode;
    @NonNull final String output;
    final boolean timedOut;

    SystemCommandResult(int exitCode, @NonNull String output, boolean timedOut) {
        this.exitCode = exitCode;
        this.output = output;
        this.timedOut = timedOut;
    }

    boolean isSuccess() {
        return !timedOut && exitCode == 0;
    }
}
