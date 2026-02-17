package com.termux.ui.files

import androidx.compose.ui.graphics.Color

object FileTypeHighlight {

    private val byFileName: Map<String, Color> = linkedMapOf(
        "build.gradle" to Color(0xFF8BC34A),
        "settings.gradle" to Color(0xFF8BC34A),
        "gradle.properties" to Color(0xFF8BC34A),
        "gradlew" to Color(0xFF8BC34A),
        "gradlew.bat" to Color(0xFF8BC34A),
        "androidmanifest.xml" to Color(0xFF4CAF50),
        "readme.md" to Color(0xFF42A5F5),
        "license" to Color(0xFF90A4AE),
        "license.md" to Color(0xFF90A4AE)
    )

    private val byExtension: Map<String, Color> = linkedMapOf(
        "kt" to Color(0xFFB39DDB),
        "kts" to Color(0xFFD1C4E9),
        "java" to Color(0xFFFFB74D),
        "xml" to Color(0xFF80DEEA),
        "gradle" to Color(0xFF8BC34A),
        "properties" to Color(0xFFAED581),
        "json" to Color(0xFFFFF59D),
        "yml" to Color(0xFF90CAF9),
        "yaml" to Color(0xFF90CAF9),
        "md" to Color(0xFF64B5F6),
        "txt" to Color(0xFFB0BEC5),
        "png" to Color(0xFFF48FB1),
        "jpg" to Color(0xFFF48FB1),
        "jpeg" to Color(0xFFF48FB1),
        "svg" to Color(0xFFF48FB1),
        "js" to Color(0xFFFFEE58),
        "ts" to Color(0xFF64B5F6),
        "tsx" to Color(0xFF64B5F6),
        "css" to Color(0xFF81C784),
        "scss" to Color(0xFFCE93D8),
        "html" to Color(0xFFFF8A65),
        "sh" to Color(0xFFB0BEC5),
        "c" to Color(0xFF90CAF9),
        "cpp" to Color(0xFF90CAF9),
        "h" to Color(0xFF90CAF9),
        "hpp" to Color(0xFF90CAF9)
    )

    private val defaultFileAccent = Color(0xFF9AA3B2)

    fun accentForFileName(name: String): Color {
        val lower = name.lowercase()
        byFileName[lower]?.let { return it }

        val parts = lower.split('.')
        if (parts.size <= 1) return defaultFileAccent

        for (i in 1 until parts.size) {
            val ext = parts.subList(i, parts.size).joinToString(".")
            byExtension[ext]?.let { return it }
        }

        return defaultFileAccent
    }
}

