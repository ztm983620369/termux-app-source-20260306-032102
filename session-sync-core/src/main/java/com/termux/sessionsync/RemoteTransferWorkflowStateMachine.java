package com.termux.sessionsync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class RemoteTransferWorkflowStateMachine {

    enum Stage {
        IDLE,
        PREPARING,
        STAGING_DOWNLOAD,
        STAGING_UPLOAD,
        CLEANUP,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    static final class Snapshot {
        @NonNull
        final Stage stage;
        final int totalFiles;
        final int completedFiles;
        final int failedFiles;
        final long totalBytes;
        final long transferredBytes;
        @NonNull
        final String currentFile;
        final long currentFileTransferred;
        final long currentFileSize;
        @NonNull
        final String messageCn;

        Snapshot(@NonNull Stage stage,
                 int totalFiles,
                 int completedFiles,
                 int failedFiles,
                 long totalBytes,
                 long transferredBytes,
                 @NonNull String currentFile,
                 long currentFileTransferred,
                 long currentFileSize,
                 @NonNull String messageCn) {
            this.stage = stage;
            this.totalFiles = Math.max(0, totalFiles);
            this.completedFiles = Math.max(0, completedFiles);
            this.failedFiles = Math.max(0, failedFiles);
            this.totalBytes = Math.max(0L, totalBytes);
            this.transferredBytes = Math.max(0L, transferredBytes);
            this.currentFile = currentFile;
            this.currentFileTransferred = Math.max(0L, currentFileTransferred);
            this.currentFileSize = Math.max(0L, currentFileSize);
            this.messageCn = messageCn;
        }
    }

    @NonNull
    private Snapshot snapshot = new Snapshot(Stage.IDLE, 0, 0, 0, 0L, 0L, "", 0L, 0L, "");

    void beginPreparing() {
        snapshot = new Snapshot(Stage.PREPARING, 0, 0, 0, 0L, 0L, "", 0L, 0L, "");
    }

    void bindDownload(@Nullable SftpProtocolManager.DownloadProgress progress) {
        if (progress == null) return;
        snapshot = new Snapshot(
            Stage.STAGING_DOWNLOAD,
            progress.totalFiles,
            progress.completedFiles,
            progress.failedFiles,
            progress.totalBytes,
            progress.transferredBytes,
            safeString(progress.currentFile),
            progress.currentFileTransferred,
            progress.currentFileSize,
            ""
        );
    }

    void bindUpload(@Nullable SftpProtocolManager.UploadProgress progress) {
        if (progress == null) return;
        snapshot = new Snapshot(
            Stage.STAGING_UPLOAD,
            progress.totalFiles,
            progress.completedFiles,
            progress.failedFiles,
            progress.totalBytes,
            progress.transferredBytes,
            safeString(progress.currentFile),
            progress.currentFileTransferred,
            progress.currentFileSize,
            ""
        );
    }

    void beginCleanup(@Nullable String messageCn) {
        snapshot = new Snapshot(
            Stage.CLEANUP,
            snapshot.totalFiles,
            snapshot.completedFiles,
            snapshot.failedFiles,
            snapshot.totalBytes,
            snapshot.transferredBytes,
            snapshot.currentFile,
            snapshot.currentFileTransferred,
            snapshot.currentFileSize,
            safeString(messageCn)
        );
    }

    void markCompleted(int totalFiles,
                       int completedFiles,
                       int failedFiles,
                       long totalBytes,
                       long transferredBytes,
                       @Nullable String messageCn) {
        snapshot = new Snapshot(
            Stage.COMPLETED,
            totalFiles,
            completedFiles,
            failedFiles,
            totalBytes,
            transferredBytes,
            "",
            0L,
            0L,
            safeString(messageCn)
        );
    }

    void markFailed(int totalFiles,
                    int completedFiles,
                    int failedFiles,
                    long totalBytes,
                    long transferredBytes,
                    @Nullable String messageCn) {
        snapshot = new Snapshot(
            Stage.FAILED,
            totalFiles,
            completedFiles,
            failedFiles,
            totalBytes,
            transferredBytes,
            "",
            0L,
            0L,
            safeString(messageCn)
        );
    }

    void markCancelled(int totalFiles,
                       int completedFiles,
                       int failedFiles,
                       long totalBytes,
                       long transferredBytes,
                       @Nullable String messageCn) {
        snapshot = new Snapshot(
            Stage.CANCELLED,
            totalFiles,
            completedFiles,
            failedFiles,
            totalBytes,
            transferredBytes,
            "",
            0L,
            0L,
            safeString(messageCn)
        );
    }

    @NonNull
    Snapshot snapshot() {
        return snapshot;
    }

    @NonNull
    static String stageLabelCn(@NonNull Stage stage) {
        switch (stage) {
            case PREPARING:
                return "准备互传";
            case STAGING_DOWNLOAD:
                return "第 1/2 步：从源服务器拉取";
            case STAGING_UPLOAD:
                return "第 2/2 步：同步到目标服务器";
            case CLEANUP:
                return "清理中转数据";
            case COMPLETED:
                return "互传完成";
            case FAILED:
                return "互传失败";
            case CANCELLED:
                return "互传已取消";
            case IDLE:
            default:
                return "待命";
        }
    }

    @NonNull
    private static String safeString(@Nullable String value) {
        return value == null ? "" : value;
    }
}
