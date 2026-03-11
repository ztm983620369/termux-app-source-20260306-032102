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

    @Nullable private SwipeRegionProvider swipeRegionProvider;
    private boolean gestureStartedInSwipeRegion;

    public ProgrammaticViewPager(@NonNull Context context) {
        super(context);
    }

    public ProgrammaticViewPager(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void setSwipeRegionProvider(@Nullable SwipeRegionProvider provider) {
        swipeRegionProvider = provider;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureStartedInSwipeRegion = isTouchInSwipeRegion(ev);
                if (!gestureStartedInSwipeRegion) {
                    return false;
                }
                return super.onInterceptTouchEvent(ev);
            case MotionEvent.ACTION_MOVE:
                if (!gestureStartedInSwipeRegion) {
                    return false;
                }
                return super.onInterceptTouchEvent(ev);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!gestureStartedInSwipeRegion) {
                    return false;
                }
                return super.onInterceptTouchEvent(ev);
            default:
                return gestureStartedInSwipeRegion && super.onInterceptTouchEvent(ev);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!gestureStartedInSwipeRegion && ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return false;
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureStartedInSwipeRegion = isTouchInSwipeRegion(ev);
                return gestureStartedInSwipeRegion && super.onTouchEvent(ev);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean handled = gestureStartedInSwipeRegion && super.onTouchEvent(ev);
                gestureStartedInSwipeRegion = false;
                return handled;
            default:
                return gestureStartedInSwipeRegion && super.onTouchEvent(ev);
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
