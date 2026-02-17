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
import android.content.DialogInterface
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import io.github.dingyi222666.monarch.languages.JavaLanguage as MonarchJavaLanguage
import io.github.dingyi222666.monarch.languages.KotlinLanguage as MonarchKotlinLanguage
import io.github.dingyi222666.monarch.languages.PythonLanguage as MonarchPythonLanguage
import io.github.dingyi222666.monarch.languages.TypescriptLanguage as MonarchTypescriptLanguage
import io.github.rosemoe.sora.app.databinding.ActivityMainBinding
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.JavaLanguageSpec
import io.github.rosemoe.sora.lang.TsLanguageJava
import io.github.rosemoe.sora.langs.java.JavaLanguage as SoraJavaLanguage
import io.github.rosemoe.sora.langs.monarch.MonarchColorScheme
import io.github.rosemoe.sora.langs.monarch.MonarchLanguage
import io.github.rosemoe.sora.langs.monarch.registry.MonarchGrammarRegistry
import io.github.rosemoe.sora.langs.monarch.registry.dsl.monarchLanguages
import io.github.rosemoe.sora.langs.monarch.registry.model.ThemeSource
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.dsl.languages
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.GrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.utils.toast
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.getComponent
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeEclipse
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import io.github.rosemoe.sora.widget.schemes.SchemeNotepadXX
import io.github.rosemoe.sora.widget.schemes.SchemeVS2019
import io.github.rosemoe.sora.widget.style.LineInfoPanelPosition
import io.github.rosemoe.sora.widget.style.LineInfoPanelPositionMode
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import androidx.appcompat.app.AppCompatActivity

/**
 * 负责“编辑器环境”的初始化与切换：
 * - TextMate/Monarch 的 themes & grammars 注册
 * - 确保 editor.colorScheme 使用 TextMateColorScheme / MonarchColorScheme
 * - 语言切换、主题切换、字体切换、行号面板位置设置
 * - 从 Uri 加载 TM grammar/theme
 */
