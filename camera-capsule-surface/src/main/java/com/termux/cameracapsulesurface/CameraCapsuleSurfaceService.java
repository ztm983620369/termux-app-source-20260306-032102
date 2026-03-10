package com.termux.cameracapsulesurface;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.systemstatussurface.SystemStatusSurfaceEngine;

public final class CameraCapsuleSurfaceService extends Service {

    private static final String LOG_TAG = "CameraCapsuleService";
    private static final String ACTION_SYNC = "com.termux.cameracapsulesurface.service.SYNC";

    @Nullable
    private CameraCapsuleOverlayRenderer overlayRenderer;
    @Nullable
    private CameraCapsuleSurfaceStore store;
    @Nullable
    private CameraCapsuleSurfaceFallbackBridge fallbackBridge;
    @Nullable
    private CameraCapsuleForegroundNotificationFactory notificationFactory;

    static void startOrSync(@NonNull Context context, @NonNull CameraCapsuleSurfaceState state) {
        Intent intent = new Intent(context, CameraCapsuleSurfaceService.class).setAction(ACTION_SYNC);
        CameraCapsuleSurfaceIntentCodec.encodeState(intent, state);
        ContextCompat.startForegroundService(context, intent);
    }

    static void stop(@NonNull Context context) {
        context.stopService(new Intent(context, CameraCapsuleSurfaceService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new CameraCapsuleSurfaceStore(this);
        overlayRenderer = new CameraCapsuleOverlayRenderer(this, state -> {
            if (store != null) store.save(state);
        });
        fallbackBridge = new CameraCapsuleSurfaceFallbackBridge(new SystemStatusSurfaceEngine(this));
        notificationFactory = new CameraCapsuleForegroundNotificationFactory(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        CameraCapsuleSurfaceState primaryState = resolvePrimaryState(intent);
        if (primaryState == null) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        if (!OverlayPermissionGate.canDrawOverlays(this)) {
            if (fallbackBridge != null) fallbackBridge.publish(primaryState);
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        try {
            Notification notification = notificationFactory == null
                ? new CameraCapsuleForegroundNotificationFactory(this).build(primaryState)
                : notificationFactory.build(primaryState);
            startForegroundCompat(notification);
            if (fallbackBridge != null) fallbackBridge.publish(primaryState);
            if (overlayRenderer != null) overlayRenderer.render(primaryState);
            Log.i(LOG_TAG, "Rendered capsule overlay: " + primaryState.surfaceId);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to render capsule overlay", e);
            if (fallbackBridge != null) fallbackBridge.publish(primaryState);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (overlayRenderer != null) overlayRenderer.hide();
        stopForegroundCompat();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundCompat(@NonNull Notification notification) {
        int notificationId = new CameraCapsuleSurfaceFallbackBridge(
            new SystemStatusSurfaceEngine(this)).notificationId();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            return;
        }
        startForeground(notificationId, notification);
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Exception ignored) {
        }
    }

    @Nullable
    private CameraCapsuleSurfaceState resolvePrimaryState(@Nullable Intent intent) {
        if (intent != null && ACTION_SYNC.equals(intent.getAction())) {
            return CameraCapsuleSurfaceIntentCodec.decodeState(intent);
        }
        if (store == null) return null;
        return CameraCapsuleSurfaceSelector.selectPrimary(store.list());
    }

    private void stopSelfSafely() {
        if (overlayRenderer != null) overlayRenderer.hide();
        stopForegroundCompat();
        stopSelf();
    }
}
