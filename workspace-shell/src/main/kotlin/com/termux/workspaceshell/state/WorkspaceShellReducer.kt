package com.termux.workspaceshell.state

import com.termux.workspaceshell.model.WorkspaceReusePolicy
import com.termux.workspaceshell.model.WorkspaceShellState
import com.termux.workspaceshell.model.WorkspaceTabModel
import com.termux.workspaceshell.model.WorkspaceTabSpec

object WorkspaceShellReducer {

    fun createInitialState(homeSpec: WorkspaceTabSpec): WorkspaceShellState {
        val homeTab = homeSpec.toModel(selected = true)
        return WorkspaceShellState(
            tabs = listOf(homeTab),
            activeTabId = homeTab.id
        )
    }

    fun reduce(state: WorkspaceShellState, action: WorkspaceShellAction): WorkspaceShellState {
        return when (action) {
            is WorkspaceShellAction.OpenTab -> openTab(state, action.spec, action.reusePolicy)
            is WorkspaceShellAction.SelectTab -> selectTab(state, action.tabId)
            is WorkspaceShellAction.CloseTab -> closeTab(state, action.tabId)
            is WorkspaceShellAction.UpdateTabRoute -> updateTabRoute(state, action.tabId, action.currentRoute)
            is WorkspaceShellAction.UpdateSearchQuery -> updateSearchQuery(state, action.tabId, action.query)
            WorkspaceShellAction.ShowSearch -> state.copy(searchVisible = true)
            WorkspaceShellAction.HideSearch -> state.copy(searchVisible = false)
        }
    }

    private fun openTab(
        state: WorkspaceShellState,
        spec: WorkspaceTabSpec,
        reusePolicy: WorkspaceReusePolicy
    ): WorkspaceShellState {
        val reusable = if (reusePolicy == WorkspaceReusePolicy.REUSE_BY_KEY) {
            state.tabs.firstOrNull { it.reuseKey == spec.reuseKey && it.kind == spec.kind }
        } else {
            null
        }

        return if (reusable != null) {
            val updatedTabs = state.tabs.map { tab ->
                when (tab.id) {
                    reusable.id -> tab.copy(
                        selected = true,
                        currentRoute = spec.currentRoute,
                        title = spec.title,
                        badgeText = spec.badgeText,
                        contentDescription = spec.contentDescription
                    )
                    else -> tab.copy(selected = false)
                }
            }
            state.copy(
                tabs = updatedTabs,
                activeTabId = reusable.id
            )
        } else {
            val newTab = spec.toModel(selected = true)
            state.copy(
                tabs = state.tabs.map { it.copy(selected = false) } + newTab,
                activeTabId = newTab.id
            )
        }
    }

    private fun selectTab(state: WorkspaceShellState, tabId: String): WorkspaceShellState {
        if (state.tabs.none { it.id == tabId }) return state
        return state.copy(
            tabs = state.tabs.map { it.copy(selected = it.id == tabId) },
            activeTabId = tabId
        )
    }

    private fun closeTab(state: WorkspaceShellState, tabId: String): WorkspaceShellState {
        val target = state.tabs.firstOrNull { it.id == tabId } ?: return state
        if (!target.closable || target.locked) return state
        if (state.tabs.size <= 1) return state

        val currentIndex = state.tabs.indexOfFirst { it.id == tabId }
        val remainingTabs = state.tabs.filterNot { it.id == tabId }
        val nextActiveId = when {
            state.activeTabId != tabId -> state.activeTabId
            currentIndex > 0 -> remainingTabs[currentIndex - 1].id
            else -> remainingTabs.first().id
        }
        val nextQueries = state.searchQueries.toMutableMap().apply { remove(tabId) }
        return state.copy(
            tabs = remainingTabs.map { it.copy(selected = it.id == nextActiveId) },
            activeTabId = nextActiveId,
            searchQueries = nextQueries
        )
    }

    private fun updateTabRoute(state: WorkspaceShellState, tabId: String, currentRoute: String): WorkspaceShellState {
        if (state.tabs.none { it.id == tabId }) return state
        return state.copy(
            tabs = state.tabs.map { tab ->
                if (tab.id == tabId) tab.copy(currentRoute = currentRoute) else tab
            }
        )
    }

    private fun updateSearchQuery(state: WorkspaceShellState, tabId: String, query: String): WorkspaceShellState {
        if (state.tabs.none { it.id == tabId }) return state
        val nextQueries = state.searchQueries.toMutableMap()
        if (query.isBlank()) {
            nextQueries.remove(tabId)
        } else {
            nextQueries[tabId] = query
        }
        return state.copy(searchQueries = nextQueries)
    }

    private fun WorkspaceTabSpec.toModel(selected: Boolean): WorkspaceTabModel {
        return WorkspaceTabModel(
            id = id,
            reuseKey = reuseKey,
            kind = kind,
            tone = tone,
            title = title,
            rootRoute = rootRoute,
            currentRoute = currentRoute,
            selected = selected,
            locked = locked,
            closable = closable,
            badgeText = badgeText,
            contentDescription = contentDescription
        )
    }
}
