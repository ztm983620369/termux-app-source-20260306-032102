package com.tencent.shadow.sample.host;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewTreeObserver;

import com.tencent.shadow.sample.constant.Constant;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Process-local, business-code-independent runtime health protocol.
 *
 * <p>The callback is registered before DynamicRuntime recovery. It observes the proxy Activity
 * only after Shadow has completed the business Activity's onCreate/onResume, then waits for an
 * actual draw and a configurable process-stability window. The manager separately monitors the
 * plugin-process Binder, so a crash wins the race and no candidate is promoted.</p>
 */
final class ShadowRuntimeHealthReporter implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "ShadowRuntimeHealth";
    private static final String CM_EXTRAS_BUNDLE_KEY = "CM_EXTRAS_BUNDLE";
    private static final int MAX_TRACKED_OPERATIONS = 64;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> scheduledOperations = new LinkedHashSet<>();

    static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new ShadowRuntimeHealthReporter());
        Log.i(TAG, "Installed process-local Activity lifecycle health reporter");
    }

    @Override
    public void onActivityResumed(Activity activity) {
        final Probe probe = Probe.from(activity.getIntent());
        if (probe == null || !markScheduled(probe.operationId)) {
            return;
        }
        final View decorView = activity.getWindow().getDecorView();
        final ViewTreeObserver.OnDrawListener[] holder = new ViewTreeObserver.OnDrawListener[1];
        holder[0] = new ViewTreeObserver.OnDrawListener() {
            @Override
            public void onDraw() {
                decorView.post(new Runnable() {
                    @Override
                    public void run() {
                        ViewTreeObserver observer = decorView.getViewTreeObserver();
                        if (observer.isAlive()) {
                            observer.removeOnDrawListener(holder[0]);
                        }
                        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                            @Override
                            public void doFrame(long frameTimeNanos) {
                                reportReadyAndArmStability(probe);
                            }
                        });
                    }
                });
            }
        };
        decorView.getViewTreeObserver().addOnDrawListener(holder[0]);
        decorView.postInvalidateOnAnimation();
    }

    private void reportReadyAndArmStability(final Probe probe) {
        final long firstFrameElapsedMs = SystemClock.elapsedRealtime();
        probe.send(Constant.RESULT_CODE_RUNTIME_READY, firstFrameElapsedMs, 0L);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                probe.send(
                        Constant.RESULT_CODE_RUNTIME_STABLE,
                        firstFrameElapsedMs,
                        SystemClock.elapsedRealtime()
                );
            }
        }, probe.stabilityWindowMs);
    }

    private synchronized boolean markScheduled(String operationId) {
        if (!scheduledOperations.add(operationId)) {
            return false;
        }
        while (scheduledOperations.size() > MAX_TRACKED_OPERATIONS) {
            String oldest = scheduledOperations.iterator().next();
            scheduledOperations.remove(oldest);
        }
        return true;
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }

    private static final class Probe {
        final ResultReceiver receiver;
        final String pluginId;
        final String generation;
        final String operationId;
        final long stabilityWindowMs;

        Probe(
                ResultReceiver receiver,
                String pluginId,
                String generation,
                String operationId,
                long stabilityWindowMs
        ) {
            this.receiver = receiver;
            this.pluginId = pluginId;
            this.generation = generation;
            this.operationId = operationId;
            this.stabilityWindowMs = stabilityWindowMs;
        }

        static Probe from(Intent intent) {
            if (intent == null) {
                return null;
            }
            try {
                intent.setExtrasClassLoader(ShadowRuntimeHealthReporter.class.getClassLoader());
                Bundle correlation = intent.getBundleExtra(CM_EXTRAS_BUNDLE_KEY);
                if (correlation == null) {
                    correlation = intent.getExtras();
                }
                if (correlation == null) {
                    return null;
                }
                correlation.setClassLoader(ShadowRuntimeHealthReporter.class.getClassLoader());
                ResultReceiver receiver = correlation.getParcelable(Constant.KEY_RESULT_RECEIVER);
                String pluginId = correlation.getString(Constant.KEY_PLUGIN_ID);
                String generation = correlation.getString(Constant.KEY_PLUGIN_GENERATION);
                String operationId = correlation.getString(Constant.KEY_OPERATION_ID);
                int protocol = correlation.getInt(Constant.KEY_HEALTH_PROTOCOL_VERSION, 0);
                if (receiver == null || isBlank(pluginId) || isBlank(generation)
                        || isBlank(operationId) || protocol != Constant.HEALTH_PROTOCOL_VERSION) {
                    Log.w(TAG, "Incomplete runtime health correlation: keys="
                            + correlation.keySet()
                            + " receiver=" + (receiver != null)
                            + " pluginId=" + pluginId
                            + " generation=" + generation
                            + " operationId=" + operationId
                            + " protocol=" + protocol);
                    return null;
                }
                long stabilityWindowMs = correlation.getLong(
                        Constant.KEY_HEALTH_STABILITY_WINDOW_MS,
                        1_500L
                );
                Log.i(TAG, "Armed first-frame health probe: " + pluginId + "/" + generation
                        + " operationId=" + operationId);
                return new Probe(
                        receiver,
                        pluginId,
                        generation,
                        operationId,
                        Math.max(500L, Math.min(stabilityWindowMs, 10_000L))
                );
            } catch (Throwable throwable) {
                Log.e(TAG, "Failed to decode runtime health correlation", throwable);
                return null;
            }
        }

        void send(int resultCode, long firstFrameElapsedMs, long stableElapsedMs) {
            Bundle proof = new Bundle();
            proof.putString(Constant.KEY_PLUGIN_ID, pluginId);
            proof.putString(Constant.KEY_PLUGIN_GENERATION, generation);
            proof.putString(Constant.KEY_OPERATION_ID, operationId);
            proof.putInt(
                    Constant.KEY_HEALTH_PROTOCOL_VERSION,
                    Constant.HEALTH_PROTOCOL_VERSION
            );
            proof.putLong(Constant.KEY_FIRST_FRAME_ELAPSED_MS, firstFrameElapsedMs);
            if (stableElapsedMs > 0L) {
                proof.putLong(Constant.KEY_STABLE_ELAPSED_MS, stableElapsedMs);
            }
            proof.putInt(Constant.KEY_PLUGIN_PROCESS_PID, Process.myPid());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                proof.putString(Constant.KEY_PLUGIN_PROCESS_NAME, Application.getProcessName());
            }
            try {
                receiver.send(resultCode, proof);
            } catch (Throwable throwable) {
                Log.e(TAG, "Failed to deliver runtime health proof", throwable);
            }
        }

        private static boolean isBlank(String value) {
            return value == null || value.trim().length() == 0;
        }
    }
}
