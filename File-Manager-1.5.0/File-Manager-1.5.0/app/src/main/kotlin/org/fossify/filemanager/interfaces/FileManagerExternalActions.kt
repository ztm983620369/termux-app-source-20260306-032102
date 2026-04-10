package org.fossify.filemanager.interfaces

interface FileManagerExternalActions {
    fun openInTerminal(path: String)
    fun installDownloadedApk(path: String, deleteAfterInstall: Boolean)
}
