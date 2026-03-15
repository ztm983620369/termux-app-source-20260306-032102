package org.fossify.filemanager.fragments

import android.content.Context
import android.util.AttributeSet
import android.widget.RelativeLayout
import com.termux.bridge.FileOpenBridge
import com.termux.bridge.FileOpenRequest
import com.termux.sessionsync.SessionFileCoordinator
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.VIEW_TYPE_LIST
import org.fossify.commons.helpers.ensureBackgroundThread
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
import org.fossify.filemanager.helpers.OPEN_AS_IMAGE
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
                if (shouldOpenRemoteImageWithSystemViewer(openRequest)) {
                    activity?.openPath(path, false, OPEN_AS_IMAGE)
                } else {
                    FileOpenBridge.dispatch(openRequest)
                }
            } else {
                activity?.tryOpenPathIntent(path, false)
            }
        }
    }

    private fun shouldOpenRemoteImageWithSystemViewer(openRequest: FileOpenRequest): Boolean {
        if (openRequest.originType != FileOpenRequest.ORIGIN_SFTP_VIRTUAL) return false
        val mimeType = openRequest.mimeType?.lowercase() ?: return false
        return mimeType.startsWith("image/")
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
        val progressDialog = simpleActivity.getAlertDialogBuilder()
            .setTitle("服务器删除中")
            .setMessage("正在准备删除...")
            .setCancelable(false)
            .create()
        try {
            progressDialog.show()
        } catch (_: Exception) {
        }

        ensureBackgroundThread {
            var deletedCount = 0
            val deletedPaths = ArrayList<String>()
            val failed = ArrayList<String>()
            files.forEachIndexed { index, file ->
                simpleActivity.runOnUiThread {
                    if (!simpleActivity.isDestroyed && !simpleActivity.isFinishing && progressDialog.isShowing) {
                        progressDialog.setMessage("正在删除 ${index + 1}/${files.size}\n${file.name.ifBlank { file.path.getFilenameFromPath() }}")
                    }
                }
                val result = SessionFileCoordinator.getInstance().deleteVirtualPath(simpleActivity.applicationContext, file.path)
                if (result.success) {
                    deletedCount++
                    deletedPaths.add(file.path)
                } else {
                    failed.add(file.name.ifBlank { file.path.getFilenameFromPath() })
                }
            }

            simpleActivity.runOnUiThread {
                try {
                    progressDialog.dismiss()
                } catch (_: Exception) {
                }
                when {
                    failed.isEmpty() -> simpleActivity.toast("服务器删除完成：$deletedCount 项")
                    deletedCount > 0 -> simpleActivity.toast("服务器删除部分完成：$deletedCount/${files.size}，失败 ${failed.joinToString()}")
                    else -> simpleActivity.toast("服务器删除失败：${failed.joinToString()}")
                }
                deletedPaths.forEach { path -> simpleActivity.config.removeFavorite(path) }
                onFinished()
            }
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
