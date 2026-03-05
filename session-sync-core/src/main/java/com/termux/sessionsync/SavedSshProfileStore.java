package com.termux.sessionsync;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Reads saved SSH server profiles and maps them into SessionEntry models.
 * This keeps file-page server switching independent from terminal runtime sessions.
 */
public final class SavedSshProfileStore {

    public static final String PROFILE_ENTRY_ID_PREFIX = "profile-";

    private static final String PREF_NAME = "ssh_persistence_prefs";
    private static final String KEY_SSH_PROFILES_JSON = "ssh_profiles.json";
    private static final Object CACHE_LOCK = new Object();
    @Nullable
    private static String sCachedRawJson;
    @Nullable
    private static ArrayList<SavedSshProfile> sCachedProfiles;

    private SavedSshProfileStore() {
    }

    public static boolean isProfileEntry(@Nullable SessionEntry entry) {
        if (entry == null || TextUtils.isEmpty(entry.id)) return false;
        return entry.id.startsWith(PROFILE_ENTRY_ID_PREFIX);
    }

    @NonNull
    public static List<SessionEntry> loadSessionEntries(@NonNull Context context) {
        List<SavedSshProfile> profiles = loadProfiles(context);
        LinkedHashMap<String, SessionEntry> byId = new LinkedHashMap<>();
        long nowMs = System.currentTimeMillis();
        for (SavedSshProfile profile : profiles) {
            if (profile == null || TextUtils.isEmpty(profile.host)) continue;
            String sshCommand = buildSshCommand(
                profile.host, profile.user, profile.port, profile.password, profile.extraOptions);
            if (sshCommand.isEmpty()) continue;

            String rawId = safe(profile.id);
            if (rawId.isEmpty()) {
                rawId = "legacy-" + shortSha1(
                    profile.host + "\n" + profile.user + "\n" + profile.port + "\n" + profile.extraOptions);
            }
            String entryId = PROFILE_ENTRY_ID_PREFIX + rawId;
            String displayName = safe(profile.displayName);
            if (displayName.isEmpty()) {
                String userPrefix = profile.user.isEmpty() ? "" : (profile.user + "@");
                displayName = userPrefix + profile.host + ":" + profile.port;
            }

            SessionEntry entry = new SessionEntry.Builder(entryId, displayName)
                .setTransport(SessionTransport.SSH)
                .setSshCommand(sshCommand)
                .setActive(false)
                .setRunning(false)
                .setUpdatedAtMs(nowMs)
                .build();
            byId.put(entryId, entry);
        }

        return new ArrayList<>(byId.values());
    }

    @NonNull
    public static List<SavedSshProfile> loadProfiles(@NonNull Context context) {
        SharedPreferences prefs = context.getApplicationContext()
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_SSH_PROFILES_JSON, "[]");
        if (TextUtils.isEmpty(raw)) raw = "[]";

        synchronized (CACHE_LOCK) {
            if (raw.equals(sCachedRawJson) && sCachedProfiles != null) {
                return new ArrayList<>(sCachedProfiles);
            }
        }

        ArrayList<SavedSshProfile> profiles = new ArrayList<>();

        try {
            JSONArray json = new JSONArray(raw);
            for (int i = 0; i < json.length(); i++) {
                JSONObject item = json.optJSONObject(i);
                if (item == null) continue;
                SavedSshProfile profile = SavedSshProfile.fromJson(item);
                if (profile == null) continue;
                profiles.add(normalizeProfile(profile));
            }
        } catch (Exception ignored) {
        }

