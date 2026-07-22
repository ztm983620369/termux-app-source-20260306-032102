package com.termux.app.terminal;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxService;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Service-owned reconciliation loop for durable native Codex session leases. */
public final class CodexSessionRecoveryController {

    private static final long INITIAL_RESTART_DELAY_MS = 250L;
    private static final long MAX_RESTART_DELAY_MS = 30_000L;
    private static final long WATCHDOG_INTERVAL_MS = 5_000L;
    private static final long MATERIALIZATION_GRACE_MS = 12_000L;
    private static final long RESTART_STABILITY_WINDOW_MS = 120_000L;
    private static final int MAX_AUTOMATIC_RESTART_ATTEMPTS = 4;

    @NonNull private final TermuxService mService;
    @NonNull private final Handler mHandler;
    @NonNull private final Map<String, Integer> mRestartAttempts = new HashMap<>();
    @NonNull private final Map<String, Runnable> mRestartTasks = new HashMap<>();
    @NonNull private final Map<String, Long> mMaterializationDeadlines = new HashMap<>();
    @NonNull private final Map<String, Long> mVerifiedSince = new HashMap<>();
    @NonNull private final Set<String> mHealthyThreads = new HashSet<>();
    @NonNull private final Set<String> mInternallyRetiringHandles = new HashSet<>();
    @NonNull private final Set<String> mSuspendedThreads = new HashSet<>();
    @NonNull private final Runnable mWatchdog = () -> reconcileOnMain("watchdog");

    private boolean mStarted;

    public CodexSessionRecoveryController(@NonNull TermuxService service) {
        mService = service;
        mHandler = new Handler(Looper.getMainLooper());
    }

    public void start(@NonNull String reason) {
        runOnMain(() -> {
            mStarted = true;
            reconcileOnMain(reason);
        });
    }

    public void reconcile(@NonNull String reason) {
        runOnMain(() -> {
            mStarted = true;
            reconcileOnMain(reason);
        });
    }

    public void stop() {
        runOnMain(() -> {
            mStarted = false;
            mHandler.removeCallbacks(mWatchdog);
            for (Runnable task : mRestartTasks.values()) mHandler.removeCallbacks(task);
            mRestartTasks.clear();
            mRestartAttempts.clear();
            mMaterializationDeadlines.clear();
            mVerifiedSince.clear();
            mHealthyThreads.clear();
            mInternallyRetiringHandles.clear();
            mSuspendedThreads.clear();
        });
    }

    public boolean handleFinishedSession(@NonNull TerminalSession terminalSession) {
        TermuxSessionRestoreStore.CodexLease lease = findLeaseForSession(terminalSession);
        String handle = safeHandle(terminalSession);
        if (lease == null && !mInternallyRetiringHandles.contains(handle)) return false;
        if (lease == null) {
            runOnMain(() -> mInternallyRetiringHandles.remove(handle));
            return true;
        }
        if (!mInternallyRetiringHandles.contains(handle) &&
            isIntentionalExitStatus(terminalSession.getExitStatus())) {
            int exitStatus = terminalSession.getExitStatus();
            TermuxSessionRestoreStore.appendCodexAudit(
                "process_lost", lease.threadId, handle, "exit_status=" + exitStatus);
            TermuxSessionRestoreStore.revokeCodexLease(
                lease.threadId, handle, "intentional_exit_status=" + exitStatus);
            TermuxSessionRestoreStore.appendCodexAudit(
                "runtime_exit_final", lease.threadId, handle, "exit_status=" + exitStatus);
            runOnMain(() -> cancelThread(lease.threadId));
            // Let the normal terminal-session path remove the tab and choose a
            // replacement. Recovery owns only abnormal process loss.
            return false;
        }
        runOnMain(() -> handleFinishedSessionOnMain(terminalSession, lease.threadId));
        return true;
    }

