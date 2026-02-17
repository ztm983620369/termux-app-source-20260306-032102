package com.termux.ui.panel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.termux.TermuxConstants

object TermuxTerminalLauncher {

    fun startTerminalSession(
        context: Context,
        bashCommand: String,
        label: String
    ) {
        val bash = TermuxCommandRunner.bashPath()
        val uri = Uri.Builder()
            .scheme(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE)
            .path(bash)
            .build()

        val execIntent = Intent(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, uri).apply {
            setClassName(
                TermuxConstants.TERMUX_PACKAGE_NAME,
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE_NAME
            )
            putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_ARGUMENTS, arrayOf("-lc", bashCommand))
            putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_WORKDIR, TermuxConstants.TERMUX_HOME_DIR_PATH)
            putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RUNNER, ExecutionCommand.Runner.TERMINAL_SESSION.getName())
            putExtra(
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SESSION_ACTION,
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY.toString()
            )
            putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_COMMAND_LABEL, label)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(execIntent)
        } else {
            context.startService(execIntent)
        }
    }
}

