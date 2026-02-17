package com.termux.ui.files

import android.content.Context
import org.json.JSONObject
import java.util.Collections
import java.util.Locale

internal data class IconResolution(
    val assetName: String?,
    val strategy: String,
    val iconId: String?,
    val attempted: List<String>
)

internal class VsCodeFileIconTheme(
    private val iconDefinitions: Map<String, IconDefinition>,
    private val dark: ThemeSection,
    private val light: ThemeSection,
    private val assetNameByLowercase: Map<String, String>
) {
    internal val iconDefinitionCount: Int = iconDefinitions.size
    internal val assetCount: Int = assetNameByLowercase.size

    fun resolveAssetName(entry: FileEntry, preferLight: Boolean): String? =
        resolve(entry.name, entry.isDirectory, preferLight).assetName

    fun resolveAssetNameFast(name: String, isDirectory: Boolean, preferLight: Boolean): String? =
        resolveAssetNameFast(name, isDirectory, preferLight, isExpandedFolder = false, isRootFolder = false)

    fun resolveAssetNameFast(
        name: String,
        isDirectory: Boolean,
        preferLight: Boolean,
        isExpandedFolder: Boolean,
        isRootFolder: Boolean
    ): String? {
        val section = if (preferLight) light else dark
        val nameLower = name.lowercase(Locale.ROOT)
        return if (isDirectory) {
            resolveFolderAssetNameFast(section, nameLower, isExpandedFolder = isExpandedFolder, isRootFolder = isRootFolder)
        } else {
            resolveFileAssetNameFast(section, nameLower)
        }
    }

    fun resolve(name: String, isDirectory: Boolean, preferLight: Boolean): IconResolution =
        resolve(name, isDirectory, preferLight, isExpandedFolder = false, isRootFolder = false)

    fun resolve(
        name: String,
        isDirectory: Boolean,
        preferLight: Boolean,
        isExpandedFolder: Boolean,
        isRootFolder: Boolean
    ): IconResolution {
        val section = if (preferLight) light else dark
        val nameLower = name.lowercase(Locale.ROOT)
        return if (isDirectory) {
            resolveFolderAssetName(section, nameLower, isExpandedFolder = isExpandedFolder, isRootFolder = isRootFolder)
        } else {
            resolveFileAssetName(section, nameLower)
        }
    }

    private fun resolveFolderAssetNameFast(
        section: ThemeSection,
        nameLower: String,
        isExpandedFolder: Boolean,
        isRootFolder: Boolean
    ): String? {
        val iconId = if (isRootFolder) {
            if (isExpandedFolder) {
                section.rootFolderNamesExpanded[nameLower]
                    ?: section.rootFolderExpanded
                    ?: section.rootFolder
                    ?: section.folderExpanded
                    ?: section.folder
            } else {
                section.rootFolderNames[nameLower]
                    ?: section.rootFolder
                    ?: section.folder
            }
        } else {
            if (isExpandedFolder) {
                section.folderNamesExpanded[nameLower]
                    ?: section.folderExpanded
                    ?: section.folder
            } else {
                section.folderNames[nameLower]
                    ?: section.folder
            }
        }
        if (iconId != null) {
            val asset = resolveIconIdToAssetFast(iconId)
            if (asset != null) return asset
        }
        val byAsset = resolveFolderByAssetsFast(nameLower)
        if (byAsset != null) return byAsset
        val fallbackBasename = when {
            isRootFolder && isExpandedFolder -> "folder-root-open"
            isRootFolder -> "folder-root"
            isExpandedFolder -> "folder-open"
            else -> "folder"
        }
        findAssetByBasename(fallbackBasename)?.let { return it }
        return null
    }

    private fun resolveFileAssetNameFast(section: ThemeSection, nameLower: String): String? {
        section.fileNames[nameLower]?.let { iconId ->
            val asset = resolveIconIdToAssetFast(iconId)
            if (asset != null) return asset
        }

        section.fileExtensions[nameLower]?.let { iconId ->
            val asset = resolveIconIdToAssetFast(iconId)
            if (asset != null) return asset
        }

        val base = nameLower.removePrefix(".")
        val firstDot = base.indexOf('.')
        if (firstDot >= 0 && firstDot < base.lastIndex) {
            var dot = firstDot
            while (dot >= 0 && dot < base.lastIndex) {
                val ext = base.substring(dot + 1)
                val iconId = section.fileExtensions[ext]
                if (iconId != null) {
                    val asset = resolveIconIdToAssetFast(iconId)
                    if (asset != null) return asset
                }
                val langAsset = resolveLanguageIdToAssetFast(section, ext)
                if (langAsset != null) return langAsset
                dot = base.indexOf('.', dot + 1)
            }
        } else {
            val iconId = section.fileExtensions[base]
            if (iconId != null) {
                val asset = resolveIconIdToAssetFast(iconId)
                if (asset != null) return asset
            }
            val langAsset = resolveLanguageIdToAssetFast(section, base)
            if (langAsset != null) return langAsset
        }

        section.file?.let { iconId ->
            val asset = resolveIconIdToAssetFast(iconId)
            if (asset != null) return asset
        }

        val byAsset = resolveFileByAssetsFast(nameLower)
        if (byAsset != null) return byAsset
        findAssetByBasename("file")?.let { return it }
        return null
    }

    private fun resolveLanguageIdToAssetFast(section: ThemeSection, ext: String): String? {
        fun tryKey(key: String): String? {
            val iconId = section.languageIds[key] ?: return null
            return resolveIconIdToAssetFast(iconId)
        }
        val e = ext.lowercase(Locale.ROOT)
        tryKey(e)?.let { return it }
        when (e) {
            "ts" -> tryKey("typescript")?.let { return it }
            "js" -> tryKey("javascript")?.let { return it }
            "yml", "yaml" -> tryKey("yaml")?.let { return it }
            "htm", "html" -> tryKey("html")?.let { return it }
            "php" -> tryKey("php")?.let { return it }
        }
        return null
    }

    private fun resolveIconIdToAssetFast(iconId: String): String? {
        val raw = iconId.trim().removePrefix("_").lowercase(Locale.ROOT)
        val seen = HashSet<String>(24)

        fun tryCandidate(candidate: String): String? {
            val c = candidate.trim()
            if (c.isBlank()) return null
            if (!seen.add(c)) return null
            val def = iconDefinitions[c]
            if (def != null) {
                val iconPath = def.iconPath
                if (iconPath != null) {
                    val normalized = iconPath.replace('\\', '/')
                    val basename = normalized.substringAfterLast('/')
                    val asset = assetNameByLowercase[basename.lowercase(Locale.ROOT)]
                    if (asset != null) return asset
                }
            }
            return findAssetByBasename(c)
        }

        fun tryVariants(s: String): String? {
            tryCandidate(s)?.let { return it }
            tryCandidate("_$s")?.let { return it }
            val dashed = s.replace('_', '-')
            if (dashed != s) {
                tryCandidate(dashed)?.let { return it }
                tryCandidate("_$dashed")?.let { return it }
            }
            return null
        }

        fun tryWithSuffixStripping(s0: String): String? {
            var s = s0
            tryVariants(s)?.let { return it }
            while (true) {
                val idx = s.lastIndexOf('_')
                if (idx <= 0) break
                val tail = s.substring(idx + 1)
                if (tail.toIntOrNull() == null) break
                s = s.substring(0, idx)
                tryVariants(s)?.let { return it }
            }
            return null
        }

        tryWithSuffixStripping(raw)?.let { return it }
        if (raw.endsWith("_light")) {
            tryWithSuffixStripping(raw.removeSuffix("_light"))?.let { return it }
        }
        if (raw == "yml") {
            tryWithSuffixStripping("yaml")?.let { return it }
        }
        return null
    }

    private fun resolveFolderByAssetsFast(nameLower: String): String? {
        findAssetByBasename("folder-$nameLower")?.let { return it }
        findAssetByBasename("folder-" + nameLower.replace('.', '-'))?.let { return it }
        findAssetByBasename("folder-" + nameLower.replace('.', '_'))?.let { return it }
        val tokens = nameLower.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        for (t in tokens) {
            findAssetByBasename("folder-$t")?.let { return it }
        }
        return null
    }

    private fun resolveFileByAssetsFast(nameLower: String): String? {
        val base = nameLower.removePrefix(".")
        findAssetByBasename(base)?.let { return it }
        findAssetByBasename(base.replace('.', '-'))?.let { return it }
        findAssetByBasename(base.replace('.', '_'))?.let { return it }

        val extBase = nameLower.removePrefix(".")
        val firstDot = extBase.indexOf('.')
        fun tryExt(ext: String): String? {
            findAssetByBasename(ext)?.let { return it }
            for (alias in aliasExtensionsForAssets(ext)) {
                findAssetByBasename(alias)?.let { return it }
            }
            if (ext.contains('.')) {
                findAssetByBasename(ext.replace('.', '-'))?.let { return it }
                findAssetByBasename(ext.replace('.', '_'))?.let { return it }
            }
            return null
        }
        if (firstDot >= 0 && firstDot < extBase.lastIndex) {
            var dot = firstDot
            while (dot >= 0 && dot < extBase.lastIndex) {
                val ext = extBase.substring(dot + 1)
                tryExt(ext)?.let { return it }
                dot = extBase.indexOf('.', dot + 1)
            }
        } else {
            tryExt(extBase)?.let { return it }
        }
        return null
    }

    private fun resolveFolderAssetName(
        section: ThemeSection,
        nameLower: String,
        isExpandedFolder: Boolean,
        isRootFolder: Boolean
    ): IconResolution {
        val attempted = ArrayList<String>(16)
        val iconId = if (isRootFolder) {
            if (isExpandedFolder) {
                section.rootFolderNamesExpanded[nameLower]
                    ?: section.rootFolderExpanded
                    ?: section.rootFolder
                    ?: section.folderExpanded
                    ?: section.folder
            } else {
                section.rootFolderNames[nameLower]
                    ?: section.rootFolder
                    ?: section.folder
            }
        } else {
            if (isExpandedFolder) {
                section.folderNamesExpanded[nameLower]
                    ?: section.folderExpanded
                    ?: section.folder
            } else {
                section.folderNames[nameLower]
                    ?: section.folder
            }
        }
        if (iconId != null) {
            attempted.add(iconId)
            val asset = resolveIconIdToAsset(iconId, attempted)
            if (asset != null) return IconResolution(asset, "themeFolder", iconId, attempted)
        }
        val byAsset = resolveFolderByAssets(nameLower, attempted)
        if (byAsset != null) return IconResolution(byAsset, "assetsFolder", iconId, attempted)
        val fallbackBasename = when {
            isRootFolder && isExpandedFolder -> "folder-root-open"
            isRootFolder -> "folder-root"
            isExpandedFolder -> "folder-open"
            else -> "folder"
        }
        val fallback = findAssetByBasename(fallbackBasename)
        attempted.add("asset:$fallbackBasename")
        if (fallback != null) return IconResolution(fallback, "assetsFolderFallback", iconId, attempted)
        return IconResolution(null, "folderMissing", iconId, attempted)
    }

    private fun resolveFileAssetName(section: ThemeSection, nameLower: String): IconResolution {
        val attempted = ArrayList<String>(32)
        section.fileNames[nameLower]?.let { iconId ->
            attempted.add(iconId)
            val asset = resolveIconIdToAsset(iconId, attempted)
            if (asset != null) return IconResolution(asset, "themeFileName", iconId, attempted)
        }

        section.fileExtensions[nameLower]?.let { iconId ->
            attempted.add(iconId)
            val asset = resolveIconIdToAsset(iconId, attempted)
            if (asset != null) return IconResolution(asset, "themeFileExtensionFullName", iconId, attempted)
        }

        val extCandidates = buildExtensionCandidates(nameLower)
        for (ext in extCandidates) {
            val iconId = section.fileExtensions[ext] ?: continue
            attempted.add(iconId)
            val asset = resolveIconIdToAsset(iconId, attempted)
            if (asset != null) return IconResolution(asset, "themeFileExtension", iconId, attempted)
        }

        for (key in buildLanguageIdCandidates(extCandidates)) {
            val iconId = section.languageIds[key] ?: continue
            attempted.add(iconId)
            val asset = resolveIconIdToAsset(iconId, attempted)
            if (asset != null) return IconResolution(asset, "themeLanguageId", iconId, attempted)
        }

        section.file?.let { iconId ->
            attempted.add(iconId)
            val asset = resolveIconIdToAsset(iconId, attempted)
            if (asset != null) return IconResolution(asset, "themeDefaultFile", iconId, attempted)
        }

        val byAsset = resolveFileByAssets(nameLower, attempted)
        if (byAsset != null) return IconResolution(byAsset, "assetsFile", section.file, attempted)
        val fallback = findAssetByBasename("file")
        attempted.add("asset:file")
        if (fallback != null) return IconResolution(fallback, "assetsFileFallback", section.file, attempted)

        return IconResolution(null, "fileMissing", section.file, attempted)
    }

    private fun buildExtensionCandidates(nameLower: String): List<String> {
        val base = nameLower.removePrefix(".")
        val dotCount = base.count { it == '.' }
        if (dotCount == 0) return listOf(base)
        val parts = base.split('.')
        return (1..parts.lastIndex)
            .map { i -> parts.subList(i, parts.size).joinToString(".") }
            .distinct()
    }

    private fun resolveIconIdToAsset(iconId: String, attempted: MutableList<String>): String? {
        val candidates = buildIconIdCandidates(iconId)
        for (candidate in candidates) {
            attempted.add("iconId:$candidate")
            val def = iconDefinitions[candidate]
            if (def != null) {
                val iconPath = def.iconPath
                if (iconPath != null) {
                    val normalized = iconPath.replace('\\', '/')
                    val basename = normalized.substringAfterLast('/')
                    val asset = assetNameByLowercase[basename.lowercase(Locale.ROOT)]
                    if (asset != null) return asset
                }
            }

            val byBasename = findAssetByBasename(candidate)
            if (byBasename != null) return byBasename
        }
        return null
    }

    private fun resolveFolderByAssets(nameLower: String, attempted: MutableList<String>): String? {
        val candidates = buildList {
            add("folder-$nameLower")
            add("folder-" + nameLower.replace('.', '-'))
            add("folder-" + nameLower.replace('.', '_'))
        }.distinct()
        for (c in candidates) {
            val asset = findAssetByBasename(c)
            attempted.add("asset:$c")
            if (asset != null) return asset
        }
        val tokens = nameLower.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        for (t in tokens) {
            val c = "folder-$t"
            val asset = findAssetByBasename(c)
            attempted.add("asset:$c")
            if (asset != null) return asset
        }
        return null
    }

    private fun resolveFileByAssets(nameLower: String, attempted: MutableList<String>): String? {
        val base = nameLower.removePrefix(".")
        val byNameCandidates = buildList {
            add(base)
            add(base.replace('.', '-'))
            add(base.replace('.', '_'))
        }.distinct()
        for (c in byNameCandidates) {
            val asset = findAssetByBasename(c)
            attempted.add("asset:$c")
            if (asset != null) return asset
        }

        val extCandidates = buildExtensionCandidates(nameLower)
        for (ext in extCandidates) {
            val candidates = buildList {
                add(ext)
                addAll(aliasExtensionsForAssets(ext))
                if (ext.contains('.')) {
                    add(ext.replace('.', '-'))
                    add(ext.replace('.', '_'))
                }
            }.distinct()
            for (c in candidates) {
                val asset = findAssetByBasename(c)
                attempted.add("asset:$c")
                if (asset != null) return asset
            }
        }
        return null
    }

    private fun aliasExtensionsForAssets(ext: String): List<String> {
        return when (ext.lowercase(Locale.ROOT)) {
            "ts" -> listOf("typescript")
            "js" -> listOf("javascript")
            "yml" -> listOf("yaml")
            "htm" -> listOf("html")
            else -> emptyList()
        }
    }

    private fun buildLanguageIdCandidates(extCandidates: List<String>): List<String> {
        val out = ArrayList<String>(16)
        for (ext in extCandidates) {
            val e = ext.lowercase(Locale.ROOT)
            out.add(e)
            when (e) {
                "ts" -> out.add("typescript")
                "js" -> out.add("javascript")
                "yml", "yaml" -> out.add("yaml")
                "htm", "html" -> out.add("html")
                "php" -> out.add("php")
            }
        }
        return out.distinct()
    }

    private fun findAssetByBasename(basenameNoExt: String): String? {
        val name = basenameNoExt.lowercase(Locale.ROOT).removeSuffix(".svg") + ".svg"
        return assetNameByLowercase[name]
    }

    private fun buildIconIdCandidates(iconId: String): List<String> {
        val raw = iconId.trim().removePrefix("_").lowercase(Locale.ROOT)
        val candidates = ArrayList<String>(24)

        fun addBoth(s: String) {
            if (s.isBlank()) return
            candidates.add(s)
            candidates.add("_$s")
        }

        fun addVariants(s: String) {
            addBoth(s)
            addBoth(s.replace('_', '-'))
        }

        addVariants(raw)

        var t = raw
        while (true) {
            val idx = t.lastIndexOf('_')
            if (idx <= 0) break
            val tail = t.substring(idx + 1)
            if (tail.toIntOrNull() == null) break
            t = t.substring(0, idx)
            addVariants(t)
        }

        if (raw.endsWith("_light")) {
            addVariants(raw.removeSuffix("_light"))
        }

        if (raw == "yml") {
            addVariants("yaml")
        }

        return candidates.distinct()
    }

    internal data class IconDefinition(val iconPath: String?)

    internal data class ThemeSection(
        val file: String?,
        val folder: String?,
        val folderExpanded: String?,
        val rootFolder: String?,
        val rootFolderExpanded: String?,
        val fileExtensions: Map<String, String>,
        val fileNames: Map<String, String>,
        val folderNames: Map<String, String>,
        val folderNamesExpanded: Map<String, String>,
        val rootFolderNames: Map<String, String>,
        val rootFolderNamesExpanded: Map<String, String>,
        val languageIds: Map<String, String>
    ) {
        fun withOverrides(overrides: ThemeSection?): ThemeSection {
            if (overrides == null) return this
            return ThemeSection(
                file = overrides.file ?: file,
                folder = overrides.folder ?: folder,
                folderExpanded = overrides.folderExpanded ?: folderExpanded,
                rootFolder = overrides.rootFolder ?: rootFolder,
                rootFolderExpanded = overrides.rootFolderExpanded ?: rootFolderExpanded,
                fileExtensions = fileExtensions + overrides.fileExtensions,
                fileNames = fileNames + overrides.fileNames,
                folderNames = folderNames + overrides.folderNames,
                folderNamesExpanded = folderNamesExpanded + overrides.folderNamesExpanded,
                rootFolderNames = rootFolderNames + overrides.rootFolderNames,
                rootFolderNamesExpanded = rootFolderNamesExpanded + overrides.rootFolderNamesExpanded,
                languageIds = languageIds + overrides.languageIds
            )
        }
    }

    companion object {
        @Volatile
        private var cached: VsCodeFileIconTheme? = null

        @Volatile
        private var loadAttempted: Boolean = false

        internal fun load(context: Context): VsCodeFileIconTheme? {
            if (loadAttempted) return cached
            synchronized(this) {
                if (loadAttempted) return cached
                cached = loadUncached(context)
                loadAttempted = true
                return cached
            }
        }

        private fun loadUncached(context: Context): VsCodeFileIconTheme? {
            val assets = context.assets
            val assetNameByLowercase = runCatching {
                (assets.list("") ?: emptyArray()).associateBy { it.lowercase(Locale.ROOT) }
            }.getOrElse { emptyMap() }

            val materialJson = readAssetJsonOrNull(assets, "material-icons.json")
            val setiJson = readAssetJsonOrNull(assets, "vs-seti-icon-theme.json")
            if (materialJson == null && setiJson == null) return null

            val iconDefinitions = LinkedHashMap<String, IconDefinition>()
            if (setiJson != null) iconDefinitions.putAll(parseIconDefinitions(setiJson.optJSONObject("iconDefinitions")))
            if (materialJson != null) iconDefinitions.putAll(parseIconDefinitions(materialJson.optJSONObject("iconDefinitions")))

            val setiDark = parseThemeSection(setiJson)
            val materialDark = parseThemeSection(materialJson)
            val darkSection = setiDark.withOverrides(materialDark)

            val setiLight = setiDark.withOverrides(parseThemeSection(setiJson?.optJSONObject("light")))
            val materialLight = materialDark.withOverrides(parseThemeSection(materialJson?.optJSONObject("light")))
            val lightSection = setiLight.withOverrides(materialLight)

            return VsCodeFileIconTheme(
                iconDefinitions = Collections.unmodifiableMap(iconDefinitions),
                dark = darkSection,
                light = lightSection,
                assetNameByLowercase = Collections.unmodifiableMap(assetNameByLowercase)
            )
        }

        private fun readAssetJsonOrNull(assets: android.content.res.AssetManager, name: String): JSONObject? {
            val text = runCatching { assets.open(name).bufferedReader().use { it.readText() } }.getOrNull() ?: return null
            return runCatching { JSONObject(text) }.getOrNull()
        }

        private fun parseIconDefinitions(obj: JSONObject?): Map<String, IconDefinition> {
            if (obj == null) return emptyMap()
            return obj.keys().asSequence()
                .mapNotNull { key ->
                    val def = obj.optJSONObject(key)
                    val iconPath = def?.optString("iconPath")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    key to IconDefinition(iconPath = iconPath)
                }
                .toMap()
        }

        private fun parseThemeSection(obj: JSONObject?): ThemeSection {
            if (obj == null) {
                return ThemeSection(
                    file = null,
                    folder = null,
                    folderExpanded = null,
                    rootFolder = null,
                    rootFolderExpanded = null,
                    fileExtensions = emptyMap(),
                    fileNames = emptyMap(),
                    folderNames = emptyMap(),
                    folderNamesExpanded = emptyMap(),
                    rootFolderNames = emptyMap(),
                    rootFolderNamesExpanded = emptyMap(),
                    languageIds = emptyMap()
                )
            }
            return ThemeSection(
                file = obj.optString("file").takeIf { it.isNotBlank() },
                folder = obj.optString("folder").takeIf { it.isNotBlank() },
                folderExpanded = obj.optString("folderExpanded").takeIf { it.isNotBlank() },
                rootFolder = obj.optString("rootFolder").takeIf { it.isNotBlank() },
                rootFolderExpanded = obj.optString("rootFolderExpanded").takeIf { it.isNotBlank() },
                fileExtensions = parseStringMap(obj.optJSONObject("fileExtensions")),
                fileNames = parseStringMap(obj.optJSONObject("fileNames")),
                folderNames = parseStringMap(obj.optJSONObject("folderNames")),
                folderNamesExpanded = parseStringMap(obj.optJSONObject("folderNamesExpanded")),
                rootFolderNames = parseStringMap(obj.optJSONObject("rootFolderNames")),
                rootFolderNamesExpanded = parseStringMap(obj.optJSONObject("rootFolderNamesExpanded")),
                languageIds = parseStringMap(obj.optJSONObject("languageIds"))
            )
        }

        private fun parseStringMap(obj: JSONObject?): Map<String, String> {
            if (obj == null) return emptyMap()
            return obj.keys().asSequence().associate { key ->
                key.lowercase(Locale.ROOT) to obj.optString(key)
            }
        }
    }
}
