package com.tencent.shadow.sample.host;

import static android.os.Process.myPid;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebView;

import com.tencent.shadow.core.common.LoggerFactory;
import com.tencent.shadow.dynamic.host.DynamicRuntime;
import com.tencent.shadow.dynamic.host.PluginManager;
import com.tencent.shadow.sample.constant.Constant;
import com.tencent.shadow.sample.host.lib.HostUiLayerProvider;
import com.tencent.shadow.sample.host.manager.Shadow;
import com.tencent.shadow.sample.host.platform.ShadowCrashHandler;
import com.tencent.shadow.sample.host.platform.ShadowEventLogger;
import com.tencent.shadow.sample.host.platform.ShadowExitInfoCollector;
import com.tencent.shadow.sample.host.platform.ShadowPaths;

import java.io.File;

public final class HostApplication {

    private static final String TAG = "ShadowHostApplication";

    private static final String PREFS_NAME = "shadow_runtime_state";

    private static final String KEY_RUNTIME_FINGERPRINT = "runtime_fingerprint";

    private static HostApplication sApp;

    private final Application mApplication;
    private PluginManager mPluginManager;
    private String mLoadedRuntimeFingerprint;
    private ShadowPaths mShadowPaths;

    private HostApplication(Application application) {
        mApplication = application;
    }

    public static synchronized HostApplication init(Application application) {
        if (sApp == null) {
            sApp = new HostApplication(application);
            sApp.onCreate();
        }
        return sApp;
    }

    public static HostApplication getApp() {
        if (sApp == null) {
            throw new IllegalStateException("Shadow HostApplication has not been initialized");
        }
        return sApp;
    }

    public static boolean isShadowPluginProcess(Context context) {
        String processName = getCurrentProcessName(context);
        return processName.endsWith(":plugin")
                || processName.endsWith(":plugin2")
                || processName.startsWith(context.getPackageName() + ":shadow");
    }

    private void onCreate() {
        LoggerFactory.setILoggerFactory(AndroidLogLoggerFactory.getInstance());

        try {
            mShadowPaths = new ShadowPaths(mApplication);
            ShadowEventLogger.initialize(mApplication, mShadowPaths);
            ShadowCrashHandler.install(mApplication, mShadowPaths);
        } catch (Throwable throwable) {
            Log.e(TAG, "Failed to initialize Shadow telemetry", throwable);
        }

        if (isShadowPluginProcess(mApplication)) {
            ShadowRuntimeHealthReporter.install(mApplication);
            setWebViewDataDirectorySuffix();
            DynamicRuntime.recoveryRuntime(mApplication);
        } else if (isMainProcess(mApplication)) {
            PluginHelper.getInstance().init(mApplication);
            if (mShadowPaths != null) {
                PluginHelper.getInstance().executor().execute(new Runnable() {
                    @Override
                    public void run() {
                        ShadowExitInfoCollector.collect(mApplication, mShadowPaths);
                    }
                });
            }
        }

        HostUiLayerProvider.init(mApplication);
    }

    public synchronized void installCrashHandler() {
        if (mShadowPaths == null) {
            return;
        }
        try {
            ShadowCrashHandler.install(mApplication, mShadowPaths);
        } catch (Throwable throwable) {
            Log.e(TAG, "Failed to install Shadow crash handler", throwable);
        }
    }

