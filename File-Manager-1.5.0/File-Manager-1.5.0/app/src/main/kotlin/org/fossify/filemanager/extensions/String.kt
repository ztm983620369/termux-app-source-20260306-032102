package org.fossify.filemanager.extensions

import java.util.Locale

fun String.isZipFile() = endsWith(".zip", true)

fun String.isArchiveFile(): Boolean {
    val lower = lowercase(Locale.ROOT)
    return supportedArchiveSuffixes.any { lower.endsWith(it) }
}

private val supportedArchiveSuffixes = arrayOf(
    ".zip",
    ".jar",
    ".apk",
    ".aar",
    ".war",
    ".tar",
    ".tar.gz",
    ".tgz",
    ".tar.bz2",
    ".tbz",
    ".tbz2",
    ".tar.xz",
    ".txz",
    ".tar.zst",
    ".tzst",
    ".gz",
    ".bz2",
    ".xz",
    ".zst",
    ".7z",
    ".rar",
    ".001",
    ".cab",
    ".iso",
    ".img",
    ".dmg",
    ".wim",
    ".swm",
    ".esd",
    ".ar",
    ".deb",
    ".rpm",
    ".cpio",
    ".lzma",
    ".lz4",
    ".br",
    ".z",
    ".lzh",
    ".lha",
    ".chm",
    ".msi",
    ".nsis",
    ".udf",
    ".vhd",
    ".vhdx",
    ".vmdk",
    ".qcow",
    ".qcow2",
    ".squashfs",
    ".crx",
    ".xar"
)

fun String.isPathInHiddenFolder(): Boolean {
    val parts = split("/")
    for (i in 1 until parts.size - 1) {
        val part = parts[i]
        val isHidden = part.startsWith(".") && part != "." && part != ".." && part.isNotEmpty()
        if (isHidden) {
            return true
        }
    }
    return false
}
