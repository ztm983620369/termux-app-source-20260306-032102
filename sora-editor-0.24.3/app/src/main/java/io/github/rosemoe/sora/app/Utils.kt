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
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 ******************************************************************************/

package io.github.rosemoe.sora.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.rosemoe.sora.langs.monarch.MonarchLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.monarch.MonarchColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula

fun switchThemeIfRequired(context: Context, editor: CodeEditor) {
    if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
        when (editor.editorLanguage) {
            is TextMateLanguage -> {
                if (editor.colorScheme !is TextMateColorScheme) {
                    editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                }
                ThemeRegistry.getInstance().setTheme("ayu-dark")
            }

            is MonarchLanguage -> {
                if (editor.colorScheme !is MonarchColorScheme) {
                    editor.colorScheme =
                        MonarchColorScheme.create(io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.currentTheme)
                }
                io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme("ayu-dark")
            }

            else -> {
                editor.colorScheme = SchemeDarcula()
            }
        }
    } else {
        when (editor.editorLanguage) {
            is TextMateLanguage -> {
                if (editor.colorScheme !is TextMateColorScheme) {
                    editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                }
                ThemeRegistry.getInstance().setTheme("quietlight")
            }

            is MonarchLanguage -> {
                if (editor.colorScheme !is MonarchColorScheme) {
                    editor.colorScheme =
                        MonarchColorScheme.create(io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.currentTheme)
                }
                io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme("quietlight")
            }

            else -> {
                editor.colorScheme = EditorColorScheme()
            }
        }
    }
    editor.invalidate()
}

inline fun <reified T : Activity> Context.startActivity() {
    startActivity(Intent(this, T::class.java))
}

/**
 * Adjust the top padding of view to the height of status bar due to edge-to-edge since API 35
 */
fun applyEdgeToEdgeForViews(paddingView: View, rootView: View) {
    if (SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        val pvLeft = paddingView.paddingLeft
        val pvTop = paddingView.paddingTop
        val pvRight = paddingView.paddingRight
        val pvBottom = paddingView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(paddingView) { v, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(pvLeft, pvTop + statusBar.top, pvRight, pvBottom)
            insets
        }
    }
}
