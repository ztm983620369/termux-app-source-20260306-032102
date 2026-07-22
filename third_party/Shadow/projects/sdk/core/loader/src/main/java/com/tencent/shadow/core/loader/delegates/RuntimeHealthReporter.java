package com.tencent.shadow.core.loader.delegates;

import android.app.Application;
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

/**
 * Emits correlated health proof after the real plugin Activity completed post-resume and drew.
 *
 * <p>This class deliberately lives in the Loader delegate layer: that is the first generic point
 * which can prove that business onCreate/onResume/onPostResume all returned successfully. The Host
 * independently watches the plugin-process Binder until the stability result arrives.</p>
 */
final class RuntimeHealthReporter {

    private static final String TAG = "ShadowRuntimeHealth";
    private static final String KEY_PLUGIN_ID = "shadowPluginId";
    private static final String KEY_PLUGIN_GENERATION = "shadowPluginGeneration";
    private static final String KEY_OPERATION_ID = "shadowOperationId";
    private static final String KEY_RESULT_RECEIVER = "shadowResultReceiver";
    private static final String KEY_HEALTH_PROTOCOL_VERSION = "shadowHealthProtocolVersion";
    private static final String KEY_HEALTH_STABILITY_WINDOW_MS = "shadowHealthStabilityWindowMs";
    private static final String KEY_FIRST_FRAME_ELAPSED_MS = "shadowFirstFrameElapsedMs";
    private static final String KEY_STABLE_ELAPSED_MS = "shadowStableElapsedMs";
    private static final String KEY_PLUGIN_PROCESS_PID = "shadowPluginProcessPid";
    private static final String KEY_PLUGIN_PROCESS_NAME = "shadowPluginProcessName";
    private static final int RESULT_CODE_RUNTIME_READY = 2002;
    private static final int RESULT_CODE_RUNTIME_STABLE = 2003;
    private static final int HEALTH_PROTOCOL_VERSION = 1;
    private static final long DEFAULT_STABILITY_WINDOW_MS = 1_500L;

    private final ResultReceiver receiver;
    private final String pluginId;
    private final String generation;
    private final String operationId;
    private final long stabilityWindowMs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean scheduled;
    private boolean cancelled;

    private RuntimeHealthReporter(
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

    static RuntimeHealthReporter from(Bundle correlation) {
        if (correlation == null) {
            Log.w(TAG, "Loader health correlation bundle is absent");
            return null;
        }
        try {
            correlation.setClassLoader(RuntimeHealthReporter.class.getClassLoader());
            ResultReceiver receiver = correlation.getParcelable(KEY_RESULT_RECEIVER);
            String pluginId = correlation.getString(KEY_PLUGIN_ID);
            String generation = correlation.getString(KEY_PLUGIN_GENERATION);
            String operationId = correlation.getString(KEY_OPERATION_ID);
            int protocol = correlation.getInt(KEY_HEALTH_PROTOCOL_VERSION, 0);
            if (receiver == null || isBlank(pluginId) || isBlank(generation)
                    || isBlank(operationId) || protocol != HEALTH_PROTOCOL_VERSION) {
                Log.w(TAG, "Loader health correlation is incomplete: keys="
                        + correlation.keySet()
                        + " receiver=" + (receiver != null)
                        + " pluginId=" + pluginId
                        + " generation=" + generation
                        + " operationId=" + operationId
                        + " protocol=" + protocol);
                return null;
            }
            long requestedWindow = correlation.getLong(
                    KEY_HEALTH_STABILITY_WINDOW_MS,
                    DEFAULT_STABILITY_WINDOW_MS
            );
            long stabilityWindowMs = Math.max(500L, Math.min(requestedWindow, 10_000L));
            Log.i(TAG, "Loader armed first-frame health probe: " + pluginId + "/" + generation
                    + " operationId=" + operationId);
            return new RuntimeHealthReporter(
                    receiver,
                    pluginId,
                    generation,
                    operationId,
                    stabilityWindowMs
            );
        } catch (Throwable throwable) {
            Log.e(TAG, "Failed to decode Loader runtime-health correlation", throwable);
            return null;
        }
    }

    void schedule(final View decorView) {
        if (scheduled || cancelled) {
            return;
        }
        scheduled = true;
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
                        Choreographer.getInstance().postFrameCallback(
                                new Choreographer.FrameCallback() {
                                    @Override
                                    public void doFrame(long frameTimeNanos) {
                                        reportReadyAndArmStability();
                                    }
                                }
                        );
                    }
                });
            }
        };
        decorView.getViewTreeObserver().addOnDrawListener(holder[0]);
        decorView.postInvalidateOnAnimation();
    }

    void cancel() {
        cancelled = true;
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void reportReadyAndArmStability() {
        if (cancelled) {
            return;
        }
        final long firstFrameElapsedMs = SystemClock.elapsedRealtime();
        Log.i(TAG, "Loader FIRST_FRAME_READY pid=" + Process.myPid());
        send(RESULT_CODE_RUNTIME_READY, firstFrameElapsedMs, 0L);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!cancelled) {
                    Log.i(TAG, "Loader RUNTIME_STABLE pid=" + Process.myPid());
                    send(
                            RESULT_CODE_RUNTIME_STABLE,
                            firstFrameElapsedMs,
                            SystemClock.elapsedRealtime()
                    );
                }
            }
        }, stabilityWindowMs);
    }

    private void send(int resultCode, long firstFrameElapsedMs, long stableElapsedMs) {
        Bundle proof = new Bundle();
        proof.putString(KEY_PLUGIN_ID, pluginId);
        proof.putString(KEY_PLUGIN_GENERATION, generation);
        proof.putString(KEY_OPERATION_ID, operationId);
        proof.putInt(KEY_HEALTH_PROTOCOL_VERSION, HEALTH_PROTOCOL_VERSION);
        proof.putLong(KEY_FIRST_FRAME_ELAPSED_MS, firstFrameElapsedMs);
        if (stableElapsedMs > 0L) {
            proof.putLong(KEY_STABLE_ELAPSED_MS, stableElapsedMs);
        }
        proof.putInt(KEY_PLUGIN_PROCESS_PID, Process.myPid());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            proof.putString(KEY_PLUGIN_PROCESS_NAME, Application.getProcessName());
        }
        try {
            receiver.send(resultCode, proof);
        } catch (Throwable throwable) {
            Log.e(TAG, "Failed to deliver Loader runtime-health proof", throwable);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }
}
