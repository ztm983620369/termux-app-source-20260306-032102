package io.github.rosemoe.sora.app

import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import com.termux.editorsync.EditorSyncIndicatorState
import com.termux.editorsync.EditorSyncSnapshot

internal class EditorSaveStatusUi {

    private var menuItem: MenuItem? = null
    private var rootView: View? = null
    private var spinnerView: ProgressBar? = null
    private var syncedDotView: View? = null
    private var hasClickAction: Boolean = false

    fun bind(menu: Menu, onClick: (() -> Unit)? = null) {
        menuItem = menu.findItem(R.id.save_sync_status)
        val actionView = menuItem?.actionView
        rootView = actionView
        spinnerView = actionView?.findViewById(R.id.save_sync_spinner)
        syncedDotView = actionView?.findViewById(R.id.save_sync_dot)
        hasClickAction = onClick != null
        actionView?.isClickable = hasClickAction
        actionView?.isFocusable = hasClickAction
        actionView?.setOnClickListener(
            onClick?.let { click ->
                View.OnClickListener { click() }
            }
        )
        menuItem?.setOnMenuItemClickListener(
            onClick?.let { click ->
                MenuItem.OnMenuItemClickListener {
                    click()
                    true
                }
            }
        )
    }

    fun render(snapshot: EditorSyncSnapshot) {
        val visible = snapshot.canSave && snapshot.shouldShowIndicator
        menuItem?.isVisible = visible
        rootView?.visibility = if (visible) View.VISIBLE else View.GONE
        spinnerView?.visibility =
            if (visible && snapshot.indicatorState == EditorSyncIndicatorState.SPINNING) View.VISIBLE else View.GONE
        syncedDotView?.visibility =
            if (visible && snapshot.indicatorState == EditorSyncIndicatorState.SYNCED) View.VISIBLE else View.GONE
        val statusText = snapshot.statusText ?: menuItem?.title
        rootView?.contentDescription = if (hasClickAction && visible) {
            "$statusText，点击刷新最新内容"
        } else {
            statusText
        }
    }
}
