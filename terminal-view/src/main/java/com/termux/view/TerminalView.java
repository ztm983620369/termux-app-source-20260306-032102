package com.termux.view;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ActionMode;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityManager;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Scroller;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.TextStyle;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.textselection.TextSelectionCursorController;

/** View displaying and interacting with a {@link TerminalSession}. */
public final class TerminalView extends View {

    /** Log terminal view key and IME events. */
    private static boolean TERMINAL_VIEW_KEY_LOGGING_ENABLED = false;

    /** The currently displayed terminal session, whose emulator is {@link #mEmulator}. */
    public TerminalSession mTermSession;
    /** Our terminal emulator whose session is {@link #mTermSession}. */
    public TerminalEmulator mEmulator;

    public TerminalRenderer mRenderer;

    public TerminalViewClient mClient;

    private TextSelectionCursorController mTextSelectionCursorController;
    private TextSelectionPreviewPopup mTextSelectionPreviewPopup;
    @Nullable
    private TextSelectionMagnifier mTextSelectionMagnifier;

    private Handler mTerminalCursorBlinkerHandler;
    private TerminalCursorBlinkerRunnable mTerminalCursorBlinkerRunnable;
    private int mTerminalCursorBlinkerRate;
    private boolean mCursorInvisibleIgnoreOnce;
    public static final int TERMINAL_CURSOR_BLINK_RATE_MIN = 100;
    public static final int TERMINAL_CURSOR_BLINK_RATE_MAX = 2000;

    /** The top row of text to display. Ranges from -activeTranscriptRows to 0. */
    int mTopRow;
    /** Sub-row viewport position in [0, lineHeight), used by the retained Ghostty renderer. */
    float mViewportPixelOffset;
    int[] mDefaultSelectors = new int[]{-1,-1,-1,-1};

    float mScaleFactor = 1.f;
    final GestureAndScaleRecognizer mGestureRecognizer;
    private boolean mScaleGestureActive;
    private boolean mScaleGestureQualified;
    private boolean mScaleCommitPending;
    private boolean mScaleFrameScheduled;
    private int mScaleStartTextSize;
    private int mScaleTargetTextSize;
    private float mScaleGestureFactor = 1f;
    /** Screen-space pivot captured once per pinch; detector focus drift must never pan history. */
    private float mScaleFocusX;
    private float mScaleFocusY;
    private float mScaleReportedFocusMaxDrift;
    private int mScaleSampleCount;
    private boolean mMultiTouchSequenceCaptured;
    private long mScaleGestureStartedNanos;
    private long mScaleGestureStartedEventTimeMillis;
    private int mScaleReflowCount;
    private long mScaleMetricNanos;
    private long mScaleResizeNanos;
    private long mScaleAnchorNanos;
    private long mScaleMetricMaxNanos;
    private long mScaleResizeMaxNanos;
    private long mScaleAnchorMaxNanos;
    private int mScaleAnchorExactCount;
    private int mScaleAnchorClampedCount;
    private int mScaleAnchorUnavailableCount;
    private int mScaleGestureStartTopRow;
    private int mScaleGestureStartTranscriptRows;
    private boolean mScaleGestureStartedAlternateScreen;
    private boolean mScaleGesturePinnedToLiveEdge;
    private int mScaleLiveEdgePinCount;
    private static final int SCALE_GLYPH_WARM_RUNS_PER_FRAME = 4;
    private static final int IDLE_GLYPH_WARM_STABLE_FRAME_COUNT = 2;
    private boolean mScaleGlyphWarmupPending;
    private boolean mScaleGlyphWarmFrameScheduled;
    private int mScaleGlyphWarmCandidates;
    private int mScaleGlyphWarmFrames;
    private long mScaleGlyphWarmNanos;
    private boolean mIdleGlyphWarmCheckScheduled;
    private boolean mGlyphWarmupFromIdle;
    private int mIdleGlyphWarmStableFrames;
    private long mIdleGlyphWarmCandidateGeneration = Long.MIN_VALUE;
    private long mIdleGlyphWarmActiveGeneration = Long.MIN_VALUE;
    private long mIdleGlyphWarmCompletedGeneration = Long.MIN_VALUE;

    /** Keep track of where mouse touch event started which we report as mouse scroll. */
    private int mMouseScrollStartX = -1, mMouseScrollStartY = -1;
    /** Keep track of the time when a touch event leading to sending mouse scroll events started. */
    private long mMouseStartDownTime = -1;

    final Scroller mScroller;

    /** What was left in from scrolling movement. */
    float mScrollRemainder;
    private final TerminalFingerScrollTracker mFingerScrollTracker =
        new TerminalFingerScrollTracker();
    private final TerminalViewportPosition.Result mResolvedViewportPosition =
        new TerminalViewportPosition.Result();
    private final float mFingerScrollCaptureSlop;
    private long mFingerScrollStartedNanos;
    private int mFingerScrollMoveCount;

    /** If non-zero, this is the last unicode code point received if that was a combining character. */
    int mCombiningAccent;
    private boolean mFrameInvalidationScheduled;
    private boolean mAccessibilityContentDescriptionDirty;
    private boolean mPendingFullInvalidation;
    private int mPendingInvalidateTop = Integer.MAX_VALUE;
    private int mPendingInvalidateBottom = Integer.MIN_VALUE;
    private int mLastRenderedCursorRow = -1;
    private int mLastRenderedCursorCol = -1;
    private int mLastRenderedCursorStyle = -1;
    private boolean mLastRenderedCursorVisible;
    private int mLastSentCellWidth = -1;
    private int mLastSentCellHeight = -1;
    /** The only authority allowed to turn observed layout into a PTY grid resize. */
    private final TerminalGeometryCommitPolicy mGeometryCommitPolicy =
        new TerminalGeometryCommitPolicy();
    private boolean mImeViewportGeometryLocked;
    private boolean mGeometryCommitFrameScheduled;
    private long mGeometryCommitFrameEpoch;
    private long mGeometryDeferredCount;
    private long mGeometryCommittedCount;
    private static final long FRAME_METRICS_LOG_INTERVAL_MS = 3000L;
    private long mRenderFrameRequests;
    private long mRenderFrameCallbacks;
    private long mRenderFrameCoalesced;
    private long mImmediateGhosttyInvalidations;
    private long mImmediateViewportInvalidations;
    private long mUserInputLiveEdgeRestores;
    private long mPartialGhosttyInvalidations;
    private long mPresentedFrames;
    private long mSkippedFramePresentations;
    private long mFrameScheduledNanos;
    private long mMaxScheduleLatencyMicros;
    private long mMaxDrawMicros;
    private long mLastPresentedContentRevision = Long.MIN_VALUE;
    private int mLastPresentedTopRow = Integer.MIN_VALUE;
    private int mLastPresentedViewportOffsetBits = Integer.MIN_VALUE;
    private int mLastPresentedViewHeight = -1;
    private int mLastPresentedFontLineSpacing = -1;
    private int mLastPresentedFontLineSpacingAndAscent = Integer.MIN_VALUE;
    private boolean mLastPresentedCursorEnabled;
    private int mLastPresentedImeProtectedBottomScreenRow = -1;
    private long mLastFrameMetricsLogMs;

    /** Optional per-tab Vulkan surface. Canvas remains the correctness fallback until its frame is committed. */
    @Nullable
    private TerminalVulkanView mVulkanView;
    @Nullable
    private Runnable mVisualViewportAnchorChangedListener;
    @Nullable
    private Runnable mImeCameraFrameReadyListener;
    @Nullable
    private Runnable mImeExplicitFocusListener;
    private final TerminalImeCameraFrameRequestPolicy mImeCameraFrameRequestPolicy =
        new TerminalImeCameraFrameRequestPolicy();
    private long mImeCameraFrameRequests;
    private long mImeCameraFrameDeltaPrewarms;
    private long mImeCameraFrameFullFallbacks;
    private long mImeCameraFrameImmediateNotifications;
    private long mLastImeCameraNotifiedRevision = Long.MIN_VALUE;
    private int mLastImeCameraNotifiedCursorRow = Integer.MIN_VALUE;
    private int mLastImeCameraNotifiedCursorColumn = Integer.MIN_VALUE;
    private int mLastImeCameraNotifiedTopRow = Integer.MIN_VALUE;
    private int mLastImeCameraNotifiedViewportOffsetBits = Integer.MIN_VALUE;
    private int mLastImeCameraNotifiedViewHeight = -1;
    private int mLastImeCameraNotifiedFontLineSpacing = -1;
    private int mLastImeCameraNotifiedFontLineSpacingAndAscent = Integer.MIN_VALUE;
    private int mLastImeCameraNotifiedProtectedBottomScreenRow = Integer.MIN_VALUE;
    private boolean mLastImeCameraNotifiedCursorEnabled;
    private boolean mVulkanFailed;
    private long mGpuFrameId;
    private long mGpuLastSubmittedCommandGeneration = Long.MIN_VALUE;
    private long mGpuLastSubmittedModelRevision = Long.MIN_VALUE;
    private int mGpuLastSubmittedTopRow = Integer.MIN_VALUE;
    private float mGpuLastSubmittedViewportOffset = Float.NaN;
    private int mGpuLastSubmittedWidth = -1;
    private int mGpuLastSubmittedHeight = -1;
    private long mGpuPresentedFrames;

    /**
     * The current AutoFill type returned for {@link View#getAutofillType()} by {@link #getAutofillType()}.
     *
     * The default is {@link #AUTOFILL_TYPE_NONE} so that AutoFill UI, like toolbar above keyboard
     * is not shown automatically, like on Activity starts/View create. This value should be updated
     * to required value, like {@link #AUTOFILL_TYPE_TEXT} before calling
     * {@link AutofillManager#requestAutofill(View)} so that AutoFill UI shows. The updated value
     * set will automatically be restored to {@link #AUTOFILL_TYPE_NONE} in
     * {@link #autofill(AutofillValue)} so that AutoFill UI isn't shown anymore by calling
     * {@link #resetAutoFill()}.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private int mAutoFillType = AUTOFILL_TYPE_NONE;

    /**
     * The current AutoFill type returned for {@link View#getImportantForAutofill()} by
     * {@link #getImportantForAutofill()}.
     *
     * The default is {@link #IMPORTANT_FOR_AUTOFILL_NO} so that view is not considered important
     * for AutoFill. This value should be updated to required value, like
     * {@link #IMPORTANT_FOR_AUTOFILL_YES} before calling {@link AutofillManager#requestAutofill(View)}
     * so that Android and apps consider the view as important for AutoFill to process the request.
     * The updated value set will automatically be restored to {@link #IMPORTANT_FOR_AUTOFILL_NO} in
     * {@link #autofill(AutofillValue)} by calling {@link #resetAutoFill()}.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private int mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_NO;

    /**
     * The current AutoFill hints returned for {@link View#getAutofillHints()} ()} by {@link #getAutofillHints()} ()}.
     *
     * The default is an empty `string[]`. This value should be updated to required value. The
     * updated value set will automatically be restored an empty `string[]` in
     * {@link #autofill(AutofillValue)} by calling {@link #resetAutoFill()}.
     */
    private String[] mAutoFillHints = new String[0];

    private final boolean mAccessibilityEnabled;

    /** The {@link KeyEvent} is generated from a virtual keyboard, like manually with the {@link KeyEvent#KeyEvent(int, int)} constructor. */
    public final static int KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD = KeyCharacterMap.VIRTUAL_KEYBOARD; // -1

    /** The {@link KeyEvent} is generated from a non-physical device, like if 0 value is returned by {@link KeyEvent#getDeviceId()}. */
    public final static int KEY_EVENT_SOURCE_SOFT_KEYBOARD = 0;

    private static final String LOG_TAG = "TerminalView";

    public TerminalView(Context context, AttributeSet attributes) { // NO_UCD (unused code)
        super(context, attributes);
        mGestureRecognizer = new GestureAndScaleRecognizer(context, new GestureAndScaleRecognizer.Listener() {

            boolean scrolledWithFinger;

            @Override
            public boolean onUp(MotionEvent event) {
                mScrollRemainder = 0.0f;
                if (mEmulator != null && shouldUseMouseTrackingForTouchTap() && !event.isFromSource(InputDevice.SOURCE_MOUSE) && !isSelectingText() && !scrolledWithFinger) {
                    // Quick event processing when mouse tracking is active - do not wait for check of double tapping
                    // for zooming.
                    // Commit the terminal mouse transaction before Android focus/IME work. tmux is
                    // authoritative for the active pane, while the concrete view that received the
                    // touch remains authoritative for the keyboard request on this same tap.
                    sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, true);
                    sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, false);
                    if (mClient == null || mClient.shouldTerminalViewRequestFocusOnTap()) {
                        requestFocusFromTouch();
                        requestFocus();
                    }
                    if (mClient != null) mClient.onTerminalViewTap(TerminalView.this, mTermSession, event);
                    scrolledWithFinger = false;
                    return true;
                }
                scrolledWithFinger = false;
                return false;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent event) {
                if (mEmulator == null) return true;
                if (mFingerScrollTracker.isDragging()) return true;

                if (isSelectingText()) {
                    stopTextSelectionMode();
                    return true;
                }
                if (mClient == null || mClient.shouldTerminalViewRequestFocusOnTap()) {
                    requestFocusFromTouch();
                    requestFocus();
                }
                mClient.onTerminalViewTap(TerminalView.this, mTermSession, event);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e, float distanceX, float distanceY) {
                if (mEmulator == null) return true;
                if (mScaleGestureActive || mMultiTouchSequenceCaptured ||
                    mGestureRecognizer.isMultiTouchSequence()) {
                    mScrollRemainder = 0f;
                    return true;
                }
                if (mEmulator.isMouseTrackingActive() && e.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    // If moving with mouse pointer while pressing button, report that instead of scroll.
                    // This means that we never report moving with button press-events for touch input,
                    // since we cannot just start sending these events without a starting press event,
                    // which we do not do for touch input, only mouse in onTouchEvent().
                    sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true);
                } else {
                    scrolledWithFinger = true;
                    if (isSmoothGhosttyLocalScroll()) {
                        if (!mFingerScrollTracker.isActive()) {
                            scrollViewportByPixels(distanceY);
                        }
                    } else {
                        distanceY += mScrollRemainder;
                        int deltaRows = (int) (distanceY / mRenderer.mFontLineSpacing);
                        mScrollRemainder = distanceY - deltaRows * mRenderer.mFontLineSpacing;
                        doScroll(e, deltaRows);
                    }
                }
                return true;
            }

            @Override
            public boolean onScaleBegin(float focusX, float focusY, long eventTimeMillis) {
                if (mEmulator == null || mRenderer == null || mClient == null ||
                    isSelectingText()) return false;
                captureMultiTouchSequence();
                if (!mScroller.isFinished()) mScroller.abortAnimation();
                mScaleGestureActive = true;
                mScaleGestureQualified = false;
                mScaleCommitPending = false;
                mScaleStartTextSize = mRenderer.mTextSize;
                mScaleTargetTextSize = mScaleStartTextSize;
                mScaleGestureFactor = 1f;
                mScaleFocusX = focusX;
                mScaleFocusY = focusY;
                mScaleReportedFocusMaxDrift = 0f;
                mScaleSampleCount = 0;
                mScrollRemainder = 0f;
                scrolledWithFinger = false;
                mScaleGestureStartedNanos = System.nanoTime();
                mScaleGestureStartedEventTimeMillis = eventTimeMillis;
                mScaleReflowCount = 0;
                mScaleMetricNanos = 0L;
                mScaleResizeNanos = 0L;
                mScaleAnchorNanos = 0L;
                mScaleMetricMaxNanos = 0L;
                mScaleResizeMaxNanos = 0L;
                mScaleAnchorMaxNanos = 0L;
                mScaleAnchorExactCount = 0;
                mScaleAnchorClampedCount = 0;
                mScaleAnchorUnavailableCount = 0;
                mScaleGestureStartTopRow = mTopRow;
                mScaleGestureStartTranscriptRows = mEmulator.getActiveTranscriptRows();
                mScaleGestureStartedAlternateScreen = mEmulator.isAlternateBufferActive();
                boolean atLiveEdge = mTopRow == 0 && Math.abs(mViewportPixelOffset) < 0.01f;
                // Stream output retains the focal-cell behavior the user already validated. An
                // inline TUI at the live edge must remain live, otherwise its post-SIGWINCH redraw
                // leaves this viewport anchored to an obsolete frame in primary-screen history.
                mScaleGesturePinnedToLiveEdge =
                    TerminalTuiResizePolicy.shouldPinLiveEdge(atLiveEdge,
                        mScaleGestureStartedAlternateScreen, mEmulator.isMouseTrackingActive(),
                        mEmulator.shouldSendFocusEvents(),
                        mEmulator.isCursorKeysApplicationMode(),
                        mEmulator.isKeypadApplicationMode(), mEmulator.isCursorEnabled());
                mScaleLiveEdgePinCount = 0;
                mScaleGlyphWarmupPending = false;
                mScaleGlyphWarmCandidates = 0;
                mScaleGlyphWarmFrames = 0;
                mScaleGlyphWarmNanos = 0L;
                mGlyphWarmupFromIdle = false;
                mIdleGlyphWarmStableFrames = 0;
                mIdleGlyphWarmCandidateGeneration = Long.MIN_VALUE;
                mRenderer.setRealtimeScaleActive(true);
                Log.i(LOG_TAG, "pinch-reflow-v4 gesture-begin textPx=" +
                    mScaleStartTextSize + " top=" + mTopRow + " scrollback=" +
                    mScaleGestureStartTranscriptRows + " alternate=" +
                    mScaleGestureStartedAlternateScreen +
                    " viewportPolicy=" + (mScaleGesturePinnedToLiveEdge
                        ? "inline-tui-live-edge" : "tracked-cell-universal") +
                    " anchor=ghostty-tracked-cell pivot=fixed multitouchExclusive=true" +
                    " realReflow=true");
                return true;
            }

            @Override
            public boolean onScale(float focusX, float focusY, float scale,
                                   long eventTimeMillis) {
                if (!mScaleGestureActive || mEmulator == null || isSelectingText()) return true;
                if (!Float.isNaN(scale) && !Float.isInfinite(scale) && scale > 0f) {
                    mScaleGestureFactor = Math.max(0.125f,
                        Math.min(8f, mScaleGestureFactor * scale));
                }
                mScaleSampleCount++;
                mScaleReportedFocusMaxDrift = Math.max(mScaleReportedFocusMaxDrift,
                    TerminalPinchViewportAnchor.reportedFocusDrift(
                        mScaleFocusX, mScaleFocusY, focusX, focusY));
                if (!mScaleGestureQualified) {
                    mScaleGestureQualified = TerminalPinchGesturePolicy.qualifies(
                        mScaleGestureFactor, mScaleSampleCount,
                        Math.max(0L, eventTimeMillis - mScaleGestureStartedEventTimeMillis));
                }
                if (!mScaleGestureQualified) return true;
                float density = getResources().getDisplayMetrics().density;
                int minimum = Math.max(4, (int) (4f * density));
                mScaleTargetTextSize = Math.max(minimum, Math.min(256,
                    Math.round(mScaleStartTextSize * mScaleGestureFactor)));
                scheduleRealtimeScaleFrame();
                return true;
            }

