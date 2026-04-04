package com.termux.workspaceshell.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.EditText
import androidx.core.view.isVisible
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.termux.workspaceshell.databinding.ViewWorkspaceChromeBinding
import com.termux.workspaceshell.model.WorkspaceShellState

class WorkspaceChromeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppBarLayout(context, attrs, defStyleAttr) {

    private val binding = ViewWorkspaceChromeBinding.inflate(LayoutInflater.from(context), this)
    private var searchWatcher: TextWatcher? = null

    var onSearchQueryChangedListener: ((String) -> Unit)? = null
    var onSearchCloseListener: (() -> Unit)? = null

    init {
        bindSearchInput(binding.workspaceSearchInput)
        binding.workspaceSearchClose.setOnClickListener {
            setSearchVisible(false, clearQuery = true)
            onSearchCloseListener?.invoke()
        }
    }

    fun tabsView(): WorkspaceTabsBarView = binding.workspaceTabsBar

    fun toolbar(): MaterialToolbar = binding.workspaceToolbar

    fun render(state: WorkspaceShellState) {
        binding.workspaceTabsBar.setTabs(state.tabs)
        if (!binding.workspaceSearchPanel.isVisible && state.searchVisible) {
            setSearchVisible(true, requestFocus = false)
        } else if (binding.workspaceSearchPanel.isVisible && !state.searchVisible) {
            setSearchVisible(false, clearQuery = false)
        }
        val activeTabId = state.activeTabId
        val query = state.queryFor(activeTabId)
        if (binding.workspaceSearchInput.text?.toString().orEmpty() != query) {
            binding.workspaceSearchInput.setText(query)
            binding.workspaceSearchInput.setSelection(query.length)
        }
    }

    fun setPalette(palette: WorkspaceChromePalette) {
        setBackgroundColor(palette.backgroundColor)
        binding.workspaceTabsBar.setPalette(palette)
        binding.workspaceHeader.setBackgroundColor(palette.backgroundColor)
        binding.workspaceToolbar.setBackgroundColor(palette.backgroundColor)
        binding.workspaceToolbar.setTitleTextColor(palette.onSurfaceColor)
        binding.workspaceSearchPanel.strokeColor = palette.accentColor
        binding.workspaceSearchPanel.setCardBackgroundColor(palette.surfaceColor)
        binding.workspaceSearchInput.setTextColor(palette.onSurfaceColor)
        binding.workspaceSearchInput.setHintTextColor((palette.onSurfaceColor and 0x00FFFFFF) or 0x66000000)
        binding.workspaceSearchClose.imageTintList = android.content.res.ColorStateList.valueOf(palette.onSurfaceColor)
        binding.workspaceSearchPrefix.setTextColor(palette.onSurfaceColor)
    }

    fun setSearchVisible(visible: Boolean, requestFocus: Boolean = true, clearQuery: Boolean = false) {
        binding.workspaceSearchPanel.isVisible = visible
        if (!visible && clearQuery) {
            binding.workspaceSearchInput.setText("")
        }
        if (visible && requestFocus) {
            binding.workspaceSearchInput.requestFocus()
        } else if (!visible) {
            binding.workspaceSearchInput.clearFocus()
        }
    }

    fun isSearchVisible(): Boolean = binding.workspaceSearchPanel.isVisible

    fun focusSearch() {
        setSearchVisible(true)
        binding.workspaceSearchInput.requestFocus()
        binding.workspaceSearchInput.setSelection(binding.workspaceSearchInput.text?.length ?: 0)
    }

    fun searchInput(): EditText = binding.workspaceSearchInput

    private fun bindSearchInput(editText: EditText) {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onSearchQueryChangedListener?.invoke(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        searchWatcher = watcher
        editText.addTextChangedListener(watcher)
    }
}
