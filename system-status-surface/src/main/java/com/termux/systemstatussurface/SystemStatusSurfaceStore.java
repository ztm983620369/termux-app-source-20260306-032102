package com.termux.systemstatussurface;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Map;

final class SystemStatusSurfaceStore {

    private static final String PREFS_NAME = "system_status_surface_store";
    private static final String KEY_PREFIX = "surface.";

    @NonNull
    private final SharedPreferences prefs;

    SystemStatusSurfaceStore(@NonNull Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    void save(@NonNull SystemStatusSurfaceState state) {
        prefs.edit().putString(KEY_PREFIX + state.surfaceId, state.toJson().toString()).apply();
    }

    @Nullable
    SystemStatusSurfaceState load(@NonNull String surfaceId) {
        String raw = prefs.getString(KEY_PREFIX + surfaceId, null);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return SystemStatusSurfaceState.fromJson(new JSONObject(raw));
        } catch (Exception ignored) {
            return null;
        }
    }

    @NonNull
    ArrayList<SystemStatusSurfaceState> list() {
        ArrayList<SystemStatusSurfaceState> states = new ArrayList<>();
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(KEY_PREFIX)) continue;
            Object value = entry.getValue();
            if (!(value instanceof String)) continue;
            try {
                states.add(SystemStatusSurfaceState.fromJson(new JSONObject((String) value)));
            } catch (Exception ignored) {
            }
        }
        return states;
    }

    void remove(@NonNull String surfaceId) {
        prefs.edit().remove(KEY_PREFIX + surfaceId).apply();
    }

    void clear() {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key != null && key.startsWith(KEY_PREFIX)) editor.remove(key);
        }
        editor.apply();
    }
}
