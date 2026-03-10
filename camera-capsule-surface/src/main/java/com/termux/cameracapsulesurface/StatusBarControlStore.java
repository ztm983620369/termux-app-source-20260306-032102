package com.termux.cameracapsulesurface;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

final class StatusBarControlStore {

    private static final String PREFS_NAME = "camera_capsule_status_bar_store";
    private static final String KEY_APPLIED = "applied";
    private static final String KEY_BACKEND = "backend";
    private static final String KEY_SPEC_SIGNATURE = "spec_signature";

    @NonNull
    private final SharedPreferences prefs;

    StatusBarControlStore(@NonNull Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    void saveApplied(@NonNull String backend, @NonNull String signature) {
        prefs.edit()
            .putBoolean(KEY_APPLIED, true)
            .putString(KEY_BACKEND, backend)
            .putString(KEY_SPEC_SIGNATURE, signature)
            .apply();
    }

    void clear() {
        prefs.edit().clear().apply();
    }

    boolean isApplied() {
        return prefs.getBoolean(KEY_APPLIED, false);
    }

    @NonNull
    String getBackend() {
        return prefs.getString(KEY_BACKEND, StatusBarPrivilegeMode.NONE);
    }

    @NonNull
    String getSpecSignature() {
        return prefs.getString(KEY_SPEC_SIGNATURE, "");
    }
}
