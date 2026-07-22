package com.tencent.shadow.sample.host;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** Fixed same-UID contract between the Shadow control receiver and TermuxService. */
public final class ShadowBuildWorkerContract {

    public static final String TERMUX_SERVICE_CLASS = "com.termux.app.TermuxService";
    public static final String ACTION_ENSURE = "com.termux.shadow.worker.ENSURE";
    public static final String ACTION_QUERY = "com.termux.shadow.worker.QUERY";
    public static final String ACTION_STOP = "com.termux.shadow.worker.STOP";

    private static final int MAX_STATE_BYTES = 64 * 1024;

    private ShadowBuildWorkerContract() {
    }

    public static String actionForMethod(String method) {
        if ("ensure-worker".equals(method)) {
            return ACTION_ENSURE;
        }
        if ("query-worker".equals(method)) {
            return ACTION_QUERY;
        }
        if ("stop-worker".equals(method)) {
            return ACTION_STOP;
        }
        return null;
    }

    public static void requestSupervisor(Context context, String action) {
        if (!ACTION_ENSURE.equals(action)
                && !ACTION_QUERY.equals(action)
                && !ACTION_STOP.equals(action)) {
            throw new IllegalArgumentException("Unknown Worker supervisor action: " + action);
        }
        Intent service = new Intent(action);
        service.setClassName(context.getPackageName(), TERMUX_SERVICE_CLASS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }

    public static String stateSummary(Context context) {
        File state = new File(
                context.getFilesDir(),
                "home/.termux-shadow/worker/state.json"
        );
        if (!state.isFile()) {
            return "worker state is not present";
        }
        try {
            byte[] bytes = readBounded(state);
            JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            return "status=" + value.optString("status", "UNKNOWN")
                    + " pid=" + value.optInt("pid", 0)
                    + " protocol=" + value.optInt("protocolVersion", 0)
                    + " requests=" + value.optLong("requestsServed", 0)
                    + " daemon=" + value.optString("gradleDaemon", "UNKNOWN");
        } catch (Exception error) {
            return "worker state is unreadable: " + error.getClass().getSimpleName();
        }
    }

    private static byte[] readBounded(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_STATE_BYTES) {
                    throw new IllegalStateException("Worker state exceeds size limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }
}
