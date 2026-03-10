package com.termux.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.sessionsync.SftpTransferRecoveryManager;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.TermuxConstants;

import java.util.concurrent.atomic.AtomicBoolean;

public class SftpTransferRecoveryService extends Service {

    private static final String LOG_TAG = "SftpTransferRecovery";
    private static final int NOTIFICATION_ID = 1348;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public static void startIfNeeded(@Nullable Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        if (!SftpTransferRecoveryManager.hasRecoverableTasks(appContext)) {
            return;
        }

        Intent intent = new Intent(appContext, SftpTransferRecoveryService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent);
            } else {
                appContext.startService(intent);
            }
        } catch (Throwable throwable) {
            Logger.logErrorExtended(LOG_TAG, "Start recovery service failed\n" + throwable);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        runStartForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!SftpTransferRecoveryManager.hasRecoverableTasks(getApplicationContext())) {
            runStopForeground();
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (!RUNNING.compareAndSet(false, true)) {
            return START_STICKY;
        }

        Thread worker = new Thread(() -> {
            try {
                SftpTransferRecoveryManager.resumePendingTasks(getApplicationContext());
            } catch (Throwable throwable) {
                Logger.logErrorExtended(LOG_TAG, "Resume recovery queue failed\n" + throwable);
            } finally {
                RUNNING.set(false);
                runStopForeground();
                stopSelf();
            }
        }, "sftp-transfer-recovery");
        worker.start();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        RUNNING.set(false);
        runStopForeground();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void runStartForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        setupNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void runStopForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    private Notification buildNotification() {
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            0,
            TermuxActivity.newInstance(this),
            0
        );

        Notification.Builder builder = NotificationUtils.geNotificationBuilder(
            this,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
            Notification.PRIORITY_LOW,
            TermuxConstants.TERMUX_APP_NAME,
            "\u6b63\u5728\u6062\u590d SFTP \u4f20\u8f93\u961f\u5217",
            "\u5df2\u68c0\u6d4b\u5230\u4e2d\u65ad\u7684\u4e0a\u4f20\u3001\u4e0b\u8f7d\u6216\u670d\u52a1\u5668\u4e92\u4f20\u4efb\u52a1\uff0c\u6b63\u5728\u540e\u53f0\u6062\u590d\u3002",
            contentIntent,
            null,
            NotificationUtils.NOTIFICATION_MODE_SILENT
        );
        if (builder == null) return null;

        builder.setShowWhen(false);
        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setColor(0xFF607D8B);
        return builder.build();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationUtils.setupNotificationChannel(
            this,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        );
    }
}
