package com.termux.ui.panel

import android.content.Context
import android.content.SharedPreferences
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.shell.command.runner.app.AppShell
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class TermuxCmdResult(
    val command: String,
    val workingDirectory: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String
) {
    val ok: Boolean get() = (exitCode ?: 1) == 0
}

object TermuxCommandRunner {

    fun defaultWorkDir(): String = TermuxConstants.TERMUX_HOME_DIR_PATH

    fun bashPath(): String = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash").absolutePath

    fun runBash(
        context: Context,
        bashCommand: String,
        workingDirectory: String = defaultWorkDir()
    ): TermuxCmdResult {
        val bash = bashPath()
        if (!File(bash).exists()) {
            return TermuxCmdResult(
                command = bashCommand,
                workingDirectory = workingDirectory,
                exitCode = 1,
                stdout = "",
                stderr = "找不到 bash：$bash"
            )
        }

        val cmd = ExecutionCommand(0).apply {
            this.executable = bash
            this.arguments = arrayOf("-lc", bashCommand)
            this.workingDirectory = workingDirectory
            this.runner = ExecutionCommand.Runner.APP_SHELL.getName()
            this.commandLabel = "ui-panel-${UUID.randomUUID()}"
            this.setShellCommandShellEnvironment = true
        }

        val appShell = AppShell.execute(
            context,
            cmd,
            null,
            TermuxShellEnvironment(),
            null,
            true
        )

        val exit = appShell?.executionCommand?.resultData?.exitCode
        val out = appShell?.executionCommand?.resultData?.stdout?.toString()?.trimEnd().orEmpty()
        val err = appShell?.executionCommand?.resultData?.stderr?.toString()?.trimEnd().orEmpty()
        return TermuxCmdResult(
            command = bashCommand,
            workingDirectory = workingDirectory,
            exitCode = exit,
            stdout = out,
            stderr = err
        )
    }

    suspend fun runBashWithLineProgress(
        context: Context,
        bashCommand: String,
        workingDirectory: String = defaultWorkDir(),
        onLine: (String) -> Unit
    ): TermuxCmdResult {
        val bash = bashPath()
        if (!File(bash).exists()) {
            return TermuxCmdResult(
                command = bashCommand,
                workingDirectory = workingDirectory,
                exitCode = 1,
                stdout = "",
                stderr = "找不到 bash：$bash"
            )
        }

        val cmd = ExecutionCommand(0).apply {
            this.executable = bash
            this.arguments = arrayOf("-lc", bashCommand)
            this.workingDirectory = workingDirectory
            this.runner = ExecutionCommand.Runner.APP_SHELL.getName()
            this.commandLabel = "ui-panel-${UUID.randomUUID()}"
            this.setShellCommandShellEnvironment = true
        }

        val finished = AtomicBoolean(false)
        val appShell = AppShell.execute(
            context,
            cmd,
            object : AppShell.AppShellClient {
                override fun onAppShellExited(appShell: AppShell) {
                    finished.set(true)
                }
            },
            TermuxShellEnvironment(),
            null,
            false
        )

        if (appShell == null) {
            return TermuxCmdResult(
                command = bashCommand,
                workingDirectory = workingDirectory,
                exitCode = 1,
                stdout = "",
                stderr = "启动失败"
            )
        }

        var scannedOutLen = 0
        var scannedErrLen = 0
        var pendingOut = ""
        var pendingErr = ""
        var lastSignalAt = System.currentTimeMillis()
        while (!finished.get()) {
            val now = System.currentTimeMillis()
            var emitted = false
            val stdout = appShell.executionCommand.resultData.stdout.toString()
            if (stdout.length > scannedOutLen) {
                val delta = stdout.substring(scannedOutLen)
                scannedOutLen = stdout.length
                val text = (pendingOut + delta).replace('\r', '\n')
                val lines = text.split('\n')
                pendingOut = if (text.endsWith('\n')) "" else lines.lastOrNull().orEmpty()
                val completeLines = if (text.endsWith('\n')) lines else lines.dropLast(1)
                for (line in completeLines) {
                    val trimmed = line.trimEnd()
                    if (trimmed.isNotBlank()) onLine(trimmed)
                }
                emitted = true
            }

            val stderr = appShell.executionCommand.resultData.stderr.toString()
            if (stderr.length > scannedErrLen) {
                val delta = stderr.substring(scannedErrLen)
                scannedErrLen = stderr.length
                val text = (pendingErr + delta).replace('\r', '\n')
                val lines = text.split('\n')
                pendingErr = if (text.endsWith('\n')) "" else lines.lastOrNull().orEmpty()
                val completeLines = if (text.endsWith('\n')) lines else lines.dropLast(1)
                for (line in completeLines) {
                    val trimmed = line.trimEnd()
                    if (trimmed.isNotBlank()) onLine(trimmed)
                }
                emitted = true
            }

            if (emitted) {
                lastSignalAt = now
            } else if (now - lastSignalAt >= 5000) {
                onLine("（执行中…）")
                lastSignalAt = now
            }
            delay(200)
        }

        val exit = appShell.executionCommand.resultData.exitCode
        val out = appShell.executionCommand.resultData.stdout.toString().trimEnd()
        val err = appShell.executionCommand.resultData.stderr.toString().trimEnd()
        return TermuxCmdResult(
            command = bashCommand,
            workingDirectory = workingDirectory,
            exitCode = exit,
            stdout = out,
            stderr = err
        )
    }
}

