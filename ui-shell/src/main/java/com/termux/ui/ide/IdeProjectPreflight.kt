package com.termux.ui.ide

import android.content.Context
import com.termux.ui.panel.TermuxCmdResult
import com.termux.ui.panel.TermuxCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object IdeProjectPreflight {

    data class PreflightPlan(
        val command: String,
        val workdir: String
    )

    fun buildPlan(context: Context, project: IdeProject): PreflightPlan {
        val root = File(project.rootDir)
        val workdir = root.absolutePath
        val cdDir = workdir
        val sb = StringBuilder()
        sb.appendLine("set -e")
        sb.appendLine("cd '${escapeSingleQuotes(cdDir)}'")
        sb.appendLine(". .termuxide/init.sh")
        sb.appendLine("echo \"project: ${escapeDoubleQuotes(project.name)}\"")
        sb.appendLine("echo \"root: ${escapeDoubleQuotes(workdir)}\"")
        sb.appendLine("echo \"cd: ${escapeDoubleQuotes(cdDir)}\"")
        sb.appendLine()

        return PreflightPlan(command = sb.toString().trimEnd(), workdir = workdir)
    }

    suspend fun run(context: Context, project: IdeProject): TermuxCmdResult {
        return withContext(Dispatchers.IO) {
            val plan = buildPlan(context, project)
            TermuxCommandRunner.runBash(context, plan.command)
        }
    }

    private fun escapeSingleQuotes(s: String): String = s.replace("'", "'\"'\"'")
    private fun escapeDoubleQuotes(s: String): String = s.replace("\"", "\\\"")
}
