package com.termux.ui.ide

import com.termux.ui.panel.ExecTarget
import java.util.Locale

enum class IdeTemplate(val id: String, val title: String) {
    CPP("cpp", "C++"),
    PYTHON("python", "Python"),
    NODE("node", "Node.js"),
    GO("go", "Go"),
    NOTE("note", "Note")
}

data class IdeRunConfig(
    val id: String,
    val name: String,
    val command: String,
    val workdir: String
)

data class IdeToolVersion(
    val toolId: String,
    val plugin: String,
    val version: String
)

data class IdeProject(
    val id: String,
    val name: String,
    val rootDir: String,
    val templateId: String,
    val target: ExecTarget,
    val toolVersions: List<IdeToolVersion>,
    val runConfigs: List<IdeRunConfig>,
    val runArgs: String,
    val pythonVenv: Boolean = false
)

object IdePathMapper {

    fun prootInternalPath(distro: String, hostPath: String): String? {
        val d = distro.trim().lowercase(Locale.ROOT)
        val p = hostPath.replace('\\', '/')
        val pLower = p.lowercase(Locale.ROOT)
        val marker = "/installed-rootfs/$d"
        val idx = pLower.indexOf(marker)
        if (idx < 0) return null
        val after = p.substring(idx + marker.length)
        return if (after.isBlank()) {
            "/"
        } else {
            if (after.startsWith('/')) after else "/$after"
        }
    }
}
