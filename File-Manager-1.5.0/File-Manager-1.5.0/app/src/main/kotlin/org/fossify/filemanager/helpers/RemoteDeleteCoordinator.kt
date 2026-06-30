package org.fossify.filemanager.helpers

import com.termux.sessionsync.SessionFileCoordinator
import com.termux.sessionsync.SftpProtocolManager
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.SimpleActivity
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val REMOTE_DELETE_TERMINAL_CLEAR_DELAY_MS = 1_600L

data class RemoteDeleteOutcome(
    val status: ActiveTransferStatus,
    val totalItems: Int,
    val deletedItems: Int,
    val failedItems: Int,
    val skippedItems: Int,
    val elapsedMs: Long,
    val deletedVirtualPaths: ArrayList<String>,
    val failedMessages: ArrayList<String>,
    val messageCn: String,
    val throwable: Throwable? = null
)

object RemoteDeleteOutcomeClassifier {
    fun fromResult(result: SftpProtocolManager.RemoteDeleteResult, cancelRequested: Boolean): RemoteDeleteOutcome {
        val status = when {
            result.success -> ActiveTransferStatus.SUCCESS
            cancelRequested || result.cancelled || isCancelledMessage(result.messageCn) -> ActiveTransferStatus.CANCELLED
            result.deletedItems > 0 -> ActiveTransferStatus.PARTIAL
            else -> ActiveTransferStatus.FAILED
        }
        return RemoteDeleteOutcome(
            status = status,
            totalItems = result.totalItems,
            deletedItems = result.deletedItems,
            failedItems = result.failedItems,
            skippedItems = result.skippedItems,
            elapsedMs = result.elapsedMs,
            deletedVirtualPaths = ArrayList(result.deletedVirtualPaths),
            failedMessages = ArrayList(result.itemResults.filter { !it.success }.map { item ->
                val name = item.displayName.ifBlank { item.remotePath.ifBlank { item.virtualPath } }
                if (item.messageCn.isBlank()) name else "$name：${item.messageCn}"
            }),
            messageCn = result.messageCn
        )
    }

    fun fromThrowable(t: Throwable): RemoteDeleteOutcome {
        val msg = t.message?.trim().orEmpty()
        return RemoteDeleteOutcome(
            status = ActiveTransferStatus.FAILED,
            totalItems = 0,
            deletedItems = 0,
            failedItems = 0,
            skippedItems = 0,
            elapsedMs = 0L,
            deletedVirtualPaths = arrayListOf(),
            failedMessages = arrayListOf(msg.ifBlank { "未知错误" }),
            messageCn = msg,
            throwable = t
        )
    }

    fun isCancelledMessage(message: String): Boolean = message.contains("已取消")
}

object RemoteDeleteCoordinator {
    fun shouldShowProgressWindow(sourceItems: List<FileDirItem>): Boolean {
        return sourceItems.size > 5 || sourceItems.any { it.isDirectory }
    }

