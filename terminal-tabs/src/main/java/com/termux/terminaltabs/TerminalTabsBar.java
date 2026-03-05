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
    private int mLastSelectedIndex = -1;

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
        updateTabsIncremental();
    }

    public interface OnTabCloseListener {
        void onTabClose(int index);
    }

    private OnTabCloseListener mOnTabCloseListener;

    public void setOnTabCloseListener(@Nullable OnTabCloseListener listener) {
        mOnTabCloseListener = listener;
    }

    private void updateTabsIncremental() {
        ensureAddButton();

        int selectedIndex = -1;
        int targetTabCount = mTabs.size();

        for (int i = 0; i < targetTabCount; i++) {
            Tab tab = mTabs.get(i);
            if (tab.selected) selectedIndex = i;

            View child = mTabsContainer.getChildAt(i);
            TabViewHolder holder = getTabViewHolder(child);
            if (holder == null) {
                holder = createTabViewHolder();
                mTabsContainer.addView(holder.root, i, createTabLayoutParams());
            }

            bindTabView(holder, tab, i);
        }

        int addTargetIndex = targetTabCount;
        if (mAddButton.getParent() != mTabsContainer) {
            mTabsContainer.addView(mAddButton, addTargetIndex, createAddButtonLayoutParams());
        } else {
            int currentAddIndex = mTabsContainer.indexOfChild(mAddButton);
            if (currentAddIndex != addTargetIndex) {
                mTabsContainer.removeViewAt(currentAddIndex);
                mTabsContainer.addView(mAddButton, addTargetIndex, createAddButtonLayoutParams());
            }
        }

        for (int i = mTabsContainer.getChildCount() - 1; i >= 0; i--) {
            View child = mTabsContainer.getChildAt(i);
            if (child == mAddButton) continue;
            if (i >= targetTabCount) {
                mTabsContainer.removeViewAt(i);
            }
        }

        if (selectedIndex >= 0 && selectedIndex != mLastSelectedIndex) {
            int indexToScroll = selectedIndex;
            post(() -> scrollToTab(indexToScroll));
        }
        mLastSelectedIndex = selectedIndex;
    }

    @Nullable
    private TabViewHolder getTabViewHolder(@Nullable View child) {
        if (child == null) return null;
        Object tag = child.getTag();
        if (!(tag instanceof TabViewHolder)) return null;
        return (TabViewHolder) tag;
    }

    private void bindTabView(@NonNull TabViewHolder holder, @NonNull Tab tab, int index) {
        holder.index = index;
        holder.key = tab.key;
        holder.titleView.setText(tab.title);
        holder.root.setBackground(createTabBackground(tab.selected, tab.locked));
        holder.root.setAlpha(tab.selected ? 1.0f : 0.7f);
        holder.closeBtn.setVisibility(tab.locked ? View.GONE : View.VISIBLE);
    }

    @NonNull
    private TabViewHolder createTabViewHolder() {
        LinearLayout tabLayout = new LinearLayout(getContext());
        tabLayout.setOrientation(HORIZONTAL);
        tabLayout.setGravity(Gravity.CENTER);
        tabLayout.setMinimumWidth(dp(80));

        TextView titleView = new TextView(getContext());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT);
        titleLp.weight = 1f;
        tabLayout.addView(titleView, titleLp);

        TextView closeBtn = new TextView(getContext());
        closeBtn.setText("\u00D7");
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        closeBtn.setTextColor(Color.LTGRAY);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(dp(4), 0, dp(8), 0);
        closeBtn.setClickable(true);
        closeBtn.setFocusable(true);
        tabLayout.addView(closeBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

        TabViewHolder holder = new TabViewHolder(tabLayout, titleView, closeBtn);
        tabLayout.setTag(holder);

        closeBtn.setOnClickListener(v -> {
            if (mOnTabCloseListener != null && holder.index >= 0) {
                mOnTabCloseListener.onTabClose(holder.index);
            }
        });

        tabLayout.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                holder.longPressTriggered = false;
                holder.trackingLongPress = true;
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                holder.downX = event.getX();
                holder.downY = event.getY();
                v.postDelayed(holder.longPressRunnable, 1000);
                return true;
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (holder.trackingLongPress &&
                    (Math.abs(event.getX() - holder.downX) > holder.moveThreshold ||
                        Math.abs(event.getY() - holder.downY) > holder.moveThreshold)) {
                    holder.trackingLongPress = false;
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                    v.removeCallbacks(holder.longPressRunnable);
                }
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                holder.trackingLongPress = false;
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                v.removeCallbacks(holder.longPressRunnable);
                if (action == MotionEvent.ACTION_UP) {
                    if (holder.longPressTriggered) {
                        holder.longPressTriggered = false;
                        return true;
                    }
                    v.performClick();
                } else {
                    holder.longPressTriggered = false;
                }
                return true;
            }
            return true;
        });

        tabLayout.setOnClickListener(v -> {
            if (holder.longPressTriggered) {
                holder.longPressTriggered = false;
                return;
            }
            if (mOnTabSelectedListener != null &&
                holder.index >= 0 && holder.index < mTabs.size()) {
                mOnTabSelectedListener.onTabSelected(holder.index, mTabs.get(holder.index));
            }
        });

        return holder;
    }

    private void ensureAddButton() {
        if (mAddButton != null) return;
        mAddButton = new TextView(getContext());
        mAddButton.setText("+");
        mAddButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        mAddButton.setGravity(Gravity.CENTER);
        mAddButton.setTextColor(Color.WHITE);
        mAddButton.setBackground(createTabBackground(false, false));
        mAddButton.setMinimumWidth(dp(48));
        mAddButton.setOnClickListener(v -> {
            if (mOnAddClickListener != null) mOnAddClickListener.run();
        });
        mAddButton.setOnLongClickListener(v -> {
            mAddButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (mOnAddLongPressListener != null) mOnAddLongPressListener.onAddLongPress();
            return true;
        });
    }

    @NonNull
    private LinearLayout.LayoutParams createTabLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(30));
        lp.weight = 1f;
        lp.setMargins(dp(1), dp(2), dp(1), dp(2));
        return lp;
    }

    @NonNull
    private LinearLayout.LayoutParams createAddButtonLayoutParams() {
        LinearLayout.LayoutParams lpAdd = new LinearLayout.LayoutParams(0, dp(30));
        lpAdd.weight = 1f;
        lpAdd.setMargins(dp(1), dp(2), dp(1), dp(2));
        return lpAdd;
    }

    private final class TabViewHolder {
        @NonNull final LinearLayout root;
        @NonNull final TextView titleView;
        @NonNull final TextView closeBtn;
        int index = -1;
        @Nullable String key;
        boolean longPressTriggered = false;
        boolean trackingLongPress = false;
        float downX = 0f;
        float downY = 0f;
        final int moveThreshold;
        @NonNull final Runnable longPressRunnable;

        TabViewHolder(@NonNull LinearLayout root, @NonNull TextView titleView, @NonNull TextView closeBtn) {
            this.root = root;
            this.titleView = titleView;
            this.closeBtn = closeBtn;
            this.moveThreshold = ViewConfiguration.get(getContext()).getScaledTouchSlop() * 3;
            this.longPressRunnable = () -> {
                if (!trackingLongPress) return;
                longPressTriggered = true;
                trackingLongPress = false;
                root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (mOnTabLongPressListener != null && index >= 0 && index < mTabs.size()) {
                    mOnTabLongPressListener.onTabLongPress(index, mTabs.get(index));
                }
            };
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

