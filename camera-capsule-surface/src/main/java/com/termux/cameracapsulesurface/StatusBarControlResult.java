package com.termux.cameracapsulesurface;

import androidx.annotation.NonNull;

final class StatusBarControlResult {

    final boolean success;
    final boolean permissionRequired;
    @NonNull final String backendName;
    @NonNull final String message;

    private StatusBarControlResult(boolean success, boolean permissionRequired,
                                   @NonNull String backendName, @NonNull String message) {
        this.success = success;
        this.permissionRequired = permissionRequired;
        this.backendName = backendName;
        this.message = message;
    }

    @NonNull
    static StatusBarControlResult success(@NonNull String backendName, @NonNull String message) {
        return new StatusBarControlResult(true, false, backendName, message);
    }

    @NonNull
    static StatusBarControlResult permissionRequired(@NonNull String backendName, @NonNull String message) {
        return new StatusBarControlResult(false, true, backendName, message);
    }

    @NonNull
    static StatusBarControlResult failure(@NonNull String backendName, @NonNull String message) {
        return new StatusBarControlResult(false, false, backendName, message);
    }
}
