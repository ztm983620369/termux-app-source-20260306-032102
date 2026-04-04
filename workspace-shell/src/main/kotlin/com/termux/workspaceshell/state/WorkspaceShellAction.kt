package com.termux.workspaceshell.state

import com.termux.workspaceshell.model.WorkspaceReusePolicy
import com.termux.workspaceshell.model.WorkspaceTabSpec

sealed interface WorkspaceShellAction {
    data class OpenTab(
        val spec: WorkspaceTabSpec,
        val reusePolicy: WorkspaceReusePolicy = WorkspaceReusePolicy.REUSE_BY_KEY
    ) : WorkspaceShellAction

    data class SelectTab(val tabId: String) : WorkspaceShellAction

    data class CloseTab(val tabId: String) : WorkspaceShellAction

    data class UpdateTabRoute(val tabId: String, val currentRoute: String) : WorkspaceShellAction

    data class UpdateSearchQuery(val tabId: String, val query: String) : WorkspaceShellAction

    data object ShowSearch : WorkspaceShellAction

    data object HideSearch : WorkspaceShellAction
}
