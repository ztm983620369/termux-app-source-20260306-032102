package com.termux.terminalsessionsurface;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TerminalSessionSurfaceView extends LinearLayout {
    private static final int MAX_SESSION_PAGER_OFFSCREEN_LIMIT = 2;

    public interface Callbacks {
        void onSessionPageSwipeTouchDown();
        void onSessionPageChangeStarted();
        void onSessionPageChangeFinished();
        void onSessionPagePreviewSelected(int index, @Nullable TerminalSession session);
        void onConfigPagePreviewSelected();
        void onSessionPageSelected(int index, @Nullable TerminalSession session, boolean fromUser, long requestToken);
        void onConfigPageSelected(boolean fromUser, long requestToken);
        void onActiveTerminalViewChanged(@NonNull TerminalView terminalView, @Nullable TerminalSession session);
        void onExtraKeysViewCreated(@NonNull ExtraKeysView extraKeysView);
    }

    private static final String PLACEHOLDER_KEY = "__placeholder__";
    private static final String CONFIG_PAGE_KEY = "__config_page__";

    private final TerminalSessionSurfacePagerStateMachine pagerStateMachine =
        new TerminalSessionSurfacePagerStateMachine();
    private final TerminalSessionSurfaceToolbarStateMachine toolbarStateMachine =
        new TerminalSessionSurfaceToolbarStateMachine();

    private final SessionPagerAdapter sessionPagerAdapter = new SessionPagerAdapter();
    private final ToolbarPagerAdapter toolbarPagerAdapter = new ToolbarPagerAdapter();

    private ViewPager mSessionPager;
    private ViewPager mToolbarPager;

    @Nullable private Callbacks mCallbacks;
    @Nullable private TerminalViewClient mTerminalViewClient;
    @Nullable private ExtraKeysView.IExtraKeysView mExtraKeysViewClient;
    @Nullable private ExtraKeysInfo mExtraKeysInfo;
    @Nullable private Typeface mTerminalTypeface;
    @Nullable private ExtraKeysView mLastDispatchedExtraKeysView;
    @Nullable private View mConfigPageView;

    private int mTerminalTextSize;
    private boolean mTerminalKeepScreenOn;
    private boolean mToolbarVisible;
    private boolean mSuppressSessionPageCallback;
    private boolean mToolbarButtonTextAllCaps = true;
    private float mToolbarDefaultHeightPx;
    private float mToolbarHeightScale = 1f;
    private int mToolbarComputedHeightPx;
    private int mSelectedSessionIndex;
    private final int mSessionPageGapPx;
    private boolean mConfigPageEnabled;
    private boolean mSessionPageSwipeTouchActive;
    private boolean mSessionPageChangeInProgress;
    private boolean mProgrammaticFocusAllowed = true;
    private boolean mFullScreenSessionSwipeEnabled;
    @NonNull
    private final Map<String, Long> mProgrammaticSelectionTokensByKey = new HashMap<>();

    public TerminalSessionSurfaceView(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        mSessionPageGapPx = Math.max(1, Math.round(2f * density));
        init(context);
    }

    public TerminalSessionSurfaceView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        mSessionPageGapPx = Math.max(1, Math.round(2f * density));
        init(context);
    }

    public TerminalSessionSurfaceView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = context.getResources().getDisplayMetrics().density;
        mSessionPageGapPx = Math.max(1, Math.round(2f * density));
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_terminal_session_surface, this, true);
        mSessionPager = findViewById(R.id.terminal_session_pager);
        mToolbarPager = findViewById(R.id.terminal_toolbar_view_pager);
        mToolbarDefaultHeightPx = resolveToolbarDefaultHeightPx();
        updateToolbarMetricsState();

        if (mSessionPager instanceof ProgrammaticViewPager) {
            ProgrammaticViewPager programmaticViewPager = (ProgrammaticViewPager) mSessionPager;
            programmaticViewPager.setSwipeRegionProvider(() -> {
                int configPageIndex = getConfigPageIndex();
                boolean configPageTargeted = configPageIndex >= 0 &&
                    (mSelectedSessionIndex == configPageIndex || mSessionPager.getCurrentItem() == configPageIndex);
                int activeIndex = configPageTargeted ? configPageIndex : mSessionPager.getCurrentItem();
                PageHolder holder = sessionPagerAdapter.findHolder(activeIndex);
                if (configPageTargeted) {
                    if (holder != null && holder.configContainer.isShown() &&
                        holder.configContainer.getWidth() > 0 && holder.configContainer.getHeight() > 0) {
                        return holder.configContainer;
                    }
                    // The config page can be the selected target before ViewPager updates currentItem
                    // and attaches the holder on the first entry.
                    return mSessionPager;
                }
                if (holder == null) return null;
                if (TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) {
                    return holder.root;
                }
                return mFullScreenSessionSwipeEnabled ? holder.root : holder.extraKeysContainer;
            });
            programmaticViewPager.setSwipeGestureListener(new ProgrammaticViewPager.SwipeGestureListener() {
                @Override
                public void onSwipeTouchDownInRegion() {
                    mSessionPageSwipeTouchActive = true;
                    if (mCallbacks != null) {
                        mCallbacks.onSessionPageSwipeTouchDown();
                    }
                }

                @Override
                public void onSwipeGestureCaptured() {
                    notifySessionPageChangeStarted();
                }

                @Override
                public void onSwipeGestureFinished() {
                    finishSessionPageChangeIfIdle();
                }
            });
        }

        updateSessionPagerCachePolicy();
        mSessionPager.setPageMargin(mSessionPageGapPx);
        mSessionPager.setPageMarginDrawable(new ColorDrawable(0xFF000000));
        mSessionPager.setAdapter(sessionPagerAdapter);
        mSessionPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager.SCROLL_STATE_DRAGGING) {
                    notifySessionPageChangeStarted();
                    pagerStateMachine.onDragStarted();
                } else if (state == ViewPager.SCROLL_STATE_SETTLING) {
                    pagerStateMachine.onSettlingStarted();
                } else {
                    pagerStateMachine.onIdle();
                    mSelectedSessionIndex = sessionPagerAdapter.clampIndex(mSessionPager.getCurrentItem());
                    dispatchActivePageChanged(mSelectedSessionIndex, true);
                }
            }

            @Override
            public void onPageSelected(int position) {
                mSelectedSessionIndex = sessionPagerAdapter.clampIndex(position);
                dispatchPreviewPageSelected(mSelectedSessionIndex);
                if (pagerStateMachine.getState() == TerminalSessionSurfacePagerStateMachine.State.IDLE) {
                    dispatchActivePageChanged(mSelectedSessionIndex, true);
                }
            }
        });

        mToolbarPager.setAdapter(toolbarPagerAdapter);
        mToolbarPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                toolbarStateMachine.onPageSelected(position);
                updateToolbarPresentation();
                if (!mProgrammaticFocusAllowed) return;
                if (position == 0) {
                    TerminalView terminalView = getCurrentTerminalView();
                    if (terminalView != null) terminalView.requestFocus();
                } else {
                    EditText editText = findViewById(R.id.terminal_surface_text_input);
                    if (editText != null) editText.requestFocus();
                }
            }
        });

        updateToolbarPresentation();
    }

    public void setCallbacks(@Nullable Callbacks callbacks) {
        mCallbacks = callbacks;
        TerminalView terminalView = getCurrentTerminalView();
        if (callbacks != null && terminalView != null) {
            callbacks.onActiveTerminalViewChanged(terminalView, terminalView.getCurrentSession());
        }
        dispatchCurrentExtraKeysViewChanged();
    }

    public void setTerminalViewClient(@Nullable TerminalViewClient terminalViewClient) {
        mTerminalViewClient = terminalViewClient;
        sessionPagerAdapter.applyTerminalViewConfigToAll();
    }

    public void setTerminalTextSize(int terminalTextSize) {
        if (mTerminalTextSize == terminalTextSize) return;
        mTerminalTextSize = terminalTextSize;
        sessionPagerAdapter.applyTerminalViewConfigToAll();
    }

    public void setTerminalKeepScreenOn(boolean terminalKeepScreenOn) {
        if (mTerminalKeepScreenOn == terminalKeepScreenOn) return;
        mTerminalKeepScreenOn = terminalKeepScreenOn;
        sessionPagerAdapter.applyTerminalViewConfigToAll();
    }

    public void setTerminalTypeface(@Nullable Typeface terminalTypeface) {
        if (mTerminalTypeface == terminalTypeface) return;
        mTerminalTypeface = terminalTypeface;
        sessionPagerAdapter.applyTerminalViewConfigToAll();
    }

    public void setToolbarVisible(boolean toolbarVisible) {
        if (mToolbarVisible == toolbarVisible) return;
        mToolbarVisible = toolbarVisible;
        updateToolbarPresentation();
    }

    public void setToolbarTextInputEnabled(boolean textInputEnabled) {
        toolbarStateMachine.setTextInputEnabled(textInputEnabled);
        toolbarPagerAdapter.notifyDataSetChanged();
        if (!textInputEnabled) {
            mToolbarPager.setCurrentItem(0, false);
        }
        updateToolbarPresentation();
    }

    public void setToolbarButtonTextAllCaps(boolean toolbarButtonTextAllCaps) {
        if (mToolbarButtonTextAllCaps == toolbarButtonTextAllCaps) return;
        mToolbarButtonTextAllCaps = toolbarButtonTextAllCaps;
        sessionPagerAdapter.reloadExtraKeysViews();
    }

    public void setToolbarMetrics(float defaultHeightPx, float heightScale) {
        float resolvedDefaultHeightPx = defaultHeightPx > 0f ? defaultHeightPx : resolveToolbarDefaultHeightPx();
        float resolvedHeightScale = heightScale <= 0f ? 1f : heightScale;
        if (Float.compare(mToolbarDefaultHeightPx, resolvedDefaultHeightPx) == 0 &&
            Float.compare(mToolbarHeightScale, resolvedHeightScale) == 0) {
            return;
        }
        mToolbarDefaultHeightPx = resolvedDefaultHeightPx;
        mToolbarHeightScale = resolvedHeightScale;
        updateToolbarMetricsState();
        sessionPagerAdapter.reloadExtraKeysViews();
        updateToolbarPresentation();
    }

    public float getToolbarDefaultHeightPx() {
        return mToolbarDefaultHeightPx;
    }

    public void setToolbarExtraKeys(@Nullable ExtraKeysInfo extraKeysInfo,
                                    @Nullable ExtraKeysView.IExtraKeysView extraKeysViewClient) {
        if (mExtraKeysInfo == extraKeysInfo && mExtraKeysViewClient == extraKeysViewClient) return;
        mExtraKeysInfo = extraKeysInfo;
        mExtraKeysViewClient = extraKeysViewClient;
        updateToolbarMetricsState();
        sessionPagerAdapter.reloadExtraKeysViews();
        updateToolbarPresentation();
    }

    public void submitSessions(@NonNull List<TerminalSessionSurfaceItem> items,
                               int selectedIndex,
                               boolean animate) {
        submitSessions(items, selectedIndex, animate, 0L);
    }

    public void submitSessions(@NonNull List<TerminalSessionSurfaceItem> items,
                               int selectedIndex,
                               boolean animate,
                               long requestToken) {
        boolean dataChanged = sessionPagerAdapter.submitItems(items);
        pruneProgrammaticSelectionTokens();
        updateSessionPagerCachePolicy();
        int safeIndex = sessionPagerAdapter.clampIndex(selectedIndex);
        int currentItem = sessionPagerAdapter.clampIndex(mSessionPager.getCurrentItem());

        if (pagerStateMachine.getState() != TerminalSessionSurfacePagerStateMachine.State.IDLE) {
            mSelectedSessionIndex = currentItem;
            if (dataChanged) {
                dispatchCurrentExtraKeysViewChanged();
            }
            return;
        }

        mSelectedSessionIndex = safeIndex;
        rememberProgrammaticSelectionTokenForPosition(safeIndex, requestToken);
        if (safeIndex != currentItem) {
            notifySessionPageChangeStarted();
            mSuppressSessionPageCallback = true;
            mSessionPager.setCurrentItem(safeIndex, animate);
            mSuppressSessionPageCallback = false;
        } else if (dataChanged) {
            dispatchCurrentExtraKeysViewChanged();
        } else if (requestToken <= 0L) {
            return;
        }
        dispatchActivePageChanged(safeIndex, false);
    }

    public void setCurrentSessionPage(int index, boolean animate) {
        setCurrentSessionPage(index, animate, 0L);
    }

    public void setCurrentSessionPage(int index, boolean animate, long requestToken) {
        int safeIndex = sessionPagerAdapter.clampIndex(index);
        mSelectedSessionIndex = safeIndex;
        rememberProgrammaticSelectionTokenForPosition(safeIndex, requestToken);
        if (safeIndex == mSessionPager.getCurrentItem()) {
            dispatchActivePageChanged(safeIndex, false);
            return;
        }
        notifySessionPageChangeStarted();
        mSuppressSessionPageCallback = true;
        mSessionPager.setCurrentItem(safeIndex, animate);
        mSuppressSessionPageCallback = false;
        if (pagerStateMachine.getState() == TerminalSessionSurfacePagerStateMachine.State.IDLE) {
            dispatchActivePageChanged(safeIndex, false);
        }
    }

    public void setConfigPageView(@Nullable View configPageView) {
        if (mConfigPageView == configPageView) return;
        mConfigPageView = configPageView;
        sessionPagerAdapter.notifyDataSetChanged();
        updateSessionPagerCachePolicy();
    }

    public void setConfigPageEnabled(boolean enabled) {
        if (mConfigPageEnabled == enabled) return;
        mConfigPageEnabled = enabled;
        sessionPagerAdapter.notifyDataSetChanged();
        updateSessionPagerCachePolicy();
    }

    public boolean isConfigPageEnabled() {
        return mConfigPageEnabled;
    }

    public int getConfigPageIndex() {
        return mConfigPageEnabled ? sessionPagerAdapter.getItemsCount() : -1;
    }

    public void setCurrentConfigPage(boolean animate) {
        setCurrentConfigPage(animate, 0L);
    }

    public void setCurrentConfigPage(boolean animate, long requestToken) {
        if (!mConfigPageEnabled) return;
        int configIndex = getConfigPageIndex();
        if (configIndex < 0) return;
        mSelectedSessionIndex = configIndex;
        rememberProgrammaticSelectionTokenForPosition(configIndex, requestToken);
        if (configIndex == mSessionPager.getCurrentItem()) {
            dispatchActivePageChanged(configIndex, false);
            return;
        }
        notifySessionPageChangeStarted();
        mSuppressSessionPageCallback = true;
        mSessionPager.setCurrentItem(configIndex, animate);
        mSuppressSessionPageCallback = false;
        if (pagerStateMachine.getState() == TerminalSessionSurfacePagerStateMachine.State.IDLE) {
            dispatchActivePageChanged(configIndex, false);
        }
    }

    public void refreshSession(@NonNull TerminalSession session) {
        PageHolder holder = sessionPagerAdapter.findHolder(session);
        if (holder == null) return;
        if (holder.terminalView.getCurrentSession() == session) {
            holder.terminalView.onScreenUpdated();
        } else {
            holder.terminalView.attachSession(session);
            holder.terminalView.onScreenUpdated();
        }
    }

    public void invalidateSession(@NonNull TerminalSession session) {
        PageHolder holder = sessionPagerAdapter.findHolder(session);
        if (holder != null) holder.terminalView.invalidate();
    }

    @Nullable
    public TerminalView getCurrentTerminalView() {
        PageHolder holder = sessionPagerAdapter.findHolder(mSessionPager.getCurrentItem());
        if (holder == null || TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) return null;
        return holder == null ? null : holder.terminalView;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        TerminalView terminalView = getCurrentTerminalView();
        return terminalView == null ? null : terminalView.getCurrentSession();
    }

    @Nullable
    public ViewPager getToolbarPager() {
        return mToolbarPager;
    }

    @Nullable
    public ExtraKeysView getExtraKeysView() {
        PageHolder holder = sessionPagerAdapter.findHolder(mSessionPager.getCurrentItem());
        if (holder == null || TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) return null;
        return holder == null ? null : holder.extraKeysView;
    }

    public boolean isTerminalToolbarPrimaryPageSelected() {
        return toolbarStateMachine.isTerminalPageSelected();
    }

    public boolean isTerminalToolbarTextInputPageSelected() {
        return toolbarStateMachine.isTextInputPageSelected();
    }

    public void setProgrammaticFocusAllowed(boolean allowed) {
        mProgrammaticFocusAllowed = allowed;
    }

    /**
     * If enabled, horizontal session paging swipes may start from anywhere on the terminal page.
     * If disabled, swipes are restricted to the bottom extra keys region.
     */
    public void setFullScreenSessionSwipeEnabled(boolean enabled) {
        if (mFullScreenSessionSwipeEnabled == enabled) return;
        mFullScreenSessionSwipeEnabled = enabled;
    }

    public void focusToolbarTextInput() {
        mProgrammaticFocusAllowed = true;
        EditText editText = findViewById(R.id.terminal_surface_text_input);
        if (editText != null) editText.requestFocus();
    }

    private void notifySessionPageChangeStarted() {
        if (mSessionPageChangeInProgress) return;

        mSessionPageChangeInProgress = true;
        if (mCallbacks != null) {
            mCallbacks.onSessionPageChangeStarted();
        }
    }

    private void finishSessionPageChangeIfIdle() {
        if (mSessionPageChangeInProgress) {
            if (pagerStateMachine.getState() != TerminalSessionSurfacePagerStateMachine.State.IDLE) {
                return;
            }
            dispatchSessionPageChangeFinished();
            return;
        }

        if (!mSessionPageSwipeTouchActive) return;

        dispatchSessionPageChangeFinished();
    }

    private void dispatchSessionPageChangeFinished() {
        if (!mSessionPageChangeInProgress && !mSessionPageSwipeTouchActive) return;

        mSessionPageChangeInProgress = false;
        mSessionPageSwipeTouchActive = false;
        if (mCallbacks != null) {
            mCallbacks.onSessionPageChangeFinished();
        }
    }

    private void dispatchPreviewPageSelected(int position) {
        if (mCallbacks == null) return;
        PageHolder holder = sessionPagerAdapter.findHolder(position);
        if (holder == null) return;

        if (TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) {
            mCallbacks.onConfigPagePreviewSelected();
            return;
        }

        mCallbacks.onSessionPagePreviewSelected(position, holder.session);
    }

    private void dispatchActivePageChanged(int position, boolean fromUser) {
        PageHolder holder = sessionPagerAdapter.findHolder(position);
        if (holder == null && sessionPagerAdapter.getCount() > 0) {
            mSessionPager.post(() -> dispatchActivePageChanged(position, fromUser));
            return;
        }
        if (holder == null) {
            dispatchSessionPageChangeFinished();
            return;
        }

        if (TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) {
            if (mCallbacks != null && !mSuppressSessionPageCallback) {
                mCallbacks.onConfigPageSelected(fromUser, consumeProgrammaticSelectionToken(holder.key, fromUser));
            }
            dispatchCurrentExtraKeysViewChanged();
            dispatchSessionPageChangeFinished();
            return;
        }

        if (mCallbacks != null) {
            mCallbacks.onActiveTerminalViewChanged(holder.terminalView, holder.session);
        }
        if (mCallbacks != null && !mSuppressSessionPageCallback) {
            mCallbacks.onSessionPageSelected(
                position,
                holder.session,
                fromUser,
                consumeProgrammaticSelectionToken(holder.key, fromUser)
            );
        }
        dispatchCurrentExtraKeysViewChanged();
        dispatchSessionPageChangeFinished();
    }

    private void dispatchCurrentExtraKeysViewChanged() {
        ExtraKeysView extraKeysView = getExtraKeysView();
        if (extraKeysView == mLastDispatchedExtraKeysView) return;

        mLastDispatchedExtraKeysView = extraKeysView;
        if (mCallbacks != null && extraKeysView != null) {
            mCallbacks.onExtraKeysViewCreated(extraKeysView);
        }
    }

    private void updateToolbarMetricsState() {
        int rows = mExtraKeysInfo == null ? 0 : mExtraKeysInfo.getMatrix().length;
        mToolbarComputedHeightPx = Math.round(mToolbarDefaultHeightPx * rows * mToolbarHeightScale);
    }

    private void rememberProgrammaticSelectionTokenForPosition(int position, long requestToken) {
        if (requestToken <= 0L) return;
        String key = sessionPagerAdapter.getItemKeyAtPosition(position);
        if (TextUtils.isEmpty(key)) return;
        mProgrammaticSelectionTokensByKey.put(key, requestToken);
    }

    private long consumeProgrammaticSelectionToken(@Nullable String key, boolean fromUser) {
        if (fromUser || TextUtils.isEmpty(key)) return 0L;
        Long token = mProgrammaticSelectionTokensByKey.remove(key);
        return token == null ? 0L : token;
    }

    private void pruneProgrammaticSelectionTokens() {
        if (mProgrammaticSelectionTokensByKey.isEmpty()) return;
        ArrayList<String> keys = new ArrayList<>(mProgrammaticSelectionTokensByKey.keySet());
        for (String key : keys) {
            if (!sessionPagerAdapter.containsItemKey(key)) {
                mProgrammaticSelectionTokensByKey.remove(key);
            }
        }
    }

    private boolean shouldShowIntegratedToolbar() {
        return mToolbarVisible && toolbarStateMachine.isTerminalPageSelected() && mToolbarComputedHeightPx > 0;
    }

    private boolean shouldShowTextInputToolbar() {
        return mToolbarVisible && toolbarStateMachine.isTextInputEnabled() &&
            toolbarStateMachine.isTextInputPageSelected() && mToolbarComputedHeightPx > 0;
    }

    private void updateToolbarPresentation() {
        boolean showTextInput = shouldShowTextInputToolbar();
        ViewGroup.LayoutParams layoutParams = mToolbarPager.getLayoutParams();
        int desiredHeight = showTextInput ? mToolbarComputedHeightPx : 0;
        if (layoutParams.height != desiredHeight) {
            layoutParams.height = desiredHeight;
            mToolbarPager.setLayoutParams(layoutParams);
        }
        mToolbarPager.setVisibility(showTextInput ? View.VISIBLE : View.GONE);
        sessionPagerAdapter.applyToolbarPresentationToAll();
        dispatchCurrentExtraKeysViewChanged();
    }

    private float resolveToolbarDefaultHeightPx() {
        ViewGroup.LayoutParams layoutParams = mToolbarPager.getLayoutParams();
        return layoutParams == null || layoutParams.height <= 0 ? 0f : layoutParams.height;
    }

    private void updateSessionPagerCachePolicy() {
        int pageCount = sessionPagerAdapter.getCount();
        int desiredOffscreenLimit = Math.min(
            MAX_SESSION_PAGER_OFFSCREEN_LIMIT,
            Math.max(1, pageCount - 1)
        );
        if (mSessionPager.getOffscreenPageLimit() != desiredOffscreenLimit) {
            mSessionPager.setOffscreenPageLimit(desiredOffscreenLimit);
        }
    }

    private final class SessionPagerAdapter extends PagerAdapter {
        private final ArrayList<TerminalSessionSurfaceItem> items = new ArrayList<>();
        private final LinkedHashMap<String, PageHolder> holdersByKey = new LinkedHashMap<>();
        private boolean forceRecreateAll;

        boolean submitItems(@NonNull List<TerminalSessionSurfaceItem> newItems) {
            if (TerminalSessionSurfaceItems.hasSameItems(items, newItems)) {
                return false;
            }

            ArrayList<String> oldKeys = new ArrayList<>();
            for (TerminalSessionSurfaceItem item : items) {
                oldKeys.add(item.key);
            }
            ArrayList<String> newKeys = new ArrayList<>();
            for (TerminalSessionSurfaceItem item : newItems) {
                newKeys.add(item.key);
            }
            forceRecreateAll = !oldKeys.equals(newKeys);
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
            forceRecreateAll = false;
            return true;
        }

        int clampIndex(int index) {
            int count = getCount();
            if (count <= 0) return 0;
            return Math.max(0, Math.min(index, count - 1));
        }

        int getItemsCount() {
            return items.size();
        }

        @Nullable
        String getItemKeyAtPosition(int position) {
            TerminalSessionSurfaceItem item;
            if (position < items.size()) {
                item = items.get(position);
            } else if (mConfigPageEnabled && position == items.size()) {
                item = new TerminalSessionSurfaceItem(CONFIG_PAGE_KEY, null);
            } else if (items.isEmpty()) {
                item = new TerminalSessionSurfaceItem(PLACEHOLDER_KEY, null);
            } else {
                item = null;
            }
            return item == null ? null : item.key;
        }

        boolean containsItemKey(@Nullable String key) {
            if (TextUtils.isEmpty(key)) return false;
            if (TextUtils.equals(CONFIG_PAGE_KEY, key)) {
                return mConfigPageEnabled;
            }
            if (TextUtils.equals(PLACEHOLDER_KEY, key)) {
                return items.isEmpty();
            }
            for (TerminalSessionSurfaceItem item : items) {
                if (TextUtils.equals(item.key, key)) return true;
            }
            return false;
        }

        @Override
        public int getCount() {
            if (mConfigPageEnabled) {
                return Math.max(1, items.size() + 1);
            }
            return Math.max(1, items.size());
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @Override
        public int getItemPosition(@NonNull Object object) {
            if (forceRecreateAll) return POSITION_NONE;
            PageHolder holder = object instanceof View
                ? (PageHolder) ((View) object).getTag()
                : null;
            if (holder == null) return POSITION_NONE;
            if (PLACEHOLDER_KEY.equals(holder.key)) {
                return items.isEmpty() ? POSITION_UNCHANGED : POSITION_NONE;
            }
            if (CONFIG_PAGE_KEY.equals(holder.key)) {
                return mConfigPageEnabled ? POSITION_UNCHANGED : POSITION_NONE;
            }
            for (TerminalSessionSurfaceItem item : items) {
                if (TextUtils.equals(item.key, holder.key)) return POSITION_UNCHANGED;
            }
            return POSITION_NONE;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            TerminalSessionSurfaceItem item;
            if (position < items.size()) {
                item = items.get(position);
            } else if (mConfigPageEnabled && position == items.size()) {
                item = new TerminalSessionSurfaceItem(CONFIG_PAGE_KEY, null);
            } else {
                item = new TerminalSessionSurfaceItem(PLACEHOLDER_KEY, null);
            }

            PageHolder holder = PLACEHOLDER_KEY.equals(item.key)
                ? createPageHolder()
                : holdersByKey.get(item.key);
            if (holder == null) {
                holder = createPageHolder();
                if (!PLACEHOLDER_KEY.equals(item.key)) {
                    holdersByKey.put(item.key, holder);
                }
            }

            bindHolder(holder, item);
            View parent = (View) holder.root.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(holder.root);
            }
            container.addView(holder.root);
            return holder.root;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }

        @Nullable
        PageHolder findHolder(int position) {
            TerminalSessionSurfaceItem item;
            if (position < items.size()) {
                item = items.get(position);
            } else if (mConfigPageEnabled && position == items.size()) {
                item = new TerminalSessionSurfaceItem(CONFIG_PAGE_KEY, null);
            } else {
                item = null;
            }
            if (item == null) return null;
            return holdersByKey.get(item.key);
        }

        @Nullable
        PageHolder findHolder(@NonNull TerminalSession session) {
            for (PageHolder holder : holdersByKey.values()) {
                if (holder.session == session) return holder;
            }
            return null;
        }

        void applyTerminalViewConfigToAll() {
            for (PageHolder holder : holdersByKey.values()) {
                applyTerminalViewConfig(holder.terminalView);
            }
            TerminalView active = getCurrentTerminalView();
            if (active != null) applyTerminalViewConfig(active);
        }

        void reloadExtraKeysViews() {
            for (PageHolder holder : holdersByKey.values()) {
                reloadExtraKeysView(holder.extraKeysView);
            }
            dispatchCurrentExtraKeysViewChanged();
        }

        void applyToolbarPresentationToAll() {
            for (PageHolder holder : holdersByKey.values()) {
                applyToolbarPresentation(holder);
            }
        }

        @NonNull
        private PageHolder createPageHolder() {
            View root = LayoutInflater.from(getContext())
                .inflate(R.layout.item_terminal_session_page, mSessionPager, false);
            TerminalView terminalView = root.findViewById(R.id.terminal_session_page_terminal_view);
            ViewGroup configContainer = root.findViewById(R.id.terminal_session_page_config_container);
            SessionSwipeFrameLayout extraKeysContainer =
                root.findViewById(R.id.terminal_session_page_extra_keys_container);
            ExtraKeysView extraKeysView = root.findViewById(R.id.terminal_session_page_extra_keys);
            applyTerminalViewConfig(terminalView);
            reloadExtraKeysView(extraKeysView);
            PageHolder holder = new PageHolder(root, terminalView, configContainer, extraKeysContainer, extraKeysView);
            root.setTag(holder);
            return holder;
        }

        private void bindHolder(@NonNull PageHolder holder, @NonNull TerminalSessionSurfaceItem item) {
            holder.key = item.key;
            holder.session = item.session;
            if (CONFIG_PAGE_KEY.equals(item.key)) {
                holder.terminalView.setVisibility(View.GONE);
                holder.extraKeysContainer.setVisibility(View.GONE);
                holder.configContainer.setVisibility(View.VISIBLE);
                holder.configContainer.removeAllViews();
                if (mConfigPageView != null) {
                    View parent = (View) mConfigPageView.getParent();
                    if (parent instanceof ViewGroup) {
                        ((ViewGroup) parent).removeView(mConfigPageView);
                    }
                    holder.configContainer.addView(
                        mConfigPageView,
                        new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    );
                }
                return;
            }

            holder.configContainer.removeAllViews();
            holder.configContainer.setVisibility(View.GONE);
            holder.terminalView.setVisibility(View.VISIBLE);
            applyTerminalViewConfig(holder.terminalView);
            reloadExtraKeysView(holder.extraKeysView);
            applyToolbarPresentation(holder);
            if (item.session != null) {
                holder.terminalView.attachSession(item.session);
            }
        }

        private void reloadExtraKeysView(@NonNull ExtraKeysView extraKeysView) {
            extraKeysView.setExtraKeysViewClient(mExtraKeysViewClient);
            extraKeysView.setButtonTextAllCaps(mToolbarButtonTextAllCaps);
            if (mExtraKeysInfo != null) {
                extraKeysView.reload(mExtraKeysInfo, mToolbarDefaultHeightPx);
            } else {
                extraKeysView.removeAllViews();
            }
        }

        private void applyToolbarPresentation(@NonNull PageHolder holder) {
            if (TextUtils.equals(holder.key, CONFIG_PAGE_KEY)) {
                holder.extraKeysContainer.setVisibility(View.GONE);
                return;
            }
            boolean showIntegratedToolbar = shouldShowIntegratedToolbar();
            ViewGroup.LayoutParams layoutParams = holder.extraKeysContainer.getLayoutParams();
            int desiredHeight = showIntegratedToolbar ? mToolbarComputedHeightPx : 0;
            if (layoutParams.height != desiredHeight) {
                layoutParams.height = desiredHeight;
                holder.extraKeysContainer.setLayoutParams(layoutParams);
            }
            holder.extraKeysContainer.setVisibility(showIntegratedToolbar ? View.VISIBLE : View.GONE);
        }

        private void applyTerminalViewConfig(@NonNull TerminalView terminalView) {
            if (mTerminalViewClient != null) {
                terminalView.setTerminalViewClient(mTerminalViewClient);
            }
            if (mTerminalTextSize > 0) {
                terminalView.setTextSize(mTerminalTextSize);
            }
            if (mTerminalTypeface != null) {
                terminalView.setTypeface(mTerminalTypeface);
            }
            terminalView.setKeepScreenOn(mTerminalKeepScreenOn);
        }
    }

    private final class ToolbarPagerAdapter extends PagerAdapter {
        @Override
        public int getCount() {
            return toolbarStateMachine.isTextInputEnabled() ? 2 : 1;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @Override
        public int getItemPosition(@NonNull Object object) {
            return POSITION_NONE;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup collection, int position) {
            View layout;
            if (position == 0) {
                layout = new View(getContext());
                layout.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ));
            } else {
                layout = LayoutInflater.from(getContext())
                    .inflate(R.layout.view_terminal_session_surface_text_input, collection, false);
                EditText editText = layout.findViewById(R.id.terminal_surface_text_input);
                editText.setOnEditorActionListener((v, actionId, event) -> {
                    if (actionId != EditorInfo.IME_ACTION_SEND &&
                        (event == null || event.getKeyCode() != KeyEvent.KEYCODE_ENTER)) {
                        return false;
                    }
                    TerminalSession session = getCurrentSession();
                    if (session != null && session.isRunning()) {
                        String textToSend = editText.getText().toString();
                        if (textToSend.length() == 0) textToSend = "\r";
                        session.write(textToSend);
                    }
                    editText.setText("");
                    return true;
                });
            }
            collection.addView(layout);
            return layout;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup collection, int position, @NonNull Object object) {
            collection.removeView((View) object);
        }
    }

    private static final class PageHolder {
        @NonNull final View root;
        @NonNull final TerminalView terminalView;
        @NonNull final ViewGroup configContainer;
        @NonNull final SessionSwipeFrameLayout extraKeysContainer;
        @NonNull final ExtraKeysView extraKeysView;
        @Nullable TerminalSession session;
        @Nullable String key;

        PageHolder(@NonNull View root,
                   @NonNull TerminalView terminalView,
                   @NonNull ViewGroup configContainer,
                   @NonNull SessionSwipeFrameLayout extraKeysContainer,
                   @NonNull ExtraKeysView extraKeysView) {
            this.root = root;
            this.terminalView = terminalView;
            this.configContainer = configContainer;
            this.extraKeysContainer = extraKeysContainer;
            this.extraKeysView = extraKeysView;
        }
    }
}
