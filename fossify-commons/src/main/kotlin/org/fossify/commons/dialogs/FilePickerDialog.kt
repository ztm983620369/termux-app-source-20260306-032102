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
import org.fossify.commons.extensions.humanizePath
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
 * @param startAtCurrentPath opens the passed currPath directly instead of the workspace root
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
    private val targetScope: TargetScope = TargetScope.ANY,
    private val startAtCurrentPath: Boolean = false,
    private val callback: (pickedPath: String) -> Unit
) : Breadcrumbs.BreadcrumbsListener {

    enum class TargetScope {
        ANY,
        LOCAL_ONLY,
        REMOTE_ONLY
    }

    private val sessionFileCoordinator = SessionFileCoordinator.getInstance()
    private val termuxRootPath = normalizePath(activity.filesDir.absolutePath)
    private val termuxHomePath = normalizePath(File(termuxRootPath, TERMUX_HOME_RELATIVE_PATH).absolutePath)
    private val workspaceRootPath = normalizePath("$termuxRootPath/$WORKSPACE_ROOT_RELATIVE_PATH")
    private var workspacePath = workspaceRootPath
    private var showWorkspaceRoot = !startAtCurrentPath
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
            if (!showWorkspaceRoot || workspacePath != workspaceRootPath) {
                workspacePath = workspaceRootPath
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
            beVisibleIf(showFavoritesButton && scopedFavoritePaths().isNotEmpty())
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
                    if (showWorkspaceRoot) {
                        if (workspacePath != workspaceRootPath) {
                            workspacePath = workspaceRootPath
                            tryUpdateItems()
                        } else {
                            isEnabled = false
                            alertDialog.onBackPressedDispatcher.onBackPressed()
                        }
                        return@addCallback
                    }

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
                val workspaceItems = buildWorkspaceItems(workspacePath)
                activity.runOnUiThread {
                    mDialogView.filepickerPlaceholder.beGone()
                    updateItems(workspaceItems)
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
        if (!showWorkspaceRoot && !containsDirectory(items) && !mFirstUpdate && !pickFile && !showFAB) {
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
                val selectedPath = (it as FileDirItem).path
                if (isWorkspaceGroupPath(selectedPath)) {
                    workspacePath = selectedPath
                } else {
                    currPath = selectedPath
                    showWorkspaceRoot = false
                }
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
            filepickerBreadcrumbs.setBreadcrumb(if (showWorkspaceRoot) workspaceBreadcrumbPath() else currPath)
            filepickerFab.beVisibleIf(showFAB && !showWorkspaceRoot)

            if (root.context.areSystemAnimationsEnabled) {
                filepickerList.scheduleLayoutAnimation()
            }

            layoutManager.onRestoreInstanceState(mScrollStates[currentNavigationPath()])
        }

        mFirstUpdate = false
        mPrevPath = currentNavigationPath()
    }

    private fun verifyPath() {
        if (showWorkspaceRoot) {
            return
        }

        if (targetScope == TargetScope.LOCAL_ONLY && sessionFileCoordinator.isVirtualPath(activity, currPath)) {
            activity.toast("请选择本地目录作为目标。", Toast.LENGTH_LONG)
            return
        }

        if (targetScope == TargetScope.REMOTE_ONLY && !sessionFileCoordinator.isVirtualPath(activity, currPath)) {
            activity.toast("请选择服务器目录作为目标。", Toast.LENGTH_LONG)
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
        FilepickerFavoritesAdapter(activity, scopedFavoritePaths().toMutableList(), mDialogView.filepickerFavoritesList) {
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
        if (showWorkspaceRoot) {
            if (workspacePath != workspaceRootPath) {
                workspacePath = workspaceRootPath
                tryUpdateItems()
            }
            return
        }

        if (id == 0) {
            if (!showWorkspaceRoot) {
                workspacePath = workspaceRootPath
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
        val firstRemoteRoot = firstRemoteRoot()

        if (targetScope == TargetScope.LOCAL_ONLY && sessionFileCoordinator.isVirtualPath(activity, path)) {
            path = preferredLocalRoot()
        }

        if (targetScope == TargetScope.REMOTE_ONLY && !sessionFileCoordinator.isVirtualPath(activity, path)) {
            return firstRemoteRoot ?: termuxRootPath
        }

        if (path.isEmpty()) {
            return when (targetScope) {
                TargetScope.LOCAL_ONLY, TargetScope.ANY -> preferredLocalRoot()
                TargetScope.REMOTE_ONLY -> firstRemoteRoot ?: termuxRootPath
            }
        }

        if (path.startsWith(activity.recycleBinPath)) {
            return preferredLocalRoot()
        }

        if (sessionFileCoordinator.isVirtualPath(activity, path)) {
            return path
        }

        if (!activity.getDoesFilePathExist(path)) {
            return when (targetScope) {
                TargetScope.LOCAL_ONLY, TargetScope.ANY -> preferredLocalRoot()
                TargetScope.REMOTE_ONLY -> firstRemoteRoot ?: termuxRootPath
            }
        }

        if (!activity.getIsPathDirectory(path)) {
            path = path.getParentPath().trimEnd('/')
        }

        if (path.isEmpty()) {
            return preferredLocalRoot()
        }

        return if (activity.getDoesFilePathExist(path)) path else preferredLocalRoot()
    }

    private fun buildWorkspaceItems(path: String = workspaceRootPath): ArrayList<FileDirItem> {
        val normalized = normalizePath(path)
        return when {
            isTermuxGroupPath(normalized) -> buildTermuxEnvironmentItems()
            isPhoneStorageGroupPath(normalized) -> buildPhoneStorageItems()
            else -> buildRootWorkspaceItems()
        }
    }

    private fun buildRootWorkspaceItems(): ArrayList<FileDirItem> {
        val items = ArrayList<FileDirItem>()
        val now = System.currentTimeMillis()
        val usedPaths = LinkedHashSet<String>()

        fun addWorkspace(name: String, path: String, requireExisting: Boolean = true) {
            val normalized = normalizePath(path)
            if (normalized.isEmpty()) return
            if (!usedPaths.add(normalized)) return
            if (requireExisting && !sessionFileCoordinator.isVirtualPath(activity, normalized) && !File(normalized).isDirectory) return
            items.add(
                FileDirItem(
                    normalized,
                    name,
                    true,
                    NAVIGATION_ITEM_CHILDREN,
                    0L,
                    now
                )
            )
        }

        if (targetScope != TargetScope.REMOTE_ONLY) {
            addWorkspace("本地工作目录", preferredLocalRoot(), requireExisting = false)
            addWorkspace("Termux 环境", workspaceGroupPath(TERMUX_GROUP), requireExisting = false)

            if (phoneStorageRoots().isNotEmpty()) {
                addWorkspace("手机存储", workspaceGroupPath(PHONE_STORAGE_GROUP), requireExisting = false)
            }

            scopedFavoritePaths().forEach { path ->
                addWorkspace("收藏 / ${workspaceFavoriteLabel(path)}", path)
            }
        }

        if (targetScope != TargetScope.LOCAL_ONLY) {
            sessionFileCoordinator.listTargets(activity).forEach { target ->
                if (target.entry.transport == SessionTransport.LOCAL) return@forEach
                val root = FileRootResolver.resolveVirtualRoot(activity, target.entry)
                addWorkspace("服务器 / ${target.entry.displayName}", root)
            }
        }

        return items
    }

    private fun buildTermuxEnvironmentItems(): ArrayList<FileDirItem> {
        val items = ArrayList<FileDirItem>()
        val now = System.currentTimeMillis()
        val usedPaths = LinkedHashSet<String>()
        val prefixPath = normalizePath(File(termuxRootPath, "usr").absolutePath)

        fun addFolder(name: String, path: String, requireExisting: Boolean = true) {
            val normalized = normalizePath(path)
            if (!usedPaths.add(normalized)) return
            if (requireExisting && !File(normalized).isDirectory) return
            items.add(
                FileDirItem(
                    normalized,
                    name,
                    true,
                    NAVIGATION_ITEM_CHILDREN,
                    0L,
                    now
                )
            )
        }

        addFolder("Termux / HOME", termuxHomePath, requireExisting = false)
        addFolder("Termux / projects", File(termuxHomePath, "projects").absolutePath)
        addFolder("Termux / .termux", File(termuxHomePath, ".termux").absolutePath)
        addFolder("Termux / files", termuxRootPath)
        addFolder("Termux / PREFIX", prefixPath)
        addFolder("Termux / tmp", File(prefixPath, "tmp").absolutePath)

        return items
    }

    private fun buildPhoneStorageItems(): ArrayList<FileDirItem> {
        val items = ArrayList<FileDirItem>()
        val now = System.currentTimeMillis()
        val usedPaths = LinkedHashSet<String>()
        val internalRoot = normalizePath(activity.internalStoragePath)

        fun addFolder(name: String, path: String) {
            val normalized = normalizePath(path)
            if (!usedPaths.add(normalized)) return
            if (!File(normalized).isDirectory) return
            items.add(
                FileDirItem(
                    normalized,
                    name,
                    true,
                    NAVIGATION_ITEM_CHILDREN,
                    0L,
                    now
                )
            )
        }

        phoneStorageRoots().forEach { root ->
            val prefix = if (root == internalRoot) "手机存储" else "外部存储"
            addFolder("$prefix / 根目录", root)
            buildPhoneDirectoryItems(root).forEach { (name, path) ->
                addFolder("$prefix / $name", path)
            }
        }

        return items
    }

    private fun buildPhoneDirectoryItems(rootPath: String): List<Pair<String, String>> {
        val root = normalizePath(rootPath)

        val candidates = linkedMapOf(
            "下载" to File(root, Environment.DIRECTORY_DOWNLOADS).absolutePath,
            "文档" to File(root, Environment.DIRECTORY_DOCUMENTS).absolutePath,
            "DCIM" to File(root, Environment.DIRECTORY_DCIM).absolutePath,
            "图片" to File(root, Environment.DIRECTORY_PICTURES).absolutePath,
            "视频" to File(root, Environment.DIRECTORY_MOVIES).absolutePath,
            "音乐" to File(root, Environment.DIRECTORY_MUSIC).absolutePath,
            "播客" to File(root, Environment.DIRECTORY_PODCASTS).absolutePath,
            "有声书" to File(root, Environment.DIRECTORY_AUDIOBOOKS).absolutePath,
            "Android/data" to File(root, "Android/data").absolutePath,
            "Android/obb" to File(root, "Android/obb").absolutePath
        )

        return candidates.mapNotNull { (label, path) ->
            if (activity.getDoesFilePathExist(path) && activity.getIsPathDirectory(path)) {
                label to path
            } else {
                null
            }
        }
    }

    private fun preferredLocalRoot(): String {
        if (File(termuxHomePath).isDirectory) {
            return termuxHomePath
        }
        return termuxRootPath
    }

    private fun firstRemoteRoot(): String? {
        return sessionFileCoordinator.listTargets(activity)
            .firstOrNull { it.entry.transport != SessionTransport.LOCAL }
            ?.let { FileRootResolver.resolveVirtualRoot(activity, it.entry) }
    }

    private fun phoneStorageRoots(): List<String> {
        val roots = LinkedHashSet<String>()
        val internal = normalizePath(activity.internalStoragePath)
        if (internal != "/" && File(internal).isDirectory) {
            roots.add(internal)
        }

        activity.getStorageDirectories().forEach { raw ->
            val normalized = normalizePath(raw)
            if (normalized != "/" && File(normalized).isDirectory) {
                roots.add(normalized)
            }
        }

        return roots.toList()
    }

    private fun scopedFavoritePaths(): List<String> {
        return activity.baseConfig.favorites
            .map { normalizePath(it) }
            .filter { isAllowedForTargetScope(it) }
            .filter { path ->
                sessionFileCoordinator.isVirtualPath(activity, path) ||
                    (activity.getDoesFilePathExist(path) && activity.getIsPathDirectory(path))
            }
    }

    private fun isAllowedForTargetScope(path: String): Boolean {
        val isRemote = sessionFileCoordinator.isVirtualPath(activity, normalizePath(path))
        return when (targetScope) {
            TargetScope.ANY -> true
            TargetScope.LOCAL_ONLY -> !isRemote
            TargetScope.REMOTE_ONLY -> isRemote
        }
    }

    private fun workspaceFavoriteLabel(path: String): String {
        return if (sessionFileCoordinator.isVirtualPath(activity, path)) {
            sessionFileCoordinator.getDisplayPath(activity, path)
        } else {
            activity.humanizePath(path).substringAfterLast('/').ifBlank { path.getFilenameFromPath() }
        }
    }

    private fun currentNavigationPath(): String {
        return if (showWorkspaceRoot) workspacePath else currPath.trimEnd('/')
    }

    private fun workspaceBreadcrumbPath(): String {
        return when {
            isTermuxGroupPath(workspacePath) -> "$WORKSPACE_ROOT_TITLE/Termux 环境"
            isPhoneStorageGroupPath(workspacePath) -> "$WORKSPACE_ROOT_TITLE/手机存储"
            else -> WORKSPACE_ROOT_TITLE
        }
    }

    private fun workspaceGroupPath(group: String): String {
        return normalizePath("$workspaceRootPath/$group")
    }

    private fun isWorkspaceGroupPath(path: String): Boolean {
        val normalized = normalizePath(path)
        return isTermuxGroupPath(normalized) || isPhoneStorageGroupPath(normalized)
    }

    private fun isTermuxGroupPath(path: String): Boolean {
        return normalizePath(path) == workspaceGroupPath(TERMUX_GROUP)
    }

    private fun isPhoneStorageGroupPath(path: String): Boolean {
        return normalizePath(path) == workspaceGroupPath(PHONE_STORAGE_GROUP)
    }

    private fun normalizePath(rawPath: String?): String {
        var path = rawPath.orEmpty().trim().replace('\\', '/')
        while (path.contains("//")) {
            path = path.replace("//", "/")
        }

        if (path.endsWith("/") && path.length > 1) {
            path = path.substring(0, path.length - 1)
        }

        return if (path.isEmpty()) "/" else path
    }

    companion object {
        private const val WORKSPACE_ROOT_TITLE = "核心导航器"
        private const val WORKSPACE_ROOT_RELATIVE_PATH = ".termux/.file-picker"
        private const val TERMUX_HOME_RELATIVE_PATH = "home"
        private const val TERMUX_GROUP = "termux"
        private const val PHONE_STORAGE_GROUP = "phone-storage"
        private const val NAVIGATION_ITEM_CHILDREN = -1
    }
}
