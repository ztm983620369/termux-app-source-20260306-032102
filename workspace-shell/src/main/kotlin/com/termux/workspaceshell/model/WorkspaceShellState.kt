package com.termux.workspaceshell.model

data class WorkspaceShellState(
    val tabs: List<WorkspaceTabModel>,
    val activeTabId: String,
    val searchVisible: Boolean = false,
    val searchQueries: Map<String, String> = emptyMap()
) {
    val activeTab: WorkspaceTabModel?
        get() = tabs.firstOrNull { it.id == activeTabId }

    fun queryFor(tabId: String): String = searchQueries[tabId].orEmpty()
}
