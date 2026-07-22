package com.tencent.shadow.sample.host;

import android.content.Context;
import android.os.FileObserver;
import android.util.Log;

import com.tencent.shadow.sample.host.platform.ShadowLaunchPlan;
import com.tencent.shadow.sample.host.platform.ShadowPackageContract;
import com.tencent.shadow.sample.host.platform.ShadowPlatform;
import com.tencent.shadow.sample.host.platform.ShadowPluginDescriptor;
import com.tencent.shadow.sample.host.platform.ShadowRuntimeHealth;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class PluginHelper {

    private static final String TAG = "ShadowPluginHelper";

    private static final PluginHelper INSTANCE = new PluginHelper();

    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "shadow-platform-bootstrap");
                    thread.setDaemon(true);
                    return thread;
                }
            }
    );
    private final ScheduledExecutorService watchdogExecutor = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "shadow-launch-watchdog");
                    thread.setDaemon(true);
                    return thread;
                }
            }
    );

    private Context applicationContext;
    private ShadowPlatform platform;
    private FileObserver inboxObserver;
    private final ShadowLaunchGate launchGate = new ShadowLaunchGate();

    public static PluginHelper getInstance() {
        return INSTANCE;
    }

    private PluginHelper() {
    }

    public synchronized void init(Context context) {
        if (applicationContext != null) {
            return;
        }
        applicationContext = context.getApplicationContext();
        controlExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    requirePlatform().refresh();
                } catch (Exception error) {
                    Log.e(TAG, "Shadow platform bootstrap failed; foreground operations will retry", error);
                }
            }
        });
    }

    public ExecutorService executor() {
        return controlExecutor;
    }

    public ScheduledFuture<?> scheduleWatchdog(Runnable runnable, long delayMs) {
        return watchdogExecutor.schedule(runnable, delayMs, TimeUnit.MILLISECONDS);
    }

    public void acquireLaunch(String pluginId, String leaseId) {
        launchGate.acquire(pluginId, leaseId);
    }

    public boolean ownsLaunch(String pluginId, String leaseId) {
        return launchGate.owns(pluginId, leaseId);
    }

    public boolean claimLaunch(String pluginId, String leaseId) {
        return launchGate.claim(pluginId, leaseId);
    }

    public boolean releaseUnclaimedLaunch(String pluginId, String leaseId) {
        return launchGate.releaseIfUnclaimed(pluginId, leaseId);
    }

    public void releaseLaunch(String pluginId, String leaseId) {
        launchGate.release(pluginId, leaseId);
    }

    public List<ShadowPluginDescriptor> refresh() throws Exception {
        return requirePlatform().refresh();
    }

    public List<ShadowPluginDescriptor> listPlugins() throws Exception {
        return requirePlatform().listPlugins();
    }

    public ShadowLaunchPlan prepareLaunch(String pluginId) throws Exception {
        return requirePlatform().prepareLaunch(pluginId);
    }

    public ShadowLaunchPlan prepareRollback(String pluginId) throws Exception {
        return requirePlatform().prepareRollback(pluginId);
    }

    public void markLaunchReady(ShadowLaunchPlan plan, ShadowRuntimeHealth health) throws Exception {
        requirePlatform().markLaunchReady(plan, health);
    }

    public void markLaunchHealthy(ShadowLaunchPlan plan, ShadowRuntimeHealth health) throws Exception {
        requirePlatform().markLaunchHealthy(plan, health);
    }

    public void markLaunchFailed(ShadowLaunchPlan plan, Throwable throwable) throws Exception {
        requirePlatform().markLaunchFailed(plan, throwable);
    }

    public void enable(String pluginId) throws Exception {
        requirePlatform().enable(pluginId);
    }

    public void disable(String pluginId) throws Exception {
        requirePlatform().disable(pluginId);
    }

    public void remove(String pluginId) throws Exception {
        requirePlatform().remove(pluginId);
    }

    private synchronized ShadowPlatform requirePlatform() throws Exception {
        if (applicationContext == null) {
            throw new IllegalStateException("PluginHelper has not been initialized");
        }
        if (platform == null) {
            platform = ShadowPlatform.initialize(applicationContext);
        }
        ensureInboxObserver();
        return platform;
    }

    private void ensureInboxObserver() {
        if (inboxObserver != null) {
            return;
        }
        final String inboxPath = platform.paths().inboxDir().getAbsolutePath();
        inboxObserver = new FileObserver(
                inboxPath,
                FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO
        ) {
            @Override
            public void onEvent(int event, final String path) {
                if (path == null || ShadowPackageContract.isTransientFileName(path)) {
                    return;
                }
                controlExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            requirePlatform().refresh();
                        } catch (Throwable throwable) {
                            Log.e(TAG, "Automatic " + ShadowPackageContract.INGRESS_MODE
                                    + " reconciliation failed: " + path, throwable);
                        }
                    }
                });
            }
        };
        inboxObserver.startWatching();
    }
}
