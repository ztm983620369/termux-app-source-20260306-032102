/*******************************************************************************
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2024  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 ******************************************************************************/
package io.github.rosemoe.sora.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.rosemoe.sora.app.databinding.ActivityMainBinding
import io.github.rosemoe.sora.langs.java.JavaLanguage as SoraJavaLanguage
import io.github.rosemoe.sora.langs.monarch.MonarchLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.GrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.monarch.registry.model.ThemeSource
import io.github.rosemoe.sora.utils.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.internal.oniguruma.Oniguruma
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration

/**
 * VS Code 相关能力（资产扫描 + 语法/主题/字体 + 自动按后缀匹配）
 *
 * 目标：把“VSCode 资产处理逻辑”从 MainActivity 抽离，便于维护、便于大模型定位修改。
 */
internal class VSCodeIntegration(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val prefs: android.content.SharedPreferences,
    private val env: EditorEnvironment,
    private val currentFilePathProvider: () -> String?,
    private val onAutoHighlightFailure: (String) -> Unit
) {

    companion object {
        private const val KEY_VSCODE_AUTO_HIGHLIGHT_ENABLED = "vscode.autoHighlight.enabled"
        private const val EXT_BUILTIN_TEXTMATE = "内置(textmate)"
        private const val EXT_BUILTIN_APP = "内置(app)"
        private const val EXT_SYSTEM = "系统"
    }

    // ---- State & cache ----
    private var vsCodeLanguageCache: List<VSCodeLanguageInfo>? = null
    private val vsCodePreloadedExtensionRoots = HashSet<String>()
    var allExtensionsPreloaded: Boolean = false
        private set
    private var vsCodeThemeCache: List<VSCodeThemeInfo>? = null
    private var vsCodeTypefaceCache: List<VSCodeTypefaceInfo>? = null

    var lastAutoHighlight: VSCodeAutoHighlight? = null
        private set

    val languageCacheSize: Int get() = vsCodeLanguageCache?.size ?: 0
    val preloadedRootsSize: Int get() = vsCodePreloadedExtensionRoots.size

    fun isVSCodeAutoHighlightEnabled(): Boolean {
        return prefs.getBoolean(KEY_VSCODE_AUTO_HIGHLIGHT_ENABLED, false)
    }

    private fun setVSCodeAutoHighlightEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VSCODE_AUTO_HIGHLIGHT_ENABLED, enabled).apply()
    }

    // ---- Public entry points called by MainActivity ----

    fun chooseVSCodeSyntaxHighlight() {
        val cached = vsCodeLanguageCache
        if (cached != null) {
            if (!allExtensionsPreloaded) {
                activity.toast("正在预加载语法文件（首次较慢）…")
                activity.lifecycleScope.launch {
                    val report = runCatching { preloadAllVSCodeExtensionGrammars() }
                        .getOrElse {
                            VSCodePreloadReport(
                                loaded = 0,
                                failed = 1,
                                failures = listOf(it.toString())
                            )
                        }
                    allExtensionsPreloaded = true
                    if (report.failed > 0) {
                        val msg = buildString {
                            appendLine("预加载完成：成功 ${report.loaded}，失败 ${report.failed}")
                            appendLine()
                            report.failures.take(200).forEach { line ->
                                appendLine(line)
                            }
                            if (report.failures.size > 200) {
                                appendLine("...（仅展示前 200 条，已省略 ${report.failures.size - 200} 条）")
                            }
                        }.trimEnd()
                        showCopyableDialog("VS Code语法高亮：预加载失败明细", msg)
                    } else {
                        activity.toast("预加载完成：成功 ${report.loaded}")
                    }
                    showVSCodeSyntaxLanguageDialog(cached)
                }
                return
            }
            showVSCodeSyntaxLanguageDialog(cached)
            return
        }

        activity.toast("正在加载 VS Code 语言列表，请稍候…")
        activity.lifecycleScope.launch {
            val languages = runCatching { loadVSCodeLanguagesFromAssets() }
                .getOrElse { emptyList() }
            vsCodeLanguageCache = languages
            if (languages.isEmpty()) {
                activity.toast("未找到可用的 VS Code 语言定义")
                return@launch
            }

            if (!allExtensionsPreloaded) {
                activity.toast("正在预加载语法文件（首次较慢）…")
                val report = runCatching { preloadAllVSCodeExtensionGrammars() }
                    .getOrElse {
                        VSCodePreloadReport(
                            loaded = 0,
                            failed = 1,
                            failures = listOf(it.toString())
                        )
                    }
                allExtensionsPreloaded = true
                if (report.failed > 0) {
                    val msg = buildString {
                        appendLine("预加载完成：成功 ${report.loaded}，失败 ${report.failed}")
                        appendLine()
                        report.failures.take(200).forEach { line ->
                            appendLine(line)
                        }
                        if (report.failures.size > 200) {
                            appendLine("...（仅展示前 200 条，已省略 ${report.failures.size - 200} 条）")
                        }
                    }.trimEnd()
                    showCopyableDialog("VS Code语法高亮：预加载失败明细", msg)
                } else {
                    activity.toast("预加载完成：成功 ${report.loaded}")
                }
            }

            showVSCodeSyntaxLanguageDialog(languages)
        }
    }

    fun chooseVSCodeTheme() {
        val cached = vsCodeThemeCache
        if (cached != null) {
            showVSCodeThemeDialog(cached)
            return
        }
        activity.toast("正在加载 VS Code 主题列表，请稍候…")
        activity.lifecycleScope.launch {
            val themes = runCatching { loadVSCodeThemesFromAssets() }
                .getOrElse { emptyList() }
            vsCodeThemeCache = themes
            if (themes.isEmpty()) {
                activity.toast("未找到可用的 VS Code 主题")
                return@launch
            }
            showVSCodeThemeDialog(themes)
        }
    }

    fun chooseVSCodeTypeface() {
        val cached = vsCodeTypefaceCache
        if (cached != null) {
            showVSCodeTypefaceDialog(cached)
            return
        }
        activity.toast("正在加载 VS Code 字体列表，请稍候…")
        activity.lifecycleScope.launch {
            val typefaces = runCatching { loadVSCodeTypefacesFromAssets() }
                .getOrElse { emptyList() }
            vsCodeTypefaceCache = typefaces
            if (typefaces.isEmpty()) {
                activity.toast("未找到可用的 VS Code 字体")
                return@launch
            }
            val extensionTypefaceCount = typefaces.count {
                it.kind == VSCodeTypefaceKind.ASSET &&
                    it.extensionName != null &&
                    it.extensionName != EXT_BUILTIN_APP &&
                    it.extensionName != EXT_SYSTEM
            }
            if (extensionTypefaceCount == 0) {
                activity.toast("extensions 里未发现字体文件（ttf/otf/ttc），VS Code 默认使用系统字体")
            }
            showVSCodeTypefaceDialog(typefaces)
        }
    }

    /**
     * 打开文件后触发：如果开了自动高亮，就按文件名/后缀匹配 VSCode grammar
     */
    fun maybeAutoApplyVSCodeSyntaxByFileName(path: String) {
        if (!isVSCodeAutoHighlightEnabled()) {
            lastAutoHighlight = null
            return
        }
        autoApplyVSCodeSyntaxByFileName(path)
    }

    // ---- VSCode models ----

    data class VSCodeLanguageInfo(
        val id: String,
        val displayName: String,
        val scopeName: String,
        val grammarPath: String?,
        val languageConfigurationPath: String?,
        val extensionName: String?,
        val extensions: List<String> = emptyList(),
        val filenames: List<String> = emptyList(),
        val embeddedLanguages: Map<String, String> = emptyMap()
    )

    enum class VSCodeAutoMatchKind {
        FILENAME,
        EXTENSION
    }

    data class VSCodeAutoHighlight(
        val attemptAtMs: Long,
        val okAtMs: Long,
        val path: String,
        val fileName: String,
        val matchKind: VSCodeAutoMatchKind?,
        val matchKey: String?,
        val languageId: String?,
        val displayName: String?,
        val scopeName: String?,
        val grammarPath: String?,
        val extensionRoot: String?,
        val warning: String?,
        val error: String?
    )

    data class VSCodePreloadReport(
        val loaded: Int,
        val failed: Int,
        val failures: List<String>
    )

    data class VSCodeThemeInfo(
        val id: String,
        val label: String,
        val uiTheme: String?,
        val path: String,
        val extensionName: String?
    )

    data class VSCodeTypefaceInfo(
        val id: String,
        val label: String,
        val kind: VSCodeTypefaceKind,
        val assetPath: String?,
        val systemFamilyName: String?,
        val extensionName: String?
    )

    enum class VSCodeTypefaceKind {
        ASSET,
        SYSTEM
    }

    // ---- VSCode: language list ----

    private suspend fun loadVSCodeLanguagesFromAssets(): List<VSCodeLanguageInfo> = withContext(Dispatchers.IO) {
        val builtin = loadVSCodeLanguagesFromTextmateIndex()
        val extensions = loadVSCodeLanguagesFromExtensions()
        val all = builtin + extensions

        all.forEach { info ->
            GrammarRegistry.getInstance().registerLanguageId(info.id, info.scopeName)
        }

        all
            .groupBy { it.id.lowercase() }
            .values
            .map { group -> group.maxBy { scoreVSCodeLanguageForDedup(it) } }
            .sortedBy { it.displayName.lowercase() }
    }

    private fun scoreVSCodeLanguageForDedup(info: VSCodeLanguageInfo): Int {
        var score = 0
        if (info.extensionName != null && info.extensionName != EXT_BUILTIN_TEXTMATE) score += 100
        if (info.grammarPath != null) score += 20
        if (info.scopeName.contains("basic", ignoreCase = true)) score += 5
        if (info.scopeName.contains("derivative", ignoreCase = true)) score -= 5
        if (info.extensions.isNotEmpty() || info.filenames.isNotEmpty()) score += 1
        return score
    }

    private fun normalizeRelativePath(path: String): String {
        var p = path.trim()
        while (p.startsWith("./")) p = p.removePrefix("./")
        while (p.startsWith("/")) p = p.removePrefix("/")
        return p
    }

    private fun loadVSCodeLanguagesFromTextmateIndex(): List<VSCodeLanguageInfo> {
        val jsonText = activity.assets.open("textmate/languages.json")
            .bufferedReader()
            .use { it.readText() }
        val root = JSONObject(jsonText)
        val languages = root.optJSONArray("languages") ?: JSONArray()
        val result = ArrayList<VSCodeLanguageInfo>(languages.length())
        for (i in 0 until languages.length()) {
            val obj = languages.optJSONObject(i) ?: continue
            val id = obj.optString("name").trim()
            val scopeName = obj.optString("scopeName").trim()
            val grammarPath = obj.optString("grammar").trim().takeIf { it.isNotEmpty() }
            val languageConfigurationPath = obj.optString("languageConfiguration").trim().takeIf { it.isNotEmpty() }
            if (id.isNotEmpty() && scopeName.isNotEmpty()) {
                result.add(
                    VSCodeLanguageInfo(
                        id = id,
                        displayName = id,
                        scopeName = scopeName,
                        grammarPath = grammarPath,
                        languageConfigurationPath = languageConfigurationPath,
                        extensionName = EXT_BUILTIN_TEXTMATE,
                        extensions = emptyList(),
                        filenames = emptyList()
                    )
                )
            }
        }
        return result
    }

    private fun loadVSCodeLanguagesFromExtensions(): List<VSCodeLanguageInfo> {
        val roots = activity.assets.list("")?.toList().orEmpty()
        val result = ArrayList<VSCodeLanguageInfo>(512)

        for (root in roots) {
            val children = activity.assets.list(root)?.toList().orEmpty()
            if (!children.contains("package.json")) continue

            val packageJsonText = runCatching {
                activity.assets.open("$root/package.json")
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrNull() ?: continue

            val pkg = runCatching { JSONObject(packageJsonText) }.getOrNull() ?: continue
            val contributes = pkg.optJSONObject("contributes") ?: continue

            val languagesArray = contributes.optJSONArray("languages") ?: JSONArray()

            data class LanguageMeta(
                val displayName: String,
                val configurationPath: String?,
                val extensions: List<String>,
                val filenames: List<String>
            )

            val languageMeta = HashMap<String, LanguageMeta>(languagesArray.length())
            for (i in 0 until languagesArray.length()) {
                val obj = languagesArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                if (id.isEmpty()) continue
                val aliases = obj.optJSONArray("aliases")
                val displayName = when {
                    aliases != null && aliases.length() > 0 -> aliases.optString(0).trim().ifEmpty { id }
                    else -> id
                }
                val configPathRaw = obj.optString("configuration").trim()
                val configPath = configPathRaw.takeIf { it.isNotEmpty() }
                    ?.let { "$root/${normalizeRelativePath(it)}" }

                val extensions = runCatching {
                    val arr = obj.optJSONArray("extensions") ?: JSONArray()
                    val list = ArrayList<String>(arr.length())
                    for (k in 0 until arr.length()) {
                        val e = arr.optString(k).trim()
                        if (e.isNotEmpty()) list.add(e.lowercase())
                    }
                    list
                }.getOrElse { emptyList() }

                val filenames = runCatching {
                    val arr = obj.optJSONArray("filenames") ?: JSONArray()
                    val list = ArrayList<String>(arr.length())
                    for (k in 0 until arr.length()) {
                        val n = arr.optString(k).trim()
                        if (n.isNotEmpty()) list.add(n.lowercase())
                    }
                    list
                }.getOrElse { emptyList() }

                languageMeta[id] = LanguageMeta(
                    displayName = displayName,
                    configurationPath = configPath,
                    extensions = extensions,
                    filenames = filenames
                )
            }

            val defaultLanguageId: String? = when (languageMeta.size) {
                1 -> languageMeta.keys.firstOrNull()
                else -> null
            }

            val grammarsArray = contributes.optJSONArray("grammars") ?: JSONArray()
            for (i in 0 until grammarsArray.length()) {
                val obj = grammarsArray.optJSONObject(i) ?: continue
                val scopeName = obj.optString("scopeName").trim()
                val languageId = obj.optString("language").trim().ifEmpty { defaultLanguageId.orEmpty() }
                val grammarPathRaw = obj.optString("path").trim()
                if (scopeName.isEmpty() || grammarPathRaw.isEmpty()) continue

                val grammarPath = "$root/${normalizeRelativePath(grammarPathRaw)}"
                val meta = languageMeta[languageId]
                val displayName = meta?.displayName ?: languageId.ifEmpty { scopeName }
                val languageConfigurationPath = meta?.configurationPath
                val extensions = meta?.extensions.orEmpty()
                val filenames = meta?.filenames.orEmpty()

                val embeddedLanguagesObj = obj.optJSONObject("embeddedLanguages")
                val embeddedLanguages = if (embeddedLanguagesObj != null) {
                    val map = HashMap<String, String>()
                    val keys = embeddedLanguagesObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = embeddedLanguagesObj.optString(key).trim()
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            map[key] = value
                        }
                    }
                    map
                } else {
                    emptyMap()
                }

                result.add(
                    VSCodeLanguageInfo(
                        id = if (languageId.isNotEmpty()) languageId else scopeName,
                        displayName = displayName,
                        scopeName = scopeName,
                        grammarPath = grammarPath,
                        languageConfigurationPath = languageConfigurationPath,
                        extensionName = root,
                        extensions = extensions,
                        filenames = filenames,
                        embeddedLanguages = embeddedLanguages
                    )
                )
            }
        }

        return result
    }

    // ---- VSCode: auto highlight ----

    private fun autoApplyVSCodeSyntaxByFileName(path: String) {
        val fileName = runCatching { File(path).name }.getOrNull() ?: path
        if (fileName.lowercase().endsWith(".java")) {
            lastAutoHighlight = null
            binding.editor.setEditorLanguage(SoraJavaLanguage())
            switchThemeIfRequired(activity, binding.editor)
            return
        }
        val attemptAt = System.currentTimeMillis()
        lastAutoHighlight = VSCodeAutoHighlight(
            attemptAtMs = attemptAt,
            okAtMs = 0L,
            path = path,
            fileName = fileName,
            matchKind = null,
            matchKey = null,
            languageId = null,
            displayName = null,
            scopeName = null,
            grammarPath = null,
            extensionRoot = null,
            warning = null,
            error = null
        )

        activity.lifecycleScope.launch {
            runCatching {
                val languages = vsCodeLanguageCache ?: runCatching { loadVSCodeLanguagesFromAssets() }
                    .getOrElse { emptyList() }
                    .also { vsCodeLanguageCache = it }
                if (languages.isEmpty()) {
                    lastAutoHighlight = lastAutoHighlight?.copy(error = "自动高亮失败：VS Code 语言列表为空")
                    return@runCatching
                }

                val match = findBestVSCodeLanguageForFileName(fileName, languages)
                if (match == null) {
                    lastAutoHighlight = lastAutoHighlight?.copy(error = "自动高亮失败：未找到匹配的后缀/文件名规则")
                    return@runCatching
                }

                applyVSCodeSyntaxHighlight(
                    match.info,
                    VSCodeAutoApplyContext(
                        attemptAtMs = attemptAt,
                        path = path,
                        matchKind = match.matchKind,
                        matchKey = match.matchKey
                    )
                )
            }.onFailure { t ->
                val msg = t.message?.trim().orEmpty()
                val err = if (msg.isEmpty()) t::class.java.name else "${t::class.java.name}: $msg"
                lastAutoHighlight = lastAutoHighlight?.copy(error = "自动高亮异常: $err")
            }
        }
    }

    private data class VSCodeFileMatch(
        val info: VSCodeLanguageInfo,
        val matchKind: VSCodeAutoMatchKind,
        val matchKey: String
    )

    private fun findBestVSCodeLanguageForFileName(
        fileName: String,
        languages: List<VSCodeLanguageInfo>
    ): VSCodeFileMatch? {
        val nameLower = fileName.lowercase()

        val byFileName = languages.firstOrNull { info ->
            info.filenames.any { it == nameLower }
        }
        if (byFileName != null) {
            return VSCodeFileMatch(
                info = byFileName,
                matchKind = VSCodeAutoMatchKind.FILENAME,
                matchKey = nameLower
            )
        }

        val dot = nameLower.indexOf('.')
        if (dot == -1) return null

        val candidates = ArrayList<String>(4)
        var idx = nameLower.indexOf('.', 0)
        while (idx != -1) {
            candidates.add(nameLower.substring(idx))
            idx = nameLower.indexOf('.', idx + 1)
        }
        candidates.sortByDescending { it.length }

        for (ext in candidates) {
            val hits = languages.filter { info ->
                info.extensions.any { it == ext }
            }
            if (hits.isNotEmpty()) {
                val hit = hits.maxBy { scoreVSCodeLanguageForFileMatch(it) }
                return VSCodeFileMatch(
                    info = hit,
                    matchKind = VSCodeAutoMatchKind.EXTENSION,
                    matchKey = ext
                )
            }
        }

        val fallbackExt = candidates.lastOrNull()
        val fallbackScopes = when (fallbackExt) {
            ".py", ".pyw" -> listOf("source.python")
            ".kt", ".kts" -> listOf("source.kotlin")
            ".java" -> listOf("source.java")
            ".js", ".mjs", ".cjs" -> listOf("source.js")
            ".ts" -> listOf("source.ts", "source.typescript")
            ".tsx" -> listOf("source.tsx", "source.typescriptreact")
            ".json" -> listOf("source.json")
            ".xml" -> listOf("text.xml", "text.xml.xsl")
            ".html", ".htm" -> listOf("text.html.basic")
            ".css" -> listOf("source.css")
            ".md", ".markdown" -> listOf("text.html.markdown")
            ".sh", ".bash" -> listOf("source.shell")
            ".yml", ".yaml" -> listOf("source.yaml")
            else -> emptyList()
        }
        if (fallbackScopes.isNotEmpty() && fallbackExt != null) {
            val hit = languages.firstOrNull { it.scopeName in fallbackScopes }
            if (hit != null) {
                return VSCodeFileMatch(
                    info = hit,
                    matchKind = VSCodeAutoMatchKind.EXTENSION,
                    matchKey = fallbackExt
                )
            }
        }

        return null
    }

    private fun scoreVSCodeLanguageForFileMatch(info: VSCodeLanguageInfo): Int {
        var score = 0
        val scope = info.scopeName

        if (scope.endsWith(".basic")) score += 50
        if (scope.contains(".basic")) score += 10
        if (scope.endsWith(".derivative")) score -= 50
        if (scope.contains("derivative")) score -= 10

        if (info.extensionName != null && info.extensionName != EXT_BUILTIN_TEXTMATE) score += 5
        if (info.grammarPath != null) score += 1

        return score
    }

    // ---- VSCode: apply grammar ----

    private fun buildVSCodeGrammarDefinition(info: VSCodeLanguageInfo): GrammarDefinition? {
        val grammarPath = info.grammarPath ?: return null
        val input = FileProviderRegistry.getInstance().tryGetInputStream(grammarPath) ?: return null
        val grammarSource = IGrammarSource.fromInputStream(input, grammarPath, Charsets.UTF_8)
        val def = DefaultGrammarDefinition.withLanguageConfiguration(
            grammarSource,
            info.languageConfigurationPath,
            grammarPath,
            info.scopeName
        )
        return if (info.embeddedLanguages.isEmpty()) def else def.withEmbeddedLanguages(info.embeddedLanguages)
    }

    private fun buildVSCodeSwitchFailureMessage(
        info: VSCodeLanguageInfo,
        throwable: Throwable,
        extra: String? = null
    ): String {
        val grammar = info.grammarPath ?: "无"
        val config = info.languageConfigurationPath ?: "无"
        val ext = info.extensionName ?: "无"
        val errMsg = throwable.message?.trim().orEmpty()
        val err = if (errMsg.isEmpty()) throwable::class.java.name else "${throwable::class.java.name}: $errMsg"
        val hint = if (
            throwable is ClassCastException &&
            errMsg.contains("cannot be cast to org.eclipse.tm4e.core.internal.grammar.raw.IRawRule")
        ) {
            "建议：这通常是 grammar 的 `repository`/`captures` 里混入了 `//` 注释键或字符串值导致，可尝试升级解析器兼容或替换该 grammar 文件"
        } else {
            ""
        }
        return """
            切换失败
            语言：${info.displayName} (${info.id})
            scope：${info.scopeName}
            grammar：$grammar
            languageConfiguration：$config
            扩展：$ext
            ${extra?.trim()?.takeIf { it.isNotEmpty() } ?: ""}
            错误：$err
            ${hint.trim()}
        """.trimIndent()
    }

    private data class VSCodeAutoApplyContext(
        val attemptAtMs: Long,
        val path: String,
        val matchKind: VSCodeAutoMatchKind,
        val matchKey: String
    )

    private fun applyVSCodeSyntaxHighlight(info: VSCodeLanguageInfo, auto: VSCodeAutoApplyContext? = null) {
        val actionLabel = if (auto == null) "正在切换" else "自动高亮"
        activity.toast("$actionLabel：${info.displayName}…")

        activity.lifecycleScope.launch {
            val def = buildVSCodeGrammarDefinition(info)
            runCatching {
                var highlightWarning: String? = null
                withContext(Dispatchers.IO) {
                    val languages = vsCodeLanguageCache.orEmpty()
                    val ext = info.extensionName
                    if (ext != null && ext != EXT_BUILTIN_TEXTMATE) {
                        preloadVSCodeExtensionGrammars(ext)
                    }
                    preloadEmbeddedLanguageDependencies(info, languages)
                    if (def != null) {
                        val grammar = GrammarRegistry.getInstance().loadGrammar(def)
                        if (grammar == null) {
                            throw IllegalStateException("grammar 加载返回 null（scope=${info.scopeName}）")
                        }
                    }

                    val grammar = GrammarRegistry.getInstance().findGrammar(info.scopeName)
                    if (grammar != null) {
                        val timeLimit =
                            if (Oniguruma().isUseNativeOniguruma) Duration.ofSeconds(2) else Duration.ofSeconds(5)
                        val samples = buildTokenizeSamples(info.scopeName, info.id)
                        var anyMultiToken = false
                        var anyStoppedEarly = false
                        var anyNonRootScope = false
                        for (sample in samples) {
                            val snippet = sample.replace("\r\n", "\n")
                            val lines = snippet.split('\n')
                            var state: IStateStack? = null
                            for (line in lines) {
                                val r = grammar.tokenizeLine(line, state, timeLimit)
                                state = r.ruleStack
                                if (r.isStoppedEarly) anyStoppedEarly = true
                                if (r.tokens.size > 1) anyMultiToken = true
                                if (r.tokens.any { t -> t.scopes.any { s -> s != info.scopeName && s != "unknown" } }) {
                                    anyNonRootScope = true
                                }
                                if (anyMultiToken && anyNonRootScope) break
                            }
                            if (anyMultiToken && anyNonRootScope) break
                        }
                        if (!anyMultiToken || !anyNonRootScope) {
                            val autoLine =
                                if (auto == null) "" else "命中：${auto.matchKind}=${auto.matchKey}\n文件：${auto.path}\n"
                            highlightWarning = """
                                切换已完成，但检测到该 grammar 可能未生效（表现为“无高亮”）
                                ${autoLine.trim()}
                                语言：${info.displayName} (${info.id})
                                scope：${info.scopeName}
                                grammar：${info.grammarPath ?: "无"}
                                扩展：${info.extensionName ?: "无"}
                                正则引擎：${if (Oniguruma().isUseNativeOniguruma) "Native Oniguruma" else "Joni"}
                                说明：对示例行进行 tokenize，结果疑似只生成默认 token/根 scope（通常是 grammar 中部分正则语法不兼容导致）
                                stoppedEarly：$anyStoppedEarly
                            """.trimIndent()
                        }
                    }
                }

                env.ensureTextmateTheme()
                val editor = binding.editor
                val editorLanguage = editor.editorLanguage
                val language = if (editorLanguage is TextMateLanguage) {
                    editorLanguage.updateLanguage(info.scopeName)
                    editorLanguage
                } else {
                    TextMateLanguage.create(info.scopeName, true)
                }
                editor.setEditorLanguage(language)
                env.applyUserPreferredTheme()
                highlightWarning
            }.onSuccess { warning ->
                if (auto != null) {
                    lastAutoHighlight = VSCodeAutoHighlight(
                        attemptAtMs = auto.attemptAtMs,
                        okAtMs = System.currentTimeMillis(),
                        path = auto.path,
                        fileName = File(auto.path).name,
                        matchKind = auto.matchKind,
                        matchKey = auto.matchKey,
                        languageId = info.id,
                        displayName = info.displayName,
                        scopeName = info.scopeName,
                        grammarPath = info.grammarPath,
                        extensionRoot = info.extensionName,
                        warning = warning,
                        error = null
                    )
                }
                if (warning.isNullOrEmpty()) {
                    activity.toast("已切换：${info.displayName}（${info.scopeName}）")
                } else {
                    activity.toast("切换成功但疑似无高亮：已弹出可复制信息")
                    showCopyableDialog("VS Code语法高亮：可能未生效", warning)
                }
            }.onFailure { t ->
                val extra = if (auto == null) null else "命中：${auto.matchKind}=${auto.matchKey}\n文件：${auto.path}"
                val msg = buildVSCodeSwitchFailureMessage(info, t, extra)
                if (auto != null) {
                    lastAutoHighlight = VSCodeAutoHighlight(
                        attemptAtMs = auto.attemptAtMs,
                        okAtMs = 0L,
                        path = auto.path,
                        fileName = File(auto.path).name,
                        matchKind = auto.matchKind,
                        matchKey = auto.matchKey,
                        languageId = info.id,
                        displayName = info.displayName,
                        scopeName = info.scopeName,
                        grammarPath = info.grammarPath,
                        extensionRoot = info.extensionName,
                        warning = null,
                        error = msg
                    )
                    onAutoHighlightFailure("语法高亮失败: $t")
                }
                activity.toast("切换失败：已弹出可复制信息")
                showCopyableDialog("VS Code语法高亮：切换失败", msg)
            }
        }
    }

    private suspend fun preloadEmbeddedLanguageDependencies(
        info: VSCodeLanguageInfo,
        languages: List<VSCodeLanguageInfo>
    ) {
        if (info.embeddedLanguages.isEmpty()) return

        val requiredLanguageIds = info.embeddedLanguages.values
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (requiredLanguageIds.isEmpty()) return

        val byId = languages.groupBy { it.id.lowercase() }
        for (langId in requiredLanguageIds) {
            val dep = byId[langId.lowercase()]
                ?.maxByOrNull { scoreVSCodeLanguageForDedup(it) }
                ?: continue
            val depRoot = dep.extensionName
            if (depRoot != null && depRoot != EXT_BUILTIN_TEXTMATE) {
                preloadVSCodeExtensionGrammars(depRoot)
            }
        }
    }

    private fun buildTokenizeSamples(scopeName: String, languageId: String): List<String> {
        return when {
            scopeName.startsWith("text.html") || languageId.equals("html", ignoreCase = true) -> listOf(
                "<div class=\"x\">Hello</div>",
                "<!-- comment -->",
                "<script>const x = 1;</script>"
            )

            scopeName == "text.html.markdown" || languageId.equals("markdown", ignoreCase = true) -> listOf(
                "# Title",
                "- item",
                "`inline`",
                "<div>html</div>"
            )

            scopeName.startsWith("text.xml") || languageId.equals("xml", ignoreCase = true) -> listOf(
                "<root attr=\"v\"/>",
                "<!-- comment -->"
            )

            scopeName.startsWith("source.js") || languageId.equals("javascript", ignoreCase = true) -> listOf(
                "const x = 1",
                "function f(a) { return a + 1 }"
            )

            scopeName.startsWith("source.python") || languageId.equals("python", ignoreCase = true) -> listOf(
                "def f(x): return x + 1",
                "# comment"
            )

            scopeName.startsWith("source.java") || languageId.equals("java", ignoreCase = true) -> listOf(
                "class A { int x = 1; }",
                "// comment"
            )

            scopeName.startsWith("source.kotlin") || languageId.equals("kotlin", ignoreCase = true) -> listOf(
                "class A(val x: Int = 1)",
                "fun f(x: Int) = x + 1"
            )

            scopeName.startsWith("source.json") || languageId.equals("json", ignoreCase = true) -> listOf(
                "{\"a\": 1, \"b\": true}",
                "[1, 2, 3]"
            )

            scopeName == "source.yaml" || languageId.equals("yaml", ignoreCase = true) -> listOf(
                "version: \"3\"",
                "services:",
                "  web:",
                "    image: nginx:latest",
                "    ports: [\"80:80\"]",
                "    environment:",
                "      - KEY=value"
            )

            scopeName.startsWith("source.css") || languageId.equals("css", ignoreCase = true) -> listOf(
                "body { color: red; }",
                ".a:hover { background: #fff; }"
            )

            else -> listOf(
                "a: 1",
                "# comment",
                "- item",
                "key: \"value\""
            )
        }
    }

    // ---- VSCode: preload grammars ----

    private suspend fun preloadAllVSCodeExtensionGrammars(): VSCodePreloadReport = withContext(Dispatchers.IO) {
        val roots = activity.assets.list("")?.toList().orEmpty()
        var loaded = 0
        val failures = ArrayList<String>()
        for (root in roots) {
            val children = activity.assets.list(root)?.toList().orEmpty()
            if (!children.contains("package.json")) continue
            val report = preloadVSCodeExtensionGrammars(root)
            loaded += report.loaded
            failures.addAll(report.failures)
        }
        VSCodePreloadReport(loaded = loaded, failed = failures.size, failures = failures)
    }

    private suspend fun preloadVSCodeExtensionGrammars(root: String): VSCodePreloadReport = withContext(Dispatchers.IO) {
        if (!vsCodePreloadedExtensionRoots.add(root)) {
            return@withContext VSCodePreloadReport(loaded = 0, failed = 0, failures = emptyList())
        }

        val pkgText = runCatching {
            activity.assets.open("$root/package.json")
                .bufferedReader()
                .use { it.readText() }
        }.getOrNull()
            ?: return@withContext VSCodePreloadReport(
                loaded = 0,
                failed = 1,
                failures = listOf("$root：package.json 读取失败")
            )

        val pkg = runCatching { JSONObject(pkgText) }.getOrNull()
            ?: return@withContext VSCodePreloadReport(
                loaded = 0,
                failed = 1,
                failures = listOf("$root：package.json 解析失败")
            )

        val contributes = pkg.optJSONObject("contributes")
            ?: return@withContext VSCodePreloadReport(loaded = 0, failed = 0, failures = emptyList())

        val languagesArray = contributes.optJSONArray("languages") ?: JSONArray()
        val languageConfigMap = HashMap<String, String?>(languagesArray.length())
        for (i in 0 until languagesArray.length()) {
            val obj = languagesArray.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            if (id.isEmpty()) continue
            val configRaw = obj.optString("configuration").trim()
            val configPath = configRaw.takeIf { it.isNotEmpty() }?.let { "$root/${normalizeRelativePath(it)}" }
            languageConfigMap[id] = configPath
        }

        val grammarsArray = contributes.optJSONArray("grammars") ?: JSONArray()
        var loaded = 0
        val failures = ArrayList<String>()

        for (i in 0 until grammarsArray.length()) {
            val obj = grammarsArray.optJSONObject(i) ?: continue
            val scopeName = obj.optString("scopeName").trim()
            val languageId = obj.optString("language").trim()
            val grammarPathRaw = obj.optString("path").trim()
            if (scopeName.isEmpty() || grammarPathRaw.isEmpty()) continue

            val grammarPath = "$root/${normalizeRelativePath(grammarPathRaw)}"
            val configPath = languageConfigMap[languageId]

            val embeddedLanguagesObj = obj.optJSONObject("embeddedLanguages")
            val embeddedLanguages = if (embeddedLanguagesObj != null) {
                val map = HashMap<String, String>()
                val keys = embeddedLanguagesObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = embeddedLanguagesObj.optString(key).trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        map[key] = value
                    }
                }
                map
            } else {
                emptyMap()
            }

            val grammarStream = FileProviderRegistry.getInstance().tryGetInputStream(grammarPath)
            if (grammarStream == null) {
                failures.add("$root：grammar 打开失败：$grammarPath")
                continue
            }

            try {
                val grammarSource = IGrammarSource.fromInputStream(grammarStream, grammarPath, Charsets.UTF_8)
                val baseDef = DefaultGrammarDefinition.withLanguageConfiguration(
                    grammarSource,
                    configPath,
                    grammarPath,
                    scopeName
                )
                val def: GrammarDefinition = if (embeddedLanguages.isNotEmpty()) {
                    baseDef.withEmbeddedLanguages(embeddedLanguages)
                } else {
                    baseDef
                }

                val grammar = GrammarRegistry.getInstance().loadGrammar(def)
                if (grammar == null) {
                    failures.add("$root：grammar 加载返回 null：$scopeName（$grammarPath）")
                } else {
                    loaded++
                }
            } catch (t: Throwable) {
                failures.add("$root：grammar 加载异常：$scopeName（$grammarPath）→ ${t::class.java.name}: ${t.message}")
            } finally {
                runCatching { grammarStream.close() }
            }
        }

        VSCodePreloadReport(loaded = loaded, failed = failures.size, failures = failures)
    }

    // ---- VSCode: syntax dialog ----

    private fun showVSCodeSyntaxLanguageDialog(languages: List<VSCodeLanguageInfo>) {
        val density = activity.resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val listHeight = (360 * density).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val autoSwitch = SwitchCompat(activity).apply {
            text = "自动按后缀匹配（打开文件时）"
            isChecked = isVSCodeAutoHighlightEnabled()
            setOnCheckedChangeListener { _, checked ->
                setVSCodeAutoHighlightEnabled(checked)
                if (checked) {
                    val p = currentFilePathProvider()
                    if (p != null) {
                        maybeAutoApplyVSCodeSyntaxByFileName(p)
                    }
                }
            }
        }

        val selfTestButton = Button(activity).apply {
            text = "语法自测"
            setOnClickListener {
                isEnabled = false
                activity.toast("正在运行语法自测（可能较慢）…")
                activity.lifecycleScope.launch {
                    val report = withContext(Dispatchers.IO) {
                        VSCodeSyntaxSelfTest.run(
                            languages = languages,
                            isNativeOniguruma = { Oniguruma().isUseNativeOniguruma },
                            ensureGrammarLoaded = { info ->
                                runCatching {
                                    val ext = info.extensionName
                                    if (ext != null && ext != EXT_BUILTIN_TEXTMATE) {
                                        preloadVSCodeExtensionGrammars(ext)
                                    }
                                    preloadEmbeddedLanguageDependencies(info, vsCodeLanguageCache.orEmpty())
                                    val def = buildVSCodeGrammarDefinition(info)
                                    if (def != null) {
                                        val g = GrammarRegistry.getInstance().loadGrammar(def)
                                        if (g == null) {
                                            throw IllegalStateException("grammar 加载返回 null（scope=${info.scopeName}）")
                                        }
                                    }
                                    GrammarRegistry.getInstance().findGrammar(info.scopeName)
                                        ?: throw IllegalStateException("grammar 未找到（scope=${info.scopeName}）")
                                }
                            },
                            sampleProvider = { info -> buildTokenizeSamples(info.scopeName, info.id) }
                        )
                    }

                    val fileName = "vscode_syntax_self_test.txt"
                    runCatching {
                        activity.openFileOutput(fileName, Context.MODE_PRIVATE).use { out ->
                            out.write(report.toByteArray(Charsets.UTF_8))
                        }
                    }

                    val maxChars = 200_000
                    val shown = if (report.length > maxChars) {
                        report.take(maxChars) + "\n...（已截断，完整内容见：$fileName）"
                    } else {
                        report
                    }
                    showCopyableDialog("VS Code语法自测报告", "已保存到内部存储：$fileName\n\n$shown")
                }.invokeOnCompletion {
                    isEnabled = true
                }
            }
        }

        val searchInput = EditText(activity).apply {
            setSingleLine(true)
            hint = "搜索：名称 / id / scope / 扩展名"
        }

        val listView = ListView(activity)

        val filtered = ArrayList(languages)

        fun buildLabel(info: VSCodeLanguageInfo): String {
            val base = if (info.displayName != info.id) "${info.displayName} (${info.id})" else info.displayName
            val ext = info.extensionName?.takeIf { it.isNotEmpty() }
            return if (ext == null) base else "$base — $ext"
        }

        val labelList = ArrayList(filtered.map { buildLabel(it) })
        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, labelList)
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(activity)
            .setTitle("${activity.getString(R.string.vscode_syntax_highlight)}（共 ${languages.size} 个）")
            .setView(container.apply {
                addView(
                    autoSwitch,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    selfTestButton,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    searchInput,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    listView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        listHeight
                    )
                )
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val info = filtered.getOrNull(position)
            if (info != null) {
                dialog.dismiss()
                applyVSCodeSyntaxHighlight(info, null)
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                filtered.clear()
                if (query.isEmpty()) {
                    filtered.addAll(languages)
                } else {
                    val q = query.lowercase()
                    filtered.addAll(
                        languages.filter {
                            it.displayName.lowercase().contains(q) ||
                                it.id.lowercase().contains(q) ||
                                it.scopeName.lowercase().contains(q) ||
                                (it.extensionName?.lowercase()?.contains(q) == true)
                        }
                    )
                }
                labelList.clear()
                labelList.addAll(filtered.map { buildLabel(it) })
                adapter.notifyDataSetChanged()
            }
        })

        dialog.show()
    }

    // ---- VSCode: theme ----

    private suspend fun loadVSCodeThemesFromAssets(): List<VSCodeThemeInfo> = withContext(Dispatchers.IO) {
        val result = ArrayList<VSCodeThemeInfo>(256)

        runCatching {
            val themeFiles = activity.assets.list("textmate")?.toList().orEmpty()
                .filter { it.endsWith(".json", ignoreCase = true) }
                .filter { it != "languages.json" }
            for (file in themeFiles) {
                val path = "textmate/$file"
                val id = "builtin:$file"
                result.add(
                    VSCodeThemeInfo(
                        id = id,
                        label = file.removeSuffix(".json"),
                        uiTheme = null,
                        path = path,
                        extensionName = EXT_BUILTIN_TEXTMATE
                    )
                )
            }
        }

        val roots = activity.assets.list("")?.toList().orEmpty()
        for (root in roots) {
            val children = activity.assets.list(root)?.toList().orEmpty()
            if (!children.contains("package.json")) continue

            val pkgText = runCatching {
                activity.assets.open("$root/package.json")
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrNull() ?: continue
            val pkg = runCatching { JSONObject(pkgText) }.getOrNull() ?: continue

            val nlsMap = runCatching {
                if (!children.contains("package.nls.json")) {
                    emptyMap()
                } else {
                    val nlsText = activity.assets.open("$root/package.nls.json")
                        .bufferedReader()
                        .use { it.readText() }
                    val nlsJson = JSONObject(nlsText)
                    val keys = nlsJson.keys()
                    val map = HashMap<String, String>()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = nlsJson.optString(key, null)
                        if (key.isNotEmpty() && value != null) {
                            map[key] = value
                        }
                    }
                    map
                }
            }.getOrElse { emptyMap() }

            fun resolveNls(value: String): String {
                var v = value.trim()
                if (v.isEmpty() || nlsMap.isEmpty()) return v

                var start = v.indexOf('%')
                while (start != -1) {
                    val end = v.indexOf('%', start + 1)
                    if (end == -1) break
                    val key = v.substring(start + 1, end)
                    val replacement = nlsMap[key]
                    if (replacement != null) {
                        v = v.substring(0, start) + replacement + v.substring(end + 1)
                        start = v.indexOf('%', start + replacement.length)
                    } else {
                        start = v.indexOf('%', end + 1)
                    }
                }
                return v
            }

            val contributes = pkg.optJSONObject("contributes") ?: continue
            val themesArray = contributes.optJSONArray("themes") ?: JSONArray()
            for (i in 0 until themesArray.length()) {
                val obj = themesArray.optJSONObject(i) ?: continue
                val rawLabel = obj.optString("label").trim()
                val rawId = obj.optString("id").trim()
                val label = resolveNls(rawLabel).ifEmpty { resolveNls(rawId) }
                val id = resolveNls(rawId).ifEmpty { "$root:$label" }
                val uiTheme = obj.optString("uiTheme").trim().ifEmpty { null }
                val pathRaw = obj.optString("path").trim()
                if (label.isEmpty() || pathRaw.isEmpty()) continue
                val path = "$root/${normalizeRelativePath(pathRaw)}"
                result.add(
                    VSCodeThemeInfo(
                        id = id,
                        label = label,
                        uiTheme = uiTheme,
                        path = path,
                        extensionName = root
                    )
                )
            }
        }

        result.distinctBy { it.path }.sortedBy { it.label.lowercase() }
    }

    private fun showVSCodeThemeDialog(themes: List<VSCodeThemeInfo>) {
        val density = activity.resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val listHeight = (360 * density).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val searchInput = EditText(activity).apply {
            setSingleLine(true)
            hint = "搜索：主题名 / id / 扩展名"
        }

        val listView = ListView(activity)
        val filtered = ArrayList(themes)

        fun buildLabel(info: VSCodeThemeInfo): String {
            val base = if (info.label != info.id) "${info.label} (${info.id})" else info.label
            val ext = info.extensionName?.takeIf { it.isNotEmpty() }
            return if (ext == null) base else "$base — $ext"
        }

        val labelList = ArrayList(filtered.map { buildLabel(it) })
        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, labelList)
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(activity)
            .setTitle("${activity.getString(R.string.vscode_theme)}（共 ${themes.size} 个）")
            .setView(container.apply {
                addView(
                    searchInput,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    listView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        listHeight
                    )
                )
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun buildThemeFailureMessage(info: VSCodeThemeInfo, throwable: Throwable): String {
            val ext = info.extensionName ?: "无"
            val errMsg = throwable.message?.trim().orEmpty()
            val err =
                if (errMsg.isEmpty()) throwable::class.java.name else "${throwable::class.java.name}: $errMsg"
            return """
                切换主题失败
                主题：${info.label} (${info.id})
                uiTheme：${info.uiTheme ?: "未知"}
                路径：${info.path}
                扩展：$ext
                错误：$err
            """.trimIndent()
        }

        fun applyTheme(info: VSCodeThemeInfo) {
            dialog.dismiss()
            activity.toast("正在切换主题：${info.label}…")
            activity.lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val stream = FileProviderRegistry.getInstance().tryGetInputStream(info.path)
                            ?: throw IllegalStateException("主题文件无法打开：${info.path}")
                        stream.use {
                            val themeModel = ThemeModel(
                                IThemeSource.fromInputStream(it, info.path, null),
                                info.id
                            ).apply {
                                val ui = info.uiTheme?.lowercase()
                                if (ui != null) {
                                    isDark = ui.contains("dark")
                                }
                            }
                            ThemeRegistry.getInstance().loadTheme(themeModel)
                        }
                    }

                    val editor = binding.editor
                    val currentLanguage = editor.editorLanguage
                    if (currentLanguage is TextMateLanguage) {
                        env.ensureTextmateTheme()
                        ThemeRegistry.getInstance().setTheme(info.id)
                        env.resetColorScheme()
                        activity.toast("已切换主题：${info.label}")
                    } else if (currentLanguage is MonarchLanguage) {
                        env.ensureMonarchTheme()
                        io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.loadTheme(
                            io.github.rosemoe.sora.langs.monarch.registry.model.ThemeModel(
                                ThemeSource(info.path, info.id)
                            ),
                            false
                        )
                        io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme(info.id)
                        env.resetColorScheme()
                        activity.toast("已切换主题：${info.label}")
                    } else {
                        activity.toast("已加载主题：${info.label}；当前语言不是 TextMate/Monarch，主题不会生效")
                    }
                }.onFailure {
                    val msg = buildThemeFailureMessage(info, it)
                    activity.toast("切换主题失败：已弹出可复制信息")
                    showCopyableDialog("VS Code主题：切换失败", msg)
                }
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val info = filtered.getOrNull(position)
            if (info != null) {
                applyTheme(info)
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                filtered.clear()
                if (query.isEmpty()) {
                    filtered.addAll(themes)
                } else {
                    val q = query.lowercase()
                    filtered.addAll(
                        themes.filter {
                            it.label.lowercase().contains(q) ||
                                it.id.lowercase().contains(q) ||
                                (it.extensionName?.lowercase()?.contains(q) == true)
                        }
                    )
                }
                labelList.clear()
                labelList.addAll(filtered.map { buildLabel(it) })
                adapter.notifyDataSetChanged()
            }
        })

        dialog.show()
    }

    // ---- VSCode: typeface ----

    private suspend fun loadVSCodeTypefacesFromAssets(): List<VSCodeTypefaceInfo> = withContext(Dispatchers.IO) {
        val result = ArrayList<VSCodeTypefaceInfo>(64)
        val seen = HashSet<String>()

        val roots = activity.assets.list("")?.toList().orEmpty()
        for (name in roots) {
            if (!isFontAssetFile(name)) continue
            val path = name
            val key = "asset:$path"
            if (seen.add(key)) {
                result.add(
                    VSCodeTypefaceInfo(
                        id = "builtin:$name",
                        label = name.substringBeforeLast('.'),
                        kind = VSCodeTypefaceKind.ASSET,
                        assetPath = path,
                        systemFamilyName = null,
                        extensionName = EXT_BUILTIN_APP
                    )
                )
            }
        }

        val candidateDirs = setOf("fonts", "font", "resources", "resource", "media", "res", "out", "dist")

        for (root in roots) {
            val children = activity.assets.list(root)?.toList().orEmpty()
            if (!children.contains("package.json")) continue

            for (child in children) {
                if (!isFontAssetFile(child)) continue
                val path = "$root/$child"
                val key = "asset:$path"
                if (seen.add(key)) {
                    result.add(
                        VSCodeTypefaceInfo(
                            id = "$root:$child",
                            label = child.substringBeforeLast('.'),
                            kind = VSCodeTypefaceKind.ASSET,
                            assetPath = path,
                            systemFamilyName = null,
                            extensionName = root
                        )
                    )
                }
            }

            val dirs = children.filter { it in candidateDirs }
            for (dir in dirs) {
                val fontPaths = ArrayList<String>(16)
                collectFontFiles("$root/$dir", maxDepth = 4, maxResults = 80, results = fontPaths)
                for (path in fontPaths) {
                    val key = "asset:$path"
                    if (!seen.add(key)) continue
                    val fileName = path.substringAfterLast('/')
                    result.add(
                        VSCodeTypefaceInfo(
                            id = "$root:$fileName",
                            label = fileName.substringBeforeLast('.'),
                            kind = VSCodeTypefaceKind.ASSET,
                            assetPath = path,
                            systemFamilyName = null,
                            extensionName = root
                        )
                    )
                }
            }
        }

        val systemFamilies = listOf(
            "monospace" to "Monospace",
            "sans-serif" to "Sans Serif",
            "serif" to "Serif",
        )
        for ((family, label) in systemFamilies) {
            val key = "system:$family"
            if (!seen.add(key)) continue
            result.add(
                VSCodeTypefaceInfo(
                    id = "system:$family",
                    label = label,
                    kind = VSCodeTypefaceKind.SYSTEM,
                    assetPath = null,
                    systemFamilyName = family,
                    extensionName = EXT_SYSTEM
                )
            )
        }

        result.distinctBy { it.id }.sortedBy { it.label.lowercase() }
    }

    private fun isFontAssetFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc")
    }

    private fun collectFontFiles(basePath: String, maxDepth: Int, maxResults: Int, results: MutableList<String>) {
        if (maxDepth < 0) return
        if (results.size >= maxResults) return

        val entries = runCatching { activity.assets.list(basePath)?.toList().orEmpty() }.getOrElse { emptyList() }
        for (entry in entries) {
            if (results.size >= maxResults) return
            val path = "$basePath/$entry"
            if (isFontAssetFile(entry)) {
                results.add(path)
            } else {
                val subEntries = runCatching { activity.assets.list(path) }.getOrNull()
                if (subEntries != null && subEntries.isNotEmpty()) {
                    collectFontFiles(path, maxDepth - 1, maxResults, results)
                }
            }
        }
    }

    private fun showVSCodeTypefaceDialog(typefaces: List<VSCodeTypefaceInfo>) {
        val density = activity.resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val listHeight = (360 * density).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val searchInput = EditText(activity).apply {
            setSingleLine(true)
            hint = "搜索：字体名 / id / 扩展名"
        }

        val listView = ListView(activity)
        val filtered = ArrayList(typefaces)

        fun buildLabel(info: VSCodeTypefaceInfo): String {
            val base = if (info.label != info.id) "${info.label} (${info.id})" else info.label
            val ext = info.extensionName?.takeIf { it.isNotEmpty() }
            return if (ext == null) base else "$base — $ext"
        }

        val labelList = ArrayList(filtered.map { buildLabel(it) })
        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, labelList)
        listView.adapter = adapter

        val countSystem = typefaces.count { it.kind == VSCodeTypefaceKind.SYSTEM }
        val countBuiltin = typefaces.count { it.kind == VSCodeTypefaceKind.ASSET && it.extensionName == EXT_BUILTIN_APP }
        val countExtension = typefaces.count {
            it.kind == VSCodeTypefaceKind.ASSET &&
                it.extensionName != null &&
                it.extensionName != EXT_BUILTIN_APP &&
                it.extensionName != EXT_SYSTEM
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("${activity.getString(R.string.vscode_typeface)}（扩展 $countExtension / 内置 $countBuiltin / 系统 $countSystem）")
            .setView(container.apply {
                addView(
                    searchInput,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    listView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        listHeight
                    )
                )
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun buildTypefaceFailureMessage(info: VSCodeTypefaceInfo, throwable: Throwable): String {
            val ext = info.extensionName ?: "无"
            val errMsg = throwable.message?.trim().orEmpty()
            val err =
                if (errMsg.isEmpty()) throwable::class.java.name else "${throwable::class.java.name}: $errMsg"
            val source = when (info.kind) {
                VSCodeTypefaceKind.ASSET -> "资源文件"
                VSCodeTypefaceKind.SYSTEM -> "系统字体"
            }
            val location = when (info.kind) {
                VSCodeTypefaceKind.ASSET -> info.assetPath ?: "无"
                VSCodeTypefaceKind.SYSTEM -> info.systemFamilyName ?: "无"
            }
            return """
                切换字体失败
                字体：${info.label} (${info.id})
                来源：$source
                路径/家族：$location
                扩展：$ext
                错误：$err
            """.trimIndent()
        }

        fun applyTypeface(info: VSCodeTypefaceInfo) {
            dialog.dismiss()
            activity.toast("正在切换字体：${info.label}…")
            activity.lifecycleScope.launch {
                runCatching {
                    val tf = withContext(Dispatchers.IO) {
                        when (info.kind) {
                            VSCodeTypefaceKind.ASSET -> Typeface.createFromAsset(
                                activity.assets,
                                info.assetPath ?: throw IllegalStateException("字体路径为空")
                            )

                            VSCodeTypefaceKind.SYSTEM -> Typeface.create(
                                info.systemFamilyName ?: throw IllegalStateException("系统字体 family 为空"),
                                Typeface.NORMAL
                            )
                        }
                    }
                    binding.editor.typefaceText = tf
                    binding.symbolInput.forEachButton { it.typeface = tf }
                    activity.toast("已切换字体：${info.label}")
                }.onFailure {
                    val msg = buildTypefaceFailureMessage(info, it)
                    activity.toast("切换字体失败：已弹出可复制信息")
                    showCopyableDialog("VS Code字体：切换失败", msg)
                }
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val info = filtered.getOrNull(position)
            if (info != null) {
                applyTypeface(info)
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                filtered.clear()
                if (query.isEmpty()) {
                    filtered.addAll(typefaces)
                } else {
                    val q = query.lowercase()
                    filtered.addAll(
                        typefaces.filter {
                            it.label.lowercase().contains(q) ||
                                it.id.lowercase().contains(q) ||
                                (it.extensionName?.lowercase()?.contains(q) == true)
                        }
                    )
                }
                labelList.clear()
                labelList.addAll(filtered.map { buildLabel(it) })
                adapter.notifyDataSetChanged()
            }
        })

        dialog.show()
    }

    // ---- UI helper ----

    private fun showCopyableDialog(title: String, message: String) {
        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val tv = TextView(activity).apply {
            text = message
            setTextIsSelectable(true)
            setPadding(padding, padding, padding, padding)
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(tv)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(title, message))
                activity.toast("已复制到剪贴板")
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
