package org.fossify.filemanager.helpers

import android.net.Uri
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import com.termux.sessionsync.SessionFileCoordinator
import com.termux.sessionsync.SftpProtocolManager
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getAndroidSAFFileItems
import org.fossify.commons.extensions.getAndroidSAFUri
import org.fossify.commons.extensions.getDocumentFile
import org.fossify.commons.extensions.getIsPathDirectory
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.isRestrictedSAFOnlyRoot
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.filemanager.extensions.sharePaths
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ShareSelectionWorkflow(
    private val activity: BaseSimpleActivity,
    private val sessionFileCoordinator: SessionFileCoordinator = SessionFileCoordinator.getInstance(),
    private val shouldShowHidden: () -> Boolean
) {
    private data class SharePreparationResult(
        val success: Boolean,
        val paths: ArrayList<String> = arrayListOf(),
        val cancelled: Boolean = false,
        val message: String = ""
    )

    fun canShare(selectedItems: List<FileDirItem>): Boolean {
        return ShareSelectionPlanner.build(selectedItems) {
            sessionFileCoordinator.isVirtualPath(activity, it)
        }.hasShareableItems
    }

    fun share(selectedItems: List<FileDirItem>, onFinished: () -> Unit) {
        val plan = ShareSelectionPlanner.build(selectedItems) {
            sessionFileCoordinator.isVirtualPath(activity, it)
        }
        if (!plan.hasShareableItems) {
            activity.toast("没有可分享的文件。")
            onFinished()
            return
        }

        if (plan.needsPreparationDialog) {
            shareWithPreparationDialog(plan, onFinished)
        } else {
            ensureBackgroundThread {
                val result = prepareSharePaths(plan, AtomicBoolean(false), null)
                activity.runOnUiThread {
                    handlePreparationResult(result, onFinished)
                }
            }
        }
    }

    private fun shareWithPreparationDialog(
        plan: ShareSelectionPlan,
        onFinished: () -> Unit
    ) {
        if (activity.isDestroyed || activity.isFinishing) {
            return
        }

        val cancelled = AtomicBoolean(false)
        val progressDialog = activity.getAlertDialogBuilder()
            .setTitle("准备分享")
            .setMessage("正在准备文件...")
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .create()

        progressDialog.setOnShowListener {
            progressDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                cancelled.set(true)
                it.isEnabled = false
                progressDialog.setMessage("正在取消...")
            }
        }

        try {
            progressDialog.show()
        } catch (_: Exception) {
        }

        val lastProgressAt = AtomicLong(0L)
        val progress: (String) -> Unit = { message ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastProgressAt.get() >= PROGRESS_THROTTLE_MS || message.startsWith("正在完成")) {
                lastProgressAt.set(now)
                activity.runOnUiThread {
                    if (!activity.isDestroyed && !activity.isFinishing && progressDialog.isShowing) {
                        progressDialog.setMessage(message)
                    }
                }
            }
        }

        ensureBackgroundThread {
            val result = prepareSharePaths(plan, cancelled, progress)
            activity.runOnUiThread {
                try {
                    if (progressDialog.isShowing) {
                        progressDialog.dismiss()
                    }
                } catch (_: Exception) {
                }
                handlePreparationResult(result, onFinished)
            }
        }
    }

    private fun prepareSharePaths(
        plan: ShareSelectionPlan,
        cancelled: AtomicBoolean,
        progress: ((String) -> Unit)?
    ): SharePreparationResult {
        return try {
            cleanupStaleShareCaches()
            val sharePaths = ArrayList<String>()

            collectLocalItems(plan.localItems, sharePaths, cancelled)
            if (cancelled.get()) {
                return SharePreparationResult(success = false, cancelled = true, message = "分享已取消。")
            }

            if (plan.remoteItems.isNotEmpty()) {
                progress?.invoke("正在下载服务器文件...")
                val remotePreparation = prepareRemoteItems(plan.remoteItems, sharePaths, cancelled, progress)
                if (!remotePreparation.success) {
                    return remotePreparation
                }
            }

            val uniquePaths = sharePaths.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toCollection(ArrayList())

            if (uniquePaths.isEmpty()) {
                SharePreparationResult(success = false, message = "没有可分享的文件。")
            } else {
                progress?.invoke("正在完成分享准备...")
                SharePreparationResult(success = true, paths = uniquePaths)
            }
        } catch (_: ShareCancelledException) {
            SharePreparationResult(success = false, cancelled = true, message = "分享已取消。")
        } catch (e: Exception) {
            SharePreparationResult(success = false, message = e.message?.takeIf { it.isNotBlank() } ?: "分享准备失败。")
        }
    }

    private fun prepareRemoteItems(
        remoteItems: List<FileDirItem>,
        sharePaths: ArrayList<String>,
        cancelled: AtomicBoolean,
        progress: ((String) -> Unit)?
    ): SharePreparationResult {
        val cacheDirectory = createShareCacheDirectory()
            ?: return SharePreparationResult(success = false, message = "无法创建分享缓存目录。")

        val remotePaths = remoteItems.map { it.path }
        val result = sessionFileCoordinator.downloadVirtualPaths(
            activity,
            remotePaths,
            cacheDirectory.absolutePath,
            object : SftpProtocolManager.DownloadProgressListener {
                override fun onProgress(downloadProgress: SftpProtocolManager.DownloadProgress) {
                    progress?.invoke(buildDownloadProgressMessage(downloadProgress))
                }
            },
            object : SftpProtocolManager.DownloadControl {
                override fun isCancelled(): Boolean = cancelled.get()
            }
        )

        if (cancelled.get() || isCancelledMessage(result.messageCn)) {
            return SharePreparationResult(success = false, cancelled = true, message = "分享已取消。")
        }

        if (!result.success) {
            val message = result.messageCn.ifBlank { "服务器文件下载失败，无法分享。" }
            return SharePreparationResult(success = false, message = message)
        }

        result.downloadedLocalPaths.forEach { localPath ->
            collectPath(localPath, sharePaths, cancelled)
        }
        return SharePreparationResult(success = true)
    }

    private fun collectLocalItems(
        localItems: List<FileDirItem>,
        sharePaths: ArrayList<String>,
        cancelled: AtomicBoolean
    ) {
        localItems.forEach { item ->
            collectPath(item.path, sharePaths, cancelled)
        }
    }

    private fun collectPath(
        path: String,
        sharePaths: ArrayList<String>,
        cancelled: AtomicBoolean
    ) {
        if (cancelled.get()) throw ShareCancelledException()
        val normalizedPath = path.trim()
        if (normalizedPath.isEmpty()) return

        if (Uri.parse(normalizedPath).scheme == "content") {
            sharePaths.add(normalizedPath)
            return
        }

        if (activity.getIsPathDirectory(normalizedPath)) {
            collectDirectory(normalizedPath, sharePaths, cancelled)
        } else {
            val file = File(normalizedPath)
            if (file.exists() || activity.isRestrictedSAFOnlyRoot(normalizedPath) || activity.isPathOnOTG(normalizedPath)) {
                sharePaths.add(normalizedPath)
            }
        }
    }

    private fun collectDirectory(
        path: String,
        sharePaths: ArrayList<String>,
        cancelled: AtomicBoolean
    ) {
        val showHidden = shouldShowHidden()
        when {
            activity.isRestrictedSAFOnlyRoot(path) -> {
                activity.getAndroidSAFFileItems(path, showHidden, false) { files ->
                    files.sortedBy { it.name.lowercase() }.forEach { child ->
                        if (cancelled.get()) throw ShareCancelledException()
                        if (child.isDirectory) {
                            collectPath(child.path, sharePaths, cancelled)
                        } else {
                            sharePaths.add(activity.getAndroidSAFUri(child.path).toString())
                        }
                    }
                }
            }

            activity.isPathOnOTG(path) -> {
                collectDocumentFile(activity.getDocumentFile(path), sharePaths, cancelled, showHidden)
            }

            else -> {
                File(path).listFiles()
                    ?.filter { showHidden || !it.name.startsWith('.') }
                    ?.sortedBy { it.name.lowercase() }
                    ?.forEach { child ->
                        if (cancelled.get()) throw ShareCancelledException()
                        if (child.isDirectory) {
                            collectPath(child.absolutePath, sharePaths, cancelled)
                        } else {
                            sharePaths.add(child.absolutePath)
                        }
                    }
            }
        }
    }

    private fun collectDocumentFile(
        documentFile: DocumentFile?,
        sharePaths: ArrayList<String>,
        cancelled: AtomicBoolean,
        showHidden: Boolean
    ) {
        if (cancelled.get()) throw ShareCancelledException()
        if (documentFile == null) return

        if (documentFile.isDirectory) {
            documentFile.listFiles()
                .filter { showHidden || !(it.name ?: "").startsWith(".") }
                .sortedBy { (it.name ?: "").lowercase() }
                .forEach { collectDocumentFile(it, sharePaths, cancelled, showHidden) }
        } else {
            sharePaths.add(documentFile.uri.toString())
        }
    }

    private fun handlePreparationResult(
        result: SharePreparationResult,
        onFinished: () -> Unit
    ) {
        if (activity.isDestroyed || activity.isFinishing) {
            onFinished()
            return
        }

        when {
            result.success -> {
                activity.sharePaths(result.paths)
                onFinished()
            }

            result.cancelled -> {
                activity.toast(result.message.ifBlank { "分享已取消。" })
                onFinished()
            }

            else -> {
                activity.toast(result.message.ifBlank { "分享准备失败。" })
                onFinished()
            }
        }
    }

    private fun createShareCacheDirectory(): File? {
        val root = File(activity.cacheDir, SHARE_CACHE_ROOT)
        if (!root.exists() && !root.mkdirs()) {
            return null
        }

        val directory = File(root, "${System.currentTimeMillis()}-${UUID.randomUUID()}")
        return if (directory.mkdirs()) directory else null
    }

    private fun cleanupStaleShareCaches() {
        val root = File(activity.cacheDir, SHARE_CACHE_ROOT)
        if (!root.exists()) return

        val now = System.currentTimeMillis()
        root.listFiles()?.forEach { child ->
            if (now - child.lastModified() > SHARE_CACHE_TTL_MS) {
                child.deleteRecursively()
            }
        }
    }

    private fun buildDownloadProgressMessage(progress: SftpProtocolManager.DownloadProgress): String {
        val count = "${progress.completedFiles + progress.failedFiles}/${progress.totalFiles}"
        val bytes = if (progress.totalBytes > 0L) {
            "${progress.transferredBytes.formatSize()} / ${progress.totalBytes.formatSize()}"
        } else {
            "${progress.transferredBytes.formatSize()} / ?"
        }
        val current = progress.currentFile.takeIf { it.isNotBlank() } ?: "服务器文件"
        return "正在下载服务器文件...\n$count\n$bytes\n$current"
    }

    private fun isCancelledMessage(message: String): Boolean {
        return message.contains("取消") || message.contains("cancel", ignoreCase = true)
    }

    private class ShareCancelledException : Exception()

    companion object {
        private const val SHARE_CACHE_ROOT = "file-manager-share"
        private const val PROGRESS_THROTTLE_MS = 250L
        private val SHARE_CACHE_TTL_MS = TimeUnit.HOURS.toMillis(24)
    }
}

internal data class ShareSelectionPlan(
    val localItems: List<FileDirItem>,
    val remoteItems: List<FileDirItem>,
    val needsPreparationDialog: Boolean
) {
    val hasShareableItems: Boolean get() = localItems.isNotEmpty() || remoteItems.isNotEmpty()
}

internal object ShareSelectionPlanner {
    fun build(
        selectedItems: List<FileDirItem>,
        isVirtualPath: (String) -> Boolean = { false }
    ): ShareSelectionPlan {
        val localItems = ArrayList<FileDirItem>()
        val remoteItems = ArrayList<FileDirItem>()
        selectedItems.forEach { item ->
            if (item.path.isBlank()) return@forEach
            if (isVirtualPath(item.path)) {
                remoteItems.add(item)
            } else {
                localItems.add(item)
            }
        }

        return ShareSelectionPlan(
            localItems = localItems,
            remoteItems = remoteItems,
            needsPreparationDialog = remoteItems.isNotEmpty() || localItems.any { it.isDirectory }
        )
    }
}
