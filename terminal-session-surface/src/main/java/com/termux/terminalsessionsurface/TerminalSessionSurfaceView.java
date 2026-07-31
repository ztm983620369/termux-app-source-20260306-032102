package com.termux.terminalsessionsurface;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalVulkanView;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TerminalSessionSurfaceView extends LinearLayout {
    private static final String LOG_TAG = "TerminalSessionSurface";
    private static final int MAX_SESSION_PAGER_OFFSCREEN_LIMIT = 1;
    private static final int MAX_VISIBLE_PAGE_RECONCILE_ATTEMPTS = 3;
    private static final int MAX_COMMITTED_TERMINAL_FRAME_RETRIES = 2;
    private static final int MAX_BACKGROUND_PREWARMS_PER_FRAME = 1;
    private static final long RENDER_WORK_LOG_INTERVAL_MS = 3000L;

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
    private final TerminalSessionTransitionFrameState transitionFrameState =
        new TerminalSessionTransitionFrameState();
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
    @Nullable private TerminalSessionBottomActionListener mBottomActionListener;
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
    @NonNull private SessionTransitionOrigin mSessionTransitionOrigin =
        SessionTransitionOrigin.UNKNOWN;
    private long mSessionTransitionSequence;
    private long mSessionTransitionStartedNanos;
    private int mSessionTransitionTargetChanges;
    private int mSessionTransitionPreparedFrames;
    private int mSessionTransitionZeroRedrawFrames;
    private int mSessionTransitionDeltaFrames;
    private int mSessionTransitionFullFrames;
    private long mActiveTerminalFrameGeneration;
    @Nullable private PageHolder mCommittedFrameLayoutWaitHolder;
    @Nullable private View.OnLayoutChangeListener mCommittedFrameLayoutWaitListener;
    private long mCommittedFrameLayoutWaitGeneration;
    private boolean mProgrammaticFocusAllowed = true;
    private boolean mFullScreenSessionSwipeEnabled;
    @Nullable private String mAuthoritativeSelectedPageKey;
    private boolean mVisiblePageReconcileScheduled;
    private boolean mVisiblePageReconcileDeferredUntilIdle;
    private int mVisiblePageReconcileAttemptsRemaining;
    private boolean mHostVisibilityAsserted;
    @Nullable private PageHolder mMotionActiveHolder;
    @NonNull
    private final Map<String, Long> mProgrammaticSelectionTokensByKey = new HashMap<>();
    @NonNull
    private final Runnable mVisiblePageReconcileRunnable = this::runVisiblePageReconciliation;
    private final TerminalSessionRenderWorkQueue<PageHolder> mForegroundRenderQueue =
        new TerminalSessionRenderWorkQueue<>();
    private final TerminalSessionRenderWorkQueue<PageHolder> mBackgroundPrewarmQueue =
        new TerminalSessionRenderWorkQueue<>();
    private final Runnable mTerminalRenderWorkRunnable = this::runTerminalRenderWorkFrame;
    private boolean mTerminalRenderWorkScheduled;
    private long mForegroundRenderRuns;
    private long mBackgroundPrewarmRuns;
    private long mForegroundRenderCoalesced;
    private long mBackgroundPrewarmCoalesced;
    private long mMaxBackgroundPrewarmWaitMicros;
    private long mMaxBackgroundPrewarmWorkMicros;
    private int mMaxBackgroundPrewarmQueueDepth;
    private long mLastRenderWorkLogMs;
    private int mImeBottomInsetPx;
    private int mImeChromeBoundaryInWindow;
    private boolean mImeAnimationRunning;
    private boolean mImeViewportGeometryLocked;
    private boolean mImeViewportApplyScheduled;
    private final int[] mImeChromeLocation = new int[2];
    private long mImeTranslationUpdates;
    private long mImeGeometryLockTransitions;
    private boolean mStructuralGeometryTransactionActive;
    private boolean mStructuralGeometryFrameScheduled;
    private int mStructuralObservedWidth = -1;
    private int mStructuralObservedHeight = -1;
    private int mStructuralStableFrames;
    private final Runnable mApplyImeViewportRunnable = () -> {
        mImeViewportApplyScheduled = false;
        applyImeViewportPresentationNow();
    };
    private final Runnable mStructuralGeometryFrameRunnable =
        this::runStructuralGeometryFrame;

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
        mToolbarPager.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                  oldLeft, oldTop, oldRight, oldBottom) -> {
            if (top != oldTop || bottom != oldBottom) scheduleImeViewportTranslation();
        });
        mToolbarDefaultHeightPx = resolveToolbarDefaultHeightPx();
        updateToolbarMetricsState();

        if (mSessionPager instanceof ProgrammaticViewPager) {
            ProgrammaticViewPager programmaticViewPager = (ProgrammaticViewPager) mSessionPager;
            programmaticViewPager.setSwipeRegionProvider(new ProgrammaticViewPager.SwipeRegionProvider() {
                @Override
                @Nullable
                public View getSwipeRegionView() {
                    int configPageIndex = getConfigPageIndex();
                    boolean configPageTargeted = configPageIndex >= 0 &&
                        (mSelectedSessionIndex == configPageIndex ||
                            mSessionPager.getCurrentItem() == configPageIndex);
                    int activeIndex = configPageTargeted ? configPageIndex :
                        mSessionPager.getCurrentItem();
                    PageHolder holder = sessionPagerAdapter.findHolder(activeIndex);
                    if (configPageTargeted) {
                        if (holder != null && holder.configContainer.isShown() &&
                            holder.configContainer.getWidth() > 0 &&
                            holder.configContainer.getHeight() > 0) {
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
                }

            });
            programmaticViewPager.setSwipeGestureListener(new ProgrammaticViewPager.SwipeGestureListener() {
                @Override
                public void onSwipeTouchDownInRegion() {
                    mSessionPageSwipeTouchActive = true;
                    int anchorPosition = sessionPagerAdapter.clampIndex(
                        mSessionPager.getCurrentItem());
                    if (mSessionPageChangeInProgress && transitionFrameState.isActive()) {
                        transitionFrameState.reanchor(anchorPosition);
                    } else {
                        beginTransitionFrameState(anchorPosition);
                    }
                    if (mCallbacks != null) {
                        mCallbacks.onSessionPageSwipeTouchDown();
                    }
                }

                @Override
                public void onSwipeGestureCaptured() {
                    mSessionTransitionOrigin = SessionTransitionOrigin.USER_SWIPE;
                    notifySessionPageChangeStarted();
                }

                @Override
                public void onSwipeTargetChanged(int pageDelta) {
                    int targetPosition = transitionFrameState.resolveGestureTarget(
                        pageDelta, sessionPagerAdapter.getCount());
                    prewarmSessionTransitionFrame(targetPosition);
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
                    if (mSessionPageSwipeTouchActive || mSessionPageChangeInProgress) {
                        ensureTransitionFrameStateStarted();
                        notifySessionPageChangeStarted();
                    }
                    pagerStateMachine.onDragStarted();
                } else if (state == ViewPager.SCROLL_STATE_SETTLING) {
                    if (mSessionPageSwipeTouchActive || mSessionPageChangeInProgress) {
                        ensureTransitionFrameStateStarted();
                        notifySessionPageChangeStarted();
                    }
                    pagerStateMachine.onSettlingStarted();
                    int currentPosition = sessionPagerAdapter.clampIndex(
                        mSessionPager.getCurrentItem());
                    if (currentPosition != transitionFrameState.getAnchorPosition()) {
                        prewarmSessionTransitionFrame(currentPosition);
                    }
                } else {
                    pagerStateMachine.onIdle();
                    mSelectedSessionIndex = sessionPagerAdapter.clampIndex(mSessionPager.getCurrentItem());
                    dispatchIdlePageSelection(mSelectedSessionIndex);
                    if (!mBackgroundPrewarmQueue.isEmpty()) scheduleTerminalRenderWorkFrame();
                    if (mVisiblePageReconcileDeferredUntilIdle) {
                        mVisiblePageReconcileDeferredUntilIdle = false;
                        scheduleVisiblePageReconciliation();
                    }
                }
            }

            @Override
            public void onPageSelected(int position) {
                mSelectedSessionIndex = sessionPagerAdapter.clampIndex(position);
                if (mSessionPageChangeInProgress &&
                    mSelectedSessionIndex != transitionFrameState.getAnchorPosition()) {
                    prewarmSessionTransitionFrame(mSelectedSessionIndex);
                }
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

    /** Receives actions from the fixed second page without participating in pager state. */
    public void setBottomActionListener(@Nullable TerminalSessionBottomActionListener listener) {
        mBottomActionListener = listener;
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

    /**
     * Keeps terminal geometry fixed for the entire IME interaction.
     *
     * <p>{@code imeBottomInsetPx} is strictly the real keyboard inset. The explicit window-space
     * boundary may account for visible app chrome parked above the IME. Every IME frame, including
     * its stable endpoint, uses presentation transforms only; real PTY geometry changes remain
     * reserved for explicit structural transactions.</p>
     */
    public void setImeViewportState(int imeBottomInsetPx, int chromeBoundaryInWindow,
                                    boolean animationRunning) {
        int boundedInset = Math.max(0, imeBottomInsetPx);
        boolean geometryLocked = TerminalImeViewportPolicy.shouldLockTerminalGeometry(
            boundedInset, animationRunning);
        boolean wasLocked = mImeViewportGeometryLocked;
        mImeBottomInsetPx = boundedInset;
        mImeChromeBoundaryInWindow = chromeBoundaryInWindow;
        mImeAnimationRunning = animationRunning;

        // On the opening edge, close every geometry ingress before moving any pixels.
        if (geometryLocked && !wasLocked) {
            mImeViewportGeometryLocked = true;
            mImeGeometryLockTransitions++;
            sessionPagerAdapter.applyImeGeometryLockToAll(true);
            requestImeCameraFrameForCurrentTerminal();
        }

        if (!applyImeViewportPresentationNow()) scheduleImeViewportTranslation();

        // On the closing edge, restore the visual viewport before allowing a real structural
        // geometry candidate to enter the two-frame commit coordinator.
        if (!geometryLocked && wasLocked) {
            mImeViewportGeometryLocked = false;
            mImeGeometryLockTransitions++;
            sessionPagerAdapter.applyImeGeometryLockToAll(false);
        }
    }

    /** Request one camera-safe terminal frame for a newly opened IME, never a grid resize. */
    private void requestImeCameraFrameForCurrentTerminal() {
        PageHolder holder = sessionPagerAdapter.findAttachedHolder(mSessionPager.getCurrentItem());
        if (holder == null || TextUtils.equals(holder.key, CONFIG_PAGE_KEY) ||
            !holder.terminalView.isAttachedToWindow()) {
            return;
        }
        holder.terminalView.requestImeCameraFrame();
    }

    /** Explicit configuration/freeform changes are real grid changes, unlike IME occlusion. */
    public void requestStructuralTerminalGeometryCommit() {
        mStructuralGeometryTransactionActive = true;
        mStructuralObservedWidth = -1;
        mStructuralObservedHeight = -1;
        mStructuralStableFrames = 0;
        dispatchStructuralGeometryCandidate();
        scheduleStructuralGeometryFrame();
    }

    private void dispatchStructuralGeometryCandidate() {
        for (PageHolder holder : sessionPagerAdapter.holdersByKey.values()) {
            if (!TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) {
                holder.terminalView.requestStructuralGeometryCommit();
            }
        }
    }

    private void scheduleStructuralGeometryFrame() {
        if (!mStructuralGeometryTransactionActive || mStructuralGeometryFrameScheduled ||
            !isAttachedToWindow()) return;
        mStructuralGeometryFrameScheduled = true;
        postOnAnimation(mStructuralGeometryFrameRunnable);
    }

    private void runStructuralGeometryFrame() {
        mStructuralGeometryFrameScheduled = false;
        if (!mStructuralGeometryTransactionActive || !isAttachedToWindow() ||
            getWidth() <= 0 || getHeight() <= 0) return;
        int width = getWidth();
        int height = getHeight();
        if (width == mStructuralObservedWidth && height == mStructuralObservedHeight) {
            mStructuralStableFrames++;
        } else {
            mStructuralObservedWidth = width;
            mStructuralObservedHeight = height;
            mStructuralStableFrames = 1;
            dispatchStructuralGeometryCandidate();
        }
        if (mStructuralStableFrames >= 2) {
            dispatchStructuralGeometryCandidate();
            mStructuralGeometryTransactionActive = false;
            return;
        }
        scheduleStructuralGeometryFrame();
    }

    public String getImeViewportDiagnostics() {
        PageHolder currentHolder = sessionPagerAdapter.findAttachedHolder(
            mSessionPager.getCurrentItem());
        float terminalTranslation = currentHolder == null
            ? 0f : currentHolder.terminalView.getTranslationY();
        float vulkanTranslation = currentHolder == null || currentHolder.vulkanView == null
            ? 0f : currentHolder.vulkanView.getTranslationY();
        String cameraDiagnostics = currentHolder == null
            ? "none" : currentHolder.imeFocusCamera.getDiagnostics();
        return "inset=" + mImeBottomInsetPx + " animation=" + mImeAnimationRunning +
            " locked=" + mImeViewportGeometryLocked + " boundary=" +
            mImeChromeBoundaryInWindow + " surfaceTranslation=" + getTranslationY() +
            " toolbarTranslation=" + mToolbarPager.getTranslationY() +
            " extraKeysTranslation=" + getCurrentExtraKeysTranslationForDiagnostics() +
            " terminalTranslation=" + terminalTranslation +
            " vulkanTranslation=" + vulkanTranslation +
            " translationUpdates=" + mImeTranslationUpdates +
            " lockTransitions=" + mImeGeometryLockTransitions +
            " camera={" + cameraDiagnostics + '}';
    }

    /** Window-space focus target used by the current page's IME camera, or {@code -1}. */
    public int getImeFocusTargetBottomInWindowForDiagnostics() {
        PageHolder holder = sessionPagerAdapter.findAttachedHolder(
            mSessionPager.getCurrentItem());
        return holder == null ? -1 : holder.imeFocusCamera.getFocusTargetBottomInWindow();
    }

    /** True when Canvas and Vulkan terminal-pixel layers share one camera transform. */
    public boolean isImeTerminalPixelTransformSynchronizedForDiagnostics() {
        PageHolder holder = sessionPagerAdapter.findAttachedHolder(
            mSessionPager.getCurrentItem());
        return holder == null || holder.vulkanView == null ||
            Math.abs(holder.terminalView.getTranslationY() -
                holder.vulkanView.getTranslationY()) <= 0.01f;
    }

    private void scheduleImeViewportTranslation() {
        if (mImeViewportApplyScheduled || !isAttachedToWindow()) return;
        mImeViewportApplyScheduled = true;
        postOnAnimation(mApplyImeViewportRunnable);
    }

    private boolean applyImeViewportPresentationNow() {
        // The common parent is never translated: doing so clips the entire pager and can erase a
        // sparse shell. Bottom chrome is anchored first. The terminal grid remains fixed while
        // the IME is visible; its per-page camera owns presentation for the whole interaction.
        setImeTranslation(this, 0f);

        boolean applied = applyImeBottomChromeTranslation(mToolbarPager);
        for (PageHolder holder : sessionPagerAdapter.holdersByKey.values()) {
            if (!applyImeBottomChromeTranslation(holder.extraKeysContainer)) applied = false;
        }
        for (PageHolder holder : sessionPagerAdapter.holdersByKey.values()) {
            if (!applyImeTerminalCursorPan(holder)) applied = false;
        }
        return applied;
    }

    private boolean applyImeBottomChromeTranslation(@NonNull View chrome) {
        if (chrome.getVisibility() != VISIBLE) {
            setImeTranslation(chrome, 0f);
            return true;
        }
        if (mImeBottomInsetPx <= 0) {
            setImeTranslation(chrome, 0f);
            return true;
        }
        // Offscreen pager holders are not part of the current composition. Their own layout
        // listener reapplies the stored IME boundary when they become attached. Preserve their
        // per-page transform in the meantime so a tab's first retained frame cannot flash behind
        // an already-visible keyboard.
        if (!chrome.isAttachedToWindow()) {
            return true;
        }
        if (mImeChromeBoundaryInWindow <= 0 || chrome.getHeight() <= 0) {
            return false;
        }
        chrome.getLocationInWindow(mImeChromeLocation);
        float untransformedBottom = mImeChromeLocation[1] - chrome.getTranslationY() +
            chrome.getHeight();
        int targetTranslation = TerminalImeViewportPolicy.computeAnchoredChromeTranslation(
            untransformedBottom, mImeChromeBoundaryInWindow, true);
        setImeTranslation(chrome, targetTranslation);
        return true;
    }

    private boolean applyImeTerminalCursorPan(@NonNull PageHolder holder) {
        TerminalView terminalView = holder.terminalView;
        if (mImeBottomInsetPx <= 0) {
            holder.imeFocusCamera.resetForBinding();
            setTerminalPixelTranslation(holder, 0f);
            clearTerminalPixelClip(holder);
            return true;
        }
        if (TextUtils.equals(holder.key, CONFIG_PAGE_KEY) ||
            terminalView.getVisibility() != VISIBLE) {
            holder.imeFocusCamera.resetForBinding();
            setTerminalPixelTranslation(holder, 0f);
            clearTerminalPixelClip(holder);
            return true;
        }
        // A detached page remains an independent retained terminal. Preserve its last committed
        // camera transform while the IME is active; attachment/layout callbacks reconcile it before
        // that page becomes authoritative again.
        if (!terminalView.isAttachedToWindow()) return true;
        int terminalBoundary = resolveTerminalContentBoundaryInWindow(holder);
        if (terminalBoundary <= 0 || terminalView.getHeight() <= 0) return false;

        terminalView.getLocationInWindow(mImeChromeLocation);
        float currentTranslation = terminalView.getTranslationY();
        int untransformedTerminalTop = Math.round(
            mImeChromeLocation[1] - currentTranslation);

        TerminalView.ImeCameraSnapshot snapshot = terminalView.getImeCameraSnapshot();
        TerminalImeFocusCamera.Availability availability;
        switch (snapshot.availability) {
            case READY:
                availability = TerminalImeFocusCamera.Availability.READY;
                break;
            case HISTORY_OWNED:
                availability = TerminalImeFocusCamera.Availability.HISTORY_OWNED;
                break;
            case FRAME_PENDING:
            default:
                availability = TerminalImeFocusCamera.Availability.FRAME_PENDING;
                break;
        }
        TerminalImeFocusCamera.Decision decision = holder.imeFocusCamera.update(
            new TerminalImeFocusCamera.Request(
                true,
                mImeAnimationRunning,
                availability,
                untransformedTerminalTop,
                terminalView.getHeight(),
                terminalBoundary,
                snapshot.cursorTopPx,
                snapshot.cursorBottomPx,
                snapshot.protectedBottomPx,
                currentTranslation));
        setTerminalPixelTranslation(holder, decision.translationY);
        applyTerminalPixelClip(holder, terminalBoundary, untransformedTerminalTop,
            decision.translationY);
        logImeCameraCommitIfChanged(holder, terminalBoundary, untransformedTerminalTop,
            snapshot, currentTranslation, decision);
        return true;
    }

    /**
     * Emits one compact record for each distinct committed IME-camera state.
     *
     * <p>This deliberately runs after both terminal pixel layers have received the transform.
     * It is diagnostic-only: no layout, terminal model, renderer or camera state is mutated.
     * Keeping the signature per page makes scroll/frame transitions observable without logging
     * every insets-animation frame forever.</p>
     */
    private void logImeCameraCommitIfChanged(@NonNull PageHolder holder,
                                             int terminalBoundary,
                                             int untransformedTerminalTop,
                                             @NonNull TerminalView.ImeCameraSnapshot snapshot,
                                             float requestedFromTranslation,
                                             @NonNull TerminalImeFocusCamera.Decision decision) {
        PageHolder currentHolder = sessionPagerAdapter.findAttachedHolder(
            mSessionPager.getCurrentItem());
        if (holder != currentHolder) return;

        View extraKeys = holder.extraKeysContainer;
        int extraTop = -1;
        int extraBottom = -1;
        if (extraKeys.isAttachedToWindow()) {
            extraKeys.getLocationInWindow(mImeChromeLocation);
            extraTop = mImeChromeLocation[1];
            extraBottom = extraTop + extraKeys.getHeight();
        }
        terminalViewLocation(holder.terminalView, mImeChromeLocation);
        int presentedTerminalTop = mImeChromeLocation[1];
        int presentedTerminalBottom = presentedTerminalTop + holder.terminalView.getHeight();
        float committedCanvasTranslation = holder.terminalView.getTranslationY();
        float committedVulkanTranslation = holder.vulkanView == null
            ? committedCanvasTranslation : holder.vulkanView.getTranslationY();

        String signature = mImeBottomInsetPx + "|" + mImeAnimationRunning + "|" +
            terminalBoundary + "|" + untransformedTerminalTop + "|" +
            holder.terminalView.getHeight() + "|" + snapshot.availability + "|" +
            snapshot.cursorTopPx + "|" + snapshot.cursorBottomPx + "|" +
            snapshot.protectedBottomPx + "|" + decision.translationY + "|" +
            decision.focusTargetBottomInWindow + "|" + decision.phase + "|" +
            decision.cause + "|" + extraTop + "|" + extraBottom + "|" +
            Math.round(committedCanvasTranslation) + "|" +
            Math.round(committedVulkanTranslation);
        if (signature.equals(holder.lastImeCameraLogSignature)) return;
        holder.lastImeCameraLogSignature = signature;

        Log.i(LOG_TAG, "ime-camera-v3 key=" + holder.key +
            " inset=" + mImeBottomInsetPx +
            " anim=" + mImeAnimationRunning +
            " lock=" + mImeViewportGeometryLocked +
            " rootBoundary=" + mImeChromeBoundaryInWindow +
            " contentBoundary=" + terminalBoundary +
            " terminal=" + presentedTerminalTop + ".." + presentedTerminalBottom +
            " naturalTop=" + untransformedTerminalTop +
            " height=" + holder.terminalView.getHeight() +
            " extra=" + extraTop + ".." + extraBottom +
            " extraTranslation=" + Math.round(extraKeys.getTranslationY()) +
            " toolbarTranslation=" + Math.round(mToolbarPager.getTranslationY()) +
            " snapshot=" + snapshot.availability +
            " cursor=" + snapshot.cursorTopPx + ".." + snapshot.cursorBottomPx +
            " protected=" + snapshot.protectedBottomPx +
            " revision=" + snapshot.presentedRevision + "/" + snapshot.contentRevision +
            " requestFrom=" + Math.round(requestedFromTranslation) +
            " decision=" + decision.translationY +
            " target=" + decision.focusTargetBottomInWindow +
            " phase=" + decision.phase +
            " cause=" + decision.cause +
            " committed=" + Math.round(committedCanvasTranslation) + "/" +
            Math.round(committedVulkanTranslation));
    }

    private static void terminalViewLocation(@NonNull View view, @NonNull int[] location) {
        view.getLocationInWindow(location);
    }

    private int resolveTerminalContentBoundaryInWindow(@NonNull PageHolder holder) {
        View extraKeys = holder.extraKeysContainer;
        if (extraKeys.getVisibility() == VISIBLE && extraKeys.isAttachedToWindow() &&
            extraKeys.getHeight() > 0) {
            extraKeys.getLocationInWindow(mImeChromeLocation);
            return mImeChromeLocation[1];
        }
        if (mToolbarPager.getVisibility() == VISIBLE && mToolbarPager.isAttachedToWindow() &&
            mToolbarPager.getHeight() > 0) {
            mToolbarPager.getLocationInWindow(mImeChromeLocation);
            return mImeChromeLocation[1];
        }
        return mImeChromeBoundaryInWindow;
    }

    private void setTerminalPixelTranslation(@NonNull PageHolder holder,
                                             float targetTranslation) {
        setImeTranslation(holder.terminalView, targetTranslation);
        if (holder.vulkanView != null) {
            setImeTranslation(holder.vulkanView, targetTranslation);
        }
    }

    /**
     * Clips both render backends at the same window-space terminal/chrome boundary.
     *
     * <p>Translation changes where a finite terminal pixel layer is composed; it does not change
     * that layer's local bounds. Without this clip, its remaining bottom rows can still be sampled
     * underneath the transparent extra-keys hierarchy. The clip is presentation-only and never
     * enters View measurement or PTY geometry.</p>
     */
    private void applyTerminalPixelClip(@NonNull PageHolder holder,
                                        int boundaryInWindow,
                                        int untransformedTerminalTop,
                                        int translationY) {
        int width = holder.terminalView.getWidth();
        int height = holder.terminalView.getHeight();
        if (width <= 0 || height <= 0 || boundaryInWindow <= 0) return;
        int presentedTop = untransformedTerminalTop + translationY;
        int clipBottom = Math.max(0, Math.min(height, boundaryInWindow - presentedTop));
        if (clipBottom >= height) {
            clearTerminalPixelClip(holder);
            return;
        }
        if (holder.terminalPixelClipActive && holder.terminalPixelClipWidth == width &&
            holder.terminalPixelClipBottom == clipBottom) {
            return;
        }
        holder.terminalPixelClipBounds.set(0, 0, width, clipBottom);
        holder.terminalView.setClipBounds(holder.terminalPixelClipBounds);
        if (holder.vulkanView != null) {
            holder.vulkanView.setClipBounds(holder.terminalPixelClipBounds);
        }
        holder.terminalPixelClipActive = true;
        holder.terminalPixelClipWidth = width;
        holder.terminalPixelClipBottom = clipBottom;
    }

    private void clearTerminalPixelClip(@NonNull PageHolder holder) {
        if (!holder.terminalPixelClipActive && holder.terminalView.getClipBounds() == null &&
            (holder.vulkanView == null || holder.vulkanView.getClipBounds() == null)) {
            return;
        }
        holder.terminalView.setClipBounds(null);
        if (holder.vulkanView != null) holder.vulkanView.setClipBounds(null);
        holder.terminalPixelClipActive = false;
        holder.terminalPixelClipWidth = -1;
        holder.terminalPixelClipBottom = -1;
    }

    private void setImeTranslation(@NonNull View view, float targetTranslation) {
        if (Math.abs(view.getTranslationY() - targetTranslation) <= 0.01f) return;
        view.setTranslationY(targetTranslation);
        mImeTranslationUpdates++;
    }

    private float getCurrentExtraKeysTranslationForDiagnostics() {
        PageHolder holder = sessionPagerAdapter.findAttachedHolder(mSessionPager.getCurrentItem());
        return holder == null ? 0f : holder.extraKeysContainer.getTranslationY();
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
        String previousAuthoritativeKey = mAuthoritativeSelectedPageKey;
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
            Log.i(LOG_TAG, "session-refresh-v1 current=" + currentItem +
                " target=" + safeIndex + " currentKey=" + previousCurrentKey +
                " targetKey=" + targetKey + " token=" + requestToken +
                " pager=" + pagerStateMachine.getState() +
                " selection=" + previousAuthoritativeKey);
            rememberProgrammaticSelectionForPosition(safeIndex, requestToken);
            boolean transitionAnimate = shouldAnimateSessionTransition(safeIndex, animate);
            startProgrammaticSessionTransition(safeIndex);
            mSuppressSessionPageCallback = true;
            mSessionPager.setCurrentItem(safeIndex, transitionAnimate);
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
        boolean transitionAnimate = shouldAnimateSessionTransition(safeIndex, animate);
        startProgrammaticSessionTransition(safeIndex);
        mSuppressSessionPageCallback = true;
        mSessionPager.setCurrentItem(safeIndex, transitionAnimate);
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
        boolean transitionAnimate = shouldAnimateSessionTransition(configIndex, animate);
        startProgrammaticSessionTransition(configIndex);
        mSuppressSessionPageCallback = true;
        mSessionPager.setCurrentItem(configIndex, transitionAnimate);
        mSuppressSessionPageCallback = false;
        if (pagerStateMachine.getState() == TerminalSessionSurfacePagerStateMachine.State.IDLE) {
            dispatchActivePageChanged(configIndex, false);
        }
        scheduleVisiblePageReconciliation();
    }

    public void refreshSession(@NonNull TerminalSession session) {
        PageHolder holder = sessionPagerAdapter.findHolder(session);
        if (holder == null) return;
        holder.pendingScreenUpdate = true;
        if (shouldRenderHolder(holder) && holder.root.getParent() == mSessionPager) {
            scheduleTerminalHolderRefresh(holder);
        } else {
            scheduleTerminalHolderBackgroundPrewarm(holder);
        }
    }

    public void invalidateSession(@NonNull TerminalSession session) {
        PageHolder holder = sessionPagerAdapter.findHolder(session);
        if (holder == null) return;
        holder.pendingInvalidate = true;
        if (shouldRenderHolder(holder) && holder.root.getParent() == mSessionPager) {
            scheduleTerminalHolderRefresh(holder);
        } else {
            scheduleTerminalHolderBackgroundPrewarm(holder);
        }
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
        mHostVisibilityAsserted = true;
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

    private void ensureTransitionFrameStateStarted() {
        if (transitionFrameState.isActive()) return;
        beginTransitionFrameState(
            sessionPagerAdapter.clampIndex(mSessionPager.getCurrentItem()));
    }

    private void startProgrammaticSessionTransition(int targetPosition) {
        mSessionTransitionOrigin = SessionTransitionOrigin.PROGRAMMATIC;
        int anchorPosition = sessionPagerAdapter.clampIndex(mSessionPager.getCurrentItem());
        if (mSessionPageChangeInProgress && transitionFrameState.isActive()) {
            transitionFrameState.reanchor(anchorPosition);
        } else {
            beginTransitionFrameState(anchorPosition);
        }
        notifySessionPageChangeStarted();
        prewarmSessionTransitionFrame(targetPosition);
    }

    private void beginTransitionFrameState(int anchorPosition) {
        transitionFrameState.begin(anchorPosition);
        mSessionTransitionStartedNanos = 0L;
        mSessionTransitionTargetChanges = 0;
        mSessionTransitionPreparedFrames = 0;
        mSessionTransitionZeroRedrawFrames = 0;
        mSessionTransitionDeltaFrames = 0;
        mSessionTransitionFullFrames = 0;
    }

    private boolean shouldAnimateSessionTransition(int targetPosition, boolean requested) {
        return TerminalSessionSurfaceRenderPolicy.shouldAnimateProgrammaticTransition(
            sessionPagerAdapter.clampIndex(mSessionPager.getCurrentItem()),
            targetPosition,
            requested);
    }

    private void notifySessionPageChangeStarted() {
        if (mSessionPageChangeInProgress) return;

        mSessionPageChangeInProgress = true;
        mSessionTransitionSequence++;
        mSessionTransitionStartedNanos = System.nanoTime();
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
        boolean hadActiveTransition = mSessionPageChangeInProgress || mSessionPageSwipeTouchActive;
        if (mSessionPageChangeInProgress) {
            long elapsedMicros = mSessionTransitionStartedNanos == 0L ? 0L :
                Math.max(0L, (System.nanoTime() - mSessionTransitionStartedNanos) / 1000L);
            Log.i(LOG_TAG, "page-transition-v2 sequence=" + mSessionTransitionSequence +
                " origin=" + mSessionTransitionOrigin +
                " anchor=" + transitionFrameState.getAnchorPosition() +
                " target=" + sessionPagerAdapter.findPosition(transitionFrameState.getTargetKey()) +
                " prepared=" + mSessionTransitionPreparedFrames +
                " zeroRedraw=" + mSessionTransitionZeroRedrawFrames +
                " dirtyDelta=" + mSessionTransitionDeltaFrames +
                " full=" + mSessionTransitionFullFrames +
                " targetChanges=" + mSessionTransitionTargetChanges +
                " pages=" + sessionPagerAdapter.getCount() +
                " offscreenLimit=" + mSessionPager.getOffscreenPageLimit() +
                " elapsedUs=" + elapsedMicros);
        }
        transitionFrameState.finish();
        if (!hadActiveTransition) return;

        mSessionPageChangeInProgress = false;
        mSessionPageSwipeTouchActive = false;
        mSessionTransitionOrigin = SessionTransitionOrigin.UNKNOWN;
        if (mCallbacks != null) {
            mCallbacks.onSessionPageChangeFinished();
        }
    }

    private void abortSessionPageTransition(String reason) {
        if (!mSessionPageChangeInProgress && !mSessionPageSwipeTouchActive) return;
        Log.i(LOG_TAG, "page-transition-v2 abort=" + reason + " state=" +
            pagerStateMachine.getState());
        pagerStateMachine.onIdle();
        dispatchSessionPageChangeFinished();
    }

    private void dispatchPreviewPageSelected(int position) {
        PageHolder holder = sessionPagerAdapter.findHolder(position);
        if (holder == null) return;
        flushPendingHolderRefresh(holder);

        if (mCallbacks == null) return;

        if (TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) {
            mCallbacks.onConfigPagePreviewSelected();
            return;
        }

        mCallbacks.onSessionPagePreviewSelected(position, holder.session);
    }

    private void dispatchActivePageChanged(int position, boolean fromUser) {
        String expectedKey = sessionPagerAdapter.getItemKeyAtPosition(position);
        if (!isCurrentPage(position, expectedKey)) {
            Log.i(LOG_TAG, "session-commit-v1 ignored=stale expected=" + expectedKey +
                " current=" + sessionPagerAdapter.getItemKeyAtPosition(
                    sessionPagerAdapter.clampIndex(mSessionPager.getCurrentItem())));
            dispatchSessionPageChangeFinished();
            return;
        }
        PageHolder holder = sessionPagerAdapter.findAttachedHolder(position);
        if (holder == null && sessionPagerAdapter.getCount() > 0) {
            scheduleVisiblePageReconciliation();
            mSessionPager.postOnAnimation(() -> {
                if (!isCurrentPage(position, expectedKey)) {
                    Log.i(LOG_TAG, "session-commit-v1 ignored=stale-deferred expected=" +
                        expectedKey + " current=" + sessionPagerAdapter.getItemKeyAtPosition(
                            sessionPagerAdapter.clampIndex(mSessionPager.getCurrentItem())));
                    dispatchSessionPageChangeFinished();
                    return;
                }
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

        if (TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) {
            setActiveVulkanHolder(null);
            prewarmTerminalHolder(holder);
            if (mCallbacks != null && !mSuppressSessionPageCallback) {
                mCallbacks.onConfigPageSelected(fromUser, consumeProgrammaticSelectionToken(holder.key, fromUser));
            }
            dispatchCurrentExtraKeysViewChanged();
            if (!fromUser) selectionOriginStateMachine.completeProgrammaticSelection(holder.key);
            dispatchSessionPageChangeFinished();
            return;
        }

        activateCommittedTerminalHolder(holder);
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

    private boolean isCurrentPage(int position, @Nullable String expectedKey) {
        int currentPosition = sessionPagerAdapter.clampIndex(mSessionPager.getCurrentItem());
        return currentPosition == position && TextUtils.equals(expectedKey,
            sessionPagerAdapter.getItemKeyAtPosition(currentPosition));
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

    private enum SessionTransitionOrigin {
        UNKNOWN,
        USER_SWIPE,
        PROGRAMMATIC
    }

    private boolean shouldRenderHolder(@NonNull PageHolder holder) {
        int holderPosition = sessionPagerAdapter.findPosition(holder.key);
        int transitionTargetPosition = sessionPagerAdapter.findPosition(
            transitionFrameState.getTargetKey());
        return TerminalSessionSurfaceRenderPolicy.shouldRender(
            holderPosition,
            mSessionPager.getCurrentItem(),
            pagerStateMachine.getState(),
            transitionTargetPosition
        );
    }

    /** Prepare only the page that can enter pixels for the current transition direction. */
    private void prewarmSessionTransitionFrame(int targetPosition) {
        if (targetPosition < 0 || targetPosition >= sessionPagerAdapter.getCount()) {
            if (transitionFrameState.selectTarget(null)) mSessionTransitionTargetChanges++;
            return;
        }
        String targetKey = sessionPagerAdapter.getItemKeyAtPosition(targetPosition);
        if (transitionFrameState.selectTarget(targetKey)) mSessionTransitionTargetChanges++;
        PageHolder holder = sessionPagerAdapter.findAttachedHolder(targetPosition);
        if (holder != null) prepareTransitionHolder(holder);
    }

    private void prepareTransitionHolder(@NonNull PageHolder holder) {
        boolean firstPreparation = transitionFrameState.markPrepared(holder.key);
        if (!transitionFrameState.isTarget(holder.key)) return;
        if (holder.session == null || TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) return;

        boolean completeFrame = holder.terminalView.getCurrentSession() == holder.session &&
            holder.terminalView.hasCompleteRenderFrame();
        boolean dirtyFrame = holder.pendingScreenUpdate || holder.pendingInvalidate;
        if (!TerminalSessionTransitionFrameState.shouldPrepareTarget(
            firstPreparation, completeFrame, dirtyFrame)) {
            return;
        }
        cancelBackgroundPrewarm(holder);
        mSessionTransitionPreparedFrames++;
        if (!completeFrame) {
            mSessionTransitionFullFrames++;
        } else if (dirtyFrame) {
            mSessionTransitionDeltaFrames++;
        } else {
            mSessionTransitionZeroRedrawFrames++;
        }
        prewarmTerminalHolder(holder);
    }

    private void flushPendingHolderRefresh(@NonNull PageHolder holder) {
        prewarmTerminalHolder(holder);
    }

    private boolean prewarmTerminalHolder(@NonNull PageHolder holder) {
        return prewarmTerminalHolder(holder, true);
    }

    private boolean prewarmTerminalHolder(@NonNull PageHolder holder,
                                          boolean invalidatePresentation) {
        boolean needsPrewarm = holder.pendingScreenUpdate || holder.pendingInvalidate ||
            holder.terminalView.getCurrentSession() != holder.session ||
            !holder.terminalView.hasCompleteRenderFrame();
        boolean forceFullPrewarm = holder.pendingInvalidate ||
            holder.terminalView.getCurrentSession() != holder.session ||
            !holder.terminalView.hasCompleteRenderFrame();
        boolean ready = refreshTerminalHolder(holder, false, true);
        if (!ready) return false;
        forceFullPrewarm = forceFullPrewarm || !holder.terminalView.hasCompleteRenderFrame();
        needsPrewarm = needsPrewarm || forceFullPrewarm;
        if (needsPrewarm) {
            if (forceFullPrewarm) {
                holder.terminalView.prewarmRenderFrame();
            } else {
                holder.terminalView.prewarmRenderDelta();
            }
        }
        if (invalidatePresentation) invalidateTerminalHierarchy(holder);
        return true;
    }

    /**
     * Keep an existing, non-visible terminal's Ghostty retained state current without scheduling
     * a ViewPager composition. PTY I/O and parsing already continue independently; this closes
     * the remaining presentation-state gap so returning to a tab is a compositing operation.
     */
    private boolean prewarmTerminalHolderInBackground(@NonNull PageHolder holder) {
        if (holder.session == null || TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) return true;
        boolean forceFullFrame = holder.pendingInvalidate ||
            holder.terminalView.getCurrentSession() != holder.session ||
            !holder.terminalView.hasCompleteRenderFrame();
        boolean ready = refreshTerminalHolder(holder, false, false, false);
        if (!ready) return false;
        forceFullFrame = forceFullFrame || !holder.terminalView.hasCompleteRenderFrame();
        boolean prewarmed = forceFullFrame
            ? holder.terminalView.prewarmRenderFrame()
            : holder.terminalView.prewarmRenderDelta();
        if (!prewarmed) {
            // Keep the regular renderer's work pending; a non-Ghostty compatibility renderer
            // cannot build its Canvas commands outside a visible draw traversal.
            holder.pendingScreenUpdate = true;
        }
        return true;
    }

    /**
     * A prewarmed ViewPager child can be fully clipped while its invalidation is consumed. Once it
     * becomes the committed page, rebuild its retained frame and invalidate every compositor level
     * that can retain the previously blank display list. The follow-up checks run from the pager,
     * rather than the child, so they survive an offscreen child invalidation being dropped.
     */
    private boolean activateCommittedTerminalHolder(@NonNull PageHolder holder) {
        cancelCommittedTerminalFrameLayoutWait();
        setActiveVulkanHolder(holder);
        boolean needsFullPrewarm = holder.pendingScreenUpdate || holder.pendingInvalidate ||
            !holder.terminalView.hasCompleteRenderFrame();
        boolean ready = needsFullPrewarm
            ? prewarmTerminalHolder(holder)
            : refreshTerminalHolder(holder, false, true);
        if (ready && !holder.terminalView.hasCompleteRenderFrame()) {
            holder.terminalView.prewarmRenderFrame();
        }
        final long generation = ++mActiveTerminalFrameGeneration;
        final String key = holder.key;
        invalidateTerminalHierarchy(holder);
        logCommittedTerminalFrame("activate", generation, holder, ready,
            holder.terminalView.hasCompleteRenderFrame());
        scheduleCommittedTerminalFrameCheck(holder, key, generation,
            MAX_COMMITTED_TERMINAL_FRAME_RETRIES);
        return ready;
    }

    private boolean isCommittedTerminalHolder(@NonNull PageHolder holder, @NonNull String key) {
        if (mSessionPager == null || !TextUtils.equals(holder.key, key) ||
            holder.root.getParent() != mSessionPager) {
            return false;
        }
        return sessionPagerAdapter.findAttachedHolder(mSessionPager.getCurrentItem()) == holder;
    }

    private void scheduleCommittedTerminalFrameCheck(@NonNull PageHolder holder, @NonNull String key,
                                                     long generation, int retriesRemaining) {
        mSessionPager.postOnAnimation(() -> {
            if (generation != mActiveTerminalFrameGeneration ||
                !isCommittedTerminalHolder(holder, key)) {
                return;
            }
            if (!hasUsableTerminalLayout(holder)) {
                waitForCommittedTerminalLayout(holder, key, generation, retriesRemaining);
                return;
            }

            boolean completeBefore = holder.terminalView.hasCompleteRenderFrame();
            boolean ready = completeBefore
                ? refreshTerminalHolder(holder, false, false)
                : prewarmTerminalHolder(holder);
            invalidateTerminalHierarchy(holder);
            boolean completeAfter = holder.terminalView.hasCompleteRenderFrame();
            logCommittedTerminalFrame("check", generation, holder, ready, completeAfter);

            if (!completeAfter && retriesRemaining > 0) {
                scheduleCommittedTerminalFrameCheck(holder, key, generation,
                    retriesRemaining - 1);
            } else if (!completeAfter) {
                Log.w(LOG_TAG, "committed-frame-v3 exhausted generation=" + generation +
                    " key=" + key + " current=" + mSessionPager.getCurrentItem());
            }
        });
    }

    private static boolean hasUsableTerminalLayout(@NonNull PageHolder holder) {
        return holder.terminalView.getWidth() > 0 && holder.terminalView.getHeight() > 0;
    }

    /**
     * A newly attached ViewPager child can remain 0x0 for several frames. Geometry is a hard
     * prerequisite for a complete terminal frame, so wait for the actual layout event instead of
     * consuming a timing-based retry budget and committing a known-incomplete page.
     */
    private void waitForCommittedTerminalLayout(@NonNull PageHolder holder, @NonNull String key,
                                                 long generation, int retriesRemaining) {
        if (generation != mActiveTerminalFrameGeneration ||
            !isCommittedTerminalHolder(holder, key)) {
            return;
        }
        if (hasUsableTerminalLayout(holder)) {
            scheduleCommittedTerminalFrameCheck(holder, key, generation, retriesRemaining);
            return;
        }
        if (mCommittedFrameLayoutWaitHolder == holder &&
            mCommittedFrameLayoutWaitGeneration == generation &&
            mCommittedFrameLayoutWaitListener != null) {
            return;
        }

        cancelCommittedTerminalFrameLayoutWait();
        View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (right <= left || bottom <= top) return;
                if (mCommittedFrameLayoutWaitListener != this) {
                    view.removeOnLayoutChangeListener(this);
                    return;
                }
                cancelCommittedTerminalFrameLayoutWait();
                if (generation != mActiveTerminalFrameGeneration ||
                    !isCommittedTerminalHolder(holder, key)) {
                    return;
                }
                Log.i(LOG_TAG, "committed-frame-v3 phase=layout-ready generation=" +
                    generation + " key=" + key + " size=" + (right - left) + 'x' +
                    (bottom - top) + " retries=" + retriesRemaining);
                scheduleCommittedTerminalFrameCheck(holder, key, generation, retriesRemaining);
            }
        };
        mCommittedFrameLayoutWaitHolder = holder;
        mCommittedFrameLayoutWaitGeneration = generation;
        mCommittedFrameLayoutWaitListener = listener;
        holder.terminalView.addOnLayoutChangeListener(listener);
        Log.i(LOG_TAG, "committed-frame-v3 phase=waiting-layout generation=" + generation +
            " key=" + key + " terminal=" + holder.terminalView.getWidth() + 'x' +
            holder.terminalView.getHeight() + " root=" + holder.root.getWidth() + 'x' +
            holder.root.getHeight() + " retries=" + retriesRemaining);

        holder.root.requestLayout();
        mSessionPager.requestLayout();
        requestLayout();
        if (hasUsableTerminalLayout(holder)) {
            cancelCommittedTerminalFrameLayoutWait();
            scheduleCommittedTerminalFrameCheck(holder, key, generation, retriesRemaining);
        }
    }

    private void cancelCommittedTerminalFrameLayoutWait() {
        PageHolder holder = mCommittedFrameLayoutWaitHolder;
        View.OnLayoutChangeListener listener = mCommittedFrameLayoutWaitListener;
        mCommittedFrameLayoutWaitHolder = null;
        mCommittedFrameLayoutWaitListener = null;
        mCommittedFrameLayoutWaitGeneration = 0L;
        if (holder != null && listener != null) {
            holder.terminalView.removeOnLayoutChangeListener(listener);
        }
    }

    /** Invalidate the child display list and each retained parent after an offscreen prewarm. */
    private void invalidateTerminalHierarchy(@NonNull PageHolder holder) {
        holder.terminalView.postInvalidateOnAnimation();
        holder.root.postInvalidateOnAnimation();
        mSessionPager.postInvalidateOnAnimation();
        postInvalidateOnAnimation();
    }

    private void logCommittedTerminalFrame(@NonNull String phase, long generation,
                                           @NonNull PageHolder holder, boolean ready,
                                           boolean complete) {
        Log.i(LOG_TAG, "committed-frame-v3 phase=" + phase + " generation=" + generation +
            " key=" + holder.key + " ready=" + ready + " complete=" + complete +
            " current=" + mSessionPager.getCurrentItem() + " pager=" +
            pagerStateMachine.getState() + " viewport={" +
            holder.terminalView.getRenderDiagnostics() + '}');
    }

    private boolean refreshTerminalHolder(@NonNull PageHolder holder, boolean forceFrame) {
        return refreshTerminalHolder(holder, forceFrame, false);
    }

    private boolean refreshTerminalHolder(@NonNull PageHolder holder, boolean forceFrame,
                                          boolean requireCompleteFrame) {
        return refreshTerminalHolder(holder, forceFrame, requireCompleteFrame, true);
    }

    private boolean refreshTerminalHolder(@NonNull PageHolder holder, boolean forceFrame,
                                          boolean requireCompleteFrame,
                                          boolean publishPresentation) {
        if (holder.session == null || TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) return true;
        if (holder.terminalView.mRenderer == null) {
            holder.pendingScreenUpdate = holder.pendingScreenUpdate || forceFrame ||
                requireCompleteFrame;
            return false;
        }
        if (holder.terminalSizeUpdateInProgress) {
            holder.pendingScreenUpdate = holder.pendingScreenUpdate || forceFrame ||
                requireCompleteFrame;
            return false;
        }
        holder.terminalSizeUpdateInProgress = true;
        try {
            holder.terminalView.setImeViewportGeometryLocked(mImeViewportGeometryLocked);
            if (holder.terminalView.getCurrentSession() != holder.session) {
                holder.terminalView.attachSession(holder.session);
            }
            holder.terminalView.updateSize();
        } finally {
            holder.terminalSizeUpdateInProgress = false;
        }
        boolean fullFrameRequired = requireCompleteFrame &&
            !holder.terminalView.hasCompleteRenderFrame();
        boolean pendingInvalidate = holder.pendingInvalidate;
        if (publishPresentation && (fullFrameRequired || pendingInvalidate)) {
            holder.terminalView.requestFullRenderFrame();
        }
        if (forceFrame || holder.pendingScreenUpdate || fullFrameRequired) {
            holder.pendingScreenUpdate = false;
            holder.pendingInvalidate = false;
            if (publishPresentation) {
                holder.terminalView.onScreenUpdated();
                // The camera may observe FRAME_PENDING here. It deliberately holds the previous
                // transform until TerminalView reports that Canvas/Vulkan committed this revision.
                if (mImeViewportGeometryLocked) scheduleImeViewportTranslation();
            }
        } else if (pendingInvalidate) {
            holder.pendingInvalidate = false;
        }
        return holder.terminalView.mEmulator != null;
    }

    private void scheduleTerminalHolderRefresh(@NonNull PageHolder holder) {
        cancelBackgroundPrewarm(holder);
        if (holder.screenRefreshScheduled) {
            mForegroundRenderCoalesced++;
            return;
        }
        holder.screenRefreshScheduled = true;
        mForegroundRenderQueue.offer(holder);
        scheduleTerminalRenderWorkFrame();
    }

    private void scheduleTerminalHolderBackgroundPrewarm(@NonNull PageHolder holder) {
        if (holder.screenRefreshScheduled) {
            mBackgroundPrewarmCoalesced++;
            return;
        }
        if (holder.backgroundPrewarmScheduled) {
            mBackgroundPrewarmCoalesced++;
            return;
        }
        holder.backgroundPrewarmScheduled = true;
        holder.backgroundPrewarmEnqueuedNanos = System.nanoTime();
        mBackgroundPrewarmQueue.offer(holder);
        mMaxBackgroundPrewarmQueueDepth = Math.max(mMaxBackgroundPrewarmQueueDepth,
            mBackgroundPrewarmQueue.size());
        scheduleTerminalRenderWorkFrame();
    }

    private void scheduleTerminalRenderWorkFrame() {
        if (mTerminalRenderWorkScheduled) return;
        mTerminalRenderWorkScheduled = true;
        postOnAnimation(mTerminalRenderWorkRunnable);
    }

    private void runTerminalRenderWorkFrame() {
        mTerminalRenderWorkScheduled = false;

        PageHolder holder;
        while ((holder = mForegroundRenderQueue.poll()) != null) {
            holder.screenRefreshScheduled = false;
            if (holder.session == null || holder.root.getParent() != mSessionPager ||
                !shouldRenderHolder(holder)) continue;
            mForegroundRenderRuns++;
            refreshTerminalHolder(holder, false);
        }

        int backgroundRuns = 0;
        if (pagerStateMachine.getState() == TerminalSessionSurfacePagerStateMachine.State.IDLE) {
            while (backgroundRuns < MAX_BACKGROUND_PREWARMS_PER_FRAME &&
                (holder = mBackgroundPrewarmQueue.poll()) != null) {
                holder.backgroundPrewarmScheduled = false;
                long waitMicros = Math.max(0L,
                    (System.nanoTime() - holder.backgroundPrewarmEnqueuedNanos) / 1000L);
                if (waitMicros > mMaxBackgroundPrewarmWaitMicros) {
                    mMaxBackgroundPrewarmWaitMicros = waitMicros;
                }
                if (holder.session == null) continue;
                if (holder.root.getParent() == mSessionPager && shouldRenderHolder(holder)) {
                    scheduleTerminalHolderRefresh(holder);
                    continue;
                }
                long startedNanos = System.nanoTime();
                prewarmTerminalHolderInBackground(holder);
                long workMicros = Math.max(0L,
                    (System.nanoTime() - startedNanos) / 1000L);
                if (workMicros > mMaxBackgroundPrewarmWorkMicros) {
                    mMaxBackgroundPrewarmWorkMicros = workMicros;
                }
                mBackgroundPrewarmRuns++;
                backgroundRuns++;
            }
        }

        maybeLogTerminalRenderWork();
        if (!mForegroundRenderQueue.isEmpty() ||
            (pagerStateMachine.getState() == TerminalSessionSurfacePagerStateMachine.State.IDLE &&
                !mBackgroundPrewarmQueue.isEmpty())) {
            scheduleTerminalRenderWorkFrame();
        }
    }

    private void cancelBackgroundPrewarm(@NonNull PageHolder holder) {
        if (!holder.backgroundPrewarmScheduled) return;
        mBackgroundPrewarmQueue.remove(holder);
        holder.backgroundPrewarmScheduled = false;
    }

    private void maybeLogTerminalRenderWork() {
        long now = SystemClock.uptimeMillis();
        if (now - mLastRenderWorkLogMs < RENDER_WORK_LOG_INTERVAL_MS) return;
        mLastRenderWorkLogMs = now;
        Log.i(LOG_TAG, "render-work-v4 foreground=" + mForegroundRenderRuns +
            " background=" + mBackgroundPrewarmRuns + " foregroundCoalesced=" +
            mForegroundRenderCoalesced + " backgroundCoalesced=" +
            mBackgroundPrewarmCoalesced + " foregroundQueue=" +
            mForegroundRenderQueue.size() + " backgroundQueue=" +
            mBackgroundPrewarmQueue.size() + " maxBackgroundQueue=" +
            mMaxBackgroundPrewarmQueueDepth + " maxBackgroundWaitUs=" +
            mMaxBackgroundPrewarmWaitMicros + " maxBackgroundWorkUs=" +
            mMaxBackgroundPrewarmWorkMicros + " backgroundPerFrame=" +
            MAX_BACKGROUND_PREWARMS_PER_FRAME);
    }

    private void clearTerminalRenderWork() {
        removeCallbacks(mTerminalRenderWorkRunnable);
        mTerminalRenderWorkScheduled = false;
        PageHolder holder;
        while ((holder = mForegroundRenderQueue.poll()) != null) {
            holder.screenRefreshScheduled = false;
        }
        while ((holder = mBackgroundPrewarmQueue.poll()) != null) {
            holder.backgroundPrewarmScheduled = false;
        }
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
            setActiveVulkanHolder(null);
            sessionPagerAdapter.rebindConfigPage();
            dispatchCurrentExtraKeysViewChanged();
            return true;
        }

        boolean ready = activateCommittedTerminalHolder(holder);
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
        scheduleImeViewportTranslation();
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

    /** Hidden pager pages retain exact Canvas commands but do not retain native swapchains. */
    private void setActiveVulkanHolder(@Nullable PageHolder requestedHolder) {
        boolean hostVisible = mHostVisibilityAsserted ||
            (isAttachedToWindow() && isShown() && getWindowVisibility() == View.VISIBLE);
        PageHolder activeHolder = hostVisible && requestedHolder != null &&
            !TextUtils.equals(requestedHolder.key, CONFIG_PAGE_KEY)
            ? requestedHolder
            : null;
        if (mMotionActiveHolder != activeHolder) {
            if (mMotionActiveHolder != null) {
                mMotionActiveHolder.terminalView.cancelViewportMotionForTabTransition();
            }
            mMotionActiveHolder = activeHolder;
        }
        for (PageHolder holder : sessionPagerAdapter.holdersByKey.values()) {
            if (holder.vulkanView != null) {
                boolean active = holder == activeHolder;
                holder.vulkanView.setRenderActive(active);
                int visibility = active && holder.vulkanView.isSupported()
                    ? View.VISIBLE
                    : View.INVISIBLE;
                if (holder.vulkanView.getVisibility() != visibility) {
                    holder.vulkanView.setVisibility(visibility);
                }
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        sessionPagerAdapter.applyImeGeometryLockToAll(mImeViewportGeometryLocked);
        scheduleImeViewportTranslation();
        scheduleStructuralGeometryFrame();
        scheduleVisiblePageReconciliation();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (mSessionPager == null) return;
        if (visibility == View.VISIBLE && isShown()) {
            scheduleVisiblePageReconciliation();
        } else {
            abortSessionPageTransition("view-hidden");
            mHostVisibilityAsserted = false;
            setActiveVulkanHolder(null);
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) {
            scheduleVisiblePageReconciliation();
        } else {
            abortSessionPageTransition("window-hidden");
            mHostVisibilityAsserted = false;
            cancelVisiblePageReconciliation();
            setActiveVulkanHolder(null);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            scheduleImeViewportTranslation();
            if (mStructuralGeometryTransactionActive || !mImeViewportGeometryLocked ||
                w != oldw) {
                dispatchStructuralGeometryCandidate();
            }
            scheduleStructuralGeometryFrame();
            scheduleVisiblePageReconciliation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        abortSessionPageTransition("detach");
        cancelVisiblePageReconciliation();
        cancelCommittedTerminalFrameLayoutWait();
        mActiveTerminalFrameGeneration++;
        mHostVisibilityAsserted = false;
        clearTerminalRenderWork();
        removeCallbacks(mApplyImeViewportRunnable);
        mImeViewportApplyScheduled = false;
        removeCallbacks(mStructuralGeometryFrameRunnable);
        mStructuralGeometryFrameScheduled = false;
        setActiveVulkanHolder(null);
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
            if (!TextUtils.equals(holder.key, CONFIG_PAGE_KEY) && holder.session != null) {
                if (transitionFrameState.isTarget(holder.key)) {
                    prepareTransitionHolder(holder);
                } else if (shouldRenderHolder(holder)) {
                    prewarmTerminalHolder(holder);
                } else {
                    holder.pendingScreenUpdate = true;
                }
            } else if (TerminalSessionSurfaceRenderPolicy.shouldRender(
                position, mSessionPager.getCurrentItem(), pagerStateMachine.getState(),
                sessionPagerAdapter.findPosition(transitionFrameState.getTargetKey()))) {
                flushPendingHolderRefresh(holder);
            }
            return holder.root;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            PageHolder holder = object instanceof View ? (PageHolder) ((View) object).getTag() : null;
            if (holder != null && holder == mCommittedFrameLayoutWaitHolder) {
                cancelCommittedTerminalFrameLayoutWait();
            }
            if (holder != null && holder.vulkanView != null) {
                holder.vulkanView.setRenderActive(false);
            }
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
                PageHolder holder = holdersByKey.remove(key);
                if (holder != null) {
                    if (holder == mCommittedFrameLayoutWaitHolder) {
                        cancelCommittedTerminalFrameLayoutWait();
                    }
                    cancelBackgroundPrewarm(holder);
                    mForegroundRenderQueue.remove(holder);
                    holder.screenRefreshScheduled = false;
                    holder.terminalView.releaseRenderResources();
                    if (holder.vulkanView != null) holder.vulkanView.releaseRenderResources();
                }
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

        void applyImeGeometryLockToAll(boolean locked) {
            for (PageHolder holder : holdersByKey.values()) {
                holder.terminalView.setImeViewportGeometryLocked(locked);
            }
        }

        @NonNull
        private PageHolder createPageHolder() {
            View root = LayoutInflater.from(getContext())
                .inflate(R.layout.item_terminal_session_page, mSessionPager, false);
            TerminalView terminalView = root.findViewById(R.id.terminal_session_page_terminal_view);
            TerminalVulkanView vulkanView = root.findViewById(R.id.terminal_session_page_vulkan_view);
            terminalView.setVulkanView(vulkanView);
            terminalView.setImeViewportGeometryLocked(mImeViewportGeometryLocked);
            if (vulkanView != null) vulkanView.setRenderActive(false);
            ViewGroup configContainer = root.findViewById(R.id.terminal_session_page_config_container);
            SessionSwipeFrameLayout extraKeysContainer =
                root.findViewById(R.id.terminal_session_page_extra_keys_container);
            ViewPager bottomPanelPager =
                root.findViewById(R.id.terminal_session_page_bottom_panel_pager);
            ExtraKeysView extraKeysView = (ExtraKeysView) LayoutInflater.from(getContext())
                .inflate(R.layout.view_terminal_session_surface_extra_keys, bottomPanelPager, false);
            TmuxControlsBottomUiProvider tmuxControlsProvider = new TmuxControlsBottomUiProvider();
            View tmuxControlsView = tmuxControlsProvider.createView(
                LayoutInflater.from(getContext()), bottomPanelPager);
            BottomPanelPagerAdapter bottomPanelAdapter = new BottomPanelPagerAdapter(
                extraKeysView, tmuxControlsView);
            bottomPanelPager.setAdapter(bottomPanelAdapter);
            bottomPanelPager.setOffscreenPageLimit(1);
            PageHolder holder = new PageHolder(
                root,
                terminalView,
                vulkanView,
                configContainer,
                extraKeysContainer,
                bottomPanelPager,
                extraKeysView,
                tmuxControlsProvider,
                tmuxControlsView
            );
            terminalView.setVisualViewportAnchorChangedListener(() -> {
                if (mImeViewportGeometryLocked) scheduleImeViewportTranslation();
            });
            terminalView.setImeCameraFrameReadyListener(() -> {
                if (mImeViewportGeometryLocked) scheduleImeViewportTranslation();
            });
            terminalView.setImeExplicitFocusListener(() -> {
                holder.imeFocusCamera.requestExplicitLiveFocus();
                if (mImeViewportGeometryLocked) scheduleImeViewportTranslation();
            });
            extraKeysContainer.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                           oldLeft, oldTop, oldRight, oldBottom) -> {
                if (top != oldTop || bottom != oldBottom) scheduleImeViewportTranslation();
            });
            terminalView.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                     oldLeft, oldTop, oldRight, oldBottom) -> {
                if (top != oldTop || bottom != oldBottom) scheduleImeViewportTranslation();
            });
            root.setTag(holder);
            return holder;
        }

        private void bindHolder(@NonNull PageHolder holder, @NonNull TerminalSessionSurfaceItem item) {
        boolean sessionChanged = holder.session != item.session;
            if (sessionChanged) {
                cancelBackgroundPrewarm(holder);
                mForegroundRenderQueue.remove(holder);
                holder.screenRefreshScheduled = false;
                holder.imeFocusCamera.resetForBinding();
                setTerminalPixelTranslation(holder, 0f);
        }
        holder.key = item.key;
            holder.session = item.session;
            if (sessionChanged) {
                holder.pendingScreenUpdate = false;
                holder.pendingInvalidate = false;
            }
            if (CONFIG_PAGE_KEY.equals(item.key)) {
                setVisibilityIfChanged(holder.terminalView, View.GONE);
                if (holder.vulkanView != null) {
                    holder.vulkanView.setRenderActive(false);
                    setVisibilityIfChanged(holder.vulkanView, View.GONE);
                }
                setVisibilityIfChanged(holder.extraKeysContainer, View.GONE);
                setVisibilityIfChanged(holder.configContainer, View.VISIBLE);
                bindConfigPageView(holder);
                return;
            }

            if (holder.configContainer.getChildCount() > 0) holder.configContainer.removeAllViews();
            setVisibilityIfChanged(holder.configContainer, View.GONE);
            setVisibilityIfChanged(holder.terminalView, View.VISIBLE);
            if (holder.vulkanView != null) {
                setVisibilityIfChanged(holder.vulkanView,
                    holder.vulkanView.isRenderActive() && holder.vulkanView.isSupported()
                        ? View.VISIBLE
                        : View.INVISIBLE);
            }
            ensureTerminalViewConfig(holder);
            ensureExtraKeysConfig(holder);
            bindBottomPanel(holder);
            ensureToolbarPresentation(holder);
            if (item.session != null &&
                (sessionChanged || holder.terminalView.getCurrentSession() != item.session)) {
                holder.pendingScreenUpdate = true;
            }
            scheduleImeViewportTranslation();
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

        private void bindBottomPanel(@NonNull PageHolder holder) {
            TerminalSessionBottomUiProvider.Binding binding =
                new TerminalSessionBottomUiProvider.Binding(
                    holder.session,
                    mExtraKeysInfo,
                    mExtraKeysViewClient,
                    mBottomActionListener,
                    mToolbarButtonTextAllCaps,
                    mToolbarDefaultHeightPx
            );
            holder.tmuxControlsProvider.bind(holder.tmuxControlsView, binding);
        }

        private void applyToolbarPresentation(@NonNull PageHolder holder) {
            if (TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) {
                setVisibilityIfChanged(holder.extraKeysContainer, View.GONE);
                return;
            }
            boolean showIntegratedToolbar = shouldShowIntegratedToolbar();
            ViewGroup.LayoutParams layoutParams = holder.extraKeysContainer.getLayoutParams();
            int desiredHeight = showIntegratedToolbar ? mToolbarComputedHeightPx : 0;
            // Bottom pages are content inside the existing toolbar slot. Their type and selected
            // page must never alter terminal rows, IME geometry, or parent layout dimensions.
            if (layoutParams.height != desiredHeight) {
                layoutParams.height = desiredHeight;
                holder.extraKeysContainer.setLayoutParams(layoutParams);
            }
            setVisibilityIfChanged(holder.extraKeysContainer, showIntegratedToolbar ? View.VISIBLE : View.GONE);
            scheduleImeViewportTranslation();
        }

        private void setVisibilityIfChanged(@NonNull View view, int visibility) {
            if (view.getVisibility() != visibility) view.setVisibility(visibility);
        }

        private void applyTerminalViewConfig(@NonNull TerminalView terminalView) {
            terminalView.setImeViewportGeometryLocked(mImeViewportGeometryLocked);
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
        @NonNull private final View tmuxControlsPanel;

        BottomPanelPagerAdapter(@NonNull View extraKeysPanel,
                                @NonNull View tmuxControlsPanel) {
            this.extraKeysPanel = extraKeysPanel;
            this.tmuxControlsPanel = tmuxControlsPanel;
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
            View view = position == 0 ? extraKeysPanel : tmuxControlsPanel;
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

    private final class PageHolder {
        @NonNull final View root;
        @NonNull final TerminalView terminalView;
        @Nullable final TerminalVulkanView vulkanView;
        @NonNull final ViewGroup configContainer;
        @NonNull final SessionSwipeFrameLayout extraKeysContainer;
        @NonNull final ViewPager bottomPanelPager;
        @NonNull final ExtraKeysView extraKeysView;
        @NonNull final TmuxControlsBottomUiProvider tmuxControlsProvider;
        @NonNull final View tmuxControlsView;
        @NonNull final TerminalImeFocusCamera imeFocusCamera = new TerminalImeFocusCamera();
        @Nullable TerminalSession session;
        @Nullable String key;
        int terminalConfigGeneration;
        int extraKeysGeneration;
        int toolbarPresentationGeneration;
        boolean pendingScreenUpdate;
        boolean pendingInvalidate;
        boolean terminalSizeUpdateInProgress;
        boolean screenRefreshScheduled;
        boolean backgroundPrewarmScheduled;
        long backgroundPrewarmEnqueuedNanos;
        @Nullable String lastImeCameraLogSignature;
        @NonNull final Rect terminalPixelClipBounds = new Rect();
        boolean terminalPixelClipActive;
        int terminalPixelClipWidth = -1;
        int terminalPixelClipBottom = -1;
        PageHolder(@NonNull View root,
                   @NonNull TerminalView terminalView,
                   @Nullable TerminalVulkanView vulkanView,
                   @NonNull ViewGroup configContainer,
                   @NonNull SessionSwipeFrameLayout extraKeysContainer,
                   @NonNull ViewPager bottomPanelPager,
                   @NonNull ExtraKeysView extraKeysView,
                   @NonNull TmuxControlsBottomUiProvider tmuxControlsProvider,
                   @NonNull View tmuxControlsView) {
            this.root = root;
            this.terminalView = terminalView;
            this.vulkanView = vulkanView;
            this.configContainer = configContainer;
            this.extraKeysContainer = extraKeysContainer;
            this.bottomPanelPager = bottomPanelPager;
            this.extraKeysView = extraKeysView;
            this.tmuxControlsProvider = tmuxControlsProvider;
            this.tmuxControlsView = tmuxControlsView;
        }
    }
}
