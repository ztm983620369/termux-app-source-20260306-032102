package com.termux.app.editor;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalSessionSurfaceBridge;
import com.termux.app.terminal.TermuxTerminalTopBarBridge;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.app.topbar.TerminalTopBarController;
import com.termux.app.topbar.TerminalTopBarView;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;
import com.termux.terminalsessionsurface.TerminalSessionSurfaceView;
import com.termux.view.TerminalView;

import java.io.File;

public final class EditorTerminalWorkspaceController {

    public interface Host {
        @NonNull Context getContext();
        @Nullable TermuxService getTermuxService();
        @Nullable TermuxTerminalSessionActivityClient getTerminalSessionClient();
        @Nullable TermuxTerminalViewClient getTerminalViewClient();
        @Nullable TermuxTerminalExtraKeys getTerminalExtraKeys();
        @Nullable TermuxAppSharedPreferences getPreferences();
        @Nullable TermuxAppSharedProperties getProperties();
        @Nullable TerminalSession getCurrentSession();
        void onEditorWorkspaceTerminalViewChanged(@Nullable TerminalView terminalView,
                                                 @Nullable TerminalSession session);
        void onEditorWorkspaceExtraKeysViewChanged(@Nullable ExtraKeysView extraKeysView);
        void onEditorWorkspaceSessionSelected(@NonNull TerminalSession session);
        float getTerminalToolbarDefaultHeight();
        void onEditorWorkspaceVisibilityChanged(boolean visible);
    }

    private static final long TRANSITION_DURATION_MS = 220L;

    private final Host mHost;
    private final ViewGroup mContainer;
    private final View mRootView;
    private final TerminalTopBarView mTopBarView;
    private final TerminalSessionSurfaceView mSessionSurfaceView;
    private final EditorTerminalWorkspaceStateMachine mStateMachine = new EditorTerminalWorkspaceStateMachine();

    private final TermuxTerminalSessionSurfaceBridge mSessionSurfaceBridge;
    private final TermuxTerminalTopBarBridge mTopBarBridge;
    private final TerminalTopBarController mTopBarController;

    @Nullable
    private String mPreferredWorkingDirectory;
    @Nullable
    private TerminalSession mPreviewSession;
    private boolean mSessionSurfaceHasSnapshot = false;

