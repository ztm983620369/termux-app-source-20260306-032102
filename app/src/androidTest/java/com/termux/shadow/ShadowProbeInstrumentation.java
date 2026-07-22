package com.termux.shadow;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.View;

import com.tencent.shadow.dynamic.host.EnterCallback;
import com.tencent.shadow.dynamic.host.PluginManager;
import com.tencent.shadow.sample.constant.Constant;
import com.tencent.shadow.sample.host.HostApplication;
import com.tencent.shadow.sample.host.PluginHelper;
import com.tencent.shadow.sample.host.platform.ShadowLaunchPlan;
import com.tencent.shadow.sample.host.platform.ShadowPaths;
import com.tencent.shadow.sample.host.platform.ShadowPluginDescriptor;
import com.tencent.shadow.sample.host.platform.ShadowRuntimeHealth;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ShadowProbeInstrumentation extends Instrumentation {

    private static final String TAG = "ShadowDeviceProbe";
    private static final String PLUGIN_ID = "com.termux.shadow.basic";

    private Bundle arguments;
    private PluginHelper helper;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : arguments;
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            setUpPlatform();
            String action = arguments.getString("action", "baseline");
            if ("baseline".equals(action)) {
                baselineLifecycle();
            } else if ("upgrade".equals(action)) {
                upgradeAndRollback();
            } else if ("reconcile".equals(action)) {
                reconcileStorage();
            } else if ("activate".equals(action)) {
                activateCandidate();
            } else if ("launch".equals(action)) {
                launchActive();
            } else {
                throw new IllegalArgumentException("Unknown Shadow probe action: " + action);
            }
            result.putString("stream", "Shadow device probe passed: " + action + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable throwable) {
            Log.e(TAG, "Shadow device probe failed", throwable);
            result.putString("stream", "Shadow device probe failed: " + rootMessage(throwable) + "\n");
            result.putString("stack", Log.getStackTraceString(throwable));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void setUpPlatform() throws Exception {
        HostApplication.init((android.app.Application) getTargetContext().getApplicationContext());
        helper = PluginHelper.getInstance();
        control(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                helper.refresh();
                return null;
            }
        });
    }

    private void baselineLifecycle() throws Exception {
        require(plugins().size() == 1, "Expected one registered plugin");
        launch(false);

        HostApplication.getApp().resetPluginRuntime("device probe disable");
        control(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                helper.disable(PLUGIN_ID);
                return null;
            }
        });
        require(!plugin().enabled, "Plugin did not enter disabled state");

        control(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                helper.enable(PLUGIN_ID);
                return null;
            }
        });
        require(plugin().enabled, "Plugin did not re-enter enabled state");
        launch(false);

        ShadowPaths paths = new ShadowPaths(getTargetContext());
        File archivedPackage = newestPackage(paths.inboxArchiveDir());
        require(archivedPackage != null, "No archived package available for reinstall");
        HostApplication.getApp().resetPluginRuntime("device probe remove");
        control(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                helper.remove(PLUGIN_ID);
                return null;
            }
        });
        require(plugins().isEmpty(), "Plugin remained registered after removal");

        copyAndSync(archivedPackage, new File(paths.inboxDir(), "device-probe-restore.shadowpkg"));
        control(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                helper.refresh();
                return null;
            }
        });
        require(plugins().size() == 1, "Plugin reinstall did not register exactly one plugin");
        launch(false);
        require("HEALTHY".equals(plugin().state.name()), "Reinstalled plugin is not healthy");
    }

    private void upgradeAndRollback() throws Exception {
        ShadowPluginDescriptor candidate = plugin();
        require(candidate.candidate, "Expected an imported upgrade candidate");
        String upgradeGeneration = candidate.generation;
        launch(false);

        ShadowPluginDescriptor upgraded = plugin();
        require(upgradeGeneration.equals(upgraded.generation), "Upgrade generation did not activate");
        require(upgraded.rollbackAvailable, "No rollback generation after upgrade");

        launch(true);
        ShadowPluginDescriptor rolledBack = plugin();
        require(!upgradeGeneration.equals(rolledBack.generation), "Rollback kept upgrade generation active");
        require("HEALTHY".equals(rolledBack.state.name()), "Rolled-back generation is not healthy");
        require(rolledBack.rollbackAvailable, "Rollback did not preserve the replaced generation");
    }

    private void reconcileStorage() throws Exception {
        ShadowPluginDescriptor descriptor = plugin();
        require(descriptor.packageFile != null && descriptor.packageFile.isFile(),
                "Runtime package was not repaired");
        require(!"FAILED".equals(descriptor.state.name()),
                "Storage reconciliation left the generation failed");
    }

    private void activateCandidate() throws Exception {
        ShadowPluginDescriptor candidate = plugin();
        require(candidate.candidate, "Expected a deployment candidate");
        String generation = candidate.generation;
        launch(false);
        ShadowPluginDescriptor active = plugin();
        require(generation.equals(active.generation), "Candidate did not become active");
        require("HEALTHY".equals(active.state.name()), "Activated candidate is not healthy");
    }

    private void launchActive() throws Exception {
        ShadowPluginDescriptor before = plugin();
        require(!before.candidate, "Expected an established active generation");
        String generation = before.generation;
        launch(false);
        ShadowPluginDescriptor active = plugin();
        require(generation.equals(active.generation), "Active generation changed during launch probe");
        require("HEALTHY".equals(active.state.name()), "Active generation is not healthy");
    }

    private void launch(final boolean rollback) throws Exception {
        final ShadowLaunchPlan plan = control(new Callable<ShadowLaunchPlan>() {
            @Override
            public ShadowLaunchPlan call() throws Exception {
                return rollback
                        ? helper.prepareRollback(PLUGIN_ID)
                        : helper.prepareLaunch(PLUGIN_ID);
            }
        });
        HostApplication.getApp().loadPluginManager(plan.managerApk, plan.runtimeFingerprint);
        PluginManager manager = HostApplication.getApp().getPluginManager();
        require(manager != null, "Shadow manager is unavailable");

        final CountDownLatch terminal = new CountDownLatch(1);
        final AtomicBoolean entered = new AtomicBoolean(false);
        final AtomicReference<String> error = new AtomicReference<>();
        final AtomicReference<ShadowRuntimeHealth> readyHealth = new AtomicReference<>();
        final AtomicReference<ShadowRuntimeHealth> stableHealth = new AtomicReference<>();
        Bundle parameters = new Bundle();
        parameters.putString(Constant.KEY_PLUGIN_ZIP_PATH, plan.pluginPackage.getAbsolutePath());
        parameters.putString(Constant.KEY_PLUGIN_ID, plan.pluginId);
        parameters.putString(Constant.KEY_PLUGIN_GENERATION, plan.generation);
        parameters.putString(Constant.KEY_OPERATION_ID, plan.operationId);
        parameters.putString(Constant.KEY_PLUGIN_PART_KEY, plan.partKey);
        parameters.putString(Constant.KEY_ACTIVITY_CLASSNAME, plan.activityClassName);
        parameters.putInt(Constant.KEY_HEALTH_PROTOCOL_VERSION, Constant.HEALTH_PROTOCOL_VERSION);
        parameters.putLong(Constant.KEY_HEALTH_STABILITY_WINDOW_MS, plan.stabilityWindowMs);
        parameters.putParcelable(Constant.KEY_RESULT_RECEIVER, new ResultReceiver(
                new Handler(Looper.getMainLooper())
        ) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (resultCode == Constant.RESULT_CODE_RUNTIME_READY) {
                    readyHealth.compareAndSet(null, runtimeHealth(resultData, false));
                } else if (resultCode == Constant.RESULT_CODE_RUNTIME_STABLE) {
                    stableHealth.compareAndSet(null, runtimeHealth(resultData, true));
                    terminal.countDown();
                } else if (resultCode == Constant.RESULT_CODE_START_ERROR
                        || resultCode == Constant.RESULT_CODE_PLUGIN_PROCESS_DIED) {
                    error.compareAndSet(null, resultData == null
                            ? "Shadow manager failed"
                            : resultData.getString(Constant.KEY_ERROR_MESSAGE));
                    terminal.countDown();
                }
            }
        });

        manager.enter(
                getTargetContext(),
                Constant.FROM_ID_START_ACTIVITY,
                parameters,
                new EnterCallback() {
                    @Override
                    public void onShowLoadingView(View view) {
                    }

                    @Override
                    public void onCloseLoadingView() {
                    }

                    @Override
                    public void onEnterComplete() {
                        entered.set(true);
                    }
                }
        );

        require(terminal.await(plan.healthTimeoutMs + 10_000L, TimeUnit.MILLISECONDS),
                "Shadow launch timed out");
        if (error.get() != null) {
            final IllegalStateException failure = new IllegalStateException(error.get());
            control(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    helper.markLaunchFailed(plan, failure);
                    return null;
                }
            });
            throw failure;
        }
        require(entered.get(), "Shadow manager did not report enter completion");
        require(readyHealth.get() != null, "Runtime did not report FIRST_FRAME_READY");
        require(stableHealth.get() != null, "Runtime did not report STABLE");
        control(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                helper.markLaunchReady(plan, readyHealth.get());
                helper.markLaunchHealthy(plan, stableHealth.get());
                return null;
            }
        });
    }

    private static ShadowRuntimeHealth runtimeHealth(Bundle data, boolean stable) {
        if (data == null) {
            throw new IllegalStateException("Runtime health proof is empty");
        }
        return new ShadowRuntimeHealth(
                data.getInt(Constant.KEY_HEALTH_PROTOCOL_VERSION, 0),
                data.getLong(Constant.KEY_FIRST_FRAME_ELAPSED_MS, 0L),
                stable ? data.getLong(Constant.KEY_STABLE_ELAPSED_MS, 0L) : 0L,
                data.getInt(Constant.KEY_PLUGIN_PROCESS_PID, 0),
                data.getString(Constant.KEY_PLUGIN_PROCESS_NAME)
        );
    }

    private ShadowPluginDescriptor plugin() throws Exception {
        List<ShadowPluginDescriptor> plugins = plugins();
        require(plugins.size() == 1, "Expected exactly one registered plugin");
        return plugins.get(0);
    }

    private List<ShadowPluginDescriptor> plugins() throws Exception {
        return control(new Callable<List<ShadowPluginDescriptor>>() {
            @Override
            public List<ShadowPluginDescriptor> call() throws Exception {
                return helper.listPlugins();
            }
        });
    }

    private <T> T control(Callable<T> callable) throws Exception {
        Future<T> future = helper.executor().submit(callable);
        return future.get(150, TimeUnit.SECONDS);
    }

    private static File newestPackage(File directory) {
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".shadowpkg"));
        if (files == null || files.length == 0) {
            return null;
        }
        File newest = files[0];
        for (File file : files) {
            if (file.lastModified() > newest.lastModified()) {
                newest = file;
            }
        }
        return newest;
    }

    private static void copyAndSync(File source, File target) throws Exception {
        FileInputStream input = new FileInputStream(source);
        FileOutputStream output = new FileOutputStream(target);
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
            output.getFD().sync();
        } finally {
            input.close();
            output.close();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
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
}
