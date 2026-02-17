package com.termux.ui.selftest

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import com.termux.ui.ide.IdePathMapper
import com.termux.ui.panel.ExecTarget
import com.termux.ui.panel.EnvironmentManager
import com.termux.ui.panel.PanelSelfTest
import com.termux.ui.panel.ProotDistroManager
import com.termux.ui.panel.ProotPrefs
import com.termux.ui.panel.TermuxCmdResult
import com.termux.ui.panel.TermuxCommandRunner
import com.termux.ui.panel.wrapBashCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object FullChainSelfTest {

    data class Result(
        val ok: Boolean,
        val title: String,
        val detail: String,
        val logFilePath: String
    )

    suspend fun run(context: Context, onLine: (String) -> Unit): Result = withContext(Dispatchers.IO) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val tsFile = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val sb = StringBuilder()
        fun line(s: String) {
            sb.appendLine(s)
            onLine(s)
        }
        fun block(text: String) {
            for (l in text.split('\n')) {
                line(l.trimEnd())
            }
        }

        line("=== FULL CHAIN SELFTEST ===")
        line("time: $ts")
        line("termux.files: ${TermuxConstants.TERMUX_FILES_DIR_PATH}")
        line("termux.home: ${TermuxConstants.TERMUX_HOME_DIR_PATH}")
        line("termux.prefix: ${TermuxConstants.TERMUX_PREFIX_DIR_PATH}")
        line("termux.var: ${TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH}")
        line("")

        val hostRoot = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
        val hostWs = File(hostRoot, "workspace")
        hostWs.mkdirs()

        line("--- Host filesystem ---")
        line("home.exists: ${hostRoot.exists()}")
        line("workspace: ${hostWs.absolutePath}")
        line("workspace.exists: ${hostWs.exists()}")
        line("")

        line("--- Host selftest (panel) ---")
        val hostPanel = PanelSelfTest.run(context, ExecTarget.Host) { onLine(it) }
        block(hostPanel.detail.trimEnd())
        line("")

        line("--- Host tool detect (normalized) ---")
        for (tool in EnvironmentManager.tools) {
            val snap = EnvironmentManager.detectTool(context, ExecTarget.Host, tool)
            val v = snap.detectedVersion ?: "n/a"
            val p = snap.detectedPath ?: "-"
            val msg = snap.message ?: ""
            line("${tool.id}: ver=$v path=$p ${msg}".trimEnd())
        }
        line("")

        val prootState = ProotPrefs.getState(context)
        line("--- Proot prefs ---")
        line("enabled: ${prootState.enabled}")
        line("defaultDistro: ${prootState.defaultDistro}")
        line("")

        if (prootState.enabled) {
            val prootSnap = ProotDistroManager.detect(context)
            line("--- Proot-distro detect ---")
            line("proot-distro.installed: ${prootSnap.prootDistroInstalled}")
            if (!prootSnap.prootDistroInstalled) {
                line("message: ${prootSnap.message ?: "unknown"}")
                line("fix: ${ProotDistroManager.pkgInstallCmd()}")
                line("")
            } else {
                line("installed.distros: ${prootSnap.installedDistros.joinToString(", ").ifBlank { "(none)" }}")
                line("")
            }

            if (!prootSnap.prootDistroInstalled) {
                line("[ERROR] proot enabled but proot-distro missing, skip proot checks")
                line("")
            } else if (!prootSnap.installedDistros.contains(prootState.defaultDistro.lowercase(Locale.ROOT))) {
                line("[ERROR] proot enabled but distro not installed: ${prootState.defaultDistro}")
                line("fix: ${ProotDistroManager.installDistroWithPrereqsCmd(prootState.defaultDistro)}")
                line("")
            } else {
                val distro = prootState.defaultDistro
                val rootfsDir = ProotDistroManager.installedRootfsDir(distro)
                val rootHome = ProotDistroManager.installedRootHomeDir(distro)
                val prootWs = File(rootHome, "workspace")
                prootWs.mkdirs()

                line("--- Proot filesystem ---")
                line("rootfs.dir: ${rootfsDir.absolutePath}")
                line("rootfs.exists: ${rootfsDir.exists()}")
                line("rootHome: ${rootHome.absolutePath}")
                line("rootHome.exists: ${rootHome.exists()}")
                line("workspace: ${prootWs.absolutePath}")
                line("workspace.exists: ${prootWs.exists()}")
                line("")

                line("--- Proot path mapping ---")
                val internalRoot = IdePathMapper.prootInternalPath(distro, rootfsDir.absolutePath) ?: "n/a"
                val internalWs = IdePathMapper.prootInternalPath(distro, prootWs.absolutePath) ?: "n/a"
                line("internal.rootfs: $internalRoot")
                line("internal.workspace: $internalWs")
                line("")

                line("--- Proot selftest (panel) ---")
                val prootPanel = PanelSelfTest.run(context, ExecTarget.Proot(distro)) { onLine(it) }
                block(prootPanel.detail.trimEnd())
                line("")

                line("--- Proot tool detect (normalized) ---")
                for (tool in EnvironmentManager.tools) {
                    val snap = EnvironmentManager.detectTool(context, ExecTarget.Proot(distro), tool)
                    val v = snap.detectedVersion ?: "n/a"
                    val p = snap.detectedPath ?: "-"
                    val msg = snap.message ?: ""
                    line("${tool.id}: ver=$v path=$p ${msg}".trimEnd())
                }
                line("")

                line("--- Proot exec sanity ---")
                val sanityScript = """
                    set +e
                    echo "pwd: ${'$'}(pwd)"
                    echo "whoami: ${'$'}(whoami 2>/dev/null || true)"
                    echo "home: ${'$'}HOME"
                    echo "ls /:"
                    ls -la / 2>&1 | head -n 40
                    echo
                    echo "ls /root:"
                    ls -la /root 2>&1 | head -n 40
                    echo
                    echo "[OK] proot exec sanity"
                """.trimIndent()
                val sanity = TermuxCommandRunner.runBash(context, ExecTarget.Proot(distro).wrapBashCommand(sanityScript))
                block(renderCmd("proot.exec.sanity", sanity))
                line("")
            }
        }

        line("--- Project template sanity ---")
        val probeDir = File(hostWs, "selftest-" + UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        File(probeDir, "main.py").writeText("print('selftest')\n")
        File(File(probeDir, ".termuxide").apply { mkdirs() }, "project.json").writeText(
            """
            {
              "version": 1,
              "id": "${UUID.randomUUID()}",
              "name": "selftest",
              "rootDir": "${probeDir.absolutePath.replace("\\", "\\\\")}",
              "templateId": "python",
              "target": { "mode": "host" },
              "toolVersions": [],
              "runArgs": "",
              "runConfigs": [ { "id": "run", "name": "Run", "command": "python3 main.py", "workdir": "." } ]
            }
            """.trimIndent() + "\n"
        )
        val probeRun = TermuxCommandRunner.runBash(
            context,
            "cd '${probeDir.absolutePath.replace("'", "'\"'\"'")}' && python3 main.py 2>&1 || true"
        )
        block(renderCmd("template.run.host", probeRun))
        line("")

        val ok = sb.toString().contains("[OK]")
        val logDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "workspace/.termuxide/selftests").apply { mkdirs() }
        val logFile = File(logDir, "fullchain-$tsFile.log")
        logFile.writeText(sb.toString().trimEnd() + "\n")
        Result(
            ok = ok,
            title = if (ok) "全链路自测通过" else "全链路自测发现问题",
            detail = sb.toString().trimEnd(),
            logFilePath = logFile.absolutePath
        )
    }

    private fun renderCmd(title: String, r: TermuxCmdResult): String {
        val out = r.stdout.trimEnd()
        val err = r.stderr.trimEnd()
        return buildString {
            appendLine("=== $title ===")
            appendLine("exit: ${r.exitCode}")
            if (out.isNotBlank()) {
                appendLine("--- stdout ---")
                appendLine(out)
            }
            if (err.isNotBlank()) {
                appendLine("--- stderr ---")
                appendLine(err)
            }
            appendLine("=== end ===")
        }.trimEnd()
    }
}
