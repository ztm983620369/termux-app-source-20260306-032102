package com.termux.sshconnectioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class SshProfileResolutionResult {

    public final boolean success;
    @Nullable public final ResolvedSshEndpoint endpoint;
    @NonNull public final String errorMessage;
    @NonNull public final SshConnectionFailureCategory failureCategory;

    private SshProfileResolutionResult(boolean success,
                                       @Nullable ResolvedSshEndpoint endpoint,
                                       @Nullable String errorMessage,
                                       @Nullable SshConnectionFailureCategory failureCategory) {
        this.success = success;
        this.endpoint = endpoint;
        this.errorMessage = safe(errorMessage);
        this.failureCategory = failureCategory == null ? SshConnectionFailureCategory.NONE : failureCategory;
    }

    @NonNull
    public static SshProfileResolutionResult success(@NonNull ResolvedSshEndpoint endpoint) {
        return new SshProfileResolutionResult(true, endpoint, "", SshConnectionFailureCategory.NONE);
    }

    @NonNull
    public static SshProfileResolutionResult failure(@Nullable String errorMessage,
                                                     @NonNull SshConnectionFailureCategory failureCategory) {
        return new SshProfileResolutionResult(false, null, errorMessage, failureCategory);
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
