package com.termux.cameracapsulesurface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class StatusBarPrivilegeMode {

    static final String AUTO = "auto";
    static final String NONE = "none";
    static final String DIRECT = "direct";
    static final String ROOT = "root";
    static final String SHIZUKU = "shizuku";

    private StatusBarPrivilegeMode() {
    }

    @NonNull
    static String normalize(@Nullable String raw) {
        String normalized = raw == null ? AUTO : raw.trim().toLowerCase();
        if (normalized.isEmpty()) normalized = AUTO;
        switch (normalized) {
            case NONE:
                return NONE;
            case DIRECT:
                return DIRECT;
            case ROOT:
                return ROOT;
            case SHIZUKU:
                return SHIZUKU;
            default:
                return AUTO;
        }
    }
}
