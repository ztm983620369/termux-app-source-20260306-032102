package org.fossify.filemanager.extensions

import android.content.Context
import android.os.storage.StorageManager
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.isPathOnSD
import org.fossify.filemanager.helpers.Config
import org.fossify.filemanager.helpers.PRIMARY_VOLUME_NAME
import java.util.Locale

val Context.config: Config get() = Config.newInstance(applicationContext)

private fun isSameOrSubPath(path: String, basePath: String): Boolean {
    val base = basePath.trimEnd('/')
    if (base.isEmpty()) return false

    val trimmedPath = path.trimEnd('/')
    return trimmedPath == base || trimmedPath.startsWith("$base/")
}

fun Context.isPathOnRoot(path: String): Boolean {
    val trimmedPath = path.trimEnd('/')

    if (isSameOrSubPath(trimmedPath, config.internalStoragePath)) return false
    if (isPathOnOTG(trimmedPath) || isPathOnSD(trimmedPath)) return false

    // Treat the app-private data directory as non-root, since the app can access it without
    // requiring device root.
    val pkg = packageName
    val appPrivateDirs = listOfNotNull(
        applicationInfo?.dataDir,
        "/data/data/$pkg",
        "/data/user/0/$pkg"
    ).map { it.trimEnd('/') }.distinct()

    if (appPrivateDirs.any { isSameOrSubPath(trimmedPath, it) }) return false

    return true
}

fun Context.getAllVolumeNames(): List<String> {
    val volumeNames = mutableListOf(PRIMARY_VOLUME_NAME)
    val storageManager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
    getExternalFilesDirs(null)
        .mapNotNull { storageManager.getStorageVolume(it) }
        .filterNot { it.isPrimary }
        .mapNotNull { it.uuid?.lowercase(Locale.US) }
        .forEach {
            volumeNames.add(it)
        }
    return volumeNames
}
