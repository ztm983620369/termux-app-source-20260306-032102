package com.tencent.shadow.sample.host.platform;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class ShadowEventLogger {

    private static final String TAG = "ShadowPlatform";
    private static final long MAX_LOG_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_ARCHIVES = 5;
    private static final int MAX_MESSAGE_LENGTH = 32 * 1024;

    private static ShadowEventLogger instance;

    private final File logFile;
    private final File auditFile;
    private final String processName;

    private ShadowEventLogger(Context context, ShadowPaths paths) throws IOException {
        processName = currentProcessName(context);
        boolean pluginProcess = processName.contains(":plugin") || processName.contains(":shadow");
        File processDir = pluginProcess ? paths.pluginLogsDir() : paths.hostLogsDir();
        ShadowFileOps.ensurePrivateDirectory(processDir);
        String processSegment = ShadowFileOps.safeSegment(processName);
        logFile = new File(processDir, processSegment + ".jsonl");
        auditFile = new File(paths.auditLogsDir(), processSegment + ".jsonl");
    }

    public static synchronized ShadowEventLogger initialize(Context context, ShadowPaths paths)
            throws IOException {
        if (instance == null) {
            instance = new ShadowEventLogger(context.getApplicationContext(), paths);
        }
        return instance;
    }

    public static synchronized ShadowEventLogger get() {
        return instance;
    }

    public synchronized void info(String event, String operationId, String pluginId,
                                  String generation, String message) {
        write("INFO", event, operationId, pluginId, generation, message, null, false);
    }

    public synchronized void warn(String event, String operationId, String pluginId,
                                  String generation, String message, Throwable throwable) {
        write("WARN", event, operationId, pluginId, generation, message, throwable, false);
    }

    public synchronized void error(String event, String operationId, String pluginId,
                                   String generation, String message, Throwable throwable) {
        write("ERROR", event, operationId, pluginId, generation, message, throwable, false);
    }

    public synchronized void audit(String event, String operationId, String pluginId,
                                   String generation, String message) {
        write("AUDIT", event, operationId, pluginId, generation, message, null, true);
    }

    public synchronized void framework(String level, String loggerName, String message,
                                       Throwable throwable) {
        write(level, "SHADOW_FRAMEWORK", null, null, null,
                loggerName + ": " + message, throwable, false);
    }

    private void write(String level, String event, String operationId, String pluginId,
                       String generation, String message, Throwable throwable, boolean audit) {
        JSONObject object = new JSONObject();
        try {
            object.put("schemaVersion", 1);
            object.put("timestamp", utcTimestamp());
            object.put("epochMs", System.currentTimeMillis());
            object.put("elapsedMs", SystemClock.elapsedRealtime());
            object.put("level", level);
            object.put("event", event);
            object.put("process", processName);
            object.put("pid", Process.myPid());
            object.put("tid", Process.myTid());
            putIfPresent(object, "operationId", operationId);
            putIfPresent(object, "pluginId", pluginId);
            putIfPresent(object, "generation", generation);
            putIfPresent(object, "message", sanitize(message));
            if (throwable != null) {
                object.put("errorType", throwable.getClass().getName());
                object.put("stackTrace", sanitize(Log.getStackTraceString(throwable)));
            }
            append(audit ? auditFile : logFile, object.toString() + "\n");
        } catch (Throwable writeError) {
            Log.e(TAG, "Failed to persist Shadow event " + event, writeError);
        }

        String line = event + (message == null ? "" : ": " + message);
        if ("ERROR".equals(level)) {
            Log.e(TAG, line, throwable);
        } else if ("WARN".equals(level)) {
            Log.w(TAG, line, throwable);
        } else {
            Log.i(TAG, line);
        }
    }

    private void append(File target, String line) throws IOException {
        rotateIfNeeded(target, line.length());
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(target, true);
            output.write(line.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } finally {
            if (output != null) {
                output.close();
            }
        }
    }

    private void rotateIfNeeded(File target, int incomingBytes) throws IOException {
        if (!target.exists() || target.length() + incomingBytes <= MAX_LOG_BYTES) {
            return;
        }
        for (int index = MAX_ARCHIVES - 1; index >= 1; index--) {
            File source = new File(target.getParentFile(), target.getName() + "." + index);
            if (!source.exists()) {
                continue;
            }
            File destination = new File(target.getParentFile(), target.getName() + "." + (index + 1));
            if (destination.exists() && !destination.delete()) {
                throw new IOException("Failed to delete old log archive: " + destination);
            }
            if (!source.renameTo(destination)) {
                throw new IOException("Failed to rotate log archive: " + source);
            }
        }
        File first = new File(target.getParentFile(), target.getName() + ".1");
        if (first.exists() && !first.delete()) {
            throw new IOException("Failed to replace log archive: " + first);
        }
        if (!target.renameTo(first)) {
            throw new IOException("Failed to rotate log: " + target);
        }
    }

    private static String currentProcessName(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName();
        }
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            for (ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses()) {
                if (process != null && process.pid == Process.myPid()) {
                    return process.processName;
                }
            }
        }
        return context.getPackageName();
    }

    private static String utcTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static void putIfPresent(JSONObject object, String key, String value) throws Exception {
        if (value != null && value.length() > 0) {
            object.put(key, value);
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = value
                .replaceAll("(?i)(password|passwd|token|secret|authorization)=([^\\s&]+)", "$1=<redacted>")
                .replaceAll("(?i)(bearer)\\s+[A-Za-z0-9._~+/=-]+", "$1 <redacted>");
        return sanitized.length() > MAX_MESSAGE_LENGTH
                ? sanitized.substring(0, MAX_MESSAGE_LENGTH) + "...[truncated]"
                : sanitized;
    }
}
