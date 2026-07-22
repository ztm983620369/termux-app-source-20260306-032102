package com.tencent.shadow.sample.host.platform;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ShadowExitInfoCollector {

    private static final String TAG = "ShadowExitInfo";
    private static final int MAX_EXIT_RECORDS = 32;
    private static final int MAX_TRACE_BYTES = 1024 * 1024;
    private static final int MAX_CONTEXT_BYTES = 64 * 1024;

    private ShadowExitInfoCollector() {
    }

    public static void collect(Context context, ShadowPaths paths) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return;
            }
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(
                    context.getPackageName(),
                    0,
                    MAX_EXIT_RECORDS
            );
            JSONObject launchContext = readLaunchContext(paths.launchContextFile());
            for (ApplicationExitInfo exit : exits) {
                persistExit(paths, exit, launchContext);
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Failed to collect historical process exits", throwable);
        }
    }

    private static void persistExit(
            ShadowPaths paths,
            ApplicationExitInfo exit,
            JSONObject launchContext
    ) throws Exception {
        String identity = exit.getTimestamp() + "-pid" + exit.getPid()
                + "-r" + exit.getReason();
        File reportFile = new File(paths.processExitReportsDir(), identity + ".json");
        if (reportFile.isFile()) {
            return;
        }

        JSONObject report = new JSONObject();
        report.put("schemaVersion", 1);
        report.put("timestamp", exit.getTimestamp());
        report.put("pid", exit.getPid());
        report.put("realUid", exit.getRealUid());
        report.put("processName", exit.getProcessName());
        report.put("reason", exit.getReason());
        report.put("reasonName", reasonName(exit.getReason()));
        report.put("status", exit.getStatus());
        report.put("importance", exit.getImportance());
        report.put("pssKb", exit.getPss());
        report.put("rssKb", exit.getRss());
        putIfPresent(report, "description", bounded(exit.getDescription(), 8192));

        if (launchContext != null
                && exit.getProcessName() != null
                && exit.getProcessName().contains(":")) {
            long launchedAt = launchContext.optLong("updatedAt", 0L);
            if (launchedAt <= exit.getTimestamp()) {
                report.put("launchContext", launchContext);
            }
        }

        File traceFile = persistTrace(paths, identity, exit.getTraceInputStream());
        if (traceFile != null) {
            report.put("traceFile", traceFile.getAbsolutePath());
        }
        ShadowFileOps.writeAtomically(
                reportFile,
                report.toString(2).getBytes(StandardCharsets.UTF_8),
                false
        );

        ShadowEventLogger logger = ShadowEventLogger.get();
        if (logger != null) {
            String pluginId = launchContext == null ? null : launchContext.optString("pluginId", null);
            String generation = launchContext == null ? null : launchContext.optString("generation", null);
            logger.warn("PROCESS_EXIT_RECORDED", null, pluginId, generation,
                    exit.getProcessName() + " reason=" + reasonName(exit.getReason()), null);
        }
    }

    private static File persistTrace(ShadowPaths paths, String identity, InputStream input) {
        if (input == null) {
            return null;
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_TRACE_BYTES) {
                    output.write("\n[trace truncated]\n".getBytes(StandardCharsets.UTF_8));
                    break;
                }
                output.write(buffer, 0, read);
            }
            File target = new File(paths.crashDir(), identity + ".trace");
            ShadowFileOps.writeAtomically(target, output.toByteArray(), false);
            return target;
        } catch (Throwable throwable) {
            Log.w(TAG, "Failed to persist process exit trace", throwable);
            return null;
        } finally {
            try {
                input.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static JSONObject readLaunchContext(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            return new JSONObject(new String(
                    ShadowFileOps.readBounded(file, MAX_CONTEXT_BYTES),
                    StandardCharsets.UTF_8
            ));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_ANR:
                return "ANR";
            case ApplicationExitInfo.REASON_CRASH:
                return "JAVA_CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "NATIVE_CRASH";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_EXIT_SELF:
                return "EXIT_SELF";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "SIGNALED";
            case ApplicationExitInfo.REASON_USER_REQUESTED:
                return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED:
                return "USER_STOPPED";
            default:
                return "REASON_" + reason;
        }
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...[truncated]";
    }

    private static void putIfPresent(JSONObject object, String key, String value) throws Exception {
        if (value != null && value.length() > 0) {
            object.put(key, value);
        }
    }
}
