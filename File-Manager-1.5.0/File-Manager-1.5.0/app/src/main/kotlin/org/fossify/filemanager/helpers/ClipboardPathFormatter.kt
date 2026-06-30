package org.fossify.filemanager.helpers

object ClipboardPathFormatter {
    fun localPath(path: String?): String {
        return normalizePath(path, forceAbsolute = false)
    }

    fun remoteLinuxPath(path: String?): String {
        return normalizePath(stripRemoteAuthority(path), forceAbsolute = true)
    }

    private fun stripRemoteAuthority(path: String?): String {
        val normalized = path?.trim().orEmpty().replace('\\', '/')
        if (normalized.isEmpty()) return ""

        if (normalized.startsWith("sftp://", ignoreCase = true)) {
            val withoutScheme = normalized.substringAfter("://")
            val firstSlash = withoutScheme.indexOf('/')
            return if (firstSlash >= 0) withoutScheme.substring(firstSlash) else "/"
        }

        val colonIndex = normalized.indexOf(':')
        if (colonIndex > 0 &&
            !normalized.startsWith("/") &&
            colonIndex + 1 < normalized.length &&
            normalized[colonIndex + 1] == '/'
        ) {
            return normalized.substring(colonIndex + 1)
        }

        return normalized
    }

    private fun normalizePath(path: String?, forceAbsolute: Boolean): String {
        val normalized = path?.trim().orEmpty().replace('\\', '/')
        if (normalized.isEmpty()) return if (forceAbsolute) "/" else ""

        val absolute = forceAbsolute || normalized.startsWith("/")
        val parts = ArrayList<String>()
        normalized.split('/').forEach { part ->
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> {
                    if (parts.isNotEmpty() && parts.last() != "..") {
                        parts.removeAt(parts.lastIndex)
                    } else if (!absolute) {
                        parts.add(part)
                    }
                }
                else -> parts.add(part)
            }
        }

        val body = parts.joinToString("/")
        return when {
            body.isEmpty() && absolute -> "/"
            body.isEmpty() -> ""
            absolute -> "/$body"
            else -> body
        }
    }
}
