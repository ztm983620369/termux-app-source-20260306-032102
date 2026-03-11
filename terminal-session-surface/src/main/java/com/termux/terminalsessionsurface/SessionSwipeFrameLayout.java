package com.termux.terminalsessionsurface;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Marks the bottom toolbar area that is allowed to originate native session paging.
 * Gesture ownership lives in {@link ProgrammaticViewPager}; this view stays focused on layout.
 */
public class SessionSwipeFrameLayout extends LinearLayout {
    public SessionSwipeFrameLayout(@NonNull Context context) {
        this(context, null);
    }

    public SessionSwipeFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SessionSwipeFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
}
