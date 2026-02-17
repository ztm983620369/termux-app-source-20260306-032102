package com.termux.ui.files

import androidx.compose.runtime.Immutable

@Immutable
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val lastModifiedMillis: Long,
    val sizeBytes: Long?
)
