package com.termux.app.topbar;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
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
import androidx.core.graphics.ColorUtils;

import com.termux.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class TerminalTopBarView extends LinearLayout {

    public interface OnTabSelectedListener {
        void onTabSelected(int index, @NonNull Item item);
    }

    public interface OnTabLongPressListener {
        void onTabLongPress(int index, @NonNull Item item);
    }

    public interface OnAddLongPressListener {
        void onAddLongPress();
    }

    public interface OnTabCloseListener {
        void onTabClose(int index);
    }

    public static final class Item {
        @NonNull public final String key;
        @NonNull public final String title;
        public final boolean selected;
        public final boolean locked;
        public final boolean closable;
        @NonNull public final TerminalTopBarStateMachine.Tone tone;
        @Nullable public final String badgeText;
        @Nullable public final String contentDescription;

        public Item(@NonNull String key, @NonNull String title, boolean selected, boolean locked,
                    boolean closable, @NonNull TerminalTopBarStateMachine.Tone tone,
                    @Nullable String badgeText, @Nullable String contentDescription) {
            this.key = key;
            this.title = title;
            this.selected = selected;
            this.locked = locked;
            this.closable = closable;
            this.tone = tone;
            this.badgeText = badgeText;
            this.contentDescription = contentDescription;
        }
    }

    private HorizontalScrollView mScrollView;
    private LinearLayout mTabsContainer;
    private TextView mAddButton;

    @Nullable private OnTabSelectedListener mOnTabSelectedListener;
    @Nullable private OnTabLongPressListener mOnTabLongPressListener;
    @Nullable private Runnable mOnAddClickListener;
    @Nullable private OnAddLongPressListener mOnAddLongPressListener;
    @Nullable private OnTabCloseListener mOnTabCloseListener;

    private final List<Item> mItems = new ArrayList<>();
    private int mLastSelectedIndex = -1;
    private int mTabMoveThresholdPx;
    private int mLongPressTimeoutMs;

    public TerminalTopBarView(Context context) {
        super(context);
        init(context);
    }

    public TerminalTopBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public TerminalTopBarView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(HORIZONTAL);
        setBackgroundColor(0xFF121212);
        LayoutInflater.from(context).inflate(R.layout.view_terminal_top_bar, this, true);
        mScrollView = findViewById(R.id.terminal_top_bar_scroll);
        mTabsContainer = findViewById(R.id.terminal_top_bar_container);
        ViewConfiguration viewConfiguration = ViewConfiguration.get(context);
        mTabMoveThresholdPx = viewConfiguration.getScaledTouchSlop();
        mLongPressTimeoutMs = ViewConfiguration.getLongPressTimeout();
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

    public void setOnTabCloseListener(@Nullable OnTabCloseListener listener) {
        mOnTabCloseListener = listener;
    }

    public void setItems(@NonNull List<Item> items) {
        mItems.clear();
        mItems.addAll(items);
        updateItems();
    }

    private void updateItems() {
        ensureAddButton();

        int selectedIndex = -1;
        int targetCount = mItems.size();
        LinkedHashMap<String, TabViewHolder> holdersByKey = new LinkedHashMap<>();
        ArrayList<TabViewHolder> recycledHolders = new ArrayList<>();

        for (int i = 0; i < mTabsContainer.getChildCount(); i++) {
            View child = mTabsContainer.getChildAt(i);
            if (child == mAddButton) continue;

            TabViewHolder holder = getTabViewHolder(child);
            if (holder == null) continue;

            if (!TextUtils.isEmpty(holder.key) && !holdersByKey.containsKey(holder.key)) {
                holdersByKey.put(holder.key, holder);
            } else {
                recycledHolders.add(holder);
            }
        }

        ArrayList<View> orderedViews = new ArrayList<>(targetCount);
        for (int i = 0; i < targetCount; i++) {
            Item item = mItems.get(i);
            if (item.selected) selectedIndex = i;

            TabViewHolder holder = TextUtils.isEmpty(item.key) ? null : holdersByKey.remove(item.key);
            if (holder == null && !recycledHolders.isEmpty()) {
                holder = recycledHolders.remove(recycledHolders.size() - 1);
            }
            if (holder == null) {
                holder = createTabViewHolder();
            }

            bindTabView(holder, item, i);
            orderedViews.add(holder.root);
        }

        mTabsContainer.removeAllViews();
        for (View tabView : orderedViews) {
            mTabsContainer.addView(tabView, createTabLayoutParams());
        }
        mTabsContainer.addView(mAddButton, createAddButtonLayoutParams());

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

    private void bindTabView(@NonNull TabViewHolder holder, @NonNull Item item, int index) {
        holder.index = index;
        holder.key = item.key;
        holder.titleView.setText(item.title);
        holder.root.setBackground(createTabBackground(item.selected, item.tone));
        holder.root.setAlpha(item.selected ? 1.0f : 0.88f);
        holder.root.setContentDescription(TextUtils.isEmpty(item.contentDescription) ? item.title : item.contentDescription);
        holder.titleView.setTextColor(item.selected ? Color.WHITE : 0xFFE6E6E6);
        holder.statusDot.setBackground(createStatusDotBackground(item.tone));
        bindBadgeView(holder.badgeView, item);
        holder.closeBtn.setVisibility(item.closable ? View.VISIBLE : View.GONE);
        holder.closeBtn.setTextColor(item.selected ? 0xFFF2F2F2 : 0xFFBDBDBD);
    }

    @NonNull
    private TabViewHolder createTabViewHolder() {
        LinearLayout tabLayout = new LinearLayout(getContext());
        tabLayout.setOrientation(HORIZONTAL);
        tabLayout.setGravity(Gravity.CENTER_VERTICAL);
        tabLayout.setMinimumWidth(dp(80));

        View statusDot = new View(getContext());
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.setMargins(dp(8), 0, dp(6), 0);
        tabLayout.addView(statusDot, dotLp);

        TextView titleView = new TextView(getContext());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT);
        titleLp.weight = 1f;
        tabLayout.addView(titleView, titleLp);

        TextView badgeView = new TextView(getContext());
        badgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        badgeView.setTypeface(Typeface.DEFAULT_BOLD);
        badgeView.setAllCaps(true);
        badgeView.setGravity(Gravity.CENTER);
        badgeView.setPadding(dp(5), dp(1), dp(5), dp(1));
        badgeView.setVisibility(View.GONE);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.setMargins(dp(4), 0, dp(2), 0);
        tabLayout.addView(badgeView, badgeLp);

        TextView closeBtn = new TextView(getContext());
        closeBtn.setText("\u00D7");
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        closeBtn.setTextColor(Color.LTGRAY);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(dp(4), 0, dp(8), 0);
        closeBtn.setClickable(true);
        closeBtn.setFocusable(true);
        tabLayout.addView(closeBtn, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

        TabViewHolder holder = new TabViewHolder(tabLayout, statusDot, titleView, badgeView, closeBtn);
        tabLayout.setTag(holder);

        closeBtn.setOnClickListener(v -> {
            if (mOnTabCloseListener != null && holder.index >= 0) {
                mOnTabCloseListener.onTabClose(holder.index);
            }
        });

        tabLayout.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                holder.touchStateMachine.onDown(event.getX(), event.getY());
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                v.postDelayed(holder.longPressRunnable, mLongPressTimeoutMs);
                return true;
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (holder.touchStateMachine.onMove(event.getX(), event.getY())) {
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                    v.removeCallbacks(holder.longPressRunnable);
                }
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                v.removeCallbacks(holder.longPressRunnable);
                if (action == MotionEvent.ACTION_UP) {
                    TerminalTopBarTouchStateMachine.ReleaseAction releaseAction = holder.touchStateMachine.onUp();
                    if (releaseAction == TerminalTopBarTouchStateMachine.ReleaseAction.CONSUME) {
                        return true;
                    }
                    if (releaseAction == TerminalTopBarTouchStateMachine.ReleaseAction.CLICK) {
                        v.performClick();
                    }
                } else {
                    holder.touchStateMachine.onCancel();
                }
                return true;
            }
            return true;
        });

        tabLayout.setOnClickListener(v -> {
            if (mOnTabSelectedListener != null &&
                holder.index >= 0 && holder.index < mItems.size()) {
                mOnTabSelectedListener.onTabSelected(holder.index, mItems.get(holder.index));
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
        mAddButton.setBackground(createTabBackground(false, TerminalTopBarStateMachine.Tone.NEUTRAL));
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(30));
        lp.weight = 1f;
        lp.setMargins(dp(1), dp(2), dp(1), dp(2));
        return lp;
    }

    private void bindBadgeView(@NonNull TextView badgeView, @NonNull Item item) {
        if (TextUtils.isEmpty(item.badgeText)) {
            badgeView.setVisibility(View.GONE);
            badgeView.setText(null);
            badgeView.setBackground(null);
            return;
        }

        int toneColor = getToneColor(item.tone);
        badgeView.setVisibility(View.VISIBLE);
        badgeView.setText(item.badgeText);
        badgeView.setTextColor(toneColor);
        badgeView.setBackground(createBadgeBackground(toneColor, item.selected));
    }

    private GradientDrawable createTabBackground(boolean selected, @NonNull TerminalTopBarStateMachine.Tone tone) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        float radius = dp(4);
        drawable.setCornerRadii(new float[]{radius, radius, radius, radius, radius, radius, radius, radius});
        drawable.setColor(getToneFillColor(tone, selected));
        drawable.setStroke(dp(1), getToneStrokeColor(tone, selected));
        return drawable;
    }

    private GradientDrawable createStatusDotBackground(@NonNull TerminalTopBarStateMachine.Tone tone) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(getToneColor(tone));
        return drawable;
    }

    private GradientDrawable createBadgeBackground(int toneColor, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(8));
        drawable.setColor(ColorUtils.setAlphaComponent(toneColor, selected ? 82 : 58));
        drawable.setStroke(dp(1), ColorUtils.setAlphaComponent(toneColor, selected ? 210 : 170));
        return drawable;
    }

    private int getToneFillColor(@NonNull TerminalTopBarStateMachine.Tone tone, boolean selected) {
        switch (tone) {
            case ACTIVE:
                return selected ? 0xFF353535 : 0xFF242424;
            case REMOTE:
                return selected ? 0xFF16364F : 0xFF102537;
            case PERSISTENT:
                return selected ? 0xFF1B5E20 : 0xFF143F17;
            case BUSY:
                return selected ? 0xFF5D4315 : 0xFF3E2D10;
            case SUCCESS:
                return selected ? 0xFF1E4D2B : 0xFF14331D;
            case ERROR:
                return selected ? 0xFF5A1E1E : 0xFF381313;
            case NEUTRAL:
            default:
                return selected ? 0xFF333333 : 0xFF1E1E1E;
        }
    }

    private int getToneStrokeColor(@NonNull TerminalTopBarStateMachine.Tone tone, boolean selected) {
        int toneColor = getToneColor(tone);
        return ColorUtils.setAlphaComponent(toneColor, selected ? 255 : 204);
    }

    private int getToneColor(@NonNull TerminalTopBarStateMachine.Tone tone) {
        switch (tone) {
            case ACTIVE:
                return 0xFFBDBDBD;
            case REMOTE:
                return 0xFF64B5F6;
            case PERSISTENT:
                return 0xFFA5D6A7;
            case BUSY:
                return 0xFFFFD54F;
            case SUCCESS:
                return 0xFF81C784;
            case ERROR:
                return 0xFFEF9A9A;
            case NEUTRAL:
            default:
                return 0xFF8A8A8A;
        }
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

    private final class TabViewHolder {
        @NonNull final LinearLayout root;
        @NonNull final View statusDot;
        @NonNull final TextView titleView;
        @NonNull final TextView badgeView;
        @NonNull final TextView closeBtn;
        int index = -1;
        @Nullable String key;
        @NonNull final TerminalTopBarTouchStateMachine touchStateMachine;
        @NonNull final Runnable longPressRunnable;

        TabViewHolder(@NonNull LinearLayout root, @NonNull View statusDot, @NonNull TextView titleView,
                      @NonNull TextView badgeView, @NonNull TextView closeBtn) {
            this.root = root;
            this.statusDot = statusDot;
            this.titleView = titleView;
            this.badgeView = badgeView;
            this.closeBtn = closeBtn;
            this.touchStateMachine = new TerminalTopBarTouchStateMachine(mTabMoveThresholdPx);
            this.longPressRunnable = () -> {
                if (!touchStateMachine.onLongPressTimeout()) return;
                root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (mOnTabLongPressListener != null && index >= 0 && index < mItems.size()) {
                    mOnTabLongPressListener.onTabLongPress(index, mItems.get(index));
                }
            };
        }
    }
}
