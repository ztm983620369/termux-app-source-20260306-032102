package com.termux.workspaceshell.model

data class WorkspaceTabSpec(
    val id: String,
    val reuseKey: String,
    val kind: WorkspaceKind,
    val tone: WorkspaceTabTone,
    val title: String,
    val rootRoute: String,
    val currentRoute: String,
    val locked: Boolean = false,
    val closable: Boolean = !locked,
    val badgeText: String? = null,
    val contentDescription: String? = null
)
