package com.termux.cameracapsulesurface;

import androidx.annotation.NonNull;

import java.util.List;

final class RootStatusBarControlBackend extends AbstractStatusBarShellBackend {

    @NonNull
    @Override
    public String getName() {
        return "root";
    }

    @Override
    public boolean isSupported() {
        SystemCommandResult result = commandExecutor.execute(new String[]{"su", "-c", "id"});
        return result.isSuccess();
    }

    @NonNull
    @Override
    protected String[] buildDisableCommand(@NonNull List<String> flags) {
        return new String[]{"su", "-c", joinDisableCommand(flags)};
    }

    @NonNull
    @Override
    protected String[] buildRestoreCommand() {
        return new String[]{"su", "-c", joinRestoreCommand()};
    }
}
