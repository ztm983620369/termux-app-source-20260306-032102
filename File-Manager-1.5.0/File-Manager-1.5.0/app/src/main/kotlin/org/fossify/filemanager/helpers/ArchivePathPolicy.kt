package org.fossify.filemanager.helpers

import java.util.Locale

internal class ArchivePolicyException(message: String) : Exception(message)

internal enum class ArchiveEntryKind {
    FILE,
    DIRECTORY,
    SYMBOLIC_LINK,
    HARD_LINK,
    SPECIAL
}

internal data class ArchiveEntryMetadata(
    val rawPath: String,
    val kind: ArchiveEntryKind,
    val size: Long = 0L,
    val linkTarget: String = ""
)

internal data class ArchivePolicyReport(
    val entryCount: Int,
    val expandedBytes: Long,
    val symbolicLinkCount: Int,
    val trustedExternalLinks: List<String>,
    val duplicateCount: Int
)

/**
 * Validates archive names before extraction. External links are limited to explicitly trusted,
 * non-user-data roots such as the Termux prefix; links into HOME remain rejected.
 */
internal object ArchivePathPolicy {
    private const val MAX_ENTRY_COUNT = 500_000
    private const val MAX_ENTRY_BYTES = 64L * 1024L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 256L * 1024L * 1024L * 1024L
    private const val MAX_REPORTED_EXTERNAL_LINKS = 32

    fun validate(
        entries: List<ArchiveEntryMetadata>,
        trustedAbsolutePrefixes: List<String>
    ): ArchivePolicyReport {
        if (entries.size > MAX_ENTRY_COUNT) {
            throw ArchivePolicyException("压缩包条目过多：${entries.size}，上限为 $MAX_ENTRY_COUNT。")
        }

        val normalizedTrustedRoots = trustedAbsolutePrefixes
            .map(::normalizeAbsolutePath)
            .distinct()
        val normalizedEntries = ArrayList<Pair<String, ArchiveEntryMetadata>>(entries.size)
        val symbolicLinkPaths = linkedSetOf<String>()
        val trustedExternalLinks = arrayListOf<String>()
        val seen = linkedMapOf<String, ArchiveEntryKind>()
        var duplicateCount = 0
        var expandedBytes = 0L
        var symbolicLinkCount = 0

        entries.forEach { entry ->
            val path = normalizeEntryPath(entry.rawPath)
            if (path.isEmpty()) return@forEach
            if (entry.kind == ArchiveEntryKind.SPECIAL) {
                throw ArchivePolicyException("压缩包包含不支持的特殊文件：$path")
            }
            if (entry.size < 0L || entry.size > MAX_ENTRY_BYTES) {
                throw ArchivePolicyException("压缩包条目大小异常：$path")
            }
            expandedBytes = checkedAdd(expandedBytes, entry.size, path)

            val previousKind = seen.put(path, entry.kind)
            if (previousKind != null) {
                duplicateCount++
                if (previousKind != entry.kind) {
                    throw ArchivePolicyException("压缩包包含类型冲突的重复路径：$path")
                }
            }

            when (entry.kind) {
                ArchiveEntryKind.SYMBOLIC_LINK -> {
                    symbolicLinkCount++
                    symbolicLinkPaths.add(path)
                    val target = entry.linkTarget.trim()
                    if (target.isEmpty() || target.indexOf('\u0000') >= 0) {
                        throw ArchivePolicyException("符号链接目标无效：$path")
                    }
                    val externalTarget = resolveSymbolicLink(path, target)
                    if (externalTarget.absolute) {
                        if (!isUnderTrustedRoot(externalTarget.path, normalizedTrustedRoots)) {
                            throw ArchivePolicyException("符号链接指向未受信任位置：$path -> $target")
                        }
                        if (trustedExternalLinks.size < MAX_REPORTED_EXTERNAL_LINKS) {
                            trustedExternalLinks.add("$path -> ${externalTarget.path}")
                        }
                    } else if (externalTarget.escapedArchiveRoot) {
                        throw ArchivePolicyException("符号链接越过解压根目录：$path -> $target")
                    }
                }

                ArchiveEntryKind.HARD_LINK -> {
                    val target = normalizeEntryPath(entry.linkTarget)
                    if (target.isEmpty()) {
                        throw ArchivePolicyException("硬链接目标无效：$path")
                    }
                }

                else -> Unit
            }
            normalizedEntries.add(path to entry)
        }

        if (expandedBytes > MAX_EXPANDED_BYTES) {
            throw ArchivePolicyException("压缩包展开体积过大：$expandedBytes 字节。")
        }

        normalizedEntries.forEach { (path, _) ->
            parentPaths(path).firstOrNull(symbolicLinkPaths::contains)?.let { linkParent ->
                throw ArchivePolicyException("压缩包尝试通过符号链接写入子路径：$linkParent -> $path")
            }
        }

        return ArchivePolicyReport(
            entryCount = normalizedEntries.size,
            expandedBytes = expandedBytes,
            symbolicLinkCount = symbolicLinkCount,
            trustedExternalLinks = trustedExternalLinks,
            duplicateCount = duplicateCount
        )
    }

