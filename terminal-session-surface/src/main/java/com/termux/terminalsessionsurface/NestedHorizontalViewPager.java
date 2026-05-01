package com.termux.terminalsessionsurface;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

public class NestedHorizontalViewPager extends ViewPager {
    private final int touchSlop;
    private float downX;
    private float downY;

    public NestedHorizontalViewPager(@NonNull Context context) {
        this(context, null);
    }

    public NestedHorizontalViewPager(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        updateParentIntercept(event);
        try {
            return super.onInterceptTouchEvent(event);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        updateParentIntercept(event);
        try {
            return super.onTouchEvent(event);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void updateParentIntercept(@NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                requestParentDisallowIntercept(true);
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(event.getX() - downX);
                float dy = Math.abs(event.getY() - downY);
                requestParentDisallowIntercept(dx > touchSlop && dx >= dy);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                requestParentDisallowIntercept(false);
                break;
            default:
                break;
        }
    }

    private void requestParentDisallowIntercept(boolean disallow) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(disallow);
        }
    }
}
