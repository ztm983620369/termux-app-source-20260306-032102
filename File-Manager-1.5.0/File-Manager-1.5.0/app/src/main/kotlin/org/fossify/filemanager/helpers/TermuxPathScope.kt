package org.fossify.filemanager.helpers

import android.content.Context
import org.fossify.commons.extensions.getStorageDirectories
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.filemanager.extensions.config
import org.fossify.filemanager.interfaces.FileManagerEnvironment
import java.io.File
import java.util.LinkedHashSet

object TermuxPathScope {
    private const val TERMUX_HOME_RELATIVE_PATH = "home"
    private const val NAV_ROOT_RELATIVE_PATH = ".termux/.navigator"
    private const val SFTP_VIRTUAL_RELATIVE_ROOT = ".termux/sftp-virtual"
    private const val SFTP_MOUNT_RELATIVE_ROOT = ".termux/sftp-mounts"

    fun termuxRootPath(context: Context): String = normalizePath(context.filesDir.absolutePath)

    fun termuxHomePath(context: Context): String = normalizePath(File(context.filesDir, TERMUX_HOME_RELATIVE_PATH).absolutePath)

    fun navigatorRootPath(context: Context): String = normalizePath("${termuxRootPath(context)}/$NAV_ROOT_RELATIVE_PATH")

    fun preferredLocalRoot(context: Context): String {
        return if (context.config.showTermuxSystemDirs) preferredPhoneRoot(context) else termuxHomePath(context)
    }

    fun isScopedHost(context: Context): Boolean {
        return (context as? FileManagerEnvironment)?.isTermuxScopedFileManager() == true
    }

    fun normalizePath(rawPath: String?): String {
        var path = rawPath.orEmpty().trim().replace('\\', '/')
        while (path.contains("//")) path = path.replace("//", "/")
        if (path.endsWith("/") && path.length > 1) path = path.substring(0, path.length - 1)
        return if (path.isEmpty()) "/" else path
    }

    fun isInTermuxRoot(context: Context, path: String?): Boolean {
        val normalized = normalizePath(path)
        val root = termuxRootPath(context)
        return normalized == root || normalized.startsWith("$root/")
    }

    fun isInTermuxHome(context: Context, path: String?): Boolean {
        val normalized = normalizePath(path)
        val home = termuxHomePath(context)
        return normalized == home || normalized.startsWith("$home/")
    }

    fun isVirtualWorkspacePath(context: Context, path: String?): Boolean {
        val normalized = normalizePath(path)
        val root = termuxRootPath(context)
        val virtualRoot = "$root/$SFTP_VIRTUAL_RELATIVE_ROOT"
        val mountRoot = "$root/$SFTP_MOUNT_RELATIVE_ROOT"
        return normalized == virtualRoot ||
            normalized.startsWith("$virtualRoot/") ||
            normalized == mountRoot ||
            normalized.startsWith("$mountRoot/")
    }

    fun phoneStorageRoots(context: Context): List<String> {
        val roots = LinkedHashSet<String>()
        val internal = normalizePath(context.internalStoragePath)
        if (internal.isNotBlank() && File(internal).exists()) {
            roots.add(internal)
        }

        context.getStorageDirectories().forEach { raw ->
            val normalized = normalizePath(raw)
            if (normalized.isNotBlank() && File(normalized).exists()) {
                roots.add(normalized)
            }
        }

        return roots.toList()
    }

    fun preferredPhoneRoot(context: Context): String {
        return phoneStorageRoots(context).firstOrNull() ?: termuxHomePath(context)
    }

    fun isInPhoneStorage(context: Context, path: String?): Boolean {
        val normalized = normalizePath(path)
        return phoneStorageRoots(context).any { root ->
            normalized == root || normalized.startsWith("$root/")
        }
    }

    fun isVisibleInFileManager(context: Context, path: String?, isScopedHost: Boolean = isScopedHost(context)): Boolean {
        if (!isScopedHost) return true

        val normalized = normalizePath(path)
        if (normalized == navigatorRootPath(context)) return true
        if (isVirtualWorkspacePath(context, normalized)) return true

        return if (context.config.showTermuxSystemDirs) {
            isInTermuxRoot(context, normalized) || isInPhoneStorage(context, normalized)
        } else {
            isInTermuxHome(context, normalized)
        }
    }

    fun clampVisiblePath(
        context: Context,
        path: String?,
        fallback: String? = null,
        isScopedHost: Boolean = isScopedHost(context)
    ): String {
        val normalized = normalizePath(path)
        if (!isScopedHost) return normalized

        val resolvedFallback = normalizePath(fallback ?: preferredLocalRoot(context))
        return if (isVisibleInFileManager(context, normalized, isScopedHost)) normalized else resolvedFallback
    }

    fun isSystemPath(context: Context, path: String?): Boolean {
        if (!isScopedHost(context)) return false
        val normalized = normalizePath(path)
        return (isInPhoneStorage(context, normalized) || isInTermuxRoot(context, normalized)) &&
            !isInTermuxHome(context, normalized) &&
            !isVirtualWorkspacePath(context, normalized) &&
            normalized != navigatorRootPath(context)
    }
}
