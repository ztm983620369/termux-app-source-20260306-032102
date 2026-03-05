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
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TextStyle;

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

    private SoundPool mBellSoundPool;

    private int mBellSoundId;

    private static final String LOG_TAG = "TermuxTerminalSessionActivityClient";
    private static final int SSH_BG_MAX_THREADS = 4;
    private static final long SSH_BG_KEEP_ALIVE_SECONDS = 30L;
    private static final AtomicInteger SSH_BG_THREAD_COUNTER = new AtomicInteger(1);
    private static final ExecutorService SSH_BG_EXECUTOR = createSshBackgroundExecutor();
    private final Map<String, String> mSshBootstrapCommandByHandle = new HashMap<>();
    private final AtomicBoolean mEnsuringPinnedSshSessions = new AtomicBoolean(false);
    private final AtomicBoolean mEnsurePinnedSshSessionsRetryScheduled = new AtomicBoolean(false);
    private final AtomicBoolean mEnsurePinnedSshSessionsPending = new AtomicBoolean(false);
    private final AtomicBoolean mEnsurePinnedSshSessionsPendingSwitchToAny = new AtomicBoolean(false);
    private final Object mSshPersistRecordsLock = new Object();
    @Nullable
    private ArrayList<SshPersistenceRecord> mSshPersistRecordsCache;

    public TermuxTerminalSessionActivityClient(TermuxActivity activity) {
        this.mActivity = activity;
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
        // Set terminal fonts and colors
        checkForFontAndColors();
    }

    /**
     * Should be called when mActivity.onStart() is called
     */
    public void onStart() {
        // The service has connected, but data may have changed since we were last in the foreground.
        // Get the session stored in shared preferences stored by {@link #onStop} if its valid,
        // otherwise get the last session currently running.
        if (mActivity.getTermuxService() != null) {
            setCurrentSession(getCurrentStoredSessionOrLast());
            termuxSessionListNotifyUpdated();
            maybeAutoSwitchToProotSession();
            maybeAutoRestorePinnedSshSessions();
        }

        // The current terminal session may have changed while being away, force
        // a refresh of the displayed terminal.
        mActivity.getTerminalView().onScreenUpdated();
    }

    /**
     * Should be called when mActivity.onResume() is called
     */
    public void onResume() {
        // Just initialize the mBellSoundPool and load the sound, otherwise bell might not run
        // the first time bell key is pressed and play() is called, since sound may not be loaded
        // quickly enough before the call to play(). https://stackoverflow.com/questions/35435625
        loadBellSoundPool();
    }

    /**
     * Should be called when mActivity.onStop() is called
     */
    public void onStop() {
        // Store current session in shared preferences so that it can be restored later in
        // {@link #onStart} if needed.
        setCurrentStoredSession();

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

        if (mActivity.getCurrentSession() == changedSession) mActivity.getTerminalView().onScreenUpdated();
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession updatedSession) {
        if (!mActivity.isVisible()) return;

        if (updatedSession != mActivity.getCurrentSession()) {
            // Only show toast for other sessions than the current one, since the user
            // probably consciously caused the title change to change in the current session
            // and don't want an annoying toast for that.
            mActivity.showToast(toToastTitle(updatedSession), true);
        }

        termuxSessionListNotifyUpdated();
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        forgetSshBootstrapCommand(finishedSession);
        TermuxService service = mActivity.getTermuxService();
        boolean shouldReconcilePinnedState = clearLockedHandleForSessionHandle(finishedSession.mHandle);

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

        if (shouldReconcilePinnedState) {
            scheduleEnsurePinnedSshSessionsRetry(false);
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
        if (text != null)
            mActivity.getTerminalView().mEmulator.paste(text);
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
        if (mActivity.getCurrentSession() == changedSession)
            updateBackgroundColor();
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
        mActivity.getTerminalView().setTerminalCursorBlinkerState(enabled, false);
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
        mActivity.getTerminalView().setTerminalCursorBlinkerState(true, true);
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

        if (mActivity.getTerminalView().attachSession(session)) {
            // notify about switched session if not already displaying the session
            notifyOfSessionChange();
        }

        // We call the following even when the session is already being displayed since config may
        // be stale, like current session not selected or scrolled to.
        checkAndScrollToSession(session);
        updateBackgroundColor();
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
        sessionToRename.mSessionName = text;
        TermuxService service = mActivity.getTermuxService();
        if (service != null) {
            TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(sessionToRename);
            if (termuxSession != null)
                termuxSession.getExecutionCommand().shellName = text;
        }
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

    private void showDeleteSshProfileConfirmDialog(@NonNull SshProfile profile) {
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
                showSshProfilesDialog();
            })
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> showSshProfilesDialog())
            .show();
    }

    private void showSshProfileEditorDialog(@Nullable SshProfile existing) {
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
            .setNegativeButton(android.R.string.cancel, (d, which) -> showSshProfilesDialog())
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
            dialog.dismiss();
            showSshProfilesDialog();
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
        ensureSshpassAndRunIfNeeded(profile, () ->
            launchSshProfileSession(config.sessionName, config.targetLabel, config.sshCommand));
    }

    private void createPersistentSessionWithSshProfile(@NonNull SshProfile profile) {
        SshLaunchConfig config = resolveSshLaunchConfig(profile);
        if (config == null) return;
        ensureSshpassAndRunIfNeeded(profile, () -> {
            TerminalSession anchorSession = mActivity.getCurrentSession();
            if (anchorSession == null) {
                mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_launch_failed), true);
                return;
            }
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_creating, config.targetLabel), false);
            prepareSshLock(anchorSession, config.sshCommand, false);
        });
    }

    private void showSshPersistenceManagerDialog(@NonNull SshProfile profile) {
        SshLaunchConfig config = resolveSshLaunchConfig(profile);
        if (config == null) return;
        ensureSshpassAndRunIfNeeded(profile, () -> loadTmuxSessionsAndShowPersistenceDialog(profile, config));
    }

    private void loadTmuxSessionsAndShowPersistenceDialog(@NonNull SshProfile profile,
                                                          @NonNull SshLaunchConfig config) {
        mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_loading_tmux), false);
        runSshBackgroundTask("tmux-list-sessions", () -> {
            CommandResult listResult = runBashCommandSync(buildTmuxListSessionsCommand(config.sshCommand));
            String combinedOutput = getCombinedOutput(listResult);
            boolean tmuxMissing = combinedOutput.contains("__TMUX_MISSING__");
            boolean listDone = combinedOutput.contains(TMUX_LIST_DONE);
            ArrayList<RemoteTmuxSessionInfo> sessions = parseTmuxSessionList(combinedOutput);

            mActivity.runOnUiThread(() -> {
                if (tmuxMissing) {
                    showTmuxMissingForProfileDialog(profile, config);
                    return;
                }

                if (!listDone && sessions.isEmpty()) {
                    mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_list_failed), true);
                    return;
                }

                showSshPersistenceTmuxDialog(profile, config, sessions);
            });
        });
    }

    private void showTmuxMissingForProfileDialog(@NonNull SshProfile profile, @NonNull SshLaunchConfig config) {
        final String installCommand = buildTmuxInstallCommand(config.sshCommand);
        new AlertDialog.Builder(mActivity)
            .setTitle(R.string.title_ssh_persistence_tmux_missing)
            .setMessage(mActivity.getString(R.string.msg_ssh_persistence_tmux_missing_with_cmd, installCommand))
            .setPositiveButton(R.string.action_ssh_persistence_install, (dialog, which) -> {
                mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_installing_tmux), true);
                runSshBackgroundTask("tmux-install-from-manager", () -> {
                    CommandResult installResult = runBashCommandSync(installCommand);
                    CommandResult verifyResult = runBashCommandSync(buildTmuxCheckCommand(config.sshCommand));
                    boolean hasTmux = verifyResult.stdout.contains("__TMUX_OK__");
                    mActivity.runOnUiThread(() -> {
                        if (installResult.isSuccess() && hasTmux) {
                            showSshPersistenceManagerDialog(profile);
                        } else {
                            mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_install_failed), true);
                        }
                    });
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
            ? mActivity.getString(R.string.msg_ssh_persistence_current_badge) + " · " + info.name
            : info.name);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView subtitle = new TextView(mActivity);
        String attached = mActivity.getString(info.attached
            ? R.string.msg_ssh_persistence_tty_attached
            : R.string.msg_ssh_persistence_tty_detached);
        String subtitleText = mActivity.getString(R.string.msg_ssh_persistence_tty_windows, info.windows) + "  ·  " + attached;
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
            connectToPersistentTmuxSession(config, info.name);
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
        input.setText("termux-persist-" + Long.toHexString(System.currentTimeMillis()));
        input.setSelection(input.getText() == null ? 0 : input.getText().length());

        new AlertDialog.Builder(mActivity)
            .setTitle(R.string.title_ssh_persistence_new_session)
            .setView(input)
            .setPositiveButton(R.string.action_ssh_persistence_new, (dialog, which) -> {
                String rawName = input.getText() == null ? "" : input.getText().toString().trim();
                String tmuxSession = sanitizeTmuxSessionName(rawName);
                if (TextUtils.isEmpty(tmuxSession)) tmuxSession = "termux-persist-" + Long.toHexString(System.currentTimeMillis());
                createRemoteTmuxSessionAndConnect(profile, config, tmuxSession);
            })
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> showSshPersistenceManagerDialog(profile))
            .show();
    }

    private void createRemoteTmuxSessionAndConnect(@NonNull SshProfile profile, @NonNull SshLaunchConfig config,
                                                   @NonNull String tmuxSession) {
        String safeTmuxSession = sanitizeTmuxSessionName(tmuxSession);
        runSshBackgroundTask("tmux-create-session", () -> {
            CommandResult createResult = runBashCommandSync(buildTmuxCreateSessionCommand(config.sshCommand, safeTmuxSession));
            String combinedOutput = getCombinedOutput(createResult);
            boolean tmuxMissing = combinedOutput.contains("__TMUX_MISSING__");
            boolean created = combinedOutput.contains(TMUX_SESSION_CREATED);
            boolean exists = combinedOutput.contains(TMUX_SESSION_EXISTS);

            mActivity.runOnUiThread(() -> {
                if (tmuxMissing) {
                    showTmuxMissingForProfileDialog(profile, config);
                    return;
                }

                if (created || exists) {
                    mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_created_connecting, safeTmuxSession), false);
                    connectToPersistentTmuxSession(config, safeTmuxSession);
                    return;
                }

                mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_create_failed, summarizeCommandResult(createResult)), true);
                showSshPersistenceManagerDialog(profile);
            });
        });
    }

    private void showDestroyTmuxSessionConfirmDialog(@NonNull SshProfile profile, @NonNull SshLaunchConfig config,
                                                     @NonNull String tmuxSession) {
        String safeTmuxSession = sanitizeTmuxSessionName(tmuxSession);
        new AlertDialog.Builder(mActivity)
            .setMessage(mActivity.getString(R.string.msg_ssh_persistence_destroy_confirm, safeTmuxSession))
            .setPositiveButton(android.R.string.ok, (dialog, which) ->
                destroyRemoteTmuxSession(profile, config, safeTmuxSession))
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> showSshPersistenceManagerDialog(profile))
            .show();
    }

    private void destroyRemoteTmuxSession(@NonNull SshProfile profile, @NonNull SshLaunchConfig config,
                                          @NonNull String tmuxSession) {
        String safeTmuxSession = sanitizeTmuxSessionName(tmuxSession);
        runSshBackgroundTask("tmux-destroy-session", () -> {
            CommandResult destroyResult = runBashCommandSync(buildTmuxKillSessionCommand(config.sshCommand, safeTmuxSession));
            String combinedOutput = getCombinedOutput(destroyResult);
            boolean tmuxMissing = combinedOutput.contains("__TMUX_MISSING__");
            boolean destroyed = combinedOutput.contains(TMUX_SESSION_KILLED) || combinedOutput.contains(TMUX_SESSION_NOT_FOUND);

            mActivity.runOnUiThread(() -> {
                if (tmuxMissing) {
                    showTmuxMissingForProfileDialog(profile, config);
                    return;
                }

                if (destroyed) {
                    cleanupPersistenceRecordsForRemoteTmux(config.sshCommand, safeTmuxSession);
                    mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_destroyed, safeTmuxSession), false);
                } else {
                    mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_destroy_failed, summarizeCommandResult(destroyResult)), true);
                }

                showSshPersistenceManagerDialog(profile);
            });
        });
    }

    private void connectToPersistentTmuxSession(@NonNull SshLaunchConfig config, @NonNull String tmuxSession) {
        TerminalSession anchorSession = mActivity.getCurrentSession();
        if (anchorSession == null) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_launch_failed), true);
            return;
        }

        String safeTmuxSession = sanitizeTmuxSessionName(tmuxSession);
        mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_connecting_tmux, safeTmuxSession), false);
        enableSshPersistence(anchorSession, config.sshCommand, safeTmuxSession, false);
    }

    private void cleanupPersistenceRecordsForRemoteTmux(@NonNull String sshCommand, @NonNull String tmuxSession) {
        synchronized (mSshPersistRecordsLock) {
            String normalizedSshCommand = sanitizeSshBootstrapCommand(sshCommand);
            String safeTmuxSession = sanitizeTmuxSessionName(tmuxSession);
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

            String sessionName = sanitizeTmuxSessionName(parts[0]);
            if (TextUtils.isEmpty(sessionName) || seen.contains(sessionName)) continue;

            int windows = parsePositiveInt(parts[1], 1);
            boolean attached = "1".equals(parts[2].trim());
            sessions.add(new RemoteTmuxSessionInfo(sessionName, windows, attached));
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

        if (TextUtils.isEmpty(sshCommand)) {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_profile_invalid), true);
            return null;
        }

        return new SshLaunchConfig(sessionName, targetLabel, sshCommand);
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
            "echo \"[SSH][CN] 主机指纹校验失败。\"; " +
            "elif grep -qi \"REMOTE HOST IDENTIFICATION HAS CHANGED\" \"$TERMUX_SSH_ERR_FILE\"; then " +
            "echo \"[SSH][CN] 远程主机指纹发生变化。\"; " +
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

        SshLaunchConfig(@NonNull String sessionName, @NonNull String targetLabel, @NonNull String sshCommand) {
            this.sessionName = sessionName;
            this.targetLabel = targetLabel;
            this.sshCommand = sshCommand;
        }
    }

    private static final class RemoteTmuxSessionInfo {
        @NonNull final String name;
        final int windows;
        final boolean attached;

        RemoteTmuxSessionInfo(@NonNull String name, int windows, boolean attached) {
            this.name = name;
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
        if (session == null || TextUtils.isEmpty(session.mHandle)) return;
        synchronized (mSshBootstrapCommandByHandle) {
            if (TextUtils.isEmpty(sshCommand)) {
                mSshBootstrapCommandByHandle.remove(session.mHandle);
            } else {
                mSshBootstrapCommandByHandle.put(session.mHandle, sshCommand.trim());
            }
        }
    }

    @Nullable
    private String getRememberedSshBootstrapCommand(@Nullable TerminalSession session) {
        if (session == null || TextUtils.isEmpty(session.mHandle)) return null;
        synchronized (mSshBootstrapCommandByHandle) {
            return mSshBootstrapCommandByHandle.get(session.mHandle);
        }
    }

    private void forgetSshBootstrapCommand(@Nullable TerminalSession session) {
        if (session == null || TextUtils.isEmpty(session.mHandle)) return;
        synchronized (mSshBootstrapCommandByHandle) {
            mSshBootstrapCommandByHandle.remove(session.mHandle);
        }
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
        @NonNull final String shellName;
        @Nullable final String lockedHandle;

        SshPersistenceRecord(@NonNull String id, @NonNull String sshCommand, @NonNull String tmuxSession,
                             @NonNull String shellName, @Nullable String lockedHandle) {
            this.id = id;
            this.sshCommand = sshCommand;
            this.tmuxSession = tmuxSession;
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
            String shellName = json.optString("shellName", "").trim();
            String lockedHandle = json.optString("lockedHandle", "").trim();
            if (lockedHandle.isEmpty()) lockedHandle = null;

            return new SshPersistenceRecord(id, sshCommand, tmuxSession, shellName, lockedHandle);
        }

        @NonNull
        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id);
                json.put("sshCommand", sshCommand);
                json.put("tmuxSession", tmuxSession);
                json.put("shellName", shellName);
                json.put("lockedHandle", lockedHandle == null ? JSONObject.NULL : lockedHandle);
            } catch (Exception ignored) {
            }
            return json;
        }
    }

    @NonNull
    private ArrayList<SshPersistenceRecord> loadSshPersistenceRecords() {
        synchronized (mSshPersistRecordsLock) {
            if (mSshPersistRecordsCache != null) {
                return new ArrayList<>(mSshPersistRecordsCache);
            }

            ArrayList<SshPersistenceRecord> records = new ArrayList<>();
            SharedPreferences p = getSshPersistPrefs();
            if (p == null) {
                mSshPersistRecordsCache = new ArrayList<>();
                return records;
            }

            String raw = p.getString(KEY_SSH_PERSIST_RECORDS_JSON, "[]");
            if (!TextUtils.isEmpty(raw)) {
                try {
                    JSONArray array = new JSONArray(raw);
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.optJSONObject(i);
                        if (item == null) continue;
                        SshPersistenceRecord parsed = SshPersistenceRecord.fromJson(item);
                        if (parsed == null) continue;
                        records.add(normalizeSshPersistenceRecord(parsed));
                    }
                } catch (Exception ignored) {
                }
            }

            if (!records.isEmpty()) {
                ArrayList<SshPersistenceRecord> deduped = dedupeSshPersistenceRecords(records);
                if (!areSshPersistenceRecordsEqual(records, deduped)) {
                    saveSshPersistenceRecordsLocked(deduped, p);
                } else {
                    mSshPersistRecordsCache = new ArrayList<>(deduped);
                }
                return new ArrayList<>(deduped);
            }

            // Legacy single-lock migration: keep old users' lock after upgrade.
            if (p.getBoolean(KEY_SSH_PERSIST_ENABLED, false)) {
                String sshCommand = p.getString(KEY_SSH_COMMAND, null);
                if (!TextUtils.isEmpty(sshCommand)) {
                    String id = UUID.randomUUID().toString();
                    String tmuxSession = sanitizeTmuxSessionName(p.getString(KEY_SSH_TMUX_SESSION, DEFAULT_SSH_TMUX_SESSION));
                    String shellName = p.getString(KEY_SSH_SHELL_NAME, null);
                    if (TextUtils.isEmpty(shellName)) shellName = buildSshPersistShellName(id);
                    String lockedHandle = p.getString(KEY_SSH_LOCKED_HANDLE, null);
                    records.add(new SshPersistenceRecord(id, sshCommand.trim(), tmuxSession, shellName, lockedHandle));
                    records = dedupeSshPersistenceRecords(records);
                    saveSshPersistenceRecordsLocked(records, p);
                    return new ArrayList<>(records);
                }
            }

            mSshPersistRecordsCache = new ArrayList<>();
            return records;
        }
    }

    private void saveSshPersistenceRecords(@NonNull ArrayList<SshPersistenceRecord> records) {
        synchronized (mSshPersistRecordsLock) {
            SharedPreferences p = getSshPersistPrefs();
            if (p == null) return;
            saveSshPersistenceRecordsLocked(records, p);
        }
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

        String tmuxSession = sanitizeTmuxSessionName(record.tmuxSession);
        String shellName = record.shellName == null ? "" : record.shellName.trim();
        if (shellName.isEmpty()) shellName = buildSshPersistShellName(id);

        String sshCommand = sanitizeSshBootstrapCommand(record.sshCommand == null ? "" : record.sshCommand.trim());
        return new SshPersistenceRecord(id, sshCommand, tmuxSession, shellName, record.lockedHandle);
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
            sanitizeTmuxSessionName(tmuxSession == null ? "" : tmuxSession);
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
                id, record.sshCommand, record.tmuxSession, shellName, record.lockedHandle));
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
        String lockedHandle = !TextUtils.isEmpty(primary.lockedHandle) ? primary.lockedHandle : secondary.lockedHandle;

        return new SshPersistenceRecord(
            primary.id, primary.sshCommand, primary.tmuxSession, shellName, lockedHandle);
    }

    private int scoreSshPersistenceRecord(@NonNull SshPersistenceRecord record) {
        int score = 0;
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
        new AlertDialog.Builder(mActivity)
            .setTitle(R.string.title_ssh_persistence_session_actions)
            .setMessage(mActivity.getString(R.string.msg_ssh_persistence_session_target, record.tmuxSession))
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
        mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_closing_background, record.tmuxSession), false);

        runSshBackgroundTask("tmux-close-background", () -> {
            CommandResult destroyResult = runBashCommandSync(
                buildTmuxKillSessionCommand(record.sshCommand, record.tmuxSession));
            String combinedOutput = getCombinedOutput(destroyResult);
            boolean destroyed = combinedOutput.contains(TMUX_SESSION_KILLED) ||
                combinedOutput.contains(TMUX_SESSION_NOT_FOUND);

            if (destroyed) {
                cleanupPersistenceRecordsForRemoteTmux(record.sshCommand, record.tmuxSession);
            }

            mActivity.runOnUiThread(() -> {
                if (destroyed) {
                    mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_destroyed, record.tmuxSession), false);
                } else {
                    mActivity.showToast(mActivity.getString(
                        R.string.msg_ssh_persistence_destroy_failed, summarizeCommandResult(destroyResult)), true);
                }
                closeTerminalSessionFromTabAction(session);
            });
        });
    }

    private void closeTerminalSessionFromTabAction(@Nullable TerminalSession terminalSession) {
        if (terminalSession == null) return;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession == null) return;

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

        if (sessionsSize <= 1) {
            mActivity.finishActivityIfNotFinishing();
        }

        termuxSessionListNotifyUpdated();
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
        HashSet<String> pinnedHandles = new HashSet<>();
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return pinnedHandles;

        ArrayList<SshPersistenceRecord> records = loadSshPersistenceRecords();
        if (records.isEmpty()) return pinnedHandles;

        int size = service.getTermuxSessionsSize();
        for (int i = 0; i < size; i++) {
            TermuxSession termuxSession = service.getTermuxSession(i);
            if (termuxSession == null) continue;

            TerminalSession terminalSession = termuxSession.getTerminalSession();
            if (terminalSession == null || TextUtils.isEmpty(terminalSession.mHandle)) continue;

            if (isSshSessionPinned(terminalSession, records)) {
                pinnedHandles.add(terminalSession.mHandle);
            }
        }

        return pinnedHandles;
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
        if (session == null) return null;
        ArrayList<SshPersistenceRecord> records = loadSshPersistenceRecords();
        int index = findSshPersistenceRecordIndexForSession(session, records);
        if (index < 0 || index >= records.size()) return null;
        String tmuxSession = sanitizeTmuxSessionName(records.get(index).tmuxSession);
        return TextUtils.isEmpty(tmuxSession) ? null : tmuxSession;
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

        String bootstrapByHandle = mSshBootstrapCommandByHandle.get(session.mHandle);
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
        return sanitizeTmuxSessionName(value);
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
        return removeSshPersistenceRecordForSession(session) != null;
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
                    record.id, record.sshCommand, record.tmuxSession, record.shellName, null));
                changed = true;
            }
            if (changed) saveSshPersistenceRecords(records);
            return changed;
        }
    }

    public boolean ensurePinnedSshSession(boolean switchToSession) {
        return ensurePinnedSshSessions(switchToSession) > 0;
    }

    public int ensurePinnedSshSessions(boolean switchToAny) {
        if (!mEnsuringPinnedSshSessions.compareAndSet(false, true)) {
            mEnsurePinnedSshSessionsPending.set(true);
            if (switchToAny) mEnsurePinnedSshSessionsPendingSwitchToAny.set(true);
            return 0;
        }
        try {
            ArrayList<SshPersistenceRecord> records = loadSshPersistenceRecords();
            TermuxService service = mActivity.getTermuxService();
            if (service == null) return 0;
            cleanupOrphanedSshPersistentSessions(service, records);
            collapseDuplicateManagedSshPersistentSessions(service, records);
            if (records.isEmpty()) return 0;

            ArrayList<SshPersistenceRecord> updated = new ArrayList<>(records.size());
            boolean switched = false;
            int ensuredCount = 0;

            for (SshPersistenceRecord record : records) {
                SshPersistenceRecord ensured = ensurePinnedSshSessionRecord(service, record, switchToAny && !switched);
                if (ensured == null) continue;
                updated.add(ensured);
                ensuredCount++;

                if (switchToAny && !switched && !TextUtils.isEmpty(ensured.lockedHandle)) {
                    TerminalSession maybe = service.getTerminalSessionForHandle(ensured.lockedHandle);
                    if (maybe != null) switched = true;
                }
            }

            saveSshPersistenceRecords(updated);
            termuxSessionListNotifyUpdated();
            return ensuredCount;
        } finally {
            mEnsuringPinnedSshSessions.set(false);
            if (mEnsurePinnedSshSessionsPending.compareAndSet(true, false)) {
                boolean pendingSwitchToAny = mEnsurePinnedSshSessionsPendingSwitchToAny.getAndSet(false);
                scheduleEnsurePinnedSshSessionsRetry(pendingSwitchToAny);
            }
        }
    }

    @Nullable
    private SshPersistenceRecord ensurePinnedSshSessionRecord(@NonNull TermuxService service,
                                                              @NonNull SshPersistenceRecord record,
                                                              boolean switchToSession) {
        SshPersistenceRecord normalized = normalizeSshPersistenceRecord(record);
        if (TextUtils.isEmpty(normalized.sshCommand)) return null;
        String safeTmuxSession = sanitizeTmuxSessionName(normalized.tmuxSession);

        if (!TextUtils.isEmpty(normalized.lockedHandle)) {
            TerminalSession existingByHandle = service.getTerminalSessionForHandle(normalized.lockedHandle);
            if (existingByHandle != null) {
                TermuxSession existingTermuxByHandle = service.getTermuxSessionForTerminalSession(existingByHandle);
                if (shouldRecreateStalePinnedReconnectSession(existingTermuxByHandle, safeTmuxSession)) {
                    service.removeTermuxSession(existingByHandle);
                    scheduleEnsurePinnedSshSessionsRetry(switchToSession);
                    return new SshPersistenceRecord(normalized.id, normalized.sshCommand,
                        normalized.tmuxSession, normalized.shellName, null);
                } else {
                    rememberSshBootstrapCommand(existingByHandle, normalized.sshCommand);
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
                        normalized.tmuxSession, normalized.shellName, null);
                } else {
                    rememberSshBootstrapCommand(existingSession, normalized.sshCommand);
                    if (switchToSession) setCurrentSession(existingSession);
                    return new SshPersistenceRecord(normalized.id, normalized.sshCommand,
                        normalized.tmuxSession, normalized.shellName, existingSession.mHandle);
                }
            }
        }

        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) return normalized;

        String reconnectLoopScript = buildReconnectLoopCommand(normalized.sshCommand, normalized.tmuxSession);
        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bash).exists()) return normalized;

        String workingDirectory = mActivity.getProperties().getDefaultWorkingDirectory();
        String[] args = new String[]{"-lc", reconnectLoopScript};
        TermuxSession created = service.createTermuxSession(bash, args, null, workingDirectory, false, normalized.shellName);
        if (created == null) return normalized;

        TerminalSession createdSession = created.getTerminalSession();
        if (createdSession != null) {
            rememberSshBootstrapCommand(createdSession, normalized.sshCommand);
            if (switchToSession) setCurrentSession(createdSession);
            return new SshPersistenceRecord(normalized.id, normalized.sshCommand,
                normalized.tmuxSession, normalized.shellName, createdSession.mHandle);
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
        if (!loopScript.contains("tmux attach-session -t " + safeTmuxSession)) return true;
        if (countMatches(loopScript, "while true; do") > 1) return true;
        if (loopScript.contains("tmux has-session -t " + safeTmuxSession + " 2>/dev/null || tmux new-session -d -s " + safeTmuxSession +
            "; tmux set-option -t " + safeTmuxSession)) return true;
        return loopScript.contains("capture-pane -p -t \"\"") ||
            loopScript.contains("pane=;") ||
            loopScript.contains("; ; tmux") ||
            loopScript.contains("set-option -t " + safeTmuxSession + " mouse off");
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
            tmuxByShellName.put(record.shellName, sanitizeTmuxSessionName(record.tmuxSession));
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
        ensurePinnedSshSessions(mActivity.getCurrentSession() == null);
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
        final String tmuxSession = generatePersistentTmuxSessionName(targetSession, sshCommand);

        mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_checking_tmux), false);

        runSshBackgroundTask("tmux-check-before-lock", () -> {
            CommandResult check = runBashCommandSync(buildTmuxCheckCommand(sshCommand));
            boolean hasTmux = check.stdout.contains("__TMUX_OK__");
            boolean missingTmux = check.stdout.contains("__TMUX_MISSING__");

            mActivity.runOnUiThread(() -> {
                if (hasTmux) {
                    enableSshPersistence(targetSession, sshCommand, tmuxSession, attachCurrentSessionToTmux);
                } else if (missingTmux) {
                    final String installCommand = buildTmuxInstallCommand(sshCommand);
                    new AlertDialog.Builder(mActivity)
                        .setTitle(R.string.title_ssh_persistence_tmux_missing)
                        .setMessage(mActivity.getString(R.string.msg_ssh_persistence_tmux_missing_with_cmd, installCommand))
                        .setPositiveButton(R.string.action_ssh_persistence_install, (dialog, which) ->
                            runTmuxInstallAndEnable(targetSession, sshCommand, tmuxSession, installCommand, attachCurrentSessionToTmux))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                } else {
                    showTmuxCheckFailedDialog(targetSession, sshCommand, check, attachCurrentSessionToTmux);
                }
            });
        });
    }

    private void runTmuxInstallAndEnable(@NonNull TerminalSession targetSession, @NonNull String sshCommand,
                                         @NonNull String tmuxSession, @NonNull String installCommand,
                                         boolean attachCurrentSessionToTmux) {
        mActivity.showToast(mActivity.getString(R.string.msg_ssh_persistence_installing_tmux), true);

        runSshBackgroundTask("tmux-install-before-lock", () -> {
            CommandResult installResult = runBashCommandSync(installCommand);
            CommandResult verifyResult = runBashCommandSync(buildTmuxCheckCommand(sshCommand));
            boolean hasTmux = verifyResult.stdout.contains("__TMUX_OK__");
            mActivity.runOnUiThread(() -> {
                if (installResult.isSuccess() && hasTmux) {
                    enableSshPersistence(targetSession, sshCommand, tmuxSession, attachCurrentSessionToTmux);
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
        if (raw.contains("host key verification failed")) return "主机指纹校验失败";
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
                                      @NonNull String tmuxSession, boolean attachCurrentSessionToTmux) {
        sshCommand = sanitizeSshBootstrapCommand(sshCommand);
        String safeTmuxSession = sanitizeTmuxSessionName(tmuxSession);
        boolean lockCurrentSession = attachCurrentSessionToTmux && targetSession.isRunning();
        if (lockCurrentSession) {
            attachSessionToTmux(targetSession, safeTmuxSession);
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
            recordId, sshCommand, safeTmuxSession, shellName, lockedHandle
        ));
        upsertSshPersistenceRecord(record);
        if (lockCurrentSession) rememberSshBootstrapCommand(targetSession, sshCommand);

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

    private void attachSessionToTmux(@NonNull TerminalSession targetSession, @NonNull String tmuxSession) {
        String safeTmuxSession = sanitizeTmuxSessionName(tmuxSession);
        targetSession.write(buildTmuxEnsureAndAttachCommand(safeTmuxSession) + "\r");
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
        cmd.append(" -o StrictHostKeyChecking=accept-new");
        cmd.append(" ").append(quoteArg(remoteCommand));
        return cmd.toString();
    }

    @NonNull
    private String buildTmuxListSessionsCommand(@NonNull String sshCommand) {
        String remoteList =
            "if command -v tmux >/dev/null 2>&1; then " +
                "tmux list-sessions -F '__TMUX_ITEM__|#{session_name}|#{session_windows}|#{session_attached}' 2>/dev/null || true; " +
                "echo __TMUX_LIST_DONE__; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        return buildSshRemoteExecCommand(sshCommand, remoteList);
    }

    @NonNull
    private String buildTmuxCreateSessionCommand(@NonNull String sshCommand, @NonNull String tmuxSession) {
        String safeTmuxSession = sanitizeTmuxSessionName(tmuxSession);
        String remoteCreate =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if tmux has-session -t " + safeTmuxSession + " 2>/dev/null; then echo __TMUX_EXISTS__; exit 5; fi; " +
                "tmux new-session -d -s " + safeTmuxSession + " && echo __TMUX_CREATED__; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        return buildSshRemoteExecCommand(sshCommand, remoteCreate);
    }

    @NonNull
    private String buildTmuxKillSessionCommand(@NonNull String sshCommand, @NonNull String tmuxSession) {
        String safeTmuxSession = sanitizeTmuxSessionName(tmuxSession);
        String remoteDestroy =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if tmux has-session -t " + safeTmuxSession + " 2>/dev/null; then " +
                    "tmux kill-session -t " + safeTmuxSession + " && echo __TMUX_KILLED__; " +
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
    private String buildReconnectLoopCommand(@NonNull String sshCommand, @NonNull String tmuxSession) {
        sshCommand = sanitizeSshBootstrapCommand(sshCommand);
        String safeTmuxSession = sanitizeTmuxSessionName(tmuxSession);
        String remoteEnsure =
            "if command -v tmux >/dev/null 2>&1; then " +
                "tmux has-session -t " + safeTmuxSession + " 2>/dev/null || tmux new-session -d -s " + safeTmuxSession + "; " +
                "echo __TMUX_READY__; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        String remoteAttach =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if tmux has-session -t " + safeTmuxSession + " 2>/dev/null; then " +
                    buildTmuxAttachOnlyCommand(safeTmuxSession) + "; " +
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
    private String buildTmuxAttachOnlyCommand(@NonNull String tmuxSession) {
        String safeTmuxSession = sanitizeTmuxSessionName(tmuxSession);
        return "tmux set-option -t " + safeTmuxSession + " mouse on >/dev/null 2>&1; " +
            "tmux set-window-option -t " + safeTmuxSession + " alternate-screen off >/dev/null 2>&1; " +
            "tmux set-option -t " + safeTmuxSession + " history-limit " + SSH_PERSIST_TMUX_PRELOAD_LINES + " >/dev/null 2>&1; " +
            // Dump recent pane output before attach so local transcript has cache immediately.
            "pane=$(tmux display-message -p -t " + safeTmuxSession + " '#{session_name}:#{window_index}.#{pane_index}' 2>/dev/null); " +
            "[ -n \"$pane\" ] && tmux capture-pane -p -t \"$pane\" -S -" + SSH_PERSIST_TMUX_PRELOAD_LINES + " 2>/dev/null || true; " +
            "tmux attach-session -t " + safeTmuxSession;
    }

    @NonNull
    private String buildTmuxEnsureAndAttachCommand(@NonNull String tmuxSession) {
        String safeTmuxSession = sanitizeTmuxSessionName(tmuxSession);
        return "tmux has-session -t " + safeTmuxSession + " 2>/dev/null || tmux new-session -d -s " + safeTmuxSession +
            "; " + buildTmuxAttachOnlyCommand(safeTmuxSession);
    }

    @NonNull
    private String escapeForDoubleQuotes(@NonNull String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @NonNull
    private String sanitizeTmuxSessionName(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return DEFAULT_SSH_TMUX_SESSION;
        value = value.replaceAll("[^A-Za-z0-9._-]", "-");
        return value.isEmpty() ? DEFAULT_SSH_TMUX_SESSION : value;
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
    private String generatePersistentTmuxSessionName(@NonNull TerminalSession session, @NonNull String sshCommand) {
        StringBuilder sb = new StringBuilder("termux-persist-");
        sb.append(Long.toHexString(System.currentTimeMillis()));

        if (!TextUtils.isEmpty(session.mHandle)) {
            String handle = session.mHandle.replaceAll("[^A-Za-z0-9]", "");
            if (!handle.isEmpty()) {
                if (handle.length() > 8) handle = handle.substring(handle.length() - 8);
                sb.append("-").append(handle);
            }
        }

        int hash = Math.abs(sshCommand.hashCode());
        sb.append("-").append(Integer.toHexString(hash));
        return sanitizeTmuxSessionName(sb.toString());
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
        TerminalSession currentSession = mActivity.getCurrentSession();
        if (currentSession != null)
            mActivity.getPreferences().setCurrentSession(currentSession.mHandle);
        else
            mActivity.getPreferences().setCurrentSession(null);
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

        int index = service.removeTermuxSession(finishedSession);

        int size = service.getTermuxSessionsSize();
        if (size == 0) {
            // There are no sessions to show, so finish the activity.
            mActivity.finishActivityIfNotFinishing();
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
            mActivity.getTerminalView().setTypeface(newTypeface);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in checkForFontAndColors()", e);
        }
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


