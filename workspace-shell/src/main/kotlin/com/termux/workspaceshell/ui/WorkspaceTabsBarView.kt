package com.termux.workspaceshell.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import com.termux.workspaceshell.R
import com.termux.workspaceshell.model.WorkspaceTabModel
import com.termux.workspaceshell.model.WorkspaceTabTone

class WorkspaceTabsBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface OnTabSelectedListener {
        fun onTabSelected(index: Int, tab: WorkspaceTabModel)
    }

    interface OnTabCloseListener {
        fun onTabClose(index: Int, tab: WorkspaceTabModel)
    }

    interface OnTabLongPressListener {
        fun onTabLongPress(index: Int, tab: WorkspaceTabModel)
    }

    private val scrollView: HorizontalScrollView
    private val tabsContainer: LinearLayout
    private val tabs = ArrayList<WorkspaceTabModel>()
    private var lastSelectedIndex = -1
    private var lastSelectedTabId: String? = null
    private var lastRenderedTabIds: List<String> = emptyList()
    private var tabMoveThresholdPx = 0
    private var longPressTimeoutMs = 0
    private var palette = WorkspaceChromePalette(
        backgroundColor = 0xFF121212.toInt(),
        surfaceColor = 0xFF1D1D1D.toInt(),
        onSurfaceColor = Color.WHITE,
        accentColor = 0xFF4CAF50.toInt()
    )

    var onTabSelectedListener: OnTabSelectedListener? = null
    var onTabCloseListener: OnTabCloseListener? = null
    var onTabLongPressListener: OnTabLongPressListener? = null

    init {
        orientation = HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_workspace_tabs_bar, this, true)
        scrollView = findViewById(R.id.workspace_tabs_scroll)
        tabsContainer = findViewById(R.id.workspace_tabs_container)
        val viewConfiguration = ViewConfiguration.get(context)
        tabMoveThresholdPx = viewConfiguration.scaledTouchSlop
        longPressTimeoutMs = ViewConfiguration.getLongPressTimeout()
        setBackgroundColor(palette.backgroundColor)
    }

    fun setPalette(palette: WorkspaceChromePalette) {
        if (this.palette == palette) return
        this.palette = palette
        setBackgroundColor(palette.backgroundColor)
        clearHolderVisualKeys()
        updateTabs()
    }

    fun setTabs(items: List<WorkspaceTabModel>) {
        tabs.clear()
        tabs.addAll(items)
        updateTabs()
    }

    fun setPreviewSelectedTab(tabId: String?) {
        val selectedTabId = tabId ?: lastSelectedTabId
        var selectedIndex = -1
        for (i in 0 until tabsContainer.childCount) {
            val holder = getTabViewHolder(tabsContainer.getChildAt(i)) ?: continue
            val tab = holder.tab ?: continue
            val isSelected = tab.id == selectedTabId
            if (isSelected) {
                selectedIndex = i
            }
            val targetProgress = if (isSelected) 1f else 0f
            if (holder.selectedProgress != targetProgress) {
                animateSelection(holder, tab.tone, targetProgress)
            }
        }
        if (selectedIndex >= 0) {
            scrollToTabIfNeeded(selectedIndex, smooth = true)
        }
    }

    private fun updateTabs() {
        val selectedIndex = tabs.indexOfFirst { it.selected }
        val selectedTabId = tabs.getOrNull(selectedIndex)?.id
        val tabIds = tabs.map { it.id }
        val structureChanged = tabIds != lastRenderedTabIds
        val selectedChanged = selectedTabId != lastSelectedTabId || selectedIndex != lastSelectedIndex
        val holdersById = LinkedHashMap<String, TabViewHolder>()
        val recycledHolders = ArrayList<TabViewHolder>()

        for (i in 0 until tabsContainer.childCount) {
            val holder = getTabViewHolder(tabsContainer.getChildAt(i)) ?: continue
            val key = holder.key
            if (!key.isNullOrBlank() && !holdersById.containsKey(key)) {
                holdersById[key] = holder
            } else {
                recycledHolders.add(holder)
            }
        }

        val orderedViews = ArrayList<View>(tabs.size)
        tabs.forEachIndexed { index, tab ->
            val holder = holdersById.remove(tab.id)
                ?: recycledHolders.removeLastOrNull()
                ?: createTabViewHolder()
            bindTabView(holder, tab, index)
            orderedViews.add(holder.root)
        }

        holdersById.values.forEach {
            it.visualKey = null
            it.tab = null
            it.key = null
        }
        recycledHolders.forEach {
            it.visualKey = null
            it.tab = null
            it.key = null
        }
        applyOrderedTabViews(orderedViews)

        if (selectedIndex >= 0 && (selectedChanged || structureChanged)) {
            scrollToTabIfNeeded(selectedIndex, smooth = selectedChanged && !structureChanged)
        } else {
            clampScrollToContent()
        }
        lastSelectedIndex = selectedIndex
        lastSelectedTabId = selectedTabId
        lastRenderedTabIds = tabIds
    }

    private fun clearHolderVisualKeys() {
        for (i in 0 until tabsContainer.childCount) {
            getTabViewHolder(tabsContainer.getChildAt(i))?.visualKey = null
        }
    }

    private fun getTabViewHolder(child: View?): TabViewHolder? {
        return child?.tag as? TabViewHolder
    }

    private fun applyOrderedTabViews(orderedViews: List<View>) {
        val alreadyOrdered = tabsContainer.childCount == orderedViews.size &&
            orderedViews.indices.all { tabsContainer.getChildAt(it) === orderedViews[it] }
        if (alreadyOrdered) return

        tabsContainer.suppressLayout(true)
        try {
            tabsContainer.removeAllViews()
            orderedViews.forEach { tabsContainer.addView(it, createTabLayoutParams()) }
        } finally {
            tabsContainer.suppressLayout(false)
        }
    }

    private fun createTabViewHolder(): TabViewHolder {
        val tabBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
        }
        val dotBackground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }
        val badgeBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
        }

        val tabLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = dp(92)
            isClickable = true
            isFocusable = true
            background = tabBackground
        }

        val statusDot = View(context).apply {
            background = dotBackground
        }
        val dotLayoutParams = LayoutParams(dp(8), dp(8)).apply {
            marginStart = dp(10)
            marginEnd = dp(6)
        }
        tabLayout.addView(statusDot, dotLayoutParams)

        val titleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(typeface, Typeface.BOLD)
        }
        val titleLayoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT).apply {
            weight = 1f
        }
        tabLayout.addView(titleView, titleLayoutParams)

        val badgeView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(1), dp(5), dp(1))
            visibility = View.GONE
            background = badgeBackground
        }
        val badgeLayoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(6)
            marginEnd = dp(4)
        }
        tabLayout.addView(badgeView, badgeLayoutParams)

        val closeView = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(10), dp(6))
        }
        tabLayout.addView(closeView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val holder = TabViewHolder(
            root = tabLayout,
            statusDot = statusDot,
            titleView = titleView,
            badgeView = badgeView,
            closeView = closeView,
            tabBackground = tabBackground,
            statusDotBackground = dotBackground,
            badgeBackground = badgeBackground
        )
        tabLayout.tag = holder
        installTabTouch(holder)
        closeView.setOnClickListener {
            val tab = holder.tab ?: return@setOnClickListener
            onTabCloseListener?.onTabClose(holder.index, tab)
        }
        return holder
    }

    private fun bindTabView(holder: TabViewHolder, tab: WorkspaceTabModel, index: Int) {
        val tabChanged = holder.key != tab.id
        if (tabChanged) {
            holder.visualKey = null
        }
        val visualKey = tab.visualKey()
        val visualChanged = holder.visualKey != visualKey

        holder.index = index
        holder.key = tab.id
        holder.tab = tab

        if (!visualChanged) return

        holder.visualKey = visualKey
        holder.titleView.text = tab.title
        holder.root.contentDescription = tab.contentDescription ?: tab.title
        holder.closeView.visibility = if (tab.closable && !tab.locked) View.VISIBLE else View.GONE

        if (tab.badgeText.isNullOrBlank()) {
            holder.badgeView.visibility = View.GONE
            holder.badgeView.text = ""
        } else {
            holder.badgeView.visibility = View.VISIBLE
            holder.badgeView.text = tab.badgeText
            holder.badgeView.setTextColor(readableTextColor(toneColor(tab.tone)))
            holder.badgeBackground.setColor(toneColor(tab.tone))
        }

        val targetProgress = if (tab.selected) 1f else 0f
        animateSelection(holder, tab.tone, targetProgress)
    }

    private fun installTabTouch(holder: TabViewHolder) {
        val touchMachine = WorkspaceTabTouchStateMachine(tabMoveThresholdPx.toFloat())
        val longPressRunnable = Runnable {
            val tab = holder.tab ?: return@Runnable
            if (touchMachine.onLongPress()) {
                holder.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onTabLongPressListener?.onTabLongPress(holder.index, tab)
            }
        }

        holder.root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchMachine.onDown(event.x, event.y)
                    holder.tab?.let { setPreviewSelectedTab(it.id) }
                    holder.root.parent?.requestDisallowInterceptTouchEvent(true)
                    holder.root.postDelayed(longPressRunnable, longPressTimeoutMs.toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchMachine.onMove(event.x, event.y)) {
                        holder.root.removeCallbacks(longPressRunnable)
                        holder.root.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    holder.root.parent?.requestDisallowInterceptTouchEvent(false)
                    holder.root.removeCallbacks(longPressRunnable)
                    touchMachine.onCancel()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    holder.root.parent?.requestDisallowInterceptTouchEvent(false)
                    holder.root.removeCallbacks(longPressRunnable)
                    when (touchMachine.onUp()) {
                        WorkspaceTabTouchStateMachine.ReleaseAction.CLICK -> {
                            val tab = holder.tab ?: return@setOnTouchListener true
                            holder.root.performClick()
                            onTabSelectedListener?.onTabSelected(holder.index, tab)
                        }
                        WorkspaceTabTouchStateMachine.ReleaseAction.CONSUME,
                        WorkspaceTabTouchStateMachine.ReleaseAction.NONE -> Unit
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun animateSelection(holder: TabViewHolder, tone: WorkspaceTabTone, targetProgress: Float) {
        applySelectionProgress(holder, tone, targetProgress)
    }

    private fun applySelectionProgress(holder: TabViewHolder, tone: WorkspaceTabTone, progress: Float) {
        holder.selectedProgress = progress
        val toneColor = toneColor(tone)
        val fillColor = toneFillColor(tone, progress)
        val strokeColor = blendColor(toneColor, blendColor(palette.onSurfaceColor, palette.surfaceColor, 0.28f), progress)
        val textColor = blendColor(readableTextColor(toneFillColor(tone, 1f)), palette.onSurfaceColor, progress)

        holder.tabBackground.setColor(fillColor)
        holder.tabBackground.setStroke(dp(1), strokeColor)
        holder.statusDotBackground.setColor(toneColor)
        holder.statusDot.alpha = 0.62f + (0.38f * progress)
        holder.titleView.setTextColor(textColor)
        holder.closeView.setTextColor(textColor)
        holder.root.alpha = 0.94f + (0.06f * progress)
    }

    private fun createTabLayoutParams(): LayoutParams {
        return LayoutParams(LayoutParams.WRAP_CONTENT, dp(38)).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
            marginEnd = dp(8)
        }
    }

    private fun scrollToTabIfNeeded(index: Int, smooth: Boolean) {
        if (scrollView.width <= 0 || tabsContainer.width <= 0) return
        val child = tabsContainer.getChildAt(index) ?: return
        val viewportWidth = scrollView.width
        val edgeMargin = dp(12)
        val maxScroll = (tabsContainer.width - viewportWidth).coerceAtLeast(0)
        val visibleLeft = scrollView.scrollX + edgeMargin
        val visibleRight = scrollView.scrollX + viewportWidth - edgeMargin
        val target = when {
            child.left >= visibleLeft && child.right <= visibleRight -> return
            child.left < visibleLeft -> child.left - edgeMargin
            else -> child.right - viewportWidth + edgeMargin
        }.coerceIn(0, maxScroll)

        if (smooth) {
            scrollView.smoothScrollTo(target, 0)
        } else {
            scrollView.scrollTo(target, 0)
        }
    }

    private fun clampScrollToContent() {
        val maxScroll = (tabsContainer.width - scrollView.width).coerceAtLeast(0)
        if (scrollView.scrollX > maxScroll) {
            scrollView.scrollTo(maxScroll, 0)
        }
    }

    private fun toneFillColor(tone: WorkspaceTabTone, selectedProgress: Float): Int {
        val selectedFill = blendColor(toneColor(tone), palette.surfaceColor, 0.58f)
        return blendColor(selectedFill, palette.surfaceColor, selectedProgress)
    }

    @ColorInt
    private fun toneColor(tone: WorkspaceTabTone): Int {
        return when (tone) {
            WorkspaceTabTone.HOME -> palette.accentColor
            WorkspaceTabTone.LOCAL -> palette.accentColor
            WorkspaceTabTone.FAVORITE -> 0xFFE6A700.toInt()
            WorkspaceTabTone.REMOTE -> 0xFF00A8A8.toInt()
            WorkspaceTabTone.SYSTEM -> 0xFFD65A5A.toInt()
            WorkspaceTabTone.NEUTRAL -> blendColor(palette.onSurfaceColor, palette.surfaceColor, 0.4f)
        }
    }

    @ColorInt
    private fun readableTextColor(@ColorInt background: Int): Int {
        val luminance = (0.299f * Color.red(background) + 0.587f * Color.green(background) + 0.114f * Color.blue(background)) / 255f
        return if (luminance > 0.58f) Color.BLACK else Color.WHITE
    }

    @ColorInt
    private fun blendColor(@ColorInt foreground: Int, @ColorInt background: Int, ratio: Float): Int {
        val clamped = ratio.coerceIn(0f, 1f)
        val inv = 1f - clamped
        val a = (Color.alpha(foreground) * clamped + Color.alpha(background) * inv).toInt()
        val r = (Color.red(foreground) * clamped + Color.red(background) * inv).toInt()
        val g = (Color.green(foreground) * clamped + Color.green(background) * inv).toInt()
        val b = (Color.blue(foreground) * clamped + Color.blue(background) * inv).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun WorkspaceTabModel.visualKey(): TabVisualKey {
        return TabVisualKey(
            id = id,
            title = title,
            selected = selected,
            tone = tone,
            badgeText = badgeText,
            closable = closable,
            locked = locked,
            contentDescription = contentDescription
        )
    }

    private fun dp(value: Int): Int {
        return (resources.displayMetrics.density * value).toInt()
    }

    private data class TabVisualKey(
        val id: String,
        val title: String,
        val selected: Boolean,
        val tone: WorkspaceTabTone,
        val badgeText: String?,
        val closable: Boolean,
        val locked: Boolean,
        val contentDescription: String?
    )

    private data class TabViewHolder(
        val root: LinearLayout,
        val statusDot: View,
        val titleView: TextView,
        val badgeView: TextView,
        val closeView: TextView,
        val tabBackground: GradientDrawable,
        val statusDotBackground: GradientDrawable,
        val badgeBackground: GradientDrawable,
        var index: Int = -1,
        var key: String? = null,
        var tab: WorkspaceTabModel? = null,
        var visualKey: TabVisualKey? = null,
        var selectedProgress: Float = 0f
    )
}
