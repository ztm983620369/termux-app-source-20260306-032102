package org.fossify.filemanager.helpers

import android.content.Context
import android.os.Build
import com.termux.sessionsync.FileRootResolver
import com.termux.sessionsync.SavedSshProfileStore
import com.termux.sessionsync.SessionFileCoordinator
import com.termux.sessionsync.SftpProtocolManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale

object SessionSelfTestRunner {

    data class Report(
        val startedAtMs: Long,
        val finishedAtMs: Long,
        val totalChecks: Int,
        val passedChecks: Int,
        val failedChecks: Int,
        val content: String
    ) {
        val success: Boolean
            get() = failedChecks == 0
    }

    fun run(context: Context): Report {
        val startedAtMs = System.currentTimeMillis()
        val appContext = context.applicationContext
        val coordinator = SessionFileCoordinator.getInstance()
        val sftpManager = SftpProtocolManager.getInstance()

        var totalChecks = 0
        var passedChecks = 0
        var failedChecks = 0
        val checkLogs = ArrayList<String>()

        fun check(name: String, block: () -> Pair<Boolean, String>) {
            totalChecks++
            try {
                val result = block()
                if (result.first) {
                    passedChecks++
                    checkLogs.add("[PASS] $name")
                } else {
                    failedChecks++
                    checkLogs.add("[FAIL] $name")
                }
                val detail = result.second.trim()
                if (detail.isNotEmpty()) {
                    checkLogs.add("       $detail")
                }
            } catch (t: Throwable) {
                failedChecks++
                checkLogs.add("[FAIL] $name")
                checkLogs.add("       ${safeErrorText(t)}")
            }
        }

        coordinator.initialize(appContext)

        val termuxRoot = FileRootResolver.termuxPrivateRoot(appContext)
        val entries = SavedSshProfileStore.loadSessionEntries(appContext)
        val selectedKey = coordinator.getSelectedSessionKey(appContext)

        check("\u672c\u5730\u6839\u76ee\u5f55\u68c0\u67e5") {
            val rootFile = File(termuxRoot)
            val ok = rootFile.exists() && rootFile.isDirectory && rootFile.canRead()
            ok to "\u8def\u5f84=$termuxRoot, exists=${rootFile.exists()}, dir=${rootFile.isDirectory}, readable=${rootFile.canRead()}, writable=${rootFile.canWrite()}"
        }

        check("\u4f1a\u8bdd\u9009\u4e2d\u72b6\u6001\u89e3\u6790") {
            val resolve = coordinator.resolveSelectedRoot(appContext)
            val ok = resolve.success
            ok to "\u9009\u4e2d=${selectedKey ?: "__local__"}, mode=${resolve.mode}, root=${resolve.rootPath}, msg=${resolve.messageCn}"
        }

        check("\u670d\u52a1\u5668\u914d\u7f6e\u52a0\u8f7d") {
            val ids = LinkedHashSet<String>()
            val duplicateIds = ArrayList<String>()
            entries.forEach {
                if (!ids.add(it.id)) duplicateIds.add(it.id)
            }
            val ok = duplicateIds.isEmpty()
            ok to "\u603b\u6570=${entries.size}, \u91cd\u590dID=${duplicateIds.size}"
        }

        check("\u4e0b\u8f7d\u63a5\u53e3\u53c2\u6570\u6821\u9a8c") {
            val result = coordinator.downloadVirtualPaths(
                appContext,
                emptyList(),
                termuxRoot,
                null,
                null
            )
            (!result.success) to "success=${result.success}, msg=${result.messageCn}"
        }

        entries.forEach { entry ->
            val label = if (entry.displayName.isBlank()) entry.id else entry.displayName
            val safeCmd = sanitizeSshCommand(entry.sshCommand)
            check("\u670d\u52a1\u5668\u63a2\u6d3b: $label") {
                val probe = sftpManager.probeSession(appContext, entry)
                probe.success to "msg=${probe.messageCn}, cmd=$safeCmd"
            }

            check("\u6839\u76ee\u5f55\u5217\u8868: $label") {
                val virtualRoot = FileRootResolver.resolveVirtualRoot(appContext, entry)
                val list = coordinator.listVirtualPath(appContext, virtualRoot)
                val detail = if (list.success) {
                    "entries=${list.entries.size}, display=${list.displayPath}"
                } else {
                    "msg=${list.messageCn}"
                }
                list.success to detail
            }
        }

        val stateDump = runCatching { coordinator.dumpState(appContext) }.getOrElse { safeErrorText(it) }
        val traceDump = runCatching { coordinator.dumpRecentTrace(appContext, 200) }.getOrElse { safeErrorText(it) }
        val finishedAtMs = System.currentTimeMillis()

        val sb = StringBuilder(4096)
        sb.append("==== SESSION INDUSTRIAL SELF-TEST ====\n")
        sb.append("start: ").append(formatTime(startedAtMs)).append('\n')
        sb.append("end:   ").append(formatTime(finishedAtMs)).append('\n')
        sb.append("cost:  ").append(finishedAtMs - startedAtMs).append(" ms\n")
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("package: ").append(appContext.packageName).append('\n')
        sb.append("root: ").append(termuxRoot).append('\n')
        sb.append("selected: ").append(selectedKey ?: "__local__").append('\n')
        sb.append("servers: ").append(entries.size).append('\n')
        sb.append('\n')

        sb.append("summary: total=").append(totalChecks)
            .append(", pass=").append(passedChecks)
            .append(", fail=").append(failedChecks)
            .append('\n')
        sb.append('\n')

        checkLogs.forEach { line ->
            sb.append(line).append('\n')
        }
        sb.append('\n')
        sb.append("---- state_dump ----\n")
        sb.append(stateDump).append('\n')
        sb.append('\n')
        sb.append("---- trace_recent ----\n")
        sb.append(traceDump).append('\n')

        return Report(
            startedAtMs = startedAtMs,
            finishedAtMs = finishedAtMs,
            totalChecks = totalChecks,
            passedChecks = passedChecks,
            failedChecks = failedChecks,
            content = sb.toString()
        )
    }

    private fun sanitizeSshCommand(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var out = raw.orEmpty()
        out = out.replace(
            Regex("sshpass\\s+-p\\s+('[^']*'|\"[^\"]*\"|\\S+)", RegexOption.IGNORE_CASE),
            "sshpass -p ******"
        )
        out = out.replace(
            Regex("(?i)(password\\s*=\\s*)([^\\s]+)"),
            "$1******"
        )
        return out
    }

    private fun safeErrorText(t: Throwable): String {
        val name = t.javaClass.simpleName.ifBlank { "Throwable" }
        val message = t.message?.trim().orEmpty()
        return if (message.isEmpty()) name else "$name: $message"
    }

    private fun formatTime(ms: Long): String {
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))
        } catch (_: Exception) {
            ms.toString()
        }
    }
}
