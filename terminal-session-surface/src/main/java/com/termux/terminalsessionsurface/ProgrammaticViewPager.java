package com.termux.terminalsessionsurface;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

/**
 * A pager that only accepts native horizontal swipes when the gesture starts inside
 * the registered bottom swipe region. Touches starting anywhere else are ignored so
 * the terminal keeps its native gesture handling.
 */
public class ProgrammaticViewPager extends ViewPager {
    private static final String LOG_TAG = "TerminalSessionSurface";

    public interface SwipeRegionProvider {
        @Nullable View getSwipeRegionView();
    }

    public interface SwipeGestureListener {
        void onSwipeTouchDownInRegion();
        void onSwipeGestureCaptured();
        void onSwipeTargetChanged(int pageDelta);
        void onSwipeGestureFinished();
    }

    @Nullable private SwipeRegionProvider swipeRegionProvider;
    @Nullable private SwipeGestureListener swipeGestureListener;
    @NonNull
    private final TerminalSessionSwipeGestureStateMachine gestureStateMachine =
        new TerminalSessionSwipeGestureStateMachine();
    @NonNull private final Rect swipeRegionBounds = new Rect();
    private final float touchSlop;
    private float gestureDownX = Float.NaN;
    private float gestureDownY = Float.NaN;
    private int dispatchedPageDelta;

    public ProgrammaticViewPager(@NonNull Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public ProgrammaticViewPager(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setSwipeRegionProvider(@Nullable SwipeRegionProvider provider) {
        swipeRegionProvider = provider;
    }

    public void setSwipeGestureListener(@Nullable SwipeGestureListener listener) {
        swipeGestureListener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dispatchDown(ev);
                if (!gestureStateMachine.isEligible(ev.getDownTime())) return false;
                return dispatchIntercept(ev);
            case MotionEvent.ACTION_MOVE:
                if (!gestureStateMachine.isEligible(ev.getDownTime())) return false;
                boolean moveIntercepted = dispatchIntercept(ev);
                dispatchSwipeTargetIfChanged(ev);
                return moveIntercepted;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!gestureStateMachine.isEligible(ev.getDownTime())) {
                    gestureStateMachine.onFinished(ev.getDownTime());
                    return false;
                }
                boolean intercepted = dispatchIntercept(ev);
                dispatchGestureSignals(gestureStateMachine.onFinished(ev.getDownTime()));
                return intercepted;
            default:
                return gestureStateMachine.isEligible(ev.getDownTime()) && dispatchIntercept(ev);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!gestureStateMachine.isEligible(ev.getDownTime()) && ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return false;
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dispatchDown(ev);
                if (!gestureStateMachine.isEligible(ev.getDownTime())) return false;
                return dispatchTouch(ev);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean handledUp = gestureStateMachine.isEligible(ev.getDownTime()) && dispatchTouch(ev);
                dispatchGestureSignals(gestureStateMachine.onFinished(ev.getDownTime()));
                return handledUp;
            default:
                if (!gestureStateMachine.isEligible(ev.getDownTime())) return false;
                boolean handledMove = dispatchTouch(ev);
                if (handledMove && ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    dispatchGestureSignals(gestureStateMachine.onCaptured(ev.getDownTime()));
                    dispatchSwipeTargetIfChanged(ev);
                }
                return handledMove;
        }
    }

    private boolean dispatchIntercept(@NonNull MotionEvent event) {
        final boolean intercepted;
        try {
            intercepted = super.onInterceptTouchEvent(event);
        } catch (IllegalArgumentException ignored) {
            dispatchGestureSignals(gestureStateMachine.onFinished(event.getDownTime()));
            return false;
        }
        if (intercepted) {
            dispatchGestureSignals(gestureStateMachine.onCaptured(event.getDownTime()));
        }
        return intercepted;
    }

    private boolean dispatchTouch(@NonNull MotionEvent event) {
        try {
            return super.onTouchEvent(event);
        } catch (IllegalArgumentException ignored) {
            dispatchGestureSignals(gestureStateMachine.onFinished(event.getDownTime()));
            return false;
        }
    }

    private void dispatchDown(@NonNull MotionEvent event) {
        boolean touchInSwipeRegion = isTouchInSwipeRegion(event);
        int signals = gestureStateMachine.onDown(event.getDownTime(), touchInSwipeRegion);
        if ((signals & TerminalSessionSwipeGestureStateMachine.SIGNAL_TOUCH_DOWN) != 0) {
            gestureDownX = event.getX();
            gestureDownY = event.getY();
            dispatchedPageDelta = 0;
        }
        dispatchGestureSignals(signals);
    }

    private void dispatchSwipeTargetIfChanged(@NonNull MotionEvent event) {
        if (swipeGestureListener == null ||
            gestureStateMachine.getState() != TerminalSessionSwipeGestureStateMachine.State.CAPTURED ||
            Float.isNaN(gestureDownX)) return;
        int pageDelta = TerminalSessionTransitionFrameState.pageDeltaForDrag(
            gestureDownX, event.getX());
        if (pageDelta == dispatchedPageDelta) return;
        dispatchedPageDelta = pageDelta;
        Log.i(LOG_TAG, "pager-swipe-v1 target=" + pageDelta +
            " dx=" + Math.round(event.getX() - gestureDownX) +
            " dy=" + Math.round(event.getY() - gestureDownY) +
            " slop=" + Math.round(touchSlop));
        swipeGestureListener.onSwipeTargetChanged(pageDelta);
    }

    private void dispatchGestureSignals(int signals) {
        if (signals == TerminalSessionSwipeGestureStateMachine.SIGNAL_NONE) return;
        SwipeGestureListener listener = swipeGestureListener;
        if (listener != null) {
            if ((signals & TerminalSessionSwipeGestureStateMachine.SIGNAL_FINISHED) != 0) {
                listener.onSwipeGestureFinished();
            }
            if (shouldStartPagerOwnedSwipe(signals)) {
                // Only the pager that actually captures the drag may start keyboard preservation.
                listener.onSwipeTouchDownInRegion();
                listener.onSwipeGestureCaptured();
            }
        }
        if ((signals & TerminalSessionSwipeGestureStateMachine.SIGNAL_FINISHED) != 0 &&
            (signals & TerminalSessionSwipeGestureStateMachine.SIGNAL_TOUCH_DOWN) == 0) {
            gestureDownX = Float.NaN;
            gestureDownY = Float.NaN;
            dispatchedPageDelta = 0;
        }
    }

    static boolean shouldStartPagerOwnedSwipe(int signals) {
        return (signals & TerminalSessionSwipeGestureStateMachine.SIGNAL_CAPTURED) != 0;
    }

    private boolean isTouchInSwipeRegion(@NonNull MotionEvent event) {
        View regionView = swipeRegionProvider == null ? null : swipeRegionProvider.getSwipeRegionView();
        if (regionView == null || !regionView.isShown() || regionView.getWidth() <= 0 || regionView.getHeight() <= 0) {
            return false;
        }

        if (!regionView.getGlobalVisibleRect(swipeRegionBounds)) {
            return false;
        }

        return swipeRegionBounds.contains(Math.round(event.getRawX()), Math.round(event.getRawY()));
    }
}
