package org.fossify.commons.dialogs

import android.os.Environment
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import com.termux.sessionsync.FileRootResolver
import com.termux.sessionsync.SessionFileCoordinator
import com.termux.sessionsync.SessionTransport
import org.fossify.commons.R
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.adapters.FilepickerFavoritesAdapter
import org.fossify.commons.adapters.FilepickerItemsAdapter
import org.fossify.commons.databinding.DialogFilepickerBinding
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getAndroidSAFFileItems
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getDirectChildrenCount
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getFolderLastModifieds
import org.fossify.commons.extensions.getIsPathDirectory
import org.fossify.commons.extensions.getOTGItems
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getSomeAndroidSAFDocument
import org.fossify.commons.extensions.getSomeDocumentFile
import org.fossify.commons.extensions.getSomeDocumentSdk30
import org.fossify.commons.extensions.getTextSize
import org.fossify.commons.extensions.handleHiddenFolderPasswordProtection
import org.fossify.commons.extensions.handleLockedFolderOpening
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.getStorageDirectories
import org.fossify.commons.extensions.isAccessibleWithSAFSdk30
import org.fossify.commons.extensions.isInDownloadDir
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.commons.extensions.isRestrictedSAFOnlyRoot
import org.fossify.commons.extensions.isRestrictedWithSAFSdk30
import org.fossify.commons.extensions.isVisible
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.views.Breadcrumbs
import java.io.File

/**
 * The only filepicker constructor with a couple optional parameters
 *
 * @param activity has to be activity to avoid some Theme.AppCompat issues
 * @param currPath initial path of the dialog, defaults to the external storage
 * @param pickFile toggle used to determine if we are picking a file or a folder
 * @param showHidden toggle for showing hidden items, whose name starts with a dot
 * @param showFAB toggle the displaying of a Floating Action Button for creating new folders
 * @param callback the callback used for returning the selected file/folder
 */
