package com.termux.systemstatussurface;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.Collections;

final class NotificationStatusSurfaceRenderer {

    private static final String LOG_TAG = "SystemStatusSurface";
    @NonNull
    private final Context context;

    NotificationStatusSurfaceRenderer(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    void render(@NonNull SystemStatusSurfaceState state) {
        ensureChannel(state);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, state.channelId)
            .setSmallIcon(resolveSmallIcon())
            .setContentTitle(state.title)
            .setContentText(composePrimaryText(state))
            .setSubText(TextUtils.isEmpty(state.status) ? null : state.status)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(composeBigText(state)))
            .setOngoing(state.ongoing)
            .setOnlyAlertOnce(state.alertOnce)
            .setCategory(state.category)
            .setPriority(state.priority)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(buildLaunchIntent());

        if (state.colorArgb != 0) builder.setColor(state.colorArgb);
        if (state.startedAtMs > 0L) builder.setWhen(state.startedAtMs);
        builder.setUsesChronometer(state.showChronometer && state.startedAtMs > 0L);
        if (state.timeoutAfterMs > 0L) builder.setTimeoutAfter(state.timeoutAfterMs);
        applySurfaceStyle(builder, state);
        if (state.promoted && Build.VERSION.SDK_INT >= 36) {
            builder.setRequestPromotedOngoing(true);
            String shortCriticalText = resolveShortCriticalText(state);
            if (!TextUtils.isEmpty(shortCriticalText)) {
                builder.setShortCriticalText(shortCriticalText);
            }
        } else {
            builder.setProgress(state.progressMax, state.progressCurrent, state.progressIndeterminate);
        }

        Notification notification = builder.build();
        logPromotionState(state, notification);
        NotificationManagerCompat.from(context).notify(notificationIdFor(state.surfaceId), notification);
    }

    void cancel(@NonNull String surfaceId) {
        NotificationManagerCompat.from(context).cancel(notificationIdFor(surfaceId));
    }

    void cancelAll(@NonNull Iterable<SystemStatusSurfaceState> states) {
        for (SystemStatusSurfaceState state : states) {
            cancel(state.surfaceId);
        }
    }

    private void ensureChannel(@NonNull SystemStatusSurfaceState state) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel existing = manager.getNotificationChannel(state.channelId);
        if (existing != null) return;
        NotificationChannel channel = new NotificationChannel(
            state.channelId, state.channelName,
            state.promoted ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(state.channelDescription);
        manager.createNotificationChannel(channel);
    }

    void openPromotionSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Throwable ignored) {
            Intent fallback = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            fallback.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(fallback);
        }
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
    private String composePrimaryText(@NonNull SystemStatusSurfaceState state) {
        if (!TextUtils.isEmpty(state.text)) return state.text;
        if (!TextUtils.isEmpty(state.status)) return state.status;
        if (state.progressMax > 0 && !state.progressIndeterminate) {
            return state.progressCurrent + "/" + state.progressMax;
        }
        return "Running";
    }

    @NonNull
    private String composeBigText(@NonNull SystemStatusSurfaceState state) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(state.text)) sb.append(state.text.trim());
        if (!TextUtils.isEmpty(state.status)) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(state.status.trim());
        }
        if (state.progressMax > 0) {
            if (sb.length() > 0) sb.append("\n");
            if (state.progressIndeterminate) sb.append("Progress: running");
            else sb.append("Progress: ").append(state.progressCurrent).append("/").append(state.progressMax);
        }
        if (sb.length() == 0) sb.append("System status surface active");
        return sb.toString();
    }

    private void applySurfaceStyle(@NonNull NotificationCompat.Builder builder,
                                   @NonNull SystemStatusSurfaceState state) {
        if (Build.VERSION.SDK_INT >= 36 && (state.progressMax > 0 || state.progressIndeterminate)) {
            NotificationCompat.ProgressStyle progressStyle = new NotificationCompat.ProgressStyle()
                .setStyledByProgress(true)
                .setProgressIndeterminate(state.progressIndeterminate);
            if (!state.progressIndeterminate) {
                int boundedMax = Math.max(1, state.progressMax);
                int boundedCurrent = Math.max(0, Math.min(state.progressCurrent, boundedMax));
                NotificationCompat.ProgressStyle.Segment segment =
                    new NotificationCompat.ProgressStyle.Segment(boundedMax).setColor(resolveProgressColor(state));
                progressStyle
                    .addProgressSegment(segment)
                    .setProgress(boundedCurrent)
                    .setProgressPoints(Collections.singletonList(
                        new NotificationCompat.ProgressStyle.Point(Math.max(0, boundedMax / 2))
                            .setColor(resolveProgressColor(state))));
            }
            builder.setStyle(progressStyle);
            return;
        }

        builder.setStyle(new NotificationCompat.BigTextStyle().bigText(composeBigText(state)));
        builder.setProgress(state.progressMax, state.progressCurrent, state.progressIndeterminate);
    }

    private void logPromotionState(@NonNull SystemStatusSurfaceState state, @NonNull Notification notification) {
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        boolean canPromote = Build.VERSION.SDK_INT >= 36 && manager.canPostPromotedNotifications();
        boolean promotable = Build.VERSION.SDK_INT >= 36 && NotificationCompat.hasPromotableCharacteristics(notification);
        Log.i(LOG_TAG, "surface=" + state.surfaceId +
            ", promotedRequested=" + state.promoted +
            ", canPromote=" + canPromote +
            ", promotable=" + promotable +
            ", channel=" + state.channelId);
    }

    @NonNull
    private String resolveShortCriticalText(@NonNull SystemStatusSurfaceState state) {
        if (!TextUtils.isEmpty(state.shortCriticalText)) return state.shortCriticalText;
        if (!TextUtils.isEmpty(state.status)) return state.status;
        if (state.progressMax > 0 && !state.progressIndeterminate) {
            int percent = Math.max(0, Math.min(100, (int) Math.round(state.progressCurrent * 100.0d / Math.max(1, state.progressMax))));
            return percent + "%";
        }
        return "";
    }

    private int resolveProgressColor(@NonNull SystemStatusSurfaceState state) {
        return state.colorArgb != 0 ? state.colorArgb : 0xFF4CAF50;
    }

    private static int notificationIdFor(@NonNull String surfaceId) {
        return SystemStatusSurfaceNotificationIds.notificationIdFor(surfaceId);
    }
}
