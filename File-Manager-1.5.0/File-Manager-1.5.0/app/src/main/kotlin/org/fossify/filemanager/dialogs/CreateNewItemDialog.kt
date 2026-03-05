package org.fossify.filemanager.dialogs

import android.view.View
import androidx.appcompat.app.AlertDialog
import com.termux.sessionsync.SessionFileCoordinator
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.SimpleActivity
import org.fossify.filemanager.databinding.DialogCreateNewBinding
import org.fossify.filemanager.extensions.isPathOnRoot
import org.fossify.filemanager.helpers.RootHelpers
import java.io.File
import java.io.IOException

class CreateNewItemDialog(
    val activity: SimpleActivity,
    val path: String,
    val callback: (success: Boolean, createdPath: String?) -> Unit
) {
    private val binding = DialogCreateNewBinding.inflate(activity.layoutInflater)
    private val sessionFileCoordinator = SessionFileCoordinator.getInstance()

    init {
        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.create_new) { alertDialog ->
                    alertDialog.showKeyboard(binding.itemTitle)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener {
                        val name = binding.itemTitle.value
                        if (name.isEmpty()) {
                            activity.toast(R.string.empty_name)
                        } else if (name.isAValidFilename()) {
                            val isDirectory = binding.dialogRadioGroup.checkedRadioButtonId == R.id.dialog_radio_directory
                            val newPath = buildChildPath(path, name)
                            val isVirtualPath = sessionFileCoordinator.isVirtualPath(activity, path)
                            val isStaleVirtualPath = sessionFileCoordinator.isStaleVirtualPath(activity, path)
                            if (isStaleVirtualPath) {
                                activity.showErrorToast("SFTP \u4f1a\u8bdd\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u65b0\u9009\u62e9\u4f1a\u8bdd\u3002")
                                callback(false, null)
                                return@OnClickListener
                            }
                            if (!isVirtualPath && activity.getDoesFilePathExist(newPath)) {
                                activity.toast(R.string.name_taken)
                                return@OnClickListener
                            }

                            if (isVirtualPath) {
                                createVirtualItem(path, newPath, name, isDirectory, alertDialog) {
                                    callback(it, null)
                                }
                                return@OnClickListener
                            }

                            if (isDirectory) {
                                createDirectory(newPath, alertDialog) {
                                    callback(it, null)
                                }
                            } else {
                                createFile(newPath, alertDialog) {
                                    callback(it, null)
                                }
                            }
                        } else {
                            activity.toast(R.string.invalid_name)
                        }
                    })
                }
            }
    }

    private fun createDirectory(path: String, alertDialog: AlertDialog, callback: (Boolean) -> Unit) {
        when {
            activity.needsStupidWritePermissions(path) -> activity.handleSAFDialog(path) {
                if (!it) {
                    callback(false)
                    return@handleSAFDialog
                }

                val documentFile = activity.getDocumentFile(path.getParentPath())
                if (documentFile == null) {
                    showCreateError(path, true, "")
                    callback(false)
                    return@handleSAFDialog
                }
                if (documentFile.createDirectory(path.getFilenameFromPath()) != null) {
                    success(alertDialog, path)
                } else {
                    showCreateError(path, true, "")
                    callback(false)
                }
            }

            isRPlus() || !activity.isPathOnRoot(path) -> {
                if (activity.isRestrictedSAFOnlyRoot(path)) {
                    activity.handleAndroidSAFDialog(path) {
                        if (!it) {
                            callback(false)
                            return@handleAndroidSAFDialog
                        }
                        if (activity.createAndroidSAFDirectory(path)) {
                            success(alertDialog, path)
                        } else {
                            showCreateError(path, true, "")
                            callback(false)
                        }
                    }
                } else {
                    if (File(path).mkdirs()) {
                        success(alertDialog, path)
                    } else {
                        showCreateError(path, true, "")
                        callback(false)
                    }
                }
            }

            else -> {
                RootHelpers(activity).createFileFolder(path, false) {
                    if (it) {
                        success(alertDialog, path)
                    } else {
                        callback(false)
                    }
                }
            }
        }
    }

    private fun createFile(path: String, alertDialog: AlertDialog, callback: (Boolean) -> Unit) {
        try {
            when {
                activity.isRestrictedSAFOnlyRoot(path) -> {
                    activity.handleAndroidSAFDialog(path) {
                        if (!it) {
                            callback(false)
                            return@handleAndroidSAFDialog
                        }
                        if (activity.createAndroidSAFFile(path)) {
                            success(alertDialog, path)
                        } else {
                            showCreateError(path, false, "")
                            callback(false)
                        }
                    }
                }

                activity.needsStupidWritePermissions(path) -> {
                    activity.handleSAFDialog(path) {
                        if (!it) {
                            callback(false)
                            return@handleSAFDialog
                        }

                        val documentFile = activity.getDocumentFile(path.getParentPath())
                        if (documentFile == null) {
                            showCreateError(path, false, "")
                            callback(false)
                            return@handleSAFDialog
                        }
                        if (documentFile.createFile(path.getMimeType(), path.getFilenameFromPath()) != null) {
                            success(alertDialog, path)
                        } else {
                            showCreateError(path, false, "")
                            callback(false)
                        }
                    }
                }

                isRPlus() || !activity.isPathOnRoot(path) -> {
                    if (File(path).createNewFile()) {
                        success(alertDialog, path)
                    } else {
                        showCreateError(path, false, "")
                        callback(false)
                    }
                }

                else -> {
                    RootHelpers(activity).createFileFolder(path, true) {
                        if (it) {
                            success(alertDialog, path)
                        } else {
                            callback(false)
                        }
                    }
                }
            }
        } catch (exception: IOException) {
            activity.showErrorToast(exception)
            callback(false)
        }
    }

    private fun createVirtualItem(
        parentPath: String,
        newPath: String,
        name: String,
        isDirectory: Boolean,
        alertDialog: AlertDialog,
        callback: (Boolean) -> Unit
    ) {
        ensureBackgroundThread {
            val result = sessionFileCoordinator.createVirtualItem(activity.applicationContext, parentPath, name, isDirectory)
            activity.runOnUiThread {
                if (result.success) {
                    success(alertDialog, newPath)
                } else {
                    showCreateError(newPath, isDirectory, result.messageCn)
                    callback(false)
                }
            }
        }
    }

    private fun buildChildPath(parentPath: String, name: String): String {
        val normalizedParent = parentPath.trimEnd('/')
        return if (normalizedParent.isEmpty() || normalizedParent == "/") {
            "/$name"
        } else {
            "$normalizedParent/$name"
        }
    }

    private fun showCreateError(path: String, isDirectory: Boolean, detail: String) {
        val message = detail.trim().ifEmpty {
            val errorRes = if (isDirectory) R.string.could_not_create_folder else R.string.could_not_create_file
            String.format(activity.getString(errorRes), path)
        }
        activity.showErrorToast(message)
    }

    private fun success(alertDialog: AlertDialog, createdPath: String) {
        alertDialog.dismiss()
        callback(true, createdPath)
    }
}
