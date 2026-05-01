package org.fossify.filemanager.interfaces

import android.view.View

interface FileManagerControllerCommands {
    fun toggleMainFabMenu()
    fun showSessionSwitcher()
    fun showPathBarActions(anchor: View, currentPath: String)
    fun createDocumentConfirmed(path: String)
    fun openPathAndHighlight(targetPath: String, highlightPaths: ArrayList<String>)
    fun installDownloadedApk(path: String, deleteAfterInstall: Boolean)
    fun refreshMenuItems()
    fun updateFragmentColumnCounts()
    fun openedDirectory()
    fun closeActiveWorkspaceTabIfPossible(): Boolean
}
