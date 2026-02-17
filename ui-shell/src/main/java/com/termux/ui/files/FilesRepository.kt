package com.termux.ui.files

import java.io.File

internal object FilesRepository {
    fun listChildren(directory: File): List<FileEntry> {
        val names = directory.list() ?: return emptyList()
        if (names.isEmpty()) return emptyList()

        val fastMode = names.size > 2000

        val entries = ArrayList<FileEntry>(names.size)
        for (name in names) {
            val file = File(directory, name)
            val isDir = file.isDirectory
            val lastModified = if (fastMode) 0L else file.lastModified()
            val sizeBytes = if (fastMode || isDir) null else file.length()
            entries.add(
                FileEntry(
                    name = name,
                    path = file.absolutePath,
                    isDirectory = isDir,
                    lastModifiedMillis = lastModified,
                    sizeBytes = sizeBytes
                )
            )
        }

        entries.sortWith { a, b ->
            if (!fastMode && a.isDirectory != b.isDirectory) {
                if (a.isDirectory) -1 else 1
            } else {
                val c = a.name.compareTo(b.name, ignoreCase = true)
                if (c != 0) c else a.name.compareTo(b.name)
            }
        }

        return entries
    }
}