    public void onLeaseReady(@NonNull String threadId, @NonNull TerminalSession terminalSession) {
        runOnMain(() -> {
            TermuxSessionRestoreStore.CodexLease lease =
                TermuxSessionRestoreStore.findCodexLeaseByThread(threadId);
            if (lease == null) return;
            int order = mService.getIndexOfSession(terminalSession);
            TermuxSessionRestoreStore.rebindCodexLease(threadId, safeHandle(terminalSession), order);
            mStarted = true;
            markHealthy(threadId, terminalSession, "host_ready");
            scheduleWatchdog();
        });
    }

    public void onLeaseClosed(@NonNull String threadId) {
        runOnMain(() -> cancelThread(threadId));
    }

    public void onSessionMaterializing(@NonNull String threadId,
                                       @NonNull TerminalSession terminalSession) {
        runOnMain(() -> {
            mSuspendedThreads.remove(threadId);
            mRestartAttempts.remove(threadId);
            mVerifiedSince.remove(threadId);
            int order = mService.getIndexOfSession(terminalSession);
            TermuxSessionRestoreStore.UpdateResult result =
                TermuxSessionRestoreStore.rebindCodexLease(threadId, safeHandle(terminalSession), order);
            if (result == TermuxSessionRestoreStore.UpdateResult.IGNORED) return;
            mHealthyThreads.remove(threadId);
            mMaterializationDeadlines.put(threadId,
                SystemClock.elapsedRealtime() + MATERIALIZATION_GRACE_MS);
            mStarted = true;
            scheduleWatchdog();
        });
    }

    public void revokeLease(@Nullable String threadId, @Nullable String handle,
                            @NonNull String detail) {
        TermuxSessionRestoreStore.CodexLease lease = TextUtils.isEmpty(threadId)
            ? TermuxSessionRestoreStore.findCodexLeaseByHandle(handle)
            : TermuxSessionRestoreStore.findCodexLeaseByThread(threadId);
        String resolvedThreadId = lease == null ? normalize(threadId) : lease.threadId;
        TermuxSessionRestoreStore.revokeCodexLease(resolvedThreadId, handle, detail);
        runOnMain(() -> cancelThread(resolvedThreadId));
    }

    static long computeRestartDelayMs(int attempt) {
        int exponent = Math.max(0, Math.min(attempt, 16));
        long delay = INITIAL_RESTART_DELAY_MS << exponent;
        return Math.min(delay, MAX_RESTART_DELAY_MS);
    }

    static boolean shouldSuspendRestart(int attempt) {
        return attempt >= MAX_AUTOMATIC_RESTART_ATTEMPTS;
    }

    static boolean shouldResetRestartAttempts(long verifiedDurationMs) {
        return verifiedDurationMs >= RESTART_STABILITY_WINDOW_MS;
    }

    static boolean isIntentionalExitStatus(int exitStatus) {
        return exitStatus == 0 || exitStatus == 130;
    }

    static boolean isTerminalStartupPending(int shellPid) {
        // Background tabs are initialized lazily when first displayed and may stay at pid 0.
        return shellPid == 0;
    }

    private void handleFinishedSessionOnMain(@NonNull TerminalSession terminalSession,
                                             @NonNull String threadId) {
        TermuxSessionRestoreStore.CodexLease lease =
            TermuxSessionRestoreStore.findCodexLeaseByThread(threadId);
        if (lease == null) return;

        String handle = safeHandle(terminalSession);
        boolean internallyRetired = mInternallyRetiringHandles.remove(handle);
        int exitStatus = terminalSession.getExitStatus();
        mHealthyThreads.remove(threadId);
        mMaterializationDeadlines.remove(threadId);
        mVerifiedSince.remove(threadId);
        TermuxSessionRestoreStore.appendCodexAudit(
            "process_lost", threadId, handle,
            internallyRetired ? "stale_terminal_retired" : "exit_status=" + exitStatus);

        if (mService.getTermuxSessionForTerminalSession(terminalSession) != null &&
            !terminalSession.isRunning()) {
            mService.removeTermuxSession(terminalSession);
        }

        if (mService.wantsToStop()) {
            TermuxSessionRestoreStore.appendCodexAudit(
                "runtime_restart_deferred", threadId, handle, "service_stopping; lease retained");
            return;
        }
        mStarted = true;
        scheduleRestart(lease, internallyRetired ? "retired_stale_terminal" : "terminal_finished");
        scheduleWatchdog();
    }