            @Override
            public void onScaleEnd(boolean cancelled) {
                if (!mScaleGestureActive) return;
                boolean commit = TerminalPinchGesturePolicy.shouldCommit(
                    mScaleGestureQualified, cancelled, mScaleStartTextSize,
                    mScaleTargetTextSize);
                if (!commit) {
                    abortRealtimeScaleGesture(cancelled ? "touch-cancel" : "unqualified");
                    return;
                }
                mScaleGestureActive = false;
                mScaleCommitPending = true;
                Log.i(LOG_TAG, "pinch-reflow-v4 gesture-end targetPx=" +
                    mScaleTargetTextSize + " reflowsSoFar=" + mScaleReflowCount +
                    " samples=" + mScaleSampleCount + " qualified=true" +
                    " pivot=fixed reportedFocusDriftPx=" +
                    Math.round(mScaleReportedFocusMaxDrift));
                scheduleRealtimeScaleFrame();
            }

            @Override
            public boolean onFling(final MotionEvent e2, float velocityX, float velocityY) {
                if (mEmulator == null) return true;
                if (mScaleGestureActive || mMultiTouchSequenceCaptured ||
                    mGestureRecognizer.isMultiTouchSequence()) return true;
                // Do not start scrolling until last fling has been taken care of:
                if (!mScroller.isFinished()) return true;

                if (isSmoothGhosttyLocalScroll()) {
                    startSmoothGhosttyFling(velocityY);
                    return true;
                }

                final boolean mouseTrackingAtStartOfFling = shouldUseMouseTrackingForTouchScroll();
                float SCALE = 0.25f;
                if (mouseTrackingAtStartOfFling) {
                    mScroller.fling(0, 0, 0, -(int) (velocityY * SCALE), 0, 0, -mEmulator.mRows / 2, mEmulator.mRows / 2);
                } else {
                    mScroller.fling(0, mTopRow, 0, -(int) (velocityY * SCALE), 0, 0, -mEmulator.getActiveTranscriptRows(), 0);
                }

                post(new Runnable() {
                    private int mLastY = 0;

                    @Override
                    public void run() {
                        if (mouseTrackingAtStartOfFling != shouldUseMouseTrackingForTouchScroll()) {
                            mScroller.abortAnimation();
                            return;
                        }
                        if (mScroller.isFinished()) return;
                        boolean more = mScroller.computeScrollOffset();
                        int newY = mScroller.getCurrY();
                        int diff = mouseTrackingAtStartOfFling ? (newY - mLastY) : (newY - mTopRow);
                        doScroll(e2, diff);
                        mLastY = newY;
                        if (more) post(this);
                    }
                });

                return true;
            }

            @Override
            public boolean onDown(float x, float y) {
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                // Do not treat is as a single confirmed tap - it may be followed by zoom.
                return false;
            }

