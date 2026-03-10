package com.termux.cameracapsulesurface;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

abstract class AbstractStatusBarShellBackend implements StatusBarControlBackend {

    @NonNull
    protected final SystemCommandExecutor commandExecutor = new SystemCommandExecutor();

    @NonNull
    @Override
    public StatusBarControlResult apply(@NonNull StatusBarDisableSpec spec) {
        if (spec.isEmpty()) return StatusBarControlResult.success(getName(), "no disable flags requested");
        return execute(buildDisableCommand(spec.toDisableFlags()));
    }

    @NonNull
    @Override
    public StatusBarControlResult restore() {
        return execute(buildRestoreCommand());
    }

    @NonNull
    protected abstract String[] buildDisableCommand(@NonNull List<String> flags);

    @NonNull
    protected abstract String[] buildRestoreCommand();

    @NonNull
    private StatusBarControlResult execute(@NonNull String[] command) {
        SystemCommandResult result = commandExecutor.execute(command);
        if (result.isSuccess()) {
            return StatusBarControlResult.success(getName(),
                result.output.isEmpty() ? "ok" : result.output);
        }
        if (result.timedOut) {
            return StatusBarControlResult.failure(getName(), "command timed out");
        }
        return StatusBarControlResult.failure(getName(),
            result.output.isEmpty() ? ("exit=" + result.exitCode) : result.output);
    }

    @NonNull
    protected String joinDisableCommand(@NonNull List<String> flags) {
        ArrayList<String> parts = new ArrayList<>();
        parts.add("/system/bin/cmd");
        parts.add("statusbar");
        parts.add("send-disable-flag");
        parts.addAll(flags);
        return join(parts);
    }

    @NonNull
    protected String joinRestoreCommand() {
        return "/system/bin/cmd statusbar send-disable-flag none";
    }

    @NonNull
    protected String join(@NonNull List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
