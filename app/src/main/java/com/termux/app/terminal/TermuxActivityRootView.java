package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.view.ViewUtils;
import com.termux.terminalsessionsurface.TerminalImeViewportPolicy;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Coordinates IME occlusion without changing the measured terminal geometry.
 *
 * <p>A terminal resize is process-visible: it emits {@code TIOCSWINSZ}, then SSH forwards a
 * {@code window-change}, and tmux may reflow every pane. The IME is only a temporary visual
 * occluder, so its animation and candidate-row changes must not resize this root view. The exact
 * occlusion is forwarded to the terminal surface as a bottom-chrome boundary. The terminal page
 * itself never moves for an IME transition.</p>
 *
 * <p>{@link WindowInsetsCompat.Type#ime()} is authoritative. A read-only visible-frame probe is
 * retained for OEM keyboards that fail to include a candidate row (or the entire IME) in their
 * inset. Unlike the historical workaround, the probe never writes layout params.</p>
 */
public class TermuxActivityRootView extends LinearLayout
    implements ViewTreeObserver.OnGlobalLayoutListener {

    public TermuxActivity mActivity;

    private int mLastImeBottomInset;
    private int mVisibleFrameImeBottomInset;
    @Nullable private View mBottomNavigationView;
    private final int[] mWindowRootLocation = new int[2];

    private final Set<WindowInsetsAnimationCompat> mRunningImeAnimations =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private final WindowInsetsAnimationCompat.Callback mImeAnimationCallback =
        new WindowInsetsAnimationCompat.Callback(
            WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            @Override
            public void onPrepare(@NonNull WindowInsetsAnimationCompat animation) {
                if (!isImeAnimation(animation)) return;
                mRunningImeAnimations.add(animation);
                dispatchImeViewportState();
            }

            @NonNull
            @Override
            public WindowInsetsAnimationCompat.BoundsCompat onStart(
                @NonNull WindowInsetsAnimationCompat animation,
                @NonNull WindowInsetsAnimationCompat.BoundsCompat bounds) {
                if (isImeAnimation(animation)) {
                    mRunningImeAnimations.add(animation);
                    dispatchImeViewportState();
                }
                return bounds;
            }

            @NonNull
            @Override
            public WindowInsetsCompat onProgress(@NonNull WindowInsetsCompat insets,
                @NonNull List<WindowInsetsAnimationCompat> runningAnimations) {
                mLastImeBottomInset =
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
                for (WindowInsetsAnimationCompat animation : runningAnimations) {
                    if (isImeAnimation(animation)) mRunningImeAnimations.add(animation);
                }
                dispatchImeViewportState();
                return insets;
            }

            @Override
            public void onEnd(@NonNull WindowInsetsAnimationCompat animation) {
                if (!isImeAnimation(animation)) return;
                mRunningImeAnimations.remove(animation);
                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(
                    TermuxActivityRootView.this);
                if (insets != null) {
                    mLastImeBottomInset =
                        insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
                }
                refreshVisibleFrameImeBottomInset();
                dispatchImeViewportState();

                // Some OEMs update getWindowVisibleDisplayFrame() one traversal after ending the
                // insets animation. Re-sample once, without installing a timer or changing layout.
                ViewCompat.postOnAnimation(TermuxActivityRootView.this, () -> {
                    if (!mRunningImeAnimations.isEmpty()) return;
                    if (refreshVisibleFrameImeBottomInset()) dispatchImeViewportState();
                });
            }
        };

    /** Log root view events. */
    private boolean ROOT_VIEW_LOGGING_ENABLED;

    private static final String LOG_TAG = "TermuxActivityRootView";
    private static int mStatusBarHeight;

    public TermuxActivityRootView(Context context) {
        super(context);
    }

    public TermuxActivityRootView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TermuxActivityRootView(Context context, @Nullable AttributeSet attrs,
        int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setActivity(TermuxActivity activity) {
        mActivity = activity;
        ViewCompat.setWindowInsetsAnimationCallback(this, mImeAnimationCallback);
    }

    public void setIsRootViewLoggingEnabled(boolean value) {
        ROOT_VIEW_LOGGING_ENABLED = value;
    }

    private void onWindowInsetsApplied(WindowInsetsCompat insets) {
        mLastImeBottomInset = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        if (mRunningImeAnimations.isEmpty()) refreshVisibleFrameImeBottomInset();
        dispatchImeViewportState();
    }

    /** Returns the authoritative inset plus any OEM-visible candidate-row under-reporting. */
    public int getLastImeBottomInset() {
        return getEffectiveImeBottomInset();
    }

    /** Returns the real IME inset; app chrome is intentionally not folded into this value. */
    public int getTerminalViewportBottomInset() {
        return getEffectiveImeBottomInset();
    }

    /**
     * Returns the exact window-space bottom boundary for movable terminal chrome.
     *
     * <p>The app bottom navigation is persistent window chrome: it stays structurally anchored at
     * the window bottom and is occluded by a docked IME. Only terminal-local chrome moves to this
     * boundary, which is therefore always the real IME top. This is deliberately an absolute
     * coordinate so callers cannot accidentally fold navigation height into the IME inset.</p>
     */
    public int getTerminalChromeBoundaryInWindow() {
        return resolveTerminalChromeBoundaryInWindow();
    }

    /** Called after Activity changes the bottom-navigation visibility or other presentation state. */
    public void onBottomNavigationPresentationChanged() {
        dispatchImeViewportState();
    }

    public boolean isImeAnimationRunning() {
        return !mRunningImeAnimations.isEmpty();
    }

    public void dispatchCurrentImeViewportState() {
        if (mRunningImeAnimations.isEmpty()) refreshVisibleFrameImeBottomInset();
        dispatchImeViewportState();
    }

    private int getEffectiveImeBottomInset() {
        // During animation the inset is frame-synchronised. A visible-frame query may already
        // report the final keyboard bounds and would otherwise create a one-frame jump.
        if (!mRunningImeAnimations.isEmpty()) return Math.max(0, mLastImeBottomInset);
        return Math.max(Math.max(0, mLastImeBottomInset), mVisibleFrameImeBottomInset);
    }

    private void dispatchImeViewportState() {
        int imeInset = getEffectiveImeBottomInset();
        int chromeBoundaryInWindow = resolveTerminalChromeBoundaryInWindow();
        clearLegacyImeMargin();
        if (mActivity != null) {
            mActivity.onTerminalImeViewportChanged(imeInset, chromeBoundaryInWindow,
                !mRunningImeAnimations.isEmpty());
        }
    }

    private static boolean isImeAnimation(@NonNull WindowInsetsAnimationCompat animation) {
        return (animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0;
    }

    private int resolveTerminalChromeBoundaryInWindow() {
        enforceBottomNavigationWindowAnchor();
        int windowBottom = getWindowBottomInWindow();
        int imeInset = getEffectiveImeBottomInset();
        return TerminalImeViewportPolicy.computeImeTopInWindow(windowBottom, imeInset);
    }

    /**
     * Keeps primary navigation in the stable app coordinate space.
     *
     * <p>A docked keyboard is a separate occluding window. Translating primary navigation to the
     * IME top makes it appear opportunistically during typing and also steals a second strip from
     * the terminal viewport. Resetting translation is layout-free, so terminal rows and columns
     * remain unchanged throughout the IME animation.</p>
     */
    private void enforceBottomNavigationWindowAnchor() {
        if (mActivity == null) return;
        View bottomNavigationView = mBottomNavigationView;
        if (bottomNavigationView == null) {
            bottomNavigationView = mActivity.findViewById(R.id.bottom_navigation);
            mBottomNavigationView = bottomNavigationView;
        }
        if (bottomNavigationView != null && bottomNavigationView.getTranslationY() != 0f) {
            bottomNavigationView.setTranslationY(0f);
        }
    }

    private int getWindowBottomInWindow() {
        View windowRoot = getRootView();
        if (windowRoot == null || windowRoot.getHeight() <= 0) return 0;
        windowRoot.getLocationInWindow(mWindowRootLocation);
        return mWindowRootLocation[1] + windowRoot.getHeight();
    }

    /** Removes stale state written by older builds; subsequent IME frames are layout-write free. */
    private void clearLegacyImeMargin() {
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        if (!(layoutParams instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) layoutParams;
        if (params.bottomMargin == 0) return;
        params.bottomMargin = 0;
        setLayoutParams(params);
    }

    /**
     * Samples the visible frame as a conservative OEM fallback. A large threshold is required
     * when Android reports no IME inset so status/navigation bars cannot be mistaken for a
     * keyboard. Once the IME is known, smaller differences may represent a candidate row.
     */
    private boolean refreshVisibleFrameImeBottomInset() {
        int observedInset = 0;
        if (mActivity != null && mActivity.isVisible()) {
            View bottomSpaceView = mActivity.getTermuxActivityBottomSpaceView();
            Rect[] rects = ViewUtils.getWindowAndViewRects(bottomSpaceView, mStatusBarHeight);
            if (rects != null) {
                int hiddenPixels = Math.max(0, rects[1].bottom - rects[0].bottom);
                int keyboardThreshold = Math.max(1, getHeight() / 4);
                boolean imeKnown = mLastImeBottomInset > 0 ||
                    !mRunningImeAnimations.isEmpty();
                if (imeKnown || hiddenPixels > keyboardThreshold) {
                    observedInset = hiddenPixels;
                }
                if (ROOT_VIEW_LOGGING_ENABLED) {
                    Logger.logVerbose(LOG_TAG, "visible-frame IME probe: hidden=" +
                        hiddenPixels + ", inset=" + mLastImeBottomInset +
                        ", fallback=" + observedInset + ", threshold=" +
                        keyboardThreshold);
                }
            }
        }

        if (mVisibleFrameImeBottomInset == observedInset) return false;
        mVisibleFrameImeBottomInset = observedInset;
        return true;
    }

    @Override
    public void onGlobalLayout() {
        if (mActivity == null || !mActivity.isVisible()) return;
        clearLegacyImeMargin();
        enforceBottomNavigationWindowAnchor();
        if (refreshVisibleFrameImeBottomInset()) dispatchImeViewportState();
    }

    public static class WindowInsetsListener
        implements androidx.core.view.OnApplyWindowInsetsListener {
        @NonNull
        @Override
        public WindowInsetsCompat onApplyWindowInsets(@NonNull View v,
            @NonNull WindowInsetsCompat insets) {
            mStatusBarHeight =
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            if (v instanceof TermuxActivityRootView) {
                ((TermuxActivityRootView) v).onWindowInsetsApplied(insets);
            }
            return insets;
        }
    }
}
