package com.termux.sessionsync;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONObject;

final class SharedPrefsSessionStore implements SessionStore {

    private static final String PREF_NAME = "termux.session_sync.core";
    private static final String KEY_SNAPSHOT = "snapshot";

    @NonNull
    private final SharedPreferences sharedPreferences;

    SharedPrefsSessionStore(@NonNull Context context) {
        this.sharedPreferences = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public void save(@NonNull SessionSnapshot snapshot) {
        sharedPreferences.edit().putString(KEY_SNAPSHOT, snapshot.toJson().toString()).apply();
    }

    @NonNull
    @Override
    public SessionSnapshot load() {
        String raw = sharedPreferences.getString(KEY_SNAPSHOT, null);
        if (raw == null || raw.trim().isEmpty()) return SessionSnapshot.empty();
        try {
            return SessionSnapshot.fromJson(new JSONObject(raw));
        } catch (Exception ignored) {
            return SessionSnapshot.empty();
        }
    }
}

