package org.fossify.filemanager.interfaces

interface FileManagerControllerCommands {
    fun toggleMainFabMenu()
    fun showSessionSwitcher()
    fun createDocumentConfirmed(path: String)
    fun refreshMenuItems()
    fun updateFragmentColumnCounts()
    fun openedDirectory()
}
