package com.tencent.shadow.sample.host;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.tencent.shadow.sample.constant.Constant;
import com.tencent.shadow.sample.host.platform.ShadowPluginDescriptor;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Non-exported, same-UID control endpoint for the native Termux Shadow CLI.
 *
 * <p>Every mutation stays on {@link PluginHelper}'s serialized executor. The receiver writes only
 * bounded operator response reports; it never edits registry or managed package storage.</p>
 */
public final class ShadowControlReceiver extends BroadcastReceiver {

    public static final String ACTION_CONTROL = "com.termux.shadow.CONTROL";
    public static final String EXTRA_METHOD = "method";
    public static final String EXTRA_PLUGIN_ID = "pluginId";
    public static final String EXTRA_REQUEST_ID = "requestId";

    private static final int MAX_CONTROL_REPORTS = 64;
    private static final long LAUNCH_ADMISSION_TIMEOUT_MS = 10_000L;
    private static final String TAG = "ShadowControlReceiver";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION_CONTROL.equals(intent.getAction())) {
            return;
        }
        ComponentName component = intent.getComponent();
        if (component == null || !context.getPackageName().equals(component.getPackageName())) {
            return;
        }
        final String method = intent.getStringExtra(EXTRA_METHOD);
        final String pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID);
        final String requestId = intent.getStringExtra(EXTRA_REQUEST_ID);
        if (!isSafeRequestId(requestId)) {
            return;
        }

        final PendingResult pendingResult = goAsync();
        PluginHelper.getInstance().init(context);
        PluginHelper.getInstance().executor().execute(new Runnable() {
            @Override
            public void run() {
                String status = "OK";
                String message;
                try {
                    message = execute(context, method, pluginId, requestId);
                } catch (Throwable throwable) {
                    status = "ERROR";
                    message = rootMessage(throwable);
                }
                try {
                    writeResponse(context, requestId, method, pluginId, status, message);
                } catch (Throwable ignored) {
                    // The CLI times out with a direct report-path error if even the response fails.
                } finally {
                    pendingResult.finish();
                }
            }
        });
    }

    private static String execute(
            Context context,
            String method,
            String pluginId,
            String requestId
    ) throws Exception {
        if (method == null || method.trim().length() == 0) {
            throw new IllegalArgumentException("control method is required");
        }
        if ("ping".equals(method)) {
            return "Termux Shadow control is ready";
        }
        if ("refresh".equals(method)) {
            int count = PluginHelper.getInstance().refresh().size();
            return "registry refreshed; descriptors=" + count;
        }
        String workerAction = ShadowBuildWorkerContract.actionForMethod(method);
        if (workerAction != null) {
            ShadowBuildWorkerContract.requestSupervisor(context, workerAction);
            if (ShadowBuildWorkerContract.ACTION_QUERY.equals(workerAction)) {
                return ShadowBuildWorkerContract.stateSummary(context);
            }
            return method + " accepted; " + ShadowBuildWorkerContract.stateSummary(context);
        }
        pluginId = requirePluginId(method, pluginId);
        if ("run".equals(method) || "rollback".equals(method)) {
            validateLaunchRequest(method, pluginId);
            String leaseId = "control-" + requestId;
            PluginHelper.getInstance().acquireLaunch(pluginId, leaseId);
            Intent launch = new Intent(context, PluginLoadActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launch.putExtra(Constant.KEY_PLUGIN_ID, pluginId);
            launch.putExtra(Constant.KEY_ROLLBACK, "rollback".equals(method));
            launch.putExtra(Constant.KEY_LAUNCH_LEASE_ID, leaseId);
            try {
                sendUserInitiatedLaunch(context, launch, requestId);
                armLaunchAdmissionWatchdog(pluginId, leaseId);
            } catch (Exception throwable) {
                PluginHelper.getInstance().releaseLaunch(pluginId, leaseId);
                throw throwable;
            }
            return method + " accepted for " + pluginId;
        }
        if ("disable".equals(method)) {
            HostApplication.getApp().resetPluginRuntime("CLI disabled plugin: " + pluginId);
            PluginHelper.getInstance().disable(pluginId);
            return "disabled " + pluginId;
        }
        if ("enable".equals(method)) {
            PluginHelper.getInstance().enable(pluginId);
            return "enabled " + pluginId;
        }
        if ("delete".equals(method)) {
            HostApplication.getApp().resetPluginRuntime("CLI removed plugin: " + pluginId);
            PluginHelper.getInstance().remove(pluginId);
            return "removed all managed versions for " + pluginId;
        }
        throw new IllegalArgumentException("unknown control method: " + method);
    }

    private static void sendUserInitiatedLaunch(
            Context context,
            Intent launch,
            String requestId
    ) throws PendingIntent.CanceledException {
        launch.setAction(ACTION_CONTROL + ".LAUNCH." + requestId);
        int flags = PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        android.os.Bundle creatorOptions = null;
        android.os.Bundle senderOptions = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            int mode = Build.VERSION.SDK_INT >= 36
                    ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
            ActivityOptions creator = ActivityOptions.makeBasic();
            creator.setPendingIntentCreatorBackgroundActivityStartMode(mode);
            creatorOptions = creator.toBundle();
            ActivityOptions sender = ActivityOptions.makeBasic();
            sender.setPendingIntentBackgroundActivityStartMode(mode);
            senderOptions = sender.toBundle();
        }
        PendingIntent pending = PendingIntent.getActivity(
                context,
                requestId.hashCode(),
                launch,
                flags,
                creatorOptions
        );
        pending.send(context, 0, null, null, null, null, senderOptions);
    }

    private static void armLaunchAdmissionWatchdog(
            final String pluginId,
            final String leaseId
    ) {
        PluginHelper.getInstance().scheduleWatchdog(new Runnable() {
            @Override
            public void run() {
                if (PluginHelper.getInstance().releaseUnclaimedLaunch(pluginId, leaseId)) {
                    Log.e(TAG, "Launch Activity did not claim lease within "
                            + LAUNCH_ADMISSION_TIMEOUT_MS + "ms: " + pluginId);
                }
            }
        }, LAUNCH_ADMISSION_TIMEOUT_MS);
    }

    private static void writeResponse(
            Context context,
            String requestId,
            String method,
            String pluginId,
            String status,
            String message
    ) throws Exception {
        File directory = new File(
                context.getFilesDir(),
                "home/.termux-shadow/reports/control"
        );
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("failed to create control report directory");
        }
        directory.setReadable(false, false);
        directory.setWritable(false, false);
        directory.setExecutable(false, false);
        directory.setReadable(true, true);
        directory.setWritable(true, true);
        directory.setExecutable(true, true);

        JSONObject response = new JSONObject();
        response.put("schemaVersion", 1);
        response.put("requestId", requestId);
        response.put("method", method == null ? JSONObject.NULL : method);
        response.put("pluginId", pluginId == null ? JSONObject.NULL : pluginId);
        response.put("status", status);
        response.put("message", message);
        response.put("completedAt", System.currentTimeMillis());
        byte[] bytes = (response.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);

        File target = new File(directory, requestId + ".json");
        File temporary = new File(directory, "." + requestId + ".tmp");
        FileOutputStream output = new FileOutputStream(temporary, false);
        try {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        } finally {
            output.close();
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IllegalStateException("failed to commit control response");
        }
        target.setReadable(false, false);
        target.setWritable(false, false);
        target.setExecutable(false, false);
        target.setReadable(true, true);
        target.setWritable(true, true);
        trimReports(directory);
    }

    private static void trimReports(File directory) {
        File[] reports = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (reports == null || reports.length <= MAX_CONTROL_REPORTS) {
            return;
        }
        Arrays.sort(reports, Comparator.comparingLong(File::lastModified).reversed());
        for (int index = MAX_CONTROL_REPORTS; index < reports.length; index++) {
            reports[index].delete();
        }
    }

    private static boolean isSafeRequestId(String requestId) {
        return requestId != null
                && requestId.length() >= 8
                && requestId.length() <= 96
                && requestId.matches("[A-Za-z0-9._-]+");
    }

    private static String requirePluginId(String method, String pluginId) {
        if (pluginId == null || pluginId.trim().length() == 0) {
            throw new IllegalArgumentException("pluginId is required for " + method);
        }
        return pluginId.trim();
    }

    private static void validateLaunchRequest(String method, String pluginId) throws Exception {
        List<ShadowPluginDescriptor> descriptors = PluginHelper.getInstance().refresh();
        for (ShadowPluginDescriptor descriptor : descriptors) {
            if (!pluginId.equals(descriptor.pluginId)) {
                continue;
            }
            if (!descriptor.enabled) {
                throw new IllegalStateException("Plugin is disabled: " + pluginId);
            }
            if ("rollback".equals(method) && !descriptor.rollbackAvailable) {
                throw new IllegalStateException(
                        "Plugin has no retained rollback generation: " + pluginId
                );
            }
            return;
        }
        throw new IllegalArgumentException("Unknown Shadow plugin: " + pluginId);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.length() == 0) {
            message = current.getClass().getSimpleName();
        }
        return message.length() <= 1024 ? message : message.substring(0, 1024) + "...[truncated]";
    }
}
