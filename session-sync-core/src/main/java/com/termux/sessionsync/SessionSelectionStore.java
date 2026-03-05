package com.termux.sessionsync;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class SessionSelectionStore {

    private static final String PREF_NAME = "termux.session_sync.selection";
    private static final String KEY_SELECTED_SESSION_KEY = "selected_session_key";

    @NonNull
    private final SharedPreferences sharedPreferences;

    SessionSelectionStore(@NonNull Context context) {
        sharedPreferences = context.getApplicationContext()
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    @Nullable
    String getSelectedSessionKey() {
        String raw = sharedPreferences.getString(KEY_SELECTED_SESSION_KEY, null);
        if (raw == null) return null;
        String out = raw.trim();
        return out.isEmpty() ? null : out;
    }

    void setSelectedSessionKey(@Nullable String sessionKey) {
        if (sessionKey == null || sessionKey.trim().isEmpty()) {
            sharedPreferences.edit().remove(KEY_SELECTED_SESSION_KEY).apply();
        } else {
            sharedPreferences.edit().putString(KEY_SELECTED_SESSION_KEY, sessionKey.trim()).apply();
        }
    }
}

