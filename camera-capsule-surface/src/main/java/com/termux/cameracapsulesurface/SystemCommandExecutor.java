package com.termux.cameracapsulesurface;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

final class SystemCommandExecutor {

    private static final long DEFAULT_TIMEOUT_MS = 4000L;

    @NonNull
    SystemCommandResult execute(@NonNull String[] command) {
        return execute(command, DEFAULT_TIMEOUT_MS);
    }

    @NonNull
    SystemCommandResult execute(@NonNull String[] command, long timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder().command(command).redirectErrorStream(true).start();
            String output = drain(process.getInputStream(), process, timeoutMs);
            boolean timedOut = false;
            Integer exitCode = null;
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            while (SystemClock.elapsedRealtime() < deadline) {
                try {
                    exitCode = process.exitValue();
                    break;
                } catch (IllegalThreadStateException ignored) {
                    SystemClock.sleep(30L);
                }
            }
            if (exitCode == null) {
                timedOut = true;
                process.destroy();
                SystemClock.sleep(50L);
                try {
                    exitCode = process.exitValue();
                } catch (IllegalThreadStateException ignored) {
                    process.destroyForcibly();
                    exitCode = 124;
                }
            }
            return new SystemCommandResult(exitCode, output.trim(), timedOut);
        } catch (Exception e) {
            return new SystemCommandResult(1, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), false);
        } finally {
            if (process != null) process.destroy();
        }
    }

    @NonNull
    private String drain(@Nullable InputStream inputStream, @NonNull Process process, long timeoutMs) {
        if (inputStream == null) return "";
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                while (inputStream.available() > 0) {
                    int read = inputStream.read(buffer);
                    if (read < 0) return outputStream.toString();
                    outputStream.write(buffer, 0, read);
                }
                try {
                    process.exitValue();
                    while (inputStream.available() > 0) {
                        int read = inputStream.read(buffer);
                        if (read < 0) break;
                        outputStream.write(buffer, 0, read);
                    }
                    break;
                } catch (IllegalThreadStateException ignored) {
                    SystemClock.sleep(20L);
                }
            }
        } catch (Exception ignored) {
        }
        return outputStream.toString();
    }
}