internal class EditorEnvironment(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val prefs: SharedPreferences
) {

    private val editor get() = binding.editor

    private companion object {
        private const val KEY_THEME_MODE = "editor.theme.mode"
        private const val KEY_FIXED_SCHEME_NAME = "editor.theme.fixed.schemeName"
        private const val KEY_FIXED_TEXTMATE_THEME_ID = "editor.theme.fixed.textmateThemeId"
        private const val KEY_FIXED_MONARCH_THEME_ID = "editor.theme.fixed.monarchThemeId"

        private const val THEME_MODE_SYSTEM = "system"
        private const val THEME_MODE_FIXED = "fixed"
    }

    /**
     * Setup TextMate. Load our grammars and themes from assets
     */
    fun setupTextmate() {
        // Add assets file provider so that files in assets can be loaded
        FileProviderRegistry.getInstance().addFileProvider(
            AssetsFileResolver(
                activity.applicationContext.assets // use application context
            )
        )
        loadDefaultTextMateThemes()
        loadDefaultTextMateLanguages()
    }

    /**
     * Load default textmate themes
     */
    private fun loadDefaultTextMateThemes() {
        val themes = arrayOf("darcula", "ayu-dark", "quietlight", "solarized_dark")
        val themeRegistry = ThemeRegistry.getInstance()
        themes.forEach { name ->
            val path = "textmate/$name.json"
            themeRegistry.loadTheme(
                ThemeModel(
                    IThemeSource.fromInputStream(
                        FileProviderRegistry.getInstance().tryGetInputStream(path),
                        path,
                        null
                    ),
                    name
                ).apply {
                    if (name != "quietlight") {
                        isDark = true
                    }
                }
            )
        }
    }

    /**
     * Load default languages from JSON configuration
     */
    private fun loadDefaultTextMateLanguages() {
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
    }

    /**
     * Setup monarch. Load our grammars and themes from assets
     */
    fun setupMonarch() {
        // Add assets file provider so that files in assets can be loaded
        io.github.rosemoe.sora.langs.monarch.registry.FileProviderRegistry.addProvider(
            io.github.rosemoe.sora.langs.monarch.registry.provider.AssetsFileResolver(
                activity.applicationContext.assets // use application context
            )
        )
        loadDefaultMonarchThemes()
        loadDefaultMonarchLanguages()
    }

    /**
     * Load default monarch themes
     */
    private fun loadDefaultMonarchThemes() {
        val themes = arrayOf("darcula", "ayu-dark", "quietlight", "solarized_dark")

        themes.forEach { name ->
            val path = "textmate/$name.json"
            io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.loadTheme(
                io.github.rosemoe.sora.langs.monarch.registry.model.ThemeModel(
                    ThemeSource(path, name)
                ).apply {
                    if (name != "quietlight") {
                        isDark = true
                    }
                },
                false
            )
        }

        io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme("quietlight")
    }

    /**
     * Load default languages from Monarch
     */
    private fun loadDefaultMonarchLanguages() {
        MonarchGrammarRegistry.INSTANCE.loadGrammars(
            monarchLanguages {
                language("java") {
                    monarchLanguage = MonarchJavaLanguage
                    defaultScopeName()
                    languageConfiguration = "textmate/java/language-configuration.json"
                }
                language("kotlin") {
                    monarchLanguage = MonarchKotlinLanguage
                    defaultScopeName()
                    languageConfiguration = "textmate/kotlin/language-configuration.json"
                }
                language("python") {
                    monarchLanguage = MonarchPythonLanguage
                    defaultScopeName()
                    languageConfiguration = "textmate/python/language-configuration.json"
                }
                language("typescript") {
                    monarchLanguage = MonarchTypescriptLanguage
                    defaultScopeName()
                    languageConfiguration = "textmate/javascript/language-configuration.json"
                }
            }
        )
    }

    /**
     * 可选：如果你想用 DSL 方式加载 TextMate grammars（当前默认走 languages.json）
     */
    @Suppress("unused")
    private fun loadDefaultLanguagesWithDSL() {
        GrammarRegistry.getInstance().loadGrammars(
            languages {
                language("java") {
                    grammar = "textmate/java/syntaxes/java.tmLanguage.json"
                    defaultScopeName()
                    languageConfiguration = "textmate/java/language-configuration.json"
                }
                language("kotlin") {
                    grammar = "textmate/kotlin/syntaxes/Kotlin.tmLanguage"
                    defaultScopeName()
                    languageConfiguration = "textmate/kotlin/language-configuration.json"
                }
                language("python") {
                    grammar = "textmate/python/syntaxes/python.tmLanguage.json"
                    defaultScopeName()
                    languageConfiguration = "textmate/python/language-configuration.json"
                }
            }
        )
    }

    /**
     * Re-apply color scheme
     */
    fun resetColorScheme() {
        editor.apply {
            val colorScheme = this.colorScheme
            this.colorScheme = colorScheme
        }
    }

    /**
     * Ensure the editor uses a [TextMateColorScheme]
     */
    fun ensureTextmateTheme() {
        var editorColorScheme = editor.colorScheme
        if (editorColorScheme !is TextMateColorScheme) {
            editorColorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
            editor.colorScheme = editorColorScheme
        }
    }

    /**
     * Ensure the editor uses a [MonarchColorScheme]
     */
    fun ensureMonarchTheme() {
        var editorColorScheme = editor.colorScheme
        if (editorColorScheme !is MonarchColorScheme) {
            editorColorScheme =
                MonarchColorScheme.create(io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.currentTheme)
            editor.colorScheme = editorColorScheme
        }
    }

    fun applyDefaultTextMateJava() {
        ensureTextmateTheme()
        val language = TextMateLanguage.create("source.java", true)
        editor.setEditorLanguage(language)
    }

    fun chooseTypeface() {
        val fonts = arrayOf(
            "JetBrains Mono",
            "Ubuntu",
            "Roboto"
        )
        val assetsPaths = arrayOf(
            "JetBrainsMono-Regular.ttf",
            "Ubuntu-Regular.ttf",
            "Roboto-Regular.ttf"
        )
        AlertDialog.Builder(activity)
            .setTitle(android.R.string.dialog_alert_title)
            .setSingleChoiceItems(fonts, -1) { dialog: DialogInterface, which: Int ->
                if (which in assetsPaths.indices) {
                    editor.typefaceText =
                        Typeface.createFromAsset(activity.assets, assetsPaths[which])
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun chooseLineNumberPanelPositionFixed() {
        val themes = arrayOf(
            activity.getString(R.string.top),
            activity.getString(R.string.bottom),
            activity.getString(R.string.left),
            activity.getString(R.string.right),
            activity.getString(R.string.center),
            activity.getString(R.string.top_left),
            activity.getString(R.string.top_right),
            activity.getString(R.string.bottom_left),
            activity.getString(R.string.bottom_right)
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.fixed)
            .setSingleChoiceItems(themes, -1) { dialog: DialogInterface, which: Int ->
                editor.lnPanelPositionMode = LineInfoPanelPositionMode.FIXED
                when (which) {
                    0 -> editor.lnPanelPosition = LineInfoPanelPosition.TOP
                    1 -> editor.lnPanelPosition = LineInfoPanelPosition.BOTTOM
                    2 -> editor.lnPanelPosition = LineInfoPanelPosition.LEFT
                    3 -> editor.lnPanelPosition = LineInfoPanelPosition.RIGHT
                    4 -> editor.lnPanelPosition = LineInfoPanelPosition.CENTER
                    5 -> editor.lnPanelPosition =
                        LineInfoPanelPosition.TOP or LineInfoPanelPosition.LEFT
                    6 -> editor.lnPanelPosition =
                        LineInfoPanelPosition.TOP or LineInfoPanelPosition.RIGHT
                    7 -> editor.lnPanelPosition =
                        LineInfoPanelPosition.BOTTOM or LineInfoPanelPosition.LEFT
                    8 -> editor.lnPanelPosition =
                        LineInfoPanelPosition.BOTTOM or LineInfoPanelPosition.RIGHT
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun chooseLineNumberPanelPositionFollow() {
        val themes = arrayOf(
            activity.getString(R.string.top),
            activity.getString(R.string.center),
            activity.getString(R.string.bottom)
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.fixed)
            .setSingleChoiceItems(themes, -1) { dialog: DialogInterface, which: Int ->
                editor.lnPanelPositionMode = LineInfoPanelPositionMode.FOLLOW
                when (which) {
                    0 -> editor.lnPanelPosition = LineInfoPanelPosition.TOP
                    1 -> editor.lnPanelPosition = LineInfoPanelPosition.CENTER
                    2 -> editor.lnPanelPosition = LineInfoPanelPosition.BOTTOM
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun chooseLanguage(launchTMLFromFile: () -> Unit) {
        val languageOptions = arrayOf(
            "Java",
            "TextMate Java",
            "TextMate Kotlin",
            "TextMate Python",
            "TextMate Html",
            "TextMate JavaScript",
            "TextMate MarkDown",
            "TM Language from file",
            "Tree-sitter Java",
            "Monarch Java",
            "Monarch Kotlin",
            "Monarch Python",
            "Monarch TypeScript",
            "Text"
        )
        val tmLanguages = mapOf(
            "TextMate Java" to Pair("source.java", "source.java"),
            "TextMate Kotlin" to Pair("source.kotlin", "source.kotlin"),
            "TextMate Python" to Pair("source.python", "source.python"),
            "TextMate Html" to Pair("text.html.basic", "text.html.basic"),
            "TextMate JavaScript" to Pair("source.js", "source.js"),
            "TextMate MarkDown" to Pair("text.html.markdown", "text.html.markdown")
        )

        val monarchLanguages = mapOf(
            "Monarch Java" to "source.java",
            "Monarch Kotlin" to "source.kotlin",
            "Monarch Python" to "source.python",
            "Monarch TypeScript" to "source.typescript"
        )

        AlertDialog.Builder(activity)
            .setTitle(R.string.switch_language)
            .setSingleChoiceItems(languageOptions, -1) { dialog: DialogInterface, which: Int ->
                when (val selected = languageOptions[which]) {
                    in tmLanguages -> {
                        val info = tmLanguages[selected]!!
                        try {
                            ensureTextmateTheme()
                            val editorLanguage = editor.editorLanguage
                            val language = if (editorLanguage is TextMateLanguage) {
                                editorLanguage.updateLanguage(info.first)
                                editorLanguage
                            } else {
                                TextMateLanguage.create(info.second, true)
                            }
                            editor.setEditorLanguage(language)
                            applyUserPreferredTheme()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    in monarchLanguages -> {
                        val info = monarchLanguages[selected]!!
                        try {
                            ensureMonarchTheme()
                            val editorLanguage = editor.editorLanguage
                            val language = if (editorLanguage is MonarchLanguage) {
                                editorLanguage.updateLanguage(info)
                                editorLanguage
                            } else {
                                MonarchLanguage.create(info, true)
                            }
                            editor.setEditorLanguage(language)
                            applyUserPreferredTheme()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    else -> {
                        when (selected) {
                            "Java" -> {
                                editor.setEditorLanguage(SoraJavaLanguage())
                                applyUserPreferredTheme()
                            }

                            "Text" -> {
                                editor.setEditorLanguage(EmptyLanguage())
                                applyUserPreferredTheme()
                            }
                            "TM Language from file" -> launchTMLFromFile()
                            "Tree-sitter Java" -> {
                                editor.setEditorLanguage(
                                    TsLanguageJava(
                                        JavaLanguageSpec(
                                            highlightScmSource = activity.assets.open("tree-sitter-queries/java/highlights.scm")
                                                .reader().readText(),
                                            codeBlocksScmSource = activity.assets.open("tree-sitter-queries/java/blocks.scm")
                                                .reader().readText(),
                                            bracketsScmSource = activity.assets.open("tree-sitter-queries/java/brackets.scm")
                                                .reader().readText(),
                                            localsScmSource = activity.assets.open("tree-sitter-queries/java/locals.scm")
                                                .reader().readText()
                                        )
                                    )
                                )
                                applyUserPreferredTheme()
                            }
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun chooseTheme(launchTMThemeFromFile: () -> Unit) {
        val themes = arrayOf(
            "Default",
            "GitHub",
            "Eclipse",
            "Darcula",
            "VS2019",
            "NotepadXX",
            "QuietLight for TM(VSCode)",
            "Darcula for TM",
            "Ayu Dark for VSCode",
            "Solarized(Dark) for TM(VSCode)",
            "TM theme from file"
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.color_scheme)
            .setSingleChoiceItems(themes, -1) { dialog: DialogInterface, which: Int ->
                val currentLanguage = editor.editorLanguage
                val isTextMate = currentLanguage is TextMateLanguage
                val isMonarch = currentLanguage is MonarchLanguage

                when (which) {
                    0 -> {
                        if (isTextMate) {
                            try {
                                ensureTextmateTheme()
                                ThemeRegistry.getInstance().setTheme("quietlight")
                                saveFixedTheme(textmateThemeId = "quietlight")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } else if (isMonarch) {
                            try {
                                ensureMonarchTheme()
                                io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme("quietlight")
                                saveFixedTheme(monarchThemeId = "quietlight")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } else {
                            editor.colorScheme = EditorColorScheme()
                            saveFixedTheme(schemeName = "Default")
                        }
                    }

                    1 -> {
                        if (isTextMate) {
                            try {
                                ensureTextmateTheme()
                                ThemeRegistry.getInstance().setTheme("quietlight")
                                saveFixedTheme(textmateThemeId = "quietlight")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } else if (isMonarch) {
                            try {
                                ensureMonarchTheme()
                                io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme("quietlight")
                                saveFixedTheme(monarchThemeId = "quietlight")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } else {
                            editor.colorScheme = SchemeGitHub()
                            saveFixedTheme(schemeName = "GitHub")
                        }
                    }

                    2 -> {
                        if (isTextMate) {
                            try {
                                ensureTextmateTheme()
                                ThemeRegistry.getInstance().setTheme("quietlight")
                                saveFixedTheme(textmateThemeId = "quietlight")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } else if (isMonarch) {
                            try {
                                ensureMonarchTheme()
                                io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme("quietlight")
                                saveFixedTheme(monarchThemeId = "quietlight")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } else {
                            editor.colorScheme = SchemeEclipse()
                            saveFixedTheme(schemeName = "Eclipse")
                        }
                    }

                    3 -> {
                        if (isTextMate) {
                            try {
                                ensureTextmateTheme()
                                ThemeRegistry.getInstance().setTheme("darcula")
                                saveFixedTheme(textmateThemeId = "darcula")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } else if (isMonarch) {
                            try {
                                ensureMonarchTheme()
                                io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme("darcula")
                                saveFixedTheme(monarchThemeId = "darcula")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } else {
                            editor.colorScheme = SchemeDarcula()
                            saveFixedTheme(schemeName = "Darcula")
                        }
                    }

                    4 -> {
                        if (isTextMate) {
                            try {
                                ensureTextmateTheme()
                                ThemeRegistry.getInstance().setTheme("darcula")
                                saveFixedTheme(textmateThemeId = "darcula")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } else if (isMonarch) {
                            try {
                                ensureMonarchTheme()
                                io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme("darcula")
                                saveFixedTheme(monarchThemeId = "darcula")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } else {
                            editor.colorScheme = SchemeVS2019()
                            saveFixedTheme(schemeName = "VS2019")
                        }
                    }

                    5 -> {
                        if (isTextMate) {
                            try {
                                ensureTextmateTheme()
                                ThemeRegistry.getInstance().setTheme("quietlight")
                                saveFixedTheme(textmateThemeId = "quietlight")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } else if (isMonarch) {
                            try {
                                ensureMonarchTheme()
                                io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme("quietlight")
                                saveFixedTheme(monarchThemeId = "quietlight")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } else {
                            editor.colorScheme = SchemeNotepadXX()
                            saveFixedTheme(schemeName = "NotepadXX")
                        }
                    }

                    6 -> try {
                        if (isMonarch) {
                            ensureMonarchTheme()
                            io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme("quietlight")
                            saveFixedTheme(monarchThemeId = "quietlight")
                        } else {
                            ensureTextmateTheme()
                            ThemeRegistry.getInstance().setTheme("quietlight")
                            saveFixedTheme(textmateThemeId = "quietlight")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    7 -> try {
                        if (isMonarch) {
                            ensureMonarchTheme()
                            io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme("darcula")
                            saveFixedTheme(monarchThemeId = "darcula")
                        } else {
                            ensureTextmateTheme()
                            ThemeRegistry.getInstance().setTheme("darcula")
                            saveFixedTheme(textmateThemeId = "darcula")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    8 -> try {
                        if (isMonarch) {
                            ensureMonarchTheme()
                            io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme("ayu-dark")
                            saveFixedTheme(monarchThemeId = "ayu-dark")
                        } else {
                            ensureTextmateTheme()
                            ThemeRegistry.getInstance().setTheme("ayu-dark")
                            saveFixedTheme(textmateThemeId = "ayu-dark")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    9 -> try {
                        if (isMonarch) {
                            ensureMonarchTheme()
                            io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme("solarized_dark")
                            saveFixedTheme(monarchThemeId = "solarized_dark")
                        } else {
                            ensureTextmateTheme()
                            ThemeRegistry.getInstance().setTheme("solarized_dark")
                            saveFixedTheme(textmateThemeId = "solarized_dark")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    10 -> launchTMThemeFromFile()
                }
                resetColorScheme()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 通过 Uri 加载 TextMate grammar（TM Language from file）
     * - 做了可靠性增强：先确保 TextMateColorScheme 已应用，避免“加载了 grammar 但无高亮”
     */
    fun applyTextMateLanguageFromUri(uri: Uri) {
        try {
            ensureTextmateTheme()

            val id = uri.path ?: uri.toString()
            val input = activity.contentResolver.openInputStream(uri) ?: return

            val editorLanguage = editor.editorLanguage
            val language = if (editorLanguage is TextMateLanguage) {
                editorLanguage.updateLanguage(
                    DefaultGrammarDefinition.withGrammarSource(
                        IGrammarSource.fromInputStream(input, id, null),
                    )
                )
                editorLanguage
            } else {
                TextMateLanguage.create(
                    DefaultGrammarDefinition.withGrammarSource(
                        IGrammarSource.fromInputStream(input, id, null),
                    ), true
                )
            }
            editor.setEditorLanguage(language)
            applyUserPreferredTheme()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 通过 Uri 加载 TextMate theme（TM theme from file）
     */
    fun applyTextMateThemeFromUri(uri: Uri) {
        try {
            ensureTextmateTheme()

            val id = uri.path ?: uri.toString()
            ThemeRegistry.getInstance().loadTheme(
                IThemeSource.fromInputStream(
                    activity.contentResolver.openInputStream(uri),
                    id,
                    null
                )
            )

            resetColorScheme()
        } catch (e: Exception) {
            e.printStackTrace()
            activity.toast(e.toString())
        }
    }

    fun applyUserPreferredTheme() {
        val mode = prefs.getString(KEY_THEME_MODE, THEME_MODE_SYSTEM) ?: THEME_MODE_SYSTEM
        if (mode != THEME_MODE_FIXED) {
            switchThemeIfRequired(activity, editor)
            return
        }

        when (editor.editorLanguage) {
            is TextMateLanguage -> {
                val id = prefs.getString(KEY_FIXED_TEXTMATE_THEME_ID, null)
                if (!id.isNullOrBlank()) {
                    runCatching {
                        ensureTextmateTheme()
                        ThemeRegistry.getInstance().setTheme(id)
                        resetColorScheme()
                    }
                }
            }

            is MonarchLanguage -> {
                val id = prefs.getString(KEY_FIXED_MONARCH_THEME_ID, null)
                if (!id.isNullOrBlank()) {
                    runCatching {
                        ensureMonarchTheme()
                        io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme(id)
                        resetColorScheme()
                    }
                }
            }

            else -> {
                val name = prefs.getString(KEY_FIXED_SCHEME_NAME, null)
                if (name.isNullOrBlank()) {
                    switchThemeIfRequired(activity, editor)
                    return
                }
                editor.colorScheme = when (name) {
                    "GitHub" -> SchemeGitHub()
                    "Eclipse" -> SchemeEclipse()
                    "Darcula" -> SchemeDarcula()
                    "VS2019" -> SchemeVS2019()
                    "NotepadXX" -> SchemeNotepadXX()
                    else -> EditorColorScheme()
                }
                editor.invalidate()
            }
        }
    }

    private fun saveFixedTheme(
        schemeName: String? = null,
        textmateThemeId: String? = null,
        monarchThemeId: String? = null
    ) {
        prefs.edit()
            .putString(KEY_THEME_MODE, THEME_MODE_FIXED)
            .apply {
                if (schemeName != null) putString(KEY_FIXED_SCHEME_NAME, schemeName)
                if (textmateThemeId != null) putString(KEY_FIXED_TEXTMATE_THEME_ID, textmateThemeId)
                if (monarchThemeId != null) putString(KEY_FIXED_MONARCH_THEME_ID, monarchThemeId)
            }
            .apply()
    }
}
