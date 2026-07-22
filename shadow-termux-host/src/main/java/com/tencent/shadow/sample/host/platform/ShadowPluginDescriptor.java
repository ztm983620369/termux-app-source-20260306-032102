package com.tencent.shadow.sample.host.platform;

import java.io.File;

public final class ShadowPluginDescriptor {

    public final String pluginId;
    public final String generation;
    public final long versionCode;
    public final String versionName;
    public final String displayName;
    public final String description;
    public final String partKey;
    public final String activityClassName;
    public final ShadowLifecycleState state;
    public final ShadowTrustLevel trustLevel;
    public final boolean enabled;
    public final boolean candidate;
    public final boolean rollbackAvailable;
    public final File packageFile;
    public final long totalLaunchAttempts;
    public final long totalLaunchFailures;
    public final int consecutiveLaunchFailures;
    public final String lastError;

    ShadowPluginDescriptor(
            ShadowRegistry.PluginRecord plugin,
            ShadowRegistry.VersionRecord version,
            boolean candidate
    ) {
        pluginId = plugin.pluginId;
        generation = version.generation;
        versionCode = version.manifest.versionCode;
        versionName = version.manifest.versionName;
        displayName = version.manifest.displayName;
        description = version.manifest.description;
        partKey = version.manifest.partKey;
        activityClassName = version.manifest.activityClassName;
        state = version.state;
        trustLevel = version.trustLevel;
        enabled = plugin.enabled;
        this.candidate = candidate;
        rollbackAvailable = plugin.previousHealthyVersion() != null;
        packageFile = version.runtimePath == null ? null : new File(version.runtimePath);
        totalLaunchAttempts = version.totalLaunchAttempts;
        totalLaunchFailures = version.totalLaunchFailures;
        consecutiveLaunchFailures = version.consecutiveLaunchFailures;
        lastError = version.lastError;
    }
}
