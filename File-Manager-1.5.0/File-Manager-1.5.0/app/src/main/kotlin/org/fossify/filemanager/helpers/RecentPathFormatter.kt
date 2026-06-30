package org.fossify.filemanager.helpers

import android.content.Context
import com.termux.bridge.FileOpenRequest
import com.termux.bridge.RecentFileEntry
import com.termux.sessionsync.SessionFileCoordinator
import org.fossify.commons.extensions.internalStoragePath

object RecentPathFormatter {
    private const val MAX_REMOTE_SEGMENTS = 4
    private const val MAX_LOCAL_SEGMENTS = 4
    private const val MAX_REMOTE_CHARS_BEFORE_COMPACT = 140
    private const val MAX_LOCAL_CHARS_BEFORE_COMPACT = 120

    fun displayPath(
        context: Context,
        entry: RecentFileEntry,
        sessionFileCoordinator: SessionFileCoordinator = SessionFileCoordinator.getInstance()
    ): String {
        val remoteOriginPath = entry.remoteOriginPath()
        if (remoteOriginPath != null) {
            formatRemoteOriginPath(context, remoteOriginPath, sessionFileCoordinator)?.let { return it }
            normalizeDisplayRemotePath(entry.originDisplayPath)?.let { return it }
        }

        return displayPath(context, remoteOriginPath ?: entry.path, sessionFileCoordinator)
    }

    fun displayPath(
        context: Context,
        rawPath: String?,
        sessionFileCoordinator: SessionFileCoordinator = SessionFileCoordinator.getInstance()
    ): String {
        val normalizedPath = TermuxPathScope.normalizePath(rawPath)
        formatRemoteOriginPath(context, normalizedPath, sessionFileCoordinator)?.let { return it }
        normalizeDisplayRemotePath(sessionFileCoordinator.getDisplayPath(context, normalizedPath))?.let { return it }
        return compactLocalPath(context, normalizedPath)
    }

    private fun RecentFileEntry.remoteOriginPath(): String? {
        val normalizedOriginPath = originPath?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return if (originType == FileOpenRequest.ORIGIN_SFTP_VIRTUAL) normalizedOriginPath else null
    }

    private fun formatRemoteOriginPath(
        context: Context,
        path: String,
        sessionFileCoordinator: SessionFileCoordinator
    ): String? {
        val info = sessionFileCoordinator.describeVirtualPath(context, path)
        if (!info.success) return null

        val authority = info.authorityLabel
            .takeIf { it.isNotBlank() }
            ?: info.displayName.takeIf { it.isNotBlank() }
            ?: "server"
        return compactRemotePath(sanitizeAuthority(authority), normalizeRemotePath(info.remotePath))
    }

    private fun normalizeDisplayRemotePath(displayPath: String?): String? {
        val raw = displayPath?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        if (!raw.startsWith("sftp://", ignoreCase = true)) return null

        val withoutScheme = raw.substringAfter("://").trim()
        val split = withoutScheme.indexOf('/')
        val authority = if (split >= 0) withoutScheme.substring(0, split) else withoutScheme
        val remotePath = if (split >= 0) withoutScheme.substring(split) else "/"
        return compactRemotePath(sanitizeAuthority(authority), normalizeRemotePath(remotePath))
    }

    private fun compactRemotePath(authority: String, remotePath: String): String {
        val normalizedAuthority = authority.ifBlank { "server" }
        val normalizedPath = normalizeRemotePath(remotePath)
        val fullPath = "$normalizedAuthority:$normalizedPath"
        if (fullPath.length <= MAX_REMOTE_CHARS_BEFORE_COMPACT) {
            return fullPath
        }

        return "$normalizedAuthority:${compactPathSegments(normalizedPath, MAX_REMOTE_SEGMENTS)}"
    }

    private fun compactLocalPath(context: Context, path: String): String {
        val normalized = TermuxPathScope.normalizePath(path)
        val home = TermuxPathScope.termuxHomePath(context)
        val root = TermuxPathScope.termuxRootPath(context)
        val prefix = "$root/usr"
        val internalRoot = TermuxPathScope.normalizePath(context.internalStoragePath)

        val labeled = when {
            normalized == home -> "~"
            normalized.startsWith("$home/") -> "~/${normalized.removePrefix("$home/")}"
            normalized == prefix -> "\$PREFIX"
            normalized.startsWith("$prefix/") -> "\$PREFIX/${normalized.removePrefix("$prefix/")}"
            normalized == root -> "Termux"
            normalized.startsWith("$root/") -> "Termux/${normalized.removePrefix("$root/")}"
            internalRoot.isNotBlank() && normalized == internalRoot -> "Internal"
            internalRoot.isNotBlank() && normalized.startsWith("$internalRoot/") ->
                "Internal/${normalized.removePrefix("$internalRoot/")}"
            else -> normalized
        }

        if (labeled == "~" || labeled == "\$PREFIX" || labeled == "Termux" || labeled == "Internal") {
            return labeled
        }

        if (labeled.length <= MAX_LOCAL_CHARS_BEFORE_COMPACT) {
            return labeled
        }

        val firstSlash = labeled.indexOf('/')
        if (firstSlash <= 0) {
            return compactPathSegments(labeled, MAX_LOCAL_SEGMENTS)
        }

        val rootLabel = labeled.substring(0, firstSlash)
        val rest = labeled.substring(firstSlash + 1)
        val segments = rest.split('/').filter { it.isNotBlank() }
        if (segments.size <= MAX_LOCAL_SEGMENTS) {
            return labeled
        }

        val tail = segments.takeLast(MAX_LOCAL_SEGMENTS - 1).joinToString("/")
        return "$rootLabel/.../$tail"
    }

    private fun compactPathSegments(path: String, maxSegments: Int): String {
        val normalized = path.trim().replace('\\', '/').ifBlank { "/" }
        val absolute = normalized.startsWith('/')
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.size <= maxSegments) return normalized

        val tail = segments.takeLast(maxSegments).joinToString("/")
        return if (absolute) "/.../$tail" else ".../$tail"
    }

    private fun normalizeRemotePath(path: String?): String {
        val normalized = path.orEmpty().trim().replace('\\', '/')
        val parts = ArrayDeque<String>()
        normalized.split('/').forEach { token ->
            when {
                token.isBlank() || token == "." -> Unit
                token == ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(token)
            }
        }
        if (parts.isEmpty()) return "/"
        return parts.joinToString(prefix = "/", separator = "/")
    }

    private fun sanitizeAuthority(rawAuthority: String): String {
        var value = rawAuthority.trim()
        while (value.startsWith("ssh ")) value = value.removePrefix("ssh ").trim()
        if (value.startsWith("sftp://", ignoreCase = true)) {
            value = value.substringAfter("://").substringBefore('/').trim()
        }
        return value.trimEnd('/').ifBlank { "server" }
    }
}
