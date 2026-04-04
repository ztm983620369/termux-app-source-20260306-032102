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
        this.palette = palette
        setBackgroundColor(palette.backgroundColor)
        updateTabs()
    }

    fun setTabs(items: List<WorkspaceTabModel>) {
        tabs.clear()
        tabs.addAll(items)
        updateTabs()
    }

    private fun updateTabs() {
        val selectedIndex = tabs.indexOfFirst { it.selected }
        val orderedViews = ArrayList<View>(tabs.size)
        tabs.forEachIndexed { index, tab ->
            val holder = createTabViewHolder()
            bindTabView(holder, tab, index)
            orderedViews.add(holder.root)
        }

        tabsContainer.removeAllViews()
        orderedViews.forEach { tabsContainer.addView(it, createTabLayoutParams()) }

        if (selectedIndex >= 0 && selectedIndex != lastSelectedIndex) {
            post { scrollToTab(selectedIndex) }
        }
        lastSelectedIndex = selectedIndex
    }

    private fun createTabViewHolder(): TabViewHolder {
        val tabLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = dp(92)
            isClickable = true
            isFocusable = true
        }

        val statusDot = View(context)
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
        }
        val badgeLayoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(6)
            marginEnd = dp(4)
        }
        tabLayout.addView(badgeView, badgeLayoutParams)

        val closeView = TextView(context).apply {
            text = "\u2715"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(10), dp(6))
        }
        tabLayout.addView(closeView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val holder = TabViewHolder(tabLayout, statusDot, titleView, badgeView, closeView)
        tabLayout.tag = holder
        return holder
    }

    private fun bindTabView(holder: TabViewHolder, tab: WorkspaceTabModel, index: Int) {
        holder.index = index
        holder.titleView.text = tab.title
        holder.titleView.setTextColor(if (tab.selected) Color.WHITE else palette.onSurfaceColor)
        holder.statusDot.background = createStatusDotBackground(tab.tone)
        holder.root.background = createTabBackground(tab.selected, tab.tone)
        holder.root.alpha = if (tab.selected) 1f else 0.92f
        holder.root.contentDescription = tab.contentDescription ?: tab.title

        if (tab.badgeText.isNullOrBlank()) {
            holder.badgeView.visibility = View.GONE
        } else {
            holder.badgeView.visibility = View.VISIBLE
            holder.badgeView.text = tab.badgeText
            holder.badgeView.setTextColor(Color.WHITE)
            holder.badgeView.background = createBadgeBackground(tab.tone)
        }

        holder.closeView.visibility = if (tab.closable && !tab.locked) View.VISIBLE else View.GONE
        holder.closeView.setTextColor(if (tab.selected) Color.WHITE else palette.onSurfaceColor)
        holder.closeView.setOnClickListener {
            onTabCloseListener?.onTabClose(index, tab)
        }

        bindTabTouch(holder, tab)
    }

    private fun bindTabTouch(holder: TabViewHolder, tab: WorkspaceTabModel) {
        val touchMachine = WorkspaceTabTouchStateMachine(tabMoveThresholdPx.toFloat())
        val longPressRunnable = Runnable {
            if (touchMachine.onLongPress()) {
                holder.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onTabLongPressListener?.onTabLongPress(holder.index, tab)
            }
        }

        holder.root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchMachine.onDown(event.x, event.y)
                    holder.root.postDelayed(longPressRunnable, longPressTimeoutMs.toLong())
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    touchMachine.onMove(event.x, event.y)
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    holder.root.removeCallbacks(longPressRunnable)
                    touchMachine.onCancel()
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    holder.root.removeCallbacks(longPressRunnable)
                    when (touchMachine.onUp()) {
                        WorkspaceTabTouchStateMachine.ReleaseAction.CLICK -> {
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

    private fun createTabLayoutParams(): LayoutParams {
        return LayoutParams(LayoutParams.WRAP_CONTENT, dp(38)).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
            marginEnd = dp(8)
        }
    }

    private fun scrollToTab(index: Int) {
        val child = tabsContainer.getChildAt(index) ?: return
        val left = child.left - dp(12)
        val right = child.right + dp(12)
        val center = (left + right - width) / 2
        scrollView.smoothScrollTo(center.coerceAtLeast(0), 0)
    }

    private fun createTabBackground(selected: Boolean, tone: WorkspaceTabTone): GradientDrawable {
        val toneColor = toneColor(tone)
        val fillColor = if (selected) blendColor(toneColor, palette.surfaceColor, 0.78f) else palette.surfaceColor
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(fillColor)
            setStroke(dp(1), if (selected) toneColor else blendColor(palette.onSurfaceColor, fillColor, 0.82f))
        }
    }

    private fun createStatusDotBackground(tone: WorkspaceTabTone): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(toneColor(tone))
        }
    }

    private fun createBadgeBackground(tone: WorkspaceTabTone): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(toneColor(tone))
        }
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
    private fun blendColor(@ColorInt foreground: Int, @ColorInt background: Int, ratio: Float): Int {
        val clamped = ratio.coerceIn(0f, 1f)
        val inv = 1f - clamped
        val a = (Color.alpha(foreground) * clamped + Color.alpha(background) * inv).toInt()
        val r = (Color.red(foreground) * clamped + Color.red(background) * inv).toInt()
        val g = (Color.green(foreground) * clamped + Color.green(background) * inv).toInt()
        val b = (Color.blue(foreground) * clamped + Color.blue(background) * inv).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun dp(value: Int): Int {
        return (resources.displayMetrics.density * value).toInt()
    }

    private data class TabViewHolder(
        val root: LinearLayout,
        val statusDot: View,
        val titleView: TextView,
        val badgeView: TextView,
        val closeView: TextView,
        var index: Int = -1
    )
}
