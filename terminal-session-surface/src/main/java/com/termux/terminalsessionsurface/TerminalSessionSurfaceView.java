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
import java.util.LinkedHashMap;
import java.util.List;

public class TerminalSessionSurfaceView extends LinearLayout {
    public interface Callbacks {
        void onSessionPageSwipeTouchDown();
        void onSessionPageChangeStarted();
        void onSessionPageChangeFinished();
        void onSessionPageSelected(int index, @Nullable TerminalSession session, boolean fromUser);
        void onActiveTerminalViewChanged(@NonNull TerminalView terminalView, @Nullable TerminalSession session);
        void onExtraKeysViewCreated(@NonNull ExtraKeysView extraKeysView);
    }

    private static final String PLACEHOLDER_KEY = "__placeholder__";

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
    private boolean mSessionPageSwipeTouchActive;
    private boolean mSessionPageChangeInProgress;

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
                PageHolder holder = sessionPagerAdapter.findHolder(mSessionPager.getCurrentItem());
                return holder == null ? null : holder.extraKeysContainer;
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

        mSessionPager.setOffscreenPageLimit(8);
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
        mTerminalTextSize = terminalTextSize;
        sessionPagerAdapter.applyTerminalViewConfigToAll();
    }

    public void setTerminalKeepScreenOn(boolean terminalKeepScreenOn) {
        mTerminalKeepScreenOn = terminalKeepScreenOn;
        sessionPagerAdapter.applyTerminalViewConfigToAll();
    }

    public void setTerminalTypeface(@Nullable Typeface terminalTypeface) {
        mTerminalTypeface = terminalTypeface;
        sessionPagerAdapter.applyTerminalViewConfigToAll();
    }

    public void setToolbarVisible(boolean toolbarVisible) {
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
        mToolbarButtonTextAllCaps = toolbarButtonTextAllCaps;
        sessionPagerAdapter.reloadExtraKeysViews();
    }

    public void setToolbarMetrics(float defaultHeightPx, float heightScale) {
        mToolbarDefaultHeightPx = defaultHeightPx > 0f ? defaultHeightPx : resolveToolbarDefaultHeightPx();
        mToolbarHeightScale = heightScale <= 0f ? 1f : heightScale;
        updateToolbarMetricsState();
        sessionPagerAdapter.reloadExtraKeysViews();
        updateToolbarPresentation();
    }

    public float getToolbarDefaultHeightPx() {
        return mToolbarDefaultHeightPx;
    }

    public void setToolbarExtraKeys(@Nullable ExtraKeysInfo extraKeysInfo,
                                    @Nullable ExtraKeysView.IExtraKeysView extraKeysViewClient) {
        mExtraKeysInfo = extraKeysInfo;
        mExtraKeysViewClient = extraKeysViewClient;
        updateToolbarMetricsState();
        sessionPagerAdapter.reloadExtraKeysViews();
        updateToolbarPresentation();
    }

    public void submitSessions(@NonNull List<TerminalSessionSurfaceItem> items,
                               int selectedIndex,
                               boolean animate) {
        boolean dataChanged = sessionPagerAdapter.submitItems(items);
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
        if (safeIndex != currentItem) {
            notifySessionPageChangeStarted();
            mSuppressSessionPageCallback = true;
            mSessionPager.setCurrentItem(safeIndex, animate);
            mSuppressSessionPageCallback = false;
        } else if (dataChanged) {
            dispatchCurrentExtraKeysViewChanged();
        }
        dispatchActivePageChanged(safeIndex, false);
    }

    public void setCurrentSessionPage(int index, boolean animate) {
        int safeIndex = sessionPagerAdapter.clampIndex(index);
        mSelectedSessionIndex = safeIndex;
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
        return holder == null ? null : holder.extraKeysView;
    }

    public boolean isTerminalToolbarPrimaryPageSelected() {
        return toolbarStateMachine.isTerminalPageSelected();
    }

    public boolean isTerminalToolbarTextInputPageSelected() {
        return toolbarStateMachine.isTextInputPageSelected();
    }

    public void focusToolbarTextInput() {
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

        if (mCallbacks != null) {
            mCallbacks.onActiveTerminalViewChanged(holder.terminalView, holder.session);
        }
        if (mCallbacks != null && !mSuppressSessionPageCallback) {
            mCallbacks.onSessionPageSelected(position, holder.session, fromUser);
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

        @Override
        public int getCount() {
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
            for (TerminalSessionSurfaceItem item : items) {
                if (TextUtils.equals(item.key, holder.key)) return POSITION_UNCHANGED;
            }
            return POSITION_NONE;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            TerminalSessionSurfaceItem item = position < items.size()
                ? items.get(position)
                : new TerminalSessionSurfaceItem(PLACEHOLDER_KEY, null);

            PageHolder holder = PLACEHOLDER_KEY.equals(item.key)
                ? createPageHolder()
                : holdersByKey.get(item.key);
            if (holder == null) {
                holder = createPageHolder();
                holdersByKey.put(item.key, holder);
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
            TerminalSessionSurfaceItem item = position < items.size() ? items.get(position) : null;
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
            SessionSwipeFrameLayout extraKeysContainer =
                root.findViewById(R.id.terminal_session_page_extra_keys_container);
            ExtraKeysView extraKeysView = root.findViewById(R.id.terminal_session_page_extra_keys);
            applyTerminalViewConfig(terminalView);
            reloadExtraKeysView(extraKeysView);
            PageHolder holder = new PageHolder(root, terminalView, extraKeysContainer, extraKeysView);
            root.setTag(holder);
            return holder;
        }

        private void bindHolder(@NonNull PageHolder holder, @NonNull TerminalSessionSurfaceItem item) {
            holder.key = item.key;
            holder.session = item.session;
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
        @NonNull final SessionSwipeFrameLayout extraKeysContainer;
        @NonNull final ExtraKeysView extraKeysView;
        @Nullable TerminalSession session;
        @Nullable String key;

        PageHolder(@NonNull View root,
                   @NonNull TerminalView terminalView,
                   @NonNull SessionSwipeFrameLayout extraKeysContainer,
                   @NonNull ExtraKeysView extraKeysView) {
            this.root = root;
            this.terminalView = terminalView;
            this.extraKeysContainer = extraKeysContainer;
            this.extraKeysView = extraKeysView;
        }
    }
}
