package com.termux.terminalsessionruntime;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;
import com.termux.terminalsessioncore.SshTmuxSessionStateMachine;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class SshTmuxRuntimeEngine {

    public interface RemoteTmuxListCallback {
        void onComplete(@NonNull RemoteTmuxListResult result);
    }

    public interface OperationCallback {
        void onComplete(@NonNull SshTmuxOperationResult result);
    }

    private static final String LOG_TAG = "SshTmuxRuntimeEngine";
    private static final int SSH_BG_MAX_THREADS = 4;
    private static final long SSH_BG_KEEP_ALIVE_SECONDS = 30L;
    private static final int MAX_SESSIONS = 8;
    private static final int SSH_PERSIST_TMUX_PRELOAD_LINES = 50000;
    private static final String SSH_PERSIST_SHELL_NAME_PREFIX = "ssh-persistent-";
    private static final AtomicInteger SSH_BG_THREAD_COUNTER = new AtomicInteger(1);
    private static final ExecutorService SSH_BG_EXECUTOR = createBackgroundExecutor();

    private final SshTmuxRuntimeBridge bridge;
    private final SshTmuxRuntimeStateMachine stateMachine = new SshTmuxRuntimeStateMachine();
    private final SshTmuxCommandFactory commandFactory = new SshTmuxCommandFactory();
    private final SshTmuxPersistenceStore persistenceStore;
    private final AppShellCommandExecutor shellExecutor = new AppShellCommandExecutor();
    private final Map<String, String> sshBootstrapByHandle = new HashMap<>();
    private final AtomicBoolean ensuringPinnedSessions = new AtomicBoolean(false);
    private final AtomicBoolean ensureRetryScheduled = new AtomicBoolean(false);
    private final AtomicBoolean ensurePending = new AtomicBoolean(false);
    private final AtomicBoolean ensurePendingSwitchToAny = new AtomicBoolean(false);

    private static final class RuntimeOperation {
        @NonNull final String id;
        @Nullable String sessionHandle;

        RuntimeOperation(@NonNull String id, @Nullable String sessionHandle) {
            this.id = id;
            this.sessionHandle = sessionHandle;
        }
    }

    public SshTmuxRuntimeEngine(@NonNull SshTmuxRuntimeBridge bridge) {
        this.bridge = bridge;
        this.persistenceStore = new SshTmuxPersistenceStore(bridge.getApplicationContext(), commandFactory);
    }

    @NonNull
    private static ExecutorService createBackgroundExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "termux-runtime-bg-" + SSH_BG_THREAD_COUNTER.getAndIncrement());
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            0, SSH_BG_MAX_THREADS, SSH_BG_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
            new SynchronousQueue<>(), threadFactory);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private void transition(@NonNull SshTmuxRuntimeStateMachine.Phase phase,
                            @Nullable String tmuxSession, @Nullable String displayName,
                            int attempt, @Nullable String detail) {
        transition(null, phase, tmuxSession, displayName, attempt, detail);
    }

    private void transition(@Nullable RuntimeOperation operation,
                            @NonNull SshTmuxRuntimeStateMachine.Phase phase,
                            @Nullable String tmuxSession, @Nullable String displayName,
                            int attempt, @Nullable String detail) {
        bridge.onRuntimeStateChanged(stateMachine.next(
            phase, tmuxSession, displayName, attempt, detail,
            operation == null ? null : operation.id,
            operation == null ? null : operation.sessionHandle
        ));
    }

    @NonNull
    private RuntimeOperation startOperation(@Nullable String sessionHandle) {
        return new RuntimeOperation(UUID.randomUUID().toString(), normalizeSessionHandle(sessionHandle));
    }

    private void bindOperationToSession(@Nullable RuntimeOperation operation, @Nullable String sessionHandle) {
        if (operation == null) return;
        String normalized = normalizeSessionHandle(sessionHandle);
        if (!TextUtils.isEmpty(normalized)) {
            operation.sessionHandle = normalized;
        }
    }

    @Nullable
    private String normalizeSessionHandle(@Nullable String sessionHandle) {
        if (TextUtils.isEmpty(sessionHandle)) return null;
        String normalized = sessionHandle.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void runBackgroundTask(@NonNull String taskName, @NonNull Runnable task) {
        runBackgroundTask(taskName, null, task);
    }

    private void runBackgroundTask(@NonNull String taskName, @Nullable RuntimeOperation operation,
                                   @NonNull Runnable task) {
        Runnable guarded = () -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {
            }
            try {
                task.run();
            } catch (Throwable e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Runtime task failed: " + taskName, e);
                transition(operation, SshTmuxRuntimeStateMachine.Phase.FAILED,
                    null, null, 0, taskName + ": " + e.getMessage());
            }
        };
        try {
            SSH_BG_EXECUTOR.execute(guarded);
        } catch (RejectedExecutionException e) {
            Logger.logWarn(LOG_TAG, "Runtime executor saturated, fallback thread for " + taskName);
            Thread fallback = new Thread(guarded, "termux-runtime-fallback-" + taskName);
            fallback.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            fallback.start();
        }
    }

    public void rememberSshBootstrapCommand(@Nullable TerminalSession session, @Nullable String sshCommand) {
        if (session == null || TextUtils.isEmpty(session.mHandle)) return;
        synchronized (sshBootstrapByHandle) {
            if (TextUtils.isEmpty(sshCommand)) sshBootstrapByHandle.remove(session.mHandle);
            else sshBootstrapByHandle.put(session.mHandle, sshCommand.trim());
        }
    }

    @Nullable
    public String getRememberedSshBootstrapCommand(@Nullable TerminalSession session) {
        if (session == null || TextUtils.isEmpty(session.mHandle)) return null;
        synchronized (sshBootstrapByHandle) {
            return sshBootstrapByHandle.get(session.mHandle);
        }
    }

    public void forgetSshBootstrapCommand(@Nullable TerminalSession session) {
        if (session == null || TextUtils.isEmpty(session.mHandle)) return;
        synchronized (sshBootstrapByHandle) {
            sshBootstrapByHandle.remove(session.mHandle);
        }
    }

    public void onTerminalSessionFinished(@Nullable TerminalSession session) {
        if (session == null) return;
        forgetSshBootstrapCommand(session);
        if (clearLockedHandleForSessionHandle(session.mHandle)) {
            scheduleEnsurePinnedSessionsRetry(false);
        }
    }

    @NonNull
    public ArrayList<SshPersistenceRecord> loadRecords() {
        return persistenceStore.load();
    }

    public void saveRecords(@NonNull ArrayList<SshPersistenceRecord> records) {
        persistenceStore.save(records);
    }

    public boolean renamePinnedSession(@Nullable TerminalSession session, @Nullable String text) {
        if (session == null) return false;
        ArrayList<SshPersistenceRecord> records = loadRecords();
        int index = findRecordIndexForSession(session, records);
        if (index < 0 || index >= records.size()) return false;

        SshPersistenceRecord current = persistenceStore.normalize(records.get(index));
        String displayName = commandFactory.normalizeDisplayName(text, current.displayName);
        SshPersistenceRecord updated = persistenceStore.normalize(new SshPersistenceRecord(
            current.id, current.sshCommand, current.tmuxSession, displayName, current.shellName, current.lockedHandle));
        records.set(index, updated);
        saveRecords(records);
        applyPinnedDisplayName(session, updated.displayName);
        syncPinnedDisplayNameAsync(updated);
        return true;
    }

    public void loadRemoteTmuxSessions(@NonNull String sshCommand, @NonNull RemoteTmuxListCallback callback) {
        transition(SshTmuxRuntimeStateMachine.Phase.LISTING_REMOTE, null, null, 0, null);
        runBackgroundTask("tmux-list-sessions", () -> {
            ShellCommandResult result = shellExecutor.execute(
                bridge.getApplicationContext(), commandFactory.buildTmuxListSessionsCommand(sshCommand));
            String combined = combinedOutput(result);
            boolean tmuxMissing = combined.contains("__TMUX_MISSING__");
            boolean listDone = combined.contains(SshTmuxCommandFactory.TMUX_LIST_DONE);
            ArrayList<RemoteTmuxSessionInfo> sessions = parseTmuxSessionList(combined);
            bridge.runOnUiThread(() -> {
                transition(SshTmuxRuntimeStateMachine.Phase.IDLE, null, null, 0, null);
                callback.onComplete(new RemoteTmuxListResult(tmuxMissing, listDone, sessions, result));
            });
        });
    }

    public void installTmux(@NonNull String sshCommand, @NonNull OperationCallback callback) {
        transition(SshTmuxRuntimeStateMachine.Phase.INSTALLING_REMOTE, null, null, 0, null);
        runBackgroundTask("tmux-install", () -> {
            ShellCommandResult installResult = shellExecutor.execute(
                bridge.getApplicationContext(), commandFactory.buildTmuxInstallCommand(sshCommand));
            ShellCommandResult verifyResult = shellExecutor.execute(
                bridge.getApplicationContext(), commandFactory.buildTmuxCheckCommand(sshCommand));
            boolean hasTmux = verifyResult.stdout.contains("__TMUX_OK__");
            bridge.runOnUiThread(() -> {
                transition(hasTmux ? SshTmuxRuntimeStateMachine.Phase.IDLE : SshTmuxRuntimeStateMachine.Phase.FAILED,
                    null, null, 0, hasTmux ? null : summarize(verifyResult));
                callback.onComplete(new SshTmuxOperationResult(
                    hasTmux ? SshTmuxOperationResult.Code.SUCCESS : SshTmuxOperationResult.Code.FAILED,
                    "", "", null, hasTmux ? verifyResult : installResult));
            });
        });
    }

    public void createRemoteTmuxSessionAndConnect(@Nullable TerminalSession anchorSession,
                                                  @NonNull String sshCommand,
                                                  @Nullable String fallbackDisplayName,
                                                  @Nullable String requestedDisplayName,
                                                  @NonNull OperationCallback callback) {
        if (anchorSession == null) {
            callback.onComplete(new SshTmuxOperationResult(
                SshTmuxOperationResult.Code.FAILED, "", "",
                null, new ShellCommandResult(1, "", "anchor session missing")));
            return;
        }

        SshTmuxSessionStateMachine.Snapshot snapshot =
            SshTmuxSessionStateMachine.planNewManagedSession(
                requestedDisplayName, fallbackDisplayName, sshCommand, anchorSession.mHandle);
        String tmuxSession = snapshot.remoteSessionName;
        String displayName = snapshot.displayName;
        RuntimeOperation operation = startOperation(anchorSession.mHandle);
        transition(operation, SshTmuxRuntimeStateMachine.Phase.CREATING_REMOTE, tmuxSession, displayName, 0, null);
        runBackgroundTask("tmux-create-session", operation, () -> {
            ShellCommandResult createResult = shellExecutor.execute(
                bridge.getApplicationContext(),
                commandFactory.buildTmuxCreateSessionCommand(sshCommand, tmuxSession, displayName));
            String combined = combinedOutput(createResult);
            boolean tmuxMissing = combined.contains("__TMUX_MISSING__");
            boolean created = combined.contains(SshTmuxCommandFactory.TMUX_SESSION_CREATED);
            boolean exists = combined.contains(SshTmuxCommandFactory.TMUX_SESSION_EXISTS);

            bridge.runOnUiThread(() -> {
                if (tmuxMissing) {
                    transition(operation, SshTmuxRuntimeStateMachine.Phase.FAILED,
                        tmuxSession, displayName, 0, "tmux missing");
                    callback.onComplete(new SshTmuxOperationResult(
                        SshTmuxOperationResult.Code.TMUX_MISSING, tmuxSession, displayName, null, createResult));
                    return;
                }

                if (created || exists) {
                    connectToPersistentTmuxSession(operation, anchorSession, sshCommand,
                        tmuxSession, displayName, callback);
                    return;
                }

                transition(operation, SshTmuxRuntimeStateMachine.Phase.FAILED,
                    tmuxSession, displayName, 0, summarize(createResult));
                callback.onComplete(new SshTmuxOperationResult(
                    SshTmuxOperationResult.Code.FAILED, tmuxSession, displayName, null, createResult));
            });
        });
    }

    public void destroyRemoteTmuxSession(@NonNull String sshCommand, @NonNull String tmuxSession,
                                         @Nullable String displayName, @NonNull OperationCallback callback) {
        destroyRemoteTmuxSession(sshCommand, tmuxSession, displayName, null, callback);
    }

    public void destroyRemoteTmuxSession(@NonNull String sshCommand, @NonNull String tmuxSession,
                                         @Nullable String displayName, @Nullable String sessionHandle,
                                         @NonNull OperationCallback callback) {
        String normalizedTmux = commandFactory.normalizeTmuxSessionName(tmuxSession);
        String normalizedDisplay = commandFactory.normalizeDisplayName(displayName, normalizedTmux);
        RuntimeOperation operation = startOperation(sessionHandle);
        transition(operation, SshTmuxRuntimeStateMachine.Phase.DESTROYING_REMOTE,
            normalizedTmux, normalizedDisplay, 0, null);
        runBackgroundTask("tmux-destroy-session", operation, () -> {
            ShellCommandResult destroyResult = shellExecutor.execute(
                bridge.getApplicationContext(),
                commandFactory.buildTmuxKillSessionCommand(sshCommand, normalizedTmux));
            String combined = combinedOutput(destroyResult);
            boolean tmuxMissing = combined.contains("__TMUX_MISSING__");
            boolean destroyed = combined.contains(SshTmuxCommandFactory.TMUX_SESSION_KILLED) ||
                combined.contains(SshTmuxCommandFactory.TMUX_SESSION_NOT_FOUND);
            if (destroyed) cleanupRecordsForRemote(sshCommand, normalizedTmux);

            bridge.runOnUiThread(() -> {
                if (tmuxMissing) {
                    transition(operation, SshTmuxRuntimeStateMachine.Phase.FAILED,
                        normalizedTmux, normalizedDisplay, 0, "tmux missing");
                    callback.onComplete(new SshTmuxOperationResult(
                        SshTmuxOperationResult.Code.TMUX_MISSING, normalizedTmux, normalizedDisplay, null, destroyResult));
                    return;
                }
                transition(operation,
                    destroyed ? SshTmuxRuntimeStateMachine.Phase.IDLE : SshTmuxRuntimeStateMachine.Phase.FAILED,
                    normalizedTmux, normalizedDisplay, 0, destroyed ? null : summarize(destroyResult));
                callback.onComplete(new SshTmuxOperationResult(
                    destroyed ? SshTmuxOperationResult.Code.SUCCESS : SshTmuxOperationResult.Code.FAILED,
                    normalizedTmux, normalizedDisplay, null, destroyResult));
            });
        });
    }

    public void connectToPersistentTmuxSession(@Nullable TerminalSession anchorSession,
                                               @NonNull String sshCommand, @NonNull String tmuxSession,
                                               @Nullable String displayName,
                                               @NonNull OperationCallback callback) {
        connectToPersistentTmuxSession(
            startOperation(anchorSession == null ? null : anchorSession.mHandle),
            anchorSession, sshCommand, tmuxSession, displayName, callback);
    }

    private void connectToPersistentTmuxSession(@Nullable RuntimeOperation operation,
                                                @Nullable TerminalSession anchorSession,
                                                @NonNull String sshCommand, @NonNull String tmuxSession,
                                                @Nullable String displayName,
                                                @NonNull OperationCallback callback) {
        if (anchorSession == null) {
            callback.onComplete(new SshTmuxOperationResult(
                SshTmuxOperationResult.Code.FAILED, "", "", null,
                new ShellCommandResult(1, "", "anchor session missing")));
            return;
        }
        String normalizedTmux = commandFactory.normalizeTmuxSessionName(tmuxSession);
        String normalizedDisplay = commandFactory.normalizeDisplayName(displayName, normalizedTmux);
        transition(operation, SshTmuxRuntimeStateMachine.Phase.CONNECTING_REMOTE,
            normalizedTmux, normalizedDisplay, 0, null);
        transition(operation, SshTmuxRuntimeStateMachine.Phase.ENSURING_LOCAL_BINDING,
            normalizedTmux, normalizedDisplay, 0, null);
        SshPersistenceRecord record = enableSshPersistence(anchorSession, sshCommand, normalizedTmux, normalizedDisplay, false);
        bindOperationToSession(operation, record == null ? null : record.lockedHandle);
        transition(operation,
            record == null ? SshTmuxRuntimeStateMachine.Phase.FAILED : SshTmuxRuntimeStateMachine.Phase.IDLE,
            normalizedTmux, normalizedDisplay, 0, record == null ? "failed to bind local session" : null);
        callback.onComplete(new SshTmuxOperationResult(
            record == null ? SshTmuxOperationResult.Code.FAILED : SshTmuxOperationResult.Code.SUCCESS,
            normalizedTmux, normalizedDisplay, record,
            new ShellCommandResult(record == null ? 1 : 0, "", record == null ? "failed to bind local session" : "")));
    }

    public void prepareSshLock(@NonNull TerminalSession targetSession, @NonNull String sshCommandRaw,
                               boolean attachCurrentSessionToTmux, @NonNull OperationCallback callback) {
        String sshCommand = commandFactory.sanitizeSshBootstrapCommand(sshCommandRaw);
        if (sshCommand.isEmpty()) {
            callback.onComplete(new SshTmuxOperationResult(
                SshTmuxOperationResult.Code.FAILED, "", "", null,
                new ShellCommandResult(1, "", "ssh command required")));
            return;
        }
        SshTmuxSessionStateMachine.Snapshot snapshot = SshTmuxSessionStateMachine.planNewManagedSession(
            null, !TextUtils.isEmpty(targetSession.mSessionName) &&
                !SshTmuxSessionStateMachine.looksLikeOpaqueInternalName(targetSession.mSessionName)
                ? targetSession.mSessionName : targetSession.getTitle(),
            sshCommand, targetSession.mHandle);

        RuntimeOperation operation = startOperation(targetSession.mHandle);
        transition(operation, SshTmuxRuntimeStateMachine.Phase.CHECKING_REMOTE,
            snapshot.remoteSessionName, snapshot.displayName, 0, null);
        runBackgroundTask("tmux-check-before-lock", operation, () -> {
            ShellCommandResult check = shellExecutor.execute(
                bridge.getApplicationContext(), commandFactory.buildTmuxCheckCommand(sshCommand));
            boolean hasTmux = check.stdout.contains("__TMUX_OK__");
            boolean missingTmux = check.stdout.contains("__TMUX_MISSING__");
            bridge.runOnUiThread(() -> {
                if (hasTmux) {
                    transition(operation, SshTmuxRuntimeStateMachine.Phase.ENSURING_LOCAL_BINDING,
                        snapshot.remoteSessionName, snapshot.displayName, 0, null);
                    SshPersistenceRecord record = enableSshPersistence(
                        targetSession, sshCommand, snapshot.remoteSessionName, snapshot.displayName, attachCurrentSessionToTmux);
                    bindOperationToSession(operation, record == null ? null : record.lockedHandle);
                    transition(operation,
                        record == null ? SshTmuxRuntimeStateMachine.Phase.FAILED : SshTmuxRuntimeStateMachine.Phase.IDLE,
                        snapshot.remoteSessionName, snapshot.displayName, 0,
                        record == null ? "failed to enable persistence" : null);
                    callback.onComplete(new SshTmuxOperationResult(
                        record == null ? SshTmuxOperationResult.Code.FAILED : SshTmuxOperationResult.Code.SUCCESS,
                        snapshot.remoteSessionName, snapshot.displayName, record,
                        new ShellCommandResult(record == null ? 1 : 0, "", record == null ? "failed to enable persistence" : "")));
                } else if (missingTmux) {
                    transition(operation, SshTmuxRuntimeStateMachine.Phase.FAILED,
                        snapshot.remoteSessionName, snapshot.displayName, 0, "tmux missing");
                    callback.onComplete(new SshTmuxOperationResult(
                        SshTmuxOperationResult.Code.TMUX_MISSING, snapshot.remoteSessionName, snapshot.displayName,
                        null, check));
                } else {
                    transition(operation, SshTmuxRuntimeStateMachine.Phase.FAILED,
                        snapshot.remoteSessionName, snapshot.displayName, 0, summarize(check));
                    callback.onComplete(new SshTmuxOperationResult(
                        SshTmuxOperationResult.Code.FAILED, snapshot.remoteSessionName, snapshot.displayName,
                        null, check));
                }
            });
        });
    }

    public void installTmuxAndEnable(@NonNull TerminalSession targetSession, @NonNull String sshCommand,
                                     @NonNull String tmuxSession, @NonNull String displayName,
                                     boolean attachCurrentSessionToTmux,
                                     @NonNull OperationCallback callback) {
        RuntimeOperation operation = startOperation(targetSession.mHandle);
        transition(operation, SshTmuxRuntimeStateMachine.Phase.INSTALLING_REMOTE, tmuxSession, displayName, 0, null);
        runBackgroundTask("tmux-install-before-lock", operation, () -> {
            ShellCommandResult installResult = shellExecutor.execute(
                bridge.getApplicationContext(), commandFactory.buildTmuxInstallCommand(sshCommand));
            ShellCommandResult verify = shellExecutor.execute(
                bridge.getApplicationContext(), commandFactory.buildTmuxCheckCommand(sshCommand));
            boolean hasTmux = verify.stdout.contains("__TMUX_OK__");
            bridge.runOnUiThread(() -> {
                if (installResult.isSuccess() && hasTmux) {
                    transition(operation, SshTmuxRuntimeStateMachine.Phase.ENSURING_LOCAL_BINDING,
                        tmuxSession, displayName, 0, null);
                    SshPersistenceRecord record = enableSshPersistence(targetSession, sshCommand, tmuxSession,
                        displayName, attachCurrentSessionToTmux);
                    bindOperationToSession(operation, record == null ? null : record.lockedHandle);
                    transition(operation,
                        record == null ? SshTmuxRuntimeStateMachine.Phase.FAILED : SshTmuxRuntimeStateMachine.Phase.IDLE,
                        tmuxSession, displayName, 0, record == null ? "failed to enable persistence" : null);
                    callback.onComplete(new SshTmuxOperationResult(
                        record == null ? SshTmuxOperationResult.Code.FAILED : SshTmuxOperationResult.Code.SUCCESS,
                        tmuxSession, displayName, record,
                        new ShellCommandResult(record == null ? 1 : 0, "", record == null ? "failed to enable persistence" : "")));
                } else {
                    transition(operation, SshTmuxRuntimeStateMachine.Phase.FAILED,
                        tmuxSession, displayName, 0, summarize(installResult));
                    callback.onComplete(new SshTmuxOperationResult(
                        SshTmuxOperationResult.Code.FAILED, tmuxSession, displayName, null, installResult));
                }
            });
        });
    }

    public void maybeAutoRestorePinnedSshSessions() {
        ensurePinnedSshSessions(bridge.getCurrentSession() == null);
    }

    public boolean ensurePinnedSshSession(boolean switchToSession) {
        return ensurePinnedSshSessions(switchToSession) > 0;
    }

    public int ensurePinnedSshSessions(boolean switchToAny) {
        if (!ensuringPinnedSessions.compareAndSet(false, true)) {
            ensurePending.set(true);
            if (switchToAny) ensurePendingSwitchToAny.set(true);
            return 0;
        }
        try {
            ArrayList<SshPersistenceRecord> records = loadRecords();
            cleanupOrphanedSessions(records);
            collapseDuplicateManagedSessions(records);
            if (records.isEmpty()) return 0;

            ArrayList<SshPersistenceRecord> updated = new ArrayList<>(records.size());
            boolean switched = false;
            int ensured = 0;
            for (SshPersistenceRecord record : records) {
                SshPersistenceRecord ensuredRecord = ensurePinnedSessionRecord(record, switchToAny && !switched);
                if (ensuredRecord == null) continue;
                updated.add(ensuredRecord);
                ensured++;
                if (switchToAny && !switched && !TextUtils.isEmpty(ensuredRecord.lockedHandle)) {
                    TerminalSession maybe = bridge.getTerminalSessionForHandle(ensuredRecord.lockedHandle);
                    if (maybe != null) switched = true;
                }
            }
            saveRecords(updated);
            bridge.onTermuxSessionListUpdated();
            return ensured;
        } finally {
            ensuringPinnedSessions.set(false);
            if (ensurePending.compareAndSet(true, false)) {
                boolean pendingSwitch = ensurePendingSwitchToAny.getAndSet(false);
                scheduleEnsurePinnedSessionsRetry(pendingSwitch);
            }
        }
    }

    public Set<String> getPinnedSessionHandleSnapshot() {
        HashSet<String> pinned = new HashSet<>();
        ArrayList<SshPersistenceRecord> records = loadRecords();
        for (int i = 0; i < bridge.getTermuxSessionsSize(); i++) {
            TermuxSession termuxSession = bridge.getTermuxSession(i);
            if (termuxSession == null) continue;
            TerminalSession terminalSession = termuxSession.getTerminalSession();
            if (terminalSession == null || TextUtils.isEmpty(terminalSession.mHandle)) continue;
            if (findRecordIndexForSession(terminalSession, records) >= 0) {
                pinned.add(terminalSession.mHandle);
            }
        }
        return pinned;
    }

    @Nullable
    public String getPinnedTmuxSessionForSession(@Nullable TerminalSession session) {
        if (session == null) return null;
        ArrayList<SshPersistenceRecord> records = loadRecords();
        int index = findRecordIndexForSession(session, records);
        if (index < 0 || index >= records.size()) return null;
        return commandFactory.normalizeTmuxSessionName(records.get(index).tmuxSession);
    }

    @Nullable
    public String getPinnedDisplayNameForSession(@Nullable TerminalSession session) {
        if (session == null) return null;
        ArrayList<SshPersistenceRecord> records = loadRecords();
        int index = findRecordIndexForSession(session, records);
        if (index < 0 || index >= records.size()) return null;
        return commandFactory.normalizeDisplayName(records.get(index).displayName, records.get(index).tmuxSession);
    }

    @Nullable
    public String extractTmuxSessionFromReconnectLoopScript(@Nullable String script) {
        if (TextUtils.isEmpty(script)) return null;
        String marker = "tmux attach-session -t ";
        int start = script.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = script.indexOf(";", start);
        if (end < 0) end = script.indexOf("\n", start);
        if (end < 0) end = script.length();
        if (start >= end) return null;
        return commandFactory.unquoteShellToken(script.substring(start, end).trim());
    }

    @Nullable
    public String inferTmuxSessionFromExecutionCommand(@Nullable ExecutionCommand executionCommand) {
        if (executionCommand == null) return null;
        String script = extractShellScriptFromExecutionArgs(executionCommand.arguments);
        return extractTmuxSessionFromReconnectLoopScript(script);
    }

    private SshPersistenceRecord enableSshPersistence(@NonNull TerminalSession targetSession, @NonNull String sshCommand,
                                                      @NonNull String tmuxSession, @NonNull String displayName,
                                                      boolean attachCurrentSessionToTmux) {
        sshCommand = commandFactory.sanitizeSshBootstrapCommand(sshCommand);
        String safeTmuxSession = commandFactory.normalizeTmuxSessionName(tmuxSession);
        String normalizedDisplayName = commandFactory.normalizeDisplayName(displayName, safeTmuxSession);
        boolean lockCurrentSession = attachCurrentSessionToTmux && targetSession.isRunning();
        if (lockCurrentSession) {
            targetSession.write(commandFactory.buildTmuxEnsureAndAttachCommand(
                safeTmuxSession, normalizedDisplayName, SSH_PERSIST_TMUX_PRELOAD_LINES) + "\r");
        }

        ArrayList<SshPersistenceRecord> records = loadRecords();
        int existingIndex = lockCurrentSession
            ? findRecordIndexForSession(targetSession, records)
            : persistenceStore.findByRemote(records, sshCommand, safeTmuxSession);
        String recordId = existingIndex >= 0 ? records.get(existingIndex).id : UUID.randomUUID().toString();
        String shellName = existingIndex >= 0 ? records.get(existingIndex).shellName : persistenceStore.buildShellName(recordId);
        String lockedHandle = lockCurrentSession ? targetSession.mHandle :
            (existingIndex >= 0 ? records.get(existingIndex).lockedHandle : null);

        SshPersistenceRecord record = persistenceStore.normalize(new SshPersistenceRecord(
            recordId, sshCommand, safeTmuxSession, normalizedDisplayName, shellName, lockedHandle));
        upsertRecord(record);
        if (lockCurrentSession) {
            rememberSshBootstrapCommand(targetSession, sshCommand);
            applyPinnedDisplayName(targetSession, normalizedDisplayName);
        }

        if (!lockCurrentSession) {
            SshPersistenceRecord ensured = ensurePinnedSessionRecord(record, true);
            if (ensured == null || TextUtils.isEmpty(ensured.lockedHandle)) {
                removeRecordById(record.id);
                bridge.onTermuxSessionListUpdated();
                return null;
            }
            upsertRecord(ensured);
            record = ensured;
        }

        cleanupOrphanedSessions(loadRecords());
        bridge.onTermuxSessionListUpdated();
        return record;
    }

    private void applyPinnedDisplayName(@Nullable TerminalSession session, @Nullable String displayName) {
        if (session == null) return;
        session.mSessionName = commandFactory.normalizeDisplayName(displayName, session.getTitle());
    }

    private void syncPinnedDisplayNameAsync(@NonNull SshPersistenceRecord record) {
        RuntimeOperation operation = startOperation(record.lockedHandle);
        transition(operation, SshTmuxRuntimeStateMachine.Phase.SYNCING_DISPLAY_NAME,
            record.tmuxSession, record.displayName, 0, null);
        runBackgroundTask("tmux-sync-display-name", operation, () -> {
            shellExecutor.execute(bridge.getApplicationContext(),
                commandFactory.buildTmuxDisplaySyncRemoteExecCommand(record.sshCommand, record.tmuxSession, record.displayName));
            bridge.runOnUiThread(() -> transition(operation, SshTmuxRuntimeStateMachine.Phase.IDLE,
                record.tmuxSession, record.displayName, 0, null));
        });
    }

    private void upsertRecord(@NonNull SshPersistenceRecord record) {
        ArrayList<SshPersistenceRecord> records = loadRecords();
        SshPersistenceRecord normalized = persistenceStore.normalize(record);
        for (int i = records.size() - 1; i >= 0; i--) {
            if (normalized.id.equals(records.get(i).id)) records.remove(i);
        }
        records.add(normalized);
        saveRecords(records);
    }

    private boolean removeRecordById(@NonNull String id) {
        ArrayList<SshPersistenceRecord> records = loadRecords();
        boolean removed = false;
        for (int i = records.size() - 1; i >= 0; i--) {
            if (id.equals(records.get(i).id)) {
                records.remove(i);
                removed = true;
            }
        }
        if (removed) saveRecords(records);
        return removed;
    }

    @Nullable
    private SshPersistenceRecord removeRecordForSession(@Nullable TerminalSession session) {
        if (session == null) return null;
        ArrayList<SshPersistenceRecord> records = loadRecords();
        int index = findRecordIndexForSession(session, records);
        if (index < 0 || index >= records.size()) return null;
        SshPersistenceRecord removed = persistenceStore.normalize(records.remove(index));
        saveRecords(records);
        return removed;
    }

    public boolean disableSshPersistenceForSession(@Nullable TerminalSession session) {
        return removeRecordForSession(session) != null;
    }

    private boolean clearLockedHandleForSessionHandle(@Nullable String handle) {
        if (TextUtils.isEmpty(handle)) return false;
        ArrayList<SshPersistenceRecord> records = loadRecords();
        boolean changed = false;
        for (int i = 0; i < records.size(); i++) {
            SshPersistenceRecord record = records.get(i);
            if (!TextUtils.equals(handle, record.lockedHandle)) continue;
            records.set(i, new SshPersistenceRecord(
                record.id, record.sshCommand, record.tmuxSession, record.displayName, record.shellName, null));
            changed = true;
        }
        if (changed) saveRecords(records);
        return changed;
    }

    private void cleanupRecordsForRemote(@NonNull String sshCommand, @NonNull String tmuxSession) {
        String normalizedSshCommand = commandFactory.sanitizeSshBootstrapCommand(sshCommand);
        String safeTmuxSession = commandFactory.normalizeTmuxSessionName(tmuxSession);
        ArrayList<SshPersistenceRecord> records = loadRecords();
        boolean changed = false;
        for (int i = records.size() - 1; i >= 0; i--) {
            SshPersistenceRecord record = persistenceStore.normalize(records.get(i));
            if (safeTmuxSession.equals(record.tmuxSession) && normalizedSshCommand.equals(record.sshCommand)) {
                records.remove(i);
                changed = true;
            }
        }
        if (changed) saveRecords(records);
    }

    @Nullable
    private SshPersistenceRecord ensurePinnedSessionRecord(@NonNull SshPersistenceRecord record, boolean switchToSession) {
        SshPersistenceRecord normalized = persistenceStore.normalize(record);
        if (TextUtils.isEmpty(normalized.sshCommand)) return null;
        String safeTmuxSession = commandFactory.normalizeTmuxSessionName(normalized.tmuxSession);

        if (!TextUtils.isEmpty(normalized.lockedHandle)) {
            TerminalSession existingByHandle = bridge.getTerminalSessionForHandle(normalized.lockedHandle);
            if (existingByHandle != null) {
                TermuxSession existingTermux = bridge.getTermuxSessionForTerminalSession(existingByHandle);
                if (shouldRecreateStalePinnedReconnectSession(existingTermux, safeTmuxSession)) {
                    bridge.removeTermuxSession(existingByHandle);
                    scheduleEnsurePinnedSessionsRetry(switchToSession);
                    return new SshPersistenceRecord(normalized.id, normalized.sshCommand,
                        normalized.tmuxSession, normalized.displayName, normalized.shellName, null);
                } else {
                    rememberSshBootstrapCommand(existingByHandle, normalized.sshCommand);
                    applyPinnedDisplayName(existingByHandle, normalized.displayName);
                    if (switchToSession) bridge.setCurrentSession(existingByHandle);
                    return normalized;
                }
            }
        }

        TermuxSession existing = bridge.getTermuxSessionForShellName(normalized.shellName);
        if (existing != null && existing.getTerminalSession() != null) {
            TerminalSession existingSession = existing.getTerminalSession();
            if (shouldRecreateStalePinnedReconnectSession(existing, safeTmuxSession)) {
                bridge.removeTermuxSession(existingSession);
                scheduleEnsurePinnedSessionsRetry(switchToSession);
                return new SshPersistenceRecord(normalized.id, normalized.sshCommand,
                    normalized.tmuxSession, normalized.displayName, normalized.shellName, null);
            } else {
                rememberSshBootstrapCommand(existingSession, normalized.sshCommand);
                applyPinnedDisplayName(existingSession, normalized.displayName);
                if (switchToSession) bridge.setCurrentSession(existingSession);
                return new SshPersistenceRecord(normalized.id, normalized.sshCommand,
                    normalized.tmuxSession, normalized.displayName, normalized.shellName, existingSession.mHandle);
            }
        }

        if (bridge.getTermuxSessionsSize() >= MAX_SESSIONS) return normalized;

        String reconnectLoopScript = commandFactory.buildReconnectLoopCommand(
            normalized.sshCommand, normalized.tmuxSession, normalized.displayName, SSH_PERSIST_TMUX_PRELOAD_LINES);
        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bash).exists()) return normalized;

        TermuxSession created = bridge.createTermuxSession(
            bash, new String[]{"-lc", reconnectLoopScript}, null,
            bridge.getDefaultWorkingDirectory(), false, normalized.shellName);
        if (created == null || created.getTerminalSession() == null) return normalized;

        TerminalSession createdSession = created.getTerminalSession();
        rememberSshBootstrapCommand(createdSession, normalized.sshCommand);
        applyPinnedDisplayName(createdSession, normalized.displayName);
        if (switchToSession) bridge.setCurrentSession(createdSession);
        return new SshPersistenceRecord(normalized.id, normalized.sshCommand,
            normalized.tmuxSession, normalized.displayName, normalized.shellName, createdSession.mHandle);
    }

    private void cleanupOrphanedSessions(@NonNull ArrayList<SshPersistenceRecord> records) {
        HashSet<String> managedShellNames = new HashSet<>();
        for (SshPersistenceRecord record : records) {
            if (!TextUtils.isEmpty(record.shellName)) managedShellNames.add(record.shellName);
        }
        ArrayList<TermuxSession> snapshot = bridge.getTermuxSessionsSnapshot();
        for (TermuxSession termuxSession : snapshot) {
            if (termuxSession == null || termuxSession.getExecutionCommand() == null) continue;
            String shellName = termuxSession.getExecutionCommand().shellName;
            if (TextUtils.isEmpty(shellName) || !shellName.startsWith(SSH_PERSIST_SHELL_NAME_PREFIX)) continue;
            if (managedShellNames.contains(shellName)) continue;
            TerminalSession orphan = termuxSession.getTerminalSession();
            if (orphan != null) bridge.removeTermuxSession(orphan);
        }
    }

    private void collapseDuplicateManagedSessions(@NonNull ArrayList<SshPersistenceRecord> records) {
        if (records.isEmpty()) return;
        HashMap<String, String> tmuxByShellName = new HashMap<>();
        HashSet<String> managedShellNames = new HashSet<>();
        for (SshPersistenceRecord record : records) {
            if (TextUtils.isEmpty(record.shellName)) continue;
            managedShellNames.add(record.shellName);
            tmuxByShellName.put(record.shellName, commandFactory.normalizeTmuxSessionName(record.tmuxSession));
        }
        if (managedShellNames.isEmpty()) return;

        HashMap<String, ArrayList<TermuxSession>> sessionsByShellName = new HashMap<>();
        for (TermuxSession termuxSession : bridge.getTermuxSessionsSnapshot()) {
            if (termuxSession == null || termuxSession.getExecutionCommand() == null) continue;
            String shellName = termuxSession.getExecutionCommand().shellName;
            if (TextUtils.isEmpty(shellName) || !managedShellNames.contains(shellName)) continue;
            ArrayList<TermuxSession> group = sessionsByShellName.get(shellName);
            if (group == null) {
                group = new ArrayList<>();
                sessionsByShellName.put(shellName, group);
            }
            group.add(termuxSession);
        }

        for (Map.Entry<String, ArrayList<TermuxSession>> entry : sessionsByShellName.entrySet()) {
            ArrayList<TermuxSession> group = entry.getValue();
            if (group == null || group.size() < 2) continue;
            String targetTmux = tmuxByShellName.get(entry.getKey());
            TermuxSession keeper = selectBestSessionForTmux(group, targetTmux);
            for (TermuxSession candidate : group) {
                if (candidate == keeper || candidate == null) continue;
                TerminalSession terminal = candidate.getTerminalSession();
                if (terminal != null) bridge.removeTermuxSession(terminal);
            }
        }
    }

    @Nullable
    private TermuxSession selectBestSessionForTmux(@NonNull ArrayList<TermuxSession> candidates, @Nullable String safeTmuxSession) {
        TermuxSession best = null;
        int bestScore = Integer.MIN_VALUE;
        for (TermuxSession candidate : candidates) {
            if (candidate == null || candidate.getExecutionCommand() == null) continue;
            int score = 0;
            TerminalSession terminalSession = candidate.getTerminalSession();
            if (terminalSession != null && terminalSession.isRunning()) score += 4;
            String script = extractShellScriptFromExecutionArgs(candidate.getExecutionCommand().arguments);
            if (!TextUtils.isEmpty(script)) {
                if (safeTmuxSession != null &&
                    script.contains("tmux attach-session -t " + commandFactory.buildTmuxTargetArg(safeTmuxSession))) {
                    score += 3;
                }
                if (script.contains("[ssh-persist]")) score += 1;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best != null ? best : (candidates.isEmpty() ? null : candidates.get(candidates.size() - 1));
    }

    private void scheduleEnsurePinnedSessionsRetry(boolean switchToAny) {
        if (!ensureRetryScheduled.compareAndSet(false, true)) return;
        transition(SshTmuxRuntimeStateMachine.Phase.RETRY_SCHEDULED, null, null, 0, switchToAny ? "switch" : null);
        bridge.postDelayedOnUi(() -> {
            ensureRetryScheduled.set(false);
            ensurePinnedSshSessions(switchToAny);
        }, 260);
    }

    private boolean shouldRecreateStalePinnedReconnectSession(@Nullable TermuxSession termuxSession,
                                                              @NonNull String safeTmuxSession) {
        if (termuxSession == null || termuxSession.getExecutionCommand() == null) return false;
        String loopScript = extractShellScriptFromExecutionArgs(termuxSession.getExecutionCommand().arguments);
        if (TextUtils.isEmpty(loopScript)) return false;
        if (!loopScript.contains("while true; do") || !loopScript.contains("[ssh-persist]")) return false;

        String target = commandFactory.buildTmuxTargetArg(safeTmuxSession);
        if (!loopScript.contains("tmux attach-session -t " + target)) return true;
        if (countMatches(loopScript, "while true; do") > 1) return true;
        if (loopScript.contains("tmux has-session -t " + target + " 2>/dev/null || tmux new-session -d -s " + target +
            "; tmux set-option -t " + target)) return true;
        return loopScript.contains("capture-pane -p -t \"\"") ||
            loopScript.contains("pane=;") ||
            loopScript.contains("; ; tmux") ||
            loopScript.contains("set-option -t " + target + " mouse off");
    }

    private int findRecordIndexForSession(@Nullable TerminalSession session, @NonNull ArrayList<SshPersistenceRecord> records) {
        if (session == null || records.isEmpty()) return -1;
        String handle = session.mHandle;
        if (!TextUtils.isEmpty(handle)) {
            for (int i = 0; i < records.size(); i++) {
                SshPersistenceRecord record = records.get(i);
                if (!TextUtils.isEmpty(record.lockedHandle) && handle.equals(record.lockedHandle)) return i;
            }
        }

        TermuxSession ts = bridge.getTermuxSessionForTerminalSession(session);
        if (ts == null || ts.getExecutionCommand() == null) return -1;
        String shellName = ts.getExecutionCommand().shellName;
        if (TextUtils.isEmpty(shellName)) return -1;

        ArrayList<Integer> shellMatches = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            if (shellName.equals(records.get(i).shellName)) shellMatches.add(i);
        }
        if (shellMatches.isEmpty()) return -1;
        if (shellMatches.size() == 1) return shellMatches.get(0);

        String bootstrapByHandle = getRememberedSshBootstrapCommand(session);
        if (!TextUtils.isEmpty(bootstrapByHandle)) {
            String normalizedBootstrap = commandFactory.sanitizeSshBootstrapCommand(bootstrapByHandle);
            for (Integer idx : shellMatches) {
                if (idx == null || idx < 0 || idx >= records.size()) continue;
                if (normalizedBootstrap.equals(records.get(idx).sshCommand)) return idx;
            }
        }

        String tmuxByScript = inferTmuxSessionFromExecutionCommand(ts.getExecutionCommand());
        if (!TextUtils.isEmpty(tmuxByScript)) {
            for (Integer idx : shellMatches) {
                if (idx == null || idx < 0 || idx >= records.size()) continue;
                if (tmuxByScript.equals(records.get(idx).tmuxSession)) return idx;
            }
        }

        Logger.logWarn(LOG_TAG, "Ambiguous persistence record mapping for shellName=" + shellName +
            ", matches=" + shellMatches.size());
        return shellMatches.get(shellMatches.size() - 1);
    }

    @NonNull
    private ArrayList<RemoteTmuxSessionInfo> parseTmuxSessionList(@Nullable String output) {
        ArrayList<RemoteTmuxSessionInfo> sessions = new ArrayList<>();
        if (TextUtils.isEmpty(output)) return sessions;
        HashSet<String> seen = new HashSet<>();
        String normalized = output.replace("\r", "\n");
        String[] lines = normalized.split("\n");
        for (String line : lines) {
            if (TextUtils.isEmpty(line)) continue;
            String trimmed = line.trim();
            int marker = trimmed.indexOf(SshTmuxCommandFactory.TMUX_LIST_ITEM_PREFIX);
            if (marker < 0) continue;
            String payload = trimmed.substring(marker + SshTmuxCommandFactory.TMUX_LIST_ITEM_PREFIX.length());
            String[] parts = payload.split("\\|", -1);
            if (parts.length < 3) continue;

            String sessionName = commandFactory.normalizeTmuxSessionName(parts[0]);
            if (TextUtils.isEmpty(sessionName) || seen.contains(sessionName)) continue;
            String encodedDisplayName = parts.length > 3 ? parts[3] : "";
            SshTmuxSessionStateMachine.Snapshot snapshot = SshTmuxSessionStateMachine.resolveExistingRemote(
                sessionName, encodedDisplayName, null, null, sessionName);
            int windows = parsePositiveInt(parts[1], 1);
            boolean attached = "1".equals(parts[2].trim());
            sessions.add(new RemoteTmuxSessionInfo(snapshot.remoteSessionName, snapshot.displayName, windows, attached));
            seen.add(sessionName);
        }
        return sessions;
    }

    private int parsePositiveInt(@Nullable String raw, int fallback) {
        if (TextUtils.isEmpty(raw)) return fallback;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Nullable
    private String extractShellScriptFromExecutionArgs(@Nullable String[] args) {
        if (args == null || args.length == 0) return null;
        for (int i = 0; i < args.length - 1; i++) {
            if ("-lc".equals(args[i]) || "-c".equals(args[i])) return args[i + 1];
        }
        return args[args.length - 1];
    }

    private int countMatches(@Nullable String text, @NonNull String token) {
        if (TextUtils.isEmpty(text) || token.isEmpty()) return 0;
        int count = 0;
        int index = 0;
        while (true) {
            index = text.indexOf(token, index);
            if (index < 0) break;
            count++;
            index += token.length();
        }
        return count;
    }

    @NonNull
    private String combinedOutput(@NonNull ShellCommandResult result) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(result.stdout)) sb.append(result.stdout.trim());
        if (!TextUtils.isEmpty(result.stderr)) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(result.stderr.trim());
        }
        return sb.toString().trim();
    }

    @NonNull
    private String summarize(@NonNull ShellCommandResult result) {
        String combined = combinedOutput(result);
        if (TextUtils.isEmpty(combined)) return "exit " + result.exitCode;
        return combined.length() <= 160 ? combined : combined.substring(0, 160) + "\n...(已截断)";
    }
}