sealed class ExecTarget {
    object Host : ExecTarget()
    data class Proot(val distro: String) : ExecTarget()
}

fun ExecTarget.wrapBashCommand(innerScript: String): String {
    return when (this) {
        ExecTarget.Host -> innerScript
        is ExecTarget.Proot -> ProotDistroManager.execInDistroCmd(distro, innerScript)
    }
}

fun ExecTarget.displayName(): String {
    return when (this) {
        ExecTarget.Host -> "宿主"
        is ExecTarget.Proot -> "PRoot:${distro}"
    }
}

object ProotPrefs {

    data class State(
        val enabled: Boolean,
        val defaultDistro: String
    )

    private const val PREFS = "ui_shell_proot"
    private const val KEY_ENABLED = "proot.enabled"
    private const val KEY_DEFAULT_DISTRO = "proot.default_distro"

    fun getState(context: Context): State {
        val p = prefs(context)
        return State(
            enabled = p.getBoolean(KEY_ENABLED, false),
            defaultDistro = p.getString(KEY_DEFAULT_DISTRO, "ubuntu")?.trim().orEmpty().ifBlank { "ubuntu" }
        )
    }

    fun setState(context: Context, state: State) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, state.enabled)
            .putString(KEY_DEFAULT_DISTRO, state.defaultDistro.trim().ifBlank { "ubuntu" })
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}

object ProotDistroManager {

    data class Snapshot(
        val prootDistroInstalled: Boolean,
        val installedDistros: List<String>,
        val message: String?
    )

    fun pkgInstallCmd(): String = "pkg install -y proot-distro"

    fun installDistroWithPrereqsCmd(distro: String): String {
        val d = distro.trim().ifBlank { "ubuntu" }
        return "pkg install -y proot-distro && proot-distro install $d"
    }

    fun prootDistroBinPath(): String {
        return File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "proot-distro").absolutePath
    }

    fun installedRootfsDir(distro: String): File {
        val d = distro.trim().lowercase()
        return File(TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH, "lib/proot-distro/installed-rootfs/$d")
    }

    fun installedRootHomeDir(distro: String): File {
        return File(installedRootfsDir(distro), "root")
    }

    fun execInDistroCmd(distro: String, innerScript: String): String {
        val d = distro.trim().ifBlank { "ubuntu" }
        val escaped = escapeSingleQuotes(innerScript)
        return "proot-distro login $d -- /usr/bin/bash -lc '$escaped'"
    }

    fun detect(context: Context): Snapshot {
        val bin = prootDistroBinPath()
        val exists = File(bin).exists()
        if (!exists) {
            return Snapshot(
                prootDistroInstalled = false,
                installedDistros = emptyList(),
                message = "未安装 proot-distro"
            )
        }

        val listCmd = "proot-distro list --installed 2>/dev/null || true"
        val r = TermuxCommandRunner.runBash(context, listCmd)
        val out = r.stdout.ifBlank { r.stderr }.trim()
        val distros = parseInstalledDistros(out)
        return Snapshot(
            prootDistroInstalled = true,
            installedDistros = distros,
            message = null
        )
    }

    private fun parseInstalledDistros(text: String): List<String> {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        val distros = ArrayList<String>()
        for (l in lines) {
            if (l.startsWith("#")) continue
            val token = l.substringBefore(' ').substringBefore('\t').trim()
            if (token.isNotBlank() && token.lowercase() == token) {
                distros.add(token)
            }
        }
        return distros.distinct()
    }

    private fun escapeSingleQuotes(text: String): String {
        return text.replace("'", "'\"'\"'")
    }
}
