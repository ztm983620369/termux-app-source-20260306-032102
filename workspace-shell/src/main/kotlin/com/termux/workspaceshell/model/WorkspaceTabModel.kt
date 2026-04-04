package com.termux.workspaceshell.model

data class WorkspaceTabModel(
    val id: String,
    val reuseKey: String,
    val kind: WorkspaceKind,
    val tone: WorkspaceTabTone,
    val title: String,
    val rootRoute: String,
    val currentRoute: String,
    val selected: Boolean,
    val locked: Boolean,
    val closable: Boolean,
    val badgeText: String? = null,
    val contentDescription: String? = null
)
