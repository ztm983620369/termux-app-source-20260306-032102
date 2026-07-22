package com.tencent.shadow.sample.host.platform;

import java.io.File;

public final class ShadowLaunchPlan {

    public final String operationId;
    public final String pluginId;
    public final String generation;
    public final String activeBeforeLaunch;
    public final String partKey;
    public final String activityClassName;
    public final File pluginPackage;
    public final File managerApk;
    public final String runtimeFingerprint;
    public final boolean activationRequired;
    public final long healthTimeoutMs;
    public final long stabilityWindowMs;

    ShadowLaunchPlan(
            String operationId,
            ShadowRegistry.PluginRecord plugin,
            ShadowRegistry.VersionRecord version,
            File managerApk,
            String managerSha256,
            boolean activationRequired,
            long healthTimeoutMs,
            long stabilityWindowMs
    ) {
        this.operationId = operationId;
        pluginId = plugin.pluginId;
        generation = version.generation;
        activeBeforeLaunch = plugin.activeGeneration;
        partKey = version.manifest.partKey;
        activityClassName = version.manifest.activityClassName;
        pluginPackage = new File(version.runtimePath);
        this.managerApk = managerApk;
        runtimeFingerprint = managerSha256 + ":" + version.bundleSha256;
        this.activationRequired = activationRequired;
        this.healthTimeoutMs = healthTimeoutMs;
        this.stabilityWindowMs = stabilityWindowMs;
    }
}
