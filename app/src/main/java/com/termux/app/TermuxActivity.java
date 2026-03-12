package com.termux.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.FileObserver;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.RelativeLayout;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.topbar.TerminalTopBarController;
import com.termux.app.topbar.TerminalTopBarView;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalSessionSurfaceBridge;
import com.termux.app.terminal.TermuxTerminalTopBarBridge;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import io.github.rosemoe.sora.app.EditorController;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.KeyboardUtils;
import com.termux.shared.view.ViewUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;
import com.termux.terminalsessionsurface.TerminalSessionSurfaceView;
import com.termux.bridge.FileOpenBridge;
import com.termux.bridge.FileOpenListener;
import com.termux.bridge.FileEditorContract;
import com.termux.bridge.FileOpenEvent;
import com.termux.bridge.FileOpenRequest;
import com.termux.ui.nav.UiShellNavBridge;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import org.fossify.commons.extensions.Context_stylingKt;
import org.fossify.filemanager.activities.SimpleActivity;
import org.fossify.filemanager.controllers.FileManagerController;
import org.fossify.filemanager.databinding.FmActivityMainBinding;
import org.fossify.filemanager.interfaces.FileManagerHost;

import kotlin.jvm.functions.Function0;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends SimpleActivity implements ServiceConnection, FileOpenListener, FileManagerHost {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    @Nullable
    private TerminalView mContextMenuTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private boolean mSuppressTerminalContextMenuOnce;
    private long mSuppressTerminalContextMenuUntilUptimeMs;
    private boolean mAllowTerminalContextMenuMapping;

    private int mNavBarHeight;
    private int mStatusBarHeight;
    private View mStatusBarScrim;

    private float mTerminalToolbarDefaultHeight;


    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";
    private static final String ARG_BOTTOM_NAV_TAB = "bottom_nav_tab";
    private static final String ARG_BOTTOM_NAV_PRIMARY_TAB = "bottom_nav_primary_tab";
    private static final String ARG_BOTTOM_NAV_FIXED_ON_IME = "bottom_nav_fixed_on_ime";

    private static final int TAB_EDITOR = 2;
    private static final int TAB_FILES = 3;
    private static final int TAB_TERMINAL = 4;
    private static final long EDITOR_TRANSITION_DURATION_MS = 180L;

    private static final String LOG_TAG = "TermuxActivity";
    private BottomNavigationView mBottomNavigationView;
    private View mEditorPage;
    private View mFilesPage;
    private int mBottomNavTab = TAB_TERMINAL;
    private int mBottomNavPrimaryTab = TAB_TERMINAL;
    private int mNonTerminalWindowBackgroundColor = 0xFFFFFFFF;
    private View mTerminalContainer;
    private TerminalSessionSurfaceView mTerminalSessionSurfaceView;
    private TerminalTopBarView mTerminalTopBarView;
    private TerminalTopBarController mTerminalTopBarController;
    private TermuxTerminalSessionSurfaceBridge mTermuxTerminalSessionSurfaceBridge;
    private TermuxTerminalTopBarBridge mTermuxTerminalTopBarBridge;
    private boolean mSurfaceSelectionDispatchInProgress = false;
    private boolean mTerminalSessionSurfaceHasSnapshot = false;
    private boolean mBottomNavFixedOnImeEnabled = true;
    private boolean mSessionListUiUpdateScheduled = false;
    private int mSessionUiBatchDepth = 0;
    private boolean mSessionUiUpdatePendingInBatch = false;
    private final Runnable mCoalescedSessionListUiUpdate = () -> {
        mSessionListUiUpdateScheduled = false;
        if (mIsInvalidState) return;
        if (mTermuxSessionListViewController != null) {
            mTermuxSessionListViewController.notifyDataSetChanged();
        }
        refreshTerminalSessionSurface();
        refreshTerminalTopBar();
    };

    private static final String ARG_FILE_MANAGER_STATE = "file_manager_state";
    private EditorController mEditorController;
    private ActivityResultLauncher<String> mEditorLoadTmlLauncher;
    private ActivityResultLauncher<String> mEditorLoadTmtLauncher;
    private FileManagerController mFileManagerController;
    private FmActivityMainBinding mFileManagerBinding;
    private Bundle mFileManagerSavedState;
    private Intent mFileManagerIntent;
    private TermuxActivityUiReceiver mUiReceiver;
    private FileObserver mUiRequestFileObserver;
    @Nullable

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);
        if (savedInstanceState != null)
            mBottomNavFixedOnImeEnabled = savedInstanceState.getBoolean(ARG_BOTTOM_NAV_FIXED_ON_IME, true);

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        setActivityTheme();
        setUseDynamicTheme(false);

        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_termux);
        mStatusBarScrim = findViewById(R.id.status_bar_scrim);
        mNonTerminalWindowBackgroundColor = resolveNonTerminalWindowBackgroundColor();
        applyWindowBackgroundForTab(mBottomNavTab);

        mIsVisible = true;

        if (mFileManagerIntent == null) {
            mFileManagerIntent = new Intent();
        }

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());
        mTermuxActivityRootView.setBottomNavigationFixedOnImeEnabled(mBottomNavFixedOnImeEnabled);

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            WindowInsetsCompat compat = WindowInsetsCompat.toWindowInsetsCompat(insets, v);
            Insets navInsets = compat.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars());
            Insets statusInsets = compat.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars());
            Insets cutoutInsets = compat.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout());
            mNavBarHeight = navInsets.bottom;
            int statusBarTop = Math.max(statusInsets.top, cutoutInsets.top);
            mStatusBarHeight = statusBarTop > 0 ? statusBarTop : insets.getSystemWindowInsetTop();
            applyStatusBarScrimColor(resolveStatusBarColorForTab(mBottomNavTab));
            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setTermuxTerminalViewAndClients();

        setTerminalToolbarView(savedInstanceState);

        setSettingsButtonView();

        setNewSessionButtonView();

        setToggleKeyboardView();
        // Register editor launchers early. ActivityResult API requires registration before STARTED.
        // Editor itself remains lazy-initialized on first real open request.
        ensureEditorLaunchers();

        setupBottomNavigation(savedInstanceState);
        handleUiNavigationIntentIfNeeded(getIntent());
        handleEditorIntentIfNeeded(getIntent());
        FileOpenBridge.addListener(this);
        registerUiReceiver();
        registerUiRequestFileObserver();

        updateTerminalContextMenuRegistration(mTerminalView);

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,"TermuxActivity failed to start TermuxService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public ArrayList<Integer> getAppIconIDs() {
        return new ArrayList<>();
    }

    @Override
    public String getAppLauncherName() {
        return getString(R.string.app_name);
    }

    @Override
    public String getRepositoryName() {
        return "termux-app";
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mTermuxService != null && mTermuxTerminalSessionActivityClient != null) {
            mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
        }

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        registerTermuxActivityBroadcastReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        // Re-apply system/status bar colors on resume to override OEM/system resets.
        applyWindowBackgroundForTab(mBottomNavTab);

        if (mBottomNavTab == TAB_TERMINAL) {
            mAllowTerminalContextMenuMapping = false;
            mSuppressTerminalContextMenuOnce = true;
            mSuppressTerminalContextMenuUntilUptimeMs = SystemClock.uptimeMillis() + 2000;
            getWindow().getDecorView().post(() -> mSuppressTerminalContextMenuOnce = false);

            EditText textInputView = findViewById(R.id.terminal_surface_text_input);
            if (textInputView != null) textInputView.clearFocus();

            closeContextMenu();
            closeOptionsMenu();
            getWindow().getDecorView().post(() -> {
                closeContextMenu();
                closeOptionsMenu();
            });
        } else if (mBottomNavTab == TAB_FILES) {
            EditText textInputView = findViewById(R.id.terminal_surface_text_input);
            if (textInputView != null) textInputView.clearFocus();
            if (mTerminalView != null) mTerminalView.clearFocus();
            if (mTermuxTerminalViewClient != null) mTermuxTerminalViewClient.cancelPendingShowSoftKeyboard();
            KeyboardUtils.hideSoftKeyboard(this, getWindow().getDecorView());
        } else if (mBottomNavTab == TAB_EDITOR) {
            EditText textInputView = findViewById(R.id.terminal_surface_text_input);
            if (textInputView != null) textInputView.clearFocus();
            if (mTerminalView != null) mTerminalView.clearFocus();
            if (mTermuxTerminalViewClient != null) mTermuxTerminalViewClient.cancelPendingShowSoftKeyboard();
            KeyboardUtils.hideSoftKeyboard(this, getWindow().getDecorView());
        }

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        if (mBottomNavTab == TAB_EDITOR) {
            ensureEditorInitialized();
            resumeEditor();
        }
        if (mBottomNavTab == TAB_FILES) {
            ensureFileManagerInitialized();
            resumeFileManager(false);
        }

        mIsOnResumeAfterOnCreate = false;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mEditorController != null) {
            mEditorController.onConfigurationChanged(newConfig);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!mAllowTerminalContextMenuMapping && mBottomNavTab == TAB_TERMINAL && ev.getAction() == MotionEvent.ACTION_DOWN) {
            mAllowTerminalContextMenuMapping = true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mIsInvalidState) return;

        closeContextMenu();
        closeOptionsMenu();

        pauseEditor();
        pauseFileManager(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        if (mTermuxService != null) {
            mTermuxService.unsetTermuxTerminalSessionClient();
        }

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiver();
        getDrawer().closeDrawers();
        closeContextMenu();
        closeOptionsMenu();
        cancelCoalescedSessionListUiUpdate();

    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");
        cancelCoalescedSessionListUiUpdate();

        if (mIsInvalidState) return;

        unregisterUiReceiver();
        unregisterUiRequestFileObserver();
        FileOpenBridge.removeListener(this);

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }

        if (mEditorController != null) {
            mEditorController.onDestroy();
            mEditorController = null;
        }
        if (mEditorPage instanceof ViewGroup) ((ViewGroup) mEditorPage).removeAllViews();

        if (mFilesPage instanceof ViewGroup) ((ViewGroup) mFilesPage).removeAllViews();
        mFileManagerController = null;
        mFileManagerBinding = null;
        mFileManagerSavedState = null;
    }


    private void registerUiReceiver() {
        if (mUiReceiver != null) return;
        mUiReceiver = new TermuxActivityUiReceiver(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(TermuxActivityUiReceiver.ACTION_SWITCH_TAB);
        filter.addAction(TermuxActivityUiReceiver.ACTION_OPEN_FILES_AT);
        registerReceiver(mUiReceiver, filter);
    }

    private void unregisterUiReceiver() {
        if (mUiReceiver == null) return;
        try {
            unregisterReceiver(mUiReceiver);
        } catch (Exception e) {
            // ignore
        }
        mUiReceiver = null;
    }

    private void registerUiRequestFileObserver() {
        if (mUiRequestFileObserver != null) return;

        final File requestFile = new File(TermuxActivityUiReceiver.OPEN_FILES_AT_REQUEST_FILE_PATH);
        final File requestsDir = requestFile.getParentFile();
        if (requestsDir == null) return;
        if (!requestsDir.isDirectory() && !requestsDir.mkdirs()) return;

        mUiRequestFileObserver = new FileObserver(requestsDir.getAbsolutePath(),
            FileObserver.CLOSE_WRITE | FileObserver.CREATE | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, @Nullable String path) {
                if (path == null || !requestFile.getName().equals(path)) return;
                processOpenFilesAtRequestFile(requestFile);
            }
        };

        mUiRequestFileObserver.startWatching();
        processOpenFilesAtRequestFile(requestFile);
    }

    private void unregisterUiRequestFileObserver() {
        if (mUiRequestFileObserver == null) return;
        try {
            mUiRequestFileObserver.stopWatching();
        } catch (Exception e) {
            // ignore
        }
        mUiRequestFileObserver = null;
    }

    private void processOpenFilesAtRequestFile(File requestFile) {
        String requestedPath = readFirstLineFromFile(requestFile);
        if (requestedPath == null || requestedPath.trim().isEmpty()) return;
        if (!requestFile.delete()) {
            Logger.logWarn(LOG_TAG, "Failed to delete ui request file \"" + requestFile.getAbsolutePath() + "\"");
        }
        String targetPath = requestedPath.trim();
        runOnUiThread(() -> openFilesAtPath(targetPath));
    }

    @Nullable
    private String readFirstLineFromFile(File file) {
        if (file == null || !file.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            return reader.readLine();
        } catch (Exception e) {
            Logger.logErrorExtended(LOG_TAG, "Failed reading ui request file\n" + e);
            return null;
        }
    }

    public void switchBottomTabByName(String name) {
        String n = name == null ? "" : name.trim().toLowerCase();
        int tab;
        if (n.equals("terminal") || n.equals("term")) {
            tab = TAB_TERMINAL;
        } else if (n.equals("files") || n.equals("file")) {
            tab = TAB_FILES;
        } else if (n.equals("editor") || n.equals("edit")) {
            // Editor is no longer an exposed bottom tab. Route external requests to Files.
            tab = TAB_FILES;
        } else if (n.equals("project") || n.equals("projects")) {
            tab = TAB_FILES;
        } else {
            tab = TAB_TERMINAL;
        }

        if (mBottomNavigationView != null) {
            mBottomNavigationView.setSelectedItemId(tab);
        } else {
            setBottomNavTab(tab);
        }
    }

    public void openFilesAtPath(String path) {
        if (path == null || path.trim().isEmpty()) return;
        UiShellNavBridge.setRequestedFilesDir(this, path);
        if (mFileManagerIntent == null) mFileManagerIntent = new Intent();
        mFileManagerIntent.setAction(Intent.ACTION_VIEW);
        mFileManagerIntent.setData(Uri.fromFile(new File(path)));
        if (mFileManagerController != null) {
            mFileManagerController.openPath(path, true);
        }
        if (mBottomNavigationView != null) {
            mBottomNavigationView.setSelectedItemId(TAB_FILES);
        } else {
            setBottomNavTab(TAB_FILES);
        }
    }

    @Override
    public void toggleMainFabMenu() {
        if (mFileManagerController != null) {
            mFileManagerController.toggleMainFabMenu();
        }
    }

    @Override
    public boolean isTermuxScopedFileManager() {
        return true;
    }

    @Override
    public void showSessionSwitcher() {
        if (mFileManagerController != null) {
            mFileManagerController.showSessionSwitcher();
        }
    }

    @Override
    public void createDocumentConfirmed(String path) {
        if (mFileManagerController != null) {
            mFileManagerController.createDocumentConfirmed(path);
        }
    }

    @Override
    public void pickedPath(String path) {
        if (mFileManagerController != null) {
            mFileManagerController.pickedPath(path);
        }
    }

    @Override
    public void pickedPaths(java.util.ArrayList<String> paths) {
        if (mFileManagerController != null) {
            mFileManagerController.pickedPaths(paths);
        }
    }

    @Override
    public void pickedRingtone(String path) {
        if (mFileManagerController != null) {
            mFileManagerController.pickedRingtone(path);
        }
    }

    @Override
    public void refreshMenuItems() {
        if (mFileManagerController != null) {
            mFileManagerController.refreshMenuItems();
        }
    }

    @Override
    public void updateFragmentColumnCounts() {
        if (mFileManagerController != null) {
            mFileManagerController.updateFragmentColumnCounts();
        }
    }

    @Override
    public void openedDirectory() {
        if (mFileManagerController != null) {
            mFileManagerController.openedDirectory();
        }
    }

    @Override
    public void openInTerminal(String path) {
        openTerminalAtPath(path);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
        savedInstanceState.putInt(ARG_BOTTOM_NAV_TAB, mBottomNavTab);
        savedInstanceState.putInt(ARG_BOTTOM_NAV_PRIMARY_TAB, mBottomNavPrimaryTab);
        savedInstanceState.putBoolean(ARG_BOTTOM_NAV_FIXED_ON_IME, mBottomNavFixedOnImeEnabled);

        if (mFileManagerController != null) {
            Bundle fileManagerState = new Bundle();
            mFileManagerController.onSaveInstanceState(fileManagerState);
            savedInstanceState.putBundle(ARG_FILE_MANAGER_STATE, fileManagerState);
        }
    }

    private void setupBottomNavigation(Bundle savedInstanceState) {
        mBottomNavigationView = findViewById(R.id.bottom_navigation);
        if (mBottomNavigationView == null) return;

        mEditorPage = findViewById(R.id.page_editor);
        if (mEditorPage != null) {
            mEditorPage.setBackgroundColor(mNonTerminalWindowBackgroundColor);
        }
        mFilesPage = findViewById(R.id.page_files);
        if (mFilesPage != null) {
            mFilesPage.setBackgroundColor(mNonTerminalWindowBackgroundColor);
        }

        if (savedInstanceState != null) {
            mFileManagerSavedState = savedInstanceState.getBundle(ARG_FILE_MANAGER_STATE);
        }

        if (savedInstanceState != null) {
            int tab = savedInstanceState.getInt(ARG_BOTTOM_NAV_TAB, TAB_TERMINAL);
            if (tab == TAB_EDITOR || tab == TAB_FILES || tab == TAB_TERMINAL) {
                mBottomNavTab = tab;
            }
            int primaryTab = savedInstanceState.getInt(ARG_BOTTOM_NAV_PRIMARY_TAB, TAB_TERMINAL);
            if (primaryTab == TAB_FILES || primaryTab == TAB_TERMINAL) {
                mBottomNavPrimaryTab = primaryTab;
            }
        }

        final Menu menu = mBottomNavigationView.getMenu();
        menu.clear();
        menu.add(Menu.NONE, TAB_FILES, 0, "Files").setIcon(android.R.drawable.ic_menu_sort_by_size);
        menu.add(Menu.NONE, TAB_TERMINAL, 1, "Terminal").setIcon(android.R.drawable.ic_menu_view);

        int selectedPrimaryTab = mBottomNavTab == TAB_EDITOR ? mBottomNavPrimaryTab : mBottomNavTab;
        if (selectedPrimaryTab != TAB_FILES && selectedPrimaryTab != TAB_TERMINAL) {
            selectedPrimaryTab = TAB_TERMINAL;
        }
        mBottomNavigationView.setSelectedItemId(selectedPrimaryTab);
        setBottomNavTab(mBottomNavTab);

        mBottomNavigationView.setOnItemSelectedListener(item -> {
            int targetTab = item.getItemId();
            if (targetTab != TAB_FILES && targetTab != TAB_TERMINAL) return false;
            if (mBottomNavTab != TAB_EDITOR && targetTab == mBottomNavTab) return true;
            setBottomNavTab(targetTab);
            return true;
        });
        mBottomNavigationView.setOnItemReselectedListener(item -> {
            int targetTab = item.getItemId();
            if (mBottomNavTab == TAB_EDITOR && (targetTab == TAB_FILES || targetTab == TAB_TERMINAL)) {
                setBottomNavTab(targetTab);
            }
        });

        mBottomNavigationView.setOnLongClickListener(v -> {
            setBottomNavigationFixedOnImeEnabled(!mBottomNavFixedOnImeEnabled);
            Logger.showToast(this, mBottomNavFixedOnImeEnabled ? getString(R.string.msg_bottom_nav_fixed_under_ime) : getString(R.string.msg_bottom_nav_follow_system_ime), true);
            return true;
        });

        if (mFilesPage != null) {
            mFilesPage.post(() -> {
                if (mBottomNavTab != TAB_FILES) return;
                ensureFileManagerInitialized();
                resumeFileManager(true);
            });
        }
    }

    public void setBottomNavigationFixedOnImeEnabled(boolean enabled) {
        mBottomNavFixedOnImeEnabled = enabled;
        if (mTermuxActivityRootView != null) {
            mTermuxActivityRootView.setBottomNavigationFixedOnImeEnabled(enabled);
        }
    }

    private void setBottomNavTab(int tab) {
        if (tab != TAB_EDITOR && tab != TAB_FILES && tab != TAB_TERMINAL) return;
        if (tab == TAB_EDITOR && !ensureEditorInitialized()) {
            // Avoid blank page when editor cannot initialize.
            tab = (mBottomNavPrimaryTab == TAB_FILES || mBottomNavPrimaryTab == TAB_TERMINAL) ? mBottomNavPrimaryTab : TAB_FILES;
        }
        final int previousTab = mBottomNavTab;
        final boolean enteringEditor = previousTab != TAB_EDITOR && tab == TAB_EDITOR;
        final boolean leavingEditor = previousTab == TAB_EDITOR && tab != TAB_EDITOR;
        mBottomNavTab = tab;
        if (tab == TAB_FILES || tab == TAB_TERMINAL) {
            mBottomNavPrimaryTab = tab;
        }
        applyWindowBackgroundForTab(tab);

        applyBottomNavigationVisibility(tab);

        final boolean keepPreviousVisibleWhileEnteringEditor = enteringEditor && previousTab != TAB_EDITOR;
        if (mTerminalContainer != null) {
            boolean showTerminal = tab == TAB_TERMINAL || (keepPreviousVisibleWhileEnteringEditor && previousTab == TAB_TERMINAL);
            mTerminalContainer.setVisibility(showTerminal ? View.VISIBLE : View.GONE);
        } else if (mTerminalView != null) {
            boolean showTerminal = tab == TAB_TERMINAL || (keepPreviousVisibleWhileEnteringEditor && previousTab == TAB_TERMINAL);
            mTerminalView.setVisibility(showTerminal ? View.VISIBLE : View.GONE);
        }
        if (mFilesPage != null) {
            boolean showFiles = tab == TAB_FILES || (keepPreviousVisibleWhileEnteringEditor && previousTab == TAB_FILES);
            mFilesPage.setVisibility(showFiles ? View.VISIBLE : View.GONE);
        }

        if (mEditorPage != null) {
            mEditorPage.animate().cancel();
            if (tab == TAB_EDITOR) {
                mEditorPage.setVisibility(View.VISIBLE);
                mEditorPage.setTranslationX(0f);
                mEditorPage.setAlpha(1f);
                mEditorPage.bringToFront();
                if (enteringEditor) {
                    animateEditorEnter(() -> {
                        if (mBottomNavTab != TAB_EDITOR) return;
                        if (mTerminalContainer != null) mTerminalContainer.setVisibility(View.GONE);
                        else if (mTerminalView != null) mTerminalView.setVisibility(View.GONE);
                        if (mFilesPage != null) mFilesPage.setVisibility(View.GONE);
                    });
                }
            } else if (leavingEditor) {
                mEditorPage.setVisibility(View.VISIBLE);
                mEditorPage.setTranslationX(0f);
                mEditorPage.setAlpha(1f);
                mEditorPage.bringToFront();
                animateEditorExit(() -> {
                    if (mEditorPage == null) return;
                    mEditorPage.setVisibility(View.GONE);
                    mEditorPage.setTranslationX(0f);
                    mEditorPage.setAlpha(1f);
                });
            } else {
                mEditorPage.setVisibility(View.GONE);
                mEditorPage.setTranslationX(0f);
                mEditorPage.setAlpha(1f);
            }
        }

        if (tab == TAB_FILES) {
            EditText textInputView = findViewById(R.id.terminal_surface_text_input);
            if (textInputView != null) textInputView.clearFocus();
            if (mTerminalView != null) mTerminalView.clearFocus();

            if (mTermuxTerminalViewClient != null) mTermuxTerminalViewClient.cancelPendingShowSoftKeyboard();
            KeyboardUtils.hideSoftKeyboard(this, getWindow().getDecorView());

            if (previousTab == TAB_TERMINAL) {
                closeContextMenu();
                closeOptionsMenu();
            }
        }

        if (tab == TAB_EDITOR) {
            EditText textInputView = findViewById(R.id.terminal_surface_text_input);
            if (textInputView != null) textInputView.clearFocus();
            if (mTerminalView != null) mTerminalView.clearFocus();
            if (mTermuxTerminalViewClient != null) mTermuxTerminalViewClient.cancelPendingShowSoftKeyboard();
            KeyboardUtils.hideSoftKeyboard(this, getWindow().getDecorView());
        }

        invalidateOptionsMenu();

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager != null) {
            if (tab == TAB_TERMINAL) {
                terminalToolbarViewPager.setVisibility(mPreferences != null && mPreferences.shouldShowTerminalToolbar() ? View.VISIBLE : View.GONE);
            } else {
                terminalToolbarViewPager.setVisibility(View.GONE);
            }
        }

        if (tab == TAB_EDITOR) {
            ensureEditorInitialized();
            resumeEditor();
        } else {
            pauseEditor();
            if (tab == TAB_TERMINAL && mTerminalView != null && mTermuxTerminalViewClient != null) {
                mTermuxTerminalViewClient.requestTerminalViewFocus(false);
                refreshTerminalTopBar();
            }
        }

        if (tab == TAB_FILES) {
            ensureFileManagerInitialized();
            resumeFileManager(true);
        } else {
            pauseFileManager(true);
        }
        final DrawerLayout drawer = getDrawer();
        if (drawer != null) {
            drawer.setDrawerLockMode(tab == TAB_TERMINAL ? DrawerLayout.LOCK_MODE_UNLOCKED : DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        }
    }

    private void applyBottomNavigationVisibility(int tab) {
        if (mBottomNavigationView == null) return;
        if (tab == TAB_EDITOR) {
            mBottomNavigationView.animate().cancel();
            mBottomNavigationView.setVisibility(View.GONE);
            return;
        }
        if (mBottomNavigationView.getVisibility() != View.VISIBLE) {
            mBottomNavigationView.setVisibility(View.VISIBLE);
        }
        mBottomNavigationView.setTranslationY(0f);
        mBottomNavigationView.setAlpha(1f);
    }

    private int resolveNonTerminalWindowBackgroundColor() {
        TypedValue outValue = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.colorBackground, outValue, true)) {
            if (outValue.resourceId != 0) {
                return ContextCompat.getColor(this, outValue.resourceId);
            }
            return outValue.data;
        }
        return 0xFFFFFFFF;
    }

    private void applyWindowBackgroundForTab(int tab) {
        if (getWindow() == null) return;
        if (tab == TAB_TERMINAL && mTermuxTerminalSessionActivityClient != null) {
            mTermuxTerminalSessionActivityClient.updateBackgroundColor();
            int terminalBackground = resolveTerminalStatusBarColor();
            ensureSystemBarColorMode();
            applyStatusBarAppearance(terminalBackground);
            return;
        }
        int backgroundColor = tab == TAB_FILES ? resolveFileManagerBackgroundColor() : mNonTerminalWindowBackgroundColor;
        getWindow().getDecorView().setBackgroundColor(backgroundColor);
        int statusBarColor = resolveStatusBarColorForTab(tab);
        ensureSystemBarColorMode();
        applyStatusBarAppearance(statusBarColor);
    }

    private int resolveTerminalStatusBarColor() {
        int terminalBackground = 0xFF000000;
        TerminalSession session = getCurrentSession();
        if (session != null && session.getEmulator() != null) {
            terminalBackground = session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND];
        }
        return terminalBackground;
    }

    private int resolveFileManagerBackgroundColor() {
        try {
            return Context_stylingKt.getProperBackgroundColor(this);
        } catch (Throwable ignored) {
            return mNonTerminalWindowBackgroundColor;
        }
    }

    private int resolveEditorStatusBarColor() {
        TypedValue outValue = new TypedValue();
        if (getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, outValue, true)) {
            if (outValue.resourceId != 0) {
                try {
                    return ContextCompat.getColor(this, outValue.resourceId);
                } catch (Throwable ignored) {
                    // Fallback to raw color below.
                }
            }
            return outValue.data;
        }
        return mNonTerminalWindowBackgroundColor;
    }

    private int resolveStatusBarColorForTab(int tab) {
        if (tab == TAB_TERMINAL) {
            return resolveTerminalStatusBarColor();
        }
        if (tab == TAB_FILES) {
            return resolveFileManagerBackgroundColor();
        }
        if (tab == TAB_EDITOR) {
            return resolveEditorStatusBarColor();
        }
        return mNonTerminalWindowBackgroundColor;
    }

    private void applyStatusBarAppearance(int color) {
        Window window = getWindow();
        if (window == null) return;
        window.setStatusBarColor(color);
        WindowInsetsControllerCompat insetsController = new WindowInsetsControllerCompat(window, window.getDecorView());
        boolean isLightBackground = ColorUtils.calculateLuminance(color) > 0.5d;
        insetsController.setAppearanceLightStatusBars(isLightBackground);
        applyStatusBarScrimColor(color);
    }

    private void applyStatusBarScrimColor(int color) {
        if (mStatusBarScrim == null) return;
        int height = mStatusBarHeight;
        if (height <= 0) {
            int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) {
                height = getResources().getDimensionPixelSize(resId);
            }
        }
        ViewGroup.LayoutParams lp = mStatusBarScrim.getLayoutParams();
        if (lp != null && height > 0 && lp.height != height) {
            lp.height = height;
            mStatusBarScrim.setLayoutParams(lp);
        }
        mStatusBarScrim.setBackgroundColor(color);
    }

    private void ensureSystemBarColorMode() {
        Window window = getWindow();
        if (window == null) return;
        WindowCompat.setDecorFitsSystemWindows(window, false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            if (lp.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                window.setAttributes(lp);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
        }
        final View decor = window.getDecorView();
        if (decor != null) {
            int flags = decor.getSystemUiVisibility();
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            decor.setSystemUiVisibility(flags);
        }
    }

    private void animateEditorEnter(@Nullable Runnable endAction) {
        if (mEditorPage == null) {
            if (endAction != null) endAction.run();
            return;
        }
        int width = mEditorPage.getWidth();
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
        if (width <= 0) width = 1;
        mEditorPage.animate().cancel();
        mEditorPage.setTranslationX(width);
        mEditorPage.setAlpha(1f);
        mEditorPage.animate()
            .translationX(0f)
            .setDuration(EDITOR_TRANSITION_DURATION_MS)
            .setInterpolator(new DecelerateInterpolator())
            .withEndAction(() -> {
                if (endAction != null) endAction.run();
            })
            .start();
    }

    private void animateEditorExit(@Nullable Runnable endAction) {
        if (mEditorPage == null) {
            if (endAction != null) endAction.run();
            return;
        }
        int width = mEditorPage.getWidth();
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
        if (width <= 0) width = 1;
        mEditorPage.animate().cancel();
        mEditorPage.setTranslationX(0f);
        mEditorPage.setAlpha(1f);
        mEditorPage.animate()
            .translationX(width)
            .setDuration(EDITOR_TRANSITION_DURATION_MS)
            .setInterpolator(new DecelerateInterpolator())
            .withEndAction(() -> {
                if (endAction != null) endAction.run();
            })
            .start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null) return;
        handleUiNavigationIntentIfNeeded(intent);
        boolean editorIntentHandled = handleEditorIntentIfNeeded(intent);
        if (mEditorController != null && !editorIntentHandled) {
            mEditorController.onNewIntent(intent);
        }
    }

    private void openTerminalAtPath(String path) {
        if (path == null || path.trim().isEmpty()) return;
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) return;
        setBottomNavTab(TAB_TERMINAL);
        if (mTermuxTerminalSessionActivityClient != null) {
            mTermuxTerminalSessionActivityClient.addNewSessionAt(dir.getAbsolutePath());
        }
    }

    private boolean ensureEditorInitialized() {
        if (mEditorPage == null) return false;
        if (!(mEditorPage instanceof ViewGroup)) return false;
        if (mEditorController != null) return true;

        try {
            ensureEditorLaunchers();
            mEditorController = new EditorController(this, () -> getIntent(), mEditorLoadTmlLauncher, mEditorLoadTmtLauncher);
            mEditorController.setHostHandlesStatusBarInsets(true);
            mEditorController.attachTo((ViewGroup) mEditorPage);
            mEditorController.onCreate(null);
            return true;
        } catch (Exception e) {
            Logger.showToast(this, getString(R.string.msg_editor_initialization_failed, e.getMessage()), true);
            return false;
        }
    }

    private void resumeEditor() {
        if (mEditorController != null) {
            mEditorController.onResume();
            // Do not auto-focus editor on tab switch/resume. Keyboard should show only after explicit user focus.
            mEditorController.getRootView().clearFocus();
        }
    }

    private void pauseEditor() {
        if (mEditorController != null) {
            mEditorController.getRootView().clearFocus();
        }
    }

    private void ensureEditorLaunchers() {
        if (mEditorLoadTmlLauncher == null) {
            mEditorLoadTmlLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (mEditorController != null) {
                    mEditorController.onLoadTmlResult(uri);
                }
            });
        }
        if (mEditorLoadTmtLauncher == null) {
            mEditorLoadTmtLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (mEditorController != null) {
                    mEditorController.onLoadTmtResult(uri);
                }
            });
        }
    }

    private boolean handleEditorIntentIfNeeded(Intent intent) {
        if (intent == null) return false;
        FileOpenRequest request = FileEditorContract.fromIntent(intent);
        if (request == null) return false;

        boolean editorWasInitialized = mEditorController != null;
        if (mBottomNavTab != TAB_EDITOR) {
            setBottomNavTab(TAB_EDITOR);
        } else {
            ensureEditorInitialized();
        }

        if (editorWasInitialized && mEditorController != null) {
            mEditorController.onOpenRequest(request, "activity.intent");
        }
        return true;
    }

    private void handleUiNavigationIntentIfNeeded(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (TermuxActivityUiReceiver.ACTION_OPEN_FILES_AT.equals(action)) {
            openFilesAtPath(intent.getStringExtra(TermuxActivityUiReceiver.EXTRA_PATH));
        } else if (TermuxActivityUiReceiver.ACTION_SWITCH_TAB.equals(action)) {
            switchBottomTabByName(intent.getStringExtra(TermuxActivityUiReceiver.EXTRA_TAB));
        }
    }

    private Intent getFileManagerIntent() {
        if (mFileManagerIntent == null) {
            mFileManagerIntent = new Intent();
        }
        return mFileManagerIntent;
    }

    private void ensureFileManagerInitialized() {
        if (mFilesPage == null) return;
        if (!(mFilesPage instanceof ViewGroup)) return;
        if (mFileManagerController != null) return;

        if (mFileManagerBinding == null) {
            mFileManagerBinding = FmActivityMainBinding.inflate(getLayoutInflater());
        }

        Function0<Intent> intentProvider = () -> getFileManagerIntent();
        mFileManagerController = new FileManagerController(this, mFileManagerBinding, intentProvider, this, false);
        mFileManagerController.attachTo((ViewGroup) mFilesPage);
        mFileManagerController.onCreate(null);

        try {
            if (mFileManagerSavedState != null) {
                mFileManagerController.onRestoreInstanceState(mFileManagerSavedState);
            } else {
                String requested = UiShellNavBridge.consumeRequestedFilesDir(this);
                if (requested != null && !requested.trim().isEmpty()) {
                    mFileManagerController.openPath(requested.trim(), true);
                }
            }
        } catch (Exception e) {
            Logger.showToast(this, getString(R.string.msg_file_manager_initialization_failed, e.getMessage()), true);
        }
    }

    private void resumeFileManager(boolean fromTabSwitch) {
        if (mFileManagerController != null) {
            if (!fromTabSwitch) {
                mFileManagerController.onResume();
            } else {
                mFileManagerController.onHostTabVisible();
            }
            if (mFileManagerBinding != null) {
                mFileManagerBinding.getRoot().requestFocus();
            }
        }
    }

    private void pauseFileManager(boolean fromTabSwitch) {
        if (mFileManagerController != null && !fromTabSwitch) {
            mFileManagerController.onPause();
        }
    }


    @Override
    public void onOpenFile(final FileOpenEvent event) {
        if (event == null) return;
        final FileOpenRequest request = event.getRequest();
        if (request == null) return;
        Runnable action = () -> {
            if (mBottomNavTab != TAB_EDITOR) {
                setBottomNavTab(TAB_EDITOR);
            } else {
                ensureEditorInitialized();
            }
        };

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action.run();
        } else {
            runOnUiThread(action);
        }
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;
        beginSessionUiBatch();
        try {
            setTermuxSessionsListView();

            final Intent intent = getIntent();
            setIntent(null);

            if (mTermuxService.isTermuxSessionsEmpty()) {
                if (mIsVisible) {
                    TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                        if (mTermuxService == null) return; // Activity might have been destroyed.
                        try {
                            boolean launchFailsafe = false;
                            if (intent != null && intent.getExtras() != null) {
                                launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                            }
                            boolean restoredPinnedSsh = mTermuxTerminalSessionActivityClient != null &&
                                mTermuxTerminalSessionActivityClient.ensurePinnedSshSession(true);
                            if (!restoredPinnedSsh) {
                                mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null);
                            }
                        } catch (WindowManager.BadTokenException e) {
                            // Activity finished - ignore.
                        }
                    });
                } else {
                    // The service connected while not in foreground - just bail out.
                    finishActivityIfNotFinishing();
                }
            } else {
                // If termux was started from launcher "New session" shortcut and activity is recreated,
                // then the original intent will be re-delivered, resulting in a new session being re-added
                // each time.
                if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {
                    // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                    boolean isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                    mTermuxTerminalSessionActivityClient.addNewSession(isFailSafe, null);
                } else {
                    mTermuxTerminalSessionActivityClient.setCurrentSession(mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast());
                }
            }

            // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
            mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
        } finally {
            endSessionUiBatch();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }






    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }



    private void setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set termux terminal view
        mTerminalContainer = findViewById(R.id.terminal_container);
        mTerminalSessionSurfaceView = findViewById(R.id.terminal_session_surface);
        mTerminalTopBarView = findViewById(R.id.terminal_top_bar);
        if (mTerminalSessionSurfaceView != null) {
            mTermuxTerminalSessionSurfaceBridge = new TermuxTerminalSessionSurfaceBridge(
                new TermuxTerminalSessionSurfaceBridge.Host() {
                    @Nullable
                    @Override
                    public TermuxService getTermuxService() {
                        return TermuxActivity.this.getTermuxService();
                    }

                    @Nullable
                    @Override
                    public TerminalSession getCurrentSession() {
                        return TermuxActivity.this.getCurrentSession();
                    }
                }
            );
            mTerminalSessionSurfaceView.setTerminalViewClient(mTermuxTerminalViewClient);
            mTerminalSessionSurfaceView.setCallbacks(new TerminalSessionSurfaceView.Callbacks() {
                @Override
                public void onSessionPageSwipeTouchDown() {
                    if (mTermuxTerminalViewClient != null) {
                        mTermuxTerminalViewClient.preparePreservingSoftKeyboardOnSessionSwitch();
                    }
                }

                @Override
                public void onSessionPageChangeStarted() {
                    if (mTermuxTerminalViewClient != null) {
                        mTermuxTerminalViewClient.beginPreservingSoftKeyboardOnSessionSwitch();
                    }
                }

                @Override
                public void onSessionPageChangeFinished() {
                    if (mTermuxTerminalViewClient != null) {
                        mTermuxTerminalViewClient.finishPreservingSoftKeyboardOnSessionSwitch();
                    }
                }

                @Override
                public void onSessionPageSelected(int index, @Nullable TerminalSession session, boolean fromUser) {
                    if (session == null || mTermuxTerminalSessionActivityClient == null) return;

                    mSurfaceSelectionDispatchInProgress = true;
                    try {
                        mTermuxTerminalSessionActivityClient.setCurrentSession(session);
                    } finally {
                        mSurfaceSelectionDispatchInProgress = false;
                    }
                    refreshTerminalTopBar();
                }

                @Override
                public void onActiveTerminalViewChanged(@NonNull TerminalView terminalView,
                                                        @Nullable TerminalSession session) {
                    mTerminalView = terminalView;
                    if (mTermuxTerminalViewClient != null) {
                        mTermuxTerminalViewClient.bindTerminalViewKeyboardBehavior(terminalView);
                    }
                    updateTerminalContextMenuRegistration(terminalView);
                }

                @Override
                public void onExtraKeysViewCreated(@NonNull ExtraKeysView extraKeysView) {
                    mExtraKeysView = extraKeysView;
                }
            });
            mTerminalView = mTerminalSessionSurfaceView.getCurrentTerminalView();
            updateTerminalContextMenuRegistration(mTerminalView);
        }
        if (mTerminalTopBarView != null) {
            mTermuxTerminalTopBarBridge = new TermuxTerminalTopBarBridge(
                new TermuxTerminalTopBarBridge.Host() {
                    @NonNull
                    @Override
                    public Context getContext() {
                        return TermuxActivity.this;
                    }

                    @Nullable
                    @Override
                    public TermuxService getTermuxService() {
                        return TermuxActivity.this.getTermuxService();
                    }

                    @Nullable
                    @Override
                    public TermuxTerminalSessionActivityClient getSessionClient() {
                        return mTermuxTerminalSessionActivityClient;
                    }

                    @Nullable
                    @Override
                    public TerminalSession getCurrentSession() {
                        return TermuxActivity.this.getCurrentSession();
                    }
                }
            );
            mTerminalTopBarController = new TerminalTopBarController(
                mTerminalTopBarView,
                new TerminalTopBarController.Callbacks() {
                    @Override
                    public void onAddSession() {
                        if (mTermuxTerminalSessionActivityClient != null) {
                            mTermuxTerminalSessionActivityClient.addNewLocalSession(null);
                            refreshTerminalTopBar();
                        }
                    }

                    @Override
                    public void onAddLongPress() {
                        if (mTermuxTerminalSessionActivityClient != null) {
                            mTermuxTerminalSessionActivityClient.showPlusLongPressPanel();
                        }
                    }

                    @Override
                    public void onSelectSession(int index) {
                        if (mTerminalSessionSurfaceView != null) {
                            mTerminalSessionSurfaceView.setCurrentSessionPage(index, true);
                        } else if (mTermuxTerminalSessionActivityClient != null) {
                            mTermuxTerminalSessionActivityClient.switchToSession(index);
                            refreshTerminalTopBar();
                        }
                    }

                    @Override
                    public void onCloseSession(int index) {
                        TermuxService service = getTermuxService();
                        if (mTermuxTerminalSessionActivityClient == null || service == null) return;

                        TermuxSession termuxSession = service.getTermuxSession(index);
                        if (termuxSession == null) return;

                        TerminalSession terminalSession = termuxSession.getTerminalSession();
                        if (terminalSession == null) return;

                        int sessionsSize = service.getTermuxSessionsSize();
                        boolean isClosingCurrent = terminalSession == getCurrentSession();

                        if (sessionsSize > 1 && isClosingCurrent) {
                            int newIndex = index == 0 ? 1 : index - 1;
                            if (mTerminalSessionSurfaceView != null) {
                                mTerminalSessionSurfaceView.setCurrentSessionPage(newIndex, true);
                            } else {
                                mTermuxTerminalSessionActivityClient.switchToSession(newIndex);
                            }
                            refreshTerminalTopBar();
                        }

                        if (terminalSession.isRunning()) {
                            termuxSession.killIfExecuting(TermuxActivity.this, true);
                        } else {
                            service.removeTermuxSession(terminalSession);
                        }

                        if (sessionsSize <= 1) {
                            finishActivityIfNotFinishing();
                        }
                    }

                    @Override
                    public void onLongPressSession(int index) {
                        if (mTermuxTerminalSessionActivityClient != null) {
                            mTermuxTerminalSessionActivityClient.onTerminalTabLongPress(index);
                        }
                    }
                }
            );
        }

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    private void refreshTerminalTopBar() {
        if (mTerminalTopBarController == null || mTermuxTerminalTopBarBridge == null) return;
        TermuxTerminalTopBarBridge.Snapshot snapshot = mTermuxTerminalTopBarBridge.capture();
        mTerminalTopBarController.render(snapshot.models);
        mTermuxTerminalTopBarBridge.publish(snapshot);
    }

    private void refreshTerminalSessionSurface() {
        if (mTerminalSessionSurfaceView == null || mTermuxTerminalSessionSurfaceBridge == null) return;
        TermuxTerminalSessionSurfaceBridge.Snapshot snapshot = mTermuxTerminalSessionSurfaceBridge.capture();
        mTerminalSessionSurfaceHasSnapshot = !snapshot.items.isEmpty();
        mTerminalSessionSurfaceView.submitSessions(snapshot.items, snapshot.selectedIndex, false);
    }

    public void applyTerminalSessionSurfaceSettings() {
        if (mTerminalSessionSurfaceView != null && mPreferences != null) {
            mTerminalSessionSurfaceView.setTerminalTextSize(mPreferences.getFontSize());
            mTerminalSessionSurfaceView.setTerminalKeepScreenOn(mPreferences.shouldKeepScreenOn());
        } else if (mTerminalView != null && mPreferences != null) {
            mTerminalView.setTextSize(mPreferences.getFontSize());
            mTerminalView.setKeepScreenOn(mPreferences.shouldKeepScreenOn());
        }
    }

    public void applyTerminalSessionSurfaceTypeface(@NonNull Typeface typeface) {
        if (mTerminalSessionSurfaceView != null) {
            mTerminalSessionSurfaceView.setTerminalTypeface(typeface);
        } else if (mTerminalView != null) {
            mTerminalView.setTypeface(typeface);
        }
    }

    public void onTerminalSessionTextChanged(@NonNull TerminalSession session) {
        if (mTerminalSessionSurfaceView != null) {
            mTerminalSessionSurfaceView.refreshSession(session);
        }
    }

    public void onTerminalSessionColorsChanged(@NonNull TerminalSession session) {
        if (mTerminalSessionSurfaceView != null) {
            mTerminalSessionSurfaceView.invalidateSession(session);
        }
    }

    public void onTerminalSessionSelectionCommitted(@Nullable TerminalSession session) {
        if (session == null || mSurfaceSelectionDispatchInProgress) return;
        refreshTerminalSessionSurface();
        refreshTerminalTopBar();
    }

    public boolean requestTerminalSessionSurfaceSelection(@Nullable TerminalSession session, boolean animate) {
        if (session == null || mSurfaceSelectionDispatchInProgress ||
            mTerminalSessionSurfaceView == null) {
            return false;
        }

        if (!mTerminalSessionSurfaceHasSnapshot) {
            refreshTerminalSessionSurface();
        }
        if (!mTerminalSessionSurfaceHasSnapshot) return false;

        TermuxService service = getTermuxService();
        if (service == null) return false;

        int index = service.getIndexOfSession(session);
        if (index < 0) return false;

        TerminalSession currentSurfaceSession = mTerminalSessionSurfaceView.getCurrentSession();
        if (currentSurfaceSession == session) return false;

        mTerminalSessionSurfaceView.setCurrentSessionPage(index, animate);
        return true;
    }

    private void scheduleCoalescedSessionListUiUpdate() {
        Runnable schedule = () -> {
            if (mSessionListUiUpdateScheduled) return;
            mSessionListUiUpdateScheduled = true;
            View anchor = mTerminalTopBarView;
            if (anchor == null && getWindow() != null) {
                anchor = getWindow().getDecorView();
            }
            if (anchor != null) {
                anchor.postOnAnimation(mCoalescedSessionListUiUpdate);
            } else {
                mSessionListUiUpdateScheduled = false;
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            schedule.run();
        } else {
            runOnUiThread(schedule);
        }
    }

    private void cancelCoalescedSessionListUiUpdate() {
        mSessionListUiUpdateScheduled = false;
        mSessionUiBatchDepth = 0;
        mSessionUiUpdatePendingInBatch = false;
        if (mTerminalTopBarView != null) {
            mTerminalTopBarView.removeCallbacks(mCoalescedSessionListUiUpdate);
        }
        if (getWindow() != null && getWindow().getDecorView() != null) {
            getWindow().getDecorView().removeCallbacks(mCoalescedSessionListUiUpdate);
        }
    }

    private void beginSessionUiBatch() {
        mSessionUiBatchDepth++;
    }

    private void endSessionUiBatch() {
        if (mSessionUiBatchDepth <= 0) return;
        mSessionUiBatchDepth--;
        if (mSessionUiBatchDepth == 0 && mSessionUiUpdatePendingInBatch) {
            mSessionUiUpdatePendingInBatch = false;
            scheduleCoalescedSessionListUiUpdate();
        }
    }

    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);
        termuxSessionListNotifyUpdated();
    }



    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this,
            getTerminalView() == null ? new TerminalView(this, null) : getTerminalView(),
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (mTerminalSessionSurfaceView != null) {
            mTerminalToolbarDefaultHeight = mTerminalSessionSurfaceView.getToolbarDefaultHeightPx();
        } else if (terminalToolbarViewPager != null) {
            ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
            mTerminalToolbarDefaultHeight = layoutParams.height;
        }

        if (mTerminalSessionSurfaceView != null) {
            mTerminalSessionSurfaceView.setToolbarMetrics(
                mTerminalToolbarDefaultHeight,
                mProperties.getTerminalToolbarHeightScaleFactor()
            );
            mTerminalSessionSurfaceView.setToolbarTextInputEnabled(false);
            mTerminalSessionSurfaceView.setToolbarButtonTextAllCaps(
                mProperties.shouldExtraKeysTextBeAllCaps());
            mTerminalSessionSurfaceView.setToolbarExtraKeys(
                mTermuxTerminalExtraKeys.getExtraKeysInfo(),
                mTermuxTerminalExtraKeys
            );
            mTerminalSessionSurfaceView.setToolbarVisible(
                mPreferences != null && mPreferences.shouldShowTerminalToolbar());
        }
    }

    private void setTerminalToolbarHeight() {
        if (mTerminalSessionSurfaceView != null && mTermuxTerminalExtraKeys != null) {
            mTerminalSessionSurfaceView.setToolbarMetrics(
                mTerminalToolbarDefaultHeight,
                mProperties.getTerminalToolbarHeightScaleFactor()
            );
            mTerminalSessionSurfaceView.setToolbarExtraKeys(
                mTermuxTerminalExtraKeys.getExtraKeysInfo(),
                mTermuxTerminalExtraKeys
            );
        }
    }

    public void toggleTerminalToolbar() {
        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        if (mTerminalSessionSurfaceView != null) {
            mTerminalSessionSurfaceView.setToolbarVisible(showNow);
        }
        if (showNow && isTerminalToolbarTextInputViewSelected() && mTerminalSessionSurfaceView != null) {
            mTerminalSessionSurfaceView.focusToolbarTextInput();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_surface_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
        });
    }

    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
            getDrawer().closeDrawers();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }





    @SuppressLint("RtlHardcoded")
    @Override
    protected boolean onBackPressedCompat() {
        if (mBottomNavTab == TAB_EDITOR) {
            int targetTab = (mBottomNavPrimaryTab == TAB_TERMINAL) ? TAB_TERMINAL : TAB_FILES;
            if (mBottomNavigationView != null) {
                mBottomNavigationView.setSelectedItemId(targetTab);
            } else {
                setBottomNavTab(targetTab);
            }
            return true;
        }
        if (mBottomNavTab == TAB_FILES && mFileManagerController != null) {
            if (mFileManagerController.onBackPressedCompat()) {
                return true;
            }
        }
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers();
            return true;
        }
        finishActivityIfNotFinishing();
        return true;
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        Logger.showToast(getApplicationContext(), text, longDuration);
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (mBottomNavTab == TAB_EDITOR && mEditorController != null) {
            menu.clear();
            return mEditorController.onCreateOptionsMenu(menu);
        }
        if (mBottomNavTab == TAB_EDITOR) {
            ensureEditorInitialized();
            if (mEditorController != null) {
                menu.clear();
                return mEditorController.onCreateOptionsMenu(menu);
            }
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mBottomNavTab == TAB_EDITOR && mEditorController != null) {
            if (mEditorController.onOptionsItemSelected(item)) {
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        if (mBottomNavTab == TAB_TERMINAL && featureId == Window.FEATURE_OPTIONS_PANEL) {
            if (!mAllowTerminalContextMenuMapping) {
                closeOptionsMenu();
                return false;
            }
            if (SystemClock.uptimeMillis() < mSuppressTerminalContextMenuUntilUptimeMs) {
                closeOptionsMenu();
                return false;
            }
            if (mSuppressTerminalContextMenuOnce) {
                mSuppressTerminalContextMenuOnce = false;
                closeOptionsMenu();
                return false;
            }
            if (mTerminalView != null) mTerminalView.showContextMenu();
            closeOptionsMenu();
            return false;
        }

        return super.onMenuOpened(featureId, menu);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                ActivityUtils.startActivity(this, new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME);
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            new AlertDialog.Builder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install,
                    (dialog, which) -> ActivityUtils.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL))))
                .setNegativeButton(android.R.string.cancel, null).show();
        }
    }
    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                // Do not ask for permission again
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

                // If permission is granted, then also setup storage symlinks.
                if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    TermuxActivity.this, requestCode, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));

                    TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        if (mExtraKeysView == null && mTerminalSessionSurfaceView != null) {
            mExtraKeysView = mTerminalSessionSurfaceView.getExtraKeysView();
        }
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return mTerminalSessionSurfaceView == null ? null : mTerminalSessionSurfaceView.getToolbarPager();
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }

    public boolean isTerminalViewSelected() {
        return mTerminalSessionSurfaceView == null || mTerminalSessionSurfaceView.isTerminalToolbarPrimaryPageSelected();
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return mTerminalSessionSurfaceView != null && mTerminalSessionSurfaceView.isTerminalToolbarTextInputPageSelected();
    }


    public void termuxSessionListNotifyUpdated() {
        if (mIsInvalidState) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(this::termuxSessionListNotifyUpdated);
            return;
        }
        if (mSessionUiBatchDepth > 0) {
            mSessionUiUpdatePendingInBatch = true;
            return;
        }
        scheduleCoalescedSessionListUiUpdate();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }

    public boolean isTerminalTabActive() {
        return mBottomNavTab == TAB_TERMINAL;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    private void updateTerminalContextMenuRegistration(@Nullable TerminalView terminalView) {
        if (mContextMenuTerminalView == terminalView) return;

        if (mContextMenuTerminalView != null) {
            unregisterForContextMenu(mContextMenuTerminalView);
        }

        mContextMenuTerminalView = terminalView;
        if (terminalView != null) {
            registerForContextMenu(terminalView);
        }
    }

    public TerminalView getTerminalView() {
        if (mTerminalSessionSurfaceView != null) {
            TerminalView activeTerminalView = mTerminalSessionSurfaceView.getCurrentTerminalView();
            if (activeTerminalView != null) {
                mTerminalView = activeTerminalView;
            }
        }
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null) {
            TerminalSession currentSession = mTerminalView.getCurrentSession();
            if (currentSession != null) return currentSession;
        }
        if (mTerminalSessionSurfaceView != null) {
            TerminalSession currentSession = mTerminalSessionSurfaceView.getCurrentSession();
            if (currentSession != null) return currentSession;
        }
        return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        requestStoragePermission(false);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            if (mTerminalSessionSurfaceView != null && mTermuxTerminalExtraKeys != null) {
                mTerminalSessionSurfaceView.setToolbarButtonTextAllCaps(
                    mProperties.shouldExtraKeysTextBeAllCaps());
                mTerminalSessionSurfaceView.setToolbarExtraKeys(
                    mTermuxTerminalExtraKeys.getExtraKeysInfo(),
                    mTermuxTerminalExtraKeys
                );
            } else if (mExtraKeysView != null && mTermuxTerminalExtraKeys != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        setTerminalToolbarHeight();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