        synchronized (CACHE_LOCK) {
            sCachedRawJson = raw;
            sCachedProfiles = new ArrayList<>(profiles);
        }
        return profiles;
    }

    @NonNull
    public static SavedSshProfile upsertProfile(@NonNull Context context, @NonNull SavedSshProfile profile) {
        SavedSshProfile normalized = normalizeProfile(profile);
        ArrayList<SavedSshProfile> profiles = new ArrayList<>(loadProfiles(context));
        boolean replaced = false;
        for (int i = 0; i < profiles.size(); i++) {
            if (normalized.id.equals(profiles.get(i).id)) {
                profiles.set(i, normalized);
                replaced = true;
                break;
            }
        }
        if (!replaced) profiles.add(normalized);
        saveProfiles(context, profiles);
        return normalized;
    }

    public static void saveProfiles(@NonNull Context context, @NonNull List<SavedSshProfile> profiles) {
        JSONArray json = new JSONArray();
        if (profiles != null) {
            for (SavedSshProfile profile : profiles) {
                if (profile == null) continue;
                json.put(normalizeProfile(profile).toJson());
            }
        }
        String raw = json.toString();
        SharedPreferences prefs = context.getApplicationContext()
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_SSH_PROFILES_JSON, raw).apply();
        synchronized (CACHE_LOCK) {
            sCachedRawJson = raw;
            ArrayList<SavedSshProfile> cache = new ArrayList<>();
            if (profiles != null) {
                for (SavedSshProfile profile : profiles) {
                    if (profile == null) continue;
                    cache.add(normalizeProfile(profile));
                }
            }
            sCachedProfiles = cache;
        }
    }

    @NonNull
    private static SavedSshProfile normalizeProfile(@NonNull SavedSshProfile profile) {
        String host = safe(profile.host);
        String user = safe(profile.user);
        String extraOptions = safe(profile.extraOptions);
        String password = profile.password == null ? "" : profile.password;

        int port = profile.port;
        if (port <= 0 || port > 65535) port = 22;

        String id = safe(profile.id);
        if (id.isEmpty()) id = UUID.randomUUID().toString();

        String displayName = safe(profile.displayName);
        if (displayName.isEmpty()) {
            String userPrefix = user.isEmpty() ? "" : (user + "@");
            displayName = userPrefix + host + ":" + port;
        }

        return new SavedSshProfile(id, displayName, host, port, user, password, extraOptions);
    }

    @NonNull
    private static String buildSshCommand(@NonNull String hostInputRaw, @NonNull String userRaw, int port,
                                          @Nullable String passwordRaw, @NonNull String extraOptionsRaw) {
        String hostInput = safe(hostInputRaw);
        String user = safe(userRaw);
        String password = passwordRaw == null ? "" : passwordRaw;
        String extraOptions = safe(extraOptionsRaw);

        if (hostInput.isEmpty()) return "";

        if (hostInput.startsWith("ssh ")) {
            if (password.isEmpty()) return hostInput;
            if (hostInput.startsWith("sshpass ")) return hostInput;
            return "sshpass -p " + quoteArg(password) + " " + hostInput;
        }

        if (hostInput.contains(" ")) return "";

        String host = hostInput;
        String targetArg;
        int at = host.lastIndexOf('@');
        if (at > 0 && at < host.length() - 1) {
            user = host.substring(0, at).trim();
            host = host.substring(at + 1).trim();
            targetArg = hostInput;
        } else {
            targetArg = TextUtils.isEmpty(user) ? host : (user + "@" + host);
        }

        if (host.isEmpty()) return "";
        if (!password.isEmpty() && TextUtils.isEmpty(user)) return "";

        StringBuilder base = new StringBuilder("ssh");
        if (port > 0 && port != 22) {
            base.append(" -p ").append(port);
        }
        if (!extraOptions.isEmpty()) {
            base.append(" ").append(extraOptions);
        }
        base.append(" ").append(quoteArg(targetArg));

        String baseCommand = base.toString();
        if (password.isEmpty()) return baseCommand;
        return "sshpass -p " + quoteArg(password) + " " + baseCommand;
    }

    @NonNull
    private static String quoteArg(@NonNull String value) {
        if (value.isEmpty()) return "''";
        if (value.matches("[A-Za-z0-9_./:@%+=,-]+")) return value;
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @NonNull
    private static String safe(@Nullable String value) {
        if (value == null) return "";
        return value.trim();
    }

    @NonNull
    private static String shortSha1(@NonNull String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] data = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(data.length * 2);
            for (byte b : data) {
                hex.append(String.format(Locale.US, "%02x", b));
            }
            if (hex.length() > 12) return hex.substring(0, 12);
            return hex.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    public static final class SavedSshProfile {
        @NonNull
        public final String id;
        @NonNull
        public final String displayName;
        @NonNull
        public final String host;
        public final int port;
        @NonNull
        public final String user;
        @NonNull
        public final String password;
        @NonNull
        public final String extraOptions;

        public SavedSshProfile(@NonNull String id, @NonNull String displayName, @NonNull String host, int port,
                               @NonNull String user, @NonNull String password, @NonNull String extraOptions) {
            this.id = id;
            this.displayName = displayName;
            this.host = host;
            this.port = port;
            this.user = user;
            this.password = password;
            this.extraOptions = extraOptions;
        }

        @Nullable
        static SavedSshProfile fromJson(@NonNull JSONObject json) {
            String host = safe(json.optString("host", ""));
            if (host.isEmpty()) return null;
            String id = safe(json.optString("id", ""));
            if (id.isEmpty()) id = UUID.randomUUID().toString();
            String displayName = safe(json.optString("displayName", ""));
            String user = safe(json.optString("user", ""));
            int port = json.optInt("port", 22);
            if (port <= 0 || port > 65535) port = 22;
            String password = json.optString("password", "");
            String extraOptions = safe(json.optString("extraOptions", ""));
            return new SavedSshProfile(id, displayName, host, port, user, password, extraOptions);
        }

        @NonNull
        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id);
                json.put("displayName", displayName);
                json.put("host", host);
                json.put("port", port);
                json.put("user", user);
                json.put("password", password);
                json.put("extraOptions", extraOptions);
            } catch (Exception ignored) {
            }
            return json;
        }
    }
}
