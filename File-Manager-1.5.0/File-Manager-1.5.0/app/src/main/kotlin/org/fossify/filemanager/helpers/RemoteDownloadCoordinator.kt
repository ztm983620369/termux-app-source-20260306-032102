package org.fossify.filemanager.helpers

import com.termux.sessionsync.SessionFileCoordinator
import com.termux.sessionsync.SftpProtocolManager
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.SimpleActivity
import java.util.concurrent.atomic.AtomicBoolean

private const val TERMINAL_ROW_STATE_CLEAR_DELAY_MS = 1_600L

data class RemoteDownloadOutcome(
    val status: ActiveTransferStatus,
    val totalFiles: Int,
    val downloadedFiles: Int,
    val failedFiles: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val downloadedLocalPaths: ArrayList<String>,
    val messageCn: String,
    val throwable: Throwable? = null
) {
    val hasDownloadedFiles: Boolean
        get() = downloadedFiles > 0 && downloadedLocalPaths.isNotEmpty()
}

object RemoteDownloadOutcomeClassifier {
    fun fromResult(result: SftpProtocolManager.DownloadResult, cancelRequested: Boolean): RemoteDownloadOutcome {
        val status = when {
            result.success -> ActiveTransferStatus.SUCCESS
            cancelRequested || isCancelledMessage(result.messageCn) -> ActiveTransferStatus.CANCELLED
            result.downloadedFiles > 0 -> ActiveTransferStatus.PARTIAL
            else -> ActiveTransferStatus.FAILED
        }
        return RemoteDownloadOutcome(
            status = status,
            totalFiles = result.totalFiles,
            downloadedFiles = result.downloadedFiles,
            failedFiles = result.failedFiles,
            totalBytes = result.totalBytes,
            downloadedBytes = result.downloadedBytes,
            downloadedLocalPaths = ArrayList(result.downloadedLocalPaths),
            messageCn = result.messageCn
        )
    }

    fun fromThrowable(t: Throwable): RemoteDownloadOutcome {
        val msg = t.message?.trim().orEmpty()
        return RemoteDownloadOutcome(
            status = ActiveTransferStatus.FAILED,
            totalFiles = 0,
            downloadedFiles = 0,
            failedFiles = 0,
            totalBytes = 0L,
            downloadedBytes = 0L,
            downloadedLocalPaths = arrayListOf(),
            messageCn = msg,
            throwable = t
        )
    }

    fun isCancelledMessage(message: String): Boolean = message.contains("已取消")
}

