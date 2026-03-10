package org.fossify.filemanager.interfaces

import java.util.ArrayList

interface FileManagerResultHandler {
    fun pickedPath(path: String)
    fun pickedPaths(paths: ArrayList<String>)
    fun pickedRingtone(path: String)
}
