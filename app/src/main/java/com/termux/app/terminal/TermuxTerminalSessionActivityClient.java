package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.topbar.TerminalTopBarRuntimeState;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.TermuxConstants;
import com.termux.app.TermuxService;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.terminal.io.BellHandler;
import com.termux.shared.logger.Logger;
import com.termux.sessionsync.SavedSshProfileStore;
import com.termux.sessionsync.SessionEntry;
import com.termux.sessionsync.SessionFileCoordinator;
import com.termux.sessionsync.SftpProtocolManager;
import com.termux.sshconnectioncore.LegacySshCommandProfileResolver;
import com.termux.sshconnectioncore.SshPendingTrustRecord;
import com.termux.sshconnectioncore.ResolvedSshEndpoint;
import com.termux.sshconnectioncore.SshCommandKnownHostsOptions;
import com.termux.sshconnectioncore.SshKnownHostsFiles;
import com.termux.sshconnectioncore.SshProfileResolutionResult;
import com.termux.sshconnectioncore.SshTrustRecord;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TextStyle;
import com.termux.terminalsessioncore.CodexRestoreStateMachine;
import com.termux.terminalsessioncore.SshTmuxSessionStateMachine;
import com.termux.terminalsessionruntime.RemoteTmuxListResult;
import com.termux.terminalsessionruntime.SshTmuxOperationResult;
import com.termux.terminalsessionruntime.SshTmuxRuntimeBridge;
import com.termux.terminalsessionruntime.SshTmuxRuntimeEngine;
import com.termux.terminalsessionruntime.SshTmuxRuntimeStateMachine;
import com.termux.view.TerminalView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Properties;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/** The {@link TerminalSessionClient} implementation that may require an {@link Activity} for its interface methods. */
public class TermuxTerminalSessionActivityClient extends TermuxTerminalSessionClientBase {

    private final TermuxActivity mActivity;
    private final SshTmuxRuntimeEngine mSshTmuxRuntimeEngine;
    @Nullable
    private volatile SshTmuxRuntimeStateMachine.Snapshot mLastSshTmuxRuntimeSnapshot;
    private final Object mRuntimeStateLock = new Object();
    private final Map<String, SshTmuxRuntimeStateMachine.Snapshot> mRuntimeSnapshotBySessionHandle = new HashMap<>();
    private final Map<String, String> mRuntimeSessionHandleByOperationId = new HashMap<>();

    private static final int MAX_SESSIONS = 8;
    private static final String SSH_PERSIST_PREFS = "ssh_persistence_prefs";
    private static final String KEY_SSH_PERSIST_ENABLED = "ssh_persist.enabled";
    private static final String KEY_SSH_COMMAND = "ssh_persist.command";
    private static final String KEY_SSH_TMUX_SESSION = "ssh_persist.tmux_session";
    private static final String KEY_SSH_SHELL_NAME = "ssh_persist.shell_name";
    private static final String KEY_SSH_LOCKED_HANDLE = "ssh_persist.locked_handle";
    private static final String KEY_SSH_PERSIST_RECORDS_JSON = "ssh_persist.records_json";
    private static final String KEY_SSH_PROFILES_JSON = "ssh_profiles.json";
    private static final String SSHPASS_CHECK_COMMAND = "command -v sshpass >/dev/null 2>&1";
    private static final String SSHPASS_INSTALL_COMMAND = "pkg install -y sshpass";
    private static final String DEFAULT_SSH_TMUX_SESSION = "termux";
    private static final String DEFAULT_SSH_SHELL_NAME = "ssh-persistent";
    private static final String SSH_PERSIST_SHELL_NAME_PREFIX = "ssh-persistent-";
    private static final int SSH_PERSIST_TMUX_PRELOAD_LINES = 50000;
    private static final String TMUX_LIST_ITEM_PREFIX = "__TMUX_ITEM__|";
    private static final String TMUX_LIST_DONE = "__TMUX_LIST_DONE__";
    private static final String TMUX_SESSION_CREATED = "__TMUX_CREATED__";
    private static final String TMUX_SESSION_KILLED = "__TMUX_KILLED__";
    private static final String TMUX_SESSION_EXISTS = "__TMUX_EXISTS__";
    private static final String TMUX_SESSION_NOT_FOUND = "__TMUX_NOT_FOUND__";
    private static final Pattern PS_LINE_PATTERN = Pattern.compile("^\\s*(\\d+)\\s+(\\d+)\\s+(.+)$");
    private static final String CODEX_RESTORE_DEFAULT_DISPLAY_NAME = "Codex";
    private static final int TERMUX_RESTORE_VERSION = TermuxSessionRestoreStore.SCHEMA_VERSION;
    private static final String TERMUX_RESTORE_TYPE_CODEX = "codex";
    private static final String TERMUX_RESTORE_TYPE_SSH_TMUX = "ssh_tmux";
    private static final String TERMUX_RESTORE_TYPE_LOCAL_TMUX = "local_tmux";
    private static final String TERMUX_RESTORE_TYPE_SSH = "ssh";
    private static final String TERMUX_RESTORE_TYPE_PROOT = "proot";
    private static final String TERMUX_RESTORE_TYPE_SHELL = "shell";

    private SoundPool mBellSoundPool;

    private int mBellSoundId;

    private static final String LOG_TAG = "TermuxTerminalSessionActivityClient";
    private static final int SSH_BG_MAX_THREADS = 4;
    private static final long SSH_BG_KEEP_ALIVE_SECONDS = 30L;
    private static final long TERMINAL_TITLE_SETTLE_DELAY_MS = 1250L;
    private static final long SESSION_SELECTION_PERSIST_IDLE_DELAY_MS = 180L;
    private static final AtomicInteger SSH_BG_THREAD_COUNTER = new AtomicInteger(1);
    private static final ExecutorService SSH_BG_EXECUTOR = createSshBackgroundExecutor();
    private final AtomicBoolean mEnsuringPinnedSshSessions = new AtomicBoolean(false);
    private final AtomicBoolean mEnsurePinnedSshSessionsRetryScheduled = new AtomicBoolean(false);
    private final AtomicBoolean mEnsurePinnedSshSessionsPending = new AtomicBoolean(false);
    private final AtomicBoolean mEnsurePinnedSshSessionsPendingSwitchToAny = new AtomicBoolean(false);
    private final Object mNativeCodexRestoreLock = new Object();
    private final Map<String, CodexRestoreRecord> mNativeCodexRestoreByThread = new HashMap<>();
    private final Map<String, String> mNativeCodexRestoreThreadByHandle = new HashMap<>();
    private final HashSet<String> mNativeCodexRestoreSuppressedHandles = new HashSet<>();
    private final Handler mTerminalTitleHandler = new Handler(Looper.getMainLooper());
    private final Map<TerminalSession, Runnable> mPendingTerminalTitleUpdates = new HashMap<>();
    private final Handler mSessionSelectionPersistenceHandler = new Handler(Looper.getMainLooper());
    @Nullable private TerminalSession mPendingSessionSelectionPersistence;
    @NonNull
    private final Runnable mPersistPendingSessionSelection = () -> {
        TerminalSession pending = mPendingSessionSelectionPersistence;
        mPendingSessionSelectionPersistence = null;
        if (pending != null) persistTermuxSessionRestoreState(pending);
    };
    private final Object mSshPersistRecordsLock = new Object();
    private boolean mRestoringTermuxSessions = false;
    @Nullable
    private String mLastExplicitSelectedSessionHandle;
    @Nullable
    private String mLastTermuxRestoreStateSignature;
    @Nullable
    private String mLastTermuxRestoreStateJson;
    @Nullable
    private ArrayList<SshPersistenceRecord> mSshPersistRecordsCache;

    public TermuxTerminalSessionActivityClient(TermuxActivity activity) {
        this.mActivity = activity;
        this.mSshTmuxRuntimeEngine = new SshTmuxRuntimeEngine(new SshTmuxRuntimeBridge() {
            @NonNull
            @Override
            public Context getApplicationContext() {
                return mActivity.getApplicationContext();
            }

            @Nullable
            @Override
            public TerminalSession getCurrentSession() {
                return mActivity.getCurrentSession();
            }

            @Override
            public void setCurrentSession(@Nullable TerminalSession session) {
                TermuxTerminalSessionActivityClient.this.setCurrentSession(session);
            }

            @NonNull
            @Override
            public String getDefaultWorkingDirectory() {
                return mActivity.getProperties().getDefaultWorkingDirectory();
            }

            @Override
            public void onTermuxSessionListUpdated() {
                termuxSessionListNotifyUpdated();
            }

            @Override
            public void runOnUiThread(@NonNull Runnable runnable) {
                mActivity.runOnUiThread(runnable);
            }

            @Override
            public void postDelayedOnUi(@NonNull Runnable runnable, long delayMs) {
                View anchor = mActivity.getTerminalView();
                if (anchor != null) anchor.postDelayed(runnable, delayMs);
                else mActivity.runOnUiThread(runnable);
            }

            @Nullable
            @Override
            public TermuxSession getTermuxSession(int index) {
                TermuxService service = mActivity.getTermuxService();
                return service == null ? null : service.getTermuxSession(index);
            }

            @Override
            public int getTermuxSessionsSize() {
                TermuxService service = mActivity.getTermuxService();
                return service == null ? 0 : service.getTermuxSessionsSize();
            }

            @NonNull
            @Override
            public ArrayList<TermuxSession> getTermuxSessionsSnapshot() {
                TermuxService service = mActivity.getTermuxService();
                return service == null ? new ArrayList<>() : new ArrayList<>(service.getTermuxSessions());
            }

            @Nullable
            @Override
            public TermuxSession getTermuxSessionForTerminalSession(@Nullable TerminalSession session) {
                TermuxService service = mActivity.getTermuxService();
                return service == null ? null : service.getTermuxSessionForTerminalSession(session);
            }

            @Nullable
            @Override
            public TermuxSession getTermuxSessionForShellName(@Nullable String shellName) {
                TermuxService service = mActivity.getTermuxService();
                return service == null ? null : service.getTermuxSessionForShellName(shellName);
            }

            @Nullable
            @Override
            public TerminalSession getTerminalSessionForHandle(@Nullable String handle) {
                TermuxService service = mActivity.getTermuxService();
                return service == null ? null : service.getTerminalSessionForHandle(handle);
            }

            @Nullable
            @Override
            public TermuxSession createTermuxSession(@Nullable String executablePath, @Nullable String[] arguments,
                                                     @Nullable String stdin, @NonNull String workingDirectory,
                                                     boolean isFailSafe, @Nullable String sessionName) {
                TermuxService service = mActivity.getTermuxService();
                return service == null ? null :
                    service.createTermuxSession(executablePath, arguments, stdin, workingDirectory, isFailSafe, sessionName);
            }

            @Override
            public void removeTermuxSession(@NonNull TerminalSession session) {
                TermuxService service = mActivity.getTermuxService();
                if (service != null) service.removeTermuxSession(session);
            }

            @Override
            public void onRuntimeStateChanged(@NonNull SshTmuxRuntimeStateMachine.Snapshot snapshot) {
                trackRuntimeSnapshot(snapshot);
                if (snapshot.phase == SshTmuxRuntimeStateMachine.Phase.FAILED) {
                    Logger.logWarn(LOG_TAG, "SSH/tmux runtime failed: " + snapshot.detail);
                }
                mActivity.runOnUiThread(TermuxTerminalSessionActivityClient.this::termuxSessionListNotifyUpdated);
            }
        });
    }

