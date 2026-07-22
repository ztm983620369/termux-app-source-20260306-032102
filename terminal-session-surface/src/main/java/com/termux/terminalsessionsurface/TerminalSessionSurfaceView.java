package com.termux.terminalsessionsurface;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TerminalSessionSurfaceView extends LinearLayout {
    private static final String LOG_TAG = "TerminalSessionSurface";
    private static final int MAX_SESSION_PAGER_OFFSCREEN_LIMIT = 2;
    private static final int MAX_VISIBLE_PAGE_RECONCILE_ATTEMPTS = 3;

    public interface Callbacks {
        void onSessionPageSwipeTouchDown();
        void onSessionPageChangeStarted();
        void onSessionPageChangeFinished();
        void onSessionPagePreviewSelected(int index, @Nullable TerminalSession session);
        void onConfigPagePreviewSelected();
        void onSessionPageSelected(int index, @Nullable TerminalSession session, boolean fromUser, long requestToken);
        void onConfigPageSelected(boolean fromUser, long requestToken);
        void onActiveTerminalViewChanged(@NonNull TerminalView terminalView, @Nullable TerminalSession session);
        void onExtraKeysViewCreated(@NonNull ExtraKeysView extraKeysView);
    }

    private static final String PLACEHOLDER_KEY = "__placeholder__";
    private static final String CONFIG_PAGE_KEY = "__config_page__";
    private static final TerminalSessionSurfaceItem PLACEHOLDER_ITEM =
        new TerminalSessionSurfaceItem(PLACEHOLDER_KEY, null);
    private static final TerminalSessionSurfaceItem CONFIG_PAGE_ITEM =
        new TerminalSessionSurfaceItem(CONFIG_PAGE_KEY, null);

    private final TerminalSessionSurfacePagerStateMachine pagerStateMachine =
        new TerminalSessionSurfacePagerStateMachine();
    private final TerminalSessionSurfaceToolbarStateMachine toolbarStateMachine =
        new TerminalSessionSurfaceToolbarStateMachine();
    private final TerminalSessionSelectionOriginStateMachine selectionOriginStateMachine =
        new TerminalSessionSelectionOriginStateMachine();

    private final SessionPagerAdapter sessionPagerAdapter = new SessionPagerAdapter();
    private final ToolbarPagerAdapter toolbarPagerAdapter = new ToolbarPagerAdapter();

    private ViewPager mSessionPager;
    private ViewPager mToolbarPager;

    @Nullable private Callbacks mCallbacks;
    @Nullable private TerminalViewClient mTerminalViewClient;
    @Nullable private ExtraKeysView.IExtraKeysView mExtraKeysViewClient;
    @Nullable private ExtraKeysInfo mExtraKeysInfo;
    @Nullable private Typeface mTerminalTypeface;
    @Nullable private ExtraKeysView mLastDispatchedExtraKeysView;
    @Nullable private View mConfigPageView;

    private int mTerminalTextSize;
    private boolean mTerminalKeepScreenOn;
    private boolean mToolbarVisible;
    private boolean mSuppressSessionPageCallback;
    private boolean mToolbarButtonTextAllCaps = true;
    private float mToolbarDefaultHeightPx;
    private float mToolbarHeightScale = 1f;
    private int mToolbarComputedHeightPx;
    private int mSelectedSessionIndex;
    private final int mSessionPageGapPx;
    private boolean mConfigPageEnabled;
    private boolean mSessionPageSwipeTouchActive;
    private boolean mSessionPageChangeInProgress;
    private boolean mProgrammaticFocusAllowed = true;
    private boolean mFullScreenSessionSwipeEnabled;
    @Nullable private String mAuthoritativeSelectedPageKey;
    private boolean mVisiblePageReconcileScheduled;
    private boolean mVisiblePageReconcileDeferredUntilIdle;
    private int mVisiblePageReconcileAttemptsRemaining;
    @NonNull
    private final Map<String, Long> mProgrammaticSelectionTokensByKey = new HashMap<>();
    @NonNull
    private final Runnable mVisiblePageReconcileRunnable = this::runVisiblePageReconciliation;

    public TerminalSessionSurfaceView(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        mSessionPageGapPx = Math.max(1, Math.round(2f * density));
        init(context);
    }

    public TerminalSessionSurfaceView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        mSessionPageGapPx = Math.max(1, Math.round(2f * density));
        init(context);
    }

    public TerminalSessionSurfaceView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = context.getResources().getDisplayMetrics().density;
        mSessionPageGapPx = Math.max(1, Math.round(2f * density));
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_terminal_session_surface, this, true);
        mSessionPager = findViewById(R.id.terminal_session_pager);
        mToolbarPager = findViewById(R.id.terminal_toolbar_view_pager);
        mToolbarDefaultHeightPx = resolveToolbarDefaultHeightPx();
        updateToolbarMetricsState();

        if (mSessionPager instanceof ProgrammaticViewPager) {
            ProgrammaticViewPager programmaticViewPager = (ProgrammaticViewPager) mSessionPager;
            programmaticViewPager.setSwipeRegionProvider(() -> {
                int configPageIndex = getConfigPageIndex();
                boolean configPageTargeted = configPageIndex >= 0 &&
                    (mSelectedSessionIndex == configPageIndex || mSessionPager.getCurrentItem() == configPageIndex);
                int activeIndex = configPageTargeted ? configPageIndex : mSessionPager.getCurrentItem();
                PageHolder holder = sessionPagerAdapter.findHolder(activeIndex);
                if (configPageTargeted) {
                    if (holder != null && holder.configContainer.isShown() &&
                        holder.configContainer.getWidth() > 0 && holder.configContainer.getHeight() > 0) {
                        return holder.configContainer;
                    }
                    // The config page can be the selected target before ViewPager updates currentItem
                    // and attaches the holder on the first entry.
                    return mSessionPager;
                }
                if (holder == null) return null;
                if (TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) {
                    return holder.root;
                }
                return mFullScreenSessionSwipeEnabled ? holder.terminalView : null;
            });
            programmaticViewPager.setSwipeGestureListener(new ProgrammaticViewPager.SwipeGestureListener() {
                @Override
                public void onSwipeTouchDownInRegion() {
                    mSessionPageSwipeTouchActive = true;
                    if (mCallbacks != null) {
                        mCallbacks.onSessionPageSwipeTouchDown();
                    }
                }

                @Override
                public void onSwipeGestureCaptured() {
                    notifySessionPageChangeStarted();
                }

                @Override
                public void onSwipeGestureFinished() {
                    finishSessionPageChangeIfIdle();
                }
            });
        }

        updateSessionPagerCachePolicy();
        mSessionPager.setPageMargin(mSessionPageGapPx);
        mSessionPager.setPageMarginDrawable(new ColorDrawable(0xFF000000));
        mSessionPager.setAdapter(sessionPagerAdapter);
        mSessionPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager.SCROLL_STATE_DRAGGING) {
                    notifySessionPageChangeStarted();
                    pagerStateMachine.onDragStarted();
                } else if (state == ViewPager.SCROLL_STATE_SETTLING) {
                    pagerStateMachine.onSettlingStarted();
                } else {
                    pagerStateMachine.onIdle();
                    mSelectedSessionIndex = sessionPagerAdapter.clampIndex(mSessionPager.getCurrentItem());
                    dispatchIdlePageSelection(mSelectedSessionIndex);
                    if (mVisiblePageReconcileDeferredUntilIdle) {
                        mVisiblePageReconcileDeferredUntilIdle = false;
                        scheduleVisiblePageReconciliation();
                    }
                }
            }

            @Override
            public void onPageSelected(int position) {
                mSelectedSessionIndex = sessionPagerAdapter.clampIndex(position);
                if (!mSuppressSessionPageCallback) {
                    dispatchPreviewPageSelected(mSelectedSessionIndex);
                }
                if (pagerStateMachine.getState() == TerminalSessionSurfacePagerStateMachine.State.IDLE &&
                    !mSuppressSessionPageCallback) {
                    dispatchActivePageChanged(mSelectedSessionIndex, false);
                }
            }
        });

        mToolbarPager.setAdapter(toolbarPagerAdapter);
        mToolbarPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                toolbarStateMachine.onPageSelected(position);
                updateToolbarPresentation();
                if (!mProgrammaticFocusAllowed) return;
                if (position == 0) {
                    TerminalView terminalView = getCurrentTerminalView();
                    if (terminalView != null) terminalView.requestFocus();
                } else {
                    EditText editText = findViewById(R.id.terminal_surface_text_input);
                    if (editText != null) editText.requestFocus();
                }
            }
        });

        updateToolbarPresentation();
    }

    public void setCallbacks(@Nullable Callbacks callbacks) {
        mCallbacks = callbacks;
        TerminalView terminalView = getCurrentTerminalView();
        if (callbacks != null && terminalView != null) {
            callbacks.onActiveTerminalViewChanged(terminalView, terminalView.getCurrentSession());
        }
        dispatchCurrentExtraKeysViewChanged();
    }

    public void setTerminalViewClient(@Nullable TerminalViewClient terminalViewClient) {
        mTerminalViewClient = terminalViewClient;
        sessionPagerAdapter.applyTerminalViewConfigToAll();
    }

    public void setTerminalTextSize(int terminalTextSize) {
        if (mTerminalTextSize == terminalTextSize) return;
        mTerminalTextSize = terminalTextSize;
        sessionPagerAdapter.applyTerminalViewConfigToAll();
    }

    public void setTerminalKeepScreenOn(boolean terminalKeepScreenOn) {
        if (mTerminalKeepScreenOn == terminalKeepScreenOn) return;
        mTerminalKeepScreenOn = terminalKeepScreenOn;
        sessionPagerAdapter.applyTerminalViewConfigToAll();
    }

    public void setTerminalTypeface(@Nullable Typeface terminalTypeface) {
        if (mTerminalTypeface == terminalTypeface) return;
        mTerminalTypeface = terminalTypeface;
        sessionPagerAdapter.applyTerminalViewConfigToAll();
    }

    public void setToolbarVisible(boolean toolbarVisible) {
        if (mToolbarVisible == toolbarVisible) return;
        mToolbarVisible = toolbarVisible;
        updateToolbarPresentation();
    }

    public void setToolbarTextInputEnabled(boolean textInputEnabled) {
        toolbarStateMachine.setTextInputEnabled(textInputEnabled);
        toolbarPagerAdapter.notifyDataSetChanged();
        if (!textInputEnabled) {
            mToolbarPager.setCurrentItem(0, false);
        }
        updateToolbarPresentation();
    }

    public void setToolbarButtonTextAllCaps(boolean toolbarButtonTextAllCaps) {
        if (mToolbarButtonTextAllCaps == toolbarButtonTextAllCaps) return;
        mToolbarButtonTextAllCaps = toolbarButtonTextAllCaps;
        sessionPagerAdapter.reloadExtraKeysViews();
    }

    public void setToolbarMetrics(float defaultHeightPx, float heightScale) {
        float resolvedDefaultHeightPx = defaultHeightPx > 0f ? defaultHeightPx : resolveToolbarDefaultHeightPx();
        float resolvedHeightScale = heightScale <= 0f ? 1f : heightScale;
        if (Float.compare(mToolbarDefaultHeightPx, resolvedDefaultHeightPx) == 0 &&
            Float.compare(mToolbarHeightScale, resolvedHeightScale) == 0) {
            return;
        }
        mToolbarDefaultHeightPx = resolvedDefaultHeightPx;
        mToolbarHeightScale = resolvedHeightScale;
        updateToolbarMetricsState();
        sessionPagerAdapter.reloadExtraKeysViews();
        updateToolbarPresentation();
    }

    public float getToolbarDefaultHeightPx() {
        return mToolbarDefaultHeightPx;
    }

    public void setToolbarExtraKeys(@Nullable ExtraKeysInfo extraKeysInfo,
                                    @Nullable ExtraKeysView.IExtraKeysView extraKeysViewClient) {
        if (mExtraKeysInfo == extraKeysInfo && mExtraKeysViewClient == extraKeysViewClient) return;
        mExtraKeysInfo = extraKeysInfo;
        mExtraKeysViewClient = extraKeysViewClient;
        updateToolbarMetricsState();
        sessionPagerAdapter.reloadExtraKeysViews();
        updateToolbarPresentation();
    }

    public void submitSessions(@NonNull List<TerminalSessionSurfaceItem> items,
                               int selectedIndex,
                               boolean animate) {
        submitSessions(items, selectedIndex, animate, 0L);
    }

    public void submitSessions(@NonNull List<TerminalSessionSurfaceItem> items,
                               int selectedIndex,
                               boolean animate,
                               long requestToken) {
        int previousCurrentItem = sessionPagerAdapter.clampIndex(mSessionPager.getCurrentItem());
        String previousCurrentKey = sessionPagerAdapter.getItemKeyAtPosition(previousCurrentItem);
        TerminalSessionSurfaceItems.ChangeType changeType = sessionPagerAdapter.submitItems(items);
        boolean dataChanged = changeType != TerminalSessionSurfaceItems.ChangeType.NONE;
        pruneProgrammaticSelectionTokens();
        updateSessionPagerCachePolicy();
        int safeIndex = sessionPagerAdapter.clampIndex(selectedIndex);
        int currentItem = sessionPagerAdapter.clampIndex(mSessionPager.getCurrentItem());
        String targetKey = sessionPagerAdapter.getItemKeyAtPosition(safeIndex);
        boolean stableSelectionChanged = !TextUtils.equals(previousCurrentKey, targetKey);
        boolean syntheticPageRecreated =
            changeType == TerminalSessionSurfaceItems.ChangeType.STRUCTURE &&
                sessionPagerAdapter.isSyntheticKey(previousCurrentKey);
        boolean positionMismatch = safeIndex != currentItem;

        if (pagerStateMachine.getState() != TerminalSessionSurfacePagerStateMachine.State.IDLE) {
            mSelectedSessionIndex = currentItem;
            if (dataChanged) {
                dispatchCurrentExtraKeysViewChanged();
            }
            return;
        }

        mSelectedSessionIndex = safeIndex;
        mAuthoritativeSelectedPageKey = targetKey;
        if (stableSelectionChanged || syntheticPageRecreated || positionMismatch) {
            rememberProgrammaticSelectionForPosition(safeIndex, requestToken);
            notifySessionPageChangeStarted();
            mSuppressSessionPageCallback = true;
            mSessionPager.setCurrentItem(safeIndex, animate);
            mSuppressSessionPageCallback = false;
        } else if (requestToken > 0L) {
            rememberProgrammaticSelectionForPosition(safeIndex, requestToken);
        } else if (dataChanged) {
            dispatchCurrentExtraKeysViewChanged();
        } else if (requestToken <= 0L) {
            return;
        }
        if (pagerStateMachine.getState() == TerminalSessionSurfacePagerStateMachine.State.IDLE) {
            dispatchActivePageChanged(safeIndex, false);
        }
        scheduleVisiblePageReconciliation();
    }

    public void setCurrentSessionPage(int index, boolean animate) {
        setCurrentSessionPage(index, animate, 0L);
    }

    public void setCurrentSessionPage(int index, boolean animate, long requestToken) {
        int safeIndex = sessionPagerAdapter.clampIndex(index);
        mSelectedSessionIndex = safeIndex;
        mAuthoritativeSelectedPageKey = sessionPagerAdapter.getItemKeyAtPosition(safeIndex);
        rememberProgrammaticSelectionForPosition(safeIndex, requestToken);
        if (safeIndex == mSessionPager.getCurrentItem()) {
            dispatchActivePageChanged(safeIndex, false);
            scheduleVisiblePageReconciliation();
            return;
        }
        notifySessionPageChangeStarted();
        mSuppressSessionPageCallback = true;
        mSessionPager.setCurrentItem(safeIndex, animate);
        mSuppressSessionPageCallback = false;
        if (pagerStateMachine.getState() == TerminalSessionSurfacePagerStateMachine.State.IDLE) {
            dispatchActivePageChanged(safeIndex, false);
        }
        scheduleVisiblePageReconciliation();
    }

    public void setConfigPageView(@Nullable View configPageView) {
        if (mConfigPageView == configPageView) return;
        mConfigPageView = configPageView;
        sessionPagerAdapter.rebindConfigPage();
        sessionPagerAdapter.notifyDataSetChanged();
        updateSessionPagerCachePolicy();
        scheduleVisiblePageReconciliation();
    }

    public void setConfigPageEnabled(boolean enabled) {
        if (mConfigPageEnabled == enabled) return;
        mConfigPageEnabled = enabled;
        sessionPagerAdapter.pruneUnavailableHolders();
        sessionPagerAdapter.notifyDataSetChanged();
        updateSessionPagerCachePolicy();
        scheduleVisiblePageReconciliation();
    }

    public boolean isConfigPageEnabled() {
        return mConfigPageEnabled;
    }

    public int getConfigPageIndex() {
        return mConfigPageEnabled ? sessionPagerAdapter.getItemsCount() : -1;
    }

    public void setCurrentConfigPage(boolean animate) {
        setCurrentConfigPage(animate, 0L);
    }

    public void setCurrentConfigPage(boolean animate, long requestToken) {
        if (!mConfigPageEnabled) return;
        int configIndex = getConfigPageIndex();
        if (configIndex < 0) return;
        mSelectedSessionIndex = configIndex;
        mAuthoritativeSelectedPageKey = CONFIG_PAGE_KEY;
        rememberProgrammaticSelectionForPosition(configIndex, requestToken);
        if (configIndex == mSessionPager.getCurrentItem()) {
            dispatchActivePageChanged(configIndex, false);
            scheduleVisiblePageReconciliation();
            return;
        }
        notifySessionPageChangeStarted();
        mSuppressSessionPageCallback = true;
        mSessionPager.setCurrentItem(configIndex, animate);
        mSuppressSessionPageCallback = false;
        if (pagerStateMachine.getState() == TerminalSessionSurfacePagerStateMachine.State.IDLE) {
            dispatchActivePageChanged(configIndex, false);
        }
        scheduleVisiblePageReconciliation();
    }

    public void refreshSession(@NonNull TerminalSession session) {
        PageHolder holder = sessionPagerAdapter.findHolder(session);
        if (holder == null) return;
        if (!shouldRenderHolder(holder)) {
            holder.pendingScreenUpdate = true;
            return;
        }
        refreshTerminalHolder(holder, true);
    }

    public void invalidateSession(@NonNull TerminalSession session) {
        PageHolder holder = sessionPagerAdapter.findHolder(session);
        if (holder == null) return;
        if (!shouldRenderHolder(holder)) {
            holder.pendingInvalidate = true;
            return;
        }
        holder.pendingInvalidate = false;
        holder.terminalView.invalidate();
    }

    @Nullable
    public TerminalView getCurrentTerminalView() {
        PageHolder holder = sessionPagerAdapter.findAttachedHolder(mSessionPager.getCurrentItem());
        if (holder == null || TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) return null;
        return holder.terminalView;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        TerminalView terminalView = getCurrentTerminalView();
        return terminalView == null ? null : terminalView.getCurrentSession();
    }

    @Nullable
    public ViewPager getToolbarPager() {
        return mToolbarPager;
    }

    @Nullable
    public ExtraKeysView getExtraKeysView() {
        PageHolder holder = sessionPagerAdapter.findAttachedHolder(mSessionPager.getCurrentItem());
        if (holder == null || TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) return null;
        return holder.extraKeysView;
    }

    /** Reconcile the logical selection with the actually attached page and force its first frame. */
    public void onHostVisible() {
        mVisiblePageReconcileAttemptsRemaining = MAX_VISIBLE_PAGE_RECONCILE_ATTEMPTS;
        if (reconcileVisiblePageNow(true)) {
            cancelVisiblePageReconciliation();
        } else {
            scheduleVisiblePageReconciliation();
        }
    }

    public boolean isTerminalToolbarPrimaryPageSelected() {
        return toolbarStateMachine.isTerminalPageSelected();
    }

    public boolean isTerminalToolbarTextInputPageSelected() {
        return toolbarStateMachine.isTextInputPageSelected();
    }

    public void setProgrammaticFocusAllowed(boolean allowed) {
        mProgrammaticFocusAllowed = allowed;
    }

    /**
     * If enabled, horizontal session paging swipes may start from anywhere on the terminal page.
     * If disabled, swipes are restricted to the bottom extra keys region.
     */
    public void setFullScreenSessionSwipeEnabled(boolean enabled) {
        if (mFullScreenSessionSwipeEnabled == enabled) return;
        mFullScreenSessionSwipeEnabled = enabled;
    }

    public void focusToolbarTextInput() {
        mProgrammaticFocusAllowed = true;
        EditText editText = findViewById(R.id.terminal_surface_text_input);
        if (editText != null) editText.requestFocus();
    }

    private void notifySessionPageChangeStarted() {
        if (mSessionPageChangeInProgress) return;

        mSessionPageChangeInProgress = true;
        if (mCallbacks != null) {
            mCallbacks.onSessionPageChangeStarted();
        }
    }

    private void finishSessionPageChangeIfIdle() {
        if (mSessionPageChangeInProgress) {
            if (pagerStateMachine.getState() != TerminalSessionSurfacePagerStateMachine.State.IDLE) {
                return;
            }
            dispatchSessionPageChangeFinished();
            return;
        }

        if (!mSessionPageSwipeTouchActive) return;

        dispatchSessionPageChangeFinished();
    }

    private void dispatchSessionPageChangeFinished() {
        if (!mSessionPageChangeInProgress && !mSessionPageSwipeTouchActive) return;

        mSessionPageChangeInProgress = false;
        mSessionPageSwipeTouchActive = false;
        if (mCallbacks != null) {
            mCallbacks.onSessionPageChangeFinished();
        }
    }

    private void dispatchPreviewPageSelected(int position) {
        if (mCallbacks == null) return;
        PageHolder holder = sessionPagerAdapter.findHolder(position);
        if (holder == null) return;
        flushPendingHolderRefresh(holder);

        if (TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) {
            mCallbacks.onConfigPagePreviewSelected();
            return;
        }

        mCallbacks.onSessionPagePreviewSelected(position, holder.session);
    }

    private void dispatchActivePageChanged(int position, boolean fromUser) {
        PageHolder holder = sessionPagerAdapter.findAttachedHolder(position);
        if (holder == null && sessionPagerAdapter.getCount() > 0) {
            scheduleVisiblePageReconciliation();
            mSessionPager.postOnAnimation(() -> {
                PageHolder attachedHolder = sessionPagerAdapter.findAttachedHolder(position);
                if (attachedHolder != null) {
                    dispatchActivePageChanged(position, fromUser);
                } else {
                    dispatchSessionPageChangeFinished();
                }
            });
            return;
        }
        if (holder == null) {
            dispatchSessionPageChangeFinished();
            return;
        }

        refreshTerminalHolder(holder, true);

        if (TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) {
            if (mCallbacks != null && !mSuppressSessionPageCallback) {
                mCallbacks.onConfigPageSelected(fromUser, consumeProgrammaticSelectionToken(holder.key, fromUser));
            }
            dispatchCurrentExtraKeysViewChanged();
            if (!fromUser) selectionOriginStateMachine.completeProgrammaticSelection(holder.key);
            dispatchSessionPageChangeFinished();
            return;
        }

        if (mCallbacks != null) {
            mCallbacks.onActiveTerminalViewChanged(holder.terminalView, holder.session);
        }
        if (mCallbacks != null && !mSuppressSessionPageCallback) {
            mCallbacks.onSessionPageSelected(
                position,
                holder.session,
                fromUser,
                consumeProgrammaticSelectionToken(holder.key, fromUser)
            );
        }
        dispatchCurrentExtraKeysViewChanged();
        if (!fromUser) selectionOriginStateMachine.completeProgrammaticSelection(holder.key);
        dispatchSessionPageChangeFinished();
    }

    private void dispatchIdlePageSelection(int position) {
        String selectedKey = sessionPagerAdapter.getItemKeyAtPosition(position);
        mAuthoritativeSelectedPageKey = selectedKey;
        TerminalSessionSelectionOriginStateMachine.Resolution resolution =
            selectionOriginStateMachine.resolveIdleSelection(selectedKey);
        if (!TextUtils.isEmpty(resolution.abandonedProgrammaticKey)) {
            mProgrammaticSelectionTokensByKey.remove(resolution.abandonedProgrammaticKey);
        }
        dispatchActivePageChanged(
            position,
            resolution.origin == TerminalSessionSelectionOriginStateMachine.Origin.USER
        );
    }

    private boolean shouldRenderHolder(@NonNull PageHolder holder) {
        int holderPosition = sessionPagerAdapter.findPosition(holder.key);
        return TerminalSessionSurfaceRenderPolicy.shouldRender(
            holderPosition,
            mSessionPager.getCurrentItem(),
            pagerStateMachine.getState()
        );
    }

    private void flushPendingHolderRefresh(@NonNull PageHolder holder) {
        refreshTerminalHolder(holder, false);
    }

    private boolean refreshTerminalHolder(@NonNull PageHolder holder, boolean forceFrame) {
        if (holder.session == null || TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) return true;
        if (holder.terminalView.mRenderer == null) {
            holder.pendingScreenUpdate = holder.pendingScreenUpdate || forceFrame;
            return false;
        }
        if (holder.terminalSizeUpdateInProgress) {
            holder.pendingScreenUpdate = holder.pendingScreenUpdate || forceFrame;
            return false;
        }
        holder.terminalSizeUpdateInProgress = true;
        try {
            if (holder.terminalView.getCurrentSession() != holder.session) {
                holder.terminalView.attachSession(holder.session);
            }
            holder.terminalView.updateSize();
        } finally {
            holder.terminalSizeUpdateInProgress = false;
        }
        if (forceFrame || holder.pendingScreenUpdate) {
            holder.pendingScreenUpdate = false;
            holder.pendingInvalidate = false;
            holder.terminalView.onScreenUpdated();
            holder.terminalView.invalidate();
        } else if (holder.pendingInvalidate) {
            holder.pendingInvalidate = false;
            holder.terminalView.invalidate();
        }
        return holder.terminalView.mEmulator != null;
    }

    private boolean reconcileVisiblePageNow(boolean hostVisibilityAsserted) {
        if (mSessionPager == null || !isAttachedToWindow() ||
            (!hostVisibilityAsserted && (!isShown() || getWindowVisibility() != View.VISIBLE)) ||
            pagerStateMachine.getState() != TerminalSessionSurfacePagerStateMachine.State.IDLE) {
            return false;
        }

        int targetPosition = sessionPagerAdapter.findPosition(mAuthoritativeSelectedPageKey);
        if (targetPosition < 0) {
            targetPosition = sessionPagerAdapter.clampIndex(mSessionPager.getCurrentItem());
            mAuthoritativeSelectedPageKey = sessionPagerAdapter.getItemKeyAtPosition(targetPosition);
        }

        if (mSessionPager.getCurrentItem() != targetPosition) {
            mSuppressSessionPageCallback = true;
            try {
                mSessionPager.setCurrentItem(targetPosition, false);
            } finally {
                mSuppressSessionPageCallback = false;
            }
        }

        PageHolder holder = sessionPagerAdapter.findAttachedHolder(targetPosition);
        if (holder == null || !TextUtils.equals(holder.key, mAuthoritativeSelectedPageKey)) {
            sessionPagerAdapter.notifyDataSetChanged();
            mSessionPager.requestLayout();
            return false;
        }

        if (TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) {
            sessionPagerAdapter.rebindConfigPage();
            dispatchCurrentExtraKeysViewChanged();
            return true;
        }

        boolean ready = refreshTerminalHolder(holder, true);
        if (mCallbacks != null) {
            mCallbacks.onActiveTerminalViewChanged(holder.terminalView, holder.session);
        }
        dispatchCurrentExtraKeysViewChanged();
        return ready;
    }

    private void scheduleVisiblePageReconciliation() {
        if (mSessionPager == null || mVisiblePageReconcileScheduled) return;
        if (mVisiblePageReconcileAttemptsRemaining <= 0) {
            mVisiblePageReconcileAttemptsRemaining = MAX_VISIBLE_PAGE_RECONCILE_ATTEMPTS;
        }
        mVisiblePageReconcileScheduled = true;
        postOnAnimation(mVisiblePageReconcileRunnable);
    }

    private void runVisiblePageReconciliation() {
        mVisiblePageReconcileScheduled = false;
        if (pagerStateMachine.getState() != TerminalSessionSurfacePagerStateMachine.State.IDLE) {
            mVisiblePageReconcileDeferredUntilIdle = true;
            return;
        }
        if (reconcileVisiblePageNow(false)) {
            mVisiblePageReconcileAttemptsRemaining = 0;
            return;
        }
        mVisiblePageReconcileAttemptsRemaining--;
        if (mVisiblePageReconcileAttemptsRemaining > 0 && isAttachedToWindow() && isShown()) {
            mVisiblePageReconcileScheduled = true;
            postOnAnimation(mVisiblePageReconcileRunnable);
        } else if (mVisiblePageReconcileAttemptsRemaining <= 0 && isAttachedToWindow() && isShown()) {
            Log.w(LOG_TAG, "Visible page reconciliation exhausted: target=" +
                mAuthoritativeSelectedPageKey + ", current=" + mSessionPager.getCurrentItem());
        }
    }

    private void cancelVisiblePageReconciliation() {
        removeCallbacks(mVisiblePageReconcileRunnable);
        mVisiblePageReconcileScheduled = false;
        mVisiblePageReconcileDeferredUntilIdle = false;
        mVisiblePageReconcileAttemptsRemaining = 0;
    }

    private void dispatchCurrentExtraKeysViewChanged() {
        ExtraKeysView extraKeysView = getExtraKeysView();
        if (extraKeysView == mLastDispatchedExtraKeysView) return;

        mLastDispatchedExtraKeysView = extraKeysView;
        if (mCallbacks != null && extraKeysView != null) {
            mCallbacks.onExtraKeysViewCreated(extraKeysView);
        }
    }

    private void updateToolbarMetricsState() {
        int rows = mExtraKeysInfo == null ? 0 : mExtraKeysInfo.getMatrix().length;
        mToolbarComputedHeightPx = Math.round(mToolbarDefaultHeightPx * rows * mToolbarHeightScale);
    }

    private void rememberProgrammaticSelectionForPosition(int position, long requestToken) {
        String key = sessionPagerAdapter.getItemKeyAtPosition(position);
        if (TextUtils.isEmpty(key)) return;
        String supersededKey = selectionOriginStateMachine.beginProgrammaticSelection(key);
        if (!TextUtils.isEmpty(supersededKey)) {
            mProgrammaticSelectionTokensByKey.remove(supersededKey);
        }
        if (requestToken > 0L) mProgrammaticSelectionTokensByKey.put(key, requestToken);
    }

    private long consumeProgrammaticSelectionToken(@Nullable String key, boolean fromUser) {
        if (fromUser || TextUtils.isEmpty(key)) return 0L;
        Long token = mProgrammaticSelectionTokensByKey.remove(key);
        return token == null ? 0L : token;
    }

    private void pruneProgrammaticSelectionTokens() {
        if (!mProgrammaticSelectionTokensByKey.isEmpty()) {
            ArrayList<String> keys = new ArrayList<>(mProgrammaticSelectionTokensByKey.keySet());
            for (String key : keys) {
                if (!sessionPagerAdapter.containsItemKey(key)) {
                    mProgrammaticSelectionTokensByKey.remove(key);
                }
            }
        }
        String pendingKey = selectionOriginStateMachine.clear();
        if (!TextUtils.isEmpty(pendingKey) && sessionPagerAdapter.containsItemKey(pendingKey)) {
            selectionOriginStateMachine.beginProgrammaticSelection(pendingKey);
        }
    }

    private boolean shouldShowIntegratedToolbar() {
        return mToolbarVisible && toolbarStateMachine.isTerminalPageSelected() && mToolbarComputedHeightPx > 0;
    }

    private boolean shouldShowTextInputToolbar() {
        return mToolbarVisible && toolbarStateMachine.isTextInputEnabled() &&
            toolbarStateMachine.isTextInputPageSelected() && mToolbarComputedHeightPx > 0;
    }

    private void updateToolbarPresentation() {
        boolean showTextInput = shouldShowTextInputToolbar();
        ViewGroup.LayoutParams layoutParams = mToolbarPager.getLayoutParams();
        int desiredHeight = showTextInput ? mToolbarComputedHeightPx : 0;
        if (layoutParams.height != desiredHeight) {
            layoutParams.height = desiredHeight;
            mToolbarPager.setLayoutParams(layoutParams);
        }
        mToolbarPager.setVisibility(showTextInput ? View.VISIBLE : View.GONE);
        sessionPagerAdapter.applyToolbarPresentationToAll();
        dispatchCurrentExtraKeysViewChanged();
    }

    private float resolveToolbarDefaultHeightPx() {
        ViewGroup.LayoutParams layoutParams = mToolbarPager.getLayoutParams();
        return layoutParams == null || layoutParams.height <= 0 ? 0f : layoutParams.height;
    }

    private void updateSessionPagerCachePolicy() {
        int pageCount = sessionPagerAdapter.getCount();
        int desiredOffscreenLimit = Math.min(
            MAX_SESSION_PAGER_OFFSCREEN_LIMIT,
            Math.max(1, pageCount - 1)
        );
        if (mSessionPager.getOffscreenPageLimit() != desiredOffscreenLimit) {
            mSessionPager.setOffscreenPageLimit(desiredOffscreenLimit);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleVisiblePageReconciliation();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) {
            scheduleVisiblePageReconciliation();
        } else {
            cancelVisiblePageReconciliation();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) scheduleVisiblePageReconciliation();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelVisiblePageReconciliation();
        super.onDetachedFromWindow();
    }

    private final class SessionPagerAdapter extends PagerAdapter {
        private final ArrayList<TerminalSessionSurfaceItem> items = new ArrayList<>();
        private final LinkedHashMap<String, PageHolder> holdersByKey = new LinkedHashMap<>();
        private int terminalConfigGeneration = 1;
        private int extraKeysGeneration = 1;
        private int toolbarPresentationGeneration = 1;
        private boolean invalidateSyntheticPages;

        @NonNull
        TerminalSessionSurfaceItems.ChangeType submitItems(@NonNull List<TerminalSessionSurfaceItem> newItems) {
            TerminalSessionSurfaceItems.ChangeType changeType =
                TerminalSessionSurfaceItems.classifyChange(items, newItems);
            if (changeType == TerminalSessionSurfaceItems.ChangeType.NONE) return changeType;

            items.clear();
            items.addAll(newItems);
            pruneUnavailableHolders();
            rebindAttachedHolders();
            if (changeType == TerminalSessionSurfaceItems.ChangeType.STRUCTURE) {
                invalidateSyntheticPages = true;
                try {
                    notifyDataSetChanged();
                } finally {
                    invalidateSyntheticPages = false;
                }
            }
            return changeType;
        }

        int clampIndex(int index) {
            int count = getCount();
            if (count <= 0) return 0;
            return Math.max(0, Math.min(index, count - 1));
        }

        int getItemsCount() {
            return items.size();
        }

        @Nullable
        String getItemKeyAtPosition(int position) {
            TerminalSessionSurfaceItem item = getItemAtPosition(position);
            return item == null ? null : item.key;
        }

        boolean containsItemKey(@Nullable String key) {
            if (TextUtils.isEmpty(key)) return false;
            if (TextUtils.equals(CONFIG_PAGE_KEY, key)) {
                return mConfigPageEnabled;
            }
            if (TextUtils.equals(PLACEHOLDER_KEY, key)) {
                return items.isEmpty() && !mConfigPageEnabled;
            }
            for (TerminalSessionSurfaceItem item : items) {
                if (TextUtils.equals(item.key, key)) return true;
            }
            return false;
        }

        @Override
        public int getCount() {
            if (mConfigPageEnabled) {
                return Math.max(1, items.size() + 1);
            }
            return Math.max(1, items.size());
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @Override
        public int getItemPosition(@NonNull Object object) {
            PageHolder holder = object instanceof View
                ? (PageHolder) ((View) object).getTag()
                : null;
            if (holder == null) return POSITION_NONE;
            if (invalidateSyntheticPages && isSyntheticKey(holder.key)) return POSITION_NONE;
            int position = findPosition(holder.key);
            return position < 0 ? POSITION_NONE : position;
        }

        boolean isSyntheticKey(@Nullable String key) {
            return TextUtils.equals(PLACEHOLDER_KEY, key) || TextUtils.equals(CONFIG_PAGE_KEY, key);
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            TerminalSessionSurfaceItem item = getItemAtPosition(position);
            if (item == null) item = PLACEHOLDER_ITEM;

            PageHolder holder = holdersByKey.get(item.key);
            if (holder == null) {
                holder = createPageHolder();
                holdersByKey.put(item.key, holder);
            }

            bindHolder(holder, item);
            View parent = (View) holder.root.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(holder.root);
            }
            container.addView(holder.root);
            if (TerminalSessionSurfaceRenderPolicy.shouldRender(
                position, mSessionPager.getCurrentItem(), pagerStateMachine.getState())) {
                flushPendingHolderRefresh(holder);
            }
            return holder.root;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }

        @Nullable
        PageHolder findHolder(int position) {
            TerminalSessionSurfaceItem item = getItemAtPosition(position);
            if (item == null) return null;
            return holdersByKey.get(item.key);
        }

        @Nullable
        PageHolder findAttachedHolder(int position) {
            PageHolder holder = findHolder(position);
            return holder != null && holder.root.getParent() == mSessionPager ? holder : null;
        }

        @Nullable
        PageHolder findHolder(@NonNull TerminalSession session) {
            for (PageHolder holder : holdersByKey.values()) {
                if (holder.session == session) return holder;
            }
            return null;
        }

        int findPosition(@Nullable String key) {
            if (TextUtils.isEmpty(key)) return -1;
            if (PLACEHOLDER_KEY.equals(key)) return items.isEmpty() && !mConfigPageEnabled ? 0 : -1;
            if (CONFIG_PAGE_KEY.equals(key)) return mConfigPageEnabled ? items.size() : -1;
            for (int i = 0; i < items.size(); i++) {
                if (TextUtils.equals(items.get(i).key, key)) return i;
            }
            return -1;
        }

        @Nullable
        private TerminalSessionSurfaceItem getItemAtPosition(int position) {
            if (position < 0) return null;
            if (position < items.size()) return items.get(position);
            if (mConfigPageEnabled && position == items.size()) return CONFIG_PAGE_ITEM;
            if (items.isEmpty() && position == 0) return PLACEHOLDER_ITEM;
            return null;
        }

        void pruneUnavailableHolders() {
            ArrayList<String> unavailableKeys = new ArrayList<>();
            for (String key : holdersByKey.keySet()) {
                if (!containsItemKey(key)) unavailableKeys.add(key);
            }
            for (String key : unavailableKeys) {
                holdersByKey.remove(key);
            }
        }

        private void rebindAttachedHolders() {
            for (TerminalSessionSurfaceItem item : items) {
                PageHolder holder = holdersByKey.get(item.key);
                if (holder != null && holder.root.getParent() != null) {
                    bindHolder(holder, item);
                }
            }
            if (mConfigPageEnabled) rebindConfigPage();
        }

        void rebindConfigPage() {
            PageHolder holder = holdersByKey.get(CONFIG_PAGE_KEY);
            if (holder != null) bindHolder(holder, CONFIG_PAGE_ITEM);
        }

        void applyTerminalViewConfigToAll() {
            terminalConfigGeneration++;
            for (PageHolder holder : holdersByKey.values()) {
                if (holder.root.getParent() != null && !CONFIG_PAGE_KEY.equals(holder.key)) {
                    ensureTerminalViewConfig(holder);
                }
            }
        }

        void reloadExtraKeysViews() {
            extraKeysGeneration++;
            for (PageHolder holder : holdersByKey.values()) {
                if (holder.root.getParent() != null && !CONFIG_PAGE_KEY.equals(holder.key)) {
                    ensureExtraKeysConfig(holder);
                }
            }
            dispatchCurrentExtraKeysViewChanged();
        }

        void applyToolbarPresentationToAll() {
            toolbarPresentationGeneration++;
            for (PageHolder holder : holdersByKey.values()) {
                if (holder.root.getParent() != null) ensureToolbarPresentation(holder);
            }
        }

        @NonNull
        private PageHolder createPageHolder() {
            View root = LayoutInflater.from(getContext())
                .inflate(R.layout.item_terminal_session_page, mSessionPager, false);
            TerminalView terminalView = root.findViewById(R.id.terminal_session_page_terminal_view);
            ViewGroup configContainer = root.findViewById(R.id.terminal_session_page_config_container);
            SessionSwipeFrameLayout extraKeysContainer =
                root.findViewById(R.id.terminal_session_page_extra_keys_container);
            ViewPager bottomPanelPager = root.findViewById(R.id.terminal_session_page_bottom_panel_pager);
            ExtraKeysView extraKeysView = (ExtraKeysView) LayoutInflater.from(getContext())
                .inflate(R.layout.view_terminal_session_surface_extra_keys, bottomPanelPager, false);
            View placeholderPanel = createBottomPanelPlaceholderView();
            bottomPanelPager.setAdapter(new BottomPanelPagerAdapter(extraKeysView, placeholderPanel));
            bottomPanelPager.setOffscreenPageLimit(1);
            PageHolder holder = new PageHolder(
                root,
                terminalView,
                configContainer,
                extraKeysContainer,
                bottomPanelPager,
                extraKeysView
            );
            root.setTag(holder);
            return holder;
        }

        @NonNull
        private View createBottomPanelPlaceholderView() {
            TextView textView = new TextView(getContext());
            textView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            textView.setGravity(Gravity.CENTER);
            textView.setText("第二小面板占位");
            textView.setTextColor(0x99FFFFFF);
            textView.setTextSize(13f);
            textView.setBackgroundColor(0xFF000000);
            return textView;
        }

        private void bindHolder(@NonNull PageHolder holder, @NonNull TerminalSessionSurfaceItem item) {
            boolean sessionChanged = holder.session != item.session;
            holder.key = item.key;
            holder.session = item.session;
            if (sessionChanged) {
                holder.pendingScreenUpdate = false;
                holder.pendingInvalidate = false;
            }
            if (CONFIG_PAGE_KEY.equals(item.key)) {
                setVisibilityIfChanged(holder.terminalView, View.GONE);
                setVisibilityIfChanged(holder.extraKeysContainer, View.GONE);
                setVisibilityIfChanged(holder.configContainer, View.VISIBLE);
                bindConfigPageView(holder);
                return;
            }

            if (holder.configContainer.getChildCount() > 0) holder.configContainer.removeAllViews();
            setVisibilityIfChanged(holder.configContainer, View.GONE);
            setVisibilityIfChanged(holder.terminalView, View.VISIBLE);
            ensureTerminalViewConfig(holder);
            ensureExtraKeysConfig(holder);
            ensureToolbarPresentation(holder);
            if (item.session != null &&
                (sessionChanged || holder.terminalView.getCurrentSession() != item.session)) {
                holder.pendingScreenUpdate = true;
            }
        }

        private void bindConfigPageView(@NonNull PageHolder holder) {
            if (mConfigPageView == null) {
                if (holder.configContainer.getChildCount() > 0) holder.configContainer.removeAllViews();
                return;
            }
            if (mConfigPageView.getParent() == holder.configContainer) return;
            if (mConfigPageView.getParent() instanceof ViewGroup) {
                ((ViewGroup) mConfigPageView.getParent()).removeView(mConfigPageView);
            }
            holder.configContainer.removeAllViews();
            holder.configContainer.addView(
                mConfigPageView,
                new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            );
        }

        private void ensureTerminalViewConfig(@NonNull PageHolder holder) {
            if (holder.terminalConfigGeneration == terminalConfigGeneration) return;
            applyTerminalViewConfig(holder.terminalView);
            holder.terminalConfigGeneration = terminalConfigGeneration;
        }

        private void ensureExtraKeysConfig(@NonNull PageHolder holder) {
            if (holder.extraKeysGeneration == extraKeysGeneration) return;
            reloadExtraKeysView(holder.extraKeysView);
            holder.extraKeysGeneration = extraKeysGeneration;
        }

        private void ensureToolbarPresentation(@NonNull PageHolder holder) {
            if (holder.toolbarPresentationGeneration == toolbarPresentationGeneration) return;
            applyToolbarPresentation(holder);
            holder.toolbarPresentationGeneration = toolbarPresentationGeneration;
        }

        private void reloadExtraKeysView(@NonNull ExtraKeysView extraKeysView) {
            extraKeysView.setExtraKeysViewClient(mExtraKeysViewClient);
            extraKeysView.setButtonTextAllCaps(mToolbarButtonTextAllCaps);
            if (mExtraKeysInfo != null) {
                extraKeysView.reload(mExtraKeysInfo, mToolbarDefaultHeightPx);
            } else {
                extraKeysView.removeAllViews();
            }
        }

        private void applyToolbarPresentation(@NonNull PageHolder holder) {
            if (TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) {
                setVisibilityIfChanged(holder.extraKeysContainer, View.GONE);
                return;
            }
            boolean showIntegratedToolbar = shouldShowIntegratedToolbar();
            ViewGroup.LayoutParams layoutParams = holder.extraKeysContainer.getLayoutParams();
            int desiredHeight = showIntegratedToolbar ? mToolbarComputedHeightPx : 0;
            if (layoutParams.height != desiredHeight) {
                layoutParams.height = desiredHeight;
                holder.extraKeysContainer.setLayoutParams(layoutParams);
            }
            setVisibilityIfChanged(holder.extraKeysContainer, showIntegratedToolbar ? View.VISIBLE : View.GONE);
        }

        private void setVisibilityIfChanged(@NonNull View view, int visibility) {
            if (view.getVisibility() != visibility) view.setVisibility(visibility);
        }

        private void applyTerminalViewConfig(@NonNull TerminalView terminalView) {
            if (mTerminalViewClient != null) {
                terminalView.setTerminalViewClient(mTerminalViewClient);
            }
            if (mTerminalTextSize > 0) {
                terminalView.setTextSize(mTerminalTextSize);
            }
            if (mTerminalTypeface != null) {
                terminalView.setTypeface(mTerminalTypeface);
            }
            terminalView.setKeepScreenOn(mTerminalKeepScreenOn);
        }
    }

    private final class ToolbarPagerAdapter extends PagerAdapter {
        @Override
        public int getCount() {
            return toolbarStateMachine.isTextInputEnabled() ? 2 : 1;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @Override
        public int getItemPosition(@NonNull Object object) {
            return POSITION_NONE;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup collection, int position) {
            View layout;
            if (position == 0) {
                layout = new View(getContext());
                layout.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ));
            } else {
                layout = LayoutInflater.from(getContext())
                    .inflate(R.layout.view_terminal_session_surface_text_input, collection, false);
                EditText editText = layout.findViewById(R.id.terminal_surface_text_input);
                editText.setOnEditorActionListener((v, actionId, event) -> {
                    if (actionId != EditorInfo.IME_ACTION_SEND &&
                        (event == null || event.getKeyCode() != KeyEvent.KEYCODE_ENTER)) {
                        return false;
                    }
                    TerminalSession session = getCurrentSession();
                    if (session != null && session.isRunning()) {
                        String textToSend = editText.getText().toString();
                        if (textToSend.length() == 0) textToSend = "\r";
                        session.write(textToSend);
                    }
                    editText.setText("");
                    return true;
                });
            }
            collection.addView(layout);
            return layout;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup collection, int position, @NonNull Object object) {
            collection.removeView((View) object);
        }
    }

    private final class BottomPanelPagerAdapter extends PagerAdapter {
        @NonNull private final View extraKeysPanel;
        @NonNull private final View placeholderPanel;

        BottomPanelPagerAdapter(@NonNull View extraKeysPanel, @NonNull View placeholderPanel) {
            this.extraKeysPanel = extraKeysPanel;
            this.placeholderPanel = placeholderPanel;
        }

        @Override
        public int getCount() {
            return 2;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            View view = position == 0 ? extraKeysPanel : placeholderPanel;
            View parent = (View) view.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(view);
            }
            container.addView(view);
            return view;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }
    }

    private static final class PageHolder {
        @NonNull final View root;
        @NonNull final TerminalView terminalView;
        @NonNull final ViewGroup configContainer;
        @NonNull final SessionSwipeFrameLayout extraKeysContainer;
        @NonNull final ViewPager bottomPanelPager;
        @NonNull final ExtraKeysView extraKeysView;
        @Nullable TerminalSession session;
        @Nullable String key;
        int terminalConfigGeneration;
        int extraKeysGeneration;
        int toolbarPresentationGeneration;
        boolean pendingScreenUpdate;
        boolean pendingInvalidate;
        boolean terminalSizeUpdateInProgress;

        PageHolder(@NonNull View root,
                   @NonNull TerminalView terminalView,
                   @NonNull ViewGroup configContainer,
                   @NonNull SessionSwipeFrameLayout extraKeysContainer,
                   @NonNull ViewPager bottomPanelPager,
                   @NonNull ExtraKeysView extraKeysView) {
            this.root = root;
            this.terminalView = terminalView;
            this.configContainer = configContainer;
            this.extraKeysContainer = extraKeysContainer;
            this.bottomPanelPager = bottomPanelPager;
            this.extraKeysView = extraKeysView;
        }
    }
}
