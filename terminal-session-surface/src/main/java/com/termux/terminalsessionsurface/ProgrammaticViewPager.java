package com.termux.terminalsessionsurface;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

/**
 * A pager that only accepts native horizontal swipes when the gesture starts inside
 * the registered bottom swipe region. Touches starting anywhere else are ignored so
 * the terminal keeps its native gesture handling.
 */
public class ProgrammaticViewPager extends ViewPager {
    public interface SwipeRegionProvider {
        @Nullable View getSwipeRegionView();
    }

    public interface SwipeGestureListener {
        void onSwipeTouchDownInRegion();
        void onSwipeGestureCaptured();
        void onSwipeGestureFinished();
    }

    @Nullable private SwipeRegionProvider swipeRegionProvider;
    @Nullable private SwipeGestureListener swipeGestureListener;
    private boolean gestureStartedInSwipeRegion;
    private boolean gestureCapturedByPager;

    public ProgrammaticViewPager(@NonNull Context context) {
        super(context);
    }

    public ProgrammaticViewPager(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
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
                gestureCapturedByPager = false;
                gestureStartedInSwipeRegion = isTouchInSwipeRegion(ev);
                if (gestureStartedInSwipeRegion && swipeGestureListener != null) {
                    swipeGestureListener.onSwipeTouchDownInRegion();
                }
                if (!gestureStartedInSwipeRegion) {
                    return false;
                }
                return dispatchIntercept(ev);
            case MotionEvent.ACTION_MOVE:
                if (!gestureStartedInSwipeRegion) {
                    return false;
                }
                return dispatchIntercept(ev);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!gestureStartedInSwipeRegion) {
                    return false;
                }
                boolean intercepted = dispatchIntercept(ev);
                finishSwipeGesture();
                return intercepted;
            default:
                return gestureStartedInSwipeRegion && dispatchIntercept(ev);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!gestureStartedInSwipeRegion && ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return false;
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureCapturedByPager = false;
                gestureStartedInSwipeRegion = isTouchInSwipeRegion(ev);
                if (gestureStartedInSwipeRegion && swipeGestureListener != null) {
                    swipeGestureListener.onSwipeTouchDownInRegion();
                }
                if (!gestureStartedInSwipeRegion) return false;
                boolean handledDown = super.onTouchEvent(ev);
                if (handledDown) {
                    captureSwipeGesture();
                }
                return handledDown;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean handledUp = gestureStartedInSwipeRegion && super.onTouchEvent(ev);
                finishSwipeGesture();
                return handledUp;
            default:
                if (!gestureStartedInSwipeRegion) return false;
                boolean handledMove = super.onTouchEvent(ev);
                if (handledMove) {
                    captureSwipeGesture();
                }
                return handledMove;
        }
    }

    private boolean dispatchIntercept(@NonNull MotionEvent event) {
        boolean intercepted = super.onInterceptTouchEvent(event);
        if (intercepted) {
            captureSwipeGesture();
        }
        return intercepted;
    }

    private void captureSwipeGesture() {
        if (gestureCapturedByPager) return;

        gestureCapturedByPager = true;
        if (swipeGestureListener != null) {
            swipeGestureListener.onSwipeGestureCaptured();
        }
    }

    private void finishSwipeGesture() {
        boolean notifyFinished = gestureStartedInSwipeRegion || gestureCapturedByPager;
        gestureStartedInSwipeRegion = false;
        gestureCapturedByPager = false;

        if (notifyFinished && swipeGestureListener != null) {
            swipeGestureListener.onSwipeGestureFinished();
        }
    }

    private boolean isTouchInSwipeRegion(@NonNull MotionEvent event) {
        View regionView = swipeRegionProvider == null ? null : swipeRegionProvider.getSwipeRegionView();
        if (regionView == null || !regionView.isShown() || regionView.getWidth() <= 0 || regionView.getHeight() <= 0) {
            return false;
        }

        Rect rect = new Rect();
        if (!regionView.getGlobalVisibleRect(rect)) {
            return false;
        }

        return rect.contains(Math.round(event.getRawX()), Math.round(event.getRawY()));
    }
}