    public EditorTerminalWorkspaceController(@NonNull Host host, @NonNull ViewGroup container) {
        mHost = host;
        mContainer = container;
        mRootView = LayoutInflater.from(host.getContext()).inflate(R.layout.view_editor_terminal_workspace, container, false);
        mTopBarView = mRootView.findViewById(R.id.editor_terminal_top_bar);
        mSessionSurfaceView = mRootView.findViewById(R.id.editor_terminal_session_surface);
        mRootView.setVisibility(View.GONE);
        container.addView(mRootView, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        mSessionSurfaceBridge = new TermuxTerminalSessionSurfaceBridge(new TermuxTerminalSessionSurfaceBridge.Host() {
            @Nullable
            @Override
            public TermuxService getTermuxService() {
                return mHost.getTermuxService();
            }

            @Nullable
            @Override
            public TerminalSession getCurrentSession() {
                return mHost.getCurrentSession();
            }
        });

        mTopBarBridge = new TermuxTerminalTopBarBridge(new TermuxTerminalTopBarBridge.Host() {
            @NonNull
            @Override
            public Context getContext() {
                return mHost.getContext();
            }

            @Nullable
            @Override
            public TermuxService getTermuxService() {
                return mHost.getTermuxService();
            }

            @Nullable
            @Override
            public TermuxTerminalSessionActivityClient getSessionClient() {
                return mHost.getTerminalSessionClient();
            }

            @Nullable
            @Override
            public TerminalSession getCurrentSession() {
                return mHost.getCurrentSession();
            }

            @Nullable
            @Override
            public TerminalSession getTopBarSelectedSession() {
                return mPreviewSession;
            }
        });

        mTopBarController = new TerminalTopBarController(
            mTopBarView,
            new TerminalTopBarController.Callbacks() {
                @Override
                public void onAddSession() {
                    addSessionAtPreferredWorkingDirectory();
                }

                @Override
                public void onAddLongPress() {
                    TermuxTerminalSessionActivityClient sessionClient = mHost.getTerminalSessionClient();
                    if (sessionClient != null) {
                        sessionClient.showPlusLongPressPanel();
                    }
                }

                @Override
                public void onSelectSession(int index) {
                    clearSelectionPreview();
                    mSessionSurfaceView.setCurrentSessionPage(index, true);
                }

                @Override
                public void onCloseSession(int index) {
                    closeSessionAt(index);
                }

                @Override
                public void onLongPressSession(int index) {
                    TermuxTerminalSessionActivityClient sessionClient = mHost.getTerminalSessionClient();
                    if (sessionClient != null) {
                        sessionClient.onTerminalTabLongPress(index);
                    }
                }
            }
        );

        mSessionSurfaceView.setCallbacks(new TerminalSessionSurfaceView.Callbacks() {
            @Override
            public void onSessionPageSwipeTouchDown() {
                TermuxTerminalViewClient terminalViewClient = mHost.getTerminalViewClient();
                if (terminalViewClient != null) {
                    terminalViewClient.preparePreservingSoftKeyboardOnSessionSwitch();
                }
            }

            @Override
            public void onSessionPageChangeStarted() {
                TermuxTerminalViewClient terminalViewClient = mHost.getTerminalViewClient();
                if (terminalViewClient != null) {
                    terminalViewClient.beginPreservingSoftKeyboardOnSessionSwitch();
                }
            }

            @Override
            public void onSessionPageChangeFinished() {
                TermuxTerminalViewClient terminalViewClient = mHost.getTerminalViewClient();
                if (terminalViewClient != null) {
                    terminalViewClient.finishPreservingSoftKeyboardOnSessionSwitch();
                }
            }

            @Override
            public void onSessionPagePreviewSelected(int index, @Nullable TerminalSession session) {
                mPreviewSession = session;
                refreshTopBar();
            }

            @Override
            public void onConfigPagePreviewSelected() {
                mPreviewSession = null;
                refreshTopBar();
            }

            @Override
            public void onSessionPageSelected(int index, @Nullable TerminalSession session, boolean fromUser, long requestToken) {
                clearSelectionPreview();
                if (session == null) return;
                mHost.onEditorWorkspaceSessionSelected(session);
                refreshTopBar();
            }

            @Override
            public void onConfigPageSelected(boolean fromUser, long requestToken) {
                clearSelectionPreview();
                refreshTopBar();
            }

            @Override
            public void onActiveTerminalViewChanged(@NonNull TerminalView terminalView,
                                                    @Nullable TerminalSession session) {
                mHost.onEditorWorkspaceTerminalViewChanged(terminalView, session);
            }

            @Override
            public void onExtraKeysViewCreated(@NonNull ExtraKeysView extraKeysView) {
                mHost.onEditorWorkspaceExtraKeysViewChanged(extraKeysView);
            }
        });
    }

    public boolean show(@Nullable String currentFilePath) {
        boolean changed = mStateMachine.openTerminalWorkspace();
        updatePreferredWorkingDirectory(currentFilePath);
        mContainer.setVisibility(View.VISIBLE);
        mRootView.setVisibility(View.VISIBLE);
        mRootView.bringToFront();
        configureFromHostState();
        ensureSessionAvailable();
        refresh();
        mHost.onEditorWorkspaceTerminalViewChanged(getCurrentTerminalView(), getCurrentSession());
        mHost.onEditorWorkspaceExtraKeysViewChanged(getExtraKeysView());
        mHost.onEditorWorkspaceVisibilityChanged(true);
        if (changed) animateIn();
        return true;
    }

    public boolean hide() {
        boolean changed = mStateMachine.closeTerminalWorkspace();
        if (!changed) return false;

        clearSelectionPreview();
        mHost.onEditorWorkspaceExtraKeysViewChanged(null);
        mHost.onEditorWorkspaceVisibilityChanged(false);
        animateOut(() -> {
            mRootView.setVisibility(View.GONE);
            mContainer.setVisibility(View.GONE);
        });
        return true;
    }

    public boolean onBackPressed() {
        return hide();
    }

    public boolean isVisible() {
        return mStateMachine.isTerminalWorkspaceVisible();
    }

    public void destroy() {
        mRootView.animate().cancel();
        mHost.onEditorWorkspaceExtraKeysViewChanged(null);
        mContainer.removeView(mRootView);
    }

    public void refresh() {
        if (!isVisible()) return;
        configureFromHostState();
        refreshSessionSurface();
        refreshTopBar();
    }

    public void applyTerminalSessionSurfaceSettings() {
        TermuxAppSharedPreferences preferences = mHost.getPreferences();
        if (preferences == null) return;
        mSessionSurfaceView.setTerminalTextSize(preferences.getFontSize());
        mSessionSurfaceView.setTerminalKeepScreenOn(preferences.shouldKeepScreenOn());
    }

    public void applyTerminalSessionSurfaceTypeface(@NonNull android.graphics.Typeface typeface) {
        mSessionSurfaceView.setTerminalTypeface(typeface);
    }

    public void onTerminalSessionTextChanged(@NonNull TerminalSession session) {
        mSessionSurfaceView.refreshSession(session);
    }

    public void onTerminalSessionColorsChanged(@NonNull TerminalSession session) {
        mSessionSurfaceView.invalidateSession(session);
    }

    public boolean requestTerminalSessionSurfaceSelection(@Nullable TerminalSession session, boolean animate) {
        if (session == null) return false;
        if (!mSessionSurfaceHasSnapshot) {
            refreshSessionSurface();
        }
        if (!mSessionSurfaceHasSnapshot) return false;

        TermuxService service = mHost.getTermuxService();
        if (service == null) return false;

        int index = service.getIndexOfSession(session);
        if (index < 0) return false;

        TerminalSession currentSession = mSessionSurfaceView.getCurrentSession();
        if (currentSession == session) return false;

        mSessionSurfaceView.setCurrentSessionPage(index, animate);
        return true;
    }

    @Nullable
    public TerminalView getCurrentTerminalView() {
        return mSessionSurfaceView.getCurrentTerminalView();
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        return mSessionSurfaceView.getCurrentSession();
    }

    @Nullable
    public ExtraKeysView getExtraKeysView() {
        return mSessionSurfaceView.getExtraKeysView();
    }

    @Nullable
    public ViewPager getToolbarPager() {
        return mSessionSurfaceView.getToolbarPager();
    }

    public boolean isTerminalToolbarPrimaryPageSelected() {
        return mSessionSurfaceView.isTerminalToolbarPrimaryPageSelected();
    }

    public boolean isTerminalToolbarTextInputPageSelected() {
        return mSessionSurfaceView.isTerminalToolbarTextInputPageSelected();
    }

    @NonNull
    public TerminalSessionSurfaceView getSessionSurfaceView() {
        return mSessionSurfaceView;
    }

    private void refreshTopBar() {
        TermuxTerminalTopBarBridge.Snapshot snapshot = mTopBarBridge.capture();
        mTopBarController.render(snapshot.models);
        mTopBarView.setAddButtonSelected(false);
    }

    private void refreshSessionSurface() {
        TermuxTerminalSessionSurfaceBridge.Snapshot snapshot = mSessionSurfaceBridge.capture();
        mSessionSurfaceHasSnapshot = !snapshot.items.isEmpty();
        mSessionSurfaceView.submitSessions(snapshot.items, snapshot.selectedIndex, false);
    }

    private void configureFromHostState() {
        TermuxTerminalViewClient terminalViewClient = mHost.getTerminalViewClient();
        if (terminalViewClient != null) {
            mSessionSurfaceView.setTerminalViewClient(terminalViewClient);
        }

        TermuxAppSharedPreferences preferences = mHost.getPreferences();
        if (preferences != null) {
            mSessionSurfaceView.setTerminalTextSize(preferences.getFontSize());
            mSessionSurfaceView.setTerminalKeepScreenOn(preferences.shouldKeepScreenOn());
            mSessionSurfaceView.setToolbarVisible(preferences.shouldShowTerminalToolbar());
            mSessionSurfaceView.setFullScreenSessionSwipeEnabled(preferences.isTerminalFullScreenSwipeEnabled());
        }

        TermuxAppSharedProperties properties = mHost.getProperties();
        if (properties != null) {
            mSessionSurfaceView.setToolbarMetrics(
                mHost.getTerminalToolbarDefaultHeight(),
                properties.getTerminalToolbarHeightScaleFactor()
            );
            mSessionSurfaceView.setToolbarButtonTextAllCaps(properties.shouldExtraKeysTextBeAllCaps());
        }
        mSessionSurfaceView.setToolbarTextInputEnabled(false);

        TermuxTerminalExtraKeys terminalExtraKeys = mHost.getTerminalExtraKeys();
        if (terminalExtraKeys != null) {
            mSessionSurfaceView.setToolbarExtraKeys(
                terminalExtraKeys.getExtraKeysInfo(),
                terminalExtraKeys
            );
        }
    }

    private void ensureSessionAvailable() {
        TermuxService service = mHost.getTermuxService();
        TermuxTerminalSessionActivityClient sessionClient = mHost.getTerminalSessionClient();
        if (service == null || sessionClient == null) return;
        if (service.getTermuxSessionsSize() > 0) return;

        String workingDirectory = resolveWorkingDirectory();
        sessionClient.addNewSessionAt(workingDirectory);
    }

    private void addSessionAtPreferredWorkingDirectory() {
        TermuxTerminalSessionActivityClient sessionClient = mHost.getTerminalSessionClient();
        if (sessionClient == null) return;
        sessionClient.addNewSessionAt(resolveWorkingDirectory());
    }

    private String resolveWorkingDirectory() {
        if (!TextUtils.isEmpty(mPreferredWorkingDirectory)) {
            return mPreferredWorkingDirectory;
        }

        TerminalSession currentSession = mHost.getCurrentSession();
        if (currentSession != null && !TextUtils.isEmpty(currentSession.getCwd())) {
            return currentSession.getCwd();
        }

        TermuxAppSharedProperties properties = mHost.getProperties();
        return properties != null ? properties.getDefaultWorkingDirectory() : "/";
    }

    private void updatePreferredWorkingDirectory(@Nullable String currentFilePath) {
        if (TextUtils.isEmpty(currentFilePath)) return;
        File file = new File(currentFilePath);
        File directory = file.isDirectory() ? file : file.getParentFile();
        if (directory != null && directory.exists()) {
            mPreferredWorkingDirectory = directory.getAbsolutePath();
        }
    }

    private void clearSelectionPreview() {
        mPreviewSession = null;
    }

    private void closeSessionAt(int index) {
        TermuxService service = mHost.getTermuxService();
        TermuxTerminalSessionActivityClient sessionClient = mHost.getTerminalSessionClient();
        if (service == null || sessionClient == null) return;

        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession == null) return;

        TerminalSession terminalSession = termuxSession.getTerminalSession();
        if (terminalSession == null) return;

        int sessionsSize = service.getTermuxSessionsSize();
        boolean isClosingCurrent = terminalSession == mHost.getCurrentSession();

        if (sessionsSize > 1 && isClosingCurrent) {
            int newIndex = index == 0 ? 1 : index - 1;
            mSessionSurfaceView.setCurrentSessionPage(newIndex, true);
            refreshTopBar();
        }

        if (terminalSession.isRunning()) {
            termuxSession.killIfExecuting(mHost.getContext(), true);
        } else {
            service.removeTermuxSession(terminalSession);
        }

        if (sessionsSize <= 1) {
            mRootView.post(this::hide);
        }
    }

    private void animateIn() {
        int width = mRootView.getWidth();
        if (width <= 0) width = mContainer.getWidth();
        if (width <= 0) width = mHost.getContext().getResources().getDisplayMetrics().widthPixels;
        if (width <= 0) width = 1;

        mRootView.animate().cancel();
        mRootView.setTranslationX(width);
        mRootView.setAlpha(1f);
        mRootView.animate()
            .translationX(0f)
            .setDuration(TRANSITION_DURATION_MS)
            .setInterpolator(new DecelerateInterpolator())
            .start();
    }

    private void animateOut(@NonNull Runnable endAction) {
        int width = mRootView.getWidth();
        if (width <= 0) width = mContainer.getWidth();
        if (width <= 0) width = mHost.getContext().getResources().getDisplayMetrics().widthPixels;
        if (width <= 0) width = 1;

        mRootView.animate().cancel();
        mRootView.setTranslationX(0f);
        mRootView.setAlpha(1f);
        mRootView.animate()
            .translationX(width)
            .setDuration(TRANSITION_DURATION_MS)
            .setInterpolator(new DecelerateInterpolator())
            .withEndAction(() -> {
                mRootView.setTranslationX(0f);
                endAction.run();
            })
            .start();
    }
}
