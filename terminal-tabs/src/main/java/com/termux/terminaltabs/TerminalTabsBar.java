package com.termux.terminaltabs;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class TerminalTabsBar extends LinearLayout {

    public interface OnTabSelectedListener {
        void onTabSelected(int index, @NonNull Tab tab);
    }

    public interface OnTabLongPressListener {
        void onTabLongPress(int index, @NonNull Tab tab);
    }

    public interface OnAddLongPressListener {
        void onAddLongPress();
    }

    public static final class Tab {
        @NonNull
        public final String key;
        @NonNull
        public final String title;
        public final boolean selected;
        public final boolean locked;

        public Tab(@NonNull String key, @NonNull String title, boolean selected, boolean locked) {
            this.key = key;
            this.title = title;
            this.selected = selected;
            this.locked = locked;
        }
    }

    private HorizontalScrollView mScrollView;
    private LinearLayout mTabsContainer;
    private TextView mAddButton;

    @Nullable
    private OnTabSelectedListener mOnTabSelectedListener;
    @Nullable
    private OnTabLongPressListener mOnTabLongPressListener;
    @Nullable
    private Runnable mOnAddClickListener;
    @Nullable
    private OnAddLongPressListener mOnAddLongPressListener;

    private final List<Tab> mTabs = new ArrayList<>();

    public TerminalTabsBar(Context context) {
        super(context);
        init(context);
    }

    public TerminalTabsBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public TerminalTabsBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(HORIZONTAL);
        setBackgroundColor(0xFF121212);
        LayoutInflater.from(context).inflate(R.layout.view_terminal_tabs_bar, this, true);
        mScrollView = findViewById(R.id.terminal_tabs_scroll);
        mTabsContainer = findViewById(R.id.terminal_tabs_container);
        // Add button is now dynamically added to the container
    }

    public void setOnTabSelectedListener(@Nullable OnTabSelectedListener listener) {
        mOnTabSelectedListener = listener;
    }

    public void setOnAddClickListener(@Nullable Runnable listener) {
        mOnAddClickListener = listener;
    }

    public void setOnTabLongPressListener(@Nullable OnTabLongPressListener listener) {
        mOnTabLongPressListener = listener;
    }

    public void setOnAddLongPressListener(@Nullable OnAddLongPressListener listener) {
        mOnAddLongPressListener = listener;
    }

    public void setTabs(@NonNull List<Tab> tabs) {
        mTabs.clear();
        mTabs.addAll(tabs);
        rebuild();
    }

    public interface OnTabCloseListener {
        void onTabClose(int index);
    }

    private OnTabCloseListener mOnTabCloseListener;

    public void setOnTabCloseListener(@Nullable OnTabCloseListener listener) {
        mOnTabCloseListener = listener;
    }

    private void rebuild() {
        mTabsContainer.removeAllViews();

        int selectedIndex = -1;
        // Calculate dynamic width strategy
        // We want tabs to fill the screen. 
        // We use weight=1 for all tabs and the add button.
        // We also set a minimum width to ensure they don't get too small.

        for (int i = 0; i < mTabs.size(); i++) {
            Tab tab = mTabs.get(i);
            if (tab.selected) selectedIndex = i;

            // Tab container (Horizontal LinearLayout)
            LinearLayout tabLayout = new LinearLayout(getContext());
            tabLayout.setOrientation(HORIZONTAL);
            tabLayout.setGravity(Gravity.CENTER);
            tabLayout.setBackground(createTabBackground(tab.selected, tab.locked));
            tabLayout.setAlpha(tab.selected ? 1.0f : 0.7f);
            
            // Text View for Title
            TextView titleView = new TextView(getContext());
            titleView.setText(tab.title);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setGravity(Gravity.CENTER);
            titleView.setTextColor(Color.WHITE);
            
            // Add title with weight 1 to push close button to right if needed, or just center everything
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT);
            titleLp.weight = 1f;
            tabLayout.addView(titleView, titleLp);

            // Close Button "x"
            TextView closeBtn = new TextView(getContext());
            closeBtn.setText("\u00D7");
            closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            closeBtn.setTextColor(Color.LTGRAY); // Slightly dimmer than title
            closeBtn.setGravity(Gravity.CENTER);
            closeBtn.setPadding(dp(4), 0, dp(8), 0); // Padding for touch area
            
            // Make sure close button is clickable and focusable
            closeBtn.setClickable(true);
            closeBtn.setFocusable(true);

            int index = i;
            closeBtn.setOnClickListener(v -> {
                if (mOnTabCloseListener != null) mOnTabCloseListener.onTabClose(index);
            });
            closeBtn.setVisibility(tab.locked ? View.GONE : View.VISIBLE);
            
            tabLayout.addView(closeBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

            // Click listener for the whole tab to switch
            // IMPORTANT: We use setOnClickListener on the layout, but we need to ensure it doesn't block the close button.
            // The close button is added to the layout, so its click event should bubble up if not handled,
            // but since we set an OnClickListener on the close button, it should handle it first.
            // However, to be safe, let's make the close button focusable and clickable explicitly (done above).
            
            final boolean[] longPressTriggered = {false};
            final boolean[] trackingLongPress = {false};
            final float[] down = new float[]{0f, 0f};
            final int moveThreshold = ViewConfiguration.get(getContext()).getScaledTouchSlop() * 3;
            final Runnable longPressRunnable = () -> {
                if (!trackingLongPress[0]) return;
                longPressTriggered[0] = true;
                trackingLongPress[0] = false;
                tabLayout.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (mOnTabLongPressListener != null && index >= 0 && index < mTabs.size()) {
                    mOnTabLongPressListener.onTabLongPress(index, mTabs.get(index));
                }
            };

            tabLayout.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    longPressTriggered[0] = false;
                    trackingLongPress[0] = true;
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    down[0] = event.getX();
                    down[1] = event.getY();
                    v.postDelayed(longPressRunnable, 1000);
                    return true;
                } else if (action == MotionEvent.ACTION_MOVE) {
                    if (trackingLongPress[0] &&
                        (Math.abs(event.getX() - down[0]) > moveThreshold || Math.abs(event.getY() - down[1]) > moveThreshold)) {
                        trackingLongPress[0] = false;
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                        v.removeCallbacks(longPressRunnable);
                    }
                    return true;
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    trackingLongPress[0] = false;
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                    v.removeCallbacks(longPressRunnable);
                    if (action == MotionEvent.ACTION_UP) {
                        if (longPressTriggered[0]) {
                            longPressTriggered[0] = false;
                            return true;
                        }
                        v.performClick();
                    } else {
                        longPressTriggered[0] = false;
                    }
                    return true;
                }
                return true;
            });

            tabLayout.setOnClickListener(v -> {
                if (longPressTriggered[0]) {
                    longPressTriggered[0] = false;
                    return;
                }
                if (mOnTabSelectedListener != null && index >= 0 && index < mTabs.size()) {
                    mOnTabSelectedListener.onTabSelected(index, mTabs.get(index));
                }
            });

            // Layout params for the tab container
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(30)); // Flatter height: 30dp
            lp.weight = 1f;
            // Set min width to avoid crushing on small screens with many tabs
            tabLayout.setMinimumWidth(dp(80)); 
            
            // Add a small margin to separate tabs visually
            lp.setMargins(dp(1), dp(2), dp(1), dp(2));
            
            mTabsContainer.addView(tabLayout, lp);
        }

        // Add the "+" button as the last item, also participating in weight distribution
        TextView addButton = new TextView(getContext());
        addButton.setText("+");
        addButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18); // Slightly larger plus
        addButton.setGravity(Gravity.CENTER);
        addButton.setTextColor(Color.WHITE);
        addButton.setBackground(createTabBackground(false, false));
        addButton.setOnClickListener(v -> {
            if (mOnAddClickListener != null) mOnAddClickListener.run();
        });
        addButton.setOnLongClickListener(v -> {
            addButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (mOnAddLongPressListener != null) mOnAddLongPressListener.onAddLongPress();
            return true;
        });

        // The "+" button also gets weight=1 to be "equivalent" to a terminal tab
        LinearLayout.LayoutParams lpAdd = new LinearLayout.LayoutParams(0, dp(30)); // Match tab height
        lpAdd.weight = 1f;
        addButton.setMinimumWidth(dp(48)); // Slightly smaller min width for plus button
        lpAdd.setMargins(dp(1), dp(2), dp(1), dp(2));
        
        mTabsContainer.addView(addButton, lpAdd);

        if (selectedIndex >= 0) {
            int indexToScroll = selectedIndex;
            post(() -> scrollToTab(indexToScroll));
        }
    }

    private GradientDrawable createTabBackground(boolean selected, boolean locked) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        // Slight rounding
        float radius = dp(4);
        drawable.setCornerRadii(new float[]{radius, radius, radius, radius, radius, radius, radius, radius});
        
        if (locked) {
            if (selected) {
                drawable.setColor(0xFF2E7D32);
                drawable.setStroke(dp(1), 0xFFA5D6A7);
            } else {
                drawable.setColor(0xFF1B5E20);
                drawable.setStroke(dp(1), 0xFF2E7D32);
            }
        } else {
            // Dark background for all, but selected is lighter
            if (selected) {
                drawable.setColor(0xFF333333); // Lighter gray for active
                drawable.setStroke(dp(1), 0xFF888888); // Highlight stroke
            } else {
                drawable.setColor(0xFF1E1E1E); // Dark gray for inactive
                drawable.setStroke(dp(1), 0xFF333333); // Subtle stroke
            }
        }
        return drawable;
    }

    private void scrollToTab(int index) {
        View child = mTabsContainer.getChildAt(index);
        if (child == null) return;
        int scrollX = child.getLeft() - dp(16);
        mScrollView.smoothScrollTo(Math.max(scrollX, 0), 0);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}

