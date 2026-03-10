package com.termux.terminalsessionruntime;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.File;

public final class AppShellCommandExecutor {

    @NonNull
    public ShellCommandResult execute(@NonNull Context context, @NonNull String shellCommand) {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bashPath).exists()) {
            return new ShellCommandResult(1, "", "bash not found");
        }

        ExecutionCommand executionCommand = new ExecutionCommand(-1, bashPath,
            new String[]{"-lc", shellCommand},
            null, TermuxConstants.TERMUX_HOME_DIR_PATH,
            ExecutionCommand.Runner.APP_SHELL.getName(), false);
        executionCommand.commandLabel = "SSH Persistence Runtime";
        executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_OFF;
        executionCommand.setShellCommandShellEnvironment = true;

        AppShell appShell = AppShell.execute(context, executionCommand, null, new TermuxShellEnvironment(), null, true);
        if (appShell == null) {
            return new ShellCommandResult(1, "", "failed to start shell command");
        }

        Integer exitCode = executionCommand.resultData.exitCode;
        return new ShellCommandResult(exitCode == null ? 1 : exitCode,
            executionCommand.resultData.stdout.toString().trim(),
            executionCommand.resultData.stderr.toString().trim());
    }
}
