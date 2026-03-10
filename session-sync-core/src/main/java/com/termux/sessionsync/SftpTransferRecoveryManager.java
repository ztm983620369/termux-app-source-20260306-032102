package com.termux.sessionsync;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.List;

public final class SftpTransferRecoveryManager {

    private static final Object LOCK = new Object();
    private static volatile boolean sRecovering;

    private SftpTransferRecoveryManager() {
    }

    public static boolean hasRecoverableTasks(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        return SftpTransferJournal.getInstance().hasRecoverableTasks(appContext);
    }

    public static void resumePendingTasks(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        synchronized (LOCK) {
            if (sRecovering) {
                SessionSyncTracer.getInstance().debug(
                    appContext,
                    "SftpTransferRecoveryManager",
                    "resumePendingTasks",
                    null,
                    "\u6062\u590d\u961f\u5217\u5df2\u5728\u8fd0\u884c",
                    null
                );
                return;
            }
            sRecovering = true;
        }

        try {
            SessionSyncTracer.getInstance().info(
                appContext,
                "SftpTransferRecoveryManager",
                "resumePendingTasks",
                null,
                "\u5f00\u59cb\u6062\u590d SFTP \u4efb\u52a1\u961f\u5217",
                null
            );

            while (true) {
                List<SftpTransferJournal.RecoverableTask> tasks =
                    SftpTransferJournal.getInstance().snapshotRecoverableTasks(appContext);
                if (tasks.isEmpty()) {
                    break;
                }

                for (SftpTransferJournal.RecoverableTask task : tasks) {
                    if (task == null) continue;
                    resumeSingleTask(appContext, task);
                }
            }

            SessionSyncTracer.getInstance().info(
                appContext,
                "SftpTransferRecoveryManager",
                "resumePendingTasks",
                null,
                "\u6062\u590d\u961f\u5217\u5904\u7406\u5b8c\u6210",
                null
            );
        } finally {
            synchronized (LOCK) {
                sRecovering = false;
            }
        }
    }

    private static void resumeSingleTask(@NonNull Context context,
                                         @NonNull SftpTransferJournal.RecoverableTask task) {
        SftpTransferJournal journal = SftpTransferJournal.getInstance();
        SftpTransferJournal.TaskHandle handle = task.toHandle();
        journal.markRecovering(context, handle, "\u6062\u590d\u670d\u52a1\u5df2\u63a5\u7ba1");

        SessionSyncTracer.getInstance().info(
            context,
            "SftpTransferRecoveryManager",
            "resumeSingleTask",
            task.sessionKey,
            "\u5f00\u59cb\u6062\u590d\u4f20\u8f93\u4efb\u52a1",
            task.kind.name() + " -> " + task.destinationPath
        );

        try {
            switch (task.kind) {
                case DOWNLOAD: {
                    SftpProtocolManager.DownloadResult result = SftpProtocolManager.getInstance().resumeDownloadVirtualPaths(
                        context,
                        task.sourcePaths,
                        task.destinationPath,
                        null,
                        null
                    );
                    journal.handoffToRecovery(context, handle, buildResultMessage(result.messageCn));
                    SessionSyncTracer.getInstance().info(
                        context,
                        "SftpTransferRecoveryManager",
                        "resumeDownload",
                        task.sessionKey,
                        result.success ? "\u4e0b\u8f7d\u6062\u590d\u5b8c\u6210" : "\u4e0b\u8f7d\u6062\u590d\u7ed3\u675f",
                        result.messageCn
                    );
                    break;
                }
                case UPLOAD: {
                    SftpProtocolManager.UploadResult result = SftpProtocolManager.getInstance().uploadLocalPathsToVirtual(
                        context,
                        task.sourcePaths,
                        task.destinationPath,
                        null,
                        null
                    );
                    journal.handoffToRecovery(context, handle, buildResultMessage(result.messageCn));
                    SessionSyncTracer.getInstance().info(
                        context,
                        "SftpTransferRecoveryManager",
                        "resumeUpload",
                        task.sessionKey,
                        result.success ? "\u4e0a\u4f20\u6062\u590d\u5b8c\u6210" : "\u4e0a\u4f20\u6062\u590d\u7ed3\u675f",
                        result.messageCn
                    );
                    break;
                }
                case RELAY:
                default: {
                    SftpProtocolManager.RemoteTransferResult result = SftpProtocolManager.getInstance().resumeTransferVirtualPaths(
                        context,
                        task.sourcePaths,
                        task.destinationPath,
                        task.stageDirectoryPath,
                        null,
                        null
                    );
                    journal.handoffToRecovery(context, handle, buildResultMessage(result.messageCn));
                    SessionSyncTracer.getInstance().info(
                        context,
                        "SftpTransferRecoveryManager",
                        "resumeRelay",
                        task.sessionKey,
                        result.success ? "\u670d\u52a1\u5668\u4e92\u4f20\u6062\u590d\u5b8c\u6210" : "\u670d\u52a1\u5668\u4e92\u4f20\u6062\u590d\u7ed3\u675f",
                        result.messageCn
                    );
                    break;
                }
            }
        } catch (Throwable throwable) {
            SessionSyncTracer.getInstance().error(
                context,
                "SftpTransferRecoveryManager",
                "resumeSingleTask",
                task.sessionKey,
                "\u6062\u590d\u4efb\u52a1\u6267\u884c\u5f02\u5e38",
                throwable.getMessage()
            );
        }
    }

    @NonNull
    private static String buildResultMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "\u65e7\u4efb\u52a1\u5df2\u5207\u6362\u5230\u6062\u590d\u94fe\u8def";
        }
        return "\u65e7\u4efb\u52a1\u5df2\u5207\u6362\u5230\u6062\u590d\u94fe\u8def\uff1a" + message.trim();
    }
}
