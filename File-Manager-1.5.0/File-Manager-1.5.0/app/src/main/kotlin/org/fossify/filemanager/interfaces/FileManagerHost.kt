package org.fossify.filemanager.interfaces

import java.util.ArrayList

interface FileManagerHost {
    fun toggleMainFabMenu()
    fun showSessionSwitcher()
    fun createDocumentConfirmed(path: String)
    fun pickedPath(path: String)
    fun pickedPaths(paths: ArrayList<String>)
    fun pickedRingtone(path: String)
    fun refreshMenuItems()
    fun updateFragmentColumnCounts()
    fun openedDirectory()
    fun openInTerminal(path: String)
}
