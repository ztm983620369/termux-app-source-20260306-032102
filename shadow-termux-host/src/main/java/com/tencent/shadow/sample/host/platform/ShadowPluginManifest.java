package com.tencent.shadow.sample.host.platform;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Pattern;

public final class ShadowPluginManifest {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern CLASS_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"
    );

    public final int schemaVersion;
    public final String pluginId;
    public final long versionCode;
    public final String versionName;
    public final String displayName;
    public final String description;
    public final String partKey;
    public final String activityClassName;
    public final int resourcePackageId;
    public final long minHostVersionCode;
    public final long maxHostVersionCode;
    public final String shadowUuid;
    public final String loaderApkName;
    public final String runtimeApkName;
    public final String pluginApkName;

    private ShadowPluginManifest(
            int schemaVersion,
            String pluginId,
            long versionCode,
            String versionName,
            String displayName,
            String description,
            String partKey,
            String activityClassName,
            int resourcePackageId,
            long minHostVersionCode,
            long maxHostVersionCode,
            String shadowUuid,
            String loaderApkName,
            String runtimeApkName,
            String pluginApkName
    ) {
        this.schemaVersion = schemaVersion;
        this.pluginId = pluginId;
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.displayName = displayName;
        this.description = description;
        this.partKey = partKey;
        this.activityClassName = activityClassName;
        this.resourcePackageId = resourcePackageId;
        this.minHostVersionCode = minHostVersionCode;
        this.maxHostVersionCode = maxHostVersionCode;
        this.shadowUuid = shadowUuid;
        this.loaderApkName = loaderApkName;
        this.runtimeApkName = runtimeApkName;
        this.pluginApkName = pluginApkName;
    }

    public static ShadowPluginManifest parse(JSONObject metadata, JSONObject config) throws JSONException {
        int schemaVersion = metadata.optInt("schemaVersion", -1);
        if (schemaVersion != ShadowPackageContract.SCHEMA_VERSION) {
            throw new JSONException("Unsupported termux-shadow.json schemaVersion: " + schemaVersion);
        }

        String partKey = required(metadata, "partKey");
        JSONObject pluginConfig = findPluginConfig(config, partKey);
        String pluginId = required(metadata, "pluginId");
        long versionCode = metadata.optLong("versionCode", -1L);
        String versionName = required(metadata, "versionName");
        String displayName = required(metadata, "displayName");
        String description = metadata.optString("description", "").trim();
        String activityClassName = required(metadata, "activityClassName");
        int packageId = parsePackageId(metadata.opt("resourcePackageId"));
        long minHost = metadata.optLong("minHostVersionCode", 0L);
        long maxHost = metadata.optLong("maxHostVersionCode", Long.MAX_VALUE);
        String uuid = required(config, "UUID");

        JSONObject loader = config.optJSONObject("pluginLoader");
        JSONObject runtime = config.optJSONObject("runtime");
        String loaderName = loader == null ? null : required(loader, "apkName");
        String runtimeName = runtime == null ? null : required(runtime, "apkName");
        String pluginName = required(pluginConfig, "apkName");

        validateIdentifier("pluginId", pluginId);
        validateIdentifier("partKey", partKey);
        if (!CLASS_NAME.matcher(activityClassName).matches()) {
            throw new JSONException("Invalid activityClassName: " + activityClassName);
        }
        if (versionCode < 1) {
            throw new JSONException("versionCode must be positive for schemaVersion "
                    + ShadowPackageContract.SCHEMA_VERSION);
        }
        if (versionName.length() == 0 || versionName.length() > 128) {
            throw new JSONException("Invalid versionName");
        }
        if (displayName.length() > 160 || description.length() > 2048) {
            throw new JSONException("Shadow display metadata is too long");
        }
        if (minHost < 0 || maxHost < minHost) {
            throw new JSONException("Invalid host compatibility range");
        }
        if (packageId < 2 || packageId > 0x7e) {
            throw new JSONException("resourcePackageId must be between 0x02 and 0x7e");
        }

        return new ShadowPluginManifest(
                schemaVersion,
                pluginId,
                versionCode,
                versionName,
                displayName,
                description,
                partKey,
                activityClassName,
                packageId,
                minHost,
                maxHost,
                uuid,
                loaderName,
                runtimeName,
                pluginName
        );
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("schemaVersion", schemaVersion);
        object.put("pluginId", pluginId);
        object.put("versionCode", versionCode);
        object.put("versionName", versionName);
        object.put("displayName", displayName);
        object.put("description", description);
        object.put("partKey", partKey);
        object.put("activityClassName", activityClassName);
        if (resourcePackageId >= 0) {
            object.put("resourcePackageId", String.format("0x%02X", resourcePackageId));
        }
        object.put("minHostVersionCode", minHostVersionCode);
        object.put("maxHostVersionCode", maxHostVersionCode);
        object.put("shadowUuid", shadowUuid);
        object.put("loaderApkName", loaderApkName);
        object.put("runtimeApkName", runtimeApkName);
        object.put("pluginApkName", pluginApkName);
        return object;
    }

    private static JSONObject findPluginConfig(JSONObject config, String partKey) throws JSONException {
        JSONArray plugins = config.optJSONArray("plugins");
        if (plugins == null || plugins.length() == 0) {
            throw new JSONException("config.json does not contain plugins");
        }
        for (int index = 0; index < plugins.length(); index++) {
            JSONObject plugin = plugins.getJSONObject(index);
            if (partKey.equals(plugin.optString("partKey", ""))) {
                return plugin;
            }
        }
        throw new JSONException("partKey is not mapped by config.json: " + partKey);
    }

    static JSONObject pluginConfig(JSONObject config, String partKey) throws JSONException {
        return findPluginConfig(config, partKey);
    }

    private static int parsePackageId(Object value) throws JSONException {
        if (value == null || value == JSONObject.NULL) {
            return -1;
        }
        try {
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            String text = String.valueOf(value).trim();
            return text.startsWith("0x") || text.startsWith("0X")
                    ? Integer.parseInt(text.substring(2), 16)
                    : Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new JSONException("Invalid resourcePackageId: " + value);
        }
    }

    private static void validateIdentifier(String name, String value) throws JSONException {
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new JSONException("Invalid " + name + ": " + value);
        }
    }

    private static String required(JSONObject object, String key) throws JSONException {
        String value = object.optString(key, "").trim();
        if (value.length() == 0) {
            throw new JSONException("Missing " + key);
        }
        return value;
    }
}