    private void reconcileOnMain(@NonNull String reason) {
        mHandler.removeCallbacks(mWatchdog);
        if (!mStarted || mService.wantsToStop()) return;

        ArrayList<TermuxSessionRestoreStore.CodexLease> leases =
            TermuxSessionRestoreStore.listCodexLeases();
        if (leases.isEmpty()) return;

        long now = SystemClock.elapsedRealtime();
        for (TermuxSessionRestoreStore.CodexLease lease : leases) {
            TerminalSession terminalSession = findBoundSession(lease);
            if (terminalSession == null) {
                mHealthyThreads.remove(lease.threadId);
                mVerifiedSince.remove(lease.threadId);
                scheduleRestart(lease, reason + ":terminal_missing");
                continue;
            }

            int shellPid = terminalSession.getPid();
            Long deadline = mMaterializationDeadlines.get(lease.threadId);
            if (isTerminalStartupPending(shellPid)) {
                continue;
            }

            if (shellPid <= 0) {
                mHealthyThreads.remove(lease.threadId);
                mVerifiedSince.remove(lease.threadId);
                if (mService.getTermuxSessionForTerminalSession(terminalSession) != null) {
                    mService.removeTermuxSession(terminalSession);
                }
                scheduleRestart(lease, reason + ":terminal_finished");
                continue;
            }

            int codexPid = findVerifiedCodexProcess(lease, terminalSession);
            if (codexPid > 0) {
                markHealthy(lease.threadId, terminalSession, reason + ":pid=" + codexPid);
                continue;
            }

            if (deadline != null && now < deadline) continue;

            mHealthyThreads.remove(lease.threadId);
            mVerifiedSince.remove(lease.threadId);
            TermuxSessionRestoreStore.appendCodexAudit(
                "runtime_process_unverified", lease.threadId, safeHandle(terminalSession),
                reason + ";running terminal retained");
            mMaterializationDeadlines.put(
                lease.threadId, SystemClock.elapsedRealtime() + MATERIALIZATION_GRACE_MS);
        }
        scheduleWatchdog();
    }

    @Nullable
    private TerminalSession findBoundSession(@NonNull TermuxSessionRestoreStore.CodexLease lease) {
        if (!TextUtils.isEmpty(lease.handle)) {
            TerminalSession byHandle = mService.getTerminalSessionForHandle(lease.handle);
            if (byHandle != null) return byHandle;
        }

        for (TermuxSession termuxSession : new ArrayList<>(mService.getTermuxSessions())) {
            if (termuxSession == null || !isRestoreCommandForThread(termuxSession.getExecutionCommand(), lease.threadId)) {
                continue;
            }
            TerminalSession terminalSession = termuxSession.getTerminalSession();
            if (terminalSession == null) continue;
            int order = mService.getIndexOfSession(terminalSession);
            TermuxSessionRestoreStore.rebindCodexLease(lease.threadId, safeHandle(terminalSession), order);
            mMaterializationDeadlines.putIfAbsent(
                lease.threadId, SystemClock.elapsedRealtime() + MATERIALIZATION_GRACE_MS);
            return terminalSession;
        }
        return null;
    }

    @Nullable
    private TermuxSessionRestoreStore.CodexLease findLeaseForSession(@NonNull TerminalSession terminalSession) {
        TermuxSessionRestoreStore.CodexLease byHandle =
            TermuxSessionRestoreStore.findCodexLeaseByHandle(safeHandle(terminalSession));
        if (byHandle != null) return byHandle;

        TermuxSession termuxSession = mService.getTermuxSessionForTerminalSession(terminalSession);
        ExecutionCommand command = termuxSession == null ? null : termuxSession.getExecutionCommand();
        for (TermuxSessionRestoreStore.CodexLease lease : TermuxSessionRestoreStore.listCodexLeases()) {
            if (isRestoreCommandForThread(command, lease.threadId)) return lease;
        }
        return null;
    }