    private static void setWebViewDataDirectorySuffix() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        WebView.setDataDirectorySuffix(Application.getProcessName());
    }

    public synchronized void loadPluginManager(File apk) {
        loadPluginManager(apk, null);
    }

    public synchronized void loadPluginManager(File apk, String runtimeFingerprint) {
        String lastRuntimeFingerprint = getPersistedRuntimeFingerprint();
        if (mPluginManager != null
                && mLoadedRuntimeFingerprint != null
                && runtimeFingerprint != null
                && !mLoadedRuntimeFingerprint.equals(runtimeFingerprint)) {
            resetPluginRuntimeLocked("Shadow runtime fingerprint changed");
        } else if (runtimeFingerprint != null
                && lastRuntimeFingerprint != null
                && !lastRuntimeFingerprint.equals(runtimeFingerprint)) {
            resetPluginRuntimeLocked("Shadow runtime fingerprint changed after process restart");
        }

        if (mPluginManager == null) {
            mPluginManager = Shadow.getPluginManager(apk);
        }
        mLoadedRuntimeFingerprint = runtimeFingerprint;
        persistRuntimeFingerprint(runtimeFingerprint);
        ShadowEventLogger eventLogger = ShadowEventLogger.get();
        if (eventLogger != null) {
            eventLogger.info("MANAGER_READY", null, null, null,
                    "fingerprint=" + runtimeFingerprint);
        }
    }

    public synchronized PluginManager getPluginManager() {
        return mPluginManager;
    }

    public synchronized void resetPluginRuntime(String reason) {
        resetPluginRuntimeLocked(reason);
    }

    private void resetPluginRuntimeLocked(String reason) {
        if (reason != null && reason.length() > 0) {
            Log.i(TAG, reason);
        }

        PluginManager oldPluginManager = mPluginManager;
        mPluginManager = null;
        mLoadedRuntimeFingerprint = null;
        persistRuntimeFingerprint(null);

        if (oldPluginManager != null) {
            try {
                oldPluginManager.enter(mApplication, Constant.FROM_ID_CLOSE, null, null);
            } catch (Throwable throwable) {
                Log.w(TAG, "Failed to close Shadow plugin manager", throwable);
            }
        }

        stopShadowPluginServices(mApplication);
        int killed = killShadowPluginProcesses(mApplication);
        if (killed > 0) {
            waitForShadowPluginProcessesToStop(mApplication, 1500L);
        }
    }

    private static boolean isMainProcess(Context context) {
        return getCurrentProcessName(context).equals(context.getPackageName());
    }

    private static String getCurrentProcessName(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName();
        }

        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            for (ActivityManager.RunningAppProcessInfo processInfo : manager.getRunningAppProcesses()) {
                if (processInfo.pid == myPid()) {
                    return processInfo.processName;
                }
            }
        }
        return context.getPackageName();
    }

    private static void stopShadowPluginServices(Context context) {
        stopService(context, "com.tencent.shadow.sample.host.PluginProcessPPS");
        stopService(context, "com.tencent.shadow.sample.host.Plugin2ProcessPPS");
    }

    private static void stopService(Context context, String serviceName) {
        try {
            context.stopService(new Intent().setClassName(context, serviceName));
        } catch (Throwable throwable) {
            Log.w(TAG, "Failed to stop Shadow service: " + serviceName, throwable);
        }
    }

    private static int killShadowPluginProcesses(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return 0;
        }

        int killed = 0;
        for (ActivityManager.RunningAppProcessInfo processInfo : manager.getRunningAppProcesses()) {
            if (processInfo == null || processInfo.pid == myPid()) {
                continue;
            }
            if (isShadowPluginProcessName(context, processInfo.processName)) {
                Log.i(TAG, "Kill stale Shadow plugin process: " + processInfo.processName
                        + " pid=" + processInfo.pid);
                android.os.Process.killProcess(processInfo.pid);
                killed++;
            }
        }
        return killed;
    }

    private static boolean isShadowPluginProcessName(Context context, String processName) {
        if (processName == null) {
            return false;
        }
        String packageName = context.getPackageName();
        return processName.equals(packageName + ":plugin")
                || processName.equals(packageName + ":plugin2")
                || processName.startsWith(packageName + ":shadow");
    }

    private static void waitForShadowPluginProcessesToStop(Context context, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (countShadowPluginProcesses(context) == 0) {
                return;
            }
            SystemClock.sleep(50L);
        }
        Log.w(TAG, "Timed out waiting for Shadow plugin processes to stop");
    }

    private static int countShadowPluginProcesses(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return 0;
        }
        int count = 0;
        for (ActivityManager.RunningAppProcessInfo processInfo : manager.getRunningAppProcesses()) {
            if (processInfo != null && isShadowPluginProcessName(context, processInfo.processName)) {
                count++;
            }
        }
        return count;
    }

    private String getPersistedRuntimeFingerprint() {
        return mApplication
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_RUNTIME_FINGERPRINT, null);
    }

    private void persistRuntimeFingerprint(String runtimeFingerprint) {
        if (runtimeFingerprint == null) {
            mApplication
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_RUNTIME_FINGERPRINT)
                    .commit();
            return;
        }
        mApplication
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RUNTIME_FINGERPRINT, runtimeFingerprint)
                .commit();
    }
}
