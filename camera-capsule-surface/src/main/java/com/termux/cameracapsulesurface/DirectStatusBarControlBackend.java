package com.termux.cameracapsulesurface;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

final class DirectStatusBarControlBackend extends AbstractStatusBarShellBackend {

    @NonNull
    private final Context context;

    DirectStatusBarControlBackend(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    @NonNull
    @Override
    public String getName() {
        return "direct";
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    boolean hasElevatedAppPermission() {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            == PackageManager.PERMISSION_GRANTED;
    }

    @NonNull
    @Override
    protected String[] buildDisableCommand(@NonNull List<String> flags) {
        ArrayList<String> command = new ArrayList<>();
        command.add("/system/bin/cmd");
        command.add("statusbar");
        command.add("send-disable-flag");
        command.addAll(flags);
        return command.toArray(new String[0]);
    }

    @NonNull
    @Override
    protected String[] buildRestoreCommand() {
        return new String[]{"/system/bin/cmd", "statusbar", "send-disable-flag", "none"};
    }
}
