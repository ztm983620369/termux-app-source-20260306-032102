package com.termux.cameracapsulesurface;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

final class CameraCapsuleForegroundNotificationFactory {

    @NonNull
    private final Context context;

    CameraCapsuleForegroundNotificationFactory(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    @NonNull
    Notification build(@NonNull CameraCapsuleSurfaceState state) {
        ensureChannel();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
            context, CameraCapsuleSurfaceFallbackBridge.CHANNEL_ID)
            .setSmallIcon(resolveSmallIcon())
            .setContentTitle(state.title)
            .setContentText(resolveContentText(state))
            .setContentIntent(buildLaunchIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW);

        builder.setProgress(state.progressMax, state.progressCurrent, state.progressIndeterminate);
        return builder.build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager == null) return;
        NotificationChannel existing = notificationManager.getNotificationChannel(
            CameraCapsuleSurfaceFallbackBridge.CHANNEL_ID);
        if (existing != null) return;
        NotificationChannel channel = new NotificationChannel(
            CameraCapsuleSurfaceFallbackBridge.CHANNEL_ID,
            CameraCapsuleSurfaceFallbackBridge.CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(CameraCapsuleSurfaceFallbackBridge.CHANNEL_DESCRIPTION);
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private int resolveSmallIcon() {
        ApplicationInfo appInfo = context.getApplicationInfo();
        return appInfo != null && appInfo.icon != 0 ? appInfo.icon : android.R.drawable.stat_notify_sync;
    }

    @NonNull
    private PendingIntent buildLaunchIntent() {
        PackageManager pm = context.getPackageManager();
        Intent launchIntent = pm.getLaunchIntentForPackage(context.getPackageName());
        if (launchIntent == null) launchIntent = new Intent();
        launchIntent.setPackage(context.getPackageName());
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
            context, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @NonNull
    private String resolveContentText(@NonNull CameraCapsuleSurfaceState state) {
        if (!TextUtils.isEmpty(state.text)) return state.text;
        if (!TextUtils.isEmpty(state.status)) return state.status;
        if (state.progressMax > 0 && !state.progressIndeterminate) {
            return state.progressCurrent + "/" + state.progressMax;
        }
        return "Camera capsule overlay active";
    }
}