    private boolean isRestoreCommandForThread(@Nullable ExecutionCommand command,
                                              @NonNull String threadId) {
        if (command == null || TextUtils.isEmpty(threadId)) return false;
        String executable = normalize(command.executable);
        boolean directCodex = "codex".equals(new File(executable).getName());
        boolean hasResume = false;
        boolean hasThread = false;
        boolean managedScript = false;
        if (command.arguments != null) {
            for (String argument : command.arguments) {
                String value = normalize(argument);
                if ("resume".equals(value) || value.contains(" resume ")) hasResume = true;
                if (TextUtils.equals(threadId, value) || value.contains(threadId)) hasThread = true;
                if (value.contains("codex_cmd=") || value.contains("codexctl_cmd=")) {
                    managedScript = true;
                }
            }
        }
        return hasResume && hasThread && (directCodex || managedScript);
    }

    private void retireBoundSession(@NonNull TerminalSession terminalSession) {
        String handle = safeHandle(terminalSession);
        if (!TextUtils.isEmpty(handle)) mInternallyRetiringHandles.add(handle);
        TermuxSession termuxSession = mService.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession == null) {
            if (terminalSession.isRunning()) terminalSession.finishIfRunning();
            return;
        }
        if (terminalSession.isRunning()) {
            termuxSession.killIfExecuting(mService, true);
        } else {
            mService.removeTermuxSession(terminalSession);
        }
    }

    private void scheduleRestart(@NonNull TermuxSessionRestoreStore.CodexLease lease,
                                 @NonNull String reason) {
        if (mService.wantsToStop() || mRestartTasks.containsKey(lease.threadId) ||
            mSuspendedThreads.contains(lease.threadId)) return;
        int attempt = Math.max(0, mRestartAttempts.getOrDefault(lease.threadId, 0));
        if (shouldSuspendRestart(attempt)) {
            mSuspendedThreads.add(lease.threadId);
            TermuxSessionRestoreStore.appendCodexAudit(
                "runtime_restart_suspended", lease.threadId, lease.handle,
                "reason=" + reason + ";attempts=" + attempt + ";lease retained");
            return;
        }
        long delay = computeRestartDelayMs(attempt);
        Runnable task = () -> {
            mRestartTasks.remove(lease.threadId);
            runRestart(lease.threadId, reason);
        };
        mRestartTasks.put(lease.threadId, task);
        TermuxSessionRestoreStore.appendCodexAudit(
            "runtime_restart_scheduled", lease.threadId, lease.handle,
            "reason=" + reason + ";attempt=" + attempt + ";delay_ms=" + delay);
        mHandler.postDelayed(task, delay);
    }

    private void runRestart(@NonNull String threadId, @NonNull String reason) {
        TermuxSessionRestoreStore.CodexLease lease =
            TermuxSessionRestoreStore.findCodexLeaseByThread(threadId);
        if (lease == null) {
            cancelThread(threadId);
            TermuxSessionRestoreStore.appendCodexAudit(
                "runtime_restart_cancelled", threadId, "", "lease revoked");
            return;
        }
        if (mService.wantsToStop()) {
            TermuxSessionRestoreStore.appendCodexAudit(
                "runtime_restart_deferred", threadId, lease.handle, "service_stopping; lease retained");
            return;
        }

        TerminalSession existing = findBoundSession(lease);
        if (existing != null) {
            int shellPid = existing.getPid();
            Long deadline = mMaterializationDeadlines.get(threadId);
            long now = SystemClock.elapsedRealtime();
            if (isTerminalStartupPending(shellPid)) {
                scheduleWatchdog();
                return;
            }

            if (shellPid <= 0) {
                retireBoundSession(existing);
            } else {
                int codexPid = findVerifiedCodexProcess(lease, existing);
                if (codexPid > 0) {
                    markHealthy(threadId, existing, "restart_deduplicated:pid=" + codexPid);
                    scheduleWatchdog();
                    return;
                }
                if (deadline != null && now < deadline) {
                    scheduleWatchdog();
                    return;
                }
                TermuxSessionRestoreStore.appendCodexAudit(
                    "runtime_process_unverified", threadId, safeHandle(existing),
                    reason + ";restart deferred for running terminal");
                mMaterializationDeadlines.put(
                    threadId, SystemClock.elapsedRealtime() + MATERIALIZATION_GRACE_MS);
                scheduleWatchdog();
                return;
            }
        }

        File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash");
        File codex = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/codex");
        if (!bash.canExecute() || !codex.canExecute()) {
            failAndReschedule(lease, reason + ":executable_unavailable");
            return;
        }
        if (!TextUtils.isEmpty(lease.rolloutPath) &&
            !CodexSessionHostProtocol.rolloutMatchesThread(lease.rolloutPath, lease.threadId)) {
            failAndReschedule(lease, reason + ":rollout_identity_invalid");
            return;
        }

        String workingDirectory = resolveWorkingDirectory(lease.workingDirectory);
        String title = TextUtils.isEmpty(lease.title)
            ? "Codex " + lease.threadId.substring(0, 8)
            : lease.title;
        TermuxSessionRestoreStore.appendCodexAudit(
            "runtime_restart_start", threadId, lease.handle, reason);
        TermuxSession created = mService.createTermuxSession(
            bash.getAbsolutePath(),
            new String[]{"-lc", buildRestoreCommand(
                lease.threadId, workingDirectory, lease.rolloutPath)},
            null,
            workingDirectory,
            false,
            title);
        if (created == null || created.getTerminalSession() == null) {
            failAndReschedule(lease, reason + ":create_failed");
            return;
        }

        TerminalSession terminalSession = created.getTerminalSession();
        terminalSession.mSessionName = title;
        int order = mService.moveTermuxSessionToIndex(terminalSession, lease.order);
        TermuxSessionRestoreStore.UpdateResult rebound =
            TermuxSessionRestoreStore.rebindCodexLease(threadId, safeHandle(terminalSession), order);
        if (rebound == TermuxSessionRestoreStore.UpdateResult.IGNORED) {
            retireBoundSession(terminalSession);
            cancelThread(threadId);
            return;
        }

        int attempt = Math.max(0, mRestartAttempts.getOrDefault(threadId, 0));
        mRestartAttempts.put(threadId, Math.min(attempt + 1, 30));
        mHealthyThreads.remove(threadId);
        mMaterializationDeadlines.put(threadId,
            SystemClock.elapsedRealtime() + MATERIALIZATION_GRACE_MS);
        TermuxSessionRestoreStore.appendCodexAudit(
            "runtime_restart_created", threadId, safeHandle(terminalSession),
            "order=" + order + ";state=" + rebound.name().toLowerCase());

        if (mService.getTermuxTerminalSessionClient() instanceof TermuxTerminalSessionActivityClient) {
            ((TermuxTerminalSessionActivityClient) mService.getTermuxTerminalSessionClient())
                .onManagedCodexSessionRecovered(terminalSession, lease.foreground);
        }
        scheduleWatchdog();
    }

    private void failAndReschedule(@NonNull TermuxSessionRestoreStore.CodexLease lease,
                                   @NonNull String reason) {
        int attempt = Math.max(0, mRestartAttempts.getOrDefault(lease.threadId, 0));
        mRestartAttempts.put(lease.threadId, Math.min(attempt + 1, 30));
        TermuxSessionRestoreStore.appendCodexAudit(
            "runtime_restart_deferred", lease.threadId, lease.handle, reason);
        scheduleRestart(lease, reason);
        scheduleWatchdog();
    }

    private void markHealthy(@NonNull String threadId, @NonNull TerminalSession terminalSession,
                             @NonNull String detail) {
        Runnable task = mRestartTasks.remove(threadId);
        if (task != null) mHandler.removeCallbacks(task);
        mMaterializationDeadlines.remove(threadId);
        long now = SystemClock.elapsedRealtime();
        Long verifiedSince = mVerifiedSince.get(threadId);
        if (verifiedSince == null) {
            verifiedSince = now;
            mVerifiedSince.put(threadId, verifiedSince);
        }
        if (shouldResetRestartAttempts(now - verifiedSince)) {
            boolean hadFailures = mRestartAttempts.remove(threadId) != null;
            boolean wasSuspended = mSuspendedThreads.remove(threadId);
            if (hadFailures || wasSuspended) {
                TermuxSessionRestoreStore.appendCodexAudit(
                    "runtime_restart_stable", threadId, safeHandle(terminalSession),
                    "verified_ms=" + (now - verifiedSince));
            }
        }
        if (mHealthyThreads.add(threadId)) {
            TermuxSessionRestoreStore.appendCodexAudit(
                "runtime_restart_healthy", threadId, safeHandle(terminalSession), detail);
        }
    }

    private int findVerifiedCodexProcess(@NonNull TermuxSessionRestoreStore.CodexLease lease,
                                         @NonNull TerminalSession terminalSession) {
        return CodexProcessIdentity.findLiveCodexProcessForSession(
            terminalSession, lease.processId);
    }

    private void cancelThread(@Nullable String threadId) {
        String normalizedThreadId = normalize(threadId);
        if (TextUtils.isEmpty(normalizedThreadId)) return;
        Runnable task = mRestartTasks.remove(normalizedThreadId);
        if (task != null) mHandler.removeCallbacks(task);
        mRestartAttempts.remove(normalizedThreadId);
        mMaterializationDeadlines.remove(normalizedThreadId);
        mVerifiedSince.remove(normalizedThreadId);
        mHealthyThreads.remove(normalizedThreadId);
        mSuspendedThreads.remove(normalizedThreadId);
    }

    private void scheduleWatchdog() {
        scheduleWatchdog(WATCHDOG_INTERVAL_MS);
    }

    private void scheduleWatchdog(long delayMs) {
        if (!mStarted || mService.wantsToStop()) return;
        mHandler.removeCallbacks(mWatchdog);
        mHandler.postDelayed(mWatchdog, Math.max(0L, delayMs));
    }

    @NonNull
    static String resolveCodexHomeFromRolloutPath(@Nullable String rolloutPath) {
        String normalized = normalize(rolloutPath);
        if (TextUtils.isEmpty(normalized)) return "";

        File cursor;
        try {
            cursor = new File(normalized).getCanonicalFile().getParentFile();
        } catch (IOException e) {
            return "";
        }
        while (cursor != null) {
            if ("sessions".equals(cursor.getName())) {
                File codexHome = cursor.getParentFile();
                return codexHome == null ? "" : codexHome.getAbsolutePath();
            }
            cursor = cursor.getParentFile();
        }
        return "";
    }

    @NonNull
    static String resolveCodexCtlControlHome(@Nullable String codexHomePath) {
        File claudeDirectory = resolveCodexCtlClaudeDirectory(codexHomePath);
        File controlHome = claudeDirectory == null ? null : claudeDirectory.getParentFile();
        return controlHome == null ? "" : controlHome.getAbsolutePath();
    }

    @NonNull
    static String resolveCodexCtlClaudeInstance(@Nullable String codexHomePath) {
        File codexHome = canonicalFile(codexHomePath);
        if (codexHome == null || !"codex".equals(codexHome.getName())) return "";
        File instance = codexHome.getParentFile();
        File instances = instance == null ? null : instance.getParentFile();
        File claude = instances == null ? null : instances.getParentFile();
        if (instance == null || TextUtils.isEmpty(instance.getName()) ||
            instances == null || !"instances".equals(instances.getName()) ||
            claude == null || !"claude".equals(claude.getName())) {
            return "";
        }
        return instance.getName();
    }

    @Nullable
    private static File resolveCodexCtlClaudeDirectory(@Nullable String codexHomePath) {
        File codexHome = canonicalFile(codexHomePath);
        if (codexHome == null || !"codex".equals(codexHome.getName())) return null;
        File instance = codexHome.getParentFile();
        File instances = instance == null ? null : instance.getParentFile();
        File claude = instances == null ? null : instances.getParentFile();
        return instance != null && !TextUtils.isEmpty(instance.getName()) &&
            instances != null && "instances".equals(instances.getName()) &&
            claude != null && "claude".equals(claude.getName())
            ? claude
            : null;
    }

    @Nullable
    private static File canonicalFile(@Nullable String path) {
        String normalized = normalize(path);
        if (TextUtils.isEmpty(normalized)) return null;
        try {
            return new File(normalized).getCanonicalFile();
        } catch (IOException e) {
            return null;
        }
    }

    @NonNull
    static String buildRestoreCommand(@NonNull String threadId,
                                      @NonNull String workingDirectory,
                                      @Nullable String rolloutPath) {
        String codex = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/codex";
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        StringBuilder command = new StringBuilder();
        command.append("cd ").append(quoteArg(workingDirectory))
            .append(" 2>/dev/null || cd ").append(quoteArg(home)).append("; ");

        String normalizedRolloutPath = normalize(rolloutPath);
        if (!TextUtils.isEmpty(normalizedRolloutPath)) {
            String codexHome = resolveCodexHomeFromRolloutPath(normalizedRolloutPath);
            command.append("rollout_path=").append(quoteArg(normalizedRolloutPath)).append("; ")
                .append("if [ ! -f \"$rollout_path\" ]; then ")
                .append("echo 'Codex 恢复记录已丢失，停止错误实例恢复' >&2; exit 66; fi; ");
            if (TextUtils.isEmpty(codexHome)) {
                command.append("echo 'Codex 恢复目录无效，停止错误实例恢复' >&2; exit 66; ");
            } else {
                String controlHome = resolveCodexCtlControlHome(codexHome);
                String claudeInstance = resolveCodexCtlClaudeInstance(codexHome);
                if (!TextUtils.isEmpty(controlHome) && !TextUtils.isEmpty(claudeInstance)) {
                    String codexctl = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/codexctl";
                    command.append("codexctl_cmd=").append(quoteArg(codexctl)).append("; ")
                        .append("if [ ! -x \"$codexctl_cmd\" ]; then ")
                        .append("echo 'Codex 隔离实例控制器缺失，停止不完整恢复' >&2; exit 67; fi; ")
                        .append("exec \"$codexctl_cmd\" --control-home ")
                        .append(quoteArg(controlHome)).append(" --codex-bin ")
                        .append(quoteArg(codex)).append(" claude run ")
                        .append(quoteArg(claudeInstance)).append(" -- resume ")
                        .append(quoteArg(threadId));
                    return command.toString();
                }
                command.append("codex_home=").append(quoteArg(codexHome)).append("; ")
                    .append("if [ ! -d \"$codex_home\" ]; then ")
                    .append("echo 'Codex 实例目录已丢失，停止错误实例恢复' >&2; exit 66; fi; ")
                    .append("export CODEX_HOME=\"$codex_home\"; ");
            }
        }
        command.append("codex_cmd=").append(quoteArg(codex)).append("; ")
            .append("if [ ! -x \"$codex_cmd\" ]; then ")
            .append("echo 'Codex 可执行文件缺失，恢复稍后重试' >&2; exit 127; fi; ")
            .append("exec \"$codex_cmd\" resume ").append(quoteArg(threadId));
        return command.toString();
    }

    @NonNull
    private String resolveWorkingDirectory(@Nullable String raw) {
        String value = normalize(raw);
        if (TextUtils.isEmpty(value) || !new File(value).isDirectory()) {
            value = TermuxConstants.TERMUX_HOME_DIR_PATH;
        }
        return value;
    }

    @NonNull
    private static String quoteArg(@NonNull String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @NonNull
    private static String safeHandle(@Nullable TerminalSession terminalSession) {
        return terminalSession == null ? "" : normalize(terminalSession.mHandle);
    }

    @NonNull
    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private void runOnMain(@NonNull Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mHandler.post(action);
    }
}