    internal fun normalizeEntryPath(raw: String): String {
        val value = raw.replace('\\', '/').trim()
        if (value.isEmpty()) return ""
        if (value.indexOf('\u0000') >= 0 || value.startsWith('/')) {
            throw ArchivePolicyException("压缩包包含绝对或无效路径：$raw")
        }

        val parts = ArrayDeque<String>()
        value.split('/').forEach { part ->
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> {
                    if (parts.isEmpty()) {
                        throw ArchivePolicyException("压缩包路径越过解压根目录：$raw")
                    }
                    parts.removeLast()
                }
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    internal fun isTrustedAbsoluteLink(target: String, trustedAbsolutePrefixes: List<String>): Boolean {
        if (!target.startsWith('/')) return false
        val normalizedTarget = normalizeAbsolutePath(target)
        return isUnderTrustedRoot(
            normalizedTarget,
            trustedAbsolutePrefixes.map(::normalizeAbsolutePath)
        )
    }

    private data class ResolvedLink(
        val path: String,
        val absolute: Boolean,
        val escapedArchiveRoot: Boolean
    )

    private fun resolveSymbolicLink(entryPath: String, target: String): ResolvedLink {
        if (target.startsWith('/')) {
            return ResolvedLink(normalizeAbsolutePath(target), absolute = true, escapedArchiveRoot = false)
        }

        val parts = ArrayDeque<String>()
        entryPath.substringBeforeLast('/', "")
            .split('/')
            .filter(String::isNotEmpty)
            .forEach(parts::addLast)
        var escaped = false
        target.replace('\\', '/').split('/').forEach { part ->
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> {
                    if (parts.isEmpty()) escaped = true else parts.removeLast()
                }
                else -> parts.addLast(part)
            }
        }
        return ResolvedLink(parts.joinToString("/"), absolute = false, escapedArchiveRoot = escaped)
    }

    private fun normalizeAbsolutePath(raw: String): String {
        val value = raw.replace('\\', '/').trim()
        if (!value.startsWith('/') || value.indexOf('\u0000') >= 0) {
            throw ArchivePolicyException("受信任根路径无效：$raw")
        }
        val parts = ArrayDeque<String>()
        value.split('/').forEach { part ->
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun isUnderTrustedRoot(path: String, roots: List<String>): Boolean {
        val lowerPath = path.lowercase(Locale.ROOT)
        return roots.any { root ->
            val lowerRoot = root.trimEnd('/').lowercase(Locale.ROOT)
            lowerPath == lowerRoot || lowerPath.startsWith("$lowerRoot/")
        }
    }

    private fun parentPaths(path: String): Sequence<String> = sequence {
        var index = path.indexOf('/')
        while (index > 0) {
            yield(path.substring(0, index))
            index = path.indexOf('/', index + 1)
        }
    }

    private fun checkedAdd(current: Long, value: Long, path: String): Long {
        if (value > Long.MAX_VALUE - current) {
            throw ArchivePolicyException("压缩包展开体积溢出：$path")
        }
        return current + value
    }
}
