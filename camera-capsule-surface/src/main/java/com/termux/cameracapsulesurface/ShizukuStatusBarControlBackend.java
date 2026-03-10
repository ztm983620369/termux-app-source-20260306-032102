package com.termux.cameracapsulesurface;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

final class ShizukuStatusBarControlBackend implements StatusBarControlBackend {

    @NonNull
    private final Context context;

    ShizukuStatusBarControlBackend(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    @NonNull
    @Override
    public String getName() {
        return "shizuku";
    }

    @Override
    public boolean isSupported() {
        return Shizuku.pingBinder();
    }

    boolean isPermissionGranted() {
        return isSupported() && Shizuku.checkSelfPermission() == PERMISSION_GRANTED;
    }

    @NonNull
    @Override
    public StatusBarControlResult apply(@NonNull StatusBarDisableSpec spec) {
        if (spec.isEmpty()) return StatusBarControlResult.success(getName(), "no disable flags requested");
        return execute(buildDisableCommand(spec.toDisableFlags()));
    }

    @NonNull
    @Override
    public StatusBarControlResult restore() {
        return execute(new String[]{"/system/bin/cmd", "statusbar", "send-disable-flag", "none"});
    }

    @NonNull
    private StatusBarControlResult execute(@NonNull String[] command) {
        if (!Shizuku.pingBinder()) {
            return StatusBarControlResult.failure(getName(), "binder unavailable");
        }
        if (Shizuku.checkSelfPermission() != PERMISSION_GRANTED) {
            return StatusBarControlResult.permissionRequired(getName(), "Shizuku permission required");
        }
        try {
            Method method = Shizuku.class.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
            method.setAccessible(true);
            Process process = (Process) method.invoke(null, command, null, null);
            String output = drain(process == null ? null : process.getInputStream());
            int exitCode = process == null ? 1 : process.waitFor();
            if (process != null) process.destroy();
            if (exitCode == 0) {
                return StatusBarControlResult.success(getName(), output.isEmpty() ? "ok" : output);
            }
            return StatusBarControlResult.failure(getName(),
                output.isEmpty() ? ("exit=" + exitCode) : output);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return StatusBarControlResult.failure(getName(), message);
        }
    }

    @NonNull
    private String[] buildDisableCommand(@NonNull List<String> flags) {
        ArrayList<String> command = new ArrayList<>();
        command.add("/system/bin/cmd");
        command.add("statusbar");
        command.add("send-disable-flag");
        command.addAll(flags);
        return command.toArray(new String[0]);
    }

    @NonNull
    private String drain(InputStream inputStream) {
        if (inputStream == null) return "";
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        try {
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
            }
        } catch (Exception ignored) {
        }
        return outputStream.toString().trim();
    }
}
