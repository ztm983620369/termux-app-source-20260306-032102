package com.termux.sessionsync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class SessionSnapshot {

    @NonNull
    private final List<SessionEntry> entries;
    @Nullable
    private final String activeSessionId;
    private final long updatedAtMs;

    public SessionSnapshot(@NonNull List<SessionEntry> entries, @Nullable String activeSessionId, long updatedAtMs) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.activeSessionId = trimToNull(activeSessionId);
        this.updatedAtMs = updatedAtMs;
    }

    @NonNull
    public List<SessionEntry> getEntries() {
        return entries;
    }

    @Nullable
    public String getActiveSessionId() {
        return activeSessionId;
    }

    public long getUpdatedAtMs() {
        return updatedAtMs;
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        JSONArray items = new JSONArray();
        for (SessionEntry entry : entries) {
            items.put(entry.toJson());
        }
        try {
            json.put("entries", items);
            json.put("activeSessionId", activeSessionId == null ? JSONObject.NULL : activeSessionId);
            json.put("updatedAtMs", updatedAtMs);
        } catch (Exception ignored) {
        }
        return json;
    }

    @NonNull
    public static SessionSnapshot empty() {
        return new SessionSnapshot(Collections.emptyList(), null, 0L);
    }

    @NonNull
    public static SessionSnapshot fromJson(@Nullable JSONObject json) {
        if (json == null) return empty();

        ArrayList<SessionEntry> entries = new ArrayList<>();
        JSONArray array = json.optJSONArray("entries");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                SessionEntry entry = SessionEntry.fromJson(array.optJSONObject(i));
                if (entry != null) entries.add(entry);
            }
        }

        String activeId = json.optString("activeSessionId", "").trim();
        long updatedAtMs = json.optLong("updatedAtMs", 0L);
        return new SessionSnapshot(entries, activeId.isEmpty() ? null : activeId, updatedAtMs);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SessionSnapshot)) return false;
        SessionSnapshot that = (SessionSnapshot) o;
        return entries.equals(that.entries)
            && Objects.equals(activeSessionId, that.activeSessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entries, activeSessionId);
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) return null;
        String out = value.trim();
        return out.isEmpty() ? null : out;
    }
}
