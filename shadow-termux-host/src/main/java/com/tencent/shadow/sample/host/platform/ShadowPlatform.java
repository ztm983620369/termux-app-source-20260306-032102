package com.tencent.shadow.sample.host.platform;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ShadowPlatform {

    private static final int MAX_MANAGER_BYTES = 64 * 1024 * 1024;

    private static ShadowPlatform instance;

    private final Context context;
    private final ShadowPaths paths;
    private final ShadowEventLogger logger;
    private final ShadowTrustPolicy policy;
    private final ShadowPackageVerifier verifier;
    private final ShadowOperationJournal journal;
    private final ShadowRegistry registry;
    private final File managerApk;
    private final String managerSha256;

    private ShadowPlatform(Context context) throws Exception {
        this.context = context.getApplicationContext();
        paths = new ShadowPaths(this.context);
        logger = ShadowEventLogger.initialize(this.context, paths);
        policy = ShadowTrustPolicy.load(paths);
        verifier = new ShadowPackageVerifier(policy, hostVersionCode(this.context));
        journal = new ShadowOperationJournal(paths.operationJournalFile());
        retireObsoleteManagedIngress();
        migrateLegacyEngineStorage();
        registry = ShadowRegistry.load(paths.registryFile());
        FileAndHash manager = prepareManagerAsset();
        managerApk = manager.file;
        managerSha256 = manager.sha256;
        recoverInterruptedOperations();
        if (paths.registryFile().isFile()) {
            publishRegistrySnapshot();
        } else {
            persistRegistry();
        }
        logger.info(
                "PLATFORM_READY",
                null,
                null,
                null,
                "revision=" + registry.revision + " plugins=" + registry.plugins.size()
        );
    }

    public static synchronized ShadowPlatform initialize(Context context) throws Exception {
        if (instance == null) {
            instance = new ShadowPlatform(context);
        }
        return instance;
    }

    public static synchronized ShadowPlatform get() {
        if (instance == null) {
            throw new IllegalStateException("ShadowPlatform has not been initialized");
        }
        return instance;
    }

    public ShadowPaths paths() {
        return paths;
    }

    public synchronized List<ShadowPluginDescriptor> refresh() throws Exception {
        reconcileInbox();
        validateRegisteredFiles();
        return listPluginsLocked();
    }

    public synchronized List<ShadowPluginDescriptor> listPlugins() {
        return listPluginsLocked();
    }

    public synchronized ShadowLaunchPlan prepareLaunch(String pluginId) throws Exception {
        validateRegisteredFiles();
        ShadowRegistry.PluginRecord plugin = requirePlugin(pluginId);
        if (!plugin.enabled) {
            throw new IllegalStateException("Plugin is disabled: " + pluginId);
        }
        if (plugin.activatingGeneration != null) {
            throw new IllegalStateException(
                    "LAUNCH_BUSY: activation already in progress for " + plugin.activatingGeneration
            );
        }

        ShadowRegistry.VersionRecord version = plugin.candidateVersion();
        boolean activationRequired = version != null;
        if (version == null) {
            version = plugin.activeVersion();
        }
        if (version == null) {
            version = newestLaunchableVersion(plugin);
            activationRequired = true;
        }
        if (version == null) {
            throw new IllegalStateException("Plugin has no launchable generation: " + pluginId);
        }
        if (version.runtimePath == null || !new File(version.runtimePath).isFile()) {
            throw new IOException("Plugin runtime package is missing: " + version.generation);
        }
        String runtimeDigest = ShadowFileOps.sha256(new File(version.runtimePath));
        if (!version.bundleSha256.equals(runtimeDigest)) {
            throw new SecurityException("Plugin runtime package integrity changed: " + version.generation);
        }
        if (!managerSha256.equals(ShadowFileOps.sha256(managerApk))) {
            throw new SecurityException("Shadow manager integrity changed");
        }

        String operationId = UUID.randomUUID().toString();
        version.totalLaunchAttempts++;
        version.lastLaunchAttemptAt = System.currentTimeMillis();
        version.lastLaunchOperationId = operationId;
        boolean runtimeRevalidationRequired = !version.hasRuntimeHealthProof();
        if (activationRequired
                || version.state != ShadowLifecycleState.HEALTHY
                || runtimeRevalidationRequired) {
            if (!isActivatable(version.state)
                    && !(runtimeRevalidationRequired
                    && version.state == ShadowLifecycleState.HEALTHY)) {
                throw new IllegalStateException(
                        "Plugin generation is not activatable: " + version.generation + " state=" + version.state
                );
            }
            plugin.activatingGeneration = version.generation;
            if (version.state != ShadowLifecycleState.HEALTHY) {
                transition(plugin, version, ShadowLifecycleState.ACTIVATING, operationId, null);
            }
            activationRequired = true;
        }
        persistRegistry();
        journal.append(operationId, "LAUNCH", "PREPARED", pluginId, version.generation, null);
        logger.audit("PLUGIN_LAUNCH_PREPARED", operationId, pluginId, version.generation,
                "activationRequired=" + activationRequired);
        ShadowLaunchPlan plan = new ShadowLaunchPlan(
                operationId,
                plugin,
                version,
                managerApk,
                managerSha256,
                activationRequired,
                policy.launchHealthTimeoutMs(),
                policy.launchStabilityWindowMs()
        );
        writeLaunchContext(plan, "PREPARED", null);
        return plan;
    }

    public synchronized void markLaunchReady(
            ShadowLaunchPlan plan,
            ShadowRuntimeHealth health
    ) throws Exception {
        if (health.isStable()) {
            throw new IllegalArgumentException("FIRST_FRAME_READY proof must not be stable");
        }
        ShadowRegistry.PluginRecord plugin = registry.plugins.get(plan.pluginId);
        ShadowRegistry.VersionRecord version = plugin == null
                ? null
                : plugin.versions.get(plan.generation);
        if (plugin == null || version == null || !isCurrentLaunch(plugin, version, plan)) {
            recordStaleLaunchResult(plan, "READY", "launch operation was superseded");
            return;
        }
        journal.append(plan.operationId, "LAUNCH", "FIRST_FRAME_READY",
                plan.pluginId, plan.generation,
                "protocol=" + health.protocolVersion + " pid=" + health.pluginProcessPid);
        logger.audit("PLUGIN_FIRST_FRAME_READY", plan.operationId, plan.pluginId,
                plan.generation, "pid=" + health.pluginProcessPid);
        writeLaunchContext(plan, "FIRST_FRAME_READY", null, health);
    }

    public synchronized void markLaunchHealthy(
            ShadowLaunchPlan plan,
            ShadowRuntimeHealth health
    ) throws Exception {
        if (!health.isStable()) {
            throw new IllegalArgumentException("runtime stability proof is missing");
        }
        ShadowRegistry.PluginRecord plugin = registry.plugins.get(plan.pluginId);
        if (plugin == null) {
            recordStaleLaunchResult(plan, "HEALTHY", "plugin was removed");
            return;
        }
        ShadowRegistry.VersionRecord version = plugin.versions.get(plan.generation);
        if (version == null || !isCurrentLaunch(plugin, version, plan)) {
            recordStaleLaunchResult(plan, "HEALTHY", "launch operation was superseded");
            return;
        }
        if (plan.activationRequired
                && version.state != ShadowLifecycleState.ACTIVATING
                && version.state != ShadowLifecycleState.HEALTHY) {
            recordStaleLaunchResult(plan, "HEALTHY", "generation state is " + version.state);
            return;
        }
        if (!plan.activationRequired && version.state != ShadowLifecycleState.HEALTHY) {
            recordStaleLaunchResult(plan, "HEALTHY", "generation state is " + version.state);
            return;
        }
        version.recordRuntimeHealth(health, plan.operationId);
        if (plan.activationRequired) {
            if (version.state == ShadowLifecycleState.ACTIVATING) {
                transition(plugin, version, ShadowLifecycleState.HEALTHY, plan.operationId, null);
            }
            String oldActive = plugin.activeGeneration;
            if (!version.generation.equals(oldActive)) {
                plugin.previousGeneration = newestHealthyGeneration(
                        plugin,
                        version.generation,
                        oldActive
                );
                plugin.activeGeneration = version.generation;
            }
            if (version.generation.equals(plugin.candidateGeneration)) {
                plugin.candidateGeneration = null;
            }
            plugin.activatingGeneration = null;
            persistRegistry();
            ShadowRegistry.VersionRecord previous = plugin.previousHealthyVersion();
            if (previous != null && previous.state == ShadowLifecycleState.HEALTHY) {
                transition(plugin, previous, ShadowLifecycleState.SUPERSEDED, plan.operationId, null);
            }
        }
        version.consecutiveLaunchFailures = 0;
        version.lastLaunchSuccessAt = System.currentTimeMillis();
        version.healthConfirmedAt = version.lastLaunchSuccessAt;
        version.lastLaunchOperationId = null;
        version.lastError = null;
        persistRegistry();
        journal.append(plan.operationId, "LAUNCH", "HEALTHY", plan.pluginId, plan.generation, null);
        logger.audit("PLUGIN_HEALTHY", plan.operationId, plan.pluginId, plan.generation, null);
        writeLaunchContext(plan, "HEALTHY", null, health);
        garbageCollect(plugin, plan.operationId);
    }

    public synchronized void markLaunchFailed(ShadowLaunchPlan plan, Throwable throwable) throws Exception {
        String error = rootMessage(throwable);
        ShadowRegistry.PluginRecord plugin = registry.plugins.get(plan.pluginId);
        if (plugin == null) {
            recordStaleLaunchResult(plan, "FAILED", "plugin was removed: " + error);
            return;
        }
        ShadowRegistry.VersionRecord failed = plugin.versions.get(plan.generation);
        if (failed == null || !isCurrentLaunch(plugin, failed, plan)) {
            recordStaleLaunchResult(plan, "FAILED", "launch operation was superseded: " + error);
            return;
        }

        failed.totalLaunchFailures++;
        failed.consecutiveLaunchFailures++;
        failed.lastLaunchOperationId = null;
        failed.lastError = error;
        boolean rollbackTriggered = false;
        if (plan.activationRequired) {
            failed.invalidateRuntimeHealth();
            rollbackTriggered = failAndRollback(plugin, failed, plan.operationId, error, false);
        } else if (failed.state == ShadowLifecycleState.HEALTHY
                && failed.consecutiveLaunchFailures >= policy.launchFailureThreshold()) {
            transition(plugin, failed, ShadowLifecycleState.FAILED, plan.operationId, error);
            rollbackTriggered = failAndRollback(plugin, failed, plan.operationId, error, true);
        } else {
            persistRegistry();
        }

        String detail = error
                + " failures=" + failed.consecutiveLaunchFailures
                + "/" + policy.launchFailureThreshold()
                + " rollback=" + rollbackTriggered;
        journal.append(plan.operationId, "LAUNCH",
                rollbackTriggered ? "ROLLED_BACK" : "FAILED",
                plan.pluginId, plan.generation, detail);
        logger.error("PLUGIN_LAUNCH_FAILED", plan.operationId, plan.pluginId, plan.generation,
                detail, throwable);
        writeLaunchContext(plan, rollbackTriggered ? "ROLLED_BACK" : "FAILED", detail);
    }

    private boolean failAndRollback(
            ShadowRegistry.PluginRecord plugin,
            ShadowRegistry.VersionRecord failed,
            String operationId,
            String error,
            boolean alreadyFailed
    ) throws Exception {
        ShadowRegistry.VersionRecord previous = plugin.activeVersion();
        if (previous == null || previous.generation.equals(failed.generation)
                || !previous.isRollbackEligible()) {
            previous = plugin.previousHealthyVersion();
        }
        if (previous != null && previous.generation.equals(failed.generation)) {
            previous = null;
        }
        plugin.activatingGeneration = null;
        if (failed.generation.equals(plugin.candidateGeneration)) {
            plugin.candidateGeneration = null;
        }
        if (previous != null) {
            plugin.activeGeneration = previous.generation;
            if (failed.generation.equals(plugin.previousGeneration)) {
                plugin.previousGeneration = newestHealthyGeneration(
                        plugin,
                        previous.generation,
                        null
                );
            }
            if (ShadowStateMachine.canTransition(failed.state, ShadowLifecycleState.ROLLING_BACK)) {
                transition(plugin, failed, ShadowLifecycleState.ROLLING_BACK, operationId, error);
                transition(plugin, failed, ShadowLifecycleState.ROLLED_BACK, operationId, error);
            } else if (!alreadyFailed
                    && ShadowStateMachine.canTransition(failed.state, ShadowLifecycleState.FAILED)) {
                transition(plugin, failed, ShadowLifecycleState.FAILED, operationId, error);
            }
            persistRegistry();
            return true;
        }
        if (failed.generation.equals(plugin.activeGeneration)) {
            plugin.activeGeneration = null;
        }
        if (failed.generation.equals(plugin.previousGeneration)) {
            plugin.previousGeneration = null;
        }
        if (!alreadyFailed && ShadowStateMachine.canTransition(failed.state, ShadowLifecycleState.FAILED)) {
            transition(plugin, failed, ShadowLifecycleState.FAILED, operationId, error);
        }
        persistRegistry();
        return false;
    }

    private boolean isCurrentLaunch(
            ShadowRegistry.PluginRecord plugin,
            ShadowRegistry.VersionRecord version,
            ShadowLaunchPlan plan
    ) {
        return plugin.enabled
                && plan.generation.equals(plan.activationRequired
                ? plugin.activatingGeneration
                : plugin.activeGeneration)
                && plan.operationId.equals(version.lastLaunchOperationId);
    }

    private void recordStaleLaunchResult(ShadowLaunchPlan plan, String result, String detail)
            throws Exception {
        journal.append(plan.operationId, "LAUNCH", "IGNORED_" + result,
                plan.pluginId, plan.generation, detail);
        logger.warn("STALE_LAUNCH_RESULT", plan.operationId, plan.pluginId, plan.generation,
                result + ": " + detail, null);
    }

    public synchronized void disable(String pluginId) throws Exception {
        ShadowRegistry.PluginRecord plugin = requirePlugin(pluginId);
        String operationId = UUID.randomUUID().toString();
        journal.append(operationId, "DISABLE", "STARTED", pluginId, plugin.activeGeneration, null);
        ShadowRegistry.VersionRecord activating = plugin.activatingVersion();
        plugin.enabled = false;
        plugin.candidateGeneration = null;
        plugin.activatingGeneration = null;
        plugin.updatedAt = System.currentTimeMillis();
        persistRegistry();
        ShadowRegistry.VersionRecord active = plugin.activeVersion();
        if (activating != null
                && (active == null || !activating.generation.equals(active.generation))
                && ShadowStateMachine.canTransition(
                activating.state,
                ShadowLifecycleState.DISABLING
        )) {
            transition(plugin, activating, ShadowLifecycleState.DISABLING, operationId, null);
            transition(plugin, activating, ShadowLifecycleState.DISABLED, operationId, null);
        }
        if (active != null && active.state != ShadowLifecycleState.DISABLED) {
            if (ShadowStateMachine.canTransition(active.state, ShadowLifecycleState.DISABLING)) {
                transition(plugin, active, ShadowLifecycleState.DISABLING, operationId, null);
                transition(plugin, active, ShadowLifecycleState.DISABLED, operationId, null);
            } else {
                journal.append(operationId, "DISABLE", "STATE_DEFERRED", pluginId,
                        active.generation, "state=" + active.state);
            }
        }
        persistRegistry();
        journal.append(operationId, "DISABLE", "COMMITTED", pluginId, plugin.activeGeneration, null);
        logger.audit("PLUGIN_DISABLED", operationId, pluginId, plugin.activeGeneration, null);
    }

    public synchronized void enable(String pluginId) throws Exception {
        ShadowRegistry.PluginRecord plugin = requirePlugin(pluginId);
        String operationId = UUID.randomUUID().toString();
        plugin.enabled = true;
        ShadowRegistry.VersionRecord active = plugin.activeVersion();
        if (active != null && active.state == ShadowLifecycleState.DISABLED) {
            plugin.candidateGeneration = active.generation;
        }
        plugin.updatedAt = System.currentTimeMillis();
        persistRegistry();
        journal.append(operationId, "ENABLE", "COMMITTED", pluginId, plugin.candidateGeneration, null);
        logger.audit("PLUGIN_ENABLED", operationId, pluginId, plugin.candidateGeneration, null);
    }

    public synchronized ShadowLaunchPlan prepareRollback(String pluginId) throws Exception {
        ShadowRegistry.PluginRecord plugin = requirePlugin(pluginId);
        ShadowRegistry.VersionRecord previous = plugin.previousHealthyVersion();
        if (previous == null) {
            String fallback = newestHealthyGeneration(
                    plugin,
                    plugin.activeGeneration,
                    null
            );
            previous = fallback == null ? null : plugin.versions.get(fallback);
        }
        if (previous == null) {
            throw new IllegalStateException(
                    "Plugin has no runtime-verified healthy rollback generation: " + pluginId
            );
        }
        plugin.candidateGeneration = previous.generation;
        plugin.enabled = true;
        persistRegistry();
        return prepareLaunch(pluginId);
    }

    public synchronized void remove(String pluginId) throws Exception {
        ShadowRegistry.PluginRecord plugin = requirePlugin(pluginId);
        String operationId = UUID.randomUUID().toString();
        journal.append(operationId, "REMOVE", "STARTED", pluginId, plugin.activeGeneration, null);
        plugin.enabled = false;
        plugin.candidateGeneration = null;
        plugin.activatingGeneration = null;
        plugin.removalRequested = true;
        plugin.removalOperationId = operationId;
        persistRegistry();
        finalizePluginRemoval(plugin, operationId);
    }

    private void finalizePluginRemoval(
            ShadowRegistry.PluginRecord plugin,
            String operationId
    ) throws Exception {
        for (ShadowRegistry.VersionRecord version : new ArrayList<>(plugin.versions.values())) {
            moveToRemoving(plugin, version, operationId);
        }
        persistRegistry();

        ShadowFileOps.deleteRecursively(new File(
                paths.repositoryPluginsDir(),
                ShadowFileOps.safeSegment(plugin.pluginId)
        ));
        ShadowFileOps.deleteRecursively(new File(
                paths.runtimePackagesDir(),
                ShadowFileOps.safeSegment(plugin.pluginId)
        ));

        for (ShadowRegistry.VersionRecord version : new ArrayList<>(plugin.versions.values())) {
            if (version.state == ShadowLifecycleState.REMOVING) {
                transition(plugin, version, ShadowLifecycleState.REMOVED, operationId, null);
            }
        }
        journal.append(operationId, "REMOVE", "STATE_COMMITTED", plugin.pluginId, null, null);
        registry.plugins.remove(plugin.pluginId);
        persistRegistry();
        journal.append(operationId, "REMOVE", "COMMITTED", plugin.pluginId, null, null);
        logger.audit("PLUGIN_REMOVED", operationId, plugin.pluginId, null, null);
    }

    private void reconcileInbox() throws Exception {
        File[] files = paths.inboxDir().listFiles(File::isFile);
        if (files == null) {
            throw new IOException("Failed to list Shadow inbox: " + paths.inboxDir());
        }
        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, files);
        Collections.sort(sorted, (left, right) -> {
            int time = Long.compare(left.lastModified(), right.lastModified());
            return time != 0
                    ? time
                    : left.getAbsolutePath().compareTo(right.getAbsolutePath());
        });
        for (File file : sorted) {
            if (ShadowPackageContract.isTransientFileName(file.getName())) {
                continue;
            }
            Candidate candidate = new Candidate(file);
            if (ShadowPackageContract.isPackageFileName(file.getName())) {
                importCandidate(candidate);
            } else {
                rejectUnsupportedInboxArtifact(candidate);
            }
        }
    }

    private void rejectUnsupportedInboxArtifact(Candidate candidate) throws Exception {
        String operationId = UUID.randomUUID().toString();
        String error = "Unsupported Shadow inbox artifact; required suffix="
                + ShadowPackageContract.FILE_SUFFIX;
        journal.append(operationId, "IMPORT", "REJECTED", null, null,
                candidate.file.getAbsolutePath() + ": " + error);
        logger.warn("INBOX_ARTIFACT_REJECTED", operationId, null, null,
                candidate.file.getAbsolutePath() + ": " + error, null);
        quarantineCandidate(candidate, null, operationId, error);
    }

    private void importCandidate(Candidate candidate) throws Exception {
        String operationId = UUID.randomUUID().toString();
        File staging = new File(paths.runtimeStagingDir(), operationId + ".shadowpkg");
        ShadowRegistry.PluginRecord plugin = null;
        ShadowRegistry.VersionRecord version = null;
        journal.append(operationId, "IMPORT", "DISCOVERED", null, null, candidate.file.getAbsolutePath());
        logger.info("PLUGIN_DISCOVERED", operationId, null, null, candidate.file.getAbsolutePath());
        try {
            ShadowFileOps.copyAtomically(candidate.file, staging, true);
            journal.append(operationId, "IMPORT", "STAGED", null, null, staging.getAbsolutePath());
            ShadowVerificationResult verification = verifier.verify(staging);
            plugin = registry.plugins.get(verification.manifest.pluginId);
            if (plugin != null && plugin.versions.containsKey(verification.generation)) {
                journal.append(operationId, "IMPORT", "NOOP", plugin.pluginId, verification.generation,
                        "generation already registered");
                archiveInbox(candidate, verification);
                return;
            }

            if (plugin == null) {
                plugin = new ShadowRegistry.PluginRecord(verification.manifest.pluginId);
                registry.plugins.put(plugin.pluginId, plugin);
            }
            version = new ShadowRegistry.VersionRecord(
                    verification,
                    candidate.file.getAbsolutePath(),
                    operationId
            );
            plugin.versions.put(version.generation, version);
            persistRegistry();
            transition(plugin, version, ShadowLifecycleState.STAGED, operationId, null);
            transition(plugin, version, ShadowLifecycleState.VERIFYING, operationId, null);
            transition(plugin, version, ShadowLifecycleState.VERIFIED, operationId, null);
            transition(plugin, version, ShadowLifecycleState.INSTALLING, operationId, null);

            File repositoryDirectory = new File(
                    new File(paths.repositoryPluginsDir(), ShadowFileOps.safeSegment(plugin.pluginId)),
                    ShadowFileOps.safeSegment(version.generation)
            );
            File repositoryPackage = new File(repositoryDirectory, "bundle.shadowpkg");
            ShadowFileOps.copyAtomically(staging, repositoryPackage, true);
            ShadowFileOps.writeAtomically(
                    new File(repositoryDirectory, "manifest.json"),
                    version.manifest.toJson().toString(2).getBytes(StandardCharsets.UTF_8),
                    true
            );

            File runtimeDirectory = new File(
                    new File(paths.runtimePackagesDir(), ShadowFileOps.safeSegment(plugin.pluginId)),
                    ShadowFileOps.safeSegment(version.generation)
            );
            File runtimePackage = new File(runtimeDirectory, "bundle.shadowpkg");
            ShadowFileOps.copyAtomically(repositoryPackage, runtimePackage, true);
            version.repositoryPath = repositoryPackage.getAbsolutePath();
            version.runtimePath = runtimePackage.getAbsolutePath();
            transition(plugin, version, ShadowLifecycleState.INSTALLED, operationId, null);
            plugin.candidateGeneration = version.generation;
            plugin.updatedAt = System.currentTimeMillis();
            persistRegistry();
            journal.append(operationId, "IMPORT", "COMMITTED", plugin.pluginId, version.generation,
                    verification.trustLevel.name());
            logger.audit("PLUGIN_IMPORT_COMMITTED", operationId, plugin.pluginId, version.generation,
                    "trust=" + verification.trustLevel);
            archiveInbox(candidate, verification);
            garbageCollect(plugin, operationId);
        } catch (Throwable throwable) {
            String error = rootMessage(throwable);
            if (plugin != null && version != null && version.state != ShadowLifecycleState.REMOVED) {
                if (ShadowStateMachine.canTransition(version.state, ShadowLifecycleState.FAILED)) {
                    transition(plugin, version, ShadowLifecycleState.FAILED, operationId, error);
                }
            }
            quarantineCandidate(candidate, staging, operationId, error);
            journal.append(operationId, "IMPORT", "FAILED",
                    plugin == null ? null : plugin.pluginId,
                    version == null ? null : version.generation,
                    error);
            logger.error("PLUGIN_IMPORT_FAILED", operationId,
                    plugin == null ? null : plugin.pluginId,
                    version == null ? null : version.generation,
                    error,
                    throwable);
        } finally {
            if (staging.exists()) {
                //noinspection ResultOfMethodCallIgnored
                staging.delete();
            }
        }
    }

    private void validateRegisteredFiles() throws Exception {
        boolean changed = false;
        String operationId = "reconcile-" + UUID.randomUUID();
        for (ShadowRegistry.PluginRecord plugin : registry.plugins.values()) {
            Set<String> generations = new HashSet<>();
            addIfPresent(generations, plugin.previousGeneration);
            addIfPresent(generations, plugin.candidateGeneration);
            addIfPresent(generations, plugin.activatingGeneration);
            addIfPresent(generations, plugin.activeGeneration);
            for (String generation : generations) {
                ShadowRegistry.VersionRecord version = plugin.versions.get(generation);
                if (version == null) {
                    clearPointer(plugin, generation);
                    changed = true;
                    continue;
                }
                if (repairVersionFiles(plugin, version, operationId)) {
                    continue;
                }

                String fallbackGeneration = generation.equals(plugin.activeGeneration)
                        ? plugin.previousGeneration
                        : null;
                ShadowRegistry.VersionRecord fallback = fallbackGeneration == null
                        ? null
                        : plugin.versions.get(fallbackGeneration);
                if (fallback != null
                        && (fallback.generation.equals(generation)
                        || !fallback.isRollbackEligible()
                        || !repairVersionFiles(plugin, fallback, operationId))) {
                    fallback = null;
                }
                String error = "No valid repository or runtime copy for registered generation";
                version.lastError = error;
                if (ShadowStateMachine.canTransition(version.state, ShadowLifecycleState.FAILED)) {
                    transition(plugin, version, ShadowLifecycleState.FAILED, operationId, error);
                }
                clearPointer(plugin, generation);
                if (fallback != null) {
                    plugin.activeGeneration = fallback.generation;
                    plugin.previousGeneration = null;
                } else if (fallbackGeneration != null
                        && fallbackGeneration.equals(plugin.previousGeneration)) {
                    plugin.previousGeneration = null;
                }
                journal.append(operationId, "RECONCILE", "UNRECOVERABLE",
                        plugin.pluginId, generation, error);
                logger.error("PLUGIN_STORAGE_UNRECOVERABLE", operationId, plugin.pluginId,
                        generation, error, null);
                changed = true;
            }
        }
        if (changed) {
            persistRegistry();
        }
    }

    private boolean repairVersionFiles(
            ShadowRegistry.PluginRecord plugin,
            ShadowRegistry.VersionRecord version,
            String operationId
    ) throws Exception {
        File repositoryDirectory = new File(
                new File(paths.repositoryPluginsDir(), ShadowFileOps.safeSegment(plugin.pluginId)),
                ShadowFileOps.safeSegment(version.generation)
        );
        File repositoryPackage = new File(repositoryDirectory, "bundle.shadowpkg");
        File runtimeDirectory = new File(
                new File(paths.runtimePackagesDir(), ShadowFileOps.safeSegment(plugin.pluginId)),
                ShadowFileOps.safeSegment(version.generation)
        );
        File runtimePackage = new File(runtimeDirectory, "bundle.shadowpkg");

        boolean repositoryValid = hasDigest(repositoryPackage, version.bundleSha256);
        boolean runtimeValid = hasDigest(runtimePackage, version.bundleSha256);
        if (!repositoryValid && runtimeValid) {
            ShadowFileOps.copyAtomically(runtimePackage, repositoryPackage, true);
            repositoryValid = true;
            journal.append(operationId, "RECONCILE", "REPOSITORY_REPAIRED",
                    plugin.pluginId, version.generation, null);
            logger.audit("PLUGIN_REPOSITORY_REPAIRED", operationId, plugin.pluginId,
                    version.generation, null);
        }
        if (!runtimeValid && repositoryValid) {
            ShadowFileOps.copyAtomically(repositoryPackage, runtimePackage, true);
            runtimeValid = true;
            journal.append(operationId, "RECONCILE", "RUNTIME_REPAIRED",
                    plugin.pluginId, version.generation, null);
            logger.audit("PLUGIN_RUNTIME_REPAIRED", operationId, plugin.pluginId,
                    version.generation, null);
        }
        if (repositoryValid && runtimeValid) {
            version.repositoryPath = repositoryPackage.getAbsolutePath();
            version.runtimePath = runtimePackage.getAbsolutePath();
            return true;
        }
        return false;
    }

    private static boolean hasDigest(File file, String expectedSha256) {
        if (!file.isFile()) {
            return false;
        }
        try {
            return expectedSha256.equals(ShadowFileOps.sha256(file));
        } catch (IOException ignored) {
            return false;
        }
    }

    private void recoverInterruptedOperations() throws Exception {
        boolean changed = false;
        String operationId = "recovery-" + UUID.randomUUID();
        for (ShadowRegistry.PluginRecord plugin : new ArrayList<>(registry.plugins.values())) {
            if (!plugin.removalRequested) {
                continue;
            }
            String removalOperationId = plugin.removalOperationId == null
                    ? operationId
                    : plugin.removalOperationId;
            try {
                finalizePluginRemoval(plugin, removalOperationId);
            } catch (Throwable throwable) {
                String error = "Pending removal recovery failed: " + rootMessage(throwable);
                journal.append(operationId, "RECOVERY", "REMOVE_PENDING", plugin.pluginId,
                        null, error);
                logger.warn("PLUGIN_REMOVE_RECOVERY_PENDING", operationId, plugin.pluginId,
                        null, error, throwable);
            }
        }

        for (ShadowRegistry.PluginRecord plugin : new ArrayList<>(registry.plugins.values())) {
            if (plugin.removalRequested) {
                continue;
            }
            for (ShadowRegistry.VersionRecord version : new ArrayList<>(plugin.versions.values())) {
                if (!ShadowStateMachine.isTransient(version.state)) {
                    continue;
                }
                String error = "Recovered after process interruption from " + version.state;
                if (version.state == ShadowLifecycleState.REMOVING) {
                    deleteVersionFiles(version);
                    version.transition(ShadowLifecycleState.REMOVED, operationId, null);
                    plugin.versions.remove(version.generation);
                    clearPointer(plugin, version.generation);
                } else if (version.state == ShadowLifecycleState.DISABLING && !plugin.enabled) {
                    version.transition(ShadowLifecycleState.DISABLED, operationId, null);
                } else if ((version.state == ShadowLifecycleState.ACTIVATING
                        || version.state == ShadowLifecycleState.ROLLING_BACK)
                        && plugin.activeVersion() != null
                        && plugin.activeVersion().isRollbackEligible()) {
                    if (version.state == ShadowLifecycleState.ACTIVATING) {
                        version.transition(ShadowLifecycleState.ROLLING_BACK, operationId, error);
                    }
                    version.transition(ShadowLifecycleState.ROLLED_BACK, operationId, error);
                } else if (ShadowStateMachine.canTransition(version.state, ShadowLifecycleState.FAILED)) {
                    version.transition(ShadowLifecycleState.FAILED, operationId, error);
                    clearPointer(plugin, version.generation);
                }
                if (version.generation.equals(plugin.candidateGeneration)) {
                    plugin.candidateGeneration = null;
                }
                if (version.generation.equals(plugin.activatingGeneration)) {
                    plugin.activatingGeneration = null;
                }
                changed = true;
                journal.append(operationId, "RECOVERY", "RECOVERED", plugin.pluginId,
                        version.generation, error);
            }
        }
        if (changed) {
            persistRegistry();
            logger.warn("PLATFORM_RECOVERED", operationId, null, null,
                    "Recovered interrupted Shadow operations", null);
        }
    }

    private static void clearPointer(ShadowRegistry.PluginRecord plugin, String generation) {
        if (generation.equals(plugin.activeGeneration)) {
            plugin.activeGeneration = null;
        }
        if (generation.equals(plugin.previousGeneration)) {
            plugin.previousGeneration = null;
        }
        if (generation.equals(plugin.candidateGeneration)) {
            plugin.candidateGeneration = null;
        }
        if (generation.equals(plugin.activatingGeneration)) {
            plugin.activatingGeneration = null;
        }
    }

    private void garbageCollect(ShadowRegistry.PluginRecord plugin, String operationId) throws Exception {
        Set<String> protectedGenerations = new HashSet<>();
        addIfPresent(protectedGenerations, plugin.activeGeneration);
        addIfPresent(protectedGenerations, plugin.previousGeneration);
        addIfPresent(protectedGenerations, plugin.candidateGeneration);
        addIfPresent(protectedGenerations, plugin.activatingGeneration);
        List<ShadowRegistry.VersionRecord> versions = new ArrayList<>(plugin.versions.values());
        Collections.sort(versions, (left, right) -> Long.compare(right.installedAt, left.installedAt));
        int retained = protectedGenerations.size();
        for (ShadowRegistry.VersionRecord version : versions) {
            if (protectedGenerations.contains(version.generation)) {
                continue;
            }
            if (retained < policy.maxVersionsPerPlugin()) {
                retained++;
                continue;
            }
            if (!ShadowStateMachine.canTransition(version.state, ShadowLifecycleState.REMOVING)) {
                continue;
            }
            moveToRemoving(plugin, version, operationId);
            deleteVersionFiles(version);
            transition(plugin, version, ShadowLifecycleState.REMOVED, operationId, null);
            plugin.versions.remove(version.generation);
            journal.append(operationId, "GC", "REMOVED", plugin.pluginId, version.generation, null);
        }
        persistRegistry();
    }

    private void moveToRemoving(
            ShadowRegistry.PluginRecord plugin,
            ShadowRegistry.VersionRecord version,
            String operationId
    ) throws Exception {
        if (version.state == ShadowLifecycleState.REMOVED
                || version.state == ShadowLifecycleState.REMOVING) {
            return;
        }
        if (!ShadowStateMachine.canTransition(version.state, ShadowLifecycleState.REMOVING)) {
            if (ShadowStateMachine.canTransition(version.state, ShadowLifecycleState.FAILED)) {
                transition(plugin, version, ShadowLifecycleState.FAILED, operationId, "removed by operator");
            }
        }
        if (ShadowStateMachine.canTransition(version.state, ShadowLifecycleState.REMOVING)) {
            transition(plugin, version, ShadowLifecycleState.REMOVING, operationId, null);
        }
    }

    private void deleteVersionFiles(ShadowRegistry.VersionRecord version) throws IOException {
        if (version.repositoryPath != null) {
            ShadowFileOps.deleteRecursively(new File(version.repositoryPath).getParentFile());
        }
        if (version.runtimePath != null) {
            ShadowFileOps.deleteRecursively(new File(version.runtimePath).getParentFile());
        }
    }

    private void transition(
            ShadowRegistry.PluginRecord plugin,
            ShadowRegistry.VersionRecord version,
            ShadowLifecycleState target,
            String operationId,
            String error
    ) throws Exception {
        ShadowLifecycleState previous = version.state;
        version.transition(target, operationId, error);
        plugin.updatedAt = System.currentTimeMillis();
        persistRegistry();
        journal.append(operationId, "STATE", previous + "->" + target,
                plugin.pluginId, version.generation, error);
        logger.audit("PLUGIN_STATE_CHANGED", operationId, plugin.pluginId, version.generation,
                previous + "->" + target + (error == null ? "" : " error=" + error));
    }

    private List<ShadowPluginDescriptor> listPluginsLocked() {
        List<ShadowPluginDescriptor> result = new ArrayList<>();
        for (ShadowRegistry.PluginRecord plugin : registry.plugins.values()) {
            ShadowRegistry.VersionRecord candidate = plugin.candidateVersion();
            ShadowRegistry.VersionRecord selected = candidate != null ? candidate : plugin.activeVersion();
            if (selected == null) {
                selected = newestLaunchableVersion(plugin);
            }
            if (selected != null) {
                result.add(new ShadowPluginDescriptor(plugin, selected, candidate != null));
            }
        }
        Collections.sort(result, Comparator.comparing(descriptor -> descriptor.displayName));
        return result;
    }

    private ShadowRegistry.VersionRecord newestLaunchableVersion(ShadowRegistry.PluginRecord plugin) {
        List<ShadowRegistry.VersionRecord> versions = new ArrayList<>(plugin.versions.values());
        Collections.sort(versions, (left, right) -> Long.compare(right.installedAt, left.installedAt));
        for (ShadowRegistry.VersionRecord version : versions) {
            if (isActivatable(version.state) || version.state == ShadowLifecycleState.HEALTHY) {
                return version;
            }
        }
        return null;
    }

    private boolean isActivatable(ShadowLifecycleState state) {
        return state == ShadowLifecycleState.INSTALLED
                || state == ShadowLifecycleState.DISABLED
                || state == ShadowLifecycleState.SUPERSEDED
                || state == ShadowLifecycleState.ROLLED_BACK;
    }

    private String newestHealthyGeneration(
            ShadowRegistry.PluginRecord plugin,
            String excludedGeneration,
            String preferredGeneration
    ) {
        if (preferredGeneration != null && !preferredGeneration.equals(excludedGeneration)) {
            ShadowRegistry.VersionRecord preferred = plugin.versions.get(preferredGeneration);
            if (preferred != null && preferred.isRollbackEligible()) {
                return preferred.generation;
            }
        }
        List<ShadowRegistry.VersionRecord> versions = new ArrayList<>(plugin.versions.values());
        Collections.sort(versions, (left, right) -> {
            int byHealth = Long.compare(right.lastLaunchSuccessAt, left.lastLaunchSuccessAt);
            return byHealth != 0
                    ? byHealth
                    : Long.compare(right.installedAt, left.installedAt);
        });
        for (ShadowRegistry.VersionRecord version : versions) {
            if (!version.generation.equals(excludedGeneration) && version.isRollbackEligible()) {
                return version.generation;
            }
        }
        return null;
    }

    private void archiveInbox(Candidate candidate, ShadowVerificationResult verification) {
        if (!candidate.file.exists()) {
            return;
        }
        File destination = new File(
                paths.inboxArchiveDir(),
                ShadowFileOps.safeSegment(verification.manifest.pluginId)
                        + "-" + ShadowFileOps.safeSegment(verification.generation)
                        + ".shadowpkg"
        );
        try {
            if (!destination.exists()) {
                ShadowFileOps.copyAtomically(candidate.file, destination, true);
            }
            if (!candidate.file.delete() && candidate.file.exists()) {
                throw new IOException("Failed to remove archived inbox package");
            }
        } catch (IOException error) {
            logger.warn("INBOX_ARCHIVE_FAILED", null, verification.manifest.pluginId,
                    verification.generation, candidate.file.getAbsolutePath(), error);
        }
    }

    private void quarantineCandidate(
            Candidate candidate,
            File staging,
            String operationId,
            String error
    ) {
        File source = staging != null && staging.isFile() ? staging : candidate.file;
        if (!source.isFile()) {
            return;
        }
        File target = new File(
                paths.quarantineDir(),
                System.currentTimeMillis() + "-" + operationId + "-"
                        + ShadowFileOps.safeSegment(candidate.file.getName())
        );
        try {
            ShadowFileOps.copyAtomically(source, target, true);
        } catch (IOException quarantineError) {
            logger.warn("QUARANTINE_FAILED", operationId, null, null,
                    target.getAbsolutePath(), quarantineError);
            return;
        }
        try {
            JSONObject metadata = new JSONObject();
            metadata.put("schemaVersion", 1);
            metadata.put("operationId", operationId);
            metadata.put("sourcePath", candidate.file.getAbsolutePath());
            metadata.put("quarantinedAt", System.currentTimeMillis());
            metadata.put("error", error);
            ShadowFileOps.writeAtomically(
                    new File(target.getParentFile(), target.getName() + ".json"),
                    metadata.toString(2).getBytes(StandardCharsets.UTF_8),
                    false
            );
        } catch (Exception metadataError) {
            logger.warn("QUARANTINE_METADATA_FAILED", operationId, null, null,
                    target.getAbsolutePath(), metadataError);
        }
        if (candidate.file.exists() && !candidate.file.delete()) {
            logger.warn("QUARANTINE_SOURCE_DELETE_FAILED", operationId, null, null,
                    candidate.file.getAbsolutePath(), null);
        }
    }

    private FileAndHash prepareManagerAsset() throws Exception {
        byte[] bytes = readAssetBounded("pluginmanager.apk", MAX_MANAGER_BYTES);
        File temporary = new File(paths.runtimeStagingDir(), "manager-asset.apk");
        ShadowFileOps.writeAtomically(temporary, bytes, true);
        String sha256 = ShadowFileOps.sha256(temporary);
        File destination = new File(paths.runtimeManagersDir(), sha256 + ".apk");
        if (!hasDigest(destination, sha256)) {
            ShadowFileOps.copyAtomically(temporary, destination, true);
        }
        //noinspection ResultOfMethodCallIgnored
        temporary.delete();
        garbageCollectManagerAssets(destination);
        return new FileAndHash(destination, sha256);
    }

    private void garbageCollectManagerAssets(File current) {
        File[] managers = paths.runtimeManagersDir().listFiles(file -> file.isFile()
                && file.getName().endsWith(".apk"));
        if (managers == null || managers.length <= 3) {
            return;
        }
        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, managers);
        Collections.sort(sorted, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        int retainedOtherVersions = 0;
        for (File manager : sorted) {
            if (manager.equals(current)) {
                continue;
            }
            if (retainedOtherVersions < 2) {
                retainedOtherVersions++;
                continue;
            }
            if (!manager.delete() && manager.exists()) {
                logger.warn("MANAGER_ASSET_GC_FAILED", null, null, null,
                        manager.getAbsolutePath(), null);
            }
        }
    }

    private void retireObsoleteManagedIngress() throws Exception {
        File[] obsoleteFiles = new File[]{
                new File(paths.homeRoot(), "plugin-debug.zip"),
                new File(paths.homeRoot(), "plugin-release.zip"),
                new File(paths.runtimeStateDir(), "legacy-imports.json"),
                new File(paths.reportsDir(), "legacy-imports.json")
        };
        List<String> removed = new ArrayList<>();
        for (File obsolete : obsoleteFiles) {
            if (!obsolete.exists()) {
                continue;
            }
            ShadowFileOps.deleteRecursively(obsolete);
            removed.add(obsolete.getAbsolutePath());
        }
        if (removed.isEmpty()) {
            return;
        }
        String operationId = "single-ingress-" + UUID.randomUUID();
        String detail = "removed=" + removed;
        journal.append(operationId, "INGRESS_MIGRATION", "COMMITTED", null, null, detail);
        logger.audit("SINGLE_INGRESS_COMMITTED", operationId, null, null, detail);
    }

    private void migrateLegacyEngineStorage() {
        File marker = paths.engineMigrationMarkerFile();
        if (marker.isFile()) {
            return;
        }
        String operationId = "engine-migration-" + UUID.randomUUID();
        boolean complete = true;
        File[] legacyDirectories = new File[]{
                new File(context.getFilesDir(), "ShadowPluginManager"),
                new File(context.getFilesDir(), "ManagerImplLoader")
        };
        for (File directory : legacyDirectories) {
            try {
                ShadowFileOps.deleteRecursively(directory);
            } catch (Throwable throwable) {
                complete = false;
                logger.warn("LEGACY_ENGINE_CACHE_DELETE_FAILED", operationId, null, null,
                        directory.getAbsolutePath(), throwable);
            }
        }

        String legacyDatabase = "shadow_installed_plugin_dbtest-dynamic-manager";
        File databaseFile = context.getDatabasePath(legacyDatabase);
        if (databaseFile.exists() && !context.deleteDatabase(legacyDatabase)) {
            complete = false;
            logger.warn("LEGACY_ENGINE_DATABASE_DELETE_FAILED", operationId, null, null,
                    databaseFile.getAbsolutePath(), null);
        }

        try {
            journal.append(operationId, "ENGINE_MIGRATION",
                    complete ? "COMMITTED" : "PENDING", null, null,
                    "storage=~/.termux-shadow/engine");
            if (complete) {
                ShadowFileOps.writeAtomically(
                        marker,
                        ("migratedAt=" + System.currentTimeMillis() + "\n")
                                .getBytes(StandardCharsets.UTF_8),
                        true
                );
                logger.audit("ENGINE_STORAGE_MIGRATED", operationId, null, null,
                        "storage=~/.termux-shadow/engine");
            }
        } catch (Throwable throwable) {
            logger.warn("ENGINE_MIGRATION_RECORD_FAILED", operationId, null, null,
                    "storage=~/.termux-shadow/engine", throwable);
        }
    }

    private byte[] readAssetBounded(String name, int maxBytes) throws IOException {
        InputStream input = null;
        try {
            input = context.getAssets().open(name);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Shadow manager asset is too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            if (input != null) {
                input.close();
            }
        }
    }

    private void persistRegistry() throws Exception {
        registry.persist(paths.registryFile());
        publishRegistrySnapshot();
    }

    private void publishRegistrySnapshot() {
        try {
            ShadowFileOps.writeAtomically(
                    new File(paths.reportsDir(), "registry.json"),
                    registry.toJson().toString(2).getBytes(StandardCharsets.UTF_8),
                    false
            );
            publishHealthSnapshot();
        } catch (Throwable throwable) {
            logger.warn("REGISTRY_SNAPSHOT_FAILED", null, null, null,
                    "Failed to publish operator registry snapshot", throwable);
        }
    }

    private void publishHealthSnapshot() throws Exception {
        JSONObject states = new JSONObject();
        int versions = 0;
        int enabledPlugins = 0;
        for (ShadowRegistry.PluginRecord plugin : registry.plugins.values()) {
            if (plugin.enabled) {
                enabledPlugins++;
            }
            for (ShadowRegistry.VersionRecord version : plugin.versions.values()) {
                versions++;
                String state = version.state.name();
                states.put(state, states.optInt(state, 0) + 1);
            }
        }
        JSONObject health = new JSONObject();
        health.put("schemaVersion", 1);
        health.put("status", "READY");
        health.put("updatedAt", System.currentTimeMillis());
        health.put("registryRevision", registry.revision);
        health.put("plugins", registry.plugins.size());
        health.put("enabledPlugins", enabledPlugins);
        health.put("versions", versions);
        health.put("states", states);
        health.put("managerSha256", managerSha256);
        health.put("ingressMode", ShadowPackageContract.INGRESS_MODE);
        health.put("packageSchemaVersion", ShadowPackageContract.SCHEMA_VERSION);
        health.put("packageFileSuffix", ShadowPackageContract.FILE_SUFFIX);
        health.put("requireSignature", policy.requireSignature());
        health.put("maxVersionsPerPlugin", policy.maxVersionsPerPlugin());
        health.put("launchFailureThreshold", policy.launchFailureThreshold());
        health.put("launchHealthTimeoutMs", policy.launchHealthTimeoutMs());
        health.put("launchStabilityWindowMs", policy.launchStabilityWindowMs());
        ShadowFileOps.writeAtomically(
                new File(paths.reportsDir(), "health.json"),
                health.toString(2).getBytes(StandardCharsets.UTF_8),
                false
        );
    }

    private void writeLaunchContext(ShadowLaunchPlan plan, String status, String error) {
        writeLaunchContext(plan, status, error, null);
    }

    private void writeLaunchContext(
            ShadowLaunchPlan plan,
            String status,
            String error,
            ShadowRuntimeHealth health
    ) {
        try {
            JSONObject context = new JSONObject();
            context.put("schemaVersion", 2);
            context.put("pluginId", plan.pluginId);
            context.put("generation", plan.generation);
            context.put("operationId", plan.operationId);
            context.put("partKey", plan.partKey);
            context.put("activityClassName", plan.activityClassName);
            context.put("status", status);
            context.put("updatedAt", System.currentTimeMillis());
            context.put("error", error == null ? JSONObject.NULL : error);
            context.put(
                    "healthSemantics",
                    health != null && health.smokeRequested
                            ? "FIRST_FRAME_UI_SMOKE_AND_PROCESS_STABILITY"
                            : "FIRST_FRAME_AND_PROCESS_STABILITY"
            );
            if (health != null) {
                context.put("healthProtocolVersion", health.protocolVersion);
                context.put("firstFrameElapsedMs", health.firstFrameElapsedMs);
                context.put("stableElapsedMs", health.stableElapsedMs > 0L
                        ? health.stableElapsedMs
                        : JSONObject.NULL);
                context.put("pluginProcessPid", health.pluginProcessPid);
                context.put("pluginProcessName", health.pluginProcessName == null
                        ? JSONObject.NULL
                        : health.pluginProcessName);
                context.put("smokeRequested", health.smokeRequested);
                context.put("smokePassed", health.smokePassed);
                context.put("smokeStepCount", health.smokeStepCount);
                context.put("smokeDurationMs", health.smokeDurationMs);
                context.put("smokeError", health.smokeError == null
                        ? JSONObject.NULL
                        : health.smokeError);
            }
            byte[] bytes = context.toString(2).getBytes(StandardCharsets.UTF_8);
            ShadowFileOps.writeAtomically(paths.launchContextFile(), bytes, false);
            ShadowFileOps.writeAtomically(paths.launchReportFile(plan.pluginId), bytes, false);
            ShadowFileOps.writeAtomically(
                    new File(paths.reportsDir(), "last-launch.json"),
                    bytes,
                    false
            );
        } catch (Throwable throwable) {
            logger.warn("LAUNCH_CONTEXT_WRITE_FAILED", plan.operationId, plan.pluginId,
                    plan.generation, status, throwable);
        }
    }

    private ShadowRegistry.PluginRecord requirePlugin(String pluginId) {
        ShadowRegistry.PluginRecord plugin = registry.plugins.get(pluginId);
        if (plugin == null) {
            throw new IllegalArgumentException("Unknown Shadow plugin: " + pluginId);
        }
        return plugin;
    }

    private ShadowRegistry.VersionRecord requireVersion(
            ShadowRegistry.PluginRecord plugin,
            String generation
    ) {
        ShadowRegistry.VersionRecord version = plugin.versions.get(generation);
        if (version == null) {
            throw new IllegalArgumentException(
                    "Unknown Shadow generation: " + plugin.pluginId + "/" + generation
            );
        }
        return version;
    }

    private static void addIfPresent(Set<String> values, String value) {
        if (value != null) {
            values.add(value);
        }
    }

    private static long hostVersionCode(Context context) throws PackageManager.NameNotFoundException {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode()
                : info.versionCode;
    }

    private static String rootMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown failure";
        }
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.length() == 0
                ? current.getClass().getSimpleName()
                : message;
    }

    private static final class Candidate {
        final File file;

        Candidate(File file) {
            this.file = file;
        }
    }

    private static final class FileAndHash {
        final File file;
        final String sha256;

        FileAndHash(File file, String sha256) {
            this.file = file;
            this.sha256 = sha256;
        }
    }
}
