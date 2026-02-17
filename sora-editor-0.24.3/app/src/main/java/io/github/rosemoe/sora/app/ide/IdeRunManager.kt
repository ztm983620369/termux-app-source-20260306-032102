package io.github.rosemoe.sora.app.ide

import android.content.Context
import org.json.JSONObject
import java.io.File

object IdeRunManager {

    data class ToolVersion(
        val toolId: String,
        val plugin: String,
        val version: String
    )

    data class ProjectConfig(
        val id: String,
        val name: String,
        val rootDir: String,
        val targetMode: String,
        val distro: String?,
        val command: String,
        val workdir: String,
        val runArgs: String,
        val toolVersions: List<ToolVersion>
    )

    fun findProjectConfig(filePath: String): ProjectConfig? {
        var dir = File(filePath).parentFile ?: return null
        for (i in 0..12) {
            val cfg = File(File(dir, ".termuxide"), "project.json")
            if (cfg.exists() && cfg.isFile) {
                return parseProjectConfig(cfg)
            }
            dir = dir.parentFile ?: break
        }
        return null
    }

    fun buildRunCommand(config: ProjectConfig): Pair<String, String> {
        val workdirAbsHost = File(File(config.rootDir), config.workdir).absolutePath
        val cdHost = "cd '${escapeSingleQuotes(workdirAbsHost)}' || exit ${'$'}?"
        val finalCommand = if (config.runArgs.isBlank()) config.command else "${config.command} ${config.runArgs}"
        val envPrefix = "export PATH=${'$'}HOME/.local/share/mise/shims:${'$'}HOME/.local/bin:${'$'}HOME/.local/share/mise/bin:${'$'}PATH"
        val miseLines = if (config.toolVersions.isNotEmpty()) {
            buildString {
                appendLine(envPrefix)
                appendLine("command -v mise >/dev/null 2>&1 || (echo \"mise not found\" 1>&2; exit 2)")
                for (tv in config.toolVersions) {
                    appendLine("mise use -g ${tv.plugin}@${tv.version}")
                }
                appendLine("mise reshim || true")
            }.trimEnd()
        } else {
            envPrefix
        }
        val cmd = """
            set +e
            ${miseLines}
            ${cdHost}
            echo "workdir: ${escapeSingleQuotes(workdirAbsHost)}"
            echo "run: ${escapeSingleQuotes(finalCommand)}"
            ${finalCommand}
            ec=${'$'}?
            echo "exit: ${'$'}ec"
            exec bash -i
        """.trimIndent()
        return if (config.targetMode == "proot") {
            val distro = config.distro?.ifBlank { "ubuntu" } ?: "ubuntu"
            val internalRoot = prootInternalPath(distro, config.rootDir) ?: "/root"
            val workdirInternal = joinInternal(internalRoot, config.workdir)
            val cd = "cd '${escapeSingleQuotes(workdirInternal)}' || exit ${'$'}?"
            val env = "export PATH=${'$'}HOME/.local/share/mise/shims:${'$'}HOME/.local/bin:${'$'}HOME/.local/share/mise/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            val inner = """
                export HOME=${'$'}{HOME:-/root}
                $env
                set +e
                ${miseLines}
                ${cd}
                echo "workdir: ${escapeSingleQuotes(workdirInternal)}"
                echo "run: ${escapeSingleQuotes(finalCommand)}"
                ${finalCommand}
                ec=${'$'}?
                echo "exit: ${'$'}ec"
                exec /usr/bin/bash -i
            """.trimIndent()
            val escaped = escapeSingleQuotes(inner)
            Pair("proot-distro login $distro -- /usr/bin/bash -lc '$escaped'", workdirAbsHost)
        } else {
            Pair(cmd, workdirAbsHost)
        }
    }

    fun launchRunInTerminal(context: Context, config: ProjectConfig) {
        val (bashCmd, workdir) = buildRunCommand(config)
        TermuxServiceExecLauncher.startTerminalSession(
            context = context,
            bashCommand = bashCmd,
            workdir = workdir,
            label = "run-${config.id}"
        )
    }

    fun launchCustomCommandInTerminal(
        context: Context,
        command: String,
        workdir: String,
        label: String
    ) {
        val bashCmd = buildCustomRunCommand(command = command, workdirAbsHost = workdir)
        TermuxServiceExecLauncher.startTerminalSession(
            context = context,
            bashCommand = bashCmd,
            workdir = workdir,
            label = label
        )
    }

    private fun parseProjectConfig(file: File): ProjectConfig? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val id = o.optString("id").ifBlank { file.parentFile?.parentFile?.name ?: "project" }
        val name = o.optString("name").ifBlank { id }
        val rootDir = o.optString("rootDir").ifBlank { file.parentFile?.parentFile?.absolutePath ?: return null }
        val target = o.optJSONObject("target")
        val mode = target?.optString("mode")?.ifBlank { "host" } ?: "host"
        val distro = target?.optString("distro")
        val toolVersionsArr = o.optJSONArray("toolVersions")
        val toolVersions = ArrayList<ToolVersion>()
        if (toolVersionsArr != null) {
            for (i in 0 until toolVersionsArr.length()) {
                val tv = toolVersionsArr.optJSONObject(i) ?: continue
                val toolId = tv.optString("toolId").trim()
                val plugin = tv.optString("plugin").trim()
                val ver = tv.optString("version").trim()
                if (toolId.isBlank() || plugin.isBlank() || ver.isBlank()) continue
                toolVersions.add(ToolVersion(toolId, plugin, ver))
            }
        }
        val runs = o.optJSONArray("runConfigs")
        val first = runs?.optJSONObject(0)
        val command = first?.optString("command")?.ifBlank { "ls -la" } ?: "ls -la"
        val workdir = first?.optString("workdir")?.ifBlank { "." } ?: "."
        val runArgs = o.optString("runArgs").orEmpty().trim()
        return ProjectConfig(
            id = id,
            name = name,
            rootDir = rootDir,
            targetMode = mode,
            distro = distro,
            command = command,
            workdir = workdir,
            runArgs = runArgs,
            toolVersions = toolVersions
        )
    }

    private fun escapeSingleQuotes(s: String): String {
        return s.replace("'", "'\"'\"'")
    }

    private fun buildCustomRunCommand(command: String, workdirAbsHost: String): String {
        val cdHost = "cd '${escapeSingleQuotes(workdirAbsHost)}' || exit ${'$'}?"
        val finalCommand = command.trim()
        val cmd = """
            set +e
            ${cdHost}
            echo "workdir: ${escapeSingleQuotes(workdirAbsHost)}"
            echo "run: ${escapeSingleQuotes(finalCommand)}"
            ${finalCommand}
            ec=${'$'}?
            echo "exit: ${'$'}ec"
            exec bash -i
        """.trimIndent()
        return cmd
    }

    private fun prootInternalPath(distro: String, hostPath: String): String? {
        val p = hostPath.replace('\\', '/')
        val baseA = "/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/$distro/rootfs"
        val baseB = "/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/$distro"
        val base = when {
            p.startsWith(baseA) -> baseA
            p.startsWith(baseB) -> baseB
            else -> return null
        }
        val rel = p.removePrefix(base).trimStart('/')
        if (rel.isBlank()) return "/"
        return "/$rel"
    }

    private fun joinInternal(base: String, child: String): String {
        val b = base.trimEnd('/')
        val c = child.trim().ifBlank { "." }
        return if (c == "." || c == "./") b.ifBlank { "/" } else "$b/${c.trimStart('/')}"
    }
}