object RemoteDownloadCoordinator {
    fun start(
        activity: SimpleActivity,
        sessionFileCoordinator: SessionFileCoordinator,
        sourceItems: List<FileDirItem>,
        destinationPath: String,
        mode: ActiveTransferMode,
        title: String,
        fallbackFileName: String = "准备中...",
        onOutcome: (RemoteDownloadOutcome) -> Unit,
        onFinish: () -> Unit = {}
    ) {
        if (activity.isDestroyed || activity.isFinishing || sourceItems.isEmpty()) {
            onFinish()
            return
        }

        val sourcePaths = sourceItems.map { it.path }
        val initialFileName = sourceItems.firstOrNull()?.name?.ifBlank { fallbackFileName } ?: fallbackFileName
        val isFolderTransfer = sourceItems.size > 1 || sourceItems.any { it.isDirectory }
        val sessionId = ActiveTransferRegistry.beginSession(
            sourceTopLevelPaths = sourcePaths,
            destinationPath = destinationPath,
            mode = mode,
            title = title,
            initialFileName = initialFileName
        )
        val cancelRequested = AtomicBoolean(false)
        lateinit var progressWindow: TransferProgressWindow
        progressWindow = TransferProgressWindow(
            activity = activity,
            title = title,
            cancelLabel = activity.getString(R.string.transfer_cancel),
            initialFileName = initialFileName,
            mode = mode,
            isFolderTransfer = isFolderTransfer,
            onCancel = {
                cancelRequested.set(true)
                progressWindow.updateMessage(activity.getString(R.string.transfer_cancelling))
                if (sessionId > 0L) {
                    activity.runOnUiThread {
                        ActiveTransferRegistry.markCancelling(sessionId)
                    }
                }
            }
        )
        progressWindow.show()

        ensureBackgroundThread {
            try {
                val result = sessionFileCoordinator.downloadVirtualPaths(
                    activity,
                    sourcePaths,
                    destinationPath,
                    object : SftpProtocolManager.DownloadProgressListener {
                        override fun onProgress(progress: SftpProtocolManager.DownloadProgress) {
                            val state = TransferProgressState(
                                phaseLabel = title,
                                currentFile = progress.currentFile.ifEmpty { initialFileName.ifBlank { fallbackFileName } },
                                totalFiles = progress.totalFiles,
                                completedFiles = progress.completedFiles,
                                failedFiles = progress.failedFiles,
                                totalBytes = progress.totalBytes,
                                transferredBytes = progress.transferredBytes,
                                currentFileTransferred = progress.currentFileTransferred,
                                currentFileSize = progress.currentFileSize,
                                speedBytesPerSecond = 0L
                            )
                            progressWindow.updateDownload(
                                phaseLabel = state.phaseLabel,
                                currentFile = state.currentFile,
                                completedFiles = state.completedFiles,
                                failedFiles = state.failedFiles,
                                totalFiles = state.totalFiles,
                                transferredBytes = state.transferredBytes,
                                totalBytes = state.totalBytes,
                                currentFileTransferred = state.currentFileTransferred,
                                currentFileSize = state.currentFileSize
                            )
                            if (sessionId > 0L) {
                                activity.runOnUiThread {
                                    if (!activity.isDestroyed && !activity.isFinishing) {
                                        ActiveTransferRegistry.updateProgress(sessionId, state)
                                    }
                                }
                            }
                        }
                    },
                    object : SftpProtocolManager.DownloadControl {
                        override fun isCancelled(): Boolean = cancelRequested.get() || progressWindow.isCancelled
                    }
                )
                val outcome = RemoteDownloadOutcomeClassifier.fromResult(result, cancelRequested.get() || progressWindow.isCancelled)
                registerReusableDownloads(activity, sessionFileCoordinator, sourceItems, outcome)
                activity.runOnUiThread {
                    finishOnUiThread(activity, progressWindow, sessionId, outcome, onOutcome, onFinish)
                }
            } catch (t: Throwable) {
                val outcome = RemoteDownloadOutcomeClassifier.fromThrowable(t)
                activity.runOnUiThread {
                    finishOnUiThread(activity, progressWindow, sessionId, outcome, onOutcome, onFinish)
                }
            }
        }
    }

    private fun registerReusableDownloads(
        activity: SimpleActivity,
        sessionFileCoordinator: SessionFileCoordinator,
        sourceItems: List<FileDirItem>,
        outcome: RemoteDownloadOutcome
    ) {
        if (outcome.status != ActiveTransferStatus.SUCCESS || sourceItems.size != outcome.downloadedLocalPaths.size) {
            return
        }
        sourceItems.zip(outcome.downloadedLocalPaths).forEach { (source, localPath) ->
            if (localPath.isNotBlank()) {
                sessionFileCoordinator.registerDownloadedLocalForVirtualFile(activity, source.path, localPath)
            }
        }
    }

    private fun finishOnUiThread(
        activity: SimpleActivity,
        progressWindow: TransferProgressWindow,
        sessionId: Long,
        outcome: RemoteDownloadOutcome,
        onOutcome: (RemoteDownloadOutcome) -> Unit,
        onFinish: () -> Unit
    ) {
        try {
            progressWindow.dismiss()
            if (sessionId > 0L) {
                ActiveTransferRegistry.markTerminal(sessionId, outcome.status, outcome.messageCn.ifBlank { defaultTerminalMessage(activity, outcome.status) })
                activity.window?.decorView?.postDelayed({
                    ActiveTransferRegistry.clearSession(sessionId)
                }, TERMINAL_ROW_STATE_CLEAR_DELAY_MS)
            }
            if (!activity.isDestroyed && !activity.isFinishing) {
                onOutcome(outcome)
            }
        } finally {
            onFinish()
        }
    }

    private fun defaultTerminalMessage(activity: SimpleActivity, status: ActiveTransferStatus): String {
        return when (status) {
            ActiveTransferStatus.SUCCESS -> activity.getString(R.string.transfer_done_success)
            ActiveTransferStatus.PARTIAL -> activity.getString(R.string.transfer_done_partial)
            ActiveTransferStatus.CANCELLED -> activity.getString(R.string.transfer_done_cancelled)
            ActiveTransferStatus.FAILED -> activity.getString(R.string.transfer_done_failed)
            else -> ""
        }
    }
}