    @NonNull
    private static ExecutorService createSshBackgroundExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable,
                "termux-ssh-bg-" + SSH_BG_THREAD_COUNTER.getAndIncrement());
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            0,
            SSH_BG_MAX_THREADS,
            SSH_BG_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            threadFactory
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private void runSshBackgroundTask(@NonNull String taskName, @NonNull Runnable task) {
        Runnable guardedTask = () -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {
            }

            try {
                task.run();
            } catch (Throwable e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "SSH background task failed: " + taskName, e);
            }
        };

        try {
            SSH_BG_EXECUTOR.execute(guardedTask);
        } catch (RejectedExecutionException e) {
            Logger.logWarn(LOG_TAG, "SSH background executor saturated, using fallback thread for " + taskName);
            Thread fallback = new Thread(guardedTask, "termux-ssh-bg-fallback-" + taskName);
            fallback.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            fallback.start();
        }
    }

    /**
     * Should be called when mActivity.onCreate() is called
     */
    public void onCreate() {
        // Keep custom startup styling exact, but skip default no-op disk work from the cold path.
        if (hasCustomFontOrColors()) {
            checkForFontAndColors();
        }
    }

    /**
     * Should be called when mActivity.onStart() is called
     */
    public void onStart() {
        // The service has connected, but data may have changed since we were last in the foreground.
        // Prefer the durable restore foreground. Shared-preference current_session may still point
        // at the pre-restore handle during the first start after process recreation.
        if (mActivity.getTermuxService() != null) {
            mActivity.bootstrapTerminalSessionSelection(getRestoreForegroundSessionOrStoredOrLast());
            termuxSessionListNotifyUpdated();
            maybeAutoSwitchToProotSession();
            maybeAutoRestorePinnedSshSessions();
            reconcileManagedCodexSessions("activity_start");
        }

        // The current terminal session may have changed while being away, force
        // a refresh of the displayed terminal.
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null) {
            terminalView.onScreenUpdated();
        }
    }

    /**
     * Should be called when mActivity.onResume() is called
     */
    public void onResume() {
        // Bell preload is not first-frame critical; defer it only for cold start.
        if (mActivity.isOnResumeAfterOnCreate()) {
            View anchor = mActivity.getWindow() == null ? null : mActivity.getWindow().getDecorView();
            if (anchor != null) {
                anchor.post(this::loadBellSoundPool);
            } else {
                loadBellSoundPool();
            }
        } else {
            // Just initialize the mBellSoundPool and load the sound, otherwise bell might not run
            // the first time bell key is pressed and play() is called, since sound may not be loaded
            // quickly enough before the call to play(). https://stackoverflow.com/questions/35435625
            loadBellSoundPool();
        }
        reconcileManagedCodexSessions("activity_resume");
    }

    public void reconcileManagedCodexSessions(@NonNull String reason) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null || service.isTermuxSessionsEmpty() || mRestoringTermuxSessions) return;
        service.getCodexSessionRecoveryController().reconcile(reason);
    }

    public void onManagedCodexSessionRecovered(@NonNull TerminalSession terminalSession,
                                               boolean restoreForeground) {
        if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
        TerminalSession current = mActivity.getCurrentSession();
        if (restoreForeground || current == null || !current.isRunning()) {
            setCurrentSession(terminalSession);
        }
        termuxSessionListNotifyUpdated();
        persistTermuxSessionRestoreState(mActivity.getCurrentSession());
    }

    /**
     * Should be called when mActivity.onStop() is called
     */
    public void onStop() {
        mSessionSelectionPersistenceHandler.removeCallbacks(mPersistPendingSessionSelection);
        mPendingSessionSelectionPersistence = null;
        // Store current session in shared preferences so that it can be restored later in
        // {@link #onStart} if needed.
        TerminalSession selectedSession = getLastExplicitSelectedSessionOrCurrent();
        setCurrentStoredSession(selectedSession);
        persistTermuxSessionRestoreState(selectedSession);

        // Release mBellSoundPool resources, specially to prevent exceptions like the following to be thrown
        // java.util.concurrent.TimeoutException: android.media.SoundPool.finalize() timed out after 10 seconds
        // Bell is not played in background anyways
        // Related: https://stackoverflow.com/a/28708351/14686958
        releaseBellSoundPool();
    }

    /**
     * Should be called when mActivity.reloadActivityStyling() is called
     */
    public void onReloadActivityStyling() {
        // Set terminal fonts and colors
        checkForFontAndColors();
    }



    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        if (!mActivity.isVisible()) return;
        mActivity.onTerminalSessionTextChanged(changedSession);
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession updatedSession) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mTerminalTitleHandler.post(() -> onTitleChanged(updatedSession));
            return;
        }

        Runnable pending = mPendingTerminalTitleUpdates.remove(updatedSession);
        if (pending != null) mTerminalTitleHandler.removeCallbacks(pending);

        Runnable settledUpdate = () -> {
            mPendingTerminalTitleUpdates.remove(updatedSession);
            persistTermuxSessionRestoreState();
            if (!mActivity.isVisible()) return;

            if (updatedSession != mActivity.getCurrentSession()) {
                mActivity.showToast(toToastTitle(updatedSession), true);
            }
            termuxSessionListNotifyUpdated();
        };
        mPendingTerminalTitleUpdates.put(updatedSession, settledUpdate);
        mTerminalTitleHandler.postDelayed(settledUpdate, TERMINAL_TITLE_SETTLE_DELAY_MS);
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        Runnable pendingTitleUpdate = mPendingTerminalTitleUpdates.remove(finishedSession);
        if (pendingTitleUpdate != null) mTerminalTitleHandler.removeCallbacks(pendingTitleUpdate);
        clearRuntimeStateForSessionHandle(finishedSession.mHandle);
        mSshTmuxRuntimeEngine.onTerminalSessionFinished(finishedSession);
        TermuxService service = mActivity.getTermuxService();

        if (service != null && service.getCodexSessionRecoveryController().handleFinishedSession(finishedSession)) {
            if (service.wantsToStop()) mActivity.finishActivityIfNotFinishing();
            return;
        }

        if (service == null || service.wantsToStop()) {
            // The service wants to stop as soon as possible.
            mActivity.finishActivityIfNotFinishing();
            return;
        }

        int index = service.getIndexOfSession(finishedSession);

        // For plugin commands that expect the result back, we should immediately close the session
        // and send the result back instead of waiting fo the user to press enter.
        // The plugin can handle/show errors itself.
        boolean isPluginExecutionCommandWithPendingResult = false;
        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null) {
            isPluginExecutionCommandWithPendingResult = termuxSession.getExecutionCommand().isPluginExecutionCommandWithPendingResult();
            if (isPluginExecutionCommandWithPendingResult)
                Logger.logVerbose(LOG_TAG, "The \"" + finishedSession.mSessionName + "\" session will be force finished automatically since result in pending.");
        }

        if (mActivity.isVisible() && finishedSession != mActivity.getCurrentSession()) {
            // Show toast for non-current sessions that exit.
            // Verify that session was not removed before we got told about it finishing:
            if (index >= 0)
                mActivity.showToast(toToastTitle(finishedSession) + " - 已退出", true);
        }

        if (mActivity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            // On Android TV devices we need to use older behaviour because we may
            // not be able to have multiple launcher icons.
            if (service.getTermuxSessionsSize() > 1 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession);
            }
        } else {
            // Once we have a separate launcher icon for the failsafe session, it
            // should be safe to auto-close session on exit code '0' or '130'.
            if (finishedSession.getExitStatus() == 0 || finishedSession.getExitStatus() == 130 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession);
            }
        }

    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        if (!mActivity.isVisible()) return;

        ShareUtils.copyTextToClipboard(mActivity, text);
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        if (!mActivity.isVisible()) return;

        String text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true);
        TerminalView terminalView = mActivity.getTerminalView();
        if (text != null && terminalView != null && terminalView.mEmulator != null) {
            terminalView.mEmulator.paste(text);
        }
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
        if (!mActivity.isVisible()) return;

        switch (mActivity.getProperties().getBellBehaviour()) {
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VIBRATE:
                BellHandler.getInstance(mActivity).doBell();
                break;
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_BEEP:
                loadBellSoundPool();
                if (mBellSoundPool != null)
                    mBellSoundPool.play(mBellSoundId, 1.f, 1.f, 1, 0, 1.f);
                break;
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_IGNORE:
                // Ignore the bell character.
                break;
        }
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession changedSession) {
        mActivity.onTerminalSessionColorsChanged(changedSession);
        if (mActivity.getCurrentSession() == changedSession)
            updateBackgroundColor();
    }

    private void handleCodexRestoreHostControlCommand(@NonNull TerminalSession session,
                                                      @Nullable String argument) {
        Runnable action = () -> handleCodexRestoreHostControlCommandOnUi(session, argument);
        if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
        if (Looper.myLooper() == mActivity.getMainLooper()) {
            action.run();
        } else {
            mActivity.runOnUiThread(action);
        }
    }

    private void handleCodexRestoreHostControlCommandOnUi(@NonNull TerminalSession session,
                                                          @Nullable String argument) {
        CodexSessionHostProtocol.Event event = CodexSessionHostProtocol.parse(argument);
        if (event == null) {
            Logger.logWarn(LOG_TAG, "Ignoring invalid Codex session host event");
            return;
        }

        String handle = nullToEmpty(session.mHandle);
        int order = getLiveSessionOrder(session);
        if (event.type == CodexRestoreStateMachine.HostEvent.CLOSED) {
            CodexRestoreRecord current = findCodexRestoreRecordForSession(session);
            if (current != null && !TextUtils.equals(current.threadId, event.threadId)) {
                Logger.logWarn(LOG_TAG, "Ignoring stale Codex close event for " + event.threadId);
                return;
            }
            TermuxSessionRestoreStore.UpdateResult result = TermuxSessionRestoreStore.applyCodexEvent(
                handle, order, event);
            if (result == TermuxSessionRestoreStore.UpdateResult.IGNORED && current == null) return;
            if (result == TermuxSessionRestoreStore.UpdateResult.FAILED) {
                Logger.logWarn(LOG_TAG, "Failed to persist Codex close telemetry; lease remains authoritative");
                return;
            }
            if (result != TermuxSessionRestoreStore.UpdateResult.APPLIED) return;
            forgetNativeCodexRestoreForSession(session);
            TermuxService service = mActivity.getTermuxService();
            if (service != null) service.getCodexSessionRecoveryController().onLeaseClosed(event.threadId);
            termuxSessionListNotifyUpdated();
            return;
        }

        if (!CodexProcessIdentity.isLiveCodexProcessForSession(session, event.processId)) {
            Logger.logWarn(LOG_TAG, "Ignoring Codex restore identity from an unrelated process");
            return;
        }
        int codexPid = event.processId;

        String workingDirectory = event.workingDirectory;
        if (TextUtils.isEmpty(workingDirectory)) workingDirectory = session.getCwd();
        workingDirectory = resolveCodexRestoreWorkingDirectory(workingDirectory);

        String title = normalizeCodexTabTitle(event.title);
        if (TextUtils.isEmpty(title)) title = defaultCodexTitle(event.threadId);

        CodexSessionHostProtocol.Event normalizedEvent = new CodexSessionHostProtocol.Event(
            event.type, event.threadId, event.processId, workingDirectory, event.rolloutPath, title);
        TermuxSessionRestoreStore.UpdateResult result = TermuxSessionRestoreStore.applyCodexEvent(
            handle, order, normalizedEvent);
        if (result == TermuxSessionRestoreStore.UpdateResult.FAILED) {
            Logger.logWarn(LOG_TAG, "Failed to persist Codex ready event; refusing an in-memory-only registration");
        }
        if (result != TermuxSessionRestoreStore.UpdateResult.APPLIED) {
            Logger.logWarn(LOG_TAG, "Ignoring Codex ready event without a matching durable rollout");
            return;
        }

        CodexRestoreRecord record = new CodexRestoreRecord(
            event.threadId,
            workingDirectory,
            event.rolloutPath,
            title,
            codexPid,
            order,
            System.currentTimeMillis() / 1000L);

        rememberNativeCodexRestoreRecord(session, record);
        syncCodexTitleToTermuxSession(session, record.title);
        TermuxService service = mActivity.getTermuxService();
        if (service != null) service.getCodexSessionRecoveryController().onLeaseReady(event.threadId, session);
        persistTermuxSessionRestoreState(mActivity.getCurrentSession());
        termuxSessionListNotifyUpdated();
    }

    @NonNull
    private String normalizeCodexThreadId(@Nullable String rawThreadId) {
        String value = normalizeNullableRestoreString(rawThreadId);
        if (TextUtils.isEmpty(value)) return "";
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            Logger.logWarn(LOG_TAG, "Invalid Codex thread ID: " + value);
            return "";
        }
    }

    @NonNull
    private String defaultCodexTitle(@NonNull String threadId) {
        int end = Math.min(8, threadId.length());
        return CODEX_RESTORE_DEFAULT_DISPLAY_NAME + " " + threadId.substring(0, end);
    }

    private void syncCodexTitleToTermuxSession(@NonNull TerminalSession session,
                                               @Nullable String rawTitle) {
        String title = normalizeCodexTabTitle(rawTitle);
        if (TextUtils.isEmpty(title)) return;
        if (TextUtils.equals(title, session.mSessionName)) return;
        session.mSessionName = title;
    }

    @NonNull
    private String normalizeCodexTabTitle(@Nullable String rawTitle) {
        String value = normalizeNullableRestoreString(rawTitle);
        if (TextUtils.isEmpty(value)) return "";
        StringBuilder out = new StringBuilder(value.length());
        boolean previousSpace = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == 0) continue;
            if (Character.isISOControl(ch) || Character.isWhitespace(ch)) {
                if (!previousSpace && out.length() > 0) {
                    out.append(' ');
                    previousSpace = true;
                }
            } else {
                out.append(ch);
                previousSpace = false;
            }
        }
        return out.toString().trim();
    }

    @NonNull
    private String optJsonString(@NonNull JSONObject json, @NonNull String key) {
        return optJsonRestoreString(json, key);
    }

    @NonNull
    private static String optJsonRestoreString(@NonNull JSONObject json, @NonNull String key) {
        return optJsonRestoreString(json, key, "");
    }

    @NonNull
    private static String optJsonRestoreString(@NonNull JSONObject json, @NonNull String key,
                                               @Nullable String fallback) {
        Object value = json.opt(key);
        if (value == null || JSONObject.NULL.equals(value)) return normalizeNullableRestoreString(fallback);
        return normalizeNullableRestoreString(String.valueOf(value));
    }

    @NonNull
    private static String normalizeNullableRestoreString(@Nullable String value) {
        if (value == null) return "";
        String normalized = value.trim();
        return "null".equalsIgnoreCase(normalized) ? "" : normalized;
    }

    @Override
    public void onTerminalCursorStateChange(boolean enabled) {
        // Do not start cursor blinking thread if activity is not visible
        if (enabled && !mActivity.isVisible()) {
            Logger.logVerbose(LOG_TAG, "Ignoring call to start cursor blinking since activity is not visible");
            return;
        }

        // If cursor is to enabled now, then start cursor blinking if blinking is enabled
        // otherwise stop cursor blinking
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null) {
            terminalView.setTerminalCursorBlinkerState(enabled, false);
        }
    }

    @Override
    public void onTerminalHostControlCommand(@NonNull TerminalSession session, @NonNull String command, @Nullable String argument) {
        if (CodexSessionHostProtocol.COMMAND.equals(command)) {
            handleCodexRestoreHostControlCommand(session, argument);
            return;
        }

        if (!mActivity.isVisible()) return;
        if (session != mActivity.getCurrentSession()) return;

        Runnable action = () -> {
            TermuxTerminalViewClient terminalViewClient = mActivity.getTermuxTerminalViewClient();
            if (terminalViewClient == null) return;

            if ("soft-keyboard".equals(command)) {
                String normalizedArgument = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
                switch (normalizedArgument) {
                    case "show":
                        terminalViewClient.showSoftKeyboardForTerminal();
                        break;
                    case "hide":
                        terminalViewClient.hideSoftKeyboardForTerminal();
                        break;
                    case "toggle":
                        terminalViewClient.toggleSoftKeyboardForTerminal();
                        break;
                    default:
                        Logger.logWarn(LOG_TAG, "Unsupported soft-keyboard control argument: " + argument);
                        break;
                }
            } else if ("ime-rect".equals(command)) {
                terminalViewClient.setTerminalImeRect(argument);
            } else {
                Logger.logWarn(LOG_TAG, "Unsupported terminal host control command: " + command);
            }
        };

        if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
        if (Looper.myLooper() == mActivity.getMainLooper()) {
            action.run();
        } else {
            mActivity.runOnUiThread(action);
        }
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        
        TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession != null)
            termuxSession.getExecutionCommand().mPid = pid;
    }


    /**
     * Should be called when mActivity.onResetTerminalSession() is called
     */
    public void onResetTerminalSession() {
        // Ensure blinker starts again after reset if cursor blinking was disabled before reset like
        // with "tput civis" which would have called onTerminalCursorStateChange()
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null) {
            terminalView.setTerminalCursorBlinkerState(true, true);
        }
    }



    @Override
    public Integer getTerminalCursorStyle() {
        return mActivity.getProperties().getTerminalCursorStyle();
    }



    /** Load mBellSoundPool */
    private synchronized void loadBellSoundPool() {
        if (mBellSoundPool == null) {
            mBellSoundPool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build();

            try {
                mBellSoundId = mBellSoundPool.load(mActivity, com.termux.shared.R.raw.bell, 1);
            } catch (Exception e){
                // Catch java.lang.RuntimeException: Unable to resume activity {com.termux/com.termux.app.TermuxActivity}: android.content.res.Resources$NotFoundException: File res/raw/bell.ogg from drawable resource ID
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load bell sound pool", e);
            }
        }
    }

    /** Release mBellSoundPool resources */
    private synchronized void releaseBellSoundPool() {
        if (mBellSoundPool != null) {
            mBellSoundPool.release();
            mBellSoundPool = null;
        }
    }



    /** Try switching to session. */
    public void setCurrentSession(TerminalSession session) {
        if (session == null) return;
        if (mActivity.requestTerminalSessionSurfaceSelection(session, true)) {
            return;
        }

        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null && terminalView.attachSession(session)) {
            // notify about switched session if not already displaying the session
            notifyOfSessionChange();
        }

        mActivity.onTerminalSessionSelectionCommitted(session);
        persistTerminalSessionSelection(session);
    }

    public void persistTerminalSessionSelection(@Nullable TerminalSession session) {
        if (session == null) return;
        if (!TextUtils.isEmpty(session.mHandle)) {
            mLastExplicitSelectedSessionHandle = session.mHandle;
        }
        if (!TextUtils.isEmpty(session.mHandle)) {
            mActivity.getPreferences().setCurrentSession(session.mHandle);
        }
        mPendingSessionSelectionPersistence = session;
        mSessionSelectionPersistenceHandler.removeCallbacks(mPersistPendingSessionSelection);
        mSessionSelectionPersistenceHandler.postDelayed(
            mPersistPendingSessionSelection,
            SESSION_SELECTION_PERSIST_IDLE_DELAY_MS
        );
    }

    void notifyOfSessionChange() {
        if (!mActivity.isVisible()) return;

        if (!mActivity.getProperties().areTerminalSessionChangeToastsDisabled()) {
            TerminalSession session = mActivity.getCurrentSession();
            mActivity.showToast(toToastTitle(session), false);
        }
    }

    public void switchToSession(boolean forward) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TerminalSession currentTerminalSession = mActivity.getCurrentSession();
        int index = service.getIndexOfSession(currentTerminalSession);
        int size = service.getTermuxSessionsSize();
        if (forward) {
            if (++index >= size) index = 0;
        } else {
            if (--index < 0) index = size - 1;
        }

        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null)
            setCurrentSession(termuxSession.getTerminalSession());
    }

    public void switchToSession(int index) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null)
            setCurrentSession(termuxSession.getTerminalSession());
    }

    @SuppressLint("InflateParams")
    public void renameSession(final TerminalSession sessionToRename) {
        if (sessionToRename == null) return;

        TextInputDialogUtils.textInput(mActivity, R.string.title_rename_session, sessionToRename.mSessionName, R.string.action_rename_session_confirm, text -> {
            renameSession(sessionToRename, text);
            termuxSessionListNotifyUpdated();
        }, -1, null, -1, null, null);
    }

    private void renameSession(TerminalSession sessionToRename, String text) {
        if (sessionToRename == null) return;

        if (mSshTmuxRuntimeEngine.renamePinnedSession(sessionToRename, text)) {
            persistTermuxSessionRestoreState();
            return;
        }

        sessionToRename.mSessionName = TextUtils.isEmpty(text) ? null : text;
        TermuxService service = mActivity.getTermuxService();
        if (service != null) {
            TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(sessionToRename);
            if (termuxSession != null)
                termuxSession.getExecutionCommand().shellName = text;
        }
        persistTermuxSessionRestoreState();
    }

    public void addNewSession(boolean isFailSafe, String sessionName) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) {
            new AlertDialog.Builder(mActivity).setTitle(R.string.title_max_terminals_reached).setMessage(R.string.msg_max_terminals_reached)
                .setPositiveButton(android.R.string.ok, null).show();
        } else {
            TerminalSession currentSession = mActivity.getCurrentSession();

            String workingDirectory;
            if (currentSession == null) {
                workingDirectory = mActivity.getProperties().getDefaultWorkingDirectory();
            } else {
                workingDirectory = currentSession.getCwd();
            }

            TermuxSession newTermuxSession;
            if (shouldStartProotByDefault()) {
                String distro = getProotDefaultDistro();
                String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
                String prootCmd = buildProotInteractiveCommand(distro);
                String[] args = new String[]{"-lc", prootCmd};
                String name = sessionName != null ? sessionName : ("proot-" + distro);
                newTermuxSession = service.createTermuxSession(bash, args, null, workingDirectory, isFailSafe, name);
            } else {
                newTermuxSession = service.createTermuxSession(null, null, null, workingDirectory, isFailSafe, sessionName);
            }
            if (newTermuxSession == null) return;

            TerminalSession newTerminalSession = newTermuxSession.getTerminalSession();
            setCurrentSession(newTerminalSession);

            mActivity.getDrawer().closeDrawers();
        }
    }

    public void addNewLocalSession(@Nullable String sessionName) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) {
            new AlertDialog.Builder(mActivity).setTitle(R.string.title_max_terminals_reached).setMessage(R.string.msg_max_terminals_reached)
                .setPositiveButton(android.R.string.ok, null).show();
            return;
        }

        TerminalSession currentSession = mActivity.getCurrentSession();
        String workingDirectory = currentSession == null ?
            mActivity.getProperties().getDefaultWorkingDirectory() : currentSession.getCwd();

        TermuxSession newTermuxSession = service.createTermuxSession(null, null, null, workingDirectory, false, sessionName);
        if (newTermuxSession == null) return;

        setCurrentSession(newTermuxSession.getTerminalSession());
        termuxSessionListNotifyUpdated();
        mActivity.getDrawer().closeDrawers();
    }

    // Extension point: top-level long-press panel from "+" where more capabilities can be plugged in.
    public void showPlusLongPressPanel() {
        mActivity.runOnUiThread(() -> {
            ScrollView scrollView = new ScrollView(mActivity);
            LinearLayout container = new LinearLayout(mActivity);
            container.setOrientation(LinearLayout.VERTICAL);
            int padding = dp(16);
            container.setPadding(padding, padding, padding, padding);
            scrollView.addView(container);

            TextView intro = new TextView(mActivity);
            intro.setText(R.string.msg_plus_panel_intro);
            intro.setTextSize(14f);
            intro.setPadding(0, 0, 0, dp(12));
            container.addView(intro);

            Button sshButton = createPanelButton(R.string.action_plus_panel_ssh);
            sshButton.setOnClickListener(v -> {
                AlertDialog d = (AlertDialog) v.getTag();
                if (d != null) d.dismiss();
                showSshProfilesDialog();
            });
            container.addView(sshButton);

            Button localButton = createPanelButton(R.string.action_plus_panel_local);
            localButton.setOnClickListener(v -> {
                AlertDialog d = (AlertDialog) v.getTag();
                if (d != null) d.dismiss();
                addNewLocalSession(null);
            });
            container.addView(localButton);

            Button reservedSftp = createPanelButton(R.string.action_plus_panel_reserved_sftp);
            reservedSftp.setEnabled(false);
            reservedSftp.setAlpha(0.65f);
            container.addView(reservedSftp);

            Button reservedForward = createPanelButton(R.string.action_plus_panel_reserved_forward);
            reservedForward.setEnabled(false);
            reservedForward.setAlpha(0.65f);
            container.addView(reservedForward);

            AlertDialog dialog = new AlertDialog.Builder(mActivity)
                .setTitle(R.string.title_plus_panel)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

            sshButton.setTag(dialog);
            localButton.setTag(dialog);
            reservedSftp.setTag(dialog);
            reservedForward.setTag(dialog);

            dialog.show();
            applyLargePanelLayout(dialog, 0.96f, WindowManager.LayoutParams.WRAP_CONTENT);
        });
    }

    // Extension point: SSH feature panel. Additional connect targets can be added beside SSH later.
    public void showSshProfilesDialog() {
        mActivity.runOnUiThread(() -> {
            ArrayList<SshProfile> profiles = loadSshProfiles();
            ScrollView scrollView = new ScrollView(mActivity);
            LinearLayout container = new LinearLayout(mActivity);
            container.setOrientation(LinearLayout.VERTICAL);
            int padding = dp(14);
            container.setPadding(padding, padding, padding, padding);
            scrollView.addView(container);

            TextView hint = new TextView(mActivity);
            hint.setText(profiles.isEmpty() ? R.string.msg_ssh_profiles_empty : R.string.msg_ssh_profiles_hint);
            hint.setTextSize(13f);
            hint.setPadding(0, 0, 0, dp(10));
            container.addView(hint);

            final AlertDialog[] dialogRef = new AlertDialog[1];
            if (!profiles.isEmpty()) {
                for (SshProfile profile : profiles) {
                    addSshProfileCardView(container, profile, dialogRef);
                }
            }

            AlertDialog dialog = new AlertDialog.Builder(mActivity)
                .setTitle(R.string.title_ssh_profiles)
                .setView(scrollView)
                .setPositiveButton(R.string.action_ssh_profile_add, (d, which) -> showSshProfileEditorDialog(null))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
            dialogRef[0] = dialog;
            dialog.show();
            applyLargePanelLayout(dialog, 0.96f, WindowManager.LayoutParams.WRAP_CONTENT);
        });
    }

    public static final class ConfigProfileItem {
        @NonNull public final String id;
        @NonNull public final String title;
        @NonNull public final String summary;
        @NonNull public final String trustSummary;
        public final boolean hasPendingTrust;
        public final boolean hasPassword;

        public ConfigProfileItem(@NonNull String id, @NonNull String title,
                                 @NonNull String summary, @NonNull String trustSummary,
                                 boolean hasPendingTrust, boolean hasPassword) {
            this.id = id;
            this.title = title;
            this.summary = summary;
            this.trustSummary = trustSummary;
            this.hasPendingTrust = hasPendingTrust;
            this.hasPassword = hasPassword;
        }
    }

    public static final class ConfigTmuxSessionItem {
        @NonNull public final String name;
        @NonNull public final String title;
        @NonNull public final String summary;
        public final boolean current;

        public ConfigTmuxSessionItem(@NonNull String name, @NonNull String title,
                                     @NonNull String summary, boolean current) {
            this.name = name;
            this.title = title;
            this.summary = summary;
            this.current = current;
        }
    }

    public static final class ConfigTmuxSnapshot {
        @NonNull public final String profileId;
        @NonNull public final String profileTitle;
        @NonNull public final String targetLabel;
        public final boolean tmuxMissing;
        @NonNull public final ArrayList<ConfigTmuxSessionItem> sessions;
        @Nullable public final String errorMessage;

        public ConfigTmuxSnapshot(@NonNull String profileId,
                                  @NonNull String profileTitle,
                                  @NonNull String targetLabel,
                                  boolean tmuxMissing,
                                  @NonNull ArrayList<ConfigTmuxSessionItem> sessions,
                                  @Nullable String errorMessage) {
            this.profileId = profileId;
            this.profileTitle = profileTitle;
            this.targetLabel = targetLabel;
            this.tmuxMissing = tmuxMissing;
            this.sessions = sessions;
            this.errorMessage = errorMessage;
        }
    }

    public interface ConfigTmuxSnapshotCallback {
        void onLoaded(@NonNull ConfigTmuxSnapshot snapshot);
    }

    public interface ConfigActionCallback {
        void onComplete(boolean success);
    }

    @NonNull
    public ArrayList<ConfigProfileItem> getConfigProfileItems() {
        ArrayList<SshProfile> profiles = loadSshProfiles();
        ArrayList<ConfigProfileItem> items = new ArrayList<>(profiles.size());
        SessionFileCoordinator coordinator = SessionFileCoordinator.getInstance();
        coordinator.initialize(mActivity);
        for (SshProfile profile : profiles) {
            if (profile == null) continue;
            SessionEntry entry = findSessionEntryForProfileId(profile.id);
            SshPendingTrustRecord pending = entry == null ? null : coordinator.getPendingTrustForEntry(mActivity, entry);
            items.add(new ConfigProfileItem(
                profile.id,
                profile.displayName,
                buildSshProfileSummary(profile),
                buildTrustSummary(coordinator, entry, pending),
                pending != null,
                !TextUtils.isEmpty(profile.password)
            ));
        }
        return items;
    }

    @Nullable
    private SshProfile findSshProfileById(@Nullable String id) {
        if (TextUtils.isEmpty(id)) return null;
        ArrayList<SshProfile> profiles = loadSshProfiles();
        for (SshProfile profile : profiles) {
            if (profile != null && id.equals(profile.id)) return profile;
        }
        return null;
    }

    @Nullable
    private SessionEntry findSessionEntryForProfileId(@Nullable String profileId) {
        if (TextUtils.isEmpty(profileId)) return null;
        String entryId = SavedSshProfileStore.PROFILE_ENTRY_ID_PREFIX + profileId;
        for (SessionEntry entry : SavedSshProfileStore.loadSessionEntries(mActivity)) {
            if (entry != null && entryId.equals(entry.id)) return entry;
        }
        return null;
    }

    public void connectProfileFromConfigTab(@Nullable String profileId) {
        SshProfile profile = findSshProfileById(profileId);
        if (profile == null) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_invalid), true);
            return;
        }
        connectWithSshProfile(profile);
    }

    public void openProfileEditorFromConfigTab(@Nullable String profileId) {
        showSshProfileEditorDialog(findSshProfileById(profileId), false);
    }

    public void openTrustManagerFromConfigTab(@Nullable String profileId) {
        SshProfile profile = findSshProfileById(profileId);
        if (profile == null) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_invalid), true);
            return;
        }
        SessionEntry entry = findSessionEntryForProfileId(profile.id);
        if (entry == null) {
            mActivity.showToast("未找到对应的共享 SSH 会话映射。", true);
            return;
        }
        showTrustManagerDialog(profile, entry);
    }

    public void openPersistenceManagerFromConfigTab(@Nullable String profileId) {
        SshProfile profile = findSshProfileById(profileId);
        if (profile == null) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_invalid), true);
            return;
        }
        showSshPersistenceManagerDialog(profile);
    }

    public void deleteProfileFromConfigTab(@Nullable String profileId) {
        SshProfile profile = findSshProfileById(profileId);
        if (profile == null) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_invalid), true);
            return;
        }
        showDeleteSshProfileConfirmDialog(profile, false);
    }

    public void loadTmuxSnapshotForConfigTab(@Nullable String profileId,
                                             @NonNull ConfigTmuxSnapshotCallback callback) {
        SshProfile profile = findSshProfileById(profileId);
        if (profile == null) {
            callback.onLoaded(new ConfigTmuxSnapshot(
                "",
                "",
                "",
                false,
                new ArrayList<>(),
                mActivity.getString(R.string.msg_ssh_profile_invalid)
            ));
            return;
        }
        SshLaunchConfig config = resolveSshLaunchConfig(profile);
        if (config == null) {
            callback.onLoaded(new ConfigTmuxSnapshot(
                profile.id,
                profile.displayName,
                profile.displayName,
                false,
                new ArrayList<>(),
                mActivity.getString(R.string.msg_ssh_profile_invalid)
            ));
            return;
        }
        ensureSshpassAndRunIfNeeded(profile, () ->
            mSshTmuxRuntimeEngine.loadRemoteTmuxSessions(config.sshCommand, result -> {
                boolean tmuxMissing = result.tmuxMissing;
                String error = null;
                if (!tmuxMissing && !result.listDone && result.sessions.isEmpty()) {
                    error = mActivity.getString(R.string.msg_ssh_persistence_list_failed);
                }
                String currentTmux = findCurrentActiveTmuxSession(config);
                callback.onLoaded(new ConfigTmuxSnapshot(
                    profile.id,
                    profile.displayName,
                    TextUtils.isEmpty(config.targetLabel) ? profile.displayName : config.targetLabel,
                    tmuxMissing,
                    toConfigTmuxSessionItems(toAppRemoteTmuxSessions(result.sessions), currentTmux),
                    error
                ));
            })
        );
    }

    public void installTmuxFromConfigTab(@Nullable String profileId, @NonNull ConfigActionCallback callback) {
        SshProfile profile = findSshProfileById(profileId);
        if (profile == null) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_invalid), true);
            callback.onComplete(false);
            return;
        }
        SshLaunchConfig config = resolveSshLaunchConfig(profile);
        if (config == null) {
            callback.onComplete(false);
            return;
        }
        mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_installing_tmux), true);
        mSshTmuxRuntimeEngine.installTmux(config.sshCommand, result -> {
            boolean success = result.code == SshTmuxOperationResult.Code.SUCCESS;
            if (!success) {
                mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_install_failed), true);
            }
            callback.onComplete(success);
        });
    }

    public void createTmuxSessionFromConfigTab(@Nullable String profileId, @Nullable String requestedDisplayName,
                                               @NonNull ConfigActionCallback callback) {
        SshProfile profile = findSshProfileById(profileId);
        if (profile == null) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_invalid), true);
            callback.onComplete(false);
            return;
        }
        SshLaunchConfig config = resolveSshLaunchConfig(profile);
        if (config == null) {
            callback.onComplete(false);
            return;
        }
        TerminalSession anchorSession = mActivity.getCurrentSession();
        mSshTmuxRuntimeEngine.createRemoteTmuxSessionAndConnect(
            anchorSession,
            config.sshCommand,
            config.sessionName,
            requestedDisplayName,
            result -> {
                boolean success = result.code == SshTmuxOperationResult.Code.SUCCESS;
                if (result.code == SshTmuxOperationResult.Code.TMUX_MISSING) {
                    mActivity.showToast(mActivity.getString(R.string.title_ssh_persistence_tmux_missing), true);
                } else if (!success) {
                    mActivity.showToast(mActivity.getString(
                        R.string.msg_ssh_persistence_create_failed,
                        summarizeCommandResult(toCommandResult(result.commandResult))), true);
                } else {
                    mActivity.showToast(mActivity.getString(
                        R.string.msg_ssh_persistence_created_connecting, result.displayName), false);
                }
                callback.onComplete(success);
            }
        );
    }

    public void connectTmuxSessionFromConfigTab(@Nullable String profileId,
                                                @NonNull String tmuxSession,
                                                @NonNull String displayName,
                                                @NonNull ConfigActionCallback callback) {
        SshProfile profile = findSshProfileById(profileId);
        if (profile == null) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_invalid), true);
            callback.onComplete(false);
            return;
        }
        SshLaunchConfig config = resolveSshLaunchConfig(profile);
        if (config == null) {
            callback.onComplete(false);
            return;
        }
        TerminalSession anchorSession = mActivity.getCurrentSession();
        mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_connecting_tmux, displayName), false);
        mSshTmuxRuntimeEngine.connectToPersistentTmuxSession(
            anchorSession,
            config.sshCommand,
            normalizeTmuxSessionName(tmuxSession),
            displayName,
            result -> {
                boolean success = result.code == SshTmuxOperationResult.Code.SUCCESS;
                if (!success) {
                    mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_tmux_check_failed), true);
                }
                callback.onComplete(success);
            }
        );
    }

    public void destroyTmuxSessionFromConfigTab(@Nullable String profileId,
                                                @NonNull String tmuxSession,
                                                @NonNull ConfigActionCallback callback) {
        SshProfile profile = findSshProfileById(profileId);
        if (profile == null) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_invalid), true);
            callback.onComplete(false);
            return;
        }
        SshLaunchConfig config = resolveSshLaunchConfig(profile);
        if (config == null) {
            callback.onComplete(false);
            return;
        }
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        mSshTmuxRuntimeEngine.destroyRemoteTmuxSession(config.sshCommand, safeTmuxSession, safeTmuxSession, result -> {
            boolean success = result.code == SshTmuxOperationResult.Code.SUCCESS;
            if (result.code == SshTmuxOperationResult.Code.TMUX_MISSING) {
                mActivity.showToast(mActivity.getString(R.string.title_ssh_persistence_tmux_missing), true);
            } else if (!success) {
                mActivity.showToast(mActivity.getString(
                    R.string.msg_ssh_persistence_destroy_failed,
                    summarizeCommandResult(toCommandResult(result.commandResult))), true);
            } else {
                mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_destroyed, safeTmuxSession), false);
            }
            callback.onComplete(success);
        });
    }

    private void showDeleteSshProfileConfirmDialog(@NonNull SshProfile profile) {
        showDeleteSshProfileConfirmDialog(profile, true);
    }

    private void showDeleteSshProfileConfirmDialog(@NonNull SshProfile profile, boolean reopenProfilesDialog) {
        new AlertDialog.Builder(mActivity)
            .setTitle(R.string.title_ssh_profile_delete_confirm)
            .setMessage(mActivity.getString(R.string.msg_ssh_profile_delete_confirm, profile.displayName))
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                ArrayList<SshProfile> profiles = loadSshProfiles();
                boolean removed = false;
                for (int i = profiles.size() - 1; i >= 0; i--) {
                    if (profile.id.equals(profiles.get(i).id)) {
                        profiles.remove(i);
                        removed = true;
                    }
                }
                if (removed) {
                    saveSshProfiles(profiles);
                    mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_deleted), false);
                }
                mActivity.notifyTerminalConfigTabDataChanged();
                if (reopenProfilesDialog) showSshProfilesDialog();
            })
            .setNegativeButton(android.R.string.cancel,
                reopenProfilesDialog ? (dialog, which) -> showSshProfilesDialog() : null)
            .show();
    }

    private void showSshProfileEditorDialog(@Nullable SshProfile existing) {
        showSshProfileEditorDialog(existing, true);
    }

    private void showSshProfileEditorDialog(@Nullable SshProfile existing, boolean reopenProfilesDialog) {
        final boolean isEdit = existing != null;

        ScrollView scrollView = new ScrollView(mActivity);
        LinearLayout container = new LinearLayout(mActivity);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        container.setPadding(padding, padding, padding, padding);
        scrollView.addView(container);

        EditText commandInput = createDialogInput(container, R.string.hint_ssh_profile_host,
            existing == null ? "" : existing.host, InputType.TYPE_CLASS_TEXT);
        commandInput.setHint("SSH command (e.g. ssh root@1.2.3.4 -p 22)");
        EditText passwordInput = createDialogInput(container, R.string.hint_ssh_profile_password,
            existing == null ? "" : existing.password,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        AlertDialog dialog = new AlertDialog.Builder(mActivity)
            .setTitle(isEdit ? R.string.title_ssh_profile_edit : R.string.title_ssh_profile_add)
            .setView(scrollView)
            .setPositiveButton(R.string.action_ssh_profile_save, null)
            .setNegativeButton(android.R.string.cancel,
                reopenProfilesDialog ? (d, which) -> showSshProfilesDialog() : null)
            .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String command = commandInput.getText() == null ? "" : commandInput.getText().toString().trim();
            if (!isRawSshCommand(command)) {
                mActivity.showToast("Please enter a full SSH command starting with ssh.", true);
                return;
            }

            String displayName = buildSshProfileDisplayName(command);
            SshProfile saved = new SshProfile(
                isEdit ? existing.id : UUID.randomUUID().toString(),
                displayName,
                command,
                22,
                "",
                passwordInput.getText() == null ? "" : passwordInput.getText().toString(),
                ""
            );

            ArrayList<SshProfile> profiles = loadSshProfiles();
            boolean replaced = false;
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).id.equals(saved.id)) {
                    profiles.set(i, saved);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) profiles.add(saved);

            saveSshProfiles(profiles);
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_saved), false);
            mActivity.notifyTerminalConfigTabDataChanged();
            dialog.dismiss();
            if (reopenProfilesDialog) showSshProfilesDialog();
        }));
        dialog.show();
    }

    @NonNull
    private String buildSshProfileDisplayName(@NonNull String sshCommand) {
        String command = sshCommand.trim();
        if (command.isEmpty()) return "ssh";
        int max = 42;
        if (command.length() <= max) return command;
        return command.substring(0, max) + "...";
    }

    private EditText createDialogInput(@NonNull LinearLayout container, int hintRes, @NonNull String value, int inputType) {
        EditText input = new EditText(mActivity);
        input.setHint(hintRes);
        input.setText(value);
        input.setSingleLine(true);
        input.setInputType(inputType);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        container.addView(input, lp);
        return input;
    }

    private Button createPanelButton(int textRes) {
        Button button = new Button(mActivity);
        button.setText(textRes);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        button.setLayoutParams(lp);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setAllCaps(false);
        return button;
    }

    private void addSshProfileCardView(@NonNull LinearLayout parent, @NonNull SshProfile profile,
                                       @NonNull AlertDialog[] dialogRef) {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        int cardPadding = dp(12);
        card.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(8);
        parent.addView(card, cardLp);

        TextView title = new TextView(mActivity);
        title.setText(profile.displayName);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView subtitle = new TextView(mActivity);
        subtitle.setText(buildSshProfileSummary(profile));
        subtitle.setTextSize(12f);
        subtitle.setAlpha(0.78f);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleLp.topMargin = dp(4);
        card.addView(subtitle, subtitleLp);

        LinearLayout rowPrimary = new LinearLayout(mActivity);
        rowPrimary.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowPrimaryLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowPrimaryLp.topMargin = dp(10);
        card.addView(rowPrimary, rowPrimaryLp);

        Button connectBtn = createProfileActionButton(R.string.action_ssh_profile_connect, false);
        connectBtn.setOnClickListener(v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            connectWithSshProfile(profile);
        });
        rowPrimary.addView(connectBtn);

        Button persistBtn = createProfileActionButton(R.string.action_ssh_profile_persist_new, true);
        persistBtn.setOnClickListener(v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            showSshPersistenceManagerDialog(profile);
        });
        rowPrimary.addView(persistBtn);

        LinearLayout rowSecondary = new LinearLayout(mActivity);
        rowSecondary.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowSecondaryLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowSecondaryLp.topMargin = dp(6);
        card.addView(rowSecondary, rowSecondaryLp);

        Button editBtn = createProfileActionButton(R.string.action_ssh_profile_edit, false);
        editBtn.setOnClickListener(v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            showSshProfileEditorDialog(profile);
        });
        rowSecondary.addView(editBtn);

        Button deleteBtn = createProfileActionButton(R.string.action_ssh_profile_delete, true);
        deleteBtn.setOnClickListener(v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            showDeleteSshProfileConfirmDialog(profile);
        });
        rowSecondary.addView(deleteBtn);
    }

    @NonNull
    private String buildSshProfileSummary(@NonNull SshProfile profile) {
        String host = profile.host == null ? "" : profile.host.trim();
        String user = profile.user == null ? "" : profile.user.trim();
        if (host.isEmpty()) return "<invalid-host>";
        if (isRawSshCommand(host)) {
            if (TextUtils.isEmpty(profile.password)) return host;
            return host + "  (sshpass)";
        }
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(user)) sb.append(user).append("@");
        sb.append(host).append(":").append(profile.port);
        if (!TextUtils.isEmpty(profile.extraOptions)) {
            sb.append("  ").append(profile.extraOptions.trim());
        }
        return sb.toString();
    }

    @NonNull
    private String buildTrustSummary(@NonNull SessionFileCoordinator coordinator,
                                     @Nullable SessionEntry entry,
                                     @Nullable SshPendingTrustRecord pending) {
        if (entry == null) return "指纹：未建立共享会话映射";
        if (pending != null) {
            return pending.replacementRequired
                ? "指纹：待替换 · " + abbreviateFingerprint(pending.observedFingerprintSha256)
                : "指纹：待批准 · " + abbreviateFingerprint(pending.observedFingerprintSha256);
        }
        ArrayList<SshTrustRecord> records = new ArrayList<>(coordinator.listTrustedHostsForEntry(mActivity, entry));
        if (records.isEmpty()) return "指纹：暂无已信任记录";

        SshTrustRecord latest = records.get(0);
        for (SshTrustRecord record : records) {
            if (record != null && record.lastSeenAtMs > latest.lastSeenAtMs) {
                latest = record;
            }
        }
        return "指纹：" + records.size() + " 条 · "
            + latest.algorithm + " · "
            + abbreviateFingerprint(latest.fingerprintSha256);
    }

    @NonNull
    private String abbreviateFingerprint(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "-";
        if (value.length() <= 26) return value;
        return value.substring(0, 18) + "..." + value.substring(value.length() - 6);
    }

    private void showTrustManagerDialog(@NonNull SshProfile profile, @NonNull SessionEntry entry) {
        SessionFileCoordinator coordinator = SessionFileCoordinator.getInstance();
        coordinator.initialize(mActivity);
        ArrayList<SshTrustRecord> records = new ArrayList<>(coordinator.listTrustedHostsForEntry(mActivity, entry));
        SshPendingTrustRecord pending = coordinator.getPendingTrustForEntry(mActivity, entry);
        SshProfileResolutionResult resolution = LegacySshCommandProfileResolver.resolve(profile.id, entry.sshCommand);
        String authority = resolution.success && resolution.endpoint != null
            ? resolution.endpoint.authorityKey
            : entry.id;

        StringBuilder message = new StringBuilder();
        message.append("Authority:\n").append(authority).append("\n\n");
        if (pending != null) {
            message.append(pending.replacementRequired ? "待替换指纹:\n" : "待批准指纹:\n")
                .append("• ").append(pending.algorithm).append('\n')
                .append("  ").append(pending.observedFingerprintSha256).append('\n');
            if (!TextUtils.isEmpty(pending.existingFingerprintSha256)) {
                message.append("现有指纹:\n")
                    .append("  ").append(pending.existingFingerprintSha256).append('\n');
            }
            message.append('\n');
        }
        if (records.isEmpty()) {
            message.append("当前没有已信任的主机指纹记录。");
        } else {
            message.append("已信任指纹:\n");
            for (SshTrustRecord record : records) {
                if (record == null) continue;
                message.append("• ").append(record.algorithm)
                    .append('\n')
                    .append("  ").append(record.fingerprintSha256)
                    .append('\n');
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity)
            .setTitle("SSH 指纹管理")
            .setMessage(message.toString())
            .setNegativeButton(android.R.string.cancel, null);
        if (pending != null) {
            builder.setNeutralButton(pending.replacementRequired ? "替换指纹" : "批准指纹", (dialog, which) -> {
                boolean approved = coordinator.approvePendingTrustForEntry(mActivity, entry);
                if (approved) {
                    mActivity.showToast(
                        pending.replacementRequired ? "已替换主机指纹。" : "已批准主机指纹。",
                        false
                    );
                } else {
                    mActivity.showToast("未找到待处理的主机指纹。", true);
                }
                mActivity.notifyTerminalConfigTabDataChanged();
            });
        }
        if (!records.isEmpty()) {
            builder.setPositiveButton("清除指纹", (dialog, which) -> {
                boolean cleared = coordinator.clearTrustedHostForEntry(mActivity, entry);
                if (cleared) {
                    mActivity.showToast("已清除该配置的主机指纹。下次连接将重新建立信任。", false);
                } else {
                    mActivity.showToast("未找到可清除的主机指纹记录。", true);
                }
                mActivity.notifyTerminalConfigTabDataChanged();
            });
        }
        builder.show();
    }

    private Button createProfileActionButton(int textRes, boolean isLastInRow) {
        Button button = new Button(mActivity);
        button.setText(textRes);
        button.setAllCaps(false);
        button.setTextSize(12f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (!isLastInRow) lp.rightMargin = dp(8);
        button.setLayoutParams(lp);
        return button;
    }

    private void applyLargePanelLayout(@NonNull AlertDialog dialog, float widthRatio, int height) {
        Window window = dialog.getWindow();
        if (window == null) return;
        int screenWidth = mActivity.getResources().getDisplayMetrics().widthPixels;
        int width = Math.max(dp(280), Math.min(screenWidth - dp(12), Math.round(screenWidth * widthRatio)));
        window.setLayout(width, height);
    }

    private void connectWithSshProfile(@NonNull SshProfile profile) {
        SshLaunchConfig config = resolveSshLaunchConfig(profile);
        if (config == null) return;
        runWithProfileTrustReady(profile, config, () ->
            ensureSshpassAndRunIfNeeded(profile, () ->
                launchSshProfileSession(config.sessionName, config.targetLabel, config.sshCommand)));
    }

    private void createPersistentSessionWithSshProfile(@NonNull SshProfile profile) {
        SshLaunchConfig config = resolveSshLaunchConfig(profile);
        if (config == null) return;
        runWithProfileTrustReady(profile, config, () ->
            ensureSshpassAndRunIfNeeded(profile, () -> {
                TerminalSession anchorSession = mActivity.getCurrentSession();
                if (anchorSession == null) {
                    mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_launch_failed), true);
                    return;
                }
                mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_creating, config.targetLabel), false);
                prepareSshLock(anchorSession, config.sshCommand, false);
            }));
    }

    private void showSshPersistenceManagerDialog(@NonNull SshProfile profile) {
        SshLaunchConfig config = resolveSshLaunchConfig(profile);
        if (config == null) return;
        runWithProfileTrustReady(profile, config, () ->
            ensureSshpassAndRunIfNeeded(profile, () -> loadTmuxSessionsAndShowPersistenceDialog(profile, config)));
    }

    private void runWithProfileTrustReady(@NonNull SshProfile profile,
                                          @NonNull SshLaunchConfig config,
                                          @NonNull Runnable onReady) {
        SessionEntry entry = findSessionEntryForProfileId(profile.id);
        if (entry == null) {
            onReady.run();
            return;
        }

        SessionFileCoordinator coordinator = SessionFileCoordinator.getInstance();
        coordinator.initialize(mActivity);
        SshPendingTrustRecord pending = coordinator.getPendingTrustForEntry(mActivity, entry);
        if (pending != null) {
            showTrustManagerDialog(profile, entry);
            return;
        }
        if (!coordinator.listTrustedHostsForEntry(mActivity, entry).isEmpty()) {
            onReady.run();
            return;
        }

        mActivity.showToast("正在预检服务器指纹...", false);
        runSshBackgroundTask("ssh-profile-trust-probe", () -> {
            SftpProtocolManager.ProbeResult probe = SftpProtocolManager.getInstance().probeSession(mActivity, entry);
            mActivity.runOnUiThread(() -> {
                SshPendingTrustRecord refreshedPending = coordinator.getPendingTrustForEntry(mActivity, entry);
                if (refreshedPending != null) {
                    mActivity.showToast(
                        refreshedPending.replacementRequired
                            ? "检测到服务器指纹变化，请先替换指纹。"
                            : "首次检测到服务器指纹，请先批准后再连接。",
                        true
                    );
                    showTrustManagerDialog(profile, entry);
                    return;
                }
                if (probe.success) {
                    onReady.run();
                } else {
                    mActivity.showToast(probe.messageCn, true);
                }
            });
        });
    }

    private void loadTmuxSessionsAndShowPersistenceDialog(@NonNull SshProfile profile,
                                                          @NonNull SshLaunchConfig config) {
        mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_loading_tmux), false);
        mSshTmuxRuntimeEngine.loadRemoteTmuxSessions(config.sshCommand, result -> {
            if (result.tmuxMissing) {
                showTmuxMissingForProfileDialog(profile, config);
                return;
            }

            if (!result.listDone && result.sessions.isEmpty()) {
                mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_list_failed), true);
                return;
            }

            showSshPersistenceTmuxDialog(profile, config, toAppRemoteTmuxSessions(result.sessions));
        });
    }

    private void showTmuxMissingForProfileDialog(@NonNull SshProfile profile, @NonNull SshLaunchConfig config) {
        final String installCommand = buildTmuxInstallCommand(config.sshCommand);
        new AlertDialog.Builder(mActivity)
            .setTitle(R.string.title_ssh_persistence_tmux_missing)
            .setMessage(mActivity.getString(R.string.msg_ssh_persistence_tmux_missing_with_cmd, installCommand))
            .setPositiveButton(R.string.action_ssh_persistence_install, (dialog, which) -> {
                mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_installing_tmux), true);
                mSshTmuxRuntimeEngine.installTmux(config.sshCommand, result -> {
                    if (result.code == SshTmuxOperationResult.Code.SUCCESS) {
                        showSshPersistenceManagerDialog(profile);
                    } else {
                        mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_install_failed), true);
                    }
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showSshPersistenceTmuxDialog(@NonNull SshProfile profile, @NonNull SshLaunchConfig config,
                                              @NonNull ArrayList<RemoteTmuxSessionInfo> sessions) {
        ScrollView scrollView = new ScrollView(mActivity);
        LinearLayout container = new LinearLayout(mActivity);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(14);
        container.setPadding(padding, padding, padding, padding);
        scrollView.addView(container);

        TextView hint = new TextView(mActivity);
        String target = TextUtils.isEmpty(config.targetLabel) ? profile.displayName : config.targetLabel;
        if (sessions.isEmpty()) {
            hint.setText(mActivity.getString(R.string.msg_ssh_persistence_tmux_empty));
        } else {
            hint.setText(target);
        }
        hint.setTextSize(13f);
        hint.setPadding(0, 0, 0, dp(10));
        container.addView(hint);

        final AlertDialog[] dialogRef = new AlertDialog[1];
        String currentTmuxSession = findCurrentActiveTmuxSession(config);
        for (RemoteTmuxSessionInfo info : sessions) {
            addTmuxSessionCardView(container, profile, config, info, dialogRef, currentTmuxSession);
        }

        AlertDialog dialog = new AlertDialog.Builder(mActivity)
            .setTitle(R.string.title_ssh_persistence_manager)
            .setView(scrollView)
            .setPositiveButton(R.string.action_ssh_persistence_new,
                (d, which) -> showCreateTmuxSessionDialog(profile, config))
            .setNeutralButton(R.string.action_ssh_persistence_refresh,
                (d, which) -> showSshPersistenceManagerDialog(profile))
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialogRef[0] = dialog;
        dialog.show();
        applyLargePanelLayout(dialog, 0.96f, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void addTmuxSessionCardView(@NonNull LinearLayout parent, @NonNull SshProfile profile,
                                        @NonNull SshLaunchConfig config, @NonNull RemoteTmuxSessionInfo info,
                                        @NonNull AlertDialog[] dialogRef,
                                        @Nullable String currentTmuxSession) {
        boolean isCurrent = !TextUtils.isEmpty(currentTmuxSession) && currentTmuxSession.equals(info.name);
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        int cardPadding = dp(12);
        card.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);
        if (isCurrent) {
            card.setBackgroundColor(0x334CAF50);
        }

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(8);
        parent.addView(card, cardLp);

        TextView title = new TextView(mActivity);
        title.setText(isCurrent
            ? mActivity.getString(R.string.msg_ssh_persistence_current_badge) + " · " + info.displayName
            : info.displayName);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView subtitle = new TextView(mActivity);
        String attached = mActivity.getString(info.attached
            ? R.string.msg_ssh_persistence_tty_attached
            : R.string.msg_ssh_persistence_tty_detached);
        String subtitleText = mActivity.getString(R.string.msg_ssh_persistence_tty_windows, info.windows) + "  ·  " + attached;
        if (!TextUtils.equals(info.displayName, info.name)) {
            subtitleText = subtitleText + "  ·  " + info.name;
        }
        if (isCurrent) {
            subtitleText = subtitleText + "  ·  " + mActivity.getString(R.string.msg_ssh_persistence_tty_current);
        }
        subtitle.setText(subtitleText);
        subtitle.setTextSize(12f);
        subtitle.setAlpha(0.78f);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleLp.topMargin = dp(4);
        card.addView(subtitle, subtitleLp);

        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(10);
        card.addView(row, rowLp);

        Button connectBtn = createProfileActionButton(R.string.action_ssh_persistence_connect, false);
        connectBtn.setOnClickListener(v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            connectToPersistentTmuxSession(config, info.name, info.displayName);
        });
        row.addView(connectBtn);

        Button destroyBtn = createProfileActionButton(R.string.action_ssh_persistence_destroy, true);
        destroyBtn.setOnClickListener(v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            showDestroyTmuxSessionConfirmDialog(profile, config, info.name);
        });
        row.addView(destroyBtn);
    }

    @Nullable
    private String findCurrentActiveTmuxSession(@NonNull SshLaunchConfig config) {
        TerminalSession currentSession = mActivity.getCurrentSession();
        if (currentSession == null) return null;

        ArrayList<SshPersistenceRecord> records = loadSshPersistenceRecords();
        int index = findSshPersistenceRecordIndexForSession(currentSession, records);
        if (index < 0 || index >= records.size()) return null;

        SshPersistenceRecord record = normalizeSshPersistenceRecord(records.get(index));
        String targetSshCommand = sanitizeSshBootstrapCommand(config.sshCommand);
        if (!targetSshCommand.equals(record.sshCommand)) return null;

        return record.tmuxSession;
    }

    private void showCreateTmuxSessionDialog(@NonNull SshProfile profile, @NonNull SshLaunchConfig config) {
        EditText input = new EditText(mActivity);
        input.setHint(R.string.hint_ssh_persistence_new_session_name);
        input.setSingleLine(true);
        input.setText(config.sessionName);
        input.setSelection(input.getText() == null ? 0 : input.getText().length());

        new AlertDialog.Builder(mActivity)
            .setTitle(R.string.title_ssh_persistence_new_session)
            .setView(input)
            .setPositiveButton(R.string.action_ssh_persistence_new, (dialog, which) -> {
                String rawName = input.getText() == null ? "" : input.getText().toString().trim();
                createRemoteTmuxSessionAndConnect(profile, config, rawName);
            })
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> showSshPersistenceManagerDialog(profile))
            .show();
    }

    private void createRemoteTmuxSessionAndConnect(@NonNull SshProfile profile, @NonNull SshLaunchConfig config,
                                                   @Nullable String requestedDisplayName) {
        TerminalSession anchorSession = mActivity.getCurrentSession();
        mSshTmuxRuntimeEngine.createRemoteTmuxSessionAndConnect(
            anchorSession, config.sshCommand, config.sessionName, requestedDisplayName, result -> {
                if (result.code == SshTmuxOperationResult.Code.TMUX_MISSING) {
                    showTmuxMissingForProfileDialog(profile, config);
                    return;
                }

                if (result.code == SshTmuxOperationResult.Code.SUCCESS) {
                    mActivity.showToast(mActivity.getString(
                        R.string.msg_ssh_persistence_created_connecting, result.displayName), false);
                    return;
                }

                mActivity.showToast(mActivity.getString(
                    R.string.msg_ssh_persistence_create_failed, summarizeCommandResult(toCommandResult(result.commandResult))), true);
                showSshPersistenceManagerDialog(profile);
            });
    }

    private void createRemoteTmuxSessionAndConnect(@NonNull SshProfile profile, @NonNull SshLaunchConfig config,
                                                   @NonNull String tmuxSession, @NonNull String displayName) {
        createRemoteTmuxSessionAndConnect(profile, config, displayName);
    }

    private void destroyRemoteTmuxSession(@NonNull SshProfile profile, @NonNull SshLaunchConfig config,
                                          @NonNull String tmuxSession) {
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        mSshTmuxRuntimeEngine.destroyRemoteTmuxSession(config.sshCommand, safeTmuxSession, safeTmuxSession, result -> {
            if (result.code == SshTmuxOperationResult.Code.TMUX_MISSING) {
                showTmuxMissingForProfileDialog(profile, config);
                return;
            }

            if (result.code == SshTmuxOperationResult.Code.SUCCESS) {
                mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_destroyed, safeTmuxSession), false);
            } else {
                mActivity.showToast(mActivity.getString(
                    R.string.msg_ssh_persistence_destroy_failed, summarizeCommandResult(toCommandResult(result.commandResult))), true);
            }

            showSshPersistenceManagerDialog(profile);
        });
    }

    private void showDestroyTmuxSessionConfirmDialog(@NonNull SshProfile profile, @NonNull SshLaunchConfig config,
                                                     @NonNull String tmuxSession) {
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        new AlertDialog.Builder(mActivity)
            .setMessage(mActivity.getString(R.string.msg_ssh_persistence_destroy_confirm, safeTmuxSession))
            .setPositiveButton(android.R.string.ok, (dialog, which) ->
                destroyRemoteTmuxSession(profile, config, safeTmuxSession))
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> showSshPersistenceManagerDialog(profile))
            .show();
    }

    private void connectToPersistentTmuxSession(@NonNull SshLaunchConfig config, @NonNull String tmuxSession,
                                                @NonNull String displayName) {
        TerminalSession anchorSession = mActivity.getCurrentSession();
        if (anchorSession == null) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_launch_failed), true);
            return;
        }

        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String normalizedDisplayName = normalizeDisplayName(displayName, safeTmuxSession);
        mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_connecting_tmux, normalizedDisplayName), false);
        mSshTmuxRuntimeEngine.connectToPersistentTmuxSession(
            anchorSession, config.sshCommand, safeTmuxSession, normalizedDisplayName, result -> {
                if (result.code != SshTmuxOperationResult.Code.SUCCESS) {
                    mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_tmux_check_failed), true);
                }
            });
    }

    private void cleanupPersistenceRecordsForRemoteTmux(@NonNull String sshCommand, @NonNull String tmuxSession) {
        synchronized (mSshPersistRecordsLock) {
            String normalizedSshCommand = sanitizeSshBootstrapCommand(sshCommand);
            String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
            ArrayList<SshPersistenceRecord> records = loadSshPersistenceRecords();
            boolean changed = false;
            for (int i = records.size() - 1; i >= 0; i--) {
                SshPersistenceRecord record = normalizeSshPersistenceRecord(records.get(i));
                if (safeTmuxSession.equals(record.tmuxSession) && normalizedSshCommand.equals(record.sshCommand)) {
                    records.remove(i);
                    changed = true;
                }
            }
            if (changed) saveSshPersistenceRecords(records);
        }
    }

    private void applyPinnedSessionDisplayName(@Nullable TerminalSession session, @Nullable String displayName) {
        if (session == null) return;
        session.mSessionName = normalizeDisplayName(displayName, session.getTitle());
    }

    private void syncPinnedSessionDisplayNameAsync(@NonNull SshPersistenceRecord record) {
        runSshBackgroundTask("tmux-sync-display-name", () ->
            runBashCommandSync(buildTmuxDisplaySyncRemoteExecCommand(
                record.sshCommand, record.tmuxSession, record.displayName)));
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
            int marker = trimmed.indexOf(TMUX_LIST_ITEM_PREFIX);
            if (marker < 0) continue;
            String payload = trimmed.substring(marker + TMUX_LIST_ITEM_PREFIX.length());
            String[] parts = payload.split("\\|", -1);
            if (parts.length < 3) continue;

            String sessionName = normalizeTmuxSessionName(parts[0]);
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

    @NonNull
    private String summarizeCommandResult(@NonNull CommandResult result) {
        String combined = getCombinedOutput(result);
        if (TextUtils.isEmpty(combined)) return "exit " + result.exitCode;
        return trimForDialog(combined, 160);
    }

    @Nullable
    private SshLaunchConfig resolveSshLaunchConfig(@NonNull SshProfile profile) {
        String hostInput = profile.host == null ? "" : profile.host.trim();
        if (hostInput.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_invalid), true);
            return null;
        }

        String sessionName = profile.displayName;
        if (TextUtils.isEmpty(sessionName)) sessionName = "ssh";
        final String targetLabel;
        final String sshCommand;

        if (isRawSshCommand(hostInput)) {
            targetLabel = hostInput;
            sshCommand = buildRawSshCommandForProfile(profile, hostInput);
        } else {
            ResolvedSshTarget target = resolveSshTarget(profile);
            if (target == null) {
                mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_invalid), true);
                return null;
            }

            if (!TextUtils.isEmpty(profile.password) && TextUtils.isEmpty(target.user)) {
                mActivity.showToast(mActivity.getString(R.string.msg_ssh_saved_password_requires_username), true);
                return null;
            }

            targetLabel = target.targetArg;
            sshCommand = buildSshCommandForProfile(profile, target);
        }

        String managedSshCommand = SshCommandKnownHostsOptions.inject(
            sshCommand,
            SshKnownHostsFiles.resolveManagedKnownHostsPath(mActivity)
        );

        if (TextUtils.isEmpty(managedSshCommand)) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_invalid), true);
            return null;
        }

        SshProfileResolutionResult resolution = LegacySshCommandProfileResolver.resolve(profile.id, managedSshCommand);
        if (!resolution.success || resolution.endpoint == null) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_invalid), true);
            return null;
        }

        return new SshLaunchConfig(sessionName, targetLabel, managedSshCommand, resolution.endpoint);
    }

    private void launchSshProfileSession(@NonNull String sessionName, @NonNull String targetLabel,
                                         @NonNull String sshCommand) {
        if (TextUtils.isEmpty(sshCommand)) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_invalid), true);
            return;
        }

        mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_connecting, targetLabel), false);
        String wrappedCommand = wrapSshCommandWithFailureDiagnostics(targetLabel, sshCommand);
        if (!addNewSshSession(sessionName, wrappedCommand, sshCommand)) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_launch_failed), true);
        }
    }

    private void ensureSshpassAndRunIfNeeded(@NonNull SshProfile profile, @NonNull Runnable onReady) {
        if (TextUtils.isEmpty(profile.password)) {
            onReady.run();
            return;
        }
        mActivity.showToast(mActivity.getString(R.string.msg_ssh_checking_sshpass), false);
        runSshBackgroundTask("sshpass-check", () -> {
            boolean hasSshpass = runBashCommandSync(SSHPASS_CHECK_COMMAND).isSuccess();
            mActivity.runOnUiThread(() -> {
                if (hasSshpass) {
                    onReady.run();
                } else {
                    showSshpassInstallDialog(onReady);
                }
            });
        });
    }

    private void showSshpassInstallDialog(@NonNull Runnable onReady) {
        String message = mActivity.getString(R.string.msg_sshpass_required_with_cmd, SSHPASS_INSTALL_COMMAND);
        new AlertDialog.Builder(mActivity)
            .setTitle(R.string.title_sshpass_required)
            .setMessage(message)
            .setPositiveButton(R.string.action_ssh_persistence_install,
                (dialog, which) -> installSshpassAndRun(onReady))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void installSshpassAndRun(@NonNull Runnable onReady) {
        mActivity.showToast(mActivity.getString(R.string.msg_sshpass_installing), true);
        runSshBackgroundTask("sshpass-install", () -> {
            CommandResult installResult = runBashCommandSync(SSHPASS_INSTALL_COMMAND);
            boolean hasSshpass = runBashCommandSync(SSHPASS_CHECK_COMMAND).isSuccess();
            mActivity.runOnUiThread(() -> {
                if (installResult.isSuccess() && hasSshpass) {
                    onReady.run();
                } else {
                    mActivity.showToast(mActivity.getString(R.string.msg_sshpass_install_failed), true);
                }
            });
        });
    }

    private boolean isRawSshCommand(@NonNull String hostInput) {
        String trimmed = hostInput.trim();
        if (trimmed.isEmpty()) return false;
        return trimmed.toLowerCase(Locale.ROOT).startsWith("ssh ");
    }

    @NonNull
    private String buildRawSshCommandForProfile(@NonNull SshProfile profile, @NonNull String rawSshCommand) {
        String command = rawSshCommand.trim();
        if (command.isEmpty()) return "";
        if (TextUtils.isEmpty(profile.password)) return command;
        return "sshpass -p " + quoteArg(profile.password) + " " + command;
    }

    @Nullable
    private ResolvedSshTarget resolveSshTarget(@NonNull SshProfile profile) {
        String host = profile.host == null ? "" : profile.host.trim();
        String user = profile.user == null ? "" : profile.user.trim();
        String targetArg;
        if (host.isEmpty()) return null;
        if (host.contains(" ")) return null;

        int at = host.lastIndexOf('@');
        if (at > 0 && at < host.length() - 1) {
            user = host.substring(0, at).trim();
            host = host.substring(at + 1).trim();
            targetArg = profile.host == null ? "" : profile.host.trim();
        } else {
            targetArg = TextUtils.isEmpty(user) ? host : (user + "@" + host);
        }

        if (host.isEmpty()) return null;
        return new ResolvedSshTarget(user, host, targetArg);
    }

    // Extension point: this method is the single place to customize how SSH session command is built.
    @NonNull
    private String buildSshCommandForProfile(@NonNull SshProfile profile, @NonNull ResolvedSshTarget target) {
        if (TextUtils.isEmpty(target.host)) return "";

        StringBuilder base = new StringBuilder("ssh");
        if (profile.port > 0 && profile.port != 22) {
            base.append(" -p ").append(profile.port);
        }
        if (!TextUtils.isEmpty(profile.extraOptions)) {
            base.append(" ").append(profile.extraOptions.trim());
        }

        base.append(" ").append(quoteArg(target.targetArg));
        String baseCommand = base.toString();

        if (TextUtils.isEmpty(profile.password)) {
            return baseCommand;
        }

        return "sshpass -p " + quoteArg(profile.password) + " " + baseCommand;
    }

    @NonNull
    private String wrapSshCommandWithFailureDiagnostics(@NonNull String targetLabel, @NonNull String sshCommand) {
        String safeTargetLabel = escapeForDoubleQuotes(targetLabel);
        String quotedSshCommand = quoteArg(sshCommand);

        return "TERMUX_SSH_TARGET=\"" + safeTargetLabel + "\"; " +
            "TERMUX_SSH_ERR_FILE=\"$(mktemp -t termux-ssh.err.XXXXXX 2>/dev/null || mktemp)\"; " +
            "echo \"[SSH] 正在连接: ${TERMUX_SSH_TARGET}\"; " +
            "(bash -lc " + quotedSshCommand + ") 2> >(tee \"$TERMUX_SSH_ERR_FILE\" >&2); " +
            "TERMUX_SSH_CODE=$?; " +
            "if [ \"$TERMUX_SSH_CODE\" -ne 0 ]; then " +
            "echo \"\"; " +
            "echo \"[SSH][CN] 连接失败，返回码: $TERMUX_SSH_CODE\"; " +
            "if [ -s \"$TERMUX_SSH_ERR_FILE\" ]; then " +
            "if grep -qi \"Permission denied\" \"$TERMUX_SSH_ERR_FILE\"; then " +
            "echo \"[SSH][CN] 认证失败：请检查用户名/密码/密钥或服务端认证策略。\"; " +
            "elif grep -Eqi \"Connection timed out|Operation timed out\" \"$TERMUX_SSH_ERR_FILE\"; then " +
            "echo \"[SSH][CN] 连接超时：网络不可达、端口被拦截或 sshd 未监听。\"; " +
            "elif grep -qi \"Connection refused\" \"$TERMUX_SSH_ERR_FILE\"; then " +
            "echo \"[SSH][CN] 连接被拒绝：目标端口未开放或 sshd 未启动。\"; " +
            "elif grep -qi \"No route to host\" \"$TERMUX_SSH_ERR_FILE\"; then " +
            "echo \"[SSH][CN] 无法路由到目标主机。\"; " +
            "elif grep -Eqi \"Could not resolve hostname|Name or service not known|Temporary failure in name resolution\" \"$TERMUX_SSH_ERR_FILE\"; then " +
            "echo \"[SSH][CN] DNS 解析失败。\"; " +
            "elif grep -qi \"Host key verification failed\" \"$TERMUX_SSH_ERR_FILE\"; then " +
            "echo \"[SSH][CN] 主机指纹待批准：请先在“指纹管理”中批准后再连接。\"; " +
            "elif grep -qi \"REMOTE HOST IDENTIFICATION HAS CHANGED\" \"$TERMUX_SSH_ERR_FILE\"; then " +
            "echo \"[SSH][CN] 远程主机指纹发生变化：请先在“指纹管理”中替换后再连接。\"; " +
            "elif grep -qi \"Too many authentication failures\" \"$TERMUX_SSH_ERR_FILE\"; then " +
            "echo \"[SSH][CN] 认证失败次数过多。\"; " +
            "elif grep -qi \"kex_exchange_identification\" \"$TERMUX_SSH_ERR_FILE\"; then " +
            "echo \"[SSH][CN] 握手中断（kex_exchange_identification）。\"; " +
            "else " +
            "echo \"[SSH][CN] 未分类的 SSH 错误。\"; " +
            "fi; " +
            "echo \"[SSH][RAW] --------\"; " +
            "cat \"$TERMUX_SSH_ERR_FILE\"; " +
            "echo \"[SSH][RAW] --------\"; " +
            "else " +
            "if [ \"$TERMUX_SSH_CODE\" -eq 124 ]; then " +
            "echo \"[SSH][CN] 超时（exit 124）。\"; " +
            "fi; " +
            "echo \"[SSH][RAW] <无 stderr 输出>\"; " +
            "fi; " +
            "fi; " +
            "rm -f \"$TERMUX_SSH_ERR_FILE\"; " +
            "exit \"$TERMUX_SSH_CODE\"";
    }

    private static final class ResolvedSshTarget {
        @NonNull final String user;
        @NonNull final String host;
        @NonNull final String targetArg;

        ResolvedSshTarget(@NonNull String user, @NonNull String host, @NonNull String targetArg) {
            this.user = user;
            this.host = host;
            this.targetArg = targetArg;
        }
    }

    private static final class SshLaunchConfig {
        @NonNull final String sessionName;
        @NonNull final String targetLabel;
        @NonNull final String sshCommand;
        @NonNull final ResolvedSshEndpoint endpoint;

        SshLaunchConfig(@NonNull String sessionName, @NonNull String targetLabel,
                        @NonNull String sshCommand, @NonNull ResolvedSshEndpoint endpoint) {
            this.sessionName = sessionName;
            this.targetLabel = targetLabel;
            this.sshCommand = sshCommand;
            this.endpoint = endpoint;
        }
    }

    private static final class RemoteTmuxSessionInfo {
        @NonNull final String name;
        @NonNull final String displayName;
        final int windows;
        final boolean attached;

        RemoteTmuxSessionInfo(@NonNull String name, @NonNull String displayName, int windows, boolean attached) {
            this.name = name;
            this.displayName = displayName;
            this.windows = windows <= 0 ? 1 : windows;
            this.attached = attached;
        }
    }

    private boolean addNewSshSession(@NonNull String sessionName, @NonNull String shellCommand,
                                     @Nullable String bootstrapSshCommand) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return false;

        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) {
            new AlertDialog.Builder(mActivity)
                .setTitle(R.string.title_max_terminals_reached)
                .setMessage(R.string.msg_max_terminals_reached)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return false;
        }

        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bash).exists()) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_shell_unavailable), true);
            return false;
        }

        String workingDirectory = mActivity.getProperties().getDefaultWorkingDirectory();
        TermuxSession created = service.createTermuxSession(bash, new String[]{"-lc", shellCommand},
            null, workingDirectory, false, sessionName);
        if (created == null) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_launch_failed), true);
            return false;
        }

        TerminalSession createdSession = created.getTerminalSession();
        rememberSshBootstrapCommand(createdSession, bootstrapSshCommand);
        setCurrentSession(createdSession);
        termuxSessionListNotifyUpdated();
        mActivity.getDrawer().closeDrawers();
        return true;
    }

    private void rememberSshBootstrapCommand(@Nullable TerminalSession session, @Nullable String sshCommand) {
        mSshTmuxRuntimeEngine.rememberSshBootstrapCommand(session, sshCommand);
    }

    @Nullable
    private String getRememberedSshBootstrapCommand(@Nullable TerminalSession session) {
        return mSshTmuxRuntimeEngine.getRememberedSshBootstrapCommand(session);
    }

    private void forgetSshBootstrapCommand(@Nullable TerminalSession session) {
        mSshTmuxRuntimeEngine.forgetSshBootstrapCommand(session);
    }

    @NonNull
    private ArrayList<RemoteTmuxSessionInfo> toAppRemoteTmuxSessions(
        @NonNull ArrayList<com.termux.terminalsessionruntime.RemoteTmuxSessionInfo> sessions) {
        ArrayList<RemoteTmuxSessionInfo> converted = new ArrayList<>(sessions.size());
        for (com.termux.terminalsessionruntime.RemoteTmuxSessionInfo session : sessions) {
            converted.add(new RemoteTmuxSessionInfo(
                session.name, session.displayName, session.windows, session.attached));
        }
        return converted;
    }

    @NonNull
    private ArrayList<ConfigTmuxSessionItem> toConfigTmuxSessionItems(
        @NonNull ArrayList<RemoteTmuxSessionInfo> sessions,
        @Nullable String currentTmuxSession) {
        ArrayList<ConfigTmuxSessionItem> converted = new ArrayList<>(sessions.size());
        for (RemoteTmuxSessionInfo session : sessions) {
            boolean isCurrent = !TextUtils.isEmpty(currentTmuxSession) && currentTmuxSession.equals(session.name);
            String title = isCurrent
                ? mActivity.getString(R.string.msg_ssh_persistence_current_badge) + " · " + session.displayName
                : session.displayName;
            String attached = mActivity.getString(session.attached
                ? R.string.msg_ssh_persistence_tty_attached
                : R.string.msg_ssh_persistence_tty_detached);
            String summary = mActivity.getString(R.string.msg_ssh_persistence_tty_windows, session.windows) + "  ·  " + attached;
            if (!TextUtils.equals(session.displayName, session.name)) {
                summary = summary + "  ·  " + session.name;
            }
            converted.add(new ConfigTmuxSessionItem(session.name, title, summary, isCurrent));
        }
        return converted;
    }

    @NonNull
    private ArrayList<SshPersistenceRecord> toAppSshPersistenceRecords(
        @NonNull ArrayList<com.termux.terminalsessionruntime.SshPersistenceRecord> records) {
        ArrayList<SshPersistenceRecord> converted = new ArrayList<>(records.size());
        for (com.termux.terminalsessionruntime.SshPersistenceRecord record : records) {
            converted.add(new SshPersistenceRecord(
                record.id, record.sshCommand, record.tmuxSession,
                record.displayName, record.shellName, record.lockedHandle));
        }
        return converted;
    }

    @NonNull
    private ArrayList<com.termux.terminalsessionruntime.SshPersistenceRecord> toRuntimeSshPersistenceRecords(
        @NonNull ArrayList<SshPersistenceRecord> records) {
        ArrayList<com.termux.terminalsessionruntime.SshPersistenceRecord> converted = new ArrayList<>(records.size());
        for (SshPersistenceRecord record : records) {
            converted.add(new com.termux.terminalsessionruntime.SshPersistenceRecord(
                record.id, record.sshCommand, record.tmuxSession,
                record.displayName, record.shellName, record.lockedHandle));
        }
        return converted;
    }

    @NonNull
    private CommandResult toCommandResult(@NonNull com.termux.terminalsessionruntime.ShellCommandResult result) {
        return new CommandResult(result.exitCode, result.stdout, result.stderr);
    }

    private ArrayList<String> buildSshProfileLabels(@NonNull ArrayList<SshProfile> profiles) {
        ArrayList<String> labels = new ArrayList<>(profiles.size());
        for (SshProfile p : profiles) {
            StringBuilder line = new StringBuilder();
            line.append(p.displayName);
            String host = p.host == null ? "" : p.host.trim();
            if (isRawSshCommand(host)) {
                line.append("  (").append(host).append(")");
            } else if (!TextUtils.isEmpty(p.user)) {
                line.append("  (").append(p.user).append("@").append(p.host).append(":").append(p.port).append(")");
            } else {
                line.append("  (").append(p.host).append(":").append(p.port).append(")");
            }
            labels.add(line.toString());
        }
        return labels;
    }

    private ArrayList<SshProfile> loadSshProfiles() {
        ArrayList<SshProfile> profiles = new ArrayList<>();
        SharedPreferences p = getSshPersistPrefs();
        if (p == null) return profiles;

        String raw = p.getString(KEY_SSH_PROFILES_JSON, "[]");
        if (TextUtils.isEmpty(raw)) return profiles;
        try {
            JSONArray json = new JSONArray(raw);
            for (int i = 0; i < json.length(); i++) {
                JSONObject item = json.optJSONObject(i);
                if (item == null) continue;
                SshProfile profile = SshProfile.fromJson(item);
                if (profile == null) continue;
                profiles.add(profile);
            }
        } catch (Exception ignored) {
        }
        return profiles;
    }

    private void saveSshProfiles(@NonNull ArrayList<SshProfile> profiles) {
        SharedPreferences p = getSshPersistPrefs();
        if (p == null) return;

        JSONArray json = new JSONArray();
        for (SshProfile profile : profiles) {
            json.put(profile.toJson());
        }
        p.edit().putString(KEY_SSH_PROFILES_JSON, json.toString()).apply();
    }

    private int dp(int value) {
        return Math.round(mActivity.getResources().getDisplayMetrics().density * value);
    }

    // Extension point: add future fields here (e.g. private key path, jump host, proxy options).
    private static final class SshProfile {
        @NonNull final String id;
        @NonNull final String displayName;
        @NonNull final String host;
        final int port;
        @NonNull final String user;
        @NonNull final String password;
        @NonNull final String extraOptions;

        SshProfile(@NonNull String id, @NonNull String displayName, @NonNull String host, int port,
                   @NonNull String user, @NonNull String password, @NonNull String extraOptions) {
            this.id = id;
            this.displayName = displayName;
            this.host = host;
            this.port = port <= 0 ? 22 : port;
            this.user = user;
            this.password = password;
            this.extraOptions = extraOptions;
        }

        @Nullable
        static SshProfile fromJson(@NonNull JSONObject json) {
            String id = json.optString("id", "").trim();
            String host = json.optString("host", "").trim();
            if (host.isEmpty()) host = json.optString("sshCommand", "").trim();
            if (host.isEmpty()) return null;

            if (id.isEmpty()) id = UUID.randomUUID().toString();
            String user = json.optString("user", "").trim();
            int port = json.optInt("port", 22);
            if (port <= 0) port = 22;
            String displayName = json.optString("displayName", "").trim();
            if (displayName.isEmpty()) {
                String lower = host.toLowerCase(Locale.ROOT);
                if (lower.startsWith("ssh ")) {
                    displayName = host;
                } else {
                    String userPrefix = user.isEmpty() ? "" : user + "@";
                    displayName = userPrefix + host + ":" + port;
                }
            }

            String password = json.optString("password", "");
            String extraOptions = json.optString("extraOptions", "").trim();
            return new SshProfile(id, displayName, host, port, user, password, extraOptions);
        }

        @NonNull
        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id);
                json.put("displayName", displayName);
                json.put("host", host);
                json.put("sshCommand", host);
                json.put("port", port);
                json.put("user", user);
                json.put("password", password);
                json.put("extraOptions", extraOptions);
            } catch (Exception ignored) {
            }
            return json;
        }
    }

    private static final class SshPersistenceRecord {
        @NonNull final String id;
        @NonNull final String sshCommand;
        @NonNull final String tmuxSession;
        @NonNull final String displayName;
        @NonNull final String shellName;
        @Nullable final String lockedHandle;

        SshPersistenceRecord(@NonNull String id, @NonNull String sshCommand, @NonNull String tmuxSession,
                             @NonNull String displayName, @NonNull String shellName, @Nullable String lockedHandle) {
            this.id = id;
            this.sshCommand = sshCommand;
            this.tmuxSession = tmuxSession;
            this.displayName = displayName;
            this.shellName = shellName;
            this.lockedHandle = lockedHandle;
        }

        @Nullable
        static SshPersistenceRecord fromJson(@NonNull JSONObject json) {
            String sshCommand = json.optString("sshCommand", "").trim();
            if (sshCommand.isEmpty()) return null;

            String id = json.optString("id", "").trim();
            if (id.isEmpty()) id = UUID.randomUUID().toString();

            String tmuxSession = json.optString("tmuxSession", "").trim();
            String displayName = json.optString("displayName", "").trim();
            String shellName = json.optString("shellName", "").trim();
            String lockedHandle = json.optString("lockedHandle", "").trim();
            if (lockedHandle.isEmpty()) lockedHandle = null;

            return new SshPersistenceRecord(id, sshCommand, tmuxSession, displayName, shellName, lockedHandle);
        }

        @NonNull
        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id);
                json.put("sshCommand", sshCommand);
                json.put("tmuxSession", tmuxSession);
                json.put("displayName", displayName);
                json.put("shellName", shellName);
                json.put("lockedHandle", lockedHandle == null ? JSONObject.NULL : lockedHandle);
            } catch (Exception ignored) {
            }
            return json;
        }
    }

    @NonNull
    private ArrayList<SshPersistenceRecord> loadSshPersistenceRecords() {
        return toAppSshPersistenceRecords(mSshTmuxRuntimeEngine.loadRecords());
    }

    private void saveSshPersistenceRecords(@NonNull ArrayList<SshPersistenceRecord> records) {
        mSshTmuxRuntimeEngine.saveRecords(toRuntimeSshPersistenceRecords(records));
    }

    private void saveSshPersistenceRecordsLocked(@NonNull ArrayList<SshPersistenceRecord> records,
                                                 @NonNull SharedPreferences prefs) {
        ArrayList<SshPersistenceRecord> deduped = dedupeSshPersistenceRecords(records);
        JSONArray json = new JSONArray();
        for (SshPersistenceRecord record : deduped) {
            json.put(record.toJson());
        }
        prefs.edit()
            .putString(KEY_SSH_PERSIST_RECORDS_JSON, json.toString())
            .putBoolean(KEY_SSH_PERSIST_ENABLED, !deduped.isEmpty())
            .apply();
        mSshPersistRecordsCache = new ArrayList<>(deduped);
    }

    @NonNull
    private SshPersistenceRecord normalizeSshPersistenceRecord(@NonNull SshPersistenceRecord record) {
        String id = record.id.trim();
        if (id.isEmpty()) id = UUID.randomUUID().toString();

        String tmuxSession = normalizeTmuxSessionName(record.tmuxSession);
        String displayName = SshTmuxSessionStateMachine.resolveExistingRemote(
            tmuxSession, null, record.displayName, null, null).displayName;
        String shellName = record.shellName == null ? "" : record.shellName.trim();
        if (shellName.isEmpty()) shellName = buildSshPersistShellName(id);

        String sshCommand = sanitizeSshBootstrapCommand(record.sshCommand == null ? "" : record.sshCommand.trim());
        return new SshPersistenceRecord(id, sshCommand, tmuxSession, displayName, shellName, record.lockedHandle);
    }

    @NonNull
    private String buildSshPersistShellName(@NonNull String id) {
        String tail = id.replaceAll("[^A-Za-z0-9]", "");
        if (tail.isEmpty()) tail = Long.toHexString(System.currentTimeMillis());
        if (tail.length() > 12) tail = tail.substring(0, 12);
        return SSH_PERSIST_SHELL_NAME_PREFIX + tail;
    }

    private void upsertSshPersistenceRecord(@NonNull SshPersistenceRecord record) {
        synchronized (mSshPersistRecordsLock) {
            ArrayList<SshPersistenceRecord> records = loadSshPersistenceRecords();
            SshPersistenceRecord normalized = normalizeSshPersistenceRecord(record);
            for (int i = records.size() - 1; i >= 0; i--) {
                if (normalized.id.equals(records.get(i).id)) records.remove(i);
            }
            records.add(normalized);
            saveSshPersistenceRecords(records);
        }
    }

    @NonNull
    private String buildSshPersistenceRemoteKey(@Nullable String sshCommand, @Nullable String tmuxSession) {
        return sanitizeSshBootstrapCommand(sshCommand == null ? "" : sshCommand) + "\n" +
            normalizeTmuxSessionName(tmuxSession == null ? "" : tmuxSession);
    }

    private int findSshPersistenceRecordIndexByRemote(@NonNull ArrayList<SshPersistenceRecord> records,
                                                      @NonNull String sshCommand, @NonNull String tmuxSession) {
        String targetKey = buildSshPersistenceRemoteKey(sshCommand, tmuxSession);
        for (int i = 0; i < records.size(); i++) {
            SshPersistenceRecord normalized = normalizeSshPersistenceRecord(records.get(i));
            if (targetKey.equals(buildSshPersistenceRemoteKey(normalized.sshCommand, normalized.tmuxSession))) return i;
        }
        return -1;
    }

    @NonNull
    private ArrayList<SshPersistenceRecord> dedupeSshPersistenceRecords(@NonNull ArrayList<SshPersistenceRecord> records) {
        ArrayList<SshPersistenceRecord> deduped = new ArrayList<>();
        for (SshPersistenceRecord raw : records) {
            SshPersistenceRecord normalized = normalizeSshPersistenceRecord(raw);
            if (TextUtils.isEmpty(normalized.sshCommand)) continue;

            int existingIndex = findSshPersistenceRecordIndexByRemote(
                deduped, normalized.sshCommand, normalized.tmuxSession);
            if (existingIndex < 0) {
                deduped.add(normalized);
            } else {
                deduped.set(existingIndex,
                    mergeSshPersistenceRecordsForSameRemote(deduped.get(existingIndex), normalized));
            }
        }

        HashSet<String> usedIds = new HashSet<>();
        HashSet<String> usedShellNames = new HashSet<>();
        ArrayList<SshPersistenceRecord> normalizedList = new ArrayList<>(deduped.size());
        for (SshPersistenceRecord record : deduped) {
            String id = record.id == null ? "" : record.id.trim();
            if (id.isEmpty() || usedIds.contains(id)) id = UUID.randomUUID().toString();
            usedIds.add(id);

            String shellName = record.shellName == null ? "" : record.shellName.trim();
            if (shellName.isEmpty() || usedShellNames.contains(shellName)) shellName = buildSshPersistShellName(id);
            usedShellNames.add(shellName);

            normalizedList.add(new SshPersistenceRecord(
                id, record.sshCommand, record.tmuxSession, record.displayName, shellName, record.lockedHandle));
        }

        return normalizedList;
    }

    @NonNull
    private SshPersistenceRecord mergeSshPersistenceRecordsForSameRemote(@NonNull SshPersistenceRecord a,
                                                                         @NonNull SshPersistenceRecord b) {
        SshPersistenceRecord left = normalizeSshPersistenceRecord(a);
        SshPersistenceRecord right = normalizeSshPersistenceRecord(b);
        int leftScore = scoreSshPersistenceRecord(left);
        int rightScore = scoreSshPersistenceRecord(right);

        SshPersistenceRecord primary = rightScore >= leftScore ? right : left;
        SshPersistenceRecord secondary = primary == left ? right : left;

        String shellName = !TextUtils.isEmpty(primary.shellName) ? primary.shellName : secondary.shellName;
        String displayName = !TextUtils.isEmpty(primary.displayName) ? primary.displayName : secondary.displayName;
        String lockedHandle = !TextUtils.isEmpty(primary.lockedHandle) ? primary.lockedHandle : secondary.lockedHandle;

        return new SshPersistenceRecord(
            primary.id, primary.sshCommand, primary.tmuxSession, displayName, shellName, lockedHandle);
    }

    private int scoreSshPersistenceRecord(@NonNull SshPersistenceRecord record) {
        int score = 0;
        if (!TextUtils.isEmpty(record.displayName)) score += 1;
        if (!TextUtils.isEmpty(record.shellName)) score += 1;
        if (!TextUtils.isEmpty(record.lockedHandle)) score += 2;
        return score;
    }

    private boolean areSshPersistenceRecordsEqual(@NonNull ArrayList<SshPersistenceRecord> first,
                                                  @NonNull ArrayList<SshPersistenceRecord> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            SshPersistenceRecord a = first.get(i);
            SshPersistenceRecord b = second.get(i);
            if (!TextUtils.equals(a.id, b.id)) return false;
            if (!TextUtils.equals(a.sshCommand, b.sshCommand)) return false;
            if (!TextUtils.equals(a.tmuxSession, b.tmuxSession)) return false;
            if (!TextUtils.equals(a.displayName, b.displayName)) return false;
            if (!TextUtils.equals(a.shellName, b.shellName)) return false;
            if (!TextUtils.equals(a.lockedHandle, b.lockedHandle)) return false;
        }
        return true;
    }

    private boolean removeSshPersistenceRecordById(@NonNull String id) {
        synchronized (mSshPersistRecordsLock) {
            ArrayList<SshPersistenceRecord> records = loadSshPersistenceRecords();
            boolean removed = false;
            for (int i = records.size() - 1; i >= 0; i--) {
                if (id.equals(records.get(i).id)) {
                    records.remove(i);
                    removed = true;
                }
            }
            if (removed) saveSshPersistenceRecords(records);
            return removed;
        }
    }

    public void addNewSessionAt(String workingDirectory) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) {
            new AlertDialog.Builder(mActivity)
                .setTitle(R.string.title_max_terminals_reached)
                .setMessage(R.string.msg_max_terminals_reached)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        } else {
            boolean isFailSafe = false;
            TermuxSession newTermuxSession;
            if (shouldStartProotByDefault()) {
                String distro = getProotDefaultDistro();
                String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
                String prootCmd = buildProotInteractiveCommand(distro);
                String[] args = new String[]{"-lc", prootCmd};
                String name = "proot-" + distro;
                newTermuxSession = service.createTermuxSession(bash, args, null, workingDirectory, isFailSafe, name);
            } else {
                newTermuxSession = service.createTermuxSession(null, null, null, workingDirectory, isFailSafe, null);
            }
            if (newTermuxSession == null) return;

            TerminalSession newTerminalSession = newTermuxSession.getTerminalSession();
            setCurrentSession(newTerminalSession);

            mActivity.getDrawer().closeDrawers();
        }
    }

    private void maybeAutoSwitchToProotSession() {
        if (!shouldStartProotByDefault()) return;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        String distro = getProotDefaultDistro();
        String targetName = "proot-" + distro;

        TerminalSession current = mActivity.getCurrentSession();
        if (current != null && targetName.equals(current.mSessionName)) return;

        TermuxSession existing = service.getTermuxSessionForShellName(targetName);
        if (existing != null) {
            setCurrentSession(existing.getTerminalSession());
            return;
        }

        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) return;

        String workingDirectory = mActivity.getProperties().getDefaultWorkingDirectory();
        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        String prootCmd = buildProotInteractiveCommand(distro);
        String[] args = new String[]{"-lc", prootCmd};
        TermuxSession created = service.createTermuxSession(bash, args, null, workingDirectory, false, targetName);
        if (created != null) {
            setCurrentSession(created.getTerminalSession());
            termuxSessionListNotifyUpdated();
        }
    }

    private String buildProotInteractiveCommand(String distro) {
        String envPrefix = "export HOME=${HOME:-/root}; " +
            "export PATH=$HOME/.local/share/mise/shims:$HOME/.local/bin:$HOME/.local/share/mise/bin:" +
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; ";
        String inner = envPrefix + "exec /usr/bin/bash -i";
        inner = inner.replace("'", "'\"'\"'");
        return "proot-distro login " + distro + " -- /usr/bin/bash -lc '" + inner + "'";
    }

    private boolean shouldStartProotByDefault() {
        SharedPreferences p = getUiPanelPrefs();
        if (p == null) return false;
        if (!p.getBoolean("proot.enabled", false)) return false;

        File prootDistro = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/proot-distro");
        if (!prootDistro.exists()) return false;

        String distro = getProotDefaultDistro();
        File rootfs = new File(TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH + "/lib/proot-distro/installed-rootfs/" + distro);
        return rootfs.exists() && rootfs.isDirectory();
    }

    private String getProotDefaultDistro() {
        SharedPreferences p = getUiPanelPrefs();
        if (p == null) return "ubuntu";
        String v = p.getString("proot.default_distro", "ubuntu");
        return v != null && !v.trim().isEmpty() ? v.trim() : "ubuntu";
    }

    private SharedPreferences getUiPanelPrefs() {
        Context c = mActivity.getApplicationContext();
        return c.getSharedPreferences("ui_panel_prefs", Context.MODE_PRIVATE);
    }

    private static final class CodexRestoreRecord {
        @NonNull final String threadId;
        @NonNull final String workingDirectory;
        @NonNull final String rolloutPath;
        @NonNull final String title;
        final int pid;
        final int order;
        final long updatedAt;

        CodexRestoreRecord(@NonNull String threadId, @NonNull String workingDirectory,
                           @NonNull String rolloutPath,
                           @NonNull String title, int pid, int order,
                           long updatedAt) {
            this.threadId = threadId;
            this.workingDirectory = workingDirectory;
            this.rolloutPath = rolloutPath;
            this.title = title;
            this.pid = pid;
            this.order = order;
            this.updatedAt = updatedAt;
        }

    }

    private static final class TermuxRestoreState {
        @NonNull final ArrayList<TermuxRestoreRecord> records;
        @NonNull final String foregroundKey;
        @NonNull final String foregroundHandle;
        final int foregroundOrder;

        TermuxRestoreState(@NonNull ArrayList<TermuxRestoreRecord> records,
                           @NonNull String foregroundKey,
                           @NonNull String foregroundHandle) {
            this(records, foregroundKey, foregroundHandle, Integer.MAX_VALUE);
        }

        TermuxRestoreState(@NonNull ArrayList<TermuxRestoreRecord> records,
                           @NonNull String foregroundKey,
                           @NonNull String foregroundHandle,
                           int foregroundOrder) {
            this.records = records;
            this.foregroundKey = foregroundKey;
            this.foregroundHandle = foregroundHandle;
            this.foregroundOrder = foregroundOrder < 0 ? Integer.MAX_VALUE : foregroundOrder;
        }
    }

    private static final class PrunedTermuxRestoreRecords {
        @Nullable final TermuxRestoreRecord foregroundReplacement;
        final boolean removedForeground;
        final int removedForegroundOrder;

        PrunedTermuxRestoreRecords(@Nullable TermuxRestoreRecord foregroundReplacement,
                                   boolean removedForeground,
                                   int removedForegroundOrder) {
            this.foregroundReplacement = foregroundReplacement;
            this.removedForeground = removedForeground;
            this.removedForegroundOrder = removedForegroundOrder;
        }
    }

    private static final class TermuxRestoreRecord {
        @NonNull final String key;
        @NonNull final String type;
        @NonNull final String handle;
        @NonNull final String displayName;
        @NonNull final String workingDirectory;
        @NonNull final String shellName;
        @NonNull final String executable;
        @Nullable final String[] arguments;
        @Nullable final String codexThreadId;
        @Nullable final String codexRolloutPath;
        @Nullable final String sshPersistRecordId;
        @Nullable final String sshCommand;
        @Nullable final String tmuxSession;
        final int order;
        final long updatedAt;

        TermuxRestoreRecord(@NonNull String key, @NonNull String type, @NonNull String handle,
                            @NonNull String displayName, @NonNull String workingDirectory,
                            @NonNull String shellName, @NonNull String executable,
                            @Nullable String[] arguments, @Nullable String codexThreadId,
                            @Nullable String codexRolloutPath,
                            @Nullable String sshPersistRecordId, @Nullable String sshCommand,
                            @Nullable String tmuxSession, int order, long updatedAt) {
            this.key = key;
            this.type = type;
            this.handle = handle;
            this.displayName = displayName;
            this.workingDirectory = workingDirectory;
            this.shellName = shellName;
            this.executable = executable;
            this.arguments = arguments == null ? null : arguments.clone();
            this.codexThreadId = codexThreadId;
            this.codexRolloutPath = codexRolloutPath;
            this.sshPersistRecordId = sshPersistRecordId;
            this.sshCommand = sshCommand;
            this.tmuxSession = tmuxSession;
            this.order = order;
            this.updatedAt = updatedAt;
        }

        @Nullable
        static TermuxRestoreRecord fromJson(@NonNull JSONObject json) {
            String key = json.optString("key", "").trim();
            String type = json.optString("type", "").trim();
            if (TextUtils.isEmpty(key) || !isSupportedTermuxRestoreType(type)) return null;

            JSONArray argsArray = json.optJSONArray("arguments");
            String[] args = null;
            if (argsArray != null) {
                ArrayList<String> list = new ArrayList<>();
                for (int i = 0; i < argsArray.length(); i++) {
                    String arg = argsArray.optString(i, null);
                    if (arg != null) list.add(arg);
                }
                args = list.toArray(new String[0]);
            }

            return new TermuxRestoreRecord(
                key,
                type,
                json.optString("handle", "").trim(),
                json.optString("display_name", "").trim(),
                json.optString("cwd", "").trim(),
                json.optString("shell_name", "").trim(),
                json.optString("executable", "").trim(),
                args,
                optNonEmptyString(json, "codex_thread_id"),
                optNonEmptyString(json, "codex_rollout_path"),
                optNonEmptyString(json, "ssh_persist_record_id"),
                optNonEmptyString(json, "ssh_command"),
                optNonEmptyString(json, "tmux_session"),
                json.optInt("order", Integer.MAX_VALUE),
                json.optLong("updated_at", 0L));
        }

        @NonNull
        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("key", key);
                json.put("type", type);
                json.put("handle", handle);
                json.put("display_name", displayName);
                json.put("cwd", workingDirectory);
                json.put("shell_name", shellName);
                json.put("executable", executable);
                if (arguments != null) {
                    JSONArray array = new JSONArray();
                    for (String arg : arguments) array.put(arg == null ? "" : arg);
                    json.put("arguments", array);
                }
                putNullableString(json, "codex_thread_id", codexThreadId);
                putNullableString(json, "codex_rollout_path", codexRolloutPath);
                putNullableString(json, "ssh_persist_record_id", sshPersistRecordId);
                putNullableString(json, "ssh_command", sshCommand);
                putNullableString(json, "tmux_session", tmuxSession);
                json.put("order", order);
                json.put("updated_at", updatedAt);
            } catch (Exception ignored) {
            }
            return json;
        }

        @Nullable
        private static String optNonEmptyString(@NonNull JSONObject json, @NonNull String key) {
            String value = optJsonRestoreString(json, key);
            return value.isEmpty() ? null : value;
        }

        private static void putNullableString(@NonNull JSONObject json, @NonNull String key,
                                              @Nullable String value) throws Exception {
            String normalized = normalizeNullableRestoreString(value);
            if (TextUtils.isEmpty(normalized)) json.put(key, "");
            else json.put(key, normalized);
        }
    }

    private static boolean isSupportedTermuxRestoreType(@Nullable String type) {
        return TERMUX_RESTORE_TYPE_CODEX.equals(type) ||
            TERMUX_RESTORE_TYPE_SSH_TMUX.equals(type) ||
            TERMUX_RESTORE_TYPE_LOCAL_TMUX.equals(type) ||
            TERMUX_RESTORE_TYPE_SSH.equals(type) ||
            TERMUX_RESTORE_TYPE_PROOT.equals(type) ||
            TERMUX_RESTORE_TYPE_SHELL.equals(type);
    }

    @Nullable
    private TermuxSession createCodexRestoreSession(@NonNull TermuxService service,
                                                    @NonNull CodexRestoreRecord record) {
        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bash).exists()) return null;

        String codexPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/codex";
        boolean validThreadId = TextUtils.equals(record.threadId, normalizeCodexThreadId(record.threadId));
        CodexRestoreStateMachine.RecoveryAction action = CodexRestoreStateMachine.resolveRecovery(
            new CodexRestoreStateMachine.RecoveryInput(
                CodexRestoreStateMachine.RecoveryEvent.COLD_START,
                true,
                validThreadId,
                new File(codexPath).canExecute()));
        if (action != CodexRestoreStateMachine.RecoveryAction.START_CODEX) {
            TermuxSessionRestoreStore.appendCodexAudit(
                "cold_restore_deferred", record.threadId, "", action.name().toLowerCase(Locale.ROOT));
            return null;
        }
        if (!CodexSessionHostProtocol.rolloutMatchesThread(record.rolloutPath, record.threadId)) {
            TermuxSessionRestoreStore.appendCodexAudit(
                "cold_restore_deferred", record.threadId, "", "rollout_identity_invalid");
            return null;
        }

        String workingDirectory = resolveCodexRestoreWorkingDirectory(record.workingDirectory);
        String displayName = buildCodexRestoreDisplayName(record);
        TermuxSessionRestoreStore.appendCodexAudit(
            "cold_restore_start", record.threadId, "", action.name().toLowerCase(Locale.ROOT));
        TermuxSession created = service.createTermuxSession(
            bash,
            new String[]{"-lc", buildCodexRestoreCommand(record, workingDirectory)},
            null,
            workingDirectory,
            false,
            displayName);
        if (created != null && created.getTerminalSession() != null) {
            TerminalSession terminalSession = created.getTerminalSession();
            terminalSession.mSessionName = displayName;
            markCodexRestoreRecordMaterialized(record, terminalSession);
            service.getCodexSessionRecoveryController().onSessionMaterializing(record.threadId, terminalSession);
            scheduleCodexRestoreMaterializationValidation(record, terminalSession);
            TermuxSessionRestoreStore.appendCodexAudit(
                "cold_restore_created", record.threadId, nullToEmpty(terminalSession.mHandle), displayName);
        } else {
            TermuxSessionRestoreStore.appendCodexAudit(
                "cold_restore_failed", record.threadId, "", "terminal session creation failed");
        }
        return created;
    }

    private void markCodexRestoreRecordMaterialized(@NonNull CodexRestoreRecord record,
                                                    @NonNull TerminalSession terminalSession) {
        int pid = findActiveCodexProcessPid(terminalSession);

        CodexRestoreRecord materialized = record;
        int order = getLiveSessionOrder(terminalSession);
        if (order != Integer.MAX_VALUE) materialized = copyCodexRestoreRecordWithOrder(materialized, order);
        materialized = copyCodexRestoreRecordWithPid(materialized, pid > 0 ? pid : -1);
        rememberNativeCodexRestoreRecord(terminalSession, materialized);
    }

    private void scheduleCodexRestoreMaterializationValidation(@NonNull CodexRestoreRecord record,
                                                               @NonNull TerminalSession terminalSession) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            TermuxService service = mActivity.getTermuxService();
            if (service == null || service.getTermuxSessionForTerminalSession(terminalSession) == null) return;

            if (terminalSession.getPid() == 0) {
                TermuxSessionRestoreStore.appendCodexAudit(
                    "cold_restore_start_pending", record.threadId,
                    nullToEmpty(terminalSession.mHandle), "background tab awaits first display");
                persistTermuxSessionRestoreState();
                termuxSessionListNotifyUpdated();
                return;
            }

            int pid = findActiveCodexProcessPid(terminalSession);
            int order = getLiveSessionOrder(terminalSession);
            CodexRestoreRecord validated = record;
            if (order != Integer.MAX_VALUE) validated = copyCodexRestoreRecordWithOrder(validated, order);

            if (pid > 0) {
                rememberNativeCodexRestoreRecord(terminalSession,
                    copyCodexRestoreRecordWithPid(validated, pid));
            } else {
                TermuxSessionRestoreStore.appendCodexAudit(
                    "cold_restore_process_pending", record.threadId,
                    nullToEmpty(terminalSession.mHandle),
                    "awaiting host-ready or recovery-watchdog verification");
            }

            persistTermuxSessionRestoreState();
            termuxSessionListNotifyUpdated();
        }, 3500L);
    }

    @NonNull
    private String buildCodexRestoreCommand(@NonNull CodexRestoreRecord record,
                                            @NonNull String workingDirectory) {
        return CodexSessionRecoveryController.buildRestoreCommand(
            record.threadId, workingDirectory, record.rolloutPath);
    }

    @NonNull
    private CodexRestoreRecord copyCodexRestoreRecordWithPid(@NonNull CodexRestoreRecord record, int pid) {
        return new CodexRestoreRecord(
            record.threadId,
            record.workingDirectory,
            record.rolloutPath,
            record.title,
            pid,
            record.order,
            System.currentTimeMillis() / 1000L);
    }

    @NonNull
    private CodexRestoreRecord copyCodexRestoreRecordWithOrder(@NonNull CodexRestoreRecord record, int order) {
        return new CodexRestoreRecord(
            record.threadId,
            record.workingDirectory,
            record.rolloutPath,
            record.title,
            record.pid,
            normalizeRestoreOrder(order),
            System.currentTimeMillis() / 1000L);
    }

    @Nullable
    private CodexRestoreRecord findCodexRestoreRecordForSession(@Nullable TerminalSession terminalSession) {
        CodexRestoreRecord nativeRecord = findNativeCodexRestoreRecordForSession(terminalSession);
        if (nativeRecord != null) return nativeRecord;
        CodexRestoreRecord persistedRecord = findPersistedCodexRestoreRecordForSession(terminalSession);
        if (persistedRecord != null) return persistedRecord;

        int codexPid = findActiveCodexProcessPid(terminalSession);
        if (codexPid <= 0) return null;
        ArrayList<CodexRestoreRecord> records = loadCodexRestoreRecords();
        for (CodexRestoreRecord record : records) {
            if (record.pid == codexPid) return record;
        }
        return null;
    }

    private void rememberNativeCodexRestoreRecord(@Nullable TerminalSession terminalSession,
                                                  @Nullable CodexRestoreRecord record) {
        if (terminalSession == null || record == null || TextUtils.isEmpty(record.threadId)) return;
        String handle = nullToEmpty(terminalSession.mHandle);
        synchronized (mNativeCodexRestoreLock) {
            mNativeCodexRestoreByThread.put(record.threadId, normalizeCodexRestoreRecord(record));
            if (!TextUtils.isEmpty(handle)) {
                mNativeCodexRestoreThreadByHandle.put(handle, record.threadId);
                mNativeCodexRestoreSuppressedHandles.remove(handle);
            }
        }
    }

    private void forgetNativeCodexRestoreForSession(@Nullable TerminalSession terminalSession) {
        if (terminalSession == null) return;
        String handle = nullToEmpty(terminalSession.mHandle);
        synchronized (mNativeCodexRestoreLock) {
            if (!TextUtils.isEmpty(handle)) mNativeCodexRestoreSuppressedHandles.add(handle);
            String threadId = TextUtils.isEmpty(handle) ? null : mNativeCodexRestoreThreadByHandle.remove(handle);
            if (!TextUtils.isEmpty(threadId)) {
                mNativeCodexRestoreByThread.remove(threadId);
                return;
            }
        }

        CodexRestoreRecord inferred = inferCodexRestoreRecordFromExecutionCommand(terminalSession);
        if (inferred != null && !TextUtils.isEmpty(inferred.threadId)) {
            synchronized (mNativeCodexRestoreLock) {
                mNativeCodexRestoreByThread.remove(inferred.threadId);
            }
        }
    }

    @Nullable
    private CodexRestoreRecord findNativeCodexRestoreRecordForSession(@Nullable TerminalSession terminalSession) {
        if (terminalSession == null) return null;
        String handle = nullToEmpty(terminalSession.mHandle);
        synchronized (mNativeCodexRestoreLock) {
            if (!TextUtils.isEmpty(handle) && mNativeCodexRestoreSuppressedHandles.contains(handle)) {
                return null;
            }
            if (!TextUtils.isEmpty(handle)) {
                String threadId = mNativeCodexRestoreThreadByHandle.get(handle);
                CodexRestoreRecord record = TextUtils.isEmpty(threadId) ? null : mNativeCodexRestoreByThread.get(threadId);
                if (record != null) return record;
            }
        }

        CodexRestoreRecord inferred = inferCodexRestoreRecordFromExecutionCommand(terminalSession);
        if (inferred == null || TextUtils.isEmpty(inferred.threadId)) return null;
        synchronized (mNativeCodexRestoreLock) {
            return mNativeCodexRestoreByThread.get(inferred.threadId);
        }
    }

    @Nullable
    private CodexRestoreRecord findPersistedCodexRestoreRecordForSession(@Nullable TerminalSession terminalSession) {
        if (terminalSession == null) return null;
        String handle = nullToEmpty(terminalSession.mHandle);
        if (TextUtils.isEmpty(handle)) return null;

        TermuxRestoreState state = loadTermuxRestoreState();
        for (TermuxRestoreRecord record : state.records) {
            if (!TERMUX_RESTORE_TYPE_CODEX.equals(record.type)) continue;
            if (!TextUtils.equals(handle, record.handle)) continue;
            return buildCodexRestoreRecordFromTermuxRestoreRecord(record);
        }
        return null;
    }

    private void clearNativeCodexRestoreSuppression(@Nullable TerminalSession terminalSession) {
        if (terminalSession == null) return;
        String handle = nullToEmpty(terminalSession.mHandle);
        if (TextUtils.isEmpty(handle)) return;
        synchronized (mNativeCodexRestoreLock) {
            mNativeCodexRestoreSuppressedHandles.remove(handle);
        }
    }

    private boolean isNativeCodexRestoreSuppressed(@Nullable TerminalSession terminalSession) {
        if (terminalSession == null) return false;
        String handle = nullToEmpty(terminalSession.mHandle);
        if (TextUtils.isEmpty(handle)) return false;
        synchronized (mNativeCodexRestoreLock) {
            return mNativeCodexRestoreSuppressedHandles.contains(handle);
        }
    }

    @Nullable
    private CodexRestoreRecord findCodexRestoreRecordByThread(@NonNull ArrayList<CodexRestoreRecord> records,
                                                                @Nullable String threadId) {
        if (TextUtils.isEmpty(threadId)) return null;
        for (CodexRestoreRecord record : records) {
            if (TextUtils.equals(record.threadId, threadId)) return record;
        }
        return null;
    }

    @NonNull
    private CodexRestoreRecord mergeCodexRestoreRecords(@NonNull CodexRestoreRecord primary,
                                                        @NonNull CodexRestoreRecord fallback) {
        String workingDirectory = TextUtils.isEmpty(primary.workingDirectory) ? fallback.workingDirectory : primary.workingDirectory;
        String rolloutPath = TextUtils.isEmpty(primary.rolloutPath) ? fallback.rolloutPath : primary.rolloutPath;
        String title = TextUtils.isEmpty(primary.title) ? fallback.title : primary.title;
        int pid = fallback.pid > 0 ? fallback.pid : primary.pid;
        int order = fallback.order != Integer.MAX_VALUE ? fallback.order : primary.order;
        long updatedAt = Math.max(primary.updatedAt, fallback.updatedAt);
        return new CodexRestoreRecord(primary.threadId, workingDirectory, rolloutPath,
            title, pid, order, updatedAt);
    }

    @Nullable
    private CodexRestoreRecord inferCodexRestoreRecordFromExecutionCommand(@Nullable TerminalSession terminalSession) {
        if (terminalSession == null) return null;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;
        TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession == null) return null;

        ExecutionCommand command = termuxSession.getExecutionCommand();
        String script = command == null ? null : extractShellScriptFromExecutionArgs(command.arguments);
        if (!looksLikeCodexRestoreCommand(command, script)) return null;

        String threadId = normalizeCodexThreadId(extractShellOptionValue(script, "resume"));
        if (TextUtils.isEmpty(threadId)) return null;

        String workingDirectory = terminalSession.getCwd();
        workingDirectory = resolveCodexRestoreWorkingDirectory(workingDirectory);

        int order = service.getIndexOfSession(terminalSession);
        if (order < 0) order = Integer.MAX_VALUE;
        String title = buildTermuxRestoreDisplayName(terminalSession, command, order == Integer.MAX_VALUE ? 0 : order);
        int codexPid = findActiveCodexProcessPid(terminalSession);
        return new CodexRestoreRecord(threadId, workingDirectory, "", title,
            codexPid, order,
            System.currentTimeMillis() / 1000L);
    }

    private boolean looksLikeCodexRestoreCommand(@Nullable ExecutionCommand command, @Nullable String script) {
        if (command != null && isCodexExecutable(command.executable)) return true;
        if (TextUtils.isEmpty(script)) return false;
        return (script.contains("codex_cmd=") &&
            script.contains("exec \"$codex_cmd\" resume ")) ||
            (script.contains("codexctl_cmd=") && script.contains(" -- resume "));
    }

    @Nullable
    private String extractShellOptionValue(@Nullable String script, @NonNull String option) {
        if (TextUtils.isEmpty(script) || TextUtils.isEmpty(option)) return null;
        int index = 0;
        while (index < script.length()) {
            int start = script.indexOf(option, index);
            if (start < 0) return null;
            int before = start - 1;
            int after = start + option.length();
            boolean leftBoundary = before < 0 || Character.isWhitespace(script.charAt(before)) ||
                script.charAt(before) == ';';
            boolean rightBoundary = after >= script.length() || Character.isWhitespace(script.charAt(after)) ||
                script.charAt(after) == '=';
            if (leftBoundary && rightBoundary) {
                if (after < script.length() && script.charAt(after) == '=') {
                    return readShellTokenValue(script, after + 1);
                }
                return readShellTokenValue(script, after);
            }
            index = start + option.length();
        }
        return null;
    }

    @Nullable
    private String readShellTokenValue(@Nullable String text, int start) {
        if (TextUtils.isEmpty(text) || start < 0 || start >= text.length()) return null;
        int i = start;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        if (i >= text.length()) return null;

        StringBuilder value = new StringBuilder();
        boolean readAny = false;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == ';' || c == '\n' || c == '\r') break;
            readAny = true;
            if (c == '\'') {
                i++;
                while (i < text.length()) {
                    char q = text.charAt(i);
                    if (q == '\'') {
                        i++;
                        break;
                    }
                    value.append(q);
                    i++;
                }
                continue;
            }
            if (c == '"') {
                i++;
                while (i < text.length()) {
                    char q = text.charAt(i);
                    if (q == '"') {
                        i++;
                        break;
                    }
                    if (q == '\\' && i + 1 < text.length()) {
                        i++;
                        value.append(text.charAt(i));
                        i++;
                        continue;
                    }
                    value.append(q);
                    i++;
                }
                continue;
            }
            if (c == '\\' && i + 1 < text.length()) {
                i++;
                value.append(text.charAt(i));
                i++;
                continue;
            }
            value.append(c);
            i++;
        }

        if (!readAny) return null;
        String result = value.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private int findActiveCodexProcessPid(@Nullable TerminalSession terminalSession) {
        if (terminalSession == null) return -1;
        TermuxSessionRestoreStore.CodexLease lease =
            TermuxSessionRestoreStore.findCodexLeaseByHandle(nullToEmpty(terminalSession.mHandle));
        return CodexProcessIdentity.findLiveCodexProcessForSession(
            terminalSession, lease == null ? -1 : lease.processId);
    }

    private boolean isCodexExecutable(@Nullable String executable) {
        if (TextUtils.isEmpty(executable)) return false;
        String name = new File(executable).getName().toLowerCase(Locale.ROOT);
        return "codex".equals(name);
    }

    @Nullable
    private String readProcessEnvironmentValue(int pid, @NonNull String key) {
        if (pid <= 0 || TextUtils.isEmpty(key)) return null;
        File file = new File("/proc/" + pid + "/environ");
        if (!file.exists()) return null;

        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read > 0) out.write(buffer, 0, read);
            }

            byte[] raw = out.toByteArray();
            if (raw.length == 0) return null;
            String prefix = key + "=";
            int start = 0;
            for (int i = 0; i <= raw.length; i++) {
                if (i == raw.length || raw[i] == 0) {
                    if (i > start) {
                        String entry = new String(raw, start, i - start, StandardCharsets.UTF_8);
                        if (entry.startsWith(prefix)) return entry.substring(prefix.length()).trim();
                    }
                    start = i + 1;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @NonNull
    private ArrayList<CodexRestoreRecord> loadCodexRestoreRecords() {
        return buildCodexRestoreRecordsFromTermuxRestoreState(loadTermuxRestoreState());
    }

    @NonNull
    private ArrayList<CodexRestoreRecord> buildCodexRestoreRecordsFromTermuxRestoreState(
        @NonNull TermuxRestoreState state) {
        ArrayList<CodexRestoreRecord> records = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000L;
        for (TermuxRestoreRecord record : state.records) {
            if (!TERMUX_RESTORE_TYPE_CODEX.equals(record.type)) continue;
            CodexRestoreRecord codex = buildCodexRestoreRecordFromTermuxRestoreRecord(record, now);
            if (codex != null) records.add(codex);
        }
        return dedupeCodexRestoreRecords(records);
    }

    @Nullable
    private CodexRestoreRecord buildCodexRestoreRecordFromTermuxRestoreRecord(@NonNull TermuxRestoreRecord record) {
        return buildCodexRestoreRecordFromTermuxRestoreRecord(record, System.currentTimeMillis() / 1000L);
    }

    @Nullable
    private CodexRestoreRecord buildCodexRestoreRecordFromTermuxRestoreRecord(@NonNull TermuxRestoreRecord record,
                                                                              long fallbackUpdatedAt) {
        String threadId = resolveCodexThreadIdFromRestoreRecord(record);
        if (TextUtils.isEmpty(threadId)) return null;
        return new CodexRestoreRecord(
            threadId,
            resolveCodexRestoreWorkingDirectory(record.workingDirectory),
            nullToEmpty(record.codexRolloutPath),
            nullToEmpty(record.displayName),
            -1,
            normalizeRestoreOrder(record.order),
            record.updatedAt > 0 ? record.updatedAt : fallbackUpdatedAt);
    }

    @NonNull
    private CodexRestoreRecord normalizeCodexRestoreRecord(@NonNull CodexRestoreRecord record) {
        String threadId = record.threadId.trim();
        if (threadId.isEmpty()) threadId = UUID.randomUUID().toString();
        return new CodexRestoreRecord(
            threadId,
            record.workingDirectory.trim(),
            record.rolloutPath.trim(),
            record.title.trim(),
            record.pid,
            record.order,
            record.updatedAt);
    }

    @NonNull
    private ArrayList<CodexRestoreRecord> dedupeCodexRestoreRecords(
        @NonNull ArrayList<CodexRestoreRecord> records) {
        HashMap<String, CodexRestoreRecord> byThread = new HashMap<>();
        for (CodexRestoreRecord raw : records) {
            CodexRestoreRecord record = normalizeCodexRestoreRecord(raw);
            CodexRestoreRecord existing = byThread.get(record.threadId);
            if (existing == null || record.updatedAt >= existing.updatedAt) {
                byThread.put(record.threadId, record);
            }
        }

        ArrayList<CodexRestoreRecord> result = new ArrayList<>(byThread.values());
        result.sort((a, b) -> {
            int orderCompare = Integer.compare(a.order, b.order);
            if (orderCompare != 0) return orderCompare;
            int updatedCompare = Long.compare(a.updatedAt, b.updatedAt);
            if (updatedCompare != 0) return updatedCompare;
            return a.threadId.compareTo(b.threadId);
        });
        return result;
    }

    @NonNull
    private String buildCodexRestoreDisplayName(@NonNull CodexRestoreRecord record) {
        String title = record.title.trim();
        if (!TextUtils.isEmpty(title)) return title;
        int index = record.order == Integer.MAX_VALUE ? 0 : record.order;
        return CODEX_RESTORE_DEFAULT_DISPLAY_NAME + " " + (Math.max(0, index) + 1);
    }

    @NonNull
    private String resolveCodexRestoreWorkingDirectory(@Nullable String workingDirectory) {
        String value = workingDirectory == null ? "" : workingDirectory.trim();
        if (TextUtils.isEmpty(value) || !new File(value).isDirectory()) {
            value = mActivity.getProperties().getDefaultWorkingDirectory();
        }
        if (TextUtils.isEmpty(value) || !new File(value).isDirectory()) {
            value = TermuxConstants.TERMUX_HOME_DIR_PATH;
        }
        return value;
    }

    public void onTerminalTabLongPress(int index) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        if (index < 0 || index >= service.getTermuxSessionsSize()) return;

        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession == null) return;
        TerminalSession session = termuxSession.getTerminalSession();
        if (session == null) return;

        ArrayList<SshPersistenceRecord> records = loadSshPersistenceRecords();
        int recordIndex = findSshPersistenceRecordIndexForSession(session, records);
        if (recordIndex < 0 || recordIndex >= records.size()) return;

        SshPersistenceRecord record = normalizeSshPersistenceRecord(records.get(recordIndex));
        showPinnedSessionActionDialog(session, record);
    }

    private void showPinnedSessionActionDialog(@NonNull TerminalSession session,
                                               @NonNull SshPersistenceRecord record) {
        String targetLabel = normalizeDisplayName(record.displayName, record.tmuxSession);
        new AlertDialog.Builder(mActivity)
            .setTitle(R.string.title_ssh_persistence_session_actions)
            .setMessage(mActivity.getString(R.string.msg_ssh_persistence_session_target, targetLabel))
            .setPositiveButton(R.string.action_ssh_persistence_close_front,
                (dialog, which) -> closePinnedSessionForeground(session))
            .setNegativeButton(R.string.action_ssh_persistence_close_back,
                (dialog, which) -> closePinnedSessionBackground(session, record))
            .setNeutralButton(android.R.string.cancel, null)
            .show();
    }

    private void closePinnedSessionForeground(@NonNull TerminalSession session) {
        disableSshPersistenceForSession(session);
        closeTerminalSessionFromTabAction(session);
        mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_closed_front), false);
    }

    private void closePinnedSessionBackground(@NonNull TerminalSession session,
                                              @NonNull SshPersistenceRecord fallbackRecord) {
        SshPersistenceRecord removed = removeSshPersistenceRecordForSession(session);
        SshPersistenceRecord record = normalizeSshPersistenceRecord(removed != null ? removed : fallbackRecord);
        String targetLabel = normalizeDisplayName(record.displayName, record.tmuxSession);
        mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_closing_background, targetLabel), false);

        mSshTmuxRuntimeEngine.destroyRemoteTmuxSession(
            record.sshCommand, record.tmuxSession, record.displayName, session.mHandle, result -> {
            if (result.code == SshTmuxOperationResult.Code.SUCCESS) {
                mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_destroyed, targetLabel), false);
            } else {
                mActivity.showToast(mActivity.getString(
                    R.string.msg_ssh_persistence_destroy_failed, summarizeCommandResult(toCommandResult(result.commandResult))), true);
            }
            closeTerminalSessionFromTabAction(session);
        });
    }

    public void forgetSessionRestoreForUserAction(@Nullable TerminalSession terminalSession) {
        applyCodexRestoreTabCloseDecision(terminalSession);
        disableSshPersistenceForSession(terminalSession);
        forgetSshBootstrapCommand(terminalSession);
    }

    private void applyCodexRestoreTabCloseDecision(@Nullable TerminalSession terminalSession) {
        CodexRestoreRecord record = findCodexRestoreRecordForSession(terminalSession);
        String threadId = record == null ? "" : record.threadId;
        String handle = terminalSession == null ? "" : nullToEmpty(terminalSession.mHandle);
        String detail = "explicit_tab_close";
        if (record != null || TermuxSessionRestoreStore.findCodexLeaseByHandle(handle) != null) {
            TermuxService service = mActivity.getTermuxService();
            if (service != null) {
                service.getCodexSessionRecoveryController().revokeLease(threadId, handle, detail);
            } else {
                TermuxSessionRestoreStore.revokeCodexLease(threadId, handle, detail);
            }
        }
        forgetTermuxRestoreForSession(terminalSession);
        forgetNativeCodexRestoreForSession(terminalSession);
    }

    private void closeTerminalSessionFromTabAction(@Nullable TerminalSession terminalSession) {
        if (terminalSession == null) return;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession == null) return;

        if (service.getTermuxSessionsSize() <= 1 && !ensureSessionBeforeClosingLastTab(terminalSession)) {
            return;
        }

        int index = service.getIndexOfSession(terminalSession);
        int sessionsSize = service.getTermuxSessionsSize();
        boolean isClosingCurrent = terminalSession == mActivity.getCurrentSession();

        if (sessionsSize > 1 && isClosingCurrent && index >= 0) {
            int newIndex = index == 0 ? 1 : index - 1;
            switchToSession(newIndex);
        }

        if (terminalSession.isRunning()) {
            termuxSession.killIfExecuting(mActivity, true);
        } else {
            service.removeTermuxSession(terminalSession);
        }

        termuxSessionListNotifyUpdated();
    }

    public boolean ensureSessionBeforeClosingLastTab(@Nullable TerminalSession closingSession) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return false;
        if (service.wantsToStop()) return false;

        int sessionsSize = service.getTermuxSessionsSize();
        if (sessionsSize > 1) return true;
        if (closingSession != null && sessionsSize == 1 &&
            service.getTermuxSessionForTerminalSession(closingSession) == null) {
            return true;
        }

        addNewLocalSession(null);
        boolean replacementCreated = service.getTermuxSessionsSize() > sessionsSize;
        if (!replacementCreated) {
            Logger.logWarn(LOG_TAG, "Refusing to close the last terminal session because a replacement session could not be created");
            if (mActivity.isVisible()) {
                mActivity.showToast("无法创建替代会话，已保留当前 tab", true);
            }
        }
        return replacementCreated;
    }

    public boolean isSshSessionPinned(@Nullable TerminalSession session) {
        return isSshSessionPinned(session, loadSshPersistenceRecords());
    }

    private boolean isSshSessionPinned(@Nullable TerminalSession session,
                                       @NonNull ArrayList<SshPersistenceRecord> records) {
        if (session == null) return false;
        return findSshPersistenceRecordIndexForSession(session, records) >= 0;
    }

    @NonNull
    public Set<String> getPinnedSessionHandleSnapshot() {
        return mSshTmuxRuntimeEngine.getPinnedSessionHandleSnapshot();
    }

    @Nullable
    public String getSshBootstrapCommandForSession(@Nullable TerminalSession session) {
        if (session == null) return null;

        String remembered = sanitizeSshBootstrapCommand(getRememberedSshBootstrapCommand(session));
        if (!TextUtils.isEmpty(remembered)) return remembered;

        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;
        TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(session);
        String inferred = sanitizeSshBootstrapCommand(inferSshCommandFromSession(termuxSession));
        if (TextUtils.isEmpty(inferred)) return null;

        rememberSshBootstrapCommand(session, inferred);
        return inferred;
    }

    @Nullable
    public String getPinnedTmuxSessionForSession(@Nullable TerminalSession session) {
        return mSshTmuxRuntimeEngine.getPinnedTmuxSessionForSession(session);
    }

    @Nullable
    public String getPinnedDisplayNameForSession(@Nullable TerminalSession session) {
        return mSshTmuxRuntimeEngine.getPinnedDisplayNameForSession(session);
    }

    @NonNull
    public TerminalTopBarRuntimeState getTopBarRuntimeStateForSession(@Nullable TerminalSession session) {
        if (session == null || TextUtils.isEmpty(session.mHandle)) {
            return TerminalTopBarRuntimeState.IDLE;
        }
        SshTmuxRuntimeStateMachine.Snapshot snapshot;
        synchronized (mRuntimeStateLock) {
            snapshot = mRuntimeSnapshotBySessionHandle.get(session.mHandle);
        }
        if (snapshot == null) {
            return TerminalTopBarRuntimeState.IDLE;
        }
        if (snapshot.phase == SshTmuxRuntimeStateMachine.Phase.FAILED) {
            return TerminalTopBarRuntimeState.FAILED;
        }
        if (snapshot.phase == SshTmuxRuntimeStateMachine.Phase.RETRY_SCHEDULED) {
            return TerminalTopBarRuntimeState.RETRY_SCHEDULED;
        }

        return TerminalTopBarRuntimeState.BUSY;
    }

    private void trackRuntimeSnapshot(@NonNull SshTmuxRuntimeStateMachine.Snapshot snapshot) {
        mLastSshTmuxRuntimeSnapshot = snapshot;

        String operationId = normalizeRuntimeOperationId(snapshot.operationId);
        if (operationId == null) return;

        synchronized (mRuntimeStateLock) {
            String previousHandle = mRuntimeSessionHandleByOperationId.get(operationId);
            String currentHandle = normalizeRuntimeSessionHandle(snapshot.sessionHandle);
            String resolvedHandle = currentHandle != null ? currentHandle : previousHandle;

            if (snapshot.phase == SshTmuxRuntimeStateMachine.Phase.IDLE) {
                removeRuntimeSnapshotForHandleLocked(previousHandle, operationId);
                if (!TextUtils.equals(currentHandle, previousHandle)) {
                    removeRuntimeSnapshotForHandleLocked(currentHandle, operationId);
                }
                mRuntimeSessionHandleByOperationId.remove(operationId);
                return;
            }

            if (!TextUtils.equals(previousHandle, resolvedHandle)) {
                removeRuntimeSnapshotForHandleLocked(previousHandle, operationId);
            }

            if (resolvedHandle == null) {
                return;
            }

            mRuntimeSessionHandleByOperationId.put(operationId, resolvedHandle);
            mRuntimeSnapshotBySessionHandle.put(resolvedHandle, snapshot);
        }
    }

    private void clearRuntimeStateForSessionHandle(@Nullable String sessionHandle) {
        String normalizedHandle = normalizeRuntimeSessionHandle(sessionHandle);
        if (normalizedHandle == null) return;

        synchronized (mRuntimeStateLock) {
            mRuntimeSnapshotBySessionHandle.remove(normalizedHandle);

            ArrayList<String> staleOperationIds = new ArrayList<>();
            for (Map.Entry<String, String> entry : mRuntimeSessionHandleByOperationId.entrySet()) {
                if (TextUtils.equals(normalizedHandle, entry.getValue())) {
                    staleOperationIds.add(entry.getKey());
                }
            }

            for (String operationId : staleOperationIds) {
                mRuntimeSessionHandleByOperationId.remove(operationId);
            }
        }
    }

    private void removeRuntimeSnapshotForHandleLocked(@Nullable String sessionHandle, @NonNull String operationId) {
        if (sessionHandle == null) return;
        SshTmuxRuntimeStateMachine.Snapshot existing = mRuntimeSnapshotBySessionHandle.get(sessionHandle);
        if (existing == null) return;
        if (TextUtils.equals(operationId, normalizeRuntimeOperationId(existing.operationId))) {
            mRuntimeSnapshotBySessionHandle.remove(sessionHandle);
        }
    }

    @Nullable
    private String normalizeRuntimeOperationId(@Nullable String operationId) {
        if (TextUtils.isEmpty(operationId)) return null;
        String normalized = operationId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    @Nullable
    private String normalizeRuntimeSessionHandle(@Nullable String sessionHandle) {
        if (TextUtils.isEmpty(sessionHandle)) return null;
        String normalized = sessionHandle.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private int findSshPersistenceRecordIndexForSession(@Nullable TerminalSession session,
                                                        @NonNull ArrayList<SshPersistenceRecord> records) {
        if (session == null) return -1;
        if (records.isEmpty()) return -1;

        String handle = session.mHandle;
        if (!TextUtils.isEmpty(handle)) {
            for (int i = 0; i < records.size(); i++) {
                SshPersistenceRecord record = records.get(i);
                if (!TextUtils.isEmpty(record.lockedHandle) && handle.equals(record.lockedHandle)) return i;
            }
        }

        TermuxService service = mActivity.getTermuxService();
        if (service == null) return -1;
        TermuxSession ts = service.getTermuxSessionForTerminalSession(session);
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
            String normalizedBootstrap = sanitizeSshBootstrapCommand(bootstrapByHandle);
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

    @Nullable
    private String inferTmuxSessionFromExecutionCommand(@Nullable ExecutionCommand executionCommand) {
        if (executionCommand == null) return null;
        String script = extractShellScriptFromExecutionArgs(executionCommand.arguments);
        return extractTmuxSessionFromReconnectLoopScript(script);
    }

    @Nullable
    private String extractTmuxSessionFromReconnectLoopScript(@Nullable String script) {
        if (TextUtils.isEmpty(script)) return null;
        String marker = "tmux attach-session -t ";
        int start = script.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = script.indexOf(";", start);
        if (end < 0) end = script.indexOf("\n", start);
        if (end < 0) end = script.length();
        if (start >= end) return null;
        String value = script.substring(start, end).trim();
        if (TextUtils.isEmpty(value)) return null;
        return unquoteShellToken(value);
    }

    @Nullable
    private SshPersistenceRecord removeSshPersistenceRecordForSession(@Nullable TerminalSession session) {
        synchronized (mSshPersistRecordsLock) {
            if (session == null) return null;
            ArrayList<SshPersistenceRecord> records = loadSshPersistenceRecords();
            int index = findSshPersistenceRecordIndexForSession(session, records);
            if (index < 0 || index >= records.size()) return null;
            SshPersistenceRecord removed = normalizeSshPersistenceRecord(records.remove(index));
            saveSshPersistenceRecords(records);
            return removed;
        }
    }

    private boolean disableSshPersistenceForSession(@Nullable TerminalSession session) {
        return mSshTmuxRuntimeEngine.disableSshPersistenceForSession(session);
    }

    private boolean clearLockedHandleForSessionHandle(@Nullable String handle) {
        if (TextUtils.isEmpty(handle)) return false;
        synchronized (mSshPersistRecordsLock) {
            ArrayList<SshPersistenceRecord> records = loadSshPersistenceRecords();
            boolean changed = false;
            for (int i = 0; i < records.size(); i++) {
                SshPersistenceRecord record = records.get(i);
                if (!TextUtils.equals(handle, record.lockedHandle)) continue;
                records.set(i, new SshPersistenceRecord(
                    record.id, record.sshCommand, record.tmuxSession, record.displayName, record.shellName, null));
                changed = true;
            }
            if (changed) saveSshPersistenceRecords(records);
            return changed;
        }
    }

    public boolean ensurePinnedSshSession(boolean switchToSession) {
        return mSshTmuxRuntimeEngine.ensurePinnedSshSession(switchToSession);
    }

    public int ensurePinnedSshSessions(boolean switchToAny) {
        return mSshTmuxRuntimeEngine.ensurePinnedSshSessions(switchToAny);
    }

    @Nullable
    private SshPersistenceRecord ensurePinnedSshSessionRecord(@NonNull TermuxService service,
                                                              @NonNull SshPersistenceRecord record,
                                                              boolean switchToSession) {
        SshPersistenceRecord normalized = normalizeSshPersistenceRecord(record);
        if (TextUtils.isEmpty(normalized.sshCommand)) return null;
        String safeTmuxSession = normalizeTmuxSessionName(normalized.tmuxSession);

        if (!TextUtils.isEmpty(normalized.lockedHandle)) {
            TerminalSession existingByHandle = service.getTerminalSessionForHandle(normalized.lockedHandle);
            if (existingByHandle != null) {
                TermuxSession existingTermuxByHandle = service.getTermuxSessionForTerminalSession(existingByHandle);
                if (shouldRecreateStalePinnedReconnectSession(existingTermuxByHandle, safeTmuxSession)) {
                    service.removeTermuxSession(existingByHandle);
                    scheduleEnsurePinnedSshSessionsRetry(switchToSession);
                    return new SshPersistenceRecord(normalized.id, normalized.sshCommand,
                        normalized.tmuxSession, normalized.displayName, normalized.shellName, null);
                } else {
                    rememberSshBootstrapCommand(existingByHandle, normalized.sshCommand);
                    applyPinnedSessionDisplayName(existingByHandle, normalized.displayName);
                    if (switchToSession) setCurrentSession(existingByHandle);
                    return normalized;
                }
            }
        }

        TermuxSession existing = service.getTermuxSessionForShellName(normalized.shellName);
        if (existing != null) {
            TerminalSession existingSession = existing.getTerminalSession();
            if (existingSession != null) {
                if (shouldRecreateStalePinnedReconnectSession(existing, safeTmuxSession)) {
                    service.removeTermuxSession(existingSession);
                    scheduleEnsurePinnedSshSessionsRetry(switchToSession);
                    return new SshPersistenceRecord(normalized.id, normalized.sshCommand,
                        normalized.tmuxSession, normalized.displayName, normalized.shellName, null);
                } else {
                    rememberSshBootstrapCommand(existingSession, normalized.sshCommand);
                    applyPinnedSessionDisplayName(existingSession, normalized.displayName);
                    if (switchToSession) setCurrentSession(existingSession);
                    return new SshPersistenceRecord(normalized.id, normalized.sshCommand,
                        normalized.tmuxSession, normalized.displayName, normalized.shellName, existingSession.mHandle);
                }
            }
        }

        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) return normalized;

        String reconnectLoopScript = buildReconnectLoopCommand(normalized.sshCommand, normalized.tmuxSession, normalized.displayName);
        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bash).exists()) return normalized;

        String workingDirectory = mActivity.getProperties().getDefaultWorkingDirectory();
        String[] args = new String[]{"-lc", reconnectLoopScript};
        TermuxSession created = service.createTermuxSession(bash, args, null, workingDirectory, false, normalized.shellName);
        if (created == null) return normalized;

        TerminalSession createdSession = created.getTerminalSession();
        if (createdSession != null) {
            rememberSshBootstrapCommand(createdSession, normalized.sshCommand);
            applyPinnedSessionDisplayName(createdSession, normalized.displayName);
            if (switchToSession) setCurrentSession(createdSession);
            return new SshPersistenceRecord(normalized.id, normalized.sshCommand,
                normalized.tmuxSession, normalized.displayName, normalized.shellName, createdSession.mHandle);
        }

        return normalized;
    }

    private boolean shouldRecreateStalePinnedReconnectSession(@Nullable TermuxSession termuxSession,
                                                              @NonNull String safeTmuxSession) {
        if (termuxSession == null || termuxSession.getExecutionCommand() == null) return false;
        String loopScript = extractShellScriptFromExecutionArgs(termuxSession.getExecutionCommand().arguments);
        if (TextUtils.isEmpty(loopScript)) return false;

        // Only inspect persistence reconnect loops.
        if (!loopScript.contains("while true; do") || !loopScript.contains("[ssh-persist]")) return false;

        // Recreate if tmux target changed or if script matches known-bad generations.
        String target = buildTmuxTargetArg(safeTmuxSession);
        if (!loopScript.contains("tmux attach-session -t " + target)) return true;
        if (countMatches(loopScript, "while true; do") > 1) return true;
        if (loopScript.contains("tmux has-session -t " + target + " 2>/dev/null || tmux new-session -d -s " + target +
            "; tmux set-option -t " + target)) return true;
        return loopScript.contains("capture-pane -p -t \"\"") ||
            loopScript.contains("pane=;") ||
            loopScript.contains("; ; tmux") ||
            loopScript.contains("set-option -t " + target + " mouse off");
    }

    @Nullable
    private String extractShellScriptFromExecutionArgs(@Nullable String[] args) {
        if (args == null || args.length == 0) return null;
        for (int i = 0; i < args.length - 1; i++) {
            String arg = args[i];
            if ("-lc".equals(arg) || "-c".equals(arg)) return args[i + 1];
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

    private void cleanupOrphanedSshPersistentSessions(@NonNull TermuxService service,
                                                      @NonNull ArrayList<SshPersistenceRecord> records) {
        HashSet<String> managedShellNames = new HashSet<>();
        for (SshPersistenceRecord record : records) {
            if (!TextUtils.isEmpty(record.shellName)) managedShellNames.add(record.shellName);
        }

        ArrayList<TermuxSession> snapshot = new ArrayList<>(service.getTermuxSessions());
        for (TermuxSession termuxSession : snapshot) {
            if (termuxSession == null || termuxSession.getExecutionCommand() == null) continue;
            String shellName = termuxSession.getExecutionCommand().shellName;
            if (TextUtils.isEmpty(shellName) || !shellName.startsWith(SSH_PERSIST_SHELL_NAME_PREFIX)) continue;
            if (managedShellNames.contains(shellName)) continue;
            TerminalSession orphan = termuxSession.getTerminalSession();
            if (orphan != null) service.removeTermuxSession(orphan);
        }
    }

    private void collapseDuplicateManagedSshPersistentSessions(@NonNull TermuxService service,
                                                               @NonNull ArrayList<SshPersistenceRecord> records) {
        if (records.isEmpty()) return;

        HashMap<String, String> tmuxByShellName = new HashMap<>();
        HashSet<String> managedShellNames = new HashSet<>();
        for (SshPersistenceRecord record : records) {
            if (TextUtils.isEmpty(record.shellName)) continue;
            managedShellNames.add(record.shellName);
            tmuxByShellName.put(record.shellName, normalizeTmuxSessionName(record.tmuxSession));
        }
        if (managedShellNames.isEmpty()) return;

        HashMap<String, ArrayList<TermuxSession>> sessionsByShellName = new HashMap<>();
        ArrayList<TermuxSession> snapshot = new ArrayList<>(service.getTermuxSessions());
        for (TermuxSession termuxSession : snapshot) {
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
            ArrayList<TermuxSession> candidates = entry.getValue();
            if (candidates == null || candidates.size() <= 1) continue;

            String safeTmuxSession = tmuxByShellName.get(entry.getKey());
            TermuxSession keep = pickPreferredManagedSshSession(candidates, safeTmuxSession);

            for (TermuxSession candidate : candidates) {
                if (candidate == null || candidate == keep) continue;
                TerminalSession duplicate = candidate.getTerminalSession();
                if (duplicate != null) service.removeTermuxSession(duplicate);
            }
        }
    }

    @Nullable
    private TermuxSession pickPreferredManagedSshSession(@NonNull ArrayList<TermuxSession> candidates,
                                                         @Nullable String safeTmuxSession) {
        TermuxSession best = null;
        int bestScore = Integer.MIN_VALUE;

        for (TermuxSession candidate : candidates) {
            if (candidate == null) continue;
            int score = 0;
            TerminalSession terminalSession = candidate.getTerminalSession();
            if (terminalSession != null && terminalSession.isRunning()) score += 2;
            if (isReconnectLoopSession(candidate)) score += 1;
            if (!TextUtils.isEmpty(safeTmuxSession) &&
                !shouldRecreateStalePinnedReconnectSession(candidate, safeTmuxSession)) {
                score += 4;
            }
            if (best == null || score >= bestScore) {
                best = candidate;
                bestScore = score;
            }
        }

        if (best != null) return best;
        return candidates.isEmpty() ? null : candidates.get(candidates.size() - 1);
    }

    private void maybeAutoRestorePinnedSshSessions() {
        mSshTmuxRuntimeEngine.maybeAutoRestorePinnedSshSessions();
    }

    private void scheduleEnsurePinnedSshSessionsRetry(boolean switchToAny) {
        if (!mEnsurePinnedSshSessionsRetryScheduled.compareAndSet(false, true)) return;

        View anchor = mActivity.getTerminalView();
        Runnable retry = () -> {
            mEnsurePinnedSshSessionsRetryScheduled.set(false);
            ensurePinnedSshSessions(switchToAny);
        };

        if (anchor != null) {
            anchor.postDelayed(retry, 260);
        } else {
            mActivity.runOnUiThread(retry);
        }
    }

    private void prepareSshLock(@NonNull TerminalSession targetSession, @NonNull String sshCommandRaw,
                                boolean attachCurrentSessionToTmux) {
        final String sshCommand = sanitizeSshBootstrapCommand(sshCommandRaw);
        if (sshCommand.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_command_required), true);
            return;
        }
        mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_checking_tmux), false);
        mSshTmuxRuntimeEngine.prepareSshLock(targetSession, sshCommand, attachCurrentSessionToTmux, result -> {
            if (result.code == SshTmuxOperationResult.Code.SUCCESS) {
                mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_locked), true);
                return;
            }

            if (result.code == SshTmuxOperationResult.Code.TMUX_MISSING) {
                final String installCommand = buildTmuxInstallCommand(sshCommand);
                new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.title_ssh_persistence_tmux_missing)
                    .setMessage(mActivity.getString(R.string.msg_ssh_persistence_tmux_missing_with_cmd, installCommand))
                    .setPositiveButton(R.string.action_ssh_persistence_install, (dialog, which) -> {
                        mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_installing_tmux), true);
                        mSshTmuxRuntimeEngine.installTmuxAndEnable(
                            targetSession, sshCommand, result.tmuxSession, result.displayName,
                            attachCurrentSessionToTmux, installResult -> {
                                if (installResult.code == SshTmuxOperationResult.Code.SUCCESS) {
                                    mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_locked), true);
                                } else {
                                    mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_install_failed), true);
                                }
                            });
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return;
            }

            showTmuxCheckFailedDialog(targetSession, sshCommand,
                toCommandResult(result.commandResult), attachCurrentSessionToTmux);
        });
    }

    private void runTmuxInstallAndEnable(@NonNull TerminalSession targetSession, @NonNull String sshCommand,
                                         @NonNull String tmuxSession, @NonNull String displayName,
                                         @NonNull String installCommand,
                                         boolean attachCurrentSessionToTmux) {
        mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_installing_tmux), true);

        runSshBackgroundTask("tmux-install-before-lock", () -> {
            CommandResult installResult = runBashCommandSync(installCommand);
            CommandResult verifyResult = runBashCommandSync(buildTmuxCheckCommand(sshCommand));
            boolean hasTmux = verifyResult.stdout.contains("__TMUX_OK__");
            mActivity.runOnUiThread(() -> {
                if (installResult.isSuccess() && hasTmux) {
                    enableSshPersistence(targetSession, sshCommand, tmuxSession, displayName, attachCurrentSessionToTmux);
                } else {
                    mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_install_failed), true);
                }
            });
        });
    }
    private void showTmuxCheckFailedDialog(@NonNull TerminalSession targetSession, @NonNull String sshCommand,
                                           @NonNull CommandResult checkResult,
                                           boolean attachCurrentSessionToTmux) {
        String detail = buildTmuxCheckFailureDetail(checkResult);
        StringBuilder msg = new StringBuilder();
        msg.append("Lock failed: requires non-interactive SSH and remote tmux.\n\n");
        msg.append("此检查针对服务器端的远程 tmux，而不是本机 tmux。\n\n");
        msg.append("原因：").append(detail).append("\n\n");
        msg.append("原始输出：\n").append(trimForDialog(getCombinedOutput(checkResult), 700)).append("\n\n");
        msg.append("请确认：\n");
        msg.append("1) SSH 支持免交互登录（密钥登录或 sshpass 保存密码）。\n");
        msg.append("2) 远程服务器已安装 tmux 且可正常执行。");

        new AlertDialog.Builder(mActivity)
            .setTitle(R.string.title_ssh_persistence_unavailable)
            .setMessage(msg.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }
    @NonNull
    private String buildTmuxCheckFailureDetail(@NonNull CommandResult result) {
        String raw = getCombinedOutput(result).toLowerCase(Locale.ROOT);
        if (raw.contains("permission denied")) return "SSH 认证失败";
        if (raw.contains("connection timed out") || raw.contains("operation timed out")) return "连接超时";
        if (raw.contains("connection refused")) return "连接被拒绝";
        if (raw.contains("no route to host")) return "网络不可达";
        if (raw.contains("could not resolve hostname") || raw.contains("name or service not known") ||
            raw.contains("temporary failure in name resolution")) return "DNS 解析失败";
        if (raw.contains("host key verification failed")) return "主机指纹待批准或待替换";
        if (raw.contains("sshpass")) return "sshpass 不可用或执行失败";
        if (result.exitCode == 255) return "SSH 连接未建立（exit 255）";
        if (result.exitCode != 0) return "检查命令失败（exit " + result.exitCode + "）";
        return "未知错误";
    }
    @NonNull
    private String getCombinedOutput(@NonNull CommandResult result) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(result.stdout)) sb.append(result.stdout.trim());
        if (!TextUtils.isEmpty(result.stderr)) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(result.stderr.trim());
        }
        return sb.toString().trim();
    }

    @NonNull
    private String trimForDialog(@Nullable String text, int maxChars) {
        if (TextUtils.isEmpty(text)) return "<空>";
        String normalized = text.trim();
        if (normalized.length() <= maxChars) return normalized;
        return normalized.substring(0, maxChars) + "\n...(已截断)";
    }

    private void enableSshPersistence(@NonNull TerminalSession targetSession, @NonNull String sshCommand,
                                      @NonNull String tmuxSession, @NonNull String displayName,
                                      boolean attachCurrentSessionToTmux) {
        sshCommand = sanitizeSshBootstrapCommand(sshCommand);
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String normalizedDisplayName = normalizeDisplayName(displayName, safeTmuxSession);
        boolean lockCurrentSession = attachCurrentSessionToTmux && targetSession.isRunning();
        if (lockCurrentSession) {
            attachSessionToTmux(targetSession, safeTmuxSession, normalizedDisplayName);
        }

        ArrayList<SshPersistenceRecord> records = loadSshPersistenceRecords();
        int existingIndex = lockCurrentSession
            ? findSshPersistenceRecordIndexForSession(targetSession, records)
            : findSshPersistenceRecordIndexByRemote(records, sshCommand, safeTmuxSession);
        String recordId = existingIndex >= 0 ? records.get(existingIndex).id : UUID.randomUUID().toString();
        String shellName = existingIndex >= 0 ? records.get(existingIndex).shellName : buildSshPersistShellName(recordId);
        String lockedHandle = lockCurrentSession ? targetSession.mHandle :
            (existingIndex >= 0 ? records.get(existingIndex).lockedHandle : null);

        SshPersistenceRecord record = normalizeSshPersistenceRecord(new SshPersistenceRecord(
            recordId, sshCommand, safeTmuxSession, normalizedDisplayName, shellName, lockedHandle
        ));
        upsertSshPersistenceRecord(record);
        if (lockCurrentSession) {
            rememberSshBootstrapCommand(targetSession, sshCommand);
            applyPinnedSessionDisplayName(targetSession, normalizedDisplayName);
        }

        if (!lockCurrentSession) {
            TermuxService service = mActivity.getTermuxService();
            if (service == null) {
                removeSshPersistenceRecordById(record.id);
                mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_tmux_check_failed), true);
                termuxSessionListNotifyUpdated();
                return;
            }

            SshPersistenceRecord ensured = ensurePinnedSshSessionRecord(service, record, true);
            if (ensured == null || TextUtils.isEmpty(ensured.lockedHandle)) {
                removeSshPersistenceRecordById(record.id);
                mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_tmux_check_failed), true);
                termuxSessionListNotifyUpdated();
                return;
            }
            upsertSshPersistenceRecord(ensured);
        }

        TermuxService service = mActivity.getTermuxService();
        if (service != null) {
            cleanupOrphanedSshPersistentSessions(service, loadSshPersistenceRecords());
        }

        mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_locked), true);
        termuxSessionListNotifyUpdated();
    }

    private void attachSessionToTmux(@NonNull TerminalSession targetSession, @NonNull String tmuxSession,
                                     @NonNull String displayName) {
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        targetSession.write(buildTmuxEnsureAndAttachCommand(safeTmuxSession, displayName) + "\r");
    }

    private void disableSshPersistence() {
        saveSshPersistenceRecords(new ArrayList<>());
        SharedPreferences p = getSshPersistPrefs();
        if (p == null) return;
        p.edit()
            .remove(KEY_SSH_COMMAND)
            .remove(KEY_SSH_TMUX_SESSION)
            .remove(KEY_SSH_SHELL_NAME)
            .remove(KEY_SSH_LOCKED_HANDLE)
            .putBoolean(KEY_SSH_PERSIST_ENABLED, false)
            .apply();
    }

    @Nullable
    private String inferSshCommandFromSession(@Nullable TermuxSession termuxSession) {
        String fromExecutionCommand = inferSshCommandFromExecutionCommand(
            termuxSession == null ? null : termuxSession.getExecutionCommand());
        if (!TextUtils.isEmpty(fromExecutionCommand)) return fromExecutionCommand;

        return inferSshCommandFromProcessTree(termuxSession);
    }

    @Nullable
    private String inferSshCommandFromExecutionCommand(@Nullable ExecutionCommand executionCommand) {
        if (executionCommand == null) return null;
        if (isSshExecutable(executionCommand.executable)) {
            return buildCommandLine(executionCommand.executable, executionCommand.arguments);
        }

        if (!TextUtils.isEmpty(executionCommand.shellName)) {
            String shellName = executionCommand.shellName.trim();
            if (shellName.startsWith("ssh ")) return shellName;
        }

        if (executionCommand.arguments != null) {
            for (String arg : executionCommand.arguments) {
                if (TextUtils.isEmpty(arg)) continue;
                String trimmed = arg.trim();
                String fromLoop = extractSshCommandFromReconnectLoop(trimmed);
                if (!TextUtils.isEmpty(fromLoop)) return fromLoop;
                int start = trimmed.indexOf("ssh ");
                if (start >= 0) {
                    String candidate = trimmed.substring(start).trim();
                    if (candidate.startsWith("ssh ")) return candidate;
                }
            }
        }

        return null;
    }

    @Nullable
    private String inferSshCommandFromProcessTree(@Nullable TermuxSession termuxSession) {
        if (termuxSession == null) return null;
        TerminalSession terminalSession = termuxSession.getTerminalSession();
        if (terminalSession == null) return null;

        int rootPid = terminalSession.getPid();
        if (rootPid <= 0) return null;

        String fromProcSnapshot = inferSshCommandFromProcSnapshot(rootPid);
        if (!TextUtils.isEmpty(fromProcSnapshot)) return fromProcSnapshot;

        int sshPid = findActiveSshProcessPid(rootPid);
        if (sshPid > 0) {
            String fromPid = inferSshCommandFromPid(sshPid);
            if (!TextUtils.isEmpty(fromPid)) return fromPid;
        }

        return inferSshCommandFromPsSnapshot(rootPid);
    }

    @Nullable
    private String inferSshCommandFromProcSnapshot(int rootPid) {
        File procRoot = new File("/proc");
        File[] entries = procRoot.listFiles();
        if (entries == null || entries.length == 0) return null;

        Map<Integer, ArrayList<Integer>> childrenByPid = new HashMap<>();
        Set<Integer> seenPids = new HashSet<>();
        for (File entry : entries) {
            if (entry == null || !entry.isDirectory()) continue;
            String name = entry.getName();
            if (TextUtils.isEmpty(name) || !name.matches("\\d+")) continue;
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                continue;
            }

            ProcessStatus status = readProcessStatus(pid);
            if (status == null) continue;
            seenPids.add(status.pid);
            childrenByPid.computeIfAbsent(status.ppid, k -> new ArrayList<>()).add(status.pid);
        }

        if (!seenPids.contains(rootPid)) return null;

        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        stack.add(rootPid);
        String bestCandidate = null;

        while (!stack.isEmpty()) {
            Integer pidObj = stack.pollLast();
            if (pidObj == null) continue;
            int pid = pidObj;
            if (pid <= 0 || !visited.add(pid)) continue;

            String candidate = inferSshCommandFromPid(pid);
            if (!TextUtils.isEmpty(candidate)) bestCandidate = candidate;

            ArrayList<Integer> children = childrenByPid.get(pid);
            if (children == null) continue;
            for (Integer child : children) {
                if (child != null && child > 0) stack.add(child);
            }
        }

        return bestCandidate;
    }

    @Nullable
    private String inferSshCommandFromPsSnapshot(int rootPid) {
        CommandResult psResult = runBashCommandSync("ps -A -o PID=,PPID=,ARGS= 2>/dev/null");
        if (!psResult.isSuccess() || TextUtils.isEmpty(psResult.stdout)) return null;

        String[] lines = psResult.stdout.split("\\r?\\n");
        Map<Integer, ArrayList<Integer>> childrenByPid = new HashMap<>();
        Map<Integer, String> argsByPid = new HashMap<>();

        for (String line : lines) {
            if (TextUtils.isEmpty(line)) continue;
            Matcher matcher = PS_LINE_PATTERN.matcher(line);
            if (!matcher.matches()) continue;
            try {
                int pid = Integer.parseInt(matcher.group(1));
                int ppid = Integer.parseInt(matcher.group(2));
                String args = matcher.group(3) == null ? "" : matcher.group(3).trim();
                if (pid <= 0) continue;

                argsByPid.put(pid, args);
                childrenByPid.computeIfAbsent(ppid, k -> new ArrayList<>()).add(pid);
        } catch (Exception ignored) {
        }
    }

        if (childrenByPid.isEmpty()) return null;

        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        stack.add(rootPid);

        String bestCandidate = null;
        while (!stack.isEmpty()) {
            Integer pidObj = stack.pollLast();
            if (pidObj == null) continue;
            int pid = pidObj;
            if (!visited.add(pid)) continue;

            String args = argsByPid.get(pid);
            String sshCommand = extractSshCommandFromArgs(args);
            if (!TextUtils.isEmpty(sshCommand)) {
                bestCandidate = sshCommand;
            }

            ArrayList<Integer> children = childrenByPid.get(pid);
            if (children == null) continue;
            for (Integer child : children) {
                if (child != null && child > 0) {
                    stack.add(child);
                }
            }
        }

        return bestCandidate;
    }

    public boolean restoreTermuxSessionsIfRequested(boolean isFailSafe) {
        if (isFailSafe) return false;

        TermuxService service = mActivity.getTermuxService();
        if (service == null || !service.isTermuxSessionsEmpty()) return false;

        TermuxRestoreState state = pruneDisposableGeneratedShellRestoreState(loadTermuxRestoreState());
        HashMap<String, CodexRestoreRecord> codexByThread = new HashMap<>();
        for (CodexRestoreRecord codexRecord : buildCodexRestoreRecordsFromTermuxRestoreState(state)) {
            if (!TextUtils.isEmpty(codexRecord.threadId)) {
                codexByThread.put(codexRecord.threadId, codexRecord);
            }
        }

        ArrayList<TermuxRestoreRecord> records = state.records;
        if (records.isEmpty()) return false;

        HashMap<String, SshPersistenceRecord> sshRecordsById = new HashMap<>();
        for (SshPersistenceRecord record : loadSshPersistenceRecords()) {
            SshPersistenceRecord normalized = normalizeSshPersistenceRecord(record);
            if (!TextUtils.isEmpty(normalized.id)) sshRecordsById.put(normalized.id, normalized);
        }

        mRestoringTermuxSessions = true;
        TerminalSession foreground = null;
        TerminalSession foregroundByOrder = null;
        TerminalSession fallback = null;
        int restored = 0;
        try {
            for (TermuxRestoreRecord record : records) {
                if (service.getTermuxSessionsSize() >= MAX_SESSIONS) break;
                TermuxSession created = createTermuxRestoreSession(service, record, codexByThread, sshRecordsById);
                if (created == null || created.getTerminalSession() == null) continue;

                TerminalSession session = created.getTerminalSession();
                fallback = session;
                restored++;

                if (isForegroundTermuxRestoreRecord(state, record, "", "")) {
                    foreground = session;
                } else if (foregroundByOrder == null && isForegroundOrderRestoreRecord(state, record)) {
                    foregroundByOrder = session;
                }
            }
        } finally {
            mRestoringTermuxSessions = false;
        }

        if (restored <= 0) return false;

        setCurrentSession(foreground != null ? foreground : foregroundByOrder != null ? foregroundByOrder : fallback);
        termuxSessionListNotifyUpdated();
        persistTermuxSessionRestoreState();
        return true;
    }

    private void persistTermuxSessionRestoreState() {
        persistTermuxSessionRestoreState(mActivity.getCurrentSession());
    }

    private void persistTermuxSessionRestoreState(@Nullable TerminalSession current) {
        if (mRestoringTermuxSessions) return;

        TermuxService service = mActivity.getTermuxService();
        if (service == null || service.isTermuxSessionsEmpty()) return;

        String foregroundHandle = "";
        String foregroundKey = "";
        int foregroundOrder = Integer.MAX_VALUE;
        TermuxRestoreRecord foregroundRecord = null;
        int droppedForegroundOrder = Integer.MAX_VALUE;
        String droppedForegroundHandle = "";
        ArrayList<TermuxRestoreRecord> records = new ArrayList<>();
        HashSet<String> restoreIdentities = new HashSet<>();
        ArrayList<CodexRestoreRecord> codexRecords = loadCodexRestoreRecords();
        ArrayList<SshPersistenceRecord> sshRecords = loadSshPersistenceRecords();
        long now = System.currentTimeMillis() / 1000L;

        ArrayList<TermuxSession> sessions = new ArrayList<>(service.getTermuxSessions());
        for (int i = 0; i < sessions.size() && records.size() < MAX_SESSIONS; i++) {
            TermuxSession termuxSession = sessions.get(i);
            TermuxRestoreRecord record = buildTermuxRestoreRecord(termuxSession, i, now, codexRecords, sshRecords);
            TerminalSession terminalSession = termuxSession.getTerminalSession();
            if (record == null) {
                if (current != null && terminalSession == current) {
                    droppedForegroundOrder = i;
                    droppedForegroundHandle = terminalSession == null ? "" : nullToEmpty(terminalSession.mHandle);
                }
                continue;
            }
            boolean appended = appendUniqueTermuxRestoreRecord(records, record, restoreIdentities);
            if (current != null && terminalSession == current) {
                foregroundRecord = appended ? record : findEquivalentTermuxRestoreRecord(records, record);
            }
        }

        sortTermuxRestoreRecordsForMaterialization(records);

        if (records.isEmpty()) {
            persistEmptyTermuxRestoreState();
            return;
        }
        PrunedTermuxRestoreRecords pruned = pruneDisposableGeneratedShellRestoreRecords(records, foregroundRecord);
        if (pruned.removedForeground) {
            foregroundRecord = pruned.foregroundReplacement;
            foregroundOrder = pruned.removedForegroundOrder;
        }
        if (records.isEmpty()) {
            persistEmptyTermuxRestoreState();
            return;
        }
        if (foregroundRecord == null && droppedForegroundOrder != Integer.MAX_VALUE) {
            foregroundRecord = findForegroundReplacementByExactOrder(records, droppedForegroundOrder);
            foregroundOrder = droppedForegroundOrder;
        }
        if (foregroundRecord != null) {
            foregroundKey = foregroundRecord.key;
            foregroundHandle = foregroundRecord.handle;
            foregroundOrder = foregroundRecord.order;
        } else if (droppedForegroundOrder != Integer.MAX_VALUE) {
            foregroundHandle = droppedForegroundHandle;
            foregroundOrder = droppedForegroundOrder;
        }
        String signature = buildTermuxRestoreStateSignature(records, foregroundKey, foregroundHandle, foregroundOrder);
        if (TextUtils.equals(signature, mLastTermuxRestoreStateSignature)) return;
        saveTermuxRestoreState(new TermuxRestoreState(records, foregroundKey, foregroundHandle, foregroundOrder), signature);
    }

    private void persistEmptyTermuxRestoreState() {
        ArrayList<TermuxRestoreRecord> records = new ArrayList<>();
        String signature = buildTermuxRestoreStateSignature(records, "", "", Integer.MAX_VALUE);
        if (TextUtils.equals(signature, mLastTermuxRestoreStateSignature)) return;
        saveTermuxRestoreState(new TermuxRestoreState(records, "", "", Integer.MAX_VALUE), signature);
    }

    private boolean appendUniqueTermuxRestoreRecord(@NonNull ArrayList<TermuxRestoreRecord> records,
                                                    @NonNull TermuxRestoreRecord record,
                                                    @NonNull HashSet<String> identities) {
        String identity = buildTermuxRestoreRecordIdentity(record);
        if (!TextUtils.isEmpty(identity) && !identities.add(identity)) return false;
        records.add(record);
        return true;
    }

    @Nullable
    private TermuxRestoreRecord findEquivalentTermuxRestoreRecord(@NonNull ArrayList<TermuxRestoreRecord> records,
                                                                  @NonNull TermuxRestoreRecord target) {
        String identity = buildTermuxRestoreRecordIdentity(target);
        if (TextUtils.isEmpty(identity)) return null;
        for (TermuxRestoreRecord record : records) {
            if (TextUtils.equals(identity, buildTermuxRestoreRecordIdentity(record))) return record;
        }
        return null;
    }

    @NonNull
    private String buildTermuxRestoreRecordIdentity(@NonNull TermuxRestoreRecord record) {
        String threadId = resolveCodexThreadIdFromRestoreRecord(record);
        if (!TextUtils.isEmpty(threadId)) return TERMUX_RESTORE_TYPE_CODEX + ":" + threadId;
        if (TERMUX_RESTORE_TYPE_SSH_TMUX.equals(record.type) && !TextUtils.isEmpty(record.sshPersistRecordId)) {
            return TERMUX_RESTORE_TYPE_SSH_TMUX + ":" + record.sshPersistRecordId;
        }
        if (TERMUX_RESTORE_TYPE_LOCAL_TMUX.equals(record.type) && !TextUtils.isEmpty(record.tmuxSession)) {
            return TERMUX_RESTORE_TYPE_LOCAL_TMUX + ":" + record.tmuxSession;
        }
        if (TERMUX_RESTORE_TYPE_SSH.equals(record.type) && !TextUtils.isEmpty(record.sshCommand)) {
            return TERMUX_RESTORE_TYPE_SSH + ":" + record.sshCommand;
        }
        if (TERMUX_RESTORE_TYPE_PROOT.equals(record.type) && !TextUtils.isEmpty(record.tmuxSession)) {
            return TERMUX_RESTORE_TYPE_PROOT + ":" + record.tmuxSession;
        }
        if (!TextUtils.isEmpty(record.handle)) return "handle:" + record.handle;
        return "key:" + record.key;
    }

    private void collectRestoreForegroundCandidates(@NonNull ArrayList<TermuxRestoreRecord> records,
                                                    @NonNull HashSet<String> keys,
                                                    @NonNull HashSet<String> handles) {
        for (TermuxRestoreRecord record : records) {
            if (!TextUtils.isEmpty(record.key)) keys.add(record.key);
            if (!TextUtils.isEmpty(record.handle)) handles.add(record.handle);
        }
    }

    private boolean isForegroundRestoreRecord(@NonNull TermuxRestoreState state,
                                              @NonNull TermuxRestoreRecord record) {
        return (!TextUtils.isEmpty(state.foregroundKey) && TextUtils.equals(state.foregroundKey, record.key)) ||
            (!TextUtils.isEmpty(state.foregroundHandle) && TextUtils.equals(state.foregroundHandle, record.handle));
    }

    private boolean isForegroundTermuxRestoreRecord(@NonNull TermuxRestoreState state,
                                                    @NonNull TermuxRestoreRecord record,
                                                    @Nullable String foregroundThreadId,
                                                    @Nullable String foregroundSessionId) {
        if (!TextUtils.isEmpty(state.foregroundKey) && TextUtils.equals(state.foregroundKey, record.key)) {
            return true;
        }

        String threadId = resolveCodexThreadIdFromRestoreRecord(record);
        if (!TextUtils.isEmpty(state.foregroundKey) && state.foregroundKey.startsWith("codex:")) {
            String foregroundKeyThreadId = state.foregroundKey.substring("codex:".length()).trim();
            if (!TextUtils.isEmpty(threadId) && TextUtils.equals(foregroundKeyThreadId, threadId)) {
                return true;
            }
        }

        if (!TextUtils.isEmpty(foregroundThreadId) && !TextUtils.isEmpty(threadId) &&
            TextUtils.equals(foregroundThreadId, threadId)) {
            return true;
        }

        return !TextUtils.isEmpty(state.foregroundHandle) && TextUtils.equals(state.foregroundHandle, record.handle);
    }

    private boolean isForegroundOrderRestoreRecord(@NonNull TermuxRestoreState state,
                                                   @NonNull TermuxRestoreRecord record) {
        return state.foregroundOrder != Integer.MAX_VALUE &&
            normalizeRestoreOrder(state.foregroundOrder) == normalizeRestoreOrder(record.order);
    }

    @NonNull
    private TermuxRestoreState pruneDisposableGeneratedShellRestoreState(@NonNull TermuxRestoreState state) {
        ArrayList<TermuxRestoreRecord> records = new ArrayList<>(state.records);
        TermuxRestoreRecord foregroundRecord = findRestoreStateForegroundRecord(state, records);
        PrunedTermuxRestoreRecords pruned = pruneDisposableGeneratedShellRestoreRecords(records, foregroundRecord);
        if (!pruned.removedForeground && records.size() == state.records.size()) return state;

        String foregroundKey = state.foregroundKey;
        String foregroundHandle = state.foregroundHandle;
        int foregroundOrder = state.foregroundOrder;
        if (pruned.removedForeground) {
            TermuxRestoreRecord replacement = pruned.foregroundReplacement;
            if (replacement != null) {
                foregroundKey = replacement.key;
                foregroundHandle = replacement.handle;
                foregroundOrder = replacement.order;
            } else {
                foregroundKey = "";
                foregroundHandle = "";
                foregroundOrder = pruned.removedForegroundOrder;
            }
        }
        return new TermuxRestoreState(records, foregroundKey, foregroundHandle, foregroundOrder);
    }

    @NonNull
    private PrunedTermuxRestoreRecords pruneDisposableGeneratedShellRestoreRecords(
        @NonNull ArrayList<TermuxRestoreRecord> records,
        @Nullable TermuxRestoreRecord foregroundRecord) {
        if (records.size() <= 1) {
            return new PrunedTermuxRestoreRecords(null, false, Integer.MAX_VALUE);
        }

        int maxManagedOrder = getMaxManagedRestoreOrder(records);
        if (maxManagedOrder == Integer.MAX_VALUE) {
            return new PrunedTermuxRestoreRecords(null, false, Integer.MAX_VALUE);
        }

        boolean removedForeground = false;
        int removedForegroundOrder = Integer.MAX_VALUE;
        for (int i = records.size() - 1; i >= 0; i--) {
            TermuxRestoreRecord record = records.get(i);
            if (!shouldDropDisposableGeneratedShellRestoreRecord(record, maxManagedOrder)) continue;
            if (foregroundRecord != null && isSameTermuxRestoreRecord(foregroundRecord, record)) {
                removedForeground = true;
                removedForegroundOrder = record.order;
            }
            records.remove(i);
        }

        TermuxRestoreRecord replacement = removedForeground
            ? findLastManagedForegroundReplacement(records)
            : null;
        return new PrunedTermuxRestoreRecords(replacement, removedForeground, removedForegroundOrder);
    }

    @Nullable
    private TermuxRestoreRecord findRestoreStateForegroundRecord(@NonNull TermuxRestoreState state,
                                                                 @NonNull ArrayList<TermuxRestoreRecord> records) {
        for (TermuxRestoreRecord record : records) {
            if (isForegroundRestoreRecord(state, record)) return record;
        }
        if (state.foregroundOrder == Integer.MAX_VALUE) return null;
        for (TermuxRestoreRecord record : records) {
            if (isForegroundOrderRestoreRecord(state, record)) return record;
        }
        return null;
    }

    @Nullable
    private TermuxRestoreRecord findLastManagedForegroundReplacement(@NonNull ArrayList<TermuxRestoreRecord> records) {
        TermuxRestoreRecord best = null;
        for (TermuxRestoreRecord record : records) {
            if (!isManagedRestoreRecord(record)) continue;
            if (best == null || normalizeRestoreOrder(record.order) > normalizeRestoreOrder(best.order)) {
                best = record;
            }
        }
        if (best != null) return best;
        return records.isEmpty() ? null : records.get(records.size() - 1);
    }

    private int getMaxManagedRestoreOrder(@NonNull ArrayList<TermuxRestoreRecord> records) {
        int max = Integer.MAX_VALUE;
        for (TermuxRestoreRecord record : records) {
            if (!isManagedRestoreRecord(record)) continue;
            int order = normalizeRestoreOrder(record.order);
            if (order == Integer.MAX_VALUE) continue;
            if (max == Integer.MAX_VALUE || order > max) max = order;
        }
        return max;
    }

    private boolean shouldDropDisposableGeneratedShellRestoreRecord(@NonNull TermuxRestoreRecord record,
                                                                    int maxManagedOrder) {
        return CodexRestoreStateMachine.shouldDropDisposableGeneratedShellProjection(
            new CodexRestoreStateMachine.DisposableShellProjectionInput(
                record.type,
                record.displayName,
                record.shellName,
                record.executable,
                record.arguments,
                !TextUtils.isEmpty(record.codexThreadId) ||
                    !TextUtils.isEmpty(record.codexRolloutPath),
                !TextUtils.isEmpty(record.sshPersistRecordId) ||
                    !TextUtils.isEmpty(record.sshCommand),
                !TextUtils.isEmpty(record.tmuxSession),
                true,
                record.order,
                maxManagedOrder));
    }

    private boolean isManagedRestoreRecord(@NonNull TermuxRestoreRecord record) {
        return !TERMUX_RESTORE_TYPE_SHELL.equals(record.type) ||
            !TextUtils.isEmpty(record.codexThreadId) ||
            !TextUtils.isEmpty(record.codexRolloutPath) ||
            !TextUtils.isEmpty(record.sshPersistRecordId) ||
            !TextUtils.isEmpty(record.sshCommand) ||
            !TextUtils.isEmpty(record.tmuxSession);
    }

    private boolean isSameTermuxRestoreRecord(@NonNull TermuxRestoreRecord left,
                                              @NonNull TermuxRestoreRecord right) {
        if (!TextUtils.isEmpty(left.key) && TextUtils.equals(left.key, right.key)) return true;
        if (!TextUtils.isEmpty(left.handle) && TextUtils.equals(left.handle, right.handle)) return true;
        return TextUtils.equals(buildTermuxRestoreRecordIdentity(left), buildTermuxRestoreRecordIdentity(right));
    }

    private void sortTermuxRestoreRecordsForMaterialization(@NonNull ArrayList<TermuxRestoreRecord> records) {
        records.sort((a, b) -> Integer.compare(normalizeRestoreOrder(a.order), normalizeRestoreOrder(b.order)));
    }

    private int normalizeRestoreOrder(int order) {
        return order < 0 ? Integer.MAX_VALUE : order;
    }

    private int getLiveSessionOrder(@Nullable TerminalSession terminalSession) {
        if (terminalSession == null) return Integer.MAX_VALUE;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return Integer.MAX_VALUE;
        int order = service.getIndexOfSession(terminalSession);
        return order < 0 ? Integer.MAX_VALUE : order;
    }

    @Nullable
    private TermuxRestoreRecord findNearestForegroundReplacement(@NonNull ArrayList<TermuxRestoreRecord> records,
                                                                 int droppedOrder) {
        if (records.isEmpty()) return null;
        TermuxRestoreRecord best = null;
        int bestDistance = Integer.MAX_VALUE;
        int target = normalizeRestoreOrder(droppedOrder);
        for (TermuxRestoreRecord record : records) {
            int order = normalizeRestoreOrder(record.order);
            int distance = target == Integer.MAX_VALUE || order == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : Math.abs(order - target);
            if (best == null || distance < bestDistance ||
                (distance == bestDistance && order < normalizeRestoreOrder(best.order))) {
                best = record;
                bestDistance = distance;
            }
        }
        return best;
    }

    @Nullable
    private TermuxRestoreRecord findForegroundReplacementByExactOrder(@NonNull ArrayList<TermuxRestoreRecord> records,
                                                                      int order) {
        int target = normalizeRestoreOrder(order);
        if (target == Integer.MAX_VALUE) return null;
        for (TermuxRestoreRecord record : records) {
            if (normalizeRestoreOrder(record.order) == target) return record;
        }
        return null;
    }

    @Nullable
    private String resolveCodexThreadIdFromRestoreRecord(@NonNull TermuxRestoreRecord record) {
        if (!TextUtils.isEmpty(record.codexThreadId)) {
            return normalizeCodexThreadId(record.codexThreadId);
        }
        if (!TextUtils.isEmpty(record.key) && record.key.startsWith("codex:")) {
            String value = record.key.substring("codex:".length()).trim();
            if (!value.isEmpty()) return normalizeCodexThreadId(value);
        }
        String script = extractShellScriptFromExecutionArgs(record.arguments);
        return normalizeCodexThreadId(extractShellOptionValue(script, "resume"));
    }

    @Nullable
    private TermuxRestoreRecord buildTermuxRestoreRecord(@Nullable TermuxSession termuxSession, int order, long now,
                                                         @NonNull ArrayList<CodexRestoreRecord> codexRecords,
                                                         @NonNull ArrayList<SshPersistenceRecord> sshRecords) {
        if (termuxSession == null || termuxSession.getTerminalSession() == null) return null;
        TerminalSession terminalSession = termuxSession.getTerminalSession();
        if (!terminalSession.isRunning()) return null;

        ExecutionCommand command = termuxSession.getExecutionCommand();
        if (command != null && command.isPluginExecutionCommand) return null;

        String handle = nullToEmpty(terminalSession.mHandle);
        String workingDirectory = resolveRestoreWorkingDirectory(terminalSession.getCwd());
        String displayName = buildTermuxRestoreDisplayName(terminalSession, command, order);
        String shellName = command == null || command.shellName == null ? "" : command.shellName.trim();
        String executable = command == null || command.executable == null ? "" : command.executable.trim();
        String[] args = command == null || command.arguments == null ? null : command.arguments.clone();

        CodexRestoreRecord inferredCodex = inferCodexRestoreRecordFromExecutionCommand(terminalSession);
        boolean liveCodexProcess = findActiveCodexProcessPid(terminalSession) > 0;
        if (isNativeCodexRestoreSuppressed(terminalSession)) {
            if (liveCodexProcess || inferredCodex != null || findPersistedCodexRestoreRecordForSession(terminalSession) != null) {
                return null;
            }
            clearNativeCodexRestoreSuppression(terminalSession);
        }

        CodexRestoreRecord codex = findCodexRestoreRecordForSession(terminalSession, codexRecords);
        if (codex == null && inferredCodex != null) {
            CodexRestoreRecord existing = findCodexRestoreRecordByThread(codexRecords, inferredCodex.threadId);
            if (existing != null) {
                codex = mergeCodexRestoreRecords(existing, inferredCodex);
            }
        }
        if (codex == null && inferredCodex != null) return null;
        if (codex != null) {
            String key = "codex:" + codex.threadId;
            return new TermuxRestoreRecord(key, TERMUX_RESTORE_TYPE_CODEX, handle, displayName,
                resolveCodexRestoreWorkingDirectory(codex.workingDirectory), shellName, executable, args,
                codex.threadId, codex.rolloutPath, null, null, null, order, now);
        }

        SshPersistenceRecord sshRecord = findSshPersistenceRecordForSession(terminalSession, sshRecords);
        if (sshRecord != null) {
            SshPersistenceRecord normalized = normalizeSshPersistenceRecord(sshRecord);
            String key = "ssh-tmux:" + normalized.id;
            return new TermuxRestoreRecord(key, TERMUX_RESTORE_TYPE_SSH_TMUX, handle,
                normalizeDisplayName(normalized.displayName, normalized.tmuxSession), workingDirectory,
                normalized.shellName, executable, args, null, null, normalized.id,
                normalized.sshCommand, normalized.tmuxSession, order, now);
        }

        String localTmuxSession = inferLocalTmuxSessionFromTermuxSession(termuxSession);
        if (!TextUtils.isEmpty(localTmuxSession)) {
            String normalizedTmux = normalizeTmuxSessionName(localTmuxSession);
            String key = "local-tmux:" + normalizedTmux + ":" + order;
            return new TermuxRestoreRecord(key, TERMUX_RESTORE_TYPE_LOCAL_TMUX, handle, displayName,
                workingDirectory, shellName, executable, args, null, null, null, null,
                normalizedTmux, order, now);
        }

        String sshCommand = inferSshCommandFromSession(termuxSession);
        if (!TextUtils.isEmpty(sshCommand)) {
            String key = "ssh:" + Integer.toHexString(sshCommand.hashCode()) + ":" + order;
            return new TermuxRestoreRecord(key, TERMUX_RESTORE_TYPE_SSH, handle, displayName,
                workingDirectory, shellName, executable, args, null, null, null,
                sanitizeSshBootstrapCommand(sshCommand), null, order, now);
        }

        if (isProotSession(command, shellName, args)) {
            String distro = inferProotDistro(command, shellName, args);
            String normalizedDistro = TextUtils.isEmpty(distro) ? getProotDefaultDistro() : distro;
            String key = "proot:" + normalizedDistro + ":" + order;
            return new TermuxRestoreRecord(key, TERMUX_RESTORE_TYPE_PROOT, handle, displayName,
                workingDirectory, shellName, executable, args, null, null, null, null,
                normalizedDistro, order, now);
        }

        if (shouldDropStaleCodexShellRestoreProjection(displayName, shellName, executable, args)) {
            return null;
        }

        String key = "shell:" + order + ":" + handle;
        return new TermuxRestoreRecord(key, TERMUX_RESTORE_TYPE_SHELL, handle, displayName,
            workingDirectory, shellName, executable, args, null, null, null, null, null,
            order, now);
    }

    @Nullable
    private TermuxSession createTermuxRestoreSession(@NonNull TermuxService service,
                                                     @NonNull TermuxRestoreRecord record,
                                                     @NonNull HashMap<String, CodexRestoreRecord> codexByThread,
                                                     @NonNull HashMap<String, SshPersistenceRecord> sshRecordsById) {
        String type = record.type;
        if (TERMUX_RESTORE_TYPE_CODEX.equals(type)) {
            CodexRestoreRecord codexRecord = null;
            String threadId = resolveCodexThreadIdFromRestoreRecord(record);
            if (!TextUtils.isEmpty(threadId)) {
                codexRecord = codexByThread.get(threadId);
            }
            if (codexRecord == null) codexRecord = buildCodexRestoreRecordFromTermuxRestoreRecord(record);
            if (codexRecord == null) return null;
            if (record.order != Integer.MAX_VALUE) {
                codexRecord = copyCodexRestoreRecordWithOrder(codexRecord, record.order);
            }
            return createCodexRestoreSession(service, codexRecord);
        }

        if (TERMUX_RESTORE_TYPE_SSH_TMUX.equals(type)) {
            SshPersistenceRecord sshRecord = null;
            if (!TextUtils.isEmpty(record.sshPersistRecordId)) {
                sshRecord = sshRecordsById.get(record.sshPersistRecordId);
            }
            if (sshRecord == null && !TextUtils.isEmpty(record.sshCommand)) {
                sshRecord = new SshPersistenceRecord(
                    TextUtils.isEmpty(record.sshPersistRecordId) ? UUID.randomUUID().toString() : record.sshPersistRecordId,
                    record.sshCommand,
                    record.tmuxSession == null ? "" : record.tmuxSession,
                    record.displayName,
                    TextUtils.isEmpty(record.shellName) ? "" : record.shellName,
                    null);
                upsertSshPersistenceRecord(sshRecord);
            }
            if (sshRecord == null) return null;
            SshPersistenceRecord ensured = ensurePinnedSshSessionRecord(service, sshRecord, false);
            if (ensured != null && !TextUtils.isEmpty(ensured.lockedHandle)) {
                upsertSshPersistenceRecord(ensured);
                TerminalSession terminalSession = service.getTerminalSessionForHandle(ensured.lockedHandle);
                return terminalSession == null ? null : service.getTermuxSessionForTerminalSession(terminalSession);
            }
            return null;
        }

        if (TERMUX_RESTORE_TYPE_LOCAL_TMUX.equals(type)) {
            return createLocalTmuxRestoreSession(service, record);
        }

        if (TERMUX_RESTORE_TYPE_SSH.equals(type)) {
            return createSshRestoreSession(service, record);
        }

        if (TERMUX_RESTORE_TYPE_PROOT.equals(type)) {
            return createProotRestoreSession(service, record);
        }

        if (shouldDropStaleCodexShellRestoreRecord(record)) return null;
        return createShellRestoreSession(service, record);
    }

    private boolean shouldDropStaleCodexShellRestoreRecord(@NonNull TermuxRestoreRecord record) {
        if (!TERMUX_RESTORE_TYPE_SHELL.equals(record.type)) return false;
        return CodexRestoreStateMachine.shouldDropStaleCodexShellProjection(
            new CodexRestoreStateMachine.ShellProjectionInput(
                record.type,
                record.displayName,
                record.shellName,
                record.executable,
                record.arguments,
                !TextUtils.isEmpty(record.codexThreadId) ||
                    !TextUtils.isEmpty(record.codexRolloutPath),
                !TextUtils.isEmpty(record.sshPersistRecordId) ||
                    !TextUtils.isEmpty(record.sshCommand),
                !TextUtils.isEmpty(record.tmuxSession)));
    }

    private boolean shouldDropStaleCodexShellRestoreProjection(@Nullable String displayName,
                                                               @Nullable String shellName,
                                                               @Nullable String executable,
                                                               @Nullable String[] arguments) {
        return CodexRestoreStateMachine.shouldDropStaleCodexShellProjection(
            new CodexRestoreStateMachine.ShellProjectionInput(
                TERMUX_RESTORE_TYPE_SHELL,
                displayName,
                shellName,
                executable,
                arguments,
                false,
                false,
                false));
    }

    @Nullable
    private TermuxSession createLocalTmuxRestoreSession(@NonNull TermuxService service,
                                                       @NonNull TermuxRestoreRecord record) {
        if (TextUtils.isEmpty(record.tmuxSession)) return createShellRestoreSession(service, record);
        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bash).exists()) return createShellRestoreSession(service, record);
        String workingDirectory = resolveRestoreWorkingDirectory(record.workingDirectory);
        String displayName = TextUtils.isEmpty(record.displayName) ? "tmux " + record.tmuxSession : record.displayName;
        String script = "cd " + quoteArg(workingDirectory) + " 2>/dev/null || cd " +
            quoteArg(TermuxConstants.TERMUX_HOME_DIR_PATH) + "; " +
            "if command -v tmux >/dev/null 2>&1 && tmux has-session -t " +
            buildTmuxTargetArg(record.tmuxSession) + " 2>/dev/null; then " +
            "exec tmux attach-session -t " + buildTmuxTargetArg(record.tmuxSession) + "; " +
            "else echo 'tmux 会话已不存在，已恢复到普通 shell'; exec " + quoteArg(bash) + " -l; fi";
        return createNamedTermuxSession(service, bash, new String[]{"-lc", script}, workingDirectory, displayName);
    }

    @Nullable
    private TermuxSession createSshRestoreSession(@NonNull TermuxService service,
                                                 @NonNull TermuxRestoreRecord record) {
        if (TextUtils.isEmpty(record.sshCommand)) return createShellRestoreSession(service, record);
        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bash).exists()) return createShellRestoreSession(service, record);
        String workingDirectory = resolveRestoreWorkingDirectory(record.workingDirectory);
        String script = "cd " + quoteArg(workingDirectory) + " 2>/dev/null || cd " +
            quoteArg(TermuxConstants.TERMUX_HOME_DIR_PATH) + "; exec " + record.sshCommand;
        return createNamedTermuxSession(service, bash, new String[]{"-lc", script}, workingDirectory, record.displayName);
    }

    @Nullable
    private TermuxSession createProotRestoreSession(@NonNull TermuxService service,
                                                   @NonNull TermuxRestoreRecord record) {
        String distro = TextUtils.isEmpty(record.tmuxSession) ? getProotDefaultDistro() : record.tmuxSession;
        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bash).exists()) return createShellRestoreSession(service, record);
        String workingDirectory = resolveRestoreWorkingDirectory(record.workingDirectory);
        String displayName = TextUtils.isEmpty(record.displayName) ? "proot-" + distro : record.displayName;
        return createNamedTermuxSession(service, bash, new String[]{"-lc", buildProotInteractiveCommand(distro)},
            workingDirectory, displayName);
    }

    @Nullable
    private TermuxSession createShellRestoreSession(@NonNull TermuxService service,
                                                   @NonNull TermuxRestoreRecord record) {
        String workingDirectory = resolveRestoreWorkingDirectory(record.workingDirectory);
        String displayName = TextUtils.isEmpty(record.displayName) ? null : record.displayName;
        return createNamedTermuxSession(service, null, null, workingDirectory, displayName);
    }

    @Nullable
    private TermuxSession createNamedTermuxSession(@NonNull TermuxService service, @Nullable String executable,
                                                  @Nullable String[] args, @NonNull String workingDirectory,
                                                  @Nullable String displayName) {
        TermuxSession created = service.createTermuxSession(executable, args, null, workingDirectory, false, displayName);
        if (created != null && created.getTerminalSession() != null && !TextUtils.isEmpty(displayName)) {
            created.getTerminalSession().mSessionName = displayName;
        }
        return created;
    }

    @NonNull
    private TermuxRestoreState loadTermuxRestoreState() {
        return parseTermuxRestoreState(TermuxSessionRestoreStore.readStateJson());
    }

    @NonNull
    private TermuxRestoreState parseTermuxRestoreState(@Nullable String raw) {
        ArrayList<TermuxRestoreRecord> records = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return new TermuxRestoreState(records, "", "");
        String foregroundKey = "";
        String foregroundHandle = "";
        int foregroundOrder = Integer.MAX_VALUE;
        try {
            Object parsed = new org.json.JSONTokener(raw).nextValue();
            if (!(parsed instanceof JSONObject)) return new TermuxRestoreState(records, "", "");
            JSONObject root = (JSONObject) parsed;
            foregroundKey = root.optString("foreground_key", "").trim();
            foregroundHandle = root.optString("foreground_handle", "").trim();
            foregroundOrder = root.optInt("foreground_order", Integer.MAX_VALUE);
            JSONArray array = root.optJSONArray("sessions");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) continue;
                    TermuxRestoreRecord record = TermuxRestoreRecord.fromJson(item);
                    if (record != null) records.add(record);
                }
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to parse Termux restore state: " + e.getMessage());
        }
        records.sort((a, b) -> {
            int orderCompare = Integer.compare(a.order, b.order);
            if (orderCompare != 0) return orderCompare;
            int updatedCompare = Long.compare(a.updatedAt, b.updatedAt);
            if (updatedCompare != 0) return updatedCompare;
            return a.key.compareTo(b.key);
        });
        return new TermuxRestoreState(records, foregroundKey, foregroundHandle, foregroundOrder);
    }

    @NonNull
    private String buildTermuxRestoreStateSignature(@NonNull ArrayList<TermuxRestoreRecord> records,
                                                    @NonNull String foregroundKey,
                                                    @NonNull String foregroundHandle) {
        return buildTermuxRestoreStateSignature(records, foregroundKey, foregroundHandle, Integer.MAX_VALUE);
    }

    @NonNull
    private String buildTermuxRestoreStateSignature(@NonNull ArrayList<TermuxRestoreRecord> records,
                                                    @NonNull String foregroundKey,
                                                    @NonNull String foregroundHandle,
                                                    int foregroundOrder) {
        StringBuilder signature = new StringBuilder();
        appendSignatureField(signature, foregroundKey);
        appendSignatureField(signature, foregroundHandle);
        appendSignatureField(signature, String.valueOf(normalizeRestoreOrder(foregroundOrder)));
        for (TermuxRestoreRecord record : records) {
            appendSignatureField(signature, record.key);
            appendSignatureField(signature, record.type);
            appendSignatureField(signature, record.handle);
            appendSignatureField(signature, record.displayName);
            appendSignatureField(signature, record.workingDirectory);
            appendSignatureField(signature, record.shellName);
            appendSignatureField(signature, record.executable);
            appendSignatureField(signature, record.codexThreadId);
            appendSignatureField(signature, record.codexRolloutPath);
            appendSignatureField(signature, record.sshPersistRecordId);
            appendSignatureField(signature, record.sshCommand);
            appendSignatureField(signature, record.tmuxSession);
            appendSignatureField(signature, String.valueOf(record.order));
            if (record.arguments != null) {
                for (String arg : record.arguments) {
                    appendSignatureField(signature, arg);
                }
            }
            appendSignatureField(signature, "\n");
        }
        return signature.toString();
    }

    private void appendSignatureField(@NonNull StringBuilder builder, @Nullable String value) {
        String normalized = nullToEmpty(value);
        builder.append(normalized.length()).append(':').append(normalized);
    }

    private void saveTermuxRestoreState(@NonNull TermuxRestoreState state, @NonNull String signature) {
        JSONObject root = new JSONObject();
        JSONArray array = new JSONArray();
        for (TermuxRestoreRecord record : state.records) {
            array.put(record.toJson());
        }

        try {
            root.put("version", TERMUX_RESTORE_VERSION);
            root.put("updated_at", System.currentTimeMillis() / 1000L);
            root.put("foreground_key", state.foregroundKey);
            root.put("foreground_handle", state.foregroundHandle);
            if (state.foregroundOrder != Integer.MAX_VALUE) {
                root.put("foreground_order", state.foregroundOrder);
            }
            root.put("sessions", array);
            String json = root.toString();
            if (TextUtils.equals(json, mLastTermuxRestoreStateJson)) return;
            if (TermuxSessionRestoreStore.writeStateJson(json)) {
                mLastTermuxRestoreStateSignature = signature;
                mLastTermuxRestoreStateJson = json;
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to write Termux restore state: " + e.getMessage());
        }
    }

    private void forgetTermuxRestoreForSession(@Nullable TerminalSession terminalSession) {
        if (terminalSession == null) return;
        String handle = nullToEmpty(terminalSession.mHandle);
        TermuxRestoreRecord active = buildTermuxRestoreRecord(terminalSession);
        TermuxRestoreState state = loadTermuxRestoreState();
        if (state.records.isEmpty()) return;

        boolean removed = false;
        ArrayList<TermuxRestoreRecord> kept = new ArrayList<>();
        for (TermuxRestoreRecord record : state.records) {
            boolean match = !TextUtils.isEmpty(handle) && TextUtils.equals(handle, record.handle);
            if (!match && active != null) {
                match = TextUtils.equals(active.key, record.key) ||
                    (!TextUtils.isEmpty(active.codexThreadId) &&
                        TextUtils.equals(active.codexThreadId, record.codexThreadId)) ||
                    (!TextUtils.isEmpty(active.sshPersistRecordId) &&
                        TextUtils.equals(active.sshPersistRecordId, record.sshPersistRecordId));
            }
            if (match) {
                removed = true;
            } else {
                kept.add(record);
            }
        }
        if (!removed) return;

        String foregroundKey = state.foregroundKey;
        if (active != null && TextUtils.equals(foregroundKey, active.key)) foregroundKey = "";
        String signature = buildTermuxRestoreStateSignature(kept, foregroundKey, "");
        saveTermuxRestoreState(new TermuxRestoreState(kept, foregroundKey, ""), signature);
    }

    @Nullable
    private TermuxRestoreRecord buildTermuxRestoreRecord(@Nullable TerminalSession terminalSession) {
        if (terminalSession == null) return null;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;
        TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession == null) return null;
        int order = service.getIndexOfSession(terminalSession);
        if (order < 0) order = 0;
        return buildTermuxRestoreRecord(termuxSession, order, System.currentTimeMillis() / 1000L,
            loadCodexRestoreRecords(), loadSshPersistenceRecords());
    }

    @NonNull
    private String nullToEmpty(@Nullable String value) {
        return normalizeNullableRestoreString(value);
    }

    @NonNull
    private String resolveRestoreWorkingDirectory(@Nullable String workingDirectory) {
        String value = workingDirectory == null ? "" : workingDirectory.trim();
        if (TextUtils.isEmpty(value) || !new File(value).isDirectory()) {
            value = mActivity.getProperties().getDefaultWorkingDirectory();
        }
        if (TextUtils.isEmpty(value) || !new File(value).isDirectory()) {
            value = TermuxConstants.TERMUX_HOME_DIR_PATH;
        }
        return value;
    }

    @NonNull
    private String buildTermuxRestoreDisplayName(@NonNull TerminalSession terminalSession,
                                                 @Nullable ExecutionCommand command,
                                                 int order) {
        if (!TextUtils.isEmpty(terminalSession.mSessionName)) return terminalSession.mSessionName.trim();
        if (command != null && !TextUtils.isEmpty(command.shellName)) return command.shellName.trim();
        String title = terminalSession.getTitle();
        if (!TextUtils.isEmpty(title)) return title.trim();
        return "Terminal " + (Math.max(0, order) + 1);
    }

    @Nullable
    private CodexRestoreRecord findCodexRestoreRecordForSession(@Nullable TerminalSession terminalSession,
                                                                @NonNull ArrayList<CodexRestoreRecord> records) {
        CodexRestoreRecord nativeRecord = findNativeCodexRestoreRecordForSession(terminalSession);
        if (nativeRecord != null) return nativeRecord;
        CodexRestoreRecord persistedRecord = findPersistedCodexRestoreRecordForSession(terminalSession);
        if (persistedRecord != null) return persistedRecord;

        int codexPid = findActiveCodexProcessPid(terminalSession);
        if (codexPid <= 0) return null;

        for (CodexRestoreRecord record : records) {
            if (record.pid == codexPid) return record;
        }
        return null;
    }

    @Nullable
    private SshPersistenceRecord findSshPersistenceRecordForSession(@Nullable TerminalSession terminalSession,
                                                                    @NonNull ArrayList<SshPersistenceRecord> records) {
        int index = findSshPersistenceRecordIndexForSession(terminalSession, records);
        if (index < 0 || index >= records.size()) return null;
        return normalizeSshPersistenceRecord(records.get(index));
    }

    @Nullable
    private String inferLocalTmuxSessionFromTermuxSession(@Nullable TermuxSession termuxSession) {
        if (termuxSession == null) return null;
        ExecutionCommand command = termuxSession.getExecutionCommand();
        if (command != null) {
            String script = extractShellScriptFromExecutionArgs(command.arguments);
            String fromScript = extractLocalTmuxSessionFromScript(script);
            if (!TextUtils.isEmpty(fromScript)) return fromScript;

            String fromArgv = extractTmuxSessionFromArgv(command.arguments);
            if (!TextUtils.isEmpty(fromArgv)) return fromArgv;
        }

        TerminalSession terminalSession = termuxSession.getTerminalSession();
        if (terminalSession == null || terminalSession.getPid() <= 0) return null;
        return inferLocalTmuxSessionFromProcessTree(terminalSession.getPid());
    }

    @Nullable
    private String inferLocalTmuxSessionFromProcessTree(int rootPid) {
        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        stack.add(rootPid);

        while (!stack.isEmpty()) {
            Integer pidObj = stack.pollLast();
            if (pidObj == null) continue;
            int pid = pidObj;
            if (pid <= 0 || !visited.add(pid)) continue;
            if (!isProcessAlive(pid)) continue;

            String tmuxPane = readProcessEnvironmentValue(pid, "TMUX_PANE");
            String fromPane = resolveTmuxSessionFromPane(tmuxPane);
            if (!TextUtils.isEmpty(fromPane)) return fromPane;

            String[] argv = readCmdlineArguments(pid);
            String fromArgv = extractTmuxSessionFromArgv(argv);
            if (!TextUtils.isEmpty(fromArgv)) return fromArgv;

            for (int childPid : readChildPids(pid)) {
                if (childPid > 0) stack.add(childPid);
            }
        }

        return null;
    }

    @Nullable
    private String resolveTmuxSessionFromPane(@Nullable String tmuxPane) {
        if (TextUtils.isEmpty(tmuxPane)) return null;
        String tmuxPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/tmux";
        if (!new File(tmuxPath).exists()) return null;
        CommandResult result = runBashCommandSync(tmuxPath + " display-message -p -t " +
            quoteArg(tmuxPane.trim()) + " '#S' 2>/dev/null");
        if (!result.isSuccess() || TextUtils.isEmpty(result.stdout)) return null;
        return normalizeTmuxSessionName(result.stdout.trim().split("\\r?\\n")[0]);
    }

    @Nullable
    private String extractLocalTmuxSessionFromScript(@Nullable String script) {
        if (TextUtils.isEmpty(script)) return null;
        if (script.contains("[ssh-persist]")) return null;

        String fromReconnectParser = extractTmuxSessionFromReconnectLoopScript(script);
        if (!TextUtils.isEmpty(fromReconnectParser)) return fromReconnectParser;

        String[] tokens = script.split("\\s+");
        return extractTmuxSessionFromArgv(tokens);
    }

    @Nullable
    private String extractTmuxSessionFromArgv(@Nullable String[] argv) {
        if (argv == null || argv.length == 0) return null;
        for (int i = 0; i < argv.length; i++) {
            String token = argv[i];
            if (TextUtils.isEmpty(token) || !isTmuxExecutable(token)) continue;

            for (int j = i + 1; j < argv.length; j++) {
                String arg = argv[j] == null ? "" : argv[j].trim();
                if (arg.isEmpty()) continue;
                if ("-t".equals(arg) || "--target".equals(arg) || "-s".equals(arg)) {
                    if (j + 1 < argv.length) return cleanTmuxSessionToken(argv[j + 1]);
                    return null;
                }
                if (arg.startsWith("-t") && arg.length() > 2) {
                    return cleanTmuxSessionToken(arg.substring(2));
                }
                if (arg.startsWith("-s") && arg.length() > 2) {
                    return cleanTmuxSessionToken(arg.substring(2));
                }
            }
        }
        return null;
    }

    @Nullable
    private String cleanTmuxSessionToken(@Nullable String raw) {
        if (TextUtils.isEmpty(raw)) return null;
        String value = raw.trim();
        while (value.endsWith(";") || value.endsWith(",")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if ((value.startsWith("'") && value.endsWith("'")) ||
            (value.startsWith("\"") && value.endsWith("\""))) {
            value = value.substring(1, value.length() - 1);
        }
        if (TextUtils.isEmpty(value)) return null;
        return normalizeTmuxSessionName(value);
    }

    private boolean isTmuxExecutable(@Nullable String executable) {
        if (TextUtils.isEmpty(executable)) return false;
        String name = new File(executable).getName().toLowerCase(Locale.ROOT);
        return "tmux".equals(name) || "tmux.exe".equals(name);
    }

    private boolean isProotSession(@Nullable ExecutionCommand command, @Nullable String shellName,
                                   @Nullable String[] args) {
        if (!TextUtils.isEmpty(shellName) && shellName.trim().startsWith("proot-")) return true;
        String script = command == null ? null : extractShellScriptFromExecutionArgs(command.arguments);
        if (!TextUtils.isEmpty(script) && script.contains("proot-distro login")) return true;
        if (args != null) {
            for (String arg : args) {
                if (!TextUtils.isEmpty(arg) && arg.contains("proot-distro login")) return true;
            }
        }
        return false;
    }

    @NonNull
    private String inferProotDistro(@Nullable ExecutionCommand command, @Nullable String shellName,
                                    @Nullable String[] args) {
        if (!TextUtils.isEmpty(shellName) && shellName.trim().startsWith("proot-")) {
            String distro = shellName.trim().substring("proot-".length()).trim();
            if (!distro.isEmpty()) return distro;
        }

        String script = command == null ? null : extractShellScriptFromExecutionArgs(command.arguments);
        String fromScript = extractProotDistro(script);
        if (!TextUtils.isEmpty(fromScript)) return fromScript;

        if (args != null) {
            for (String arg : args) {
                String fromArg = extractProotDistro(arg);
                if (!TextUtils.isEmpty(fromArg)) return fromArg;
            }
        }
        return getProotDefaultDistro();
    }

    @Nullable
    private String extractProotDistro(@Nullable String text) {
        if (TextUtils.isEmpty(text)) return null;
        String marker = "proot-distro login ";
        int start = text.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (Character.isWhitespace(c) || c == ';') break;
            end++;
        }
        if (end <= start) return null;
        String distro = text.substring(start, end).trim();
        return distro.isEmpty() ? null : distro;
    }

    @Nullable
    private String extractSshCommandFromArgs(@Nullable String args) {
        if (TextUtils.isEmpty(args)) return null;
        String value = args.trim();
        if (value.isEmpty()) return null;

        String[] tokens = value.split("\\s+");
        if (tokens.length == 0) return null;

        if (isSshExecutable(tokens[0])) {
            String[] sshArgs = null;
            if (tokens.length > 1) {
                sshArgs = new String[tokens.length - 1];
                System.arraycopy(tokens, 1, sshArgs, 0, sshArgs.length);
            }
            return buildCommandLine("ssh", sshArgs);
        }

        int idx = value.indexOf(" ssh ");
        if (idx >= 0) {
            return value.substring(idx + 1).trim();
        }
        if (value.startsWith("ssh ")) {
            return value;
        }
        int pathIdx = value.indexOf("/ssh ");
        if (pathIdx >= 0) {
            int start = value.lastIndexOf(' ', pathIdx);
            return value.substring(start < 0 ? 0 : start + 1).trim();
        }

        return null;
    }

    @Nullable
    private String inferSshCommandFromPid(int pid) {
        if (pid <= 0) return null;

        String[] argv = readCmdlineArguments(pid);
        String fromArgv = extractSshCommandFromArgv(argv);
        if (!TextUtils.isEmpty(fromArgv)) return fromArgv;

        String processName = readProcessName(pid);
        if (!TextUtils.isEmpty(processName) && isSshExecutable(processName)) {
            return "ssh";
        }

        return null;
    }

    @Nullable
    private String extractSshCommandFromArgv(@Nullable String[] argv) {
        if (argv == null || argv.length == 0) return null;

        for (int i = 0; i < argv.length; i++) {
            String token = argv[i];
            if (TextUtils.isEmpty(token)) continue;

            if (isSshExecutable(token)) {
                String[] sshArgs = null;
                if (i + 1 < argv.length) {
                    sshArgs = new String[argv.length - i - 1];
                    System.arraycopy(argv, i + 1, sshArgs, 0, sshArgs.length);
                }
                return buildCommandLine("ssh", sshArgs);
            }

            if ("-c".equals(token) && i + 1 < argv.length) {
                String fromShellArg = extractSshCommandFromArgs(argv[i + 1]);
                if (!TextUtils.isEmpty(fromShellArg)) return fromShellArg;
            }
        }

        StringBuilder joined = new StringBuilder();
        for (String arg : argv) {
            if (TextUtils.isEmpty(arg)) continue;
            if (joined.length() > 0) joined.append(' ');
            joined.append(arg);
        }
        if (joined.length() == 0) return null;
        return extractSshCommandFromArgs(joined.toString());
    }

    @Nullable
    private ProcessStatus readProcessStatus(int pid) {
        if (pid <= 0) return null;
        File statusFile = new File("/proc/" + pid + "/status");
        if (!statusFile.exists()) return null;

        String name = null;
        int ppid = -1;
        try (BufferedReader reader = new BufferedReader(new FileReader(statusFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Name:")) {
                    name = line.substring("Name:".length()).trim();
                } else if (line.startsWith("PPid:")) {
                    try {
                        ppid = Integer.parseInt(line.substring("PPid:".length()).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (name != null && ppid >= 0) break;
            }
        } catch (Exception ignored) {
            return null;
        }

        if (ppid < 0) return null;
        return new ProcessStatus(pid, ppid, name);
    }

    private static final class ProcessStatus {
        final int pid;
        final int ppid;
        @Nullable final String name;

        ProcessStatus(int pid, int ppid, @Nullable String name) {
            this.pid = pid;
            this.ppid = ppid;
            this.name = name;
        }
    }

    private int findActiveSshProcessPid(int rootPid) {
        if (rootPid <= 0) return -1;

        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        stack.add(rootPid);

        int found = -1;
        while (!stack.isEmpty()) {
            Integer pidObj = stack.pollLast();
            if (pidObj == null) continue;
            int pid = pidObj;
            if (pid <= 0 || !visited.add(pid)) continue;
            if (!isProcessAlive(pid)) continue;

            String processName = readProcessName(pid);
            if (!TextUtils.isEmpty(processName) && isSshExecutable(processName)) {
                found = pid;
            }

            int[] children = readChildPids(pid);
            for (int childPid : children) {
                if (childPid > 0) stack.add(childPid);
            }
        }

        return found;
    }

    private boolean isProcessAlive(int pid) {
        String stat = readFirstLine(new File("/proc/" + pid + "/stat"));
        if (TextUtils.isEmpty(stat)) return false;

        int marker = stat.lastIndexOf(") ");
        if (marker < 0 || marker + 2 >= stat.length()) return true;
        char state = stat.charAt(marker + 2);
        return state != 'Z' && state != 'X';
    }

    @Nullable
    private String readProcessName(int pid) {
        String comm = readFirstLine(new File("/proc/" + pid + "/comm"));
        if (!TextUtils.isEmpty(comm)) return comm;

        String[] argv = readCmdlineArguments(pid);
        if (argv == null || argv.length == 0 || TextUtils.isEmpty(argv[0])) return null;
        return new File(argv[0]).getName();
    }

    @NonNull
    private int[] readChildPids(int pid) {
        String children = readFirstLine(new File("/proc/" + pid + "/task/" + pid + "/children"));
        if (TextUtils.isEmpty(children)) return new int[0];

        String[] tokens = children.trim().split("\\s+");
        ArrayList<Integer> ids = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            if (TextUtils.isEmpty(token)) continue;
            try {
                ids.add(Integer.parseInt(token));
            } catch (NumberFormatException ignored) {
            }
        }

        int[] result = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            result[i] = ids.get(i);
        }
        return result;
    }

    @Nullable
    private String[] readCmdlineArguments(int pid) {
        File file = new File("/proc/" + pid + "/cmdline");
        if (!file.exists()) return null;

        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read > 0) out.write(buffer, 0, read);
            }

            byte[] raw = out.toByteArray();
            if (raw.length == 0) return null;

            ArrayList<String> args = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] == 0) {
                    if (i > start) {
                        String arg = new String(raw, start, i - start, StandardCharsets.UTF_8).trim();
                        if (!arg.isEmpty()) args.add(arg);
                    }
                    start = i + 1;
                }
            }
            if (start < raw.length) {
                String arg = new String(raw, start, raw.length - start, StandardCharsets.UTF_8).trim();
                if (!arg.isEmpty()) args.add(arg);
            }

            if (args.isEmpty()) return null;
            return args.toArray(new String[0]);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private String readFirstLine(@NonNull File file) {
        if (!file.exists()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line == null ? null : line.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    @NonNull
    private String buildCommandLine(@NonNull String executable, @Nullable String[] arguments) {
        StringBuilder sb = new StringBuilder(executable);
        if (arguments != null) {
            for (String arg : arguments) {
                if (arg == null) continue;
                sb.append(" ").append(quoteArg(arg));
            }
        }
        return sb.toString().trim();
    }

    private boolean isSshExecutable(@Nullable String executable) {
        if (TextUtils.isEmpty(executable)) return false;
        String name = new File(executable).getName().toLowerCase(Locale.ROOT);
        return "ssh".equals(name) || "ssh.exe".equals(name);
    }

    @NonNull
    private String quoteArg(@NonNull String value) {
        if (value.isEmpty()) return "''";
        if (value.matches("[A-Za-z0-9_./:@%+=,-]+")) return value;
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @NonNull
    private String buildTmuxCheckCommand(@NonNull String sshCommand) {
        String remoteCheck = "command -v tmux >/dev/null 2>&1 && echo __TMUX_OK__ || echo __TMUX_MISSING__";
        return buildSshRemoteExecCommand(sshCommand, remoteCheck);
    }

    @NonNull
    private String buildSshRemoteExecCommand(@NonNull String sshCommand, @NonNull String remoteCommand) {
        StringBuilder cmd = new StringBuilder(sshCommand);
        if (!isSshpassCommand(sshCommand)) {
            // For key-based mode, force non-interactive to guarantee background restore won't block.
            cmd.append(" -o BatchMode=yes");
        }
        cmd.append(" -o ConnectTimeout=8");
        cmd.append(" -o ServerAliveInterval=8 -o ServerAliveCountMax=1");
        cmd.append(" -o StrictHostKeyChecking=yes");
        cmd.append(" ").append(quoteArg(remoteCommand));
        return cmd.toString();
    }

    @NonNull
    private String buildTmuxListSessionsCommand(@NonNull String sshCommand) {
        String remoteList =
            "if command -v tmux >/dev/null 2>&1; then " +
                "tmux list-sessions -F '__TMUX_ITEM__|#{session_name}|#{session_windows}|#{session_attached}|#{" +
                SshTmuxSessionStateMachine.TMUX_DISPLAY_NAME_OPTION + "}' 2>/dev/null || true; " +
                "echo __TMUX_LIST_DONE__; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        return buildSshRemoteExecCommand(sshCommand, remoteList);
    }

    @NonNull
    private String buildTmuxCreateSessionCommand(@NonNull String sshCommand, @NonNull String tmuxSession,
                                                 @NonNull String displayName) {
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String target = buildTmuxTargetArg(safeTmuxSession);
        String remoteCreate =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if tmux has-session -t " + target + " 2>/dev/null; then echo __TMUX_EXISTS__; exit 5; fi; " +
                "if tmux new-session -d -s " + target + "; then " +
                    buildTmuxDisplaySyncCommand(safeTmuxSession, displayName) + "; " +
                    "echo __TMUX_CREATED__; " +
                "else exit $?; fi; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        return buildSshRemoteExecCommand(sshCommand, remoteCreate);
    }

    @NonNull
    private String buildTmuxKillSessionCommand(@NonNull String sshCommand, @NonNull String tmuxSession) {
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String target = buildTmuxTargetArg(safeTmuxSession);
        String remoteDestroy =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if tmux has-session -t " + target + " 2>/dev/null; then " +
                    "tmux kill-session -t " + target + " && echo __TMUX_KILLED__; " +
                "else echo __TMUX_NOT_FOUND__; exit 3; fi; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        return buildSshRemoteExecCommand(sshCommand, remoteDestroy);
    }

    private boolean isSshpassCommand(@NonNull String sshCommand) {
        String trimmed = sshCommand.trim();
        return trimmed.startsWith("sshpass ");
    }

    @NonNull
    private String buildTmuxInstallCommand(@NonNull String sshCommand) {
        String remoteInstall =
            "if command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y tmux; " +
            "elif command -v dnf >/dev/null 2>&1; then sudo dnf install -y tmux; " +
            "elif command -v yum >/dev/null 2>&1; then sudo yum install -y tmux; " +
            "elif command -v pacman >/dev/null 2>&1; then sudo pacman -Sy --noconfirm tmux; " +
            "elif command -v apk >/dev/null 2>&1; then sudo apk add tmux; " +
            "else echo __NO_PKG_MANAGER__; exit 127; fi";
        return sshCommand + " -tt \"" + escapeForDoubleQuotes(remoteInstall) + "\"";
    }

    @NonNull
    private String buildReconnectLoopCommand(@NonNull String sshCommand, @NonNull String tmuxSession,
                                             @NonNull String displayName) {
        sshCommand = sanitizeSshBootstrapCommand(sshCommand);
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String target = buildTmuxTargetArg(safeTmuxSession);
        String remoteEnsure =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if ! tmux has-session -t " + target + " 2>/dev/null; then tmux new-session -d -s " + target + " || exit $?; fi; " +
                buildTmuxDisplaySyncCommand(safeTmuxSession, displayName) + "; " +
                "echo __TMUX_READY__; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        String remoteAttach =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if tmux has-session -t " + target + " 2>/dev/null; then " +
                    buildTmuxAttachOnlyCommand(safeTmuxSession, displayName) + "; " +
                "else echo __TMUX_GONE__; exit 43; fi; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        String quotedRemoteEnsure = quoteArg(remoteEnsure);
        String quotedRemoteAttach = quoteArg(remoteAttach);

        return "init=0; while true; do " +
            "if [ \"$init\" -eq 0 ]; then " +
            sshCommand + " -tt " + quotedRemoteEnsure + "; " +
            "ready=$?; " +
            "if [ \"$ready\" -eq 42 ]; then " +
            "echo \"[ssh-persist] tmux missing on server\"; sleep 8; continue; fi; " +
            "if [ \"$ready\" -ne 0 ]; then " +
            "echo \"[ssh-persist] bootstrap failed ($ready), retrying in 2s...\"; sleep 2; continue; fi; " +
            "init=1; fi; " +
            sshCommand + " -tt " + quotedRemoteAttach + "; " +
            "code=$?; " +
            "if [ \"$code\" -eq 42 ]; then " +
            "echo \"[ssh-persist] tmux missing on server\"; sleep 8; " +
            "elif [ \"$code\" -eq 43 ]; then " +
            "echo \"[ssh-persist] remote tmux session removed, stop reconnect loop\"; break; " +
            "else echo \"[ssh-persist] disconnected ($code), reconnecting in 2s...\"; sleep 2; fi; " +
            "done";
    }

    @NonNull
    private String buildTmuxAttachOnlyCommand(@NonNull String tmuxSession, @NonNull String displayName) {
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String target = buildTmuxTargetArg(safeTmuxSession);
        return buildTmuxDisplaySyncCommand(safeTmuxSession, displayName) + "; " +
            "tmux set-option -t " + target + " mouse on >/dev/null 2>&1; " +
            "tmux set-window-option -t " + target + " alternate-screen off >/dev/null 2>&1; " +
            "tmux set-option -t " + target + " history-limit " + SSH_PERSIST_TMUX_PRELOAD_LINES + " >/dev/null 2>&1; " +
            // Dump recent pane output before attach so local transcript has cache immediately.
            "pane=$(tmux display-message -p -t " + target + " '#{session_name}:#{window_index}.#{pane_index}' 2>/dev/null); " +
            "[ -n \"$pane\" ] && tmux capture-pane -p -t \"$pane\" -S -" + SSH_PERSIST_TMUX_PRELOAD_LINES + " 2>/dev/null || true; " +
            "tmux attach-session -t " + target;
    }

    @NonNull
    private String buildTmuxEnsureAndAttachCommand(@NonNull String tmuxSession, @NonNull String displayName) {
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String target = buildTmuxTargetArg(safeTmuxSession);
        return "tmux has-session -t " + target + " 2>/dev/null || tmux new-session -d -s " + target +
            "; " + buildTmuxAttachOnlyCommand(safeTmuxSession, displayName);
    }

    @NonNull
    private String escapeForDoubleQuotes(@NonNull String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @NonNull
    private String normalizeTmuxSessionName(@Nullable String raw) {
        String value = SshTmuxSessionStateMachine.normalizeRemoteSessionName(raw);
        return value.isEmpty() ? DEFAULT_SSH_TMUX_SESSION : value;
    }

    @NonNull
    private String normalizeDisplayName(@Nullable String raw, @Nullable String fallback) {
        return SshTmuxSessionStateMachine.normalizeDisplayName(raw, fallback);
    }

    @NonNull
    private String sanitizeTmuxSessionName(@Nullable String raw) {
        return normalizeTmuxSessionName(raw);
    }

    @NonNull
    private String buildTmuxTargetArg(@Nullable String tmuxSession) {
        return quoteArg(normalizeTmuxSessionName(tmuxSession));
    }

    @NonNull
    private String buildTmuxDisplaySyncCommand(@NonNull String tmuxSession, @Nullable String displayName) {
        String encoded = SshTmuxSessionStateMachine.encodeDisplayNameHex(normalizeDisplayName(displayName, tmuxSession));
        return "tmux set-option -q -t " + buildTmuxTargetArg(tmuxSession) + " " +
            SshTmuxSessionStateMachine.TMUX_DISPLAY_NAME_OPTION + " " + quoteArg(encoded) + " >/dev/null 2>&1";
    }

    @NonNull
    private String buildTmuxDisplaySyncRemoteExecCommand(@NonNull String sshCommand, @NonNull String tmuxSession,
                                                         @Nullable String displayName) {
        String target = buildTmuxTargetArg(tmuxSession);
        String remoteSync =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if tmux has-session -t " + target + " 2>/dev/null; then " +
                    buildTmuxDisplaySyncCommand(tmuxSession, displayName) + "; " +
                "else echo __TMUX_NOT_FOUND__; exit 3; fi; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        return buildSshRemoteExecCommand(sshCommand, remoteSync);
    }

    @NonNull
    private String unquoteShellToken(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return normalizeTmuxSessionName(null);
        if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
            value = value.replace("'\"'\"'", "'");
        }
        return normalizeTmuxSessionName(value);
    }

    private boolean isReconnectLoopSession(@Nullable TermuxSession termuxSession) {
        if (termuxSession == null || termuxSession.getExecutionCommand() == null) return false;
        String script = extractShellScriptFromExecutionArgs(termuxSession.getExecutionCommand().arguments);
        return isReconnectLoopScript(script);
    }

    private boolean isReconnectLoopScript(@Nullable String script) {
        if (TextUtils.isEmpty(script)) return false;
        String s = script.trim();
        return s.contains("while true; do") && s.contains("[ssh-persist]");
    }

    @Nullable
    private String extractSshCommandFromReconnectLoop(@Nullable String script) {
        if (!isReconnectLoopScript(script)) return null;
        String s = script.trim();
        int loopStart = s.indexOf("while true; do");
        if (loopStart < 0) return null;

        int sshStart = s.indexOf("sshpass ", loopStart);
        int plainSshStart = s.indexOf("ssh ", loopStart);
        if (sshStart < 0 || (plainSshStart >= 0 && plainSshStart < sshStart)) {
            sshStart = plainSshStart;
        }
        if (sshStart < 0) return null;

        int end = s.indexOf(" -tt ", sshStart);
        if (end <= sshStart) return null;
        String command = s.substring(sshStart, end).trim();
        return command.isEmpty() ? null : command;
    }

    @NonNull
    private String sanitizeSshBootstrapCommand(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        String extracted = extractSshCommandFromReconnectLoop(value);
        return TextUtils.isEmpty(extracted) ? value : extracted;
    }

    @NonNull
    private SshTmuxSessionStateMachine.Snapshot generatePersistentTmuxSessionPlan(@NonNull TerminalSession session,
                                                                                  @NonNull String sshCommand) {
        String fallback = !TextUtils.isEmpty(session.mSessionName) &&
            !SshTmuxSessionStateMachine.looksLikeOpaqueInternalName(session.mSessionName)
            ? session.mSessionName
            : session.getTitle();
        return SshTmuxSessionStateMachine.planNewManagedSession(null, fallback, sshCommand, session.mHandle);
    }

    private SharedPreferences getSshPersistPrefs() {
        Context c = mActivity.getApplicationContext();
        return c.getSharedPreferences(SSH_PERSIST_PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    private CommandResult runBashCommandSync(@NonNull String shellCommand) {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bashPath).exists()) {
            return new CommandResult(1, "", "bash not found");
        }

        ExecutionCommand executionCommand = new ExecutionCommand(-1, bashPath,
            new String[]{"-lc", shellCommand},
            null, TermuxConstants.TERMUX_HOME_DIR_PATH,
            ExecutionCommand.Runner.APP_SHELL.getName(), false);
        executionCommand.commandLabel = "SSH Persistence";
        executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_OFF;
        executionCommand.setShellCommandShellEnvironment = true;

        AppShell appShell = AppShell.execute(mActivity, executionCommand, null, new TermuxShellEnvironment(), null, true);
        if (appShell == null) {
            return new CommandResult(1, "", "failed to start shell command");
        }

        Integer exitCode = executionCommand.resultData.exitCode;
        String stdout = executionCommand.resultData.stdout.toString().trim();
        String stderr = executionCommand.resultData.stderr.toString().trim();
        return new CommandResult(exitCode == null ? 1 : exitCode, stdout, stderr);
    }

    private static final class CommandResult {
        final int exitCode;
        @NonNull final String stdout;
        @NonNull final String stderr;

        CommandResult(int exitCode, @NonNull String stdout, @NonNull String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        boolean isSuccess() {
            return exitCode == 0;
        }
    }

    public void setCurrentStoredSession() {
        setCurrentStoredSession(getLastExplicitSelectedSessionOrCurrent());
    }

    private void setCurrentStoredSession(@Nullable TerminalSession currentSession) {
        if (currentSession != null)
            mActivity.getPreferences().setCurrentSession(currentSession.mHandle);
        else
            mActivity.getPreferences().setCurrentSession(null);
    }

    @Nullable
    private TerminalSession getLastExplicitSelectedSessionOrCurrent() {
        TermuxService service = mActivity.getTermuxService();
        if (service != null && !TextUtils.isEmpty(mLastExplicitSelectedSessionHandle)) {
            TerminalSession selected = service.getTerminalSessionForHandle(mLastExplicitSelectedSessionHandle);
            if (selected != null) return selected;
        }
        return mActivity.getCurrentSession();
    }

    /** The current session as stored or the last one if that does not exist. */
    public TerminalSession getCurrentStoredSessionOrLast() {
        TerminalSession stored = getCurrentStoredSession();

        if (stored != null) {
            // If a stored session is in the list of currently running sessions, then return it
            return stored;
        } else {
            // Else return the last session currently running
            TermuxService service = mActivity.getTermuxService();
            if (service == null) return null;

            TermuxSession termuxSession = service.getLastTermuxSession();
            if (termuxSession != null)
                return termuxSession.getTerminalSession();
            else
                return null;
        }
    }

    public TerminalSession getRestoreForegroundSessionOrStoredOrLast() {
        TerminalSession restoreForeground = getRestoreForegroundSession();
        return restoreForeground != null ? restoreForeground : getCurrentStoredSessionOrLast();
    }

    @Nullable
    private TerminalSession getRestoreForegroundSession() {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;

        TermuxRestoreState state = loadTermuxRestoreState();
        String foregroundThreadId = "";
        if (!TextUtils.isEmpty(state.foregroundKey) && state.foregroundKey.startsWith("codex:")) {
            foregroundThreadId = state.foregroundKey.substring("codex:".length()).trim();
        }

        for (TermuxSession termuxSession : service.getTermuxSessions()) {
            if (termuxSession == null || termuxSession.getTerminalSession() == null) continue;
            TerminalSession terminalSession = termuxSession.getTerminalSession();
            TermuxRestoreRecord record = buildTermuxRestoreRecord(terminalSession);
            if (record == null) continue;
            if (!TextUtils.isEmpty(state.foregroundKey) && TextUtils.equals(state.foregroundKey, record.key)) {
                return terminalSession;
            }
            if (!TextUtils.isEmpty(foregroundThreadId) &&
                TextUtils.equals(foregroundThreadId, resolveCodexThreadIdFromRestoreRecord(record))) {
                return terminalSession;
            }
        }

        if (!TextUtils.isEmpty(state.foregroundHandle)) {
            TerminalSession byHandle = service.getTerminalSessionForHandle(state.foregroundHandle);
            if (byHandle != null) return byHandle;
        }

        if (state.foregroundOrder != Integer.MAX_VALUE) {
            int index = normalizeRestoreOrder(state.foregroundOrder);
            if (index >= 0 && index < service.getTermuxSessionsSize()) {
                TermuxSession termuxSession = service.getTermuxSession(index);
                if (termuxSession != null) return termuxSession.getTerminalSession();
            }
        }

        return null;
    }

    private TerminalSession getCurrentStoredSession() {
        String sessionHandle = mActivity.getPreferences().getCurrentSession();

        // If no session is stored in shared preferences
        if (sessionHandle == null)
            return null;

        // Check if the session handle found matches one of the currently running sessions
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;

        return service.getTerminalSessionForHandle(sessionHandle);
    }

    public void removeFinishedSession(TerminalSession finishedSession) {
        // Return pressed with finished session - remove it.
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        if (service.getCodexSessionRecoveryController().handleFinishedSession(finishedSession)) return;

        forgetTermuxRestoreForSession(finishedSession);
        forgetNativeCodexRestoreForSession(finishedSession);
        if (service.getTermuxSessionsSize() <= 1 && !ensureSessionBeforeClosingLastTab(finishedSession)) {
            return;
        }

        int index = service.removeTermuxSession(finishedSession);

        int size = service.getTermuxSessionsSize();
        if (size == 0) {
            ensureSessionBeforeClosingLastTab(null);
        } else {
            if (index >= size) {
                index = size - 1;
            }
            TermuxSession termuxSession = service.getTermuxSession(index);
            if (termuxSession != null)
                setCurrentSession(termuxSession.getTerminalSession());
        }
    }

    public void termuxSessionListNotifyUpdated() {
        mActivity.termuxSessionListNotifyUpdated();
    }

    public void checkAndScrollToSession(TerminalSession session) {
        if (!mActivity.isVisible()) return;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        final int indexOfSession = service.getIndexOfSession(session);
        if (indexOfSession < 0) return;
        final ListView termuxSessionsListView = mActivity.findViewById(R.id.terminal_sessions_list);
        if (termuxSessionsListView == null) return;

        termuxSessionsListView.setItemChecked(indexOfSession, true);
        if (!termuxSessionsListView.isShown() || !termuxSessionsListView.isAttachedToWindow()) return;
        // Delay is necessary otherwise sometimes scroll to newly added session does not happen
        termuxSessionsListView.postDelayed(() -> termuxSessionsListView.smoothScrollToPosition(indexOfSession), 1000);
    }


    String toToastTitle(TerminalSession session) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;

        final int indexOfSession = service.getIndexOfSession(session);
        if (indexOfSession < 0) return null;
        StringBuilder toastTitle = new StringBuilder("[" + (indexOfSession + 1) + "]");
        if (!TextUtils.isEmpty(session.mSessionName)) {
            toastTitle.append(" ").append(session.mSessionName);
        }
        String title = session.getTitle();
        if (!TextUtils.isEmpty(title)) {
            // Space to "[${NR}] or newline after session name:
            toastTitle.append(session.mSessionName == null ? " " : "\n");
            toastTitle.append(title);
        }
        return toastTitle.toString();
    }


    public void checkForFontAndColors() {
        try {
            File colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
            File fontFile = TermuxConstants.TERMUX_FONT_FILE;

            final Properties props = new Properties();
            if (colorsFile.isFile()) {
                try (InputStream in = new FileInputStream(colorsFile)) {
                    props.load(in);
                }
            }

            TerminalColors.COLOR_SCHEME.updateWith(props);
            TerminalSession session = mActivity.getCurrentSession();
            if (session != null && session.getEmulator() != null) {
                session.getEmulator().mColors.reset();
            }
            updateBackgroundColor();

            final Typeface newTypeface = (fontFile.exists() && fontFile.length() > 0) ? Typeface.createFromFile(fontFile) : Typeface.MONOSPACE;
            mActivity.applyTerminalSessionSurfaceTypeface(newTypeface);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in checkForFontAndColors()", e);
        }
    }

    private boolean hasCustomFontOrColors() {
        File colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
        File fontFile = TermuxConstants.TERMUX_FONT_FILE;
        return colorsFile.isFile() || (fontFile.exists() && fontFile.length() > 0);
    }

    public void updateBackgroundColor() {
        if (!mActivity.isVisible()) return;
        if (!mActivity.isTerminalTabActive()) return;
        TerminalSession session = mActivity.getCurrentSession();
        if (session != null && session.getEmulator() != null) {
            mActivity.getWindow().getDecorView().setBackgroundColor(session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        }
    }

}
