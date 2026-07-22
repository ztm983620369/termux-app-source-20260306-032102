package com.tencent.shadow.sample.host.platform;

import android.content.Context;

import java.io.File;
import java.io.IOException;

public final class ShadowPaths {

    private final File homeRoot;
    private final File runtimeRoot;

    public ShadowPaths(Context context) throws IOException {
        File filesDir = context.getFilesDir();
        homeRoot = new File(filesDir, "home/.termux-shadow");
        runtimeRoot = new File(homeRoot, "runtime");
        ensureLayout();
    }

    public File homeRoot() {
        return homeRoot;
    }

    public File inboxDir() {
        return new File(homeRoot, "inbox");
    }

    public File inboxArchiveDir() {
        return new File(inboxDir(), "archive");
    }

    public File repositoryDir() {
        return new File(homeRoot, "repository");
    }

    public File repositoryPluginsDir() {
        return new File(repositoryDir(), "plugins");
    }

    public File quarantineDir() {
        return new File(homeRoot, "quarantine");
    }

    public File logsDir() {
        return new File(homeRoot, "logs");
    }

    public File hostLogsDir() {
        return new File(logsDir(), "host");
    }

    public File pluginLogsDir() {
        return new File(logsDir(), "plugins");
    }

    public File auditLogsDir() {
        return new File(logsDir(), "audit");
    }

    public File crashDir() {
        return new File(homeRoot, "crash");
    }

    public File reportsDir() {
        return new File(homeRoot, "reports");
    }

    public File launchReportsDir() {
        return new File(reportsDir(), "launch");
    }

    public File runtimeCrashReportsDir() {
        return new File(reportsDir(), "runtime-crash");
    }

    public File launchReportFile(String pluginId) {
        if (pluginId == null || !pluginId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Invalid pluginId for launch report");
        }
        return new File(launchReportsDir(), pluginId + ".json");
    }

    public File runtimeCrashReportFile(String operationId) {
        if (operationId == null || !operationId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Invalid operationId for runtime crash report");
        }
        return new File(runtimeCrashReportsDir(), operationId + ".json");
    }

    public File exportsDir() {
        return new File(homeRoot, "exports");
    }

    public File configDir() {
        return new File(homeRoot, "config");
    }

    public File engineDir() {
        return new File(homeRoot, "engine");
    }

    public File engineCacheDir() {
        return new File(engineDir(), "cache");
    }

    public File engineStateDir() {
        return new File(engineDir(), "state");
    }

    public File engineManagerOdexDir() {
        return new File(engineDir(), "manager-odex");
    }

    public File runtimeRoot() {
        return runtimeRoot;
    }

    public File runtimeStateDir() {
        return new File(runtimeRoot, "state");
    }

    public File registryFile() {
        return new File(runtimeStateDir(), "registry.json");
    }

    public File launchContextFile() {
        return new File(runtimeStateDir(), "launch-context.json");
    }

    public File engineMigrationMarkerFile() {
        return new File(runtimeStateDir(), "engine-v2-migrated");
    }

    public File runtimeJournalDir() {
        return new File(runtimeRoot, "journal");
    }

    public File operationJournalFile() {
        return new File(runtimeJournalDir(), "operations.jsonl");
    }

    public File processExitReportsDir() {
        return new File(reportsDir(), "process-exits");
    }

    public File runtimePackagesDir() {
        return new File(runtimeRoot, "packages");
    }

    public File runtimeManagersDir() {
        return new File(runtimeRoot, "managers");
    }

    public File runtimeStagingDir() {
        return new File(runtimeRoot, "staging");
    }

    public File runtimeLocksDir() {
        return new File(runtimeRoot, "locks");
    }

    private void ensureLayout() throws IOException {
        ShadowFileOps.ensurePrivateDirectory(homeRoot);
        ShadowFileOps.ensurePrivateDirectory(inboxDir());
        ShadowFileOps.ensurePrivateDirectory(inboxArchiveDir());
        ShadowFileOps.ensurePrivateDirectory(repositoryDir());
        ShadowFileOps.ensurePrivateDirectory(repositoryPluginsDir());
        ShadowFileOps.ensurePrivateDirectory(quarantineDir());
        ShadowFileOps.ensurePrivateDirectory(logsDir());
        ShadowFileOps.ensurePrivateDirectory(hostLogsDir());
        ShadowFileOps.ensurePrivateDirectory(pluginLogsDir());
        ShadowFileOps.ensurePrivateDirectory(auditLogsDir());
        ShadowFileOps.ensurePrivateDirectory(crashDir());
        ShadowFileOps.ensurePrivateDirectory(reportsDir());
        ShadowFileOps.ensurePrivateDirectory(launchReportsDir());
        ShadowFileOps.ensurePrivateDirectory(runtimeCrashReportsDir());
        ShadowFileOps.ensurePrivateDirectory(processExitReportsDir());
        ShadowFileOps.ensurePrivateDirectory(exportsDir());
        ShadowFileOps.ensurePrivateDirectory(configDir());
        ShadowFileOps.ensurePrivateDirectory(engineDir());
        ShadowFileOps.ensurePrivateDirectory(engineCacheDir());
        ShadowFileOps.ensurePrivateDirectory(engineStateDir());
        ShadowFileOps.ensurePrivateDirectory(engineManagerOdexDir());
        ShadowFileOps.ensurePrivateDirectory(runtimeRoot());
        ShadowFileOps.ensurePrivateDirectory(runtimeStateDir());
        ShadowFileOps.ensurePrivateDirectory(runtimeJournalDir());
        ShadowFileOps.ensurePrivateDirectory(runtimePackagesDir());
        ShadowFileOps.ensurePrivateDirectory(runtimeManagersDir());
        ShadowFileOps.ensurePrivateDirectory(runtimeStagingDir());
        ShadowFileOps.ensurePrivateDirectory(runtimeLocksDir());
    }
}