            @Override
            public void onLongPress(MotionEvent event) {
                if (mGestureRecognizer.isInProgress()) return;
                if (mClient.onLongPress(event)) return;
                if (!isSelectingText()) {
                    finishDirectFingerScroll("long-press");
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    startTextSelectionMode(event);
                }
            }
        });
        mScroller = new Scroller(context);
        mFingerScrollCaptureSlop = Math.max(1f,
            ViewConfiguration.get(context).getScaledTouchSlop() * 0.35f);
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        mAccessibilityEnabled = am.isEnabled();
    }



    /**
     * @param client The {@link TerminalViewClient} interface implementation to allow
     *                           for communication between {@link TerminalView} and its client.
     */
    public void setTerminalViewClient(TerminalViewClient client) {
        this.mClient = client;
    }

    /**
     * Sets whether terminal view key logging is enabled or not.
     *
     * @param value The boolean value that defines the state.
     */
    public void setIsTerminalViewKeyLoggingEnabled(boolean value) {
        TERMINAL_VIEW_KEY_LOGGING_ENABLED = value;
    }



    /**
     * Attach a {@link TerminalSession} to this view.
     *
     * @param session The {@link TerminalSession} this view will be displaying.
     */
    public boolean attachSession(TerminalSession session) {
        if (session == mTermSession) return false;
        finishDirectFingerScroll("session-switch");
        abortRealtimeScaleGesture("session-switch");
        if (!mScroller.isFinished()) mScroller.abortAnimation();
        if (mRenderer != null) mRenderer.resetRenderState();
        mTopRow = 0;
        mViewportPixelOffset = 0f;
        mLastSentCellWidth = -1;
        mLastSentCellHeight = -1;
        mGeometryCommitPolicy.reset();
        mGeometryCommitPolicy.setImeViewportActive(mImeViewportGeometryLocked);
        mGeometryCommitFrameEpoch++;
        mGeometryCommitFrameScheduled = false;
        mLastRenderedCursorRow = -1;
        mLastRenderedCursorCol = -1;
        mLastRenderedCursorStyle = -1;
        mLastRenderedCursorVisible = false;
        resetPresentedImeCameraState();
        resetImeCameraFrameNotificationState();
        resetVulkanSubmissionState();

        mTermSession = session;
        // A retained tab/session already owns an authoritative grid. Adopt it before consulting
        // this View's transient layout so a tab switch or IME-visible holder attachment cannot
        // resize a live SSH/tmux PTY merely to present it.
        mEmulator = session.getEmulator();
        mCombiningAccent = 0;

        if (mEmulator != null) {
            mLastSentCellWidth = mEmulator.getCellWidthPixelsForDiagnostics();
            mLastSentCellHeight = mEmulator.getCellHeightPixelsForDiagnostics();
            mGeometryCommitPolicy.markCommitted(new TerminalGeometryCommitPolicy.Geometry(
                mEmulator.mColumns, mEmulator.mRows,
                mLastSentCellWidth, mLastSentCellHeight));
            mClient.onEmulatorSet();
            if (mTerminalCursorBlinkerRunnable != null) {
                mTerminalCursorBlinkerRunnable.setEmulator(mEmulator);
            }
        }

        updateSize();

        // Wait with enabling the scrollbar until we have a terminal to get scroll position from.
        setVerticalScrollBarEnabled(true);
        requestFullRenderFrame();

        return true;
    }

    /** Connect the optional GPU surface owned by the containing pager page. */
    public void setVulkanView(@Nullable TerminalVulkanView view) {
        if (mVulkanView == view) return;
        if (mVulkanView != null) mVulkanView.attachTerminalView(null);
        mVulkanView = view;
        mVulkanFailed = false;
        resetVulkanSubmissionState();
        if (view != null) {
            view.attachTerminalView(this);
            publishVulkanFrameNow(true);
        }
        invalidate();
    }

    /** Notifies the owner when local scroll changes which terminal row is visually anchored. */
    public void setVisualViewportAnchorChangedListener(@Nullable Runnable listener) {
        mVisualViewportAnchorChangedListener = listener;
    }

    /** Notifies the owner after a terminal-pixel frame becomes authoritative for IME focus. */
    public void setImeCameraFrameReadyListener(@Nullable Runnable listener) {
        mImeCameraFrameReadyListener = listener;
        resetImeCameraFrameNotificationState();
    }

    /** Notifies the owner of explicit user intent to leave history and write at the live cursor. */
    public void setImeExplicitFocusListener(@Nullable Runnable listener) {
        mImeExplicitFocusListener = listener;
    }

    private void notifyVisualViewportAnchorChanged() {
        Runnable listener = mVisualViewportAnchorChangedListener;
        if (listener != null) listener.run();
    }

    private void notifyImeExplicitFocus() {
        Runnable listener = mImeExplicitFocusListener;
        if (listener != null) listener.run();
    }

    private void resetImeCameraFrameNotificationState() {
        mImeCameraFrameRequestPolicy.reset();
        mLastImeCameraNotifiedRevision = Long.MIN_VALUE;
        mLastImeCameraNotifiedCursorRow = Integer.MIN_VALUE;
        mLastImeCameraNotifiedCursorColumn = Integer.MIN_VALUE;
        mLastImeCameraNotifiedTopRow = Integer.MIN_VALUE;
        mLastImeCameraNotifiedViewportOffsetBits = Integer.MIN_VALUE;
        mLastImeCameraNotifiedViewHeight = -1;
        mLastImeCameraNotifiedFontLineSpacing = -1;
        mLastImeCameraNotifiedFontLineSpacingAndAscent = Integer.MIN_VALUE;
        mLastImeCameraNotifiedProtectedBottomScreenRow = Integer.MIN_VALUE;
        mLastImeCameraNotifiedCursorEnabled = false;
    }

    private void resetPresentedImeCameraState() {
        mLastPresentedContentRevision = Long.MIN_VALUE;
        mLastPresentedTopRow = Integer.MIN_VALUE;
        mLastPresentedViewportOffsetBits = Integer.MIN_VALUE;
        mLastPresentedViewHeight = -1;
        mLastPresentedFontLineSpacing = -1;
        mLastPresentedFontLineSpacingAndAscent = Integer.MIN_VALUE;
        mLastPresentedCursorEnabled = false;
        mLastPresentedImeProtectedBottomScreenRow = -1;
    }

    private void recordPresentedImeCameraState(int topRow, float viewportPixelOffset,
                                                int viewHeight, int fontLineSpacing,
                                                int fontAscent,
                                                int imeProtectedBottomScreenRow) {
        mLastPresentedTopRow = topRow;
        mLastPresentedViewportOffsetBits = Float.floatToIntBits(viewportPixelOffset);
        mLastPresentedViewHeight = viewHeight;
        mLastPresentedFontLineSpacing = fontLineSpacing;
        mLastPresentedFontLineSpacingAndAscent = fontLineSpacing + fontAscent;
        mLastPresentedImeProtectedBottomScreenRow = imeProtectedBottomScreenRow;
    }

    private int resolvePresentedImeProtectedBottomScreenRow() {
        if (mEmulator == null || mRenderer == null) return mLastRenderedCursorRow;
        int cursorScreenRow = mLastRenderedCursorRow;
        if (cursorScreenRow < 0 || cursorScreenRow >= mEmulator.mRows) {
            return cursorScreenRow;
        }
        boolean alternateScreen = mEmulator.isAlternateBufferActive();
        boolean inlinePrimaryScreen = TerminalTuiResizePolicy.isInlinePrimaryScreen(
            alternateScreen, mEmulator.isMouseTrackingActive(),
            mEmulator.shouldSendFocusEvents(), mEmulator.isCursorKeysApplicationMode(),
            mEmulator.isKeypadApplicationMode(), mEmulator.isCursorEnabled());
        int semanticTail = mEmulator.isGhosttyRenderAuthorityActive()
            ? mRenderer.findLastGhosttySemanticScreenRow(
                mTopRow, cursorScreenRow, mEmulator.mRows)
            : mEmulator.getScreen().findLastNonBlankScreenRow(cursorScreenRow);
        return TerminalImeSemanticEnvelope.resolveProtectedBottomScreenRow(
            alternateScreen, inlinePrimaryScreen, cursorScreenRow, semanticTail, mEmulator.mRows);
    }

    private void notifyImeCameraFrameReady() {
        Runnable listener = mImeCameraFrameReadyListener;
        if (listener == null || mEmulator == null) return;
        long revision = mLastPresentedContentRevision;
        int cursorRow = mLastRenderedCursorRow;
        int cursorColumn = mLastRenderedCursorCol;
        // Every value in this identity must come from the frame that actually reached pixels.
        // Reading mTopRow/mViewportPixelOffset here mixes an older asynchronous Vulkan frame with
        // a newer UI-thread viewport. A far history-to-live jump can then record the stale history
        // frame as topRow=0 and suppress the real live frame because its model revision and cursor
        // happen to be unchanged. The IME camera would remain permanently WAITING_READY.
        int presentedTopRow = mLastPresentedTopRow;
        int viewportOffsetBits = mLastPresentedViewportOffsetBits;
        boolean cursorEnabled = mLastPresentedCursorEnabled;
        boolean frameIdentityChanged = !(revision == mLastImeCameraNotifiedRevision &&
            cursorRow == mLastImeCameraNotifiedCursorRow &&
            cursorColumn == mLastImeCameraNotifiedCursorColumn &&
            presentedTopRow == mLastImeCameraNotifiedTopRow &&
            viewportOffsetBits == mLastImeCameraNotifiedViewportOffsetBits &&
            mLastPresentedViewHeight == mLastImeCameraNotifiedViewHeight &&
            mLastPresentedFontLineSpacing == mLastImeCameraNotifiedFontLineSpacing &&
            mLastPresentedFontLineSpacingAndAscent ==
                mLastImeCameraNotifiedFontLineSpacingAndAscent &&
            mLastPresentedImeProtectedBottomScreenRow ==
                mLastImeCameraNotifiedProtectedBottomScreenRow &&
            cursorEnabled == mLastImeCameraNotifiedCursorEnabled);
        if (!mImeCameraFrameRequestPolicy.shouldNotify(frameIdentityChanged)) {
            return;
        }
        mLastImeCameraNotifiedRevision = revision;
        mLastImeCameraNotifiedCursorRow = cursorRow;
        mLastImeCameraNotifiedCursorColumn = cursorColumn;
        mLastImeCameraNotifiedTopRow = presentedTopRow;
        mLastImeCameraNotifiedViewportOffsetBits = viewportOffsetBits;
        mLastImeCameraNotifiedViewHeight = mLastPresentedViewHeight;
        mLastImeCameraNotifiedFontLineSpacing = mLastPresentedFontLineSpacing;
        mLastImeCameraNotifiedFontLineSpacingAndAscent =
            mLastPresentedFontLineSpacingAndAscent;
        mLastImeCameraNotifiedProtectedBottomScreenRow =
            mLastPresentedImeProtectedBottomScreenRow;
        mLastImeCameraNotifiedCursorEnabled = cursorEnabled;
        mImeCameraFrameRequestPolicy.markNotified();
        listener.run();
    }

    private void resetVulkanSubmissionState() {
        mGpuLastSubmittedCommandGeneration = Long.MIN_VALUE;
        mGpuLastSubmittedModelRevision = Long.MIN_VALUE;
        mGpuLastSubmittedTopRow = Integer.MIN_VALUE;
        mGpuLastSubmittedViewportOffset = Float.NaN;
        mGpuLastSubmittedWidth = -1;
        mGpuLastSubmittedHeight = -1;
    }

    /** Called on the UI thread after the Vulkan thread has presented a complete frame. */
    void onVulkanFramePresented(@NonNull TerminalVulkanView view,
                                @NonNull TerminalGpuFrame frame) {
        if (view != mVulkanView) return;
        mVulkanFailed = false;
        mGpuPresentedFrames++;
        mLastPresentedContentRevision = frame.modelRevision;
        mLastRenderedCursorRow = frame.cursorRow;
        mLastRenderedCursorCol = frame.cursorColumn;
        mLastRenderedCursorStyle = frame.cursorStyle;
        mLastRenderedCursorVisible = frame.cursorVisible;
        mLastPresentedCursorEnabled = frame.cursorEnabled;
        recordPresentedImeCameraState(frame.viewportTopRow, frame.viewportPixelOffset,
            frame.viewHeight, frame.fontLineSpacing, frame.fontAscent,
            frame.imeProtectedBottomScreenRow >= frame.cursorRow
                ? frame.imeProtectedBottomScreenRow : frame.cursorRow);
        notifyImeCameraFrameReady();
        invalidate();
    }

    /** A missing retained row is recoverable by exporting one complete GPU frame. */
    void onVulkanFrameNeedsFull(@NonNull TerminalVulkanView view,
                                @NonNull TerminalGpuFrame ignoredFrame) {
        if (view != mVulkanView || mEmulator == null || mRenderer == null) return;
        resetVulkanSubmissionState();
        mPendingFullInvalidation = true;
        publishVulkanFrameNow(true);
        invalidate();
    }

    /** Permanently switch this tab to the proven Canvas path after native failure. */
    void onVulkanRendererFailed(@NonNull TerminalVulkanView view) {
        if (view != mVulkanView) return;
        mVulkanFailed = true;
        view.setVisibility(INVISIBLE);
        resetVulkanSubmissionState();
        invalidate();
        Log.e(LOG_TAG, "vulkan-renderer-fallback view=" + System.identityHashCode(this) +
            " diagnostics=" + view.getDiagnostics());
    }

    /** Surface loss must immediately expose Canvas so a tab never remains black. */
    void onVulkanFrameUnavailable(@NonNull TerminalVulkanView view) {
        if (view != mVulkanView) return;
        resetVulkanSubmissionState();
        invalidate();
    }

    /** A TextureView resize invalidates the last swapchain image even if its frame metadata was final. */
    void onVulkanSurfaceSizeChanged(@NonNull TerminalVulkanView view) {
        if (view != mVulkanView || mVulkanFailed) return;
        resetVulkanSubmissionState();
        mPendingFullInvalidation = true;
        publishVulkanFrameNow(true);
        invalidate();
    }

    /** Queue a complete native frame when this page becomes the sole visible Vulkan owner. */
    void onVulkanSurfaceActivated(@NonNull TerminalVulkanView view) {
        if (view != mVulkanView || mVulkanFailed) return;
        resetVulkanSubmissionState();
        mPendingFullInvalidation = true;
        publishVulkanFrameNow(true);
        invalidate();
    }

    private boolean isVulkanFrameActive() {
        return mVulkanView != null && !mVulkanFailed && hasCurrentTerminalGeometry() &&
            mVulkanView.isFrameReadyForGeometry(getWidth(), getHeight(), mRenderer.mTextSize,
                mRenderer.mFontWidth, mRenderer.mFontLineSpacing,
                mRenderer.mFontAscent, mEmulator.mRows, mTopRow, mViewportPixelOffset,
                mRenderer.getGhosttyRetainedCommandGeneration(),
                mRenderer.getGhosttyCachedModelRevision());
    }

    /** Submit the latest immutable Ghostty frame to the per-tab GPU thread. */
    private void publishVulkanFrameNow(boolean forceFull) {
        TerminalVulkanView view = mVulkanView;
        if (view == null || mVulkanFailed || !view.isRenderActive() || !view.isSupported() ||
            mEmulator == null ||
            mRenderer == null || !mEmulator.isGhosttyRenderAuthorityActive() ||
            getWidth() <= 0 || getHeight() <= 0 || !hasCurrentTerminalGeometry()) return;

        long commandGeneration = mRenderer.getGhosttyRetainedCommandGeneration();
        long modelRevision = mRenderer.getGhosttyCachedModelRevision();
        int topRow = mTopRow;
        float viewportOffset = mViewportPixelOffset;
        int width = getWidth();
        int height = getHeight();
        boolean sizeChanged = width != mGpuLastSubmittedWidth ||
            height != mGpuLastSubmittedHeight;
        boolean requiresSubmission = forceFull || !view.isFrameReady() ||
            view.getConsumedCommandGeneration() == Long.MIN_VALUE ||
            commandGeneration != mGpuLastSubmittedCommandGeneration ||
            modelRevision != mGpuLastSubmittedModelRevision ||
            topRow != mGpuLastSubmittedTopRow ||
            Math.abs(viewportOffset - mGpuLastSubmittedViewportOffset) > 0.01f ||
            sizeChanged;
        if (!requiresSubmission) return;
        boolean exportFull = forceFull || !view.isFrameReady() ||
            view.getConsumedCommandGeneration() == Long.MIN_VALUE || sizeChanged;

        int[] selectors = mDefaultSelectors;
        if (mTextSelectionCursorController != null) {
            mTextSelectionCursorController.getSelectors(selectors);
        }
        TerminalGpuFrame frame = mRenderer.prepareGpuFrame(mEmulator, width, height, topRow,
            viewportOffset, exportFull, selectors[0], selectors[1], selectors[2], selectors[3],
            ++mGpuFrameId, view.getConsumedCommandGeneration(), view.getConsumedTopRow());
        if (frame == null || !frame.contentReady) return;
        view.submitFrame(frame);
        mGpuLastSubmittedCommandGeneration = frame.commandGeneration;
        mGpuLastSubmittedModelRevision = frame.modelRevision;
        mGpuLastSubmittedTopRow = topRow;
        mGpuLastSubmittedViewportOffset = viewportOffset;
        mGpuLastSubmittedWidth = width;
        mGpuLastSubmittedHeight = height;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // Ensure that inputType is only set if TerminalView is selected view with the keyboard and
        // an alternate view is not selected, like an EditText. This is necessary if an activity is
        // initially started with the alternate view or if activity is returned to from another app
        // and the alternate view was the one selected the last time.
        if (mClient.isTerminalViewSelected()) {
            if (mClient.shouldEnforceCharBasedInput()) {
                // Some keyboards seems do not reset the internal state on TYPE_NULL.
                // Affects mostly Samsung stock keyboards.
                // https://github.com/termux/termux-app/issues/686
                // However, this is not a valid value as per AOSP since `InputType.TYPE_CLASS_*` is
                // not set and it logs a warning:
                // W/InputAttributes: Unexpected input class: inputType=0x00080090 imeOptions=0x02000000
                // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:packages/inputmethods/LatinIME/java/src/com/android/inputmethod/latin/InputAttributes.java;l=79
                outAttrs.inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            } else {
                // Using InputType.NULL is the most correct input type and avoids issues with other hacks.
                //
                // Previous keyboard issues:
                // https://github.com/termux/termux-packages/issues/25
                // https://github.com/termux/termux-app/issues/87.
                // https://github.com/termux/termux-app/issues/126.
                // https://github.com/termux/termux-app/issues/137 (japanese chars and TYPE_NULL).
                outAttrs.inputType = InputType.TYPE_NULL;
            }
        } else {
            // Corresponds to android:inputType="text"
            outAttrs.inputType =  InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL;
        }

        // Note that IME_ACTION_NONE cannot be used as that makes it impossible to input newlines using the on-screen
        // keyboard on Android TV (see https://github.com/termux/termux-app/issues/221).
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;

        return new BaseInputConnection(this, true) {

            @Override
            public boolean finishComposingText() {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) mClient.logInfo(LOG_TAG, "IME: finishComposingText()");
                super.finishComposingText();

                sendTextToTerminal(getEditable());
                getEditable().clear();
                return true;
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient.logInfo(LOG_TAG, "IME: commitText(\"" + text + "\", " + newCursorPosition + ")");
                }
                super.commitText(text, newCursorPosition);

                if (mEmulator == null) return true;

                Editable content = getEditable();
                sendTextToTerminal(content);
                content.clear();
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int leftLength, int rightLength) {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient.logInfo(LOG_TAG, "IME: deleteSurroundingText(" + leftLength + ", " + rightLength + ")");
                }
                // The stock Samsung keyboard with 'Auto check spelling' enabled sends leftLength > 1.
                KeyEvent deleteKey = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL);
                for (int i = 0; i < leftLength; i++) sendKeyEvent(deleteKey);
                return super.deleteSurroundingText(leftLength, rightLength);
            }

            void sendTextToTerminal(CharSequence text) {
                stopTextSelectionMode();
                final int textLengthInChars = text.length();
                for (int i = 0; i < textLengthInChars; i++) {
                    char firstChar = text.charAt(i);
                    int codePoint;
                    if (Character.isHighSurrogate(firstChar)) {
                        if (++i < textLengthInChars) {
                            codePoint = Character.toCodePoint(firstChar, text.charAt(i));
                        } else {
                            // At end of string, with no low surrogate following the high:
                            codePoint = TerminalEmulator.UNICODE_REPLACEMENT_CHAR;
                        }
                    } else {
                        codePoint = firstChar;
                    }

                    // Check onKeyDown() for details.
                    if (mClient.readShiftKey())
                        codePoint = Character.toUpperCase(codePoint);

                    boolean ctrlHeld = false;
                    if (codePoint <= 31 && codePoint != 27) {
                        if (codePoint == '\n') {
                            // The AOSP keyboard and descendants seems to send \n as text when the enter key is pressed,
                            // instead of a key event like most other keyboard apps. A terminal expects \r for the enter
                            // key (although when icrnl is enabled this doesn't make a difference - run 'stty -icrnl' to
                            // check the behaviour).
                            codePoint = '\r';
                        }

                        // E.g. penti keyboard for ctrl input.
                        ctrlHeld = true;
                        switch (codePoint) {
                            case 31:
                                codePoint = '_';
                                break;
                            case 30:
                                codePoint = '^';
                                break;
                            case 29:
                                codePoint = ']';
                                break;
                            case 28:
                                codePoint = '\\';
                                break;
                            default:
                                codePoint += 96;
                                break;
                        }
                    }

                    inputCodePoint(KEY_EVENT_SOURCE_SOFT_KEYBOARD, codePoint, ctrlHeld, false);
                }
            }

        };
    }

    @Override
    protected int computeVerticalScrollRange() {
        return mEmulator == null ? 1 : mEmulator.getActiveRows();
    }

    @Override
    protected int computeVerticalScrollExtent() {
        return mEmulator == null ? 1 : mEmulator.mRows;
    }

    @Override
    protected int computeVerticalScrollOffset() {
        return mEmulator == null ? 1 : mEmulator.getActiveRows() + mTopRow - mEmulator.mRows;
    }

    public void onScreenUpdated() {
        onScreenUpdated(false);
    }

    public void onScreenUpdated(boolean skipScrolling) {
        if (mEmulator == null) return;

        boolean ghosttyAuthority = mEmulator.isGhosttyRenderAuthorityActive();
        TerminalBuffer screen = ghosttyAuthority ? null : mEmulator.getScreen();
        int rowsInHistory = mEmulator.getActiveTranscriptRows();
        if (mTopRow < -rowsInHistory) {
            mTopRow = -rowsInHistory;
            mViewportPixelOffset = 0f;
        }
        boolean requireFullInvalidate = mEmulator.consumeFullRedrawRequired();
        final int scrolledRows = mEmulator.consumeScrollCounter();

        // Only follow output when we are already at the bottom. If the user has scrolled up (mTopRow != 0),
        // keep their scroll position stable while new output is appended.
        final boolean selectingText = isSelectingText();
        final boolean followOutput = (mTopRow == 0) && !selectingText && !mEmulator.isAutoScrollDisabled();
        if (!followOutput) {
            int rowShift = scrolledRows;
            if (rowShift != 0) {
                float viewportPositionBeforeOutputShift = getViewportPositionPixels();
                if (-mTopRow + rowShift > rowsInHistory) {
                    // We're hitting the end of the history transcript, clamp to the oldest available row.
                    if (selectingText) stopTextSelectionMode();
                    mTopRow = -rowsInHistory;
                    mViewportPixelOffset = 0f;
                } else {
                    mTopRow -= rowShift;
                    if (selectingText) decrementYTextSelectionCursors(rowShift);
                }
                rebaseActiveLocalScrollAfterOutput(viewportPositionBeforeOutputShift);
                requireFullInvalidate = true;
            }
            skipScrolling = true;
        }

        if (!skipScrolling && mTopRow != 0) {
            // Scroll down if not already there.
            if (mTopRow < -3) {
                // Awaken scroll bars only if scrolling a noticeable amount
                // - we do not want visible scroll bars during normal typing
                // of one row at a time.
                awakenScrollBars();
            }
            mTopRow = 0;
            mViewportPixelOffset = 0f;
            requireFullInvalidate = true;
        }

        int dirtyStart = Integer.MAX_VALUE;
        int dirtyEnd = Integer.MIN_VALUE;
        if (screen != null && screen.hasDirtyRows()) {
            dirtyStart = screen.getDirtyStartRow();
            dirtyEnd = screen.getDirtyEndRow();
        }
        boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        int cursorRow = mEmulator.getCursorRow();
        int cursorCol = mEmulator.getCursorCol();
        int cursorStyle = mEmulator.getCursorStyle();
        boolean cursorChanged = mLastRenderedCursorVisible != cursorVisible ||
            (cursorVisible && (mLastRenderedCursorRow != cursorRow ||
                mLastRenderedCursorCol != cursorCol || mLastRenderedCursorStyle != cursorStyle));
        if (cursorChanged) {
            if (mLastRenderedCursorVisible && mLastRenderedCursorRow >= 0) {
                dirtyStart = Math.min(dirtyStart, mLastRenderedCursorRow);
                dirtyEnd = Math.max(dirtyEnd, mLastRenderedCursorRow + 1);
            }
            if (cursorVisible) {
                dirtyStart = Math.min(dirtyStart, cursorRow);
                dirtyEnd = Math.max(dirtyEnd, cursorRow + 1);
            }
        }

        if (screen != null) screen.clearDirtyRows();

        if (ghosttyAuthority) {
            int[] selectors = mDefaultSelectors;
            if (mTextSelectionCursorController != null) {
                mTextSelectionCursorController.getSelectors(selectors);
            }
            boolean prepared = mRenderer != null && mRenderer.prewarmGhosttyFrame(
                mEmulator, mTopRow, mViewportPixelOffset, requireFullInvalidate,
                selectors[0], selectors[1], selectors[2], selectors[3]);
            if (!prepared) {
                scheduleRenderFrame(true);
                return;
            }

            if (requireFullInvalidate || mRenderer.isPreparedGhosttyDamageFull()) {
                publishGhosttyRenderFrameNow(true, -1, -1);
                return;
            }

            int damageStart = mRenderer.getPreparedGhosttyDamageStart();
            int damageEnd = mRenderer.getPreparedGhosttyDamageEnd();
            if (damageStart < damageEnd) {
                publishGhosttyRenderFrameNow(true, damageStart, damageEnd);
            } else {
                publishVulkanFrameNow(false);
            }
            return;
        }

        if (requireFullInvalidate) {
            scheduleRenderFrame(true);
            return;
        }

        if (dirtyStart >= dirtyEnd) return;

        int visibleStart = mTopRow;
        int visibleEnd = mTopRow + mEmulator.mRows;
        int overlapStart = Math.max(dirtyStart, visibleStart);
        int overlapEnd = Math.min(dirtyEnd, visibleEnd);
        if (overlapStart < overlapEnd) {
            scheduleRenderFrame(true, overlapStart - mTopRow, overlapEnd - mTopRow);
        }
    }

    /** This must be called by the hosting activity in {@link Activity#onContextMenuClosed(Menu)}
     * when context menu for the {@link TerminalView} is started by
     * {@link TextSelectionCursorController#ACTION_MORE} is closed. */
    public void onContextMenuClosed(Menu menu) {
        // Unset the stored text since it shouldn't be used anymore and should be cleared from memory
        unsetStoredSelectedText();
    }

    /**
     * Sets the text size, which in turn sets the number of rows and columns.
     *
     * @param textSize the new font size, in density-independent pixels.
     */
    public void setTextSize(int textSize) {
        if (mRenderer != null && mRenderer.mTextSize == textSize) return;
        if (mRenderer == null) {
            mRenderer = new TerminalRenderer(textSize, Typeface.MONOSPACE);
        } else {
            mRenderer.reconfigure(textSize, mRenderer.mTypeface);
        }
        resetVulkanSubmissionState();
        invalidate();
        requestTerminalGeometry(TerminalGeometryCommitPolicy.Source.USER_TEXT_SCALE,
            false, 0, -1, -1, -1);
        publishVulkanFrameNow(true);
    }

    /** Test-only diagnostic accessor; production rendering state remains renderer-owned. */
    public int getTextSizeForDiagnostics() {
        return mRenderer == null ? 0 : mRenderer.mTextSize;
    }

    public int getResizeAnchorOutcomeForDiagnostics() {
        return mEmulator == null ? 0 : mEmulator.getGhosttyResizeAnchorOutcome();
    }

    public String getResizeAnchorStatusForDiagnostics() {
        return mEmulator == null ? "emulator=none outcome=0" :
            mEmulator.getGhosttyResizeAnchorStatusForDiagnostics();
    }

    public boolean isResizeAnchorCommitValidForDiagnostics() {
        return mEmulator != null &&
            mEmulator.isGhosttyResizeAnchorCommitValidForDiagnostics();
    }

    public float getViewportPixelOffsetForDiagnostics() {
        return mViewportPixelOffset;
    }

    /** Device-lab hook for exercising pinch anchoring at a deterministic transcript position. */
    public void setViewportPositionForDiagnostics(int topRow, float pixelOffset) {
        if (mEmulator == null || mRenderer == null) return;
        float lineHeight = Math.max(1f, mRenderer.mFontLineSpacing);
        setViewportPositionPixels(topRow * lineHeight + pixelOffset, false);
        requestFullRenderFrame();
    }

    public int getTranscriptRowsForDiagnostics() {
        return mEmulator == null ? 0 : mEmulator.getActiveTranscriptRows();
    }

    public float getScaleReportedFocusDriftForDiagnostics() {
        return mScaleReportedFocusMaxDrift;
    }

    public boolean isScalePivotLockedForDiagnostics() {
        return true;
    }

    public boolean isScaleLiveEdgePinnedForDiagnostics() {
        return mScaleGesturePinnedToLiveEdge;
    }

    public int getScaleLiveEdgePinCountForDiagnostics() {
        return mScaleLiveEdgePinCount;
    }

    private void scheduleRealtimeScaleFrame() {
        if (mScaleFrameScheduled) return;
        mScaleFrameScheduled = true;
        postOnAnimation(() -> {
            mScaleFrameScheduled = false;
            applyRealtimeScaleFrame();
        });
    }

    private void applyRealtimeScaleFrame() {
        if (mRenderer == null || mEmulator == null) {
            mScaleCommitPending = false;
            return;
        }
        int target = mScaleTargetTextSize;
        if (target > 0 && target != mRenderer.mTextSize) {
            applyRealtimeTextSize(target, mScaleFocusX, mScaleFocusY);
        }
        if (!mScaleGestureActive && mScaleCommitPending) {
            mScaleCommitPending = false;
            mScaleFactor = 1f;
            mRenderer.setRealtimeScaleActive(false);
            mScaleGlyphWarmupPending = true;
            if (mClient != null) {
                mClient.onScaleTextSizeChanged(mRenderer.mTextSize, true);
            }
            long elapsedMicros = Math.max(0L,
                (System.nanoTime() - mScaleGestureStartedNanos) / 1000L);
            Log.i(LOG_TAG, "pinch-reflow-v4 startPx=" + mScaleStartTextSize +
                " endPx=" + mRenderer.mTextSize + " nativeReflows=" + mScaleReflowCount +
                " elapsedUs=" + elapsedMicros +
                " metricUs=" + averageScaleMicros(mScaleMetricNanos) + '/' +
                    nanosToMicros(mScaleMetricMaxNanos) +
                " resizeUs=" + averageScaleMicros(mScaleResizeNanos) + '/' +
                    nanosToMicros(mScaleResizeMaxNanos) +
                " anchorUs=" + averageScaleMicros(mScaleAnchorNanos) + '/' +
                    nanosToMicros(mScaleAnchorMaxNanos) +
                " anchorOutcome=" + mScaleAnchorExactCount + '/' +
                    mScaleAnchorClampedCount + '/' + mScaleAnchorUnavailableCount +
                " viewport=" + mScaleGestureStartTopRow + "->" + mTopRow +
                " scrollback=" + mScaleGestureStartTranscriptRows + "->" +
                    mEmulator.getActiveTranscriptRows() +
                " alternate=" + mScaleGestureStartedAlternateScreen +
                " viewportPolicy=" + (mScaleGesturePinnedToLiveEdge
                    ? "inline-tui-live-edge" : "tracked-cell-universal") +
                " liveEdgePins=" + mScaleLiveEdgePinCount +
                " pivot=fixed reportedFocusDriftPx=" +
                    Math.round(mScaleReportedFocusMaxDrift) +
                " multitouchExclusive=true" +
                " ptyResize=continuous-vsync" +
                " realReflow=true resizePath={" +
                    mEmulator.getGhosttyResizeStatusForDiagnostics() +
                "} anchorPath={" +
                    mEmulator.getGhosttyResizeAnchorStatusForDiagnostics() + '}');
        }
    }

    private void abortRealtimeScaleGesture(String reason) {
        if (!mScaleGestureActive && !mScaleCommitPending) return;
        int currentTextSize = mRenderer == null ? -1 : mRenderer.mTextSize;
        mScaleGestureActive = false;
        mScaleCommitPending = false;
        mScaleTargetTextSize = mScaleStartTextSize;
        if (mRenderer != null && mEmulator != null && mScaleStartTextSize > 0 &&
            currentTextSize != mScaleStartTextSize) {
            applyRealtimeTextSize(mScaleStartTextSize, mScaleFocusX, mScaleFocusY);
        }
        if (mRenderer != null) mRenderer.setRealtimeScaleActive(false);
        Log.i(LOG_TAG, "pinch-reflow-v4 aborted reason=" + reason +
            " startPx=" + mScaleStartTextSize + " currentPx=" + currentTextSize +
            " samples=" + mScaleSampleCount + " qualified=" + mScaleGestureQualified +
            " persisted=false");
    }

    private void applyRealtimeTextSize(int textSize, float focusX, float focusY) {
        int oldTopRow = mTopRow;
        int oldScreenRows = mEmulator.mRows;
        int oldTranscriptRows = mEmulator.getActiveTranscriptRows();
        int oldLineSpacingAndAscent = mRenderer.mFontLineSpacingAndAscent;
        float oldLineHeight = Math.max(1f, mRenderer.mFontLineSpacing);
        float oldViewportRow = TerminalPinchViewportAnchor.continuousViewportRow(
            focusY, oldLineSpacingAndAscent, oldLineHeight, mViewportPixelOffset);
        int anchorViewportRow = TerminalPinchViewportAnchor.trackedCellRow(
            oldViewportRow, oldScreenRows);
        float anchorCellFraction = TerminalPinchViewportAnchor.cellFraction(
            oldViewportRow, anchorViewportRow);
        int anchorColumn = TerminalPinchViewportAnchor.cellColumn(
            focusX, mRenderer.mFontWidth, mEmulator.mColumns);
        float logicalAnchorRow = oldTopRow + oldViewportRow;

        long stageStarted = System.nanoTime();
        mRenderer.reconfigure(textSize, mRenderer.mTypeface);
        long metricNanos = System.nanoTime() - stageStarted;
        mScaleMetricNanos += metricNanos;
        mScaleMetricMaxNanos = Math.max(mScaleMetricMaxNanos, metricNanos);

        int targetViewportRow = TerminalPinchViewportAnchor.targetCellRow(
            focusY, mRenderer.mFontLineSpacingAndAscent,
            mRenderer.mFontLineSpacing, Math.max(4,
                (getHeight() - mRenderer.mFontLineSpacingAndAscent) /
                    Math.max(1, mRenderer.mFontLineSpacing)));
        float targetPixelOffset = TerminalPinchViewportAnchor.targetPixelOffset(
            focusY, mRenderer.mFontLineSpacingAndAscent, mRenderer.mFontLineSpacing,
            targetViewportRow, anchorCellFraction);

        stageStarted = System.nanoTime();
        boolean resized = updateSizeImmediately(true,
            mScaleGesturePinnedToLiveEdge ? 0 : oldTopRow,
            mScaleGesturePinnedToLiveEdge ? -1 : anchorColumn,
            mScaleGesturePinnedToLiveEdge ? -1 : anchorViewportRow,
            mScaleGesturePinnedToLiveEdge ? -1 : targetViewportRow);
        long resizeNanos = System.nanoTime() - stageStarted;
        mScaleResizeNanos += resizeNanos;
        mScaleResizeMaxNanos = Math.max(mScaleResizeMaxNanos, resizeNanos);

        stageStarted = System.nanoTime();
        if (mEmulator != null) {
            float newLineHeight = Math.max(1f, mRenderer.mFontLineSpacing);
            int anchorOutcome = resized ? mEmulator.getGhosttyResizeAnchorOutcome() : 0;
            if (mScaleGesturePinnedToLiveEdge) {
                mTopRow = 0;
                mViewportPixelOffset = 0f;
                mScrollRemainder = 0f;
                mScaleLiveEdgePinCount++;
            } else if (mEmulator.isGhosttyRenderAuthorityActive()) {
                commitNativeAnchoredViewport(resized
                        ? mEmulator.getGhosttyViewportTopRow() : oldTopRow,
                    targetPixelOffset, newLineHeight);
                if (!resized || anchorOutcome == 1) {
                    mScaleAnchorExactCount++;
                } else if (anchorOutcome == 2) {
                    mScaleAnchorClampedCount++;
                } else {
                    // Native already committed a bounded same-relative-position fallback when the
                    // tracked cell was legitimately discarded by resize. Keep that integer row;
                    // never run the old proportional formula on top of it.
                    mScaleAnchorUnavailableCount++;
                }
            } else {
                float reflowedAnchorRow = logicalAnchorRow +
                    (mEmulator.mRows - oldScreenRows);
                float anchoredTop = reflowedAnchorRow -
                    (focusY - mRenderer.mFontLineSpacingAndAscent) / newLineHeight;
                setViewportPositionPixels(anchoredTop * newLineHeight, false);
                mScaleAnchorUnavailableCount++;
            }
            Log.d(LOG_TAG, "pinch-anchor-v4 outcome=" + anchorOutcome +
                " top=" + oldTopRow + "->" + mTopRow +
                " scrollback=" + oldTranscriptRows + "->" +
                    mEmulator.getActiveTranscriptRows() +
                " cell=" + anchorColumn + ',' + anchorViewportRow + "->" +
                    targetViewportRow + " offset=" + Math.round(mViewportPixelOffset) +
                " viewportPolicy=" + (mScaleGesturePinnedToLiveEdge
                    ? "inline-tui-live-edge" : "tracked-cell-universal") +
                " alternate=" + mEmulator.isAlternateBufferActive() + " native={" +
                    mEmulator.getGhosttyResizeAnchorStatusForDiagnostics() + '}');
        }
        scrollTo(0, 0);
        requestFullRenderFrame();
        long anchorNanos = System.nanoTime() - stageStarted;
        mScaleAnchorNanos += anchorNanos;
        mScaleAnchorMaxNanos = Math.max(mScaleAnchorMaxNanos, anchorNanos);
        mScaleReflowCount++;
    }

    /**
     * Commit the native tracked-cell row and its sub-row visual fraction as one viewport. This
     * intentionally bypasses TerminalViewportPosition.resolve(): that helper is correct for finger
     * scrolling, but its directional floor/ceil representation may change the integer top row and
     * would therefore invalidate the native resize anchor on the next render packet.
     */
    private void commitNativeAnchoredViewport(int nativeTopRow, float requestedPixelOffset,
                                              float lineHeight) {
        int transcriptRows = mEmulator == null ? 0 : mEmulator.getActiveTranscriptRows();
        mTopRow = Math.max(-transcriptRows, Math.min(0, nativeTopRow));
        mViewportPixelOffset = TerminalPinchViewportAnchor.committedPixelOffset(
            mTopRow, transcriptRows, lineHeight, requestedPixelOffset);
        mScrollRemainder = 0f;
        awakenScrollBars();
    }

    private long averageScaleMicros(long nanos) {
        return mScaleReflowCount <= 0 ? 0L : nanos / mScaleReflowCount / 1000L;
    }

    private static long nanosToMicros(long nanos) {
        return nanos / 1000L;
    }

    private void beginScaleGlyphWarmupAfterPresentedFrame() {
        if (!mScaleGlyphWarmupPending || mScaleGestureActive || mRenderer == null) return;
        mScaleGlyphWarmupPending = false;
        mGlyphWarmupFromIdle = false;
        mIdleGlyphWarmActiveGeneration = Long.MIN_VALUE;
        mScaleGlyphWarmCandidates = mRenderer.beginScaleGlyphWarmup();
        if (mScaleGlyphWarmCandidates > 0) scheduleScaleGlyphWarmFrame();
    }

    private void armIdleGlyphWarmupAfterPresentedFrame() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || mRenderer == null ||
            mEmulator == null || !mEmulator.isGhosttyRenderAuthorityActive() ||
            mScaleGestureActive || mScaleCommitPending || mScaleGlyphWarmupPending ||
            mScaleGlyphWarmFrameScheduled || mFingerScrollTracker.isActive() ||
            !mScroller.isFinished()) {
            return;
        }
        long generation = mRenderer.getGhosttyRetainedCommandGeneration();
        if (generation == mIdleGlyphWarmCompletedGeneration) return;
        if (generation != mIdleGlyphWarmCandidateGeneration) {
            mIdleGlyphWarmCandidateGeneration = generation;
            mIdleGlyphWarmStableFrames = 0;
        }
        scheduleIdleGlyphWarmCheck();
    }

    private void scheduleIdleGlyphWarmCheck() {
        if (mIdleGlyphWarmCheckScheduled) return;
        mIdleGlyphWarmCheckScheduled = true;
        postOnAnimation(() -> {
            mIdleGlyphWarmCheckScheduled = false;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                mRenderer == null || mEmulator == null || !isAttachedToWindow() ||
                !isShown() || !mEmulator.isGhosttyRenderAuthorityActive() ||
                mScaleGestureActive || mScaleCommitPending || mScaleGlyphWarmupPending ||
                mScaleGlyphWarmFrameScheduled || mFingerScrollTracker.isActive() ||
                !mScroller.isFinished()) {
                mIdleGlyphWarmStableFrames = 0;
                return;
            }
            long generation = mRenderer.getGhosttyRetainedCommandGeneration();
            if (generation != mIdleGlyphWarmCandidateGeneration ||
                !mRenderer.hasCompleteGhosttyFrame(mTopRow, mEmulator.mRows)) {
                mIdleGlyphWarmCandidateGeneration = generation;
                mIdleGlyphWarmStableFrames = 0;
                return;
            }
            if (mFrameInvalidationScheduled) {
                mIdleGlyphWarmStableFrames = 0;
                return;
            }
            if (++mIdleGlyphWarmStableFrames < IDLE_GLYPH_WARM_STABLE_FRAME_COUNT) {
                scheduleIdleGlyphWarmCheck();
                return;
            }

            mGlyphWarmupFromIdle = true;
            mIdleGlyphWarmActiveGeneration = generation;
            mScaleGlyphWarmCandidates = mRenderer.beginScaleGlyphWarmup();
            mScaleGlyphWarmFrames = 0;
            mScaleGlyphWarmNanos = 0L;
            if (mScaleGlyphWarmCandidates > 0) {
                scheduleScaleGlyphWarmFrame();
            } else {
                mIdleGlyphWarmCompletedGeneration = generation;
                mGlyphWarmupFromIdle = false;
                mIdleGlyphWarmActiveGeneration = Long.MIN_VALUE;
            }
        });
    }

    private void scheduleScaleGlyphWarmFrame() {
        if (mScaleGlyphWarmFrameScheduled) return;
        mScaleGlyphWarmFrameScheduled = true;
        postOnAnimation(() -> {
            mScaleGlyphWarmFrameScheduled = false;
            if (mScaleGestureActive || mRenderer == null || !isAttachedToWindow()) {
                mGlyphWarmupFromIdle = false;
                mIdleGlyphWarmActiveGeneration = Long.MIN_VALUE;
                return;
            }
            long started = System.nanoTime();
            int remaining = mRenderer.warmScaleGlyphCache(SCALE_GLYPH_WARM_RUNS_PER_FRAME);
            mScaleGlyphWarmNanos += System.nanoTime() - started;
            mScaleGlyphWarmFrames++;
            if (remaining > 0) {
                scheduleScaleGlyphWarmFrame();
            } else {
                boolean wasIdleWarmup = mGlyphWarmupFromIdle;
                long warmupGeneration = wasIdleWarmup
                    ? mIdleGlyphWarmActiveGeneration
                    : mRenderer.getGhosttyRetainedCommandGeneration();
                boolean idleWarmupCommitted = wasIdleWarmup &&
                    mIdleGlyphWarmActiveGeneration ==
                        mRenderer.getGhosttyRetainedCommandGeneration();
                if (idleWarmupCommitted) {
                    mIdleGlyphWarmCompletedGeneration = mIdleGlyphWarmActiveGeneration;
                }
                Log.i(LOG_TAG, (wasIdleWarmup ? "idle" : "pinch") +
                    "-glyph-warm-v2 candidates=" +
                    mScaleGlyphWarmCandidates + " prepared=" +
                    mRenderer.getScaleGlyphWarmPrepared() + " frames=" +
                    mScaleGlyphWarmFrames + " elapsedUs=" +
                    nanosToMicros(mScaleGlyphWarmNanos) +
                    " budgetRuns=" + SCALE_GLYPH_WARM_RUNS_PER_FRAME +
                    " generation=" + warmupGeneration +
                    (wasIdleWarmup ? " committed=" + idleWarmupCommitted : "") +
                    " pixelsUnchanged=true");
                mGlyphWarmupFromIdle = false;
                mIdleGlyphWarmActiveGeneration = Long.MIN_VALUE;
            }
        });
    }

    public void setTypeface(Typeface newTypeface) {
        if (mRenderer != null && mRenderer.mTypeface == newTypeface) return;
        if (mRenderer == null) {
            mRenderer = new TerminalRenderer(14, newTypeface);
        } else {
            mRenderer.reconfigure(mRenderer.mTextSize, newTypeface);
        }
        resetVulkanSubmissionState();
        requestTerminalGeometry(TerminalGeometryCommitPolicy.Source.USER_TEXT_SCALE,
            false, 0, -1, -1, -1);
        invalidate();
        publishVulkanFrameNow(true);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public boolean isOpaque() {
        return !isVulkanFrameActive();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (mEmulator != null) {
            mEmulator.onHostWindowFocusChanged(hasWindowFocus);
        }
    }

    /**
     * Get the zero indexed column and row of the terminal view for the
     * position of the event.
     *
     * @param event The event with the position to get the column and row for.
     * @param relativeToScroll If true the column number will take the scroll
     * position into account. E.g. if scrolled 3 lines up and the event
     * position is in the top left, column will be -3 if relativeToScroll is
     * true and 0 if relativeToScroll is false.
     * @return Array with the column and row.
     */
    public int[] getColumnAndRow(MotionEvent event, boolean relativeToScroll) {
        int column = (int) (event.getX() / mRenderer.mFontWidth);
        int row = (int) ((event.getY() + mViewportPixelOffset -
            mRenderer.mFontLineSpacingAndAscent) / mRenderer.mFontLineSpacing);
        if (relativeToScroll) {
            row += mTopRow;
        }
        return new int[] { column, row };
    }

    /** Send a single mouse event code to the terminal. */
    void sendMouseEventCode(MotionEvent e, int button, boolean pressed) {
        int[] columnAndRow = getColumnAndRow(e, false);
        int x = columnAndRow[0] + 1;
        int y = columnAndRow[1] + 1;
        if (pressed && (button == TerminalEmulator.MOUSE_WHEELDOWN_BUTTON || button == TerminalEmulator.MOUSE_WHEELUP_BUTTON)) {
            if (mMouseStartDownTime == e.getDownTime()) {
                x = mMouseScrollStartX;
                y = mMouseScrollStartY;
            } else {
                mMouseStartDownTime = e.getDownTime();
                mMouseScrollStartX = x;
                mMouseScrollStartY = y;
            }
        }
        mEmulator.sendMouseEvent(button, x, y, pressed);
    }

    /** Touch taps use an independent policy from touch scrolling. */
    private boolean shouldUseMouseTrackingForTouchTap() {
        return mEmulator != null &&
            TerminalTouchInputPolicy.shouldSendMouseClick(
                mEmulator.isMouseTrackingActive(),
                mClient == null || mClient.shouldSendMouseClickEventsForTouchTap(mTermSession));
    }

    private TerminalTouchInputPolicy.ScrollRoute resolveTouchScrollRoute() {
        if (mEmulator == null) return TerminalTouchInputPolicy.ScrollRoute.LOCAL_VIEWPORT;
        boolean shouldSendMouseWheel = mClient == null ||
            mClient.shouldSendMouseWheelEventsForTouchScroll(mTermSession);
        return TerminalTouchInputPolicy.resolveScrollRoute(
            mEmulator.isMouseTrackingActive(), shouldSendMouseWheel,
            mEmulator.getActiveTranscriptRows());
    }

    private boolean shouldUseMouseTrackingForTouchScroll() {
        return resolveTouchScrollRoute() ==
            TerminalTouchInputPolicy.ScrollRoute.REMOTE_MOUSE_WHEEL;
    }

    private boolean isSmoothGhosttyLocalScroll() {
        if (mEmulator == null || mRenderer == null ||
            !mEmulator.isGhosttyRenderAuthorityActive()) return false;
        return resolveTouchScrollRoute() ==
            TerminalTouchInputPolicy.ScrollRoute.LOCAL_VIEWPORT;
    }

    private float getViewportPositionPixels() {
        if (mRenderer == null) return 0f;
        return mTopRow * (float) mRenderer.mFontLineSpacing + mViewportPixelOffset;
    }

    private void setViewportPositionPixels(float position) {
        setViewportPositionPixels(position, true);
    }

    private void setViewportPositionPixels(float position, boolean publishImmediately) {
        if (mEmulator == null || mRenderer == null) return;
        float lineHeight = Math.max(1f, mRenderer.mFontLineSpacing);
        float previousPosition = getViewportPositionPixels();
        TerminalViewportPosition.resolve(position, mEmulator.getActiveTranscriptRows(), lineHeight,
            previousPosition, mTopRow, mViewportPixelOffset, mResolvedViewportPosition);
        int topRow = mResolvedViewportPosition.topRow;
        float offset = mResolvedViewportPosition.pixelOffset;
        if (topRow == mTopRow && Math.abs(offset - mViewportPixelOffset) < 0.01f) return;
        mTopRow = topRow;
        mViewportPixelOffset = offset;
        mScrollRemainder = 0f;
        awakenScrollBars();
        if (publishImmediately) publishViewportRenderFrameNow();
    }

    /** Output can move history while an absolute touch anchor or fling is still active. */
    private void rebaseActiveLocalScrollAfterOutput(float viewportPositionBeforeOutputShift) {
        if (mRenderer == null) return;
        float viewportDelta = getViewportPositionPixels() - viewportPositionBeforeOutputShift;
        if (Math.abs(viewportDelta) < 0.01f) return;
        if (mFingerScrollTracker.isActive()) mFingerScrollTracker.rebase(viewportDelta);
        // Scroller has no supported way to rebase its internal coordinate system mid-flight.
        if (isSmoothGhosttyLocalScroll() && !mScroller.isFinished()) mScroller.abortAnimation();
    }

    /** Force a complete retained-frame transaction before this terminal can become visible. */
    public void requestFullRenderFrame() {
        if (mRenderer != null) mRenderer.requestFullFrame();
        mPendingFullInvalidation = true;
        if (mEmulator == null) updateSize();
        if (mEmulator != null && getWidth() > 0) {
            scheduleRenderFrame(false);
        } else {
            invalidate();
        }
    }

    /**
     * Ensures that one authoritative terminal frame is handed to the IME camera.
     *
     * <p>Opening the keyboard or returning from history creates a new focus transaction even when
     * terminal pixels did not change. The request generation therefore forces one callback for an
     * already-ready frame. When a frame is pending, use the retained-delta path first and request a
     * full frame only when that cache cannot establish a current presentation.</p>
     */
    @MainThread
    public void requestImeCameraFrame() {
        mImeCameraFrameRequests++;
        mImeCameraFrameRequestPolicy.request();

        ImeCameraSnapshot snapshot = getImeCameraSnapshot();
        if (snapshot.availability == ImeCameraSnapshot.Availability.READY) {
            mImeCameraFrameImmediateNotifications++;
            notifyImeCameraFrameReady();
            return;
        }
        if (snapshot.availability == ImeCameraSnapshot.Availability.HISTORY_OWNED) return;

        boolean prewarmed = mEmulator != null && mRenderer != null &&
            mEmulator.isGhosttyRenderAuthorityActive() && prewarmRenderFrame(false);
        if (prewarmed) {
            mImeCameraFrameDeltaPrewarms++;
            // The retained cache now contains the frame, but Canvas still needs a traversal and
            // Vulkan still needs one UI-thread presentation callback before it is camera-safe.
            scheduleRenderFrame(false);
            return;
        }

        mImeCameraFrameFullFallbacks++;
        requestFullRenderFrame();
    }

    /** True when a retained frame can be brought current without rebuilding every row. */
    public boolean hasCompleteRenderFrame() {
        if (!hasCurrentTerminalGeometry()) return false;
        return !mEmulator.isGhosttyRenderAuthorityActive() ||
            mRenderer.hasCompleteGhosttyFrame(mTopRow, mEmulator.mRows);
    }

    /** True only when PTY rows/columns and cell metrics match the View's current pixel bounds. */
    public boolean hasCurrentTerminalGeometryForDiagnostics() {
        return hasCurrentTerminalGeometry();
    }

    /** Immutable result consumed by the host's per-tab IME focus camera. */
    public static final class ImeCameraSnapshot {
        public enum Availability {
            READY,
            FRAME_PENDING,
            HISTORY_OWNED
        }

        @NonNull public final Availability availability;
        public final int cursorTopPx;
        public final int cursorBottomPx;
        /** Bottom of the frame-committed semantic footer/status envelope, never above cursor. */
        public final int protectedBottomPx;
        public final long contentRevision;
        public final long presentedRevision;

        private ImeCameraSnapshot(@NonNull Availability availability, int cursorTopPx,
                                  int cursorBottomPx, int protectedBottomPx, long contentRevision,
                                  long presentedRevision) {
            this.availability = availability;
            this.cursorTopPx = cursorTopPx;
            this.cursorBottomPx = cursorBottomPx;
            this.protectedBottomPx = protectedBottomPx;
            this.contentRevision = contentRevision;
            this.presentedRevision = presentedRevision;
        }
    }

    /**
     * Returns the cursor rectangle only when it belongs to the frame currently on screen.
     *
     * <p>History is an explicit ownership state, not a missing-anchor error. A frame that is being
     * rebuilt is likewise distinguished from history so the surface can hold its last committed
     * transform instead of jumping to zero. The cursor remains the only focus authority; semantic
     * footer/status rows from that same committed frame extend only its occlusion envelope.</p>
     */
    @NonNull
    public ImeCameraSnapshot getImeCameraSnapshot() {
        long contentRevision = mEmulator == null
            ? Long.MIN_VALUE : mEmulator.getContentRevision();
        if (mEmulator != null && (mTopRow != 0 ||
            Math.abs(mViewportPixelOffset) >= 0.01f ||
            mEmulator.isAutoScrollDisabled())) {
            return new ImeCameraSnapshot(ImeCameraSnapshot.Availability.HISTORY_OWNED,
                -1, -1, -1, contentRevision, mLastPresentedContentRevision);
        }
        if (mEmulator == null || mRenderer == null || getHeight() <= 0 ||
            !hasCurrentTerminalGeometry()) {
            return new ImeCameraSnapshot(ImeCameraSnapshot.Availability.FRAME_PENDING,
                -1, -1, -1, contentRevision, mLastPresentedContentRevision);
        }

        boolean frameGeometryCurrent = mLastPresentedTopRow == mTopRow &&
            mLastPresentedViewportOffsetBits == Float.floatToIntBits(mViewportPixelOffset) &&
            mLastPresentedViewHeight == getHeight() &&
            mLastPresentedFontLineSpacing == mRenderer.mFontLineSpacing &&
            mLastPresentedFontLineSpacingAndAscent == mRenderer.mFontLineSpacingAndAscent &&
            mLastPresentedContentRevision != Long.MIN_VALUE;
        int screenRow = mLastRenderedCursorRow - mLastPresentedTopRow;
        if (!frameGeometryCurrent || !mLastPresentedCursorEnabled ||
            screenRow < 0 || screenRow >= mEmulator.mRows) {
            return new ImeCameraSnapshot(ImeCameraSnapshot.Availability.FRAME_PENDING,
                -1, -1, -1, contentRevision, mLastPresentedContentRevision);
        }
        int protectedScreenRow = mLastPresentedImeProtectedBottomScreenRow;
        if (protectedScreenRow < screenRow || protectedScreenRow >= mEmulator.mRows) {
            protectedScreenRow = screenRow;
        }
        return new ImeCameraSnapshot(ImeCameraSnapshot.Availability.READY,
            getImeScreenRowTopPx(screenRow), getImeScreenRowBottomPx(screenRow),
            getImeScreenRowBottomPx(protectedScreenRow), contentRevision,
            mLastPresentedContentRevision);
    }

    /** Compatibility diagnostic accessor for the bottom of the current cursor row. */
    public int getImeCursorAnchorBottomPx() {
        ImeCameraSnapshot snapshot = getImeCameraSnapshot();
        return snapshot.availability == ImeCameraSnapshot.Availability.READY
            ? snapshot.cursorBottomPx : -1;
    }

    /**
     * Returns the bottom of the semantic input envelope from the currently presented frame.
     *
     * <p>The envelope may include a TUI footer or tmux status line below the cursor. It is not
     * recomputed from a newer emulator/cache state, so an IME caller can never combine old pixels
     * with a future tail position.</p>
     */
    public int getImeContentAnchorBottomPx() {
        ImeCameraSnapshot snapshot = getImeCameraSnapshot();
        return snapshot.availability == ImeCameraSnapshot.Availability.READY
            ? snapshot.protectedBottomPx : -1;
    }

    private int getImeScreenRowBottomPx(int screenRow) {
        float rowBottom = mRenderer.mFontLineSpacingAndAscent +
            (screenRow + 1f) * mRenderer.mFontLineSpacing - mViewportPixelOffset;
        return Math.max(1, Math.min(getHeight(), (int) Math.ceil(rowBottom)));
    }

    private int getImeScreenRowTopPx(int screenRow) {
        float rowTop = mRenderer.mFontLineSpacingAndAscent +
            screenRow * mRenderer.mFontLineSpacing - mViewportPixelOffset;
        return Math.max(0, Math.min(getHeight() - 1, (int) Math.floor(rowTop)));
    }

    private boolean hasCurrentTerminalGeometry() {
        if (mEmulator == null || mRenderer == null || getWidth() <= 0 || getHeight() <= 0) {
            return false;
        }
        return mGeometryCommitPolicy.matchesCommitted(mEmulator.mColumns, mEmulator.mRows,
            mLastSentCellWidth, mLastSentCellHeight);
    }

    /** Compact state used to verify that a committed page and its retained cache share a viewport. */
    public String getRenderDiagnostics() {
        int columns = mEmulator == null ? -1 : mEmulator.mColumns;
        int rows = mEmulator == null ? -1 : mEmulator.mRows;
        int expectedColumns = mRenderer == null || getWidth() <= 0
            ? -1 : expectedTerminalColumns();
        int expectedRows = mRenderer == null || getHeight() <= 0
            ? -1 : expectedTerminalRows();
        TerminalGeometryCommitPolicy.Geometry committed = mGeometryCommitPolicy.getCommitted();
        String renderer = mRenderer == null ? "renderer=none" :
            mRenderer.getGhosttyRenderDiagnostics();
        String vulkan = mVulkanView == null ? "vulkan=absent" :
            "vulkan={" + mVulkanView.getDiagnostics() + '}';
        String ptyResize = mTermSession == null ? "ptyResize=none" :
            "ptyResize={transactions=" +
                mTermSession.getResizeTransactionsForDiagnostics() + " ioctls=" +
                mTermSession.getPtyWindowSizeRequestsForDiagnostics() + " deduped=" +
                mTermSession.getRedundantResizeRequestsSuppressedForDiagnostics() + '}';
        return "view=" + System.identityHashCode(this) + " size=" + getWidth() + 'x' +
            getHeight() + " grid=" + columns + 'x' + rows + " observedGrid=" +
            expectedColumns + 'x' + expectedRows + " committedGrid=" +
            (committed == null ? "none" : committed.columns + "x" + committed.rows) +
            " geometry=" +
            hasCurrentTerminalGeometry() + " top=" + mTopRow +
            " offset=" + Math.round(mViewportPixelOffset) + " complete=" +
            hasCompleteRenderFrame() + " publish=" + mRenderFrameCallbacks + '/' +
            mRenderFrameRequests + " coalesced=" + mRenderFrameCoalesced +
            " presented=" + mPresentedFrames + " skipped=" + mSkippedFramePresentations +
            " revision=" +
            mLastPresentedContentRevision + " gpuPresented=" + mGpuPresentedFrames +
            " imeFrameRequests=" + mImeCameraFrameRequests + '/' +
            mImeCameraFrameDeltaPrewarms + '/' + mImeCameraFrameFullFallbacks + '/' +
            mImeCameraFrameImmediateNotifications + " imeFrameGeneration=" +
            mImeCameraFrameRequestPolicy.getRequestedGeneration() + '/' +
            mImeCameraFrameRequestPolicy.getNotifiedGeneration() +
            " geometryDeferred=" + mGeometryDeferredCount +
            " geometryCommitted=" + mGeometryCommittedCount +
            " inputLiveEdgeRestores=" + mUserInputLiveEdgeRestores + ' ' +
            " geometryPolicy={" + mGeometryCommitPolicy.getDiagnostics() + "} " +
            ptyResize + ' ' + vulkan + ' ' + renderer;
    }

    public boolean isVulkanRendererExpectedForDiagnostics() {
        return mVulkanView != null && mVulkanView.isHardwareSupportedForDiagnostics();
    }

    public boolean isVulkanFrameReadyForDiagnostics() {
        return isVulkanFrameActive();
    }

    public boolean hasVulkanRendererFailedForDiagnostics() {
        return mVulkanView != null &&
            (mVulkanFailed || mVulkanView.hasPermanentlyFailedForDiagnostics());
    }

    public long getVulkanPresentedFrameCountForDiagnostics() {
        return mVulkanView == null ? 0L : mVulkanView.getPresentedFrameCountForDiagnostics();
    }

    /**
     * Materialize a Ghostty retained frame without depending on this view's next draw traversal.
     * This is used by pager prewarming while a terminal page is clipped outside the viewport.
     */
    public boolean prewarmRenderFrame() {
        return prewarmRenderFrame(true);
    }

    /** Incrementally update a retained Ghostty frame while this view remains offscreen. */
    public boolean prewarmRenderDelta() {
        return prewarmRenderFrame(false);
    }

    private boolean prewarmRenderFrame(boolean forceFull) {
        if (mEmulator == null) updateSize();
        if (mEmulator == null || mRenderer == null || getWidth() <= 0 || getHeight() <= 0) {
            return false;
        }
        int[] sel = mDefaultSelectors;
        if (mTextSelectionCursorController != null) {
            mTextSelectionCursorController.getSelectors(sel);
        }
        boolean prepared = mRenderer.prewarmGhosttyFrame(mEmulator, mTopRow,
            mViewportPixelOffset, forceFull,
            sel[0], sel[1], sel[2], sel[3]);
        if (prepared) publishVulkanFrameNow(forceFull);
        return prepared;
    }

    private void scrollViewportByPixels(float pixels) {
        if (pixels == 0f) return;
        setViewportPositionPixels(getViewportPositionPixels() + pixels);
    }

    private void startSmoothGhosttyFling(float velocityY) {
        if (mEmulator == null || mRenderer == null) return;
        int start = Math.round(getViewportPositionPixels());
        long minimumLong = -(long) mEmulator.getActiveTranscriptRows() *
            Math.max(1, mRenderer.mFontLineSpacing);
        int minimum = (int) Math.max(Integer.MIN_VALUE + 1L, minimumLong);
        mScroller.fling(0, start, 0, -(int) velocityY,
            0, 0, minimum, 0);
        postOnAnimation(new Runnable() {
            @Override
            public void run() {
                if (!isSmoothGhosttyLocalScroll() || mScroller.isFinished()) return;
                boolean more = mScroller.computeScrollOffset();
                setViewportPositionPixels(mScroller.getCurrY());
                if (more) postOnAnimation(this);
            }
        });
    }

    /** Perform a scroll, either from dragging the screen or by scrolling a mouse wheel. */
    void doScroll(MotionEvent event, int rowsDown) {
        boolean up = rowsDown < 0;
        int amount = Math.abs(rowsDown);
        TerminalTouchInputPolicy.ScrollRoute scrollRoute = resolveTouchScrollRoute();

        if (scrollRoute == TerminalTouchInputPolicy.ScrollRoute.LOCAL_VIEWPORT) {
            if (isSmoothGhosttyLocalScroll()) {
                scrollViewportByPixels(rowsDown * (float) mRenderer.mFontLineSpacing);
                return;
            }
            int activeTranscriptRows = mEmulator.getActiveTranscriptRows();
            int delta = up ? -amount : amount;
            int nextTopRow = Math.min(0, Math.max(-activeTranscriptRows, mTopRow + delta));
            if (nextTopRow != mTopRow) {
                mTopRow = nextTopRow;
                mViewportPixelOffset = 0f;
                if (!awakenScrollBars()) scheduleRenderFrame(false);
            }
            return;
        }

        for (int i = 0; i < amount; i++) {
            if (scrollRoute == TerminalTouchInputPolicy.ScrollRoute.REMOTE_MOUSE_WHEEL) {
                sendMouseEventCode(event, up ? TerminalEmulator.MOUSE_WHEELUP_BUTTON : TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, true);
            }
        }
    }

    private void scheduleRenderFrame(boolean updateAccessibilityDescription) {
        scheduleRenderFrame(updateAccessibilityDescription, -1, -1);
    }

    private void scheduleRenderFrame(boolean updateAccessibilityDescription, int screenRowStart, int screenRowEndExclusive) {
        mRenderFrameRequests++;
        publishVulkanFrameNow(screenRowStart < 0 || screenRowEndExclusive <= screenRowStart);
        if (updateAccessibilityDescription && mAccessibilityEnabled) {
            mAccessibilityContentDescriptionDirty = true;
        }

        if (screenRowStart < 0 || screenRowEndExclusive <= screenRowStart ||
            mRenderer == null || mEmulator == null || getWidth() <= 0) {
            mPendingFullInvalidation = true;
        } else {
            screenRowStart = Math.max(0, screenRowStart);
            screenRowEndExclusive = Math.min(mEmulator.mRows, screenRowEndExclusive);
            if (screenRowEndExclusive <= screenRowStart) return;
            if (screenRowStart == 0 && screenRowEndExclusive == mEmulator.mRows) {
                mPendingFullInvalidation = true;
            } else if (!mPendingFullInvalidation) {
                int top = getScreenRowTopPx(screenRowStart);
                int bottom = getScreenRowBottomPx(screenRowEndExclusive);
                if (top < mPendingInvalidateTop) mPendingInvalidateTop = top;
                if (bottom > mPendingInvalidateBottom) mPendingInvalidateBottom = bottom;
            }
        }

        if (mFrameInvalidationScheduled) {
            mRenderFrameCoalesced++;
            return;
        }

        mFrameInvalidationScheduled = true;
        mFrameScheduledNanos = System.nanoTime();
        postOnAnimation(() -> {
            mFrameInvalidationScheduled = false;
            mRenderFrameCallbacks++;
            long scheduleMicros = Math.max(0L,
                (System.nanoTime() - mFrameScheduledNanos) / 1000L);
            if (scheduleMicros > mMaxScheduleLatencyMicros) {
                mMaxScheduleLatencyMicros = scheduleMicros;
            }
            if (mPendingFullInvalidation || mPendingInvalidateTop >= mPendingInvalidateBottom) {
                invalidate();
            } else {
                invalidate(0, mPendingInvalidateTop, getWidth(), mPendingInvalidateBottom);
            }
            mPendingFullInvalidation = false;
            mPendingInvalidateTop = Integer.MAX_VALUE;
            mPendingInvalidateBottom = Integer.MIN_VALUE;
            if (mAccessibilityEnabled && mAccessibilityContentDescriptionDirty) {
                mAccessibilityContentDescriptionDirty = false;
                setContentDescription(getText());
            }
        });
    }

    /** Native screen publication already runs in Choreographer's animation phase. */
    private void publishGhosttyRenderFrameNow(boolean updateAccessibilityDescription,
                                              int screenRowStart,
                                              int screenRowEndExclusive) {
        mRenderFrameRequests++;
        mRenderFrameCallbacks++;
        mImmediateGhosttyInvalidations++;
        boolean full = screenRowStart < 0 || screenRowEndExclusive <= screenRowStart ||
            mRenderer == null || mEmulator == null || getWidth() <= 0;
        publishVulkanFrameNow(full);
        if (full) {
            invalidate();
        } else {
            int start = Math.max(0, screenRowStart);
            int end = Math.min(mEmulator.mRows, screenRowEndExclusive);
            if (start >= end) return;
            if (start == 0 && end == mEmulator.mRows) {
                invalidate();
            } else {
                mPartialGhosttyInvalidations++;
                invalidate(0, getScreenRowTopPx(start), getWidth(), getScreenRowBottomPx(end));
            }
        }
        if (updateAccessibilityDescription && mAccessibilityEnabled) {
            setContentDescription(getText());
        }
    }

    /** Viewport motion is already on the UI/animation timeline; do not add another VSync hop. */
    private void publishViewportRenderFrameNow() {
        mRenderFrameRequests++;
        mImmediateViewportInvalidations++;
        notifyVisualViewportAnchorChanged();
        publishVulkanFrameNow(false);
        invalidate();
    }

    private void scheduleCursorRenderFrame() {
        if (mEmulator == null) return;

        int dirtyStart = Integer.MAX_VALUE;
        int dirtyEnd = Integer.MIN_VALUE;
        if (mLastRenderedCursorVisible && mLastRenderedCursorRow >= 0) {
            dirtyStart = Math.min(dirtyStart, mLastRenderedCursorRow);
            dirtyEnd = Math.max(dirtyEnd, mLastRenderedCursorRow + 1);
        }
        if (mEmulator.shouldCursorBeVisible()) {
            int cursorRow = mEmulator.getCursorRow();
            dirtyStart = Math.min(dirtyStart, cursorRow);
            dirtyEnd = Math.max(dirtyEnd, cursorRow + 1);
        }
        if (dirtyStart >= dirtyEnd) return;

        int overlapStart = Math.max(dirtyStart, mTopRow);
        int overlapEnd = Math.min(dirtyEnd, mTopRow + mEmulator.mRows);
        if (overlapStart < overlapEnd) {
            scheduleRenderFrame(false, overlapStart - mTopRow, overlapEnd - mTopRow);
        }
    }

    private int getScreenRowTopPx(int screenRow) {
        return Math.max(0, mRenderer.mFontLineSpacingAndAscent +
            screenRow * mRenderer.mFontLineSpacing);
    }

    private int getScreenRowBottomPx(int screenRowExclusive) {
        return Math.min(getHeight(), mRenderer.mFontLineSpacingAndAscent +
            screenRowExclusive * mRenderer.mFontLineSpacing);
    }

    /** Overriding {@link View#onGenericMotionEvent(MotionEvent)}. */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (mEmulator != null && event.isFromSource(InputDevice.SOURCE_MOUSE) && event.getAction() == MotionEvent.ACTION_SCROLL) {
            // Handle mouse wheel scrolling.
            boolean up = event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0.0f;
            doScroll(event, up ? -1 : 1);
            return true;
        }
        return false;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    @TargetApi(23)
    public boolean onTouchEvent(MotionEvent event) {
        if (mEmulator == null) return true;
        final int action = event.getActionMasked();
        final boolean touchInput = !event.isFromSource(InputDevice.SOURCE_MOUSE);
        if (touchInput) {
            if (action == MotionEvent.ACTION_DOWN && mMultiTouchSequenceCaptured) {
                finishMultiTouchSequence();
            } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                captureMultiTouchSequence();
            }
        }

        if (isSelectingText()) {
            updateFloatingToolbarVisibility(event);
            mGestureRecognizer.onTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                finishDirectFingerScroll("selection");
                finishMultiTouchSequence();
            }
            return true;
        } else if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                if (action == MotionEvent.ACTION_DOWN) showContextMenu();
                return true;
            } else if (event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) {
                ClipboardManager clipboardManager = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clipData = clipboardManager.getPrimaryClip();
                if (clipData != null) {
                    ClipData.Item clipItem = clipData.getItemAt(0);
                    if (clipItem != null) {
                        CharSequence text = clipItem.coerceToText(getContext());
                        if (!TextUtils.isEmpty(text)) {
                            prepareForUserInput();
                            mEmulator.paste(text.toString());
                        }
                    }
                }
            } else if (mEmulator.isMouseTrackingActive()) { // BUTTON_PRIMARY.
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_UP:
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, event.getAction() == MotionEvent.ACTION_DOWN);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true);
                        break;
                }
            }
        }

        if (touchInput && !isSelectingText()) {
            handleDirectFingerScrollBeforeGesture(event);
        }
        mGestureRecognizer.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            finishDirectFingerScroll(action == MotionEvent.ACTION_UP ? "up" : "cancel");
            finishMultiTouchSequence();
        }
        return true;
    }

    private void handleDirectFingerScrollBeforeGesture(MotionEvent event) {
        if (mMultiTouchSequenceCaptured || event.getPointerCount() > 1) {
            finishDirectFingerScroll("multitouch-exclusive");
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                finishDirectFingerScroll("restart");
                if (!isSmoothGhosttyLocalScroll()) return;
                if (!mScroller.isFinished()) mScroller.abortAnimation();
                mFingerScrollTracker.start(event.getPointerId(0), event.getY(0),
                    getViewportPositionPixels());
                mFingerScrollStartedNanos = System.nanoTime();
                mFingerScrollMoveCount = 0;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                finishDirectFingerScroll("multitouch");
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (!mFingerScrollTracker.isActive() || event.getPointerCount() != 1) return;
                int pointerIndex = event.findPointerIndex(mFingerScrollTracker.getPointerId());
                if (pointerIndex < 0) {
                    finishDirectFingerScroll("pointer-lost");
                    return;
                }
                boolean wasDragging = mFingerScrollTracker.isDragging();
                float target = mFingerScrollTracker.update(
                    event.getY(pointerIndex), mFingerScrollCaptureSlop);
                if (Float.isNaN(target)) return;
                if (!wasDragging) {
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    Log.i(LOG_TAG, "finger-scroll-v2 begin startPx=" +
                        Math.round(mFingerScrollTracker.getStartViewport()) + " slopPx=" +
                        Math.round(mFingerScrollCaptureSlop) + " absoluteAnchor=true");
                }
                mFingerScrollMoveCount++;
                setViewportPositionPixels(target);
                break;
        }
    }

    private void captureMultiTouchSequence() {
        if (mMultiTouchSequenceCaptured) return;
        finishDirectFingerScroll("multitouch-exclusive");
        if (!mScroller.isFinished()) mScroller.abortAnimation();
        mMultiTouchSequenceCaptured = true;
        mScrollRemainder = 0f;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
    }

    private void finishMultiTouchSequence() {
        if (!mMultiTouchSequenceCaptured) return;
        mMultiTouchSequenceCaptured = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    private void finishDirectFingerScroll(String reason) {
        if (!mFingerScrollTracker.isActive()) return;
        boolean dragged = mFingerScrollTracker.isDragging();
        float requested = mFingerScrollTracker.getLastTarget();
        float start = mFingerScrollTracker.getStartViewport();
        mFingerScrollTracker.cancel();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        if (dragged) {
            long elapsedMicros = Math.max(0L,
                (System.nanoTime() - mFingerScrollStartedNanos) / 1000L);
            float applied = getViewportPositionPixels() - start;
            Log.i(LOG_TAG, "finger-scroll-v2 end reason=" + reason + " moves=" +
                mFingerScrollMoveCount + " touchDeltaPx=" + Math.round(requested - start) +
                " viewportDeltaPx=" + Math.round(applied) +
                " trackingErrorPx=" + Math.round(applied - (requested - start)) +
                " viewportPx=" + Math.round(getViewportPositionPixels()) +
                " elapsedUs=" + elapsedMicros + " absoluteAnchor=true");
        }
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "onKeyPreIme(keyCode=" + keyCode + ", event=" + event + ")");
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            cancelRequestAutoFill();
            if (isSelectingText()) {
                stopTextSelectionMode();
                return true;
            } else if (mClient.shouldBackButtonBeMappedToEscape()) {
                // Intercept back button to treat it as escape:
                switch (event.getAction()) {
                    case KeyEvent.ACTION_DOWN:
                        return onKeyDown(keyCode, event);
                    case KeyEvent.ACTION_UP:
                        return onKeyUp(keyCode, event);
                }
            }
        } else if (mClient.shouldUseCtrlSpaceWorkaround() &&
                   keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed()) {
            /* ctrl+space does not work on some ROMs without this workaround.
               However, this breaks it on devices where it works out of the box. */
            return onKeyDown(keyCode, event);
        }
        return super.onKeyPreIme(keyCode, event);
    }

    /**
     * Key presses in software keyboards will generally NOT trigger this listener, although some
     * may elect to do so in some situations. Do not rely on this to catch software key presses.
     * Gboard calls this when shouldEnforceCharBasedInput() is disabled (InputType.TYPE_NULL) instead
     * of calling commitText(), with deviceId=-1. However, Hacker's Keyboard, OpenBoard, LG Keyboard
     * call commitText().
     *
     * This function may also be called directly without android calling it, like by
     * `TerminalExtraKeys` which generates a KeyEvent manually which uses {@link KeyCharacterMap#VIRTUAL_KEYBOARD}
     * as the device (deviceId=-1), as does Gboard. That would normally use mappings defined in
     * `/system/usr/keychars/Virtual.kcm`. You can run `dumpsys input` to find the `KeyCharacterMapFile`
     * used by virtual keyboard or hardware keyboard. Note that virtual keyboard device is not the
     * same as software keyboard, like Gboard, etc. Its a fake device used for generating events and
     * for testing.
     *
     * We handle shift key in `commitText()` to convert codepoint to uppercase case there with a
     * call to {@link Character#toUpperCase(int)}, but here we instead rely on getUnicodeChar() for
     * conversion of keyCode, for both hardware keyboard shift key (via effectiveMetaState) and
     * `mClient.readShiftKey()`, based on value in kcm files.
     * This may result in different behaviour depending on keyboard and android kcm files set for the
     * InputDevice for the event passed to this function. This will likely be an issue for non-english
     * languages since `Virtual.kcm` in english only by default or at least in AOSP. For both hardware
     * shift key (via effectiveMetaState) and `mClient.readShiftKey()`, `getUnicodeChar()` is used
     * for shift specific behaviour which usually is to uppercase.
     *
     * For fn key on hardware keyboard, android checks kcm files for hardware keyboards, which is
     * `Generic.kcm` by default, unless a vendor specific one is defined. The event passed will have
     * {@link KeyEvent#META_FUNCTION_ON} set. If the kcm file only defines a single character or unicode
     * code point `\\uxxxx`, then only one event is passed with that value. However, if kcm defines
     * a `fallback` key for fn or others, like `key DPAD_UP { ... fn: fallback PAGE_UP }`, then
     * android will first pass an event with original key `DPAD_UP` and {@link KeyEvent#META_FUNCTION_ON}
     * set. But this function will not consume it and android will pass another event with `PAGE_UP`
     * and {@link KeyEvent#META_FUNCTION_ON} not set, which will be consumed.
     *
     * Now there are some other issues as well, firstly ctrl and alt flags are not passed to
     * `getUnicodeChar()`, so modified key values in kcm are not used. Secondly, if the kcm file
     * for other modifiers like shift or fn define a non-alphabet, like { fn: '\u0015' } to act as
     * DPAD_LEFT, the `getUnicodeChar()` will correctly return `21` as the code point but action will
     * not happen because the `handleKeyCode()` function that transforms DPAD_LEFT to `\033[D`
     * escape sequence for the terminal to perform the left action would not be called since its
     * called before `getUnicodeChar()` and terminal will instead get `21 0x15 Negative Acknowledgement`.
     * The solution to such issues is calling `getUnicodeChar()` before the call to `handleKeyCode()`
     * if user has defined a custom kcm file, like done in POC mentioned in #2237. Note that
     * Hacker's Keyboard calls `commitText()` so don't test fn/shift with it for this function.
     * https://github.com/termux/termux-app/pull/2237
     * https://github.com/agnostic-apollo/termux-app/blob/terminal-code-point-custom-mapping/terminal-view/src/main/java/com/termux/view/TerminalView.java
     *
     * Key Character Map (kcm) and Key Layout (kl) files info:
     * https://source.android.com/devices/input/key-character-map-files
     * https://source.android.com/devices/input/key-layout-files
     * https://source.android.com/devices/input/keyboard-devices
     * AOSP kcm and kl files:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/data/keyboards
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/packages/InputDevices/res/raw
     *
     * KeyCodes:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/view/KeyEvent.java
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/native/include/android/keycodes.h
     *
     * `dumpsys input`:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/services/inputflinger/reader/EventHub.cpp;l=1917
     *
     * Loading of keymap:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/services/inputflinger/reader/EventHub.cpp;l=1644
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/Keyboard.cpp;l=41
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/InputDevice.cpp
     * OVERLAY keymaps for hardware keyboards may be combined as well:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=165
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=831
     *
     * Parse kcm file:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=727
     * Parse key value:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=981
     *
     * `KeyEvent.getUnicodeChar()`
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/view/KeyEvent.java;l=2716
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/view/KeyCharacterMap.java;l=368
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/jni/android_view_KeyCharacterMap.cpp;l=117
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=231
     *
     * Keyboard layouts advertised by applications, like for hardware keyboards via #ACTION_QUERY_KEYBOARD_LAYOUTS
     * Config is stored in `/data/system/input-manager-state.xml`
     * https://github.com/ris58h/custom-keyboard-layout
     * Loading from apps:
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/InputManagerService.java;l=1221
     * Set:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/hardware/input/InputManager.java;l=89
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/hardware/input/InputManager.java;l=543
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:packages/apps/Settings/src/com/android/settings/inputmethod/KeyboardLayoutDialogFragment.java;l=167
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/InputManagerService.java;l=1385
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/PersistentDataStore.java
     * Get overlay keyboard layout
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/InputManagerService.java;l=2158
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/services/core/jni/com_android_server_input_InputManagerService.cpp;l=616
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "onKeyDown(keyCode=" + keyCode + ", isSystem()=" + event.isSystem() + ", event=" + event + ")");
        if (mEmulator == null) return true;
        if (isSelectingText()) {
            stopTextSelectionMode();
        }

        if (mClient.onKeyDown(keyCode, event, mTermSession)) {
            invalidate();
            return true;
        } else if (event.isSystem() && (!mClient.shouldBackButtonBeMappedToEscape() || keyCode != KeyEvent.KEYCODE_BACK)) {
            return super.onKeyDown(keyCode, event);
        } else if (event.getAction() == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            prepareForUserInput();
            mTermSession.write(event.getCharacters());
            return true;
        }

        final int metaState = event.getMetaState();
        final boolean controlDown = event.isCtrlPressed() || mClient.readControlKey();
        final boolean leftAltDown = (metaState & KeyEvent.META_ALT_LEFT_ON) != 0 || mClient.readAltKey();
        final boolean shiftDown = event.isShiftPressed() || mClient.readShiftKey();
        final boolean rightAltDownFromEvent = (metaState & KeyEvent.META_ALT_RIGHT_ON) != 0;

        int keyMod = 0;
        if (controlDown) keyMod |= KeyHandler.KEYMOD_CTRL;
        if (event.isAltPressed() || leftAltDown) keyMod |= KeyHandler.KEYMOD_ALT;
        if (shiftDown) keyMod |= KeyHandler.KEYMOD_SHIFT;
        if (event.isNumLockOn()) keyMod |= KeyHandler.KEYMOD_NUM_LOCK;
        // https://github.com/termux/termux-app/issues/731
        if (!event.isFunctionPressed() && handleKeyCode(keyCode, keyMod)) {
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) mClient.logInfo(LOG_TAG, "handleKeyCode() took key event");
            return true;
        }

        // Clear Ctrl since we handle that ourselves:
        int bitsToClear = KeyEvent.META_CTRL_MASK;
        if (rightAltDownFromEvent) {
            // Let right Alt/Alt Gr be used to compose characters.
        } else {
            // Use left alt to send to terminal (e.g. Left Alt+B to jump back a word), so remove:
            bitsToClear |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        }
        int effectiveMetaState = event.getMetaState() & ~bitsToClear;

        if (shiftDown) effectiveMetaState |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        if (mClient.readFnKey()) effectiveMetaState |= KeyEvent.META_FUNCTION_ON;

        int result = event.getUnicodeChar(effectiveMetaState);
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "KeyEvent#getUnicodeChar(" + effectiveMetaState + ") returned: " + result);
        if (result == 0) {
            return false;
        }

        int oldCombiningAccent = mCombiningAccent;
        if ((result & KeyCharacterMap.COMBINING_ACCENT) != 0) {
            // If entered combining accent previously, write it out:
            if (mCombiningAccent != 0)
                inputCodePoint(event.getDeviceId(), mCombiningAccent, controlDown, leftAltDown);
            mCombiningAccent = result & KeyCharacterMap.COMBINING_ACCENT_MASK;
        } else {
            if (mCombiningAccent != 0) {
                int combinedChar = KeyCharacterMap.getDeadChar(mCombiningAccent, result);
                if (combinedChar > 0) result = combinedChar;
                mCombiningAccent = 0;
            }
            inputCodePoint(event.getDeviceId(), result, controlDown, leftAltDown);
        }

        if (mCombiningAccent != oldCombiningAccent) invalidate();

        return true;
    }

    public void inputCodePoint(int eventSource, int codePoint, boolean controlDownFromEvent, boolean leftAltDownFromEvent) {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mClient.logInfo(LOG_TAG, "inputCodePoint(eventSource=" + eventSource + ", codePoint=" + codePoint + ", controlDownFromEvent=" + controlDownFromEvent + ", leftAltDownFromEvent="
                + leftAltDownFromEvent + ")");
        }

        if (mTermSession == null) return;

        // Ensure cursor is shown when a key is pressed down like long hold on (arrow) keys
        if (mEmulator != null)
            mEmulator.setCursorBlinkState(true);

        final boolean controlDown = controlDownFromEvent || mClient.readControlKey();
        final boolean altDown = leftAltDownFromEvent || mClient.readAltKey();

        if (mClient.onCodePoint(codePoint, controlDown, mTermSession)) return;

        if (controlDown) {
            if (codePoint >= 'a' && codePoint <= 'z') {
                codePoint = codePoint - 'a' + 1;
            } else if (codePoint >= 'A' && codePoint <= 'Z') {
                codePoint = codePoint - 'A' + 1;
            } else if (codePoint == ' ' || codePoint == '2') {
                codePoint = 0;
            } else if (codePoint == '[' || codePoint == '3') {
                codePoint = 27; // ^[ (Esc)
            } else if (codePoint == '\\' || codePoint == '4') {
                codePoint = 28;
            } else if (codePoint == ']' || codePoint == '5') {
                codePoint = 29;
            } else if (codePoint == '^' || codePoint == '6') {
                codePoint = 30; // control-^
            } else if (codePoint == '_' || codePoint == '7' || codePoint == '/') {
                // "Ctrl-/ sends 0x1f which is equivalent of Ctrl-_ since the days of VT102"
                // - http://apple.stackexchange.com/questions/24261/how-do-i-send-c-that-is-control-slash-to-the-terminal
                codePoint = 31;
            } else if (codePoint == '8') {
                codePoint = 127; // DEL
            }
        }

        if (codePoint > -1) {
            // If not virtual or soft keyboard.
            if (eventSource > KEY_EVENT_SOURCE_SOFT_KEYBOARD) {
                // Work around bluetooth keyboards sending funny unicode characters instead
                // of the more normal ones from ASCII that terminal programs expect - the
                // desire to input the original characters should be low.
                switch (codePoint) {
                    case 0x02DC: // SMALL TILDE.
                        codePoint = 0x007E; // TILDE (~).
                        break;
                    case 0x02CB: // MODIFIER LETTER GRAVE ACCENT.
                        codePoint = 0x0060; // GRAVE ACCENT (`).
                        break;
                    case 0x02C6: // MODIFIER LETTER CIRCUMFLEX ACCENT.
                        codePoint = 0x005E; // CIRCUMFLEX ACCENT (^).
                        break;
                }
            }

            // If left alt, send escape before the code point to make e.g. Alt+B and Alt+F work in readline:
            prepareForUserInput();
            mTermSession.writeCodePoint(altDown, codePoint);
        }
    }

    /** Input the specified keyCode if applicable and return if the input was consumed. */
    public boolean handleKeyCode(int keyCode, int keyMod) {
        // Ensure cursor is shown when a key is pressed down like long hold on (arrow) keys
        if (mEmulator != null)
            mEmulator.setCursorBlinkState(true);

        if (handleKeyCodeAction(keyCode, keyMod))
            return true;

        TerminalEmulator term = mTermSession.getEmulator();
        String code = KeyHandler.getCode(keyCode, keyMod, term.isCursorKeysApplicationMode(), term.isKeypadApplicationMode());
        if (code == null) return false;
        prepareForUserInput();
        mTermSession.write(code);
        return true;
    }

    public boolean handleKeyCodeAction(int keyCode, int keyMod) {
        boolean shiftDown = (keyMod & KeyHandler.KEYMOD_SHIFT) != 0;

        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                // shift+page_up and shift+page_down should scroll scrollback history instead of
                // scrolling command history or changing pages
                if (shiftDown) {
                    long time = SystemClock.uptimeMillis();
                    MotionEvent motionEvent = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 0, 0, 0);
                    doScroll(motionEvent, keyCode == KeyEvent.KEYCODE_PAGE_UP ? -mEmulator.mRows : mEmulator.mRows);
                    motionEvent.recycle();
                    return true;
                }
        }

       return false;
    }

    /**
     * Called when a key is released in the view.
     *
     * @param keyCode The keycode of the key which was released.
     * @param event   A {@link KeyEvent} describing the event.
     * @return Whether the event was handled.
     */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "onKeyUp(keyCode=" + keyCode + ", event=" + event + ")");

        // Do not return for KEYCODE_BACK and send it to the client since user may be trying
        // to exit the activity.
        if (mEmulator == null && keyCode != KeyEvent.KEYCODE_BACK) return true;

        if (mClient.onKeyUp(keyCode, event)) {
            invalidate();
            return true;
        } else if (event.isSystem()) {
            // Let system key events through.
            return super.onKeyUp(keyCode, event);
        }

        return true;
    }

    /**
     * This is called during layout when the size of this view has changed. If you were just added to the view
     * hierarchy, you're called with the old values of 0.
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        TerminalGeometryCommitPolicy.Decision decision = requestTerminalGeometry(
            TerminalGeometryCommitPolicy.Source.LAYOUT, false, 0, -1, -1, -1);
        if (decision == TerminalGeometryCommitPolicy.Decision.SUPPRESSED_BY_IME ||
            decision == TerminalGeometryCommitPolicy.Decision.WAIT_FOR_STABLE_FRAME) {
            // Keep rendering the last committed grid. Canvas naturally clips it to the physical
            // viewport while an IME or transient pager layout is moving.
            invalidate();
            return;
        }
        resetVulkanSubmissionState();
        publishVulkanFrameNow(true);
    }

    /**
     * Check if the terminal size in rows and columns should be updated. Explicit callers use this
     * as a synchronous geometry barrier before presenting a page.
     */
    public void updateSize() {
        requestTerminalGeometry(mEmulator == null
            ? TerminalGeometryCommitPolicy.Source.INITIAL_ATTACH
            : TerminalGeometryCommitPolicy.Source.RENDER_BARRIER,
            false, 0, -1, -1, -1);
    }

    /**
     * IME occlusion owns no terminal grid changes, including at its stable endpoint. Unlocking
     * only re-evaluates the fully restored physical viewport so a real structural change that was
     * deferred while the keyboard was visible cannot be lost.
     */
    public void setImeViewportGeometryLocked(boolean locked) {
        mImeViewportGeometryLocked = locked;
        boolean wasLocked = mGeometryCommitPolicy.isImeViewportActive();
        mGeometryCommitPolicy.setImeViewportActive(locked);
        if (wasLocked && !locked) {
            requestTerminalGeometry(TerminalGeometryCommitPolicy.Source.LAYOUT,
                false, 0, -1, -1, -1);
        }
    }

    /** Request one stable, anchored structural geometry transaction after configuration changes. */
    public void requestStructuralGeometryCommit() {
        requestTerminalGeometry(TerminalGeometryCommitPolicy.Source.STRUCTURAL,
            false, 0, -1, -1, -1);
    }

    public long getPtyGeometryCommitCountForDiagnostics() {
        return mGeometryCommittedCount;
    }

    public long getImeGeometrySuppressedCountForDiagnostics() {
        return mGeometryCommitPolicy.getSuppressedByImeCount();
    }

    private void updateSizeImmediately() {
        requestTerminalGeometry(mEmulator == null
            ? TerminalGeometryCommitPolicy.Source.INITIAL_ATTACH
            : TerminalGeometryCommitPolicy.Source.RENDER_BARRIER,
            false, 0, -1, -1, -1);
    }

    /**
     * Resize transaction. An anchored transaction suppresses publication until the native tracked
     * cell has been resolved and {@link #applyRealtimeTextSize(int, float, float)} commits it.
     */
    private boolean updateSizeImmediately(boolean preserveViewport, int viewportTopRow,
                                          int anchorColumn, int anchorViewportRow,
                                          int targetViewportRow) {
        TerminalGeometryCommitPolicy.Decision decision = requestTerminalGeometry(
            preserveViewport ? TerminalGeometryCommitPolicy.Source.USER_TEXT_SCALE
                : (mEmulator == null ? TerminalGeometryCommitPolicy.Source.INITIAL_ATTACH
                    : TerminalGeometryCommitPolicy.Source.RENDER_BARRIER),
            preserveViewport, viewportTopRow, anchorColumn, anchorViewportRow,
            targetViewportRow);
        return decision == TerminalGeometryCommitPolicy.Decision.COMMIT;
    }

    private TerminalGeometryCommitPolicy.Decision requestTerminalGeometry(
        @NonNull TerminalGeometryCommitPolicy.Source source, boolean preserveViewport,
        int viewportTopRow, int anchorColumn, int anchorViewportRow,
        int targetViewportRow) {
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (viewWidth == 0 || viewHeight == 0 || mTermSession == null || mRenderer == null) {
            return TerminalGeometryCommitPolicy.Decision.UNCHANGED;
        }

        TerminalGeometryCommitPolicy.Geometry geometry = observedTerminalGeometry();
        TerminalGeometryCommitPolicy.Decision decision = mGeometryCommitPolicy.request(geometry, source);
        if (decision == TerminalGeometryCommitPolicy.Decision.WAIT_FOR_STABLE_FRAME) {
            mGeometryDeferredCount++;
            scheduleTerminalGeometryCommitFrame();
            return decision;
        }
        if (decision != TerminalGeometryCommitPolicy.Decision.COMMIT) return decision;

        return commitTerminalGeometry(geometry, preserveViewport, viewportTopRow, anchorColumn,
            anchorViewportRow, targetViewportRow)
            ? TerminalGeometryCommitPolicy.Decision.COMMIT
            : TerminalGeometryCommitPolicy.Decision.UNCHANGED;
    }

    private boolean commitTerminalGeometry(@NonNull TerminalGeometryCommitPolicy.Geometry geometry,
                                           boolean preserveViewport, int viewportTopRow,
                                           int anchorColumn, int anchorViewportRow,
                                           int targetViewportRow) {
        int newColumns = geometry.columns;
        int newRows = geometry.rows;
        int cellWidth = geometry.cellWidth;
        int cellHeight = geometry.cellHeight;
        if (mEmulator != null && newColumns == mEmulator.mColumns && newRows == mEmulator.mRows &&
            cellWidth == mLastSentCellWidth && cellHeight == mLastSentCellHeight) {
            mGeometryCommitPolicy.markCommitted(geometry);
            return false;
        }

        boolean anchoredResize = preserveViewport && mEmulator != null;
        if (anchoredResize) {
            mTermSession.updateSize(newColumns, newRows, cellWidth, cellHeight,
                viewportTopRow, anchorColumn, anchorViewportRow, targetViewportRow);
        } else {
            mTermSession.updateSize(newColumns, newRows, cellWidth, cellHeight);
        }
        mLastSentCellWidth = cellWidth;
        mLastSentCellHeight = cellHeight;
        mEmulator = mTermSession.getEmulator();
        if (mEmulator == null) return false;
        mGeometryCommitPolicy.markCommitted(geometry);
        mGeometryCommittedCount++;
        mClient.onEmulatorSet();

        // Update mTerminalCursorBlinkerRunnable inner class mEmulator on session change.
        if (mTerminalCursorBlinkerRunnable != null)
            mTerminalCursorBlinkerRunnable.setEmulator(mEmulator);

        if (anchoredResize && mEmulator.isGhosttyRenderAuthorityActive()) {
            mTopRow = mEmulator.getGhosttyViewportTopRow();
        } else if (!anchoredResize) {
            mTopRow = 0;
            mViewportPixelOffset = 0f;
        }
        scrollTo(0, 0);
        if (anchoredResize) {
            // Mark the cache stale, but do not publish the pre-anchor viewport.
            if (mRenderer != null) mRenderer.requestFullFrame();
            mPendingFullInvalidation = true;
        } else {
            requestFullRenderFrame();
        }
        if (!mEmulator.isGhosttyRenderAuthorityActive()) {
            mEmulator.getScreen().clearDirtyRows();
        }
        return true;
    }

    private void scheduleTerminalGeometryCommitFrame() {
        if (mGeometryCommitFrameScheduled) return;
        mGeometryCommitFrameScheduled = true;
        final long frameEpoch = mGeometryCommitFrameEpoch;
        postOnAnimation(new Runnable() {
            @Override
            public void run() {
                if (frameEpoch != mGeometryCommitFrameEpoch) return;
                mGeometryCommitFrameScheduled = false;
                if (!isAttachedToWindow() || mTermSession == null || mRenderer == null ||
                    getWidth() == 0 || getHeight() == 0) {
                    return;
                }
                TerminalGeometryCommitPolicy.Decision decision =
                    mGeometryCommitPolicy.onVsync(observedTerminalGeometry());
                if (decision == TerminalGeometryCommitPolicy.Decision.WAIT_FOR_STABLE_FRAME) {
                    scheduleTerminalGeometryCommitFrame();
                    return;
                }
                if (decision == TerminalGeometryCommitPolicy.Decision.COMMIT) {
                    TerminalGeometryCommitPolicy.Geometry geometry =
                        observedTerminalGeometry();
                    if (commitStableTerminalGeometry(geometry)) {
                        resetVulkanSubmissionState();
                        publishVulkanFrameNow(true);
                    }
                }
            }
        });
    }

    /** Preserve a user's real history viewport across structural reflow. Live TUIs stay live. */
    private boolean commitStableTerminalGeometry(
        @NonNull TerminalGeometryCommitPolicy.Geometry geometry) {
        boolean preserveHistoryViewport = mEmulator != null && mTopRow < 0 &&
            mEmulator.isGhosttyRenderAuthorityActive() &&
            !mEmulator.isAlternateBufferActive();
        if (!preserveHistoryViewport) {
            return commitTerminalGeometry(geometry, false, 0, -1, -1, -1);
        }

        int anchorViewportRow = Math.max(0, mEmulator.mRows / 2);
        int targetViewportRow = Math.max(0, geometry.rows / 2);
        int anchorColumn = Math.max(0, mEmulator.mColumns / 2);
        boolean committed = commitTerminalGeometry(geometry, true, mTopRow,
            anchorColumn, anchorViewportRow, targetViewportRow);
        if (committed) requestFullRenderFrame();
        return committed;
    }

    @NonNull
    private TerminalGeometryCommitPolicy.Geometry observedTerminalGeometry() {
        return new TerminalGeometryCommitPolicy.Geometry(expectedTerminalColumns(),
            expectedTerminalRows(), expectedTerminalCellWidth(), expectedTerminalCellHeight());
    }

    private int expectedTerminalColumns() {
        return Math.max(4, (int) (getWidth() / mRenderer.mFontWidth));
    }

    private int expectedTerminalRows() {
        return Math.max(4, (getHeight() - mRenderer.mFontLineSpacingAndAscent) /
            mRenderer.mFontLineSpacing);
    }

    private int expectedTerminalCellWidth() {
        return Math.max(1, (int) mRenderer.getFontWidth());
    }

    private int expectedTerminalCellHeight() {
        return Math.max(1, mRenderer.getFontLineSpacing());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (isVulkanFrameActive()) {
            // The TextureView below owns terminal pixels once a complete frame is committed.
            // Keep this overlay transparent, but continue drawing selection handles/cursors.
            renderTextSelection();
            maybeLogFramePublication(0L);
            return;
        }
        super.onDraw(canvas);
        drawTerminalFrame(canvas);
    }

    private void drawTerminalFrame(Canvas canvas) {
        long drawStartedNanos = System.nanoTime();
        boolean presented = true;
        if (mEmulator == null) {
            canvas.drawColor(0XFF000000);
            mLastRenderedCursorRow = -1;
            mLastRenderedCursorCol = -1;
            mLastRenderedCursorStyle = -1;
            mLastRenderedCursorVisible = false;
        } else {
            // render the terminal view and highlight any selected text
            int[] sel = mDefaultSelectors;
            if (mTextSelectionCursorController != null) {
                mTextSelectionCursorController.getSelectors(sel);
            }

            presented = mRenderer.renderFrame(mEmulator, canvas, mTopRow, mViewportPixelOffset,
                sel[0], sel[1], sel[2], sel[3]);
            if (presented) {
                mLastRenderedCursorRow = mEmulator.getCursorRow();
                mLastRenderedCursorCol = mEmulator.getCursorCol();
                mLastRenderedCursorStyle = mEmulator.getCursorStyle();
                mLastRenderedCursorVisible = mEmulator.shouldCursorBeVisible();
                mLastPresentedCursorEnabled = mEmulator.isCursorEnabled();

                // Render handles only after their underlying terminal frame was committed.
                renderTextSelection();
                mLastPresentedContentRevision = mEmulator.isGhosttyRenderAuthorityActive()
                    ? mRenderer.getGhosttyCachedModelRevision()
                    : mEmulator.getContentRevision();
                recordPresentedImeCameraState(mTopRow, mViewportPixelOffset, getHeight(),
                    mRenderer.mFontLineSpacing, mRenderer.mFontAscent,
                    resolvePresentedImeProtectedBottomScreenRow());
                beginScaleGlyphWarmupAfterPresentedFrame();
                armIdleGlyphWarmupAfterPresentedFrame();
                notifyImeCameraFrameReady();
            }
        }
        if (presented) {
            mPresentedFrames++;
        } else {
            mSkippedFramePresentations++;
            scheduleRenderFrame(false);
        }
        long drawMicros = Math.max(0L, (System.nanoTime() - drawStartedNanos) / 1000L);
        if (drawMicros > mMaxDrawMicros) mMaxDrawMicros = drawMicros;
        maybeLogFramePublication(drawMicros);
    }

    private void maybeLogFramePublication(long drawMicros) {
        long now = SystemClock.uptimeMillis();
        if (now - mLastFrameMetricsLogMs < FRAME_METRICS_LOG_INTERVAL_MS) return;
        mLastFrameMetricsLogMs = now;
        long currentRevision = mEmulator == null ? Long.MIN_VALUE : mEmulator.getContentRevision();
        Log.i(LOG_TAG, "frame-publication-v4 view=" + System.identityHashCode(this) +
            " requests=" + mRenderFrameRequests + " callbacks=" + mRenderFrameCallbacks +
            " coalesced=" + mRenderFrameCoalesced + " presented=" + mPresentedFrames +
            " immediate=" + mImmediateGhosttyInvalidations + " partial=" +
            mPartialGhosttyInvalidations + " viewportImmediate=" +
            mImmediateViewportInvalidations +
            " skipped=" + mSkippedFramePresentations +
            " currentRevision=" + currentRevision + " presentedRevision=" +
            mLastPresentedContentRevision + " drawUs=" + drawMicros + " maxDrawUs=" +
            mMaxDrawMicros + " maxScheduleUs=" + mMaxScheduleLatencyMicros +
            " complete=" + hasCompleteRenderFrame());
    }

    public TerminalSession getCurrentSession() {
        return mTermSession;
    }

    /** Release retained row commands when the owning tab is permanently removed. */
    public void releaseRenderResources() {
        if (mVulkanView != null) mVulkanView.releaseRenderResources();
        if (mRenderer != null) mRenderer.dispose();
        resetVulkanSubmissionState();
        resetImeCameraFrameNotificationState();
        mVulkanFailed = false;
        resetPresentedImeCameraState();
        mPendingFullInvalidation = true;
    }

    /** Stop an offscreen tab from continuing to submit viewport frames after a page commit. */
    public void cancelViewportMotionForTabTransition() {
        finishDirectFingerScroll("tab-transition");
        if (!mScroller.isFinished()) mScroller.abortAnimation();
    }

    /**
     * Commits an explicit user text-input action to the live PTY viewport.
     *
     * <p>A terminal has one writable focus: the live cursor. A tap that opens the IME, a key that
     * is actually sent to the PTY, or a user paste must therefore leave scrollback before the IME
     * surface computes its cursor/content-tail pan. Passive focus restoration, tab attachment and
     * inset replay must not call this method, since those events do not express input intent.</p>
     *
     * <p>The explicit SCROLL lock and text selection remain authoritative. Returning to the live
     * edge only changes this view's retained presentation; it never changes terminal rows/columns
     * or sends a window-size ioctl.</p>
     *
     * @return {@code true} if a history or fractional viewport was restored to the live edge.
     */
    @MainThread
    public boolean prepareForUserInput() {
        if (mEmulator == null || isSelectingText() || mEmulator.isAutoScrollDisabled()) {
            return false;
        }

        finishDirectFingerScroll("user-input");
        if (!mScroller.isFinished()) mScroller.abortAnimation();
        mScrollRemainder = 0f;

        boolean restoreLiveViewport = mTopRow != 0 ||
            Math.abs(mViewportPixelOffset) >= 0.01f;

        // A nearby history viewport often still retains every live row; a far history viewport
        // legitimately does not. Decide before changing the viewport so the explicit focus
        // transaction can request one authoritative full frame immediately instead of depending
        // on a best-effort delta followed by an asynchronous retry.
        boolean liveGhosttyFrameRetained = !restoreLiveViewport || (mRenderer != null &&
            mEmulator.isGhosttyRenderAuthorityActive() &&
            mRenderer.hasCompleteGhosttyFrame(0, mEmulator.mRows,
                mEmulator.getContentRevision()));

        if (restoreLiveViewport) {
            mTopRow = 0;
            mViewportPixelOffset = 0f;
            mUserInputLiveEdgeRestores++;

            // Invalidate only callback de-duplication, not the last presented pixels. A history
            // frame may already be queued on the Vulkan thread; the next authoritative live frame
            // must always be allowed to close this input transaction.
            resetImeCameraFrameNotificationState();
        }

        // Publish intent only after the UI viewport has atomically become live. The surface then
        // waits for a frame whose own immutable top-row identity is also live.
        notifyImeExplicitFocus();

        if (!restoreLiveViewport) {
            if (getImeCameraSnapshot().availability == ImeCameraSnapshot.Availability.FRAME_PENDING) {
                requestImeCameraFrame();
            }
            return false;
        }

        // The IME anchor reads Ghostty's retained rows. Prepare the live viewport before notifying
        // the surface, otherwise the old history cache can transiently report no semantic tail and
        // leave the terminal pixel layer unpanned behind the keyboard.
        if (mRenderer != null && mEmulator.isGhosttyRenderAuthorityActive()) {
            boolean prepared = prewarmRenderFrame(!liveGhosttyFrameRetained);
            if (!prepared && liveGhosttyFrameRetained) {
                prepared = prewarmRenderFrame(true);
            }
            if (!prepared) requestFullRenderFrame();
        }
        requestImeCameraFrame();
        publishViewportRenderFrameNow();
        return true;
    }

    private CharSequence getText() {
        return mEmulator.getSelectedText(
            0, mTopRow, mEmulator.mColumns, mTopRow + mEmulator.mRows);
    }

    public int getCursorX(float x) {
        // Avoid occasional off-by-one due to floating point rounding at exact cell boundaries.
        // (E.g. x/fontWidth yielding 4.999999 -> 4 instead of 5.)
        if (x <= 0) return 0;
        return (int) (x / mRenderer.mFontWidth + 0.0001f);
    }

    public int getCursorY(float y) {
        // Keep consistent with {@link #getColumnAndRow(MotionEvent, boolean)} to avoid row drift.
        return (int) ((y + mViewportPixelOffset -
            mRenderer.mFontLineSpacingAndAscent) / mRenderer.mFontLineSpacing) + mTopRow;
    }

    public int getPointX(int cx) {
        if (cx > mEmulator.mColumns) {
            cx = mEmulator.mColumns;
        }
        return Math.round(cx * mRenderer.mFontWidth);
    }

    public int getPointY(int cy) {
        return Math.round((cy - mTopRow) * mRenderer.mFontLineSpacing -
            mViewportPixelOffset);
    }

    public int getTopRow() {
        return mTopRow;
    }

    public float getViewportPixelOffset() {
        return mViewportPixelOffset;
    }

    public void setTopRow(int mTopRow) {
        this.mTopRow = mTopRow;
        mViewportPixelOffset = 0f;
        notifyVisualViewportAnchorChanged();
    }



    /**
     * Define functions required for AutoFill API
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void autofill(AutofillValue value) {
        if (value.isText()) {
            prepareForUserInput();
            mTermSession.write(value.getTextValue().toString());
        }

        resetAutoFill();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public int getAutofillType() {
        return mAutoFillType;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public String[] getAutofillHints() {
        return mAutoFillHints;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public AutofillValue getAutofillValue() {
        return AutofillValue.forText("");
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public int getImportantForAutofill() {
        return mAutoFillImportance;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private synchronized void resetAutoFill() {
        // Restore none type so that AutoFill UI isn't shown anymore.
        mAutoFillType = AUTOFILL_TYPE_NONE;
        mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_NO;
        mAutoFillHints = new String[0];
    }

    public AutofillManager getAutoFillManagerService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;

        try {
            Context context = getContext();
            if (context == null) return null;
            return context.getSystemService(AutofillManager.class);
        } catch (Exception e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to get AutofillManager service", e);
            return null;
        }
    }

    public boolean isAutoFillEnabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;

        try {
            AutofillManager autofillManager = getAutoFillManagerService();
            return autofillManager != null && autofillManager.isEnabled();
        } catch (Exception e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to check if Autofill is enabled", e);
            return false;
        }
    }

    public synchronized void requestAutoFillUsername() {
        requestAutoFill(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new String[]{View.AUTOFILL_HINT_USERNAME} :
                null);
    }

    public synchronized void requestAutoFillPassword() {
        requestAutoFill(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new String[]{View.AUTOFILL_HINT_PASSWORD} :
            null);
    }

    public synchronized void requestAutoFill(String[] autoFillHints) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (autoFillHints == null || autoFillHints.length < 1) return;

        try {
            AutofillManager autofillManager = getAutoFillManagerService();
            if (autofillManager != null && autofillManager.isEnabled()) {
                // Update type that will be returned by `getAutofillType()` so that AutoFill UI is shown.
                mAutoFillType = AUTOFILL_TYPE_TEXT;
                // Update importance that will be returned by `getImportantForAutofill()` so that
                // AutoFill considers the view as important.
                mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_YES;
                // Update hints that will be returned by `getAutofillHints()` for which to show AutoFill UI.
                mAutoFillHints = autoFillHints;
                autofillManager.requestAutofill(this);
            }
        } catch (Exception e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to request Autofill", e);
        }
    }

    public synchronized void cancelRequestAutoFill() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (mAutoFillType == AUTOFILL_TYPE_NONE) return;

        try {
            AutofillManager autofillManager = getAutoFillManagerService();
            if (autofillManager != null && autofillManager.isEnabled()) {
                resetAutoFill();
                autofillManager.cancel();
            }
        } catch (Exception e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to cancel Autofill request", e);
        }
    }





    /**
     * Set terminal cursor blinker rate. It must be between {@link #TERMINAL_CURSOR_BLINK_RATE_MIN}
     * and {@link #TERMINAL_CURSOR_BLINK_RATE_MAX}, otherwise it will be disabled.
     *
     * The {@link #setTerminalCursorBlinkerState(boolean, boolean)} must be called after this
     * for changes to take effect if not disabling.
     *
     * @param blinkRate The value to set.
     * @return Returns {@code true} if setting blinker rate was successfully set, otherwise [@code false}.
     */
    public synchronized boolean setTerminalCursorBlinkerRate(int blinkRate) {
        boolean result;

        // If cursor blinking rate is not valid
        if (blinkRate != 0 && (blinkRate < TERMINAL_CURSOR_BLINK_RATE_MIN || blinkRate > TERMINAL_CURSOR_BLINK_RATE_MAX)) {
            mClient.logError(LOG_TAG, "The cursor blink rate must be in between " + TERMINAL_CURSOR_BLINK_RATE_MIN + "-" + TERMINAL_CURSOR_BLINK_RATE_MAX + ": " + blinkRate);
            mTerminalCursorBlinkerRate = 0;
            result = false;
        } else {
            mClient.logVerbose(LOG_TAG, "Setting cursor blinker rate to " + blinkRate);
            mTerminalCursorBlinkerRate = blinkRate;
            result = true;
        }

        if (mTerminalCursorBlinkerRate == 0) {
            mClient.logVerbose(LOG_TAG, "Cursor blinker disabled");
            stopTerminalCursorBlinker();
        }

        return result;
    }

    /**
     * Sets whether cursor blinker should be started or stopped. Cursor blinker will only be
     * started if {@link #mTerminalCursorBlinkerRate} does not equal 0 and is between
     * {@link #TERMINAL_CURSOR_BLINK_RATE_MIN} and {@link #TERMINAL_CURSOR_BLINK_RATE_MAX}.
     *
     * This should be called when the view holding this activity is resumed or stopped so that
     * cursor blinker does not run when activity is not visible. If you call this on onResume()
     * to start cursor blinking, then ensure that {@link #mEmulator} is set, otherwise wait for the
     * {@link TerminalViewClient#onEmulatorSet()} event after calling {@link #attachSession(TerminalSession)}
     * for the first session added in the activity since blinking will not start if {@link #mEmulator}
     * is not set, like if activity is started again after exiting it with double back press. Do not
     * call this directly after {@link #attachSession(TerminalSession)} since {@link #updateSize()}
     * may return without setting {@link #mEmulator} since width/height may be 0. Its called again in
     * {@link #onSizeChanged(int, int, int, int)}. Calling on onResume() if emulator is already set
     * is necessary, since onEmulatorSet() may not be called after activity is started after device
     * display timeout with double tap and not power button.
     *
     * It should also be called on the
     * {@link com.termux.terminal.TerminalSessionClient#onTerminalCursorStateChange(boolean)}
     * callback when cursor is enabled or disabled so that blinker is disabled if cursor is not
     * to be shown. It should also be checked if activity is visible if blinker is to be started
     * before calling this.
     *
     * It should also be called after terminal is reset with {@link TerminalSession#reset()} in case
     * cursor blinker was disabled before reset due to call to
     * {@link com.termux.terminal.TerminalSessionClient#onTerminalCursorStateChange(boolean)}.
     *
     * How cursor blinker starting works is by registering a {@link Runnable} with the looper of
     * the main thread of the app which when run, toggles the cursor blinking state and re-registers
     * itself to be called with the delay set by {@link #mTerminalCursorBlinkerRate}. When cursor
     * blinking needs to be disabled, we just cancel any callbacks registered. We don't run our own
     * "thread" and let the thread for the main looper do the work for us, whose usage is also
     * required to update the UI, since it also handles other calls to update the UI as well based
     * on a queue.
     *
     * Note that when moving cursor in text editors like nano, the cursor state is quickly
     * toggled `-> off -> on`, which would call this very quickly sequentially. So that if cursor
     * is moved 2 or more times quickly, like long hold on arrow keys, it would trigger
     * `-> off -> on -> off -> on -> ...`, and the "on" callback at index 2 is automatically
     * cancelled by next "off" callback at index 3 before getting a chance to be run. For this case
     * we log only if {@link #TERMINAL_VIEW_KEY_LOGGING_ENABLED} is enabled, otherwise would clutter
     * the log. We don't start the blinking with a delay to immediately show cursor in case it was
     * previously not visible.
     *
     * @param start If cursor blinker should be started or stopped.
     * @param startOnlyIfCursorEnabled If set to {@code true}, then it will also be checked if the
     *                                 cursor is even enabled by {@link TerminalEmulator} before
     *                                 starting the cursor blinker.
     */
    public synchronized void setTerminalCursorBlinkerState(boolean start, boolean startOnlyIfCursorEnabled) {
        // Stop any existing cursor blinker callbacks
        stopTerminalCursorBlinker();

        if (mEmulator == null) return;

        mEmulator.setCursorBlinkingEnabled(false);

        if (start) {
            // If cursor blinker is not enabled or is not valid
            if (mTerminalCursorBlinkerRate < TERMINAL_CURSOR_BLINK_RATE_MIN || mTerminalCursorBlinkerRate > TERMINAL_CURSOR_BLINK_RATE_MAX)
                return;
            // If cursor blinder is to be started only if cursor is enabled
            else if (startOnlyIfCursorEnabled && ! mEmulator.isCursorEnabled()) {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                    mClient.logVerbose(LOG_TAG, "Ignoring call to start cursor blinker since cursor is not enabled");
                return;
            }

            // Start cursor blinker runnable
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                mClient.logVerbose(LOG_TAG, "Starting cursor blinker with the blink rate " + mTerminalCursorBlinkerRate);
            if (mTerminalCursorBlinkerHandler == null)
                mTerminalCursorBlinkerHandler = new Handler(Looper.getMainLooper());
            mTerminalCursorBlinkerRunnable = new TerminalCursorBlinkerRunnable(mEmulator, mTerminalCursorBlinkerRate);
            mEmulator.setCursorBlinkingEnabled(true);
            mTerminalCursorBlinkerRunnable.run();
        }
    }

    /**
     * Cancel the terminal cursor blinker callbacks
     */
    private void stopTerminalCursorBlinker() {
        if (mTerminalCursorBlinkerHandler != null && mTerminalCursorBlinkerRunnable != null) {
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                mClient.logVerbose(LOG_TAG, "Stopping cursor blinker");
            mTerminalCursorBlinkerHandler.removeCallbacks(mTerminalCursorBlinkerRunnable);
        }
    }

    private class TerminalCursorBlinkerRunnable implements Runnable {

        private TerminalEmulator mEmulator;
        private final int mBlinkRate;

        // Initialize with false so that initial blink state is visible after toggling
        boolean mCursorVisible = false;

        public TerminalCursorBlinkerRunnable(TerminalEmulator emulator, int blinkRate) {
            mEmulator = emulator;
            mBlinkRate = blinkRate;
        }

        public void setEmulator(TerminalEmulator emulator) {
            mEmulator = emulator;
        }

        public void run() {
            try {
                if (mEmulator != null) {
                    // Toggle the blink state and schedule only the old/new cursor rows. The active
                    // renderer checks TerminalEmulator.shouldCursorBeVisible() for the final state.
                    mCursorVisible = !mCursorVisible;
                    //mClient.logVerbose(LOG_TAG, "Toggling cursor blink state to " + mCursorVisible);
                    mEmulator.setCursorBlinkState(mCursorVisible);
                    scheduleCursorRenderFrame();
                }
            } finally {
                // Recall the Runnable after mBlinkRate milliseconds to toggle the blink state
                mTerminalCursorBlinkerHandler.postDelayed(this, mBlinkRate);
            }
        }
    }



    /**
     * Define functions required for text selection and its handles.
     */
    TextSelectionCursorController getTextSelectionCursorController() {
        if (mTextSelectionCursorController == null) {
            mTextSelectionCursorController = new TextSelectionCursorController(this);

            final ViewTreeObserver observer = getViewTreeObserver();
            if (observer != null) {
                observer.addOnTouchModeChangeListener(mTextSelectionCursorController);
            }
        }

        return mTextSelectionCursorController;
    }

    private void showTextSelectionCursors(MotionEvent event) {
        getTextSelectionCursorController().show(event);
    }

    private boolean hideTextSelectionCursors() {
        return getTextSelectionCursorController().hide();
    }

    private void renderTextSelection() {
        if (mTextSelectionCursorController != null)
            mTextSelectionCursorController.render();
    }

    public void showTextSelectionPreview(CharSequence text, int anchorX, int anchorY) {
        if (mEmulator == null) return;

        if (mTextSelectionPreviewPopup == null) {
            mTextSelectionPreviewPopup = new TextSelectionPreviewPopup(this);
        }

        mTextSelectionPreviewPopup.show(text, getSelectionPreviewForegroundColor(), getSelectionPreviewBackgroundColor(), anchorX, anchorY);
    }

    public boolean isTextSelectionMagnifierSupported() {
        return true;
    }

    public void showTextSelectionMagnifier(float anchorX, float anchorY) {
        if (!isTextSelectionMagnifierSupported()) return;
        if (mEmulator == null) return;

        if (mTextSelectionMagnifier == null) {
            mTextSelectionMagnifier = new TextSelectionMagnifier(this);
        }

        mTextSelectionMagnifier.show(anchorX, anchorY);
    }

    public void hideTextSelectionPreview() {
        if (mTextSelectionPreviewPopup != null) {
            mTextSelectionPreviewPopup.hide();
        }
        if (mTextSelectionMagnifier != null) {
            mTextSelectionMagnifier.hide();
        }
    }

    public boolean isSelectingText() {
        if (mTextSelectionCursorController != null) {
            return mTextSelectionCursorController.isActive();
        } else {
            return false;
        }
    }

    /** Get the currently selected text if selecting. */
    public String getSelectedText() {
        if (isSelectingText() && mTextSelectionCursorController != null)
            return mTextSelectionCursorController.getSelectedText();
        else
            return null;
    }

    /** Get the selected text stored before "MORE" button was pressed on the context menu. */
    @Nullable
    public String getStoredSelectedText() {
        return mTextSelectionCursorController != null ? mTextSelectionCursorController.getStoredSelectedText() : null;
    }

    /** Unset the selected text stored before "MORE" button was pressed on the context menu. */
    public void unsetStoredSelectedText() {
        if (mTextSelectionCursorController != null) mTextSelectionCursorController.unsetStoredSelectedText();
    }

    private ActionMode getTextSelectionActionMode() {
        if (mTextSelectionCursorController != null) {
            return mTextSelectionCursorController.getActionMode();
        } else {
            return null;
        }
    }

    public void startTextSelectionMode(MotionEvent event) {
        if (!requestFocus()) {
            return;
        }

        showTextSelectionCursors(event);
        mClient.copyModeChanged(isSelectingText());

        invalidate();
    }

    public void stopTextSelectionMode() {
        if (hideTextSelectionCursors()) {
            mClient.copyModeChanged(isSelectingText());
            invalidate();
        }
    }

    private void decrementYTextSelectionCursors(int decrement) {
        if (mTextSelectionCursorController != null) {
            mTextSelectionCursorController.decrementYTextSelectionCursors(decrement);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        ensureRenderFrameForPresentation();

        if (mTextSelectionCursorController != null) {
            getViewTreeObserver().addOnTouchModeChangeListener(mTextSelectionCursorController);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        finishDirectFingerScroll("detach");
        abortRealtimeScaleGesture("detach");
        mGeometryCommitFrameEpoch++;
        mGeometryCommitFrameScheduled = false;
        if (mRenderer != null) mRenderer.setRealtimeScaleActive(false);
        if (!mScroller.isFinished()) mScroller.abortAnimation();
        super.onDetachedFromWindow();

        if (mTextSelectionCursorController != null) {
            // Might solve the following exception
            // android.view.WindowLeaked: Activity com.termux.app.TermuxActivity has leaked window android.widget.PopupWindow
            stopTextSelectionMode();

            getViewTreeObserver().removeOnTouchModeChangeListener(mTextSelectionCursorController);
            mTextSelectionCursorController.onDetached();
        }

        hideTextSelectionPreview();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            ensureRenderFrameForPresentation();
        } else {
            abortRealtimeScaleGesture("window-hidden");
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this && visibility == VISIBLE) ensureRenderFrameForPresentation();
    }

    private void ensureRenderFrameForPresentation() {
        if (mRenderer == null) return;
        // Visibility is a render barrier, not permission to bypass the geometry coordinator.
        // An IME visual viewport keeps presenting the last real committed grid; structural layout
        // changes are committed only after the coordinator observes a stable frame transaction.
        updateSizeImmediately();
        if (mEmulator == null || !hasCompleteRenderFrame()) {
            requestFullRenderFrame();
        } else {
            scheduleRenderFrame(false);
        }
    }



    /**
     * Define functions required for long hold toolbar.
     */
    private final Runnable mShowFloatingToolbar = new Runnable() {
        @RequiresApi(api = Build.VERSION_CODES.M)
        @Override
        public void run() {
            if (getTextSelectionActionMode() != null) {
                getTextSelectionActionMode().hide(0);  // hide off.
            }
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void showFloatingToolbar() {
        if (getTextSelectionActionMode() != null) {
            int delay = ViewConfiguration.getDoubleTapTimeout();
            postDelayed(mShowFloatingToolbar, delay);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    void hideFloatingToolbar() {
        if (getTextSelectionActionMode() != null) {
            removeCallbacks(mShowFloatingToolbar);
            getTextSelectionActionMode().hide(-1);
        }
    }

    public void updateFloatingToolbarVisibility(MotionEvent event) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && getTextSelectionActionMode() != null) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    hideFloatingToolbar();
                    break;
                case MotionEvent.ACTION_UP:  // fall through
                case MotionEvent.ACTION_CANCEL:
                    showFloatingToolbar();
            }
        }
    }

    private int getSelectionPreviewForegroundColor() {
        return mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND];
    }

    private int getSelectionPreviewBackgroundColor() {
        return mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND];
    }

}
