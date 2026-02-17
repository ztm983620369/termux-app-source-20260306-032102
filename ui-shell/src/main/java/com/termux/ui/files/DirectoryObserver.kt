package com.termux.ui.files

import android.os.FileObserver
import java.io.File

internal class DirectoryObserver(
    directory: File,
    private val onDirectoryChanged: (event: Int, path: String?) -> Unit
) : FileObserver(
    directory.absolutePath,
    CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE or DELETE_SELF or MOVE_SELF or ATTRIB
) {

    override fun onEvent(event: Int, path: String?) {
        onDirectoryChanged(event, path)
    }
}
