package org.fossify.filemanager.fragments

import android.content.Context
import android.util.AttributeSet
import android.widget.RelativeLayout
import com.termux.bridge.FileOpenBridge
import com.termux.bridge.FileOpenRequest
import com.termux.sessionsync.SessionFileCoordinator
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.VIEW_TYPE_LIST
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.views.MyFloatingActionButton
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.SimpleActivity
import org.fossify.filemanager.databinding.ItemsFragmentBinding
import org.fossify.filemanager.databinding.RecentsFragmentBinding
import org.fossify.filemanager.databinding.StorageFragmentBinding
import org.fossify.filemanager.extensions.config
import org.fossify.filemanager.extensions.isPathOnRoot
import org.fossify.filemanager.extensions.openPath
import org.fossify.filemanager.extensions.tryOpenPathIntent
import org.fossify.filemanager.helpers.ActiveTransferStatus
import org.fossify.filemanager.helpers.FileOpenStateMachine
import org.fossify.filemanager.helpers.RemoteDeleteCoordinator
import org.fossify.filemanager.helpers.RemoteDeleteOutcome
import org.fossify.filemanager.helpers.RootHelpers
import org.fossify.filemanager.interfaces.FileManagerDependencies

abstract class MyViewPagerFragment<BINDING : MyViewPagerFragment.InnerBinding>(context: Context, attributeSet: AttributeSet) :
    RelativeLayout(context, attributeSet) {
    protected var activity: SimpleActivity? = null
    protected var currentViewType = VIEW_TYPE_LIST
    protected lateinit var fileManagerDependencies: FileManagerDependencies

    var currentPath = ""
    var isGetContentIntent = false
    var isGetRingtonePicker = false
    var isPickMultipleIntent = false
    var wantedMimeTypes = listOf("")
    protected var isCreateDocumentIntent = false
    protected lateinit var innerBinding: BINDING

    protected val fileManagerEnvironment
        get() = fileManagerDependencies.environment

    protected val fileManagerControllerCommands
        get() = fileManagerDependencies.controllerCommands

    protected val fileManagerResultHandler
        get() = fileManagerDependencies.resultHandler

    fun bindDependencies(dependencies: FileManagerDependencies) {
        fileManagerDependencies = dependencies
    }

    protected fun clickedPath(path: String, openRequest: FileOpenRequest? = null) {
        if (isGetContentIntent || isCreateDocumentIntent) {
            fileManagerResultHandler.pickedPath(path)
        } else if (isGetRingtonePicker) {
            if (path.isAudioFast()) {
                fileManagerResultHandler.pickedRingtone(path)
            } else {
                activity?.toast(R.string.select_audio_file)
            }
        } else {
            if (openRequest != null) {
                when (val action = FileOpenStateMachine.decide(openRequest.toFileOpenStateInput()).action) {
                    is FileOpenStateMachine.Action.OpenInEditor -> FileOpenBridge.dispatch(action.request)
                    is FileOpenStateMachine.Action.OpenWithSystemViewer -> {
                        activity?.openPath(action.path, action.forceChooser, action.openAsType)
                    }
                }
            } else {
                activity?.tryOpenPathIntent(path, false)
            }
        }
    }

    private fun FileOpenRequest.toFileOpenStateInput(): FileOpenStateMachine.Input {
        return FileOpenStateMachine.Input(
            path = path,
            displayName = displayName,
            extension = extension,
            mimeType = mimeType,
            request = this
        )
    }

    fun updateIsCreateDocumentIntent(isCreateDocumentIntent: Boolean) {
        val iconId = if (isCreateDocumentIntent) {
            R.drawable.ic_check_vector
        } else {
            R.drawable.ic_plus_vector
        }

        this.isCreateDocumentIntent = isCreateDocumentIntent
        val fabIcon = context.resources.getColoredDrawableWithColor(iconId, context.getProperPrimaryColor().getContrastColor())
        innerBinding.itemsFab?.setImageDrawable(fabIcon)
    }

    fun handleFileDeleting(files: ArrayList<FileDirItem>, hasFolder: Boolean) {
        val ctx = context ?: return
        if (files.isEmpty()) {
            return
        }

        val remoteFiles = ArrayList<FileDirItem>()
        val localFiles = ArrayList<FileDirItem>()
        files.forEach { file ->
            if (SessionFileCoordinator.getInstance().isVirtualPath(ctx, file.path)) {
                remoteFiles.add(file)
            } else {
                localFiles.add(file)
            }
        }

        if (remoteFiles.isNotEmpty()) {
            deleteRemoteFiles(remoteFiles) {
                if (localFiles.isNotEmpty()) {
                    deleteLocalFiles(localFiles, localFiles.any { it.isDirectory })
                } else {
                    refreshFragment()
                }
            }
            return
        }

        deleteLocalFiles(localFiles, hasFolder)
    }

    private fun deleteLocalFiles(files: ArrayList<FileDirItem>, hasFolder: Boolean) {
        val firstPath = files.firstOrNull()?.path ?: return
        val simpleActivity = activity as? SimpleActivity ?: return
        if (context?.isPathOnRoot(firstPath) == true) {
            RootHelpers(simpleActivity).deleteFiles(files)
            refreshFragment()
            return
        }

        simpleActivity.deleteFiles(files, hasFolder) {
            simpleActivity.runOnUiThread {
                if (!it) {
                    simpleActivity.toast(R.string.unknown_error_occurred)
                } else {
                    files.forEach { file -> simpleActivity.config.removeFavorite(file.path) }
                }
                refreshFragment()
            }
        }
    }

    private fun deleteRemoteFiles(files: ArrayList<FileDirItem>, onFinished: () -> Unit) {
        val simpleActivity = activity as? SimpleActivity ?: return
        RemoteDeleteCoordinator.start(
            activity = simpleActivity,
            sessionFileCoordinator = SessionFileCoordinator.getInstance(),
            sourceItems = files,
            onOutcome = { outcome ->
                cleanupDeletedRemoteFavorites(simpleActivity, files, outcome)
                showRemoteDeleteOutcome(simpleActivity, outcome)
            },
            onFinish = onFinished
        )
    }

    private fun cleanupDeletedRemoteFavorites(
        simpleActivity: SimpleActivity,
        files: ArrayList<FileDirItem>,
        outcome: RemoteDeleteOutcome
    ) {
        outcome.deletedVirtualPaths.forEach { path -> simpleActivity.config.removeFavorite(path) }
        val deletedParents = outcome.deletedVirtualPaths.filter { it.isNotBlank() }
        files.forEach { file ->
            if (deletedParents.any { parent -> file.path == parent || file.path.startsWith("$parent/") }) {
                simpleActivity.config.removeFavorite(file.path)
            }
        }
    }

    private fun showRemoteDeleteOutcome(simpleActivity: SimpleActivity, outcome: RemoteDeleteOutcome) {
        val elapsed = RemoteDeleteCoordinator.formatElapsed(outcome.elapsedMs)
        when (outcome.status) {
            ActiveTransferStatus.SUCCESS -> simpleActivity.toast("服务器删除完成：${outcome.deletedItems} 项，耗时 $elapsed")
            ActiveTransferStatus.CANCELLED -> simpleActivity.toast("服务器删除已取消：已删除 ${outcome.deletedItems}/${outcome.totalItems} 项")
            ActiveTransferStatus.PARTIAL -> showRemoteDeleteFailureDialog(simpleActivity, "服务器删除部分完成", outcome, elapsed)
            ActiveTransferStatus.FAILED -> showRemoteDeleteFailureDialog(simpleActivity, "服务器删除失败", outcome, elapsed)
            else -> simpleActivity.toast(outcome.messageCn.ifBlank { "服务器删除完成" })
        }
    }

    private fun showRemoteDeleteFailureDialog(
        simpleActivity: SimpleActivity,
        title: String,
        outcome: RemoteDeleteOutcome,
        elapsed: String
    ) {
        val failures = outcome.failedMessages.take(8)
        val more = (outcome.failedMessages.size - failures.size).coerceAtLeast(0)
        val message = buildString {
            append("成功 ${outcome.deletedItems}/${outcome.totalItems} 项")
            if (outcome.skippedItems > 0) append("，跳过 ${outcome.skippedItems} 项")
            append("，失败 ${outcome.failedItems} 项，耗时 $elapsed")
            if (failures.isNotEmpty()) {
                append("\n\n")
                failures.forEach { append("• ").append(it).append('\n') }
                if (more > 0) append("… 等 $more 项")
            } else if (outcome.messageCn.isNotBlank()) {
                append("\n\n").append(outcome.messageCn)
            }
        }.trim()
        try {
            simpleActivity.getAlertDialogBuilder()
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (_: Exception) {
            simpleActivity.toast(message)
        }
    }

    protected fun isProperMimeType(wantedMimeType: String, path: String, isDirectory: Boolean): Boolean {
        return if (wantedMimeType.isEmpty() || wantedMimeType == "*/*" || isDirectory) {
            true
        } else {
            val fileMimeType = path.getMimeType()
            if (wantedMimeType.endsWith("/*")) {
                fileMimeType.substringBefore("/").equals(wantedMimeType.substringBefore("/"), true)
            } else {
                fileMimeType.equals(wantedMimeType, true)
            }
        }
    }

    abstract fun setupFragment(activity: SimpleActivity)

    abstract fun onResume(textColor: Int)

    abstract fun refreshFragment()

    abstract fun searchQueryChanged(text: String)

    interface InnerBinding {
        val itemsFab: MyFloatingActionButton?
    }

    class ItemsInnerBinding(val binding: ItemsFragmentBinding) : InnerBinding {
        override val itemsFab: MyFloatingActionButton = binding.itemsFab
    }

    class RecentsInnerBinding(val binding: RecentsFragmentBinding) : InnerBinding {
        override val itemsFab: MyFloatingActionButton? = null
    }

    class StorageInnerBinding(val binding: StorageFragmentBinding) : InnerBinding {
        override val itemsFab: MyFloatingActionButton? = null
    }
}