    fun start(
        activity: SimpleActivity,
        sessionFileCoordinator: SessionFileCoordinator,
        sourceItems: List<FileDirItem>,
        title: String = "服务器删除中",
        onOutcome: (RemoteDeleteOutcome) -> Unit,
        onFinish: () -> Unit = {}
    ) {
        if (activity.isDestroyed || activity.isFinishing || sourceItems.isEmpty()) {
            onFinish()
            return
        }

        val sourcePaths = sourceItems.map { it.path }
        val initialFileName = sourceItems.firstOrNull()?.name?.ifBlank { sourceItems.firstOrNull()?.path?.substringAfterLast('/').orEmpty() } ?: "准备中..."
        val showWindow = shouldShowProgressWindow(sourceItems)
        val sessionId = ActiveTransferRegistry.beginSession(
            sourceTopLevelPaths = sourcePaths,
            destinationPath = "",
            mode = ActiveTransferMode.REMOTE_DELETE,
            title = title,
            initialFileName = initialFileName
        )
        val cancelRequested = AtomicBoolean(false)
        var progressWindow: TransferProgressWindow? = null
        if (showWindow) {
            progressWindow = TransferProgressWindow(
                activity = activity,
                title = title,
                cancelLabel = activity.getString(R.string.transfer_cancel),
                initialFileName = initialFileName,
                mode = ActiveTransferMode.REMOTE_DELETE,
                isFolderTransfer = sourceItems.any { it.isDirectory },
                onCancel = {
                    cancelRequested.set(true)
                    progressWindow?.updateMessage(activity.getString(R.string.transfer_cancelling))
                    if (sessionId > 0L) {
                        activity.runOnUiThread { ActiveTransferRegistry.markCancelling(sessionId) }
                    }
                }
            )
            progressWindow?.show()
        }

        ensureBackgroundThread {
            try {
                val result = sessionFileCoordinator.deleteVirtualPaths(
                    activity.applicationContext,
                    sourcePaths,
                    object : SftpProtocolManager.RemoteDeleteProgressListener {
                        override fun onProgress(progress: SftpProtocolManager.RemoteDeleteProgress) {
                            val topLevelCompleted = progress.successItems + progress.failedItems + progress.skippedItems
                            val hasEntryProgress = progress.currentEntryDone > 0L || progress.currentEntryTotal > 0L
                            val entryText = if (progress.currentEntryTotal > 0L && progress.currentEntryTotal != progress.currentEntryDone) {
                                "${progress.currentEntryDone}/${progress.currentEntryTotal}"
                            } else {
                                "${progress.currentEntryDone} 项"
                            }
                            val detail = if (hasEntryProgress) {
                                "目录条目 $entryText，成功 ${progress.successItems}，失败 ${progress.failedItems}，跳过 ${progress.skippedItems}，耗时 ${formatElapsed(progress.elapsedMs)}"
                            } else {
                                "成功 ${progress.successItems}，失败 ${progress.failedItems}，跳过 ${progress.skippedItems}，耗时 ${formatElapsed(progress.elapsedMs)}"
                            }
                            val totalUnits = if (hasEntryProgress) progress.currentEntryTotal.coerceAtLeast(progress.currentEntryDone).coerceAtLeast(1L) else progress.totalItems.toLong().coerceAtLeast(1L)
                            val doneUnits = if (hasEntryProgress) progress.currentEntryDone else topLevelCompleted.toLong()
                            val state = TransferProgressState(
                                phaseLabel = progress.stageLabelCn.ifBlank { title },
                                currentFile = progress.currentDisplayName.ifBlank { progress.currentRemotePath.ifBlank { initialFileName } },
                                totalFiles = progress.totalItems.coerceAtLeast(sourceItems.size).coerceAtLeast(1),
                                completedFiles = progress.successItems + progress.skippedItems,
                                failedFiles = progress.failedItems,
                                totalBytes = totalUnits.coerceAtLeast(1L),
                                transferredBytes = doneUnits.coerceIn(0L, totalUnits.coerceAtLeast(1L)),
                                currentFileTransferred = doneUnits.coerceAtLeast(0L),
                                currentFileSize = totalUnits.coerceAtLeast(0L),
                                speedBytesPerSecond = 0L,
                                detailMessage = if (progress.messageCn.isBlank()) detail else "${progress.messageCn}\n$detail"
                            )
                            progressWindow?.updateDownload(
                                phaseLabel = state.phaseLabel,
                                currentFile = state.currentFile,
                                completedFiles = state.completedFiles,
                                failedFiles = state.failedFiles,
                                totalFiles = state.totalFiles,
                                transferredBytes = state.transferredBytes,
                                totalBytes = state.totalBytes,
                                currentFileTransferred = state.currentFileTransferred,
                                currentFileSize = state.currentFileSize,
                                detailMessage = state.detailMessage,
                                force = true
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
                    object : SftpProtocolManager.RemoteDeleteControl {
                        override fun isCancelled(): Boolean = cancelRequested.get() || progressWindow?.isCancelled == true
                    }
                )
                val outcome = RemoteDeleteOutcomeClassifier.fromResult(result, cancelRequested.get() || progressWindow?.isCancelled == true)
                activity.runOnUiThread {
                    finishOnUiThread(activity, progressWindow, sessionId, outcome, onOutcome, onFinish)
                }
            } catch (t: Throwable) {
                val outcome = RemoteDeleteOutcomeClassifier.fromThrowable(t)
                activity.runOnUiThread {
                    finishOnUiThread(activity, progressWindow, sessionId, outcome, onOutcome, onFinish)
                }
            }
        }
    }

    private fun finishOnUiThread(
        activity: SimpleActivity,
        progressWindow: TransferProgressWindow?,
        sessionId: Long,
        outcome: RemoteDeleteOutcome,
        onOutcome: (RemoteDeleteOutcome) -> Unit,
        onFinish: () -> Unit
    ) {
        try {
            progressWindow?.dismiss()
            if (sessionId > 0L) {
                ActiveTransferRegistry.markTerminal(sessionId, outcome.status, outcome.messageCn.ifBlank { defaultTerminalMessage(outcome.status) })
                activity.window?.decorView?.postDelayed({
                    ActiveTransferRegistry.clearSession(sessionId)
                }, REMOTE_DELETE_TERMINAL_CLEAR_DELAY_MS)
            }
            if (!activity.isDestroyed && !activity.isFinishing) {
                onOutcome(outcome)
            }
        } finally {
            onFinish()
        }
    }

    private fun defaultTerminalMessage(status: ActiveTransferStatus): String {
        return when (status) {
            ActiveTransferStatus.SUCCESS -> "删除完成"
            ActiveTransferStatus.PARTIAL -> "删除部分完成"
            ActiveTransferStatus.CANCELLED -> "删除已取消"
            ActiveTransferStatus.FAILED -> "删除失败"
            else -> ""
        }
    }

    fun formatElapsed(elapsedMs: Long): String {
        val seconds = elapsedMs.coerceAtLeast(0L) / 1000.0
        return if (seconds < 60.0) {
            String.format(Locale.getDefault(), "%.1fs", seconds)
        } else {
            val minutes = (seconds / 60).toInt()
            val remain = (seconds % 60).toInt()
            "${minutes}m ${remain}s"
        }
    }
}
