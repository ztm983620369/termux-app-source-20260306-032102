package com.tencent.shadow.sample.host;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.tencent.shadow.dynamic.host.EnterCallback;
import com.tencent.shadow.dynamic.host.PluginManager;
import com.tencent.shadow.sample.constant.Constant;
import com.tencent.shadow.sample.host.platform.ShadowLaunchPlan;
import com.tencent.shadow.sample.host.platform.ShadowRuntimeHealth;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledFuture;
import java.util.UUID;

public class PluginLoadActivity extends Activity {

    private static final String TAG = "PluginLoadActivity";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean terminalReported = new AtomicBoolean(false);
    private final AtomicBoolean readyReported = new AtomicBoolean(false);
    private final AtomicBoolean leaseReleased = new AtomicBoolean(false);

    private ViewGroup viewGroup;
    private TextView statusView;
    private volatile ShadowLaunchPlan launchPlan;
    private volatile ScheduledFuture<?> watchdogFuture;
    private String launchPluginId;
    private String launchLeaseId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_load);
        viewGroup = findViewById(R.id.container);
        statusView = findViewById(R.id.load_status);

        String pluginId = getIntent().getStringExtra(Constant.KEY_PLUGIN_ID);
        if (pluginId == null || pluginId.trim().length() == 0) {
            Toast.makeText(this, "缺少 pluginId", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        launchPluginId = pluginId;
        String requestedLease = getIntent().getStringExtra(Constant.KEY_LAUNCH_LEASE_ID);
        if (requestedLease != null) {
            if (!PluginHelper.getInstance().claimLaunch(pluginId, requestedLease)) {
                Log.w(TAG, "Ignoring stale or unowned launch Activity for " + pluginId);
                finish();
                return;
            }
            launchLeaseId = requestedLease;
        } else {
            launchLeaseId = "ui-" + UUID.randomUUID();
            try {
                PluginHelper.getInstance().acquireLaunch(pluginId, launchLeaseId);
                if (!PluginHelper.getInstance().claimLaunch(pluginId, launchLeaseId)) {
                    throw new IllegalStateException("failed to claim UI launch lease");
                }
                getIntent().putExtra(Constant.KEY_LAUNCH_LEASE_ID, launchLeaseId);
            } catch (Throwable throwable) {
                Toast.makeText(this, rootMessage(throwable), Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        statusView.setText("正在准备插件运行世代\n\npluginId = " + pluginId);
        startPlugin(pluginId, getIntent().getBooleanExtra(Constant.KEY_ROLLBACK, false));
    }

    private void startPlugin(final String pluginId, final boolean rollback) {
        PluginHelper.getInstance().executor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    launchPlan = rollback
                            ? PluginHelper.getInstance().prepareRollback(pluginId)
                            : PluginHelper.getInstance().prepareLaunch(pluginId);
                    postPlanStatus(launchPlan, rollback);
                    armWatchdog(launchPlan);

                    HostApplication.getApp().loadPluginManager(
                            launchPlan.managerApk,
                            launchPlan.runtimeFingerprint
                    );
                    PluginManager pluginManager = HostApplication.getApp().getPluginManager();
                    if (pluginManager == null) {
                        throw new IllegalStateException("Shadow plugin manager is not available");
                    }

                    Bundle bundle = new Bundle();
                    bundle.putString(Constant.KEY_PLUGIN_ZIP_PATH, launchPlan.pluginPackage.getAbsolutePath());
                    bundle.putString(Constant.KEY_PLUGIN_ID, launchPlan.pluginId);
                    bundle.putString(Constant.KEY_PLUGIN_GENERATION, launchPlan.generation);
                    bundle.putString(Constant.KEY_OPERATION_ID, launchPlan.operationId);
                    bundle.putString(Constant.KEY_PLUGIN_PART_KEY, launchPlan.partKey);
                    bundle.putString(Constant.KEY_ACTIVITY_CLASSNAME, launchPlan.activityClassName);
                    bundle.putInt(
                            Constant.KEY_HEALTH_PROTOCOL_VERSION,
                            Constant.HEALTH_PROTOCOL_VERSION
                    );
                    bundle.putLong(
                            Constant.KEY_HEALTH_STABILITY_WINDOW_MS,
                            launchPlan.stabilityWindowMs
                    );
                    bundle.putParcelable(Constant.KEY_RESULT_RECEIVER, createResultReceiver());

                    pluginManager.enter(
                            PluginLoadActivity.this,
                            Constant.FROM_ID_START_ACTIVITY,
                            bundle,
                            new EnterCallback() {
                                @Override
                                public void onShowLoadingView(final View view) {
                                    handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (!isFinishing() && viewGroup != null) {
                                                if (view.getParent() instanceof ViewGroup) {
                                                    ((ViewGroup) view.getParent()).removeView(view);
                                                }
                                                viewGroup.addView(view);
                                            }
                                        }
                                    });
                                }

                                @Override
                                public void onCloseLoadingView() {
                                    handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (viewGroup != null) {
                                                viewGroup.removeAllViews();
                                            }
                                            if (!isFinishing() && statusView != null) {
                                                statusView.setText(
                                                        "启动请求已提交，等待插件首帧与稳定性证明"
                                                );
                                            }
                                        }
                                    });
                                }

                                @Override
                                public void onEnterComplete() {
                                    Log.i(TAG, "Shadow entry accepted; runtime proof is still pending");
                                }
                            }
                    );
                } catch (final Throwable throwable) {
                    if (terminalReported.compareAndSet(false, true)) {
                        cancelWatchdog();
                        reportFailure(throwable);
                    }
                    Log.e(TAG, "Failed to enter Shadow plugin", throwable);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) {
                                return;
                            }
                            Toast.makeText(
                                    PluginLoadActivity.this,
                                    "Shadow 插件启动失败：" + rootMessage(throwable),
                                    Toast.LENGTH_LONG
                            ).show();
                            finish();
                        }
                    });
                }
            }
        });
    }

    private ResultReceiver createResultReceiver() {
        return new ResultReceiver(handler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (resultCode == Constant.RESULT_CODE_RUNTIME_READY) {
                    handleRuntimeReady(resultData, false);
                    return;
                }
                if (resultCode == Constant.RESULT_CODE_RUNTIME_STABLE) {
                    handleRuntimeReady(resultData, true);
                    return;
                }
                if (resultCode != Constant.RESULT_CODE_START_ERROR
                        && resultCode != Constant.RESULT_CODE_PLUGIN_PROCESS_DIED) {
                    return;
                }
                if (!terminalReported.compareAndSet(false, true)) {
                    return;
                }
                cancelWatchdog();
                String type = resultData == null
                        ? "ShadowManagerError"
                        : resultData.getString(Constant.KEY_ERROR_TYPE, "ShadowManagerError");
                String message = resultData == null
                        ? "Shadow manager failed without details"
                        : resultData.getString(Constant.KEY_ERROR_MESSAGE, "Shadow manager failed");
                String stack = resultData == null
                        ? null
                        : resultData.getString(Constant.KEY_ERROR_STACK_TRACE);
                String detail = type + ": " + message;
                if (stack != null && stack.length() > 0) {
                    detail += "\n" + bounded(stack, 32 * 1024);
                }
                IllegalStateException failure = new IllegalStateException(detail);
                reportFailure(failure);
                showFailureAndFinish(message);
            }
        };
    }

    private void handleRuntimeReady(Bundle resultData, boolean stable) {
        if (terminalReported.get()) {
            return;
        }
        final ShadowRuntimeHealth health;
        try {
            health = parseRuntimeHealth(resultData, stable);
        } catch (Throwable throwable) {
            if (terminalReported.compareAndSet(false, true)) {
                cancelWatchdog();
                reportFailure(throwable);
                showFailureAndFinish(rootMessage(throwable));
            }
            return;
        }

        if (!stable) {
            if (!readyReported.compareAndSet(false, true)) {
                return;
            }
            PluginHelper.getInstance().executor().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        PluginHelper.getInstance().markLaunchReady(launchPlan, health);
                    } catch (Throwable throwable) {
                        Log.e(TAG, "Failed to persist first-frame readiness", throwable);
                    }
                }
            });
            if (statusView != null) {
                statusView.setText("插件首帧已完成，正在通过稳定窗口");
            }
            return;
        }

        if (!readyReported.get()) {
            IllegalStateException protocolError = new IllegalStateException(
                    "Runtime STABLE proof arrived before FIRST_FRAME_READY"
            );
            if (terminalReported.compareAndSet(false, true)) {
                cancelWatchdog();
                reportFailure(protocolError);
                showFailureAndFinish(protocolError.getMessage());
            }
            return;
        }
        if (!terminalReported.compareAndSet(false, true)) {
            return;
        }
        cancelWatchdog();
        PluginHelper.getInstance().executor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    PluginHelper.getInstance().markLaunchHealthy(launchPlan, health);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing()) {
                                finish();
                            }
                        }
                    });
                } catch (Throwable throwable) {
                    Log.e(TAG, "Failed to commit Shadow runtime health", throwable);
                    try {
                        PluginHelper.getInstance().markLaunchFailed(launchPlan, throwable);
                    } catch (Throwable reportError) {
                        Log.e(TAG, "Failed to persist health-commit failure", reportError);
                    }
                    showFailureAndFinish(rootMessage(throwable));
                } finally {
                    releaseLaunchLease();
                }
            }
        });
    }

    private ShadowRuntimeHealth parseRuntimeHealth(Bundle data, boolean stable) {
        if (data == null) {
            throw new IllegalStateException("Runtime health proof is empty");
        }
        ShadowLaunchPlan plan = launchPlan;
        if (plan == null) {
            throw new IllegalStateException("Runtime health proof has no launch plan");
        }
        requireCorrelation(Constant.KEY_PLUGIN_ID, plan.pluginId, data);
        requireCorrelation(Constant.KEY_PLUGIN_GENERATION, plan.generation, data);
        requireCorrelation(Constant.KEY_OPERATION_ID, plan.operationId, data);
        int protocol = data.getInt(Constant.KEY_HEALTH_PROTOCOL_VERSION, 0);
        long firstFrameElapsedMs = data.getLong(Constant.KEY_FIRST_FRAME_ELAPSED_MS, 0L);
        long stableElapsedMs = stable
                ? data.getLong(Constant.KEY_STABLE_ELAPSED_MS, 0L)
                : 0L;
        return new ShadowRuntimeHealth(
                protocol,
                firstFrameElapsedMs,
                stableElapsedMs,
                data.getInt(Constant.KEY_PLUGIN_PROCESS_PID, 0),
                data.getString(Constant.KEY_PLUGIN_PROCESS_NAME)
        );
    }

    private static void requireCorrelation(String key, String expected, Bundle data) {
        String actual = data.getString(key);
        if (!expected.equals(actual)) {
            throw new SecurityException(
                    "Runtime health correlation mismatch for " + key
            );
        }
    }

    private void armWatchdog(final ShadowLaunchPlan plan) {
        watchdogFuture = PluginHelper.getInstance().scheduleWatchdog(new Runnable() {
            @Override
            public void run() {
                if (!terminalReported.compareAndSet(false, true)) {
                    return;
                }
                IllegalStateException timeout = new IllegalStateException(
                        "Shadow launch health timeout after " + plan.healthTimeoutMs + "ms"
                );
                reportFailure(timeout);
                showFailureAndFinish("插件启动健康检查超时");
            }
        }, plan.healthTimeoutMs);
    }

    private void cancelWatchdog() {
        ScheduledFuture<?> future = watchdogFuture;
        if (future != null) {
            future.cancel(false);
            watchdogFuture = null;
        }
    }

    private void showFailureAndFinish(final String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(
                        PluginLoadActivity.this,
                        "Shadow 插件启动失败：" + message,
                        Toast.LENGTH_LONG
                ).show();
                finish();
            }
        });
    }

    private void reportFailure(final Throwable throwable) {
        final ShadowLaunchPlan plan = launchPlan;
        if (plan == null) {
            releaseLaunchLease();
            return;
        }
        PluginHelper.getInstance().executor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    PluginHelper.getInstance().markLaunchFailed(plan, throwable);
                    HostApplication.getApp().resetPluginRuntime(
                            "Plugin launch failed: " + plan.pluginId + "/" + plan.generation
                    );
                } catch (Throwable reportError) {
                    Log.e(TAG, "Failed to persist Shadow launch failure", reportError);
                } finally {
                    releaseLaunchLease();
                }
            }
        });
    }

    private void releaseLaunchLease() {
        if (leaseReleased.compareAndSet(false, true)) {
            PluginHelper.getInstance().releaseLaunch(launchPluginId, launchLeaseId);
        }
    }

    private void postPlanStatus(final ShadowLaunchPlan plan, final boolean rollback) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || statusView == null) {
                    return;
                }
                statusView.setText((rollback ? "正在回滚并启动插件" : "正在启动插件")
                        + "\n\npluginId = " + plan.pluginId
                        + "\ngeneration = " + plan.generation
                        + "\npartKey = " + plan.partKey
                        + "\noperationId = " + plan.operationId);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (viewGroup != null) {
            viewGroup.removeAllViews();
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.length() == 0
                ? current.getClass().getSimpleName()
                : message;
    }

    private static String bounded(String value, int maxLength) {
        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength) + "...[truncated]";
    }
}
