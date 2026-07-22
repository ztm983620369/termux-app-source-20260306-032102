package com.tencent.shadow.sample.host.platform;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

public final class ShadowCrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "ShadowCrashHandler";
    private static final int MAX_MESSAGE_LENGTH = 8 * 1024;
    private static final int MAX_STACK_TRACE_LENGTH = 256 * 1024;

    private final ShadowPaths paths;
    private final ShadowEventLogger logger;
    private final Thread.UncaughtExceptionHandler delegate;

    private ShadowCrashHandler(
            ShadowPaths paths,
            ShadowEventLogger logger,
            Thread.UncaughtExceptionHandler delegate
    ) {
        this.paths = paths;
        this.logger = logger;
        this.delegate = delegate;
    }

    public static synchronized void install(Context context, ShadowPaths paths) throws Exception {
        ShadowEventLogger logger = ShadowEventLogger.initialize(context, paths);
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof ShadowCrashHandler) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(new ShadowCrashHandler(paths, logger, current));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            JSONObject launchContext = readLaunchContext();
            String pluginId = launchContext == null
                    ? null
                    : launchContext.optString("pluginId", null);
            String generation = launchContext == null
                    ? null
                    : launchContext.optString("generation", null);
            logger.error(
                    "PROCESS_CRASH",
                    launchContext == null ? null : launchContext.optString("operationId", null),
                    pluginId,
                    generation,
                    "thread=" + thread.getName(),
                    throwable
            );
            long epochMs = System.currentTimeMillis();
            JSONObject report = buildReport(
                    thread,
                    throwable,
                    launchContext,
                    epochMs,
                    Process.myPid()
            );
            byte[] reportBytes = report.toString(2).getBytes(StandardCharsets.UTF_8);
            File target = new File(
                    paths.crashDir(),
                    epochMs + "-pid" + Process.myPid() + ".json"
            );
            ShadowFileOps.writeAtomically(target, reportBytes, false);
            String operationId = report.optString("operationId", null);
            if (operationId != null && operationId.length() > 0) {
                ShadowFileOps.writeAtomically(
                        paths.runtimeCrashReportFile(operationId),
                        reportBytes,
                        false
                );
            }
        } catch (Throwable reportError) {
            Log.e(TAG, "Failed to persist Shadow crash report", reportError);
        }

        if (delegate != null) {
            delegate.uncaughtException(thread, throwable);
        } else {
            Process.killProcess(Process.myPid());
            System.exit(10);
        }
    }

    static JSONObject buildReport(
            Thread thread,
            Throwable throwable,
            JSONObject launchContext,
            long epochMs,
            int pid
    ) throws Exception {
        JSONObject report = new JSONObject();
        report.put("schemaVersion", 2);
        report.put("epochMs", epochMs);
        report.put("pid", pid);
        report.put("thread", thread.getName());
        report.put("errorType", throwable.getClass().getName());
        putIfPresent(report, "message", sanitizeAndBound(
                throwable.getMessage(),
                MAX_MESSAGE_LENGTH
        ));
        report.put("stackTrace", sanitizeAndBound(
                stackTrace(throwable),
                MAX_STACK_TRACE_LENGTH
        ));
        if (launchContext != null) {
            report.put("launchContext", launchContext);
            copyCorrelation(report, launchContext, "operationId");
            copyCorrelation(report, launchContext, "pluginId");
            copyCorrelation(report, launchContext, "generation");
            copyCorrelation(report, launchContext, "activityClassName");
        }
        return report;
    }

    private static void copyCorrelation(
            JSONObject target,
            JSONObject launchContext,
            String key
    ) throws Exception {
        putIfPresent(target, key, launchContext.optString(key, null));
    }

    private static void putIfPresent(JSONObject target, String key, String value) throws Exception {
        if (value != null && value.length() > 0) {
            target.put(key, value);
        }
    }

    static String sanitizeAndBound(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String sanitized = value
                .replaceAll(
                        "(?i)(password|passwd|token|secret|authorization)=([^\\s&]+)",
                        "$1=<redacted>"
                )
                .replaceAll(
                        "(?i)(bearer)\\s+[A-Za-z0-9._~+/=-]+",
                        "$1 <redacted>"
                );
        return sanitized.length() > maxLength
                ? sanitized.substring(0, maxLength) + "...[truncated]"
                : sanitized;
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        throwable.printStackTrace(writer);
        writer.flush();
        return buffer.toString();
    }

    private JSONObject readLaunchContext() {
        File file = paths.launchContextFile();
        if (!file.isFile()) {
            return null;
        }
        try {
            return new JSONObject(new String(
                    ShadowFileOps.readBounded(file, 64 * 1024),
                    StandardCharsets.UTF_8
            ));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