class FilePickerDialog(
    private val activity: BaseSimpleActivity,
    private var currPath: String = Environment.getExternalStorageDirectory().toString(),
    private val pickFile: Boolean = true,
    private var showHidden: Boolean = false,
    private val showFAB: Boolean = false,
    private val canAddShowHiddenButton: Boolean = false,
    private val forceShowRoot: Boolean = false,
    private val showFavoritesButton: Boolean = false,
    private val showRationale: Boolean = true,
    private val enforceStorageRestrictions: Boolean = true,
    private val callback: (pickedPath: String) -> Unit
) : Breadcrumbs.BreadcrumbsListener {

    private val sessionFileCoordinator = SessionFileCoordinator.getInstance()
    private val termuxRootPath = activity.filesDir.absolutePath.trimEnd('/')
    private var showWorkspaceRoot = true
    private var mFirstUpdate = true
    private var mPrevPath = ""
    private var mScrollStates = HashMap<String, Parcelable>()

    private var mDialog: AlertDialog? = null
    private var mDialogView = DialogFilepickerBinding.inflate(activity.layoutInflater, null, false)

    init {
        sessionFileCoordinator.initialize(activity)
        currPath = normalizeInitialPath(currPath)

        mDialogView.filepickerBreadcrumbs.apply {
            listener = this@FilePickerDialog
            updateFontSize(activity.getTextSize(), false)
            isShownInDialog = true
        }

        mDialogView.filepickerToggleStorage.setOnClickListener {
            if (!showWorkspaceRoot) {
                showWorkspaceRoot = true
                tryUpdateItems()
            }
        }

        tryUpdateItems()
        setupFavorites()

        val builder = activity.getAlertDialogBuilder()
            .setNegativeButton(R.string.cancel, null)

        if (!pickFile) {
            builder.setPositiveButton(R.string.ok, null)
        }

        if (showFAB) {
            mDialogView.filepickerFab.apply {
                beVisible()
                setOnClickListener { createNewFolder() }
            }
        }

        val secondaryFabBottomMargin = activity.resources.getDimension(if (showFAB) R.dimen.secondary_fab_bottom_margin else R.dimen.activity_margin).toInt()
        mDialogView.filepickerFabsHolder.apply {
            (layoutParams as CoordinatorLayout.LayoutParams).bottomMargin = secondaryFabBottomMargin
        }

        mDialogView.filepickerPlaceholder.setTextColor(activity.getProperTextColor())
        mDialogView.filepickerFastscroller.updateColors(activity.getProperPrimaryColor())
        mDialogView.filepickerFabShowHidden.apply {
            beVisibleIf(!showHidden && canAddShowHiddenButton)
            setOnClickListener {
                activity.handleHiddenFolderPasswordProtection {
                    beGone()
                    showHidden = true
                    tryUpdateItems()
                }
            }
        }

        mDialogView.filepickerFavoritesLabel.text = "${activity.getString(R.string.favorites)}:"
        mDialogView.filepickerFabShowFavorites.apply {
            beVisibleIf(showFavoritesButton && context.baseConfig.favorites.isNotEmpty())
            setOnClickListener {
                if (mDialogView.filepickerFavoritesHolder.isVisible()) {
                    hideFavorites()
                } else {
                    showFavorites()
                }
            }
        }

        builder.apply {
            activity.setupDialogStuff(mDialogView.root, this, getTitle()) { alertDialog ->
                mDialog = alertDialog
                alertDialog.onBackPressedDispatcher.addCallback(alertDialog) {
                    val breadcrumbs = mDialogView.filepickerBreadcrumbs
                    if (breadcrumbs.getItemCount() > 1) {
                        breadcrumbs.removeBreadcrumb()
                        currPath = breadcrumbs.getLastItem().path.trimEnd('/')
                        tryUpdateItems()
                    } else {
                        isEnabled = false
                        alertDialog.onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }

        if (!pickFile) {
            mDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                verifyPath()
            }
        }
    }

    private fun getTitle() = if (pickFile) R.string.select_file else R.string.select_folder

    private fun createNewFolder() {
        CreateNewFolderDialog(activity, currPath) {
            callback(it)
            mDialog?.dismiss()
        }
    }

    private fun tryUpdateItems() {
        ensureBackgroundThread {
            if (showWorkspaceRoot) {
                activity.runOnUiThread {
                    mDialogView.filepickerPlaceholder.beGone()
                    updateItems(buildWorkspaceItems())
                }
                return@ensureBackgroundThread
            }

            getItems(currPath) {
                activity.runOnUiThread {
                    mDialogView.filepickerPlaceholder.beGone()
                    updateItems(it as ArrayList<FileDirItem>)
                }
            }
        }
    }

    private fun updateItems(items: ArrayList<FileDirItem>) {
        if (!containsDirectory(items) && !mFirstUpdate && !pickFile && !showFAB) {
            verifyPath()
            return
        }

        val displayItems = if (showWorkspaceRoot) {
            items
        } else {
            items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
        val adapter = FilepickerItemsAdapter(activity, displayItems, mDialogView.filepickerList) {
            if (showWorkspaceRoot) {
                currPath = (it as FileDirItem).path
                showWorkspaceRoot = false
                tryUpdateItems()
                return@FilepickerItemsAdapter
            }

            if ((it as FileDirItem).isDirectory) {
                activity.handleLockedFolderOpening(it.path) { success ->
                    if (success) {
                        currPath = it.path
                        tryUpdateItems()
                    }
                }
            } else if (pickFile) {
                currPath = it.path
                verifyPath()
            }
        }

        val layoutManager = mDialogView.filepickerList.layoutManager as LinearLayoutManager
        mScrollStates[mPrevPath.trimEnd('/')] = layoutManager.onSaveInstanceState()!!

        mDialogView.apply {
            filepickerList.adapter = adapter
            filepickerBreadcrumbs.setBreadcrumb(if (showWorkspaceRoot) WORKSPACE_ROOT_TITLE else currPath)

            if (root.context.areSystemAnimationsEnabled) {
                filepickerList.scheduleLayoutAnimation()
            }

            layoutManager.onRestoreInstanceState(mScrollStates[currPath.trimEnd('/')])
        }

        mFirstUpdate = false
        mPrevPath = currPath
    }

    private fun verifyPath() {
        if (showWorkspaceRoot) {
            return
        }

        if (sessionFileCoordinator.isVirtualPath(activity, currPath)) {
            sendSuccess()
            return
        }

        when {
            activity.isRestrictedSAFOnlyRoot(currPath) -> {
                val document = activity.getSomeAndroidSAFDocument(currPath) ?: return
                sendSuccessForDocumentFile(document)
            }

            activity.isPathOnOTG(currPath) -> {
                val fileDocument = activity.getSomeDocumentFile(currPath) ?: return
                sendSuccessForDocumentFile(fileDocument)
            }

            activity.isAccessibleWithSAFSdk30(currPath) -> {
                if (enforceStorageRestrictions) {
                    activity.handleSAFDialogSdk30(path = currPath, showRationale = showRationale) {
                        if (it) {
                            val document = activity.getSomeDocumentSdk30(currPath)
                            sendSuccessForDocumentFile(document ?: return@handleSAFDialogSdk30)
                        }
                    }
                } else {
                    sendSuccessForDirectFile()
                }

            }

            activity.isRestrictedWithSAFSdk30(currPath) -> {
                if (enforceStorageRestrictions) {
                    if (activity.isInDownloadDir(currPath)) {
                        sendSuccessForDirectFile()
                    } else {
                        activity.toast(R.string.system_folder_restriction, Toast.LENGTH_LONG)
                    }
                } else {
                    sendSuccessForDirectFile()
                }
            }

            else -> {
                sendSuccessForDirectFile()
            }
        }
    }

    private fun sendSuccessForDocumentFile(document: DocumentFile) {
        if ((pickFile && document.isFile) || (!pickFile && document.isDirectory)) {
            sendSuccess()
        }
    }

    private fun sendSuccessForDirectFile() {
        val file = File(currPath)
        if ((pickFile && file.isFile) || (!pickFile && file.isDirectory)) {
            sendSuccess()
        }
    }

    private fun sendSuccess() {
        currPath = if (currPath.length == 1) {
            currPath
        } else {
            currPath.trimEnd('/')
        }

        callback(currPath)
        mDialog?.dismiss()
    }

    private fun getItems(path: String, callback: (List<FileDirItem>) -> Unit) {
        if (sessionFileCoordinator.isVirtualPath(activity, path)) {
            val result = sessionFileCoordinator.listVirtualPath(activity, path)
            if (!result.success) {
                activity.runOnUiThread {
                    activity.toast(result.messageCn)
                }
                callback(arrayListOf())
                return
            }

            val remoteItems = ArrayList<FileDirItem>(result.entries.size)
            result.entries.forEach { entry ->
                remoteItems.add(
                    FileDirItem(
                        entry.localPath,
                        entry.name,
                        entry.directory,
                        0,
                        entry.size,
                        entry.modifiedMs
                    )
                )
            }
            callback(remoteItems)
            return
        }

        when {
            activity.isRestrictedSAFOnlyRoot(path) -> {
                activity.handleAndroidSAFDialog(path) {
                    activity.getAndroidSAFFileItems(path, showHidden) {
                        callback(it)
                    }
                }
            }

            activity.isPathOnOTG(path) -> activity.getOTGItems(path, showHidden, false, callback)
            else -> {
                val lastModifieds = activity.getFolderLastModifieds(path)
                getRegularItems(path, lastModifieds, callback)
            }
        }
    }

    private fun getRegularItems(path: String, lastModifieds: HashMap<String, Long>, callback: (List<FileDirItem>) -> Unit) {
        val items = ArrayList<FileDirItem>()
        val files = File(path).listFiles()?.filterNotNull()
        if (files == null) {
            callback(items)
            return
        }

        for (file in files) {
            if (!showHidden && file.name.startsWith('.')) {
                continue
            }

            val curPath = file.absolutePath
            val curName = curPath.getFilenameFromPath()
            val size = file.length()
            var lastModified = lastModifieds.remove(curPath)
            val isDirectory = file.isDirectory
            if (lastModified == null) {
                lastModified = 0    // we don't actually need the real lastModified that badly, do not check file.lastModified()
            }

            val children = if (isDirectory) file.getDirectChildrenCount(activity, showHidden) else 0
            items.add(FileDirItem(curPath, curName, isDirectory, children, size, lastModified))
        }
        callback(items)
    }

    private fun containsDirectory(items: List<FileDirItem>) = items.any { it.isDirectory }

    private fun setupFavorites() {
        FilepickerFavoritesAdapter(activity, activity.baseConfig.favorites.toMutableList(), mDialogView.filepickerFavoritesList) {
            currPath = it as String
            showWorkspaceRoot = false
            tryUpdateItems()
        }.apply {
            mDialogView.filepickerFavoritesList.adapter = this
        }
    }

    private fun showFavorites() {
        mDialogView.apply {
            filepickerFavoritesHolder.beVisible()
            filepickerFilesHolder.beGone()
            val drawable = activity.resources.getColoredDrawableWithColor(R.drawable.ic_folder_vector, activity.getProperPrimaryColor().getContrastColor())
            filepickerFabShowFavorites.setImageDrawable(drawable)
        }
    }

    private fun hideFavorites() {
        mDialogView.apply {
            filepickerFavoritesHolder.beGone()
            filepickerFilesHolder.beVisible()
            val drawable = activity.resources.getColoredDrawableWithColor(R.drawable.ic_star_vector, activity.getProperPrimaryColor().getContrastColor())
            filepickerFabShowFavorites.setImageDrawable(drawable)
        }
    }

    override fun breadcrumbClicked(id: Int) {
        if (id == 0) {
            if (!showWorkspaceRoot) {
                showWorkspaceRoot = true
                tryUpdateItems()
            }
        } else {
            val item = mDialogView.filepickerBreadcrumbs.getItem(id)
            if (!showWorkspaceRoot && currPath != item.path.trimEnd('/')) {
                currPath = item.path
                tryUpdateItems()
            }
        }
    }

    private fun normalizeInitialPath(rawPath: String): String {
        var path = rawPath.trimEnd('/')
        if (path.isEmpty()) {
            return termuxRootPath
        }

        if (path.startsWith(activity.recycleBinPath)) {
            return termuxRootPath
        }

        if (sessionFileCoordinator.isVirtualPath(activity, path)) {
            return path
        }

        if (!activity.getDoesFilePathExist(path)) {
            return termuxRootPath
        }

        if (!activity.getIsPathDirectory(path)) {
            path = path.getParentPath().trimEnd('/')
        }

        if (path.isEmpty()) {
            return termuxRootPath
        }

        return if (activity.getDoesFilePathExist(path)) path else termuxRootPath
    }

    private fun buildWorkspaceItems(): ArrayList<FileDirItem> {
        val items = ArrayList<FileDirItem>()
        val now = System.currentTimeMillis()
        val usedPaths = LinkedHashSet<String>()

        fun addWorkspace(name: String, path: String) {
            val normalized = path.trimEnd('/')
            if (normalized.isEmpty()) return
            if (!usedPaths.add(normalized)) return
            items.add(
                FileDirItem(
                    normalized,
                    name,
                    true,
                    0,
                    0L,
                    now
                )
            )
        }

        addWorkspace("Termux", termuxRootPath)

        val internal = activity.internalStoragePath.trimEnd('/')
        if (internal.isNotEmpty() && activity.getDoesFilePathExist(internal)) {
            addWorkspace("手机存储", internal)
        }

        activity.getStorageDirectories().forEach { root ->
            val normalized = root.trimEnd('/')
            if (normalized.isNotEmpty() && activity.getDoesFilePathExist(normalized)) {
                addWorkspace("存储 / $normalized", normalized)
            }
        }

        sessionFileCoordinator.listTargets(activity).forEach { target ->
            if (target.entry.transport == SessionTransport.LOCAL) return@forEach
            val root = FileRootResolver.resolveVirtualRoot(activity, target.entry)
            addWorkspace("服务器 / ${target.entry.displayName}", root)
        }

        return items
    }

    companion object {
        private const val WORKSPACE_ROOT_TITLE = "工作区"
    }
}
