package org.fossify.filemanager.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Parcelable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import com.termux.bridge.RecentFileEntry
import com.termux.bridge.RecentFileHistory
import com.termux.sessionsync.SftpProtocolManager
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.views.MyGridLayoutManager
import org.fossify.commons.views.MyRecyclerView
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.SimpleActivity
import org.fossify.filemanager.adapters.ItemsAdapter
import org.fossify.filemanager.databinding.ItemsFragmentBinding
import org.fossify.filemanager.dialogs.CreateNewItemDialog
import org.fossify.filemanager.extensions.config
import org.fossify.filemanager.extensions.isPathOnRoot
import org.fossify.filemanager.helpers.MAX_COLUMN_COUNT
import org.fossify.filemanager.helpers.NavigatorFolderHelper
import org.fossify.filemanager.helpers.RootHelpers
import org.fossify.filemanager.helpers.TermuxPathScope
import org.fossify.filemanager.helpers.DownloadedApkArchiveInfo
import org.fossify.filemanager.helpers.DownloadedApkInstallerSupport
import org.fossify.filemanager.interfaces.ItemOperationsListener
import org.fossify.filemanager.models.ListItem
import com.termux.bridge.FileOpenRequest
import com.termux.sessionsync.SessionFileCoordinator
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ItemsFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.ItemsInnerBinding>(context, attributeSet),
    ItemOperationsListener {
    companion object {
        private const val RECENTS_LIMIT = 200
    }

    interface DirectoryNavigationHandler {
        fun onDirectoryNavigationRequested(
            fragment: ItemsFragment,
            currentPath: String,
            item: FileDirItem,
            fromNavigatorRoot: Boolean
        ): Boolean
    }

    private enum class ContentMode {
        DIRECTORY,
        RECENTS
    }

    private val sessionFileCoordinator = SessionFileCoordinator.getInstance()
    private var showHidden = false
    private var lastSearchedText = ""
    private var scrollStates = HashMap<String, Parcelable>()
    private var zoomListener: MyRecyclerView.MyZoomListener? = null
    private var pendingRevealRequest: RevealRequest? = null
    private var contentMode = ContentMode.DIRECTORY

    private var storedItems = ArrayList<ListItem>()
    private var itemsIgnoringSearch = ArrayList<ListItem>()
    private var recentEntriesByPath = linkedMapOf<String, RecentFileEntry>()
    private lateinit var binding: ItemsFragmentBinding
    var directoryNavigationHandler: DirectoryNavigationHandler? = null
    var pathChangedListener: ((String) -> Unit)? = null

    private data class RevealRequest(
        val targetPath: String,
        val highlightPaths: Set<String>,
        val createdAtMs: Long = SystemClock.elapsedRealtime()
    )

    private data class RecentListResult(
        val items: ArrayList<ListItem>,
        val entriesByPath: LinkedHashMap<String, RecentFileEntry>
    )

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = ItemsFragmentBinding.bind(this)
        innerBinding = ItemsInnerBinding(binding)
    }

    override fun setupFragment(activity: SimpleActivity) {
        if (this.activity == null) {
            this.activity = activity
            binding.apply {
                pathBarHolder.setOnClickListener {
                    if (contentMode == ContentMode.RECENTS) {
                        showDirectoryContents(forceRefresh = true)
                        return@setOnClickListener
                    }
                    if (NavigatorFolderHelper.isNavigatorPath(activity, currentPath)) {
                        return@setOnClickListener
                    }
                    val parent = resolveParentPathForNavigation(currentPath)
                    if (parent == currentPath.trimEnd('/')) {
                        fileManagerControllerCommands.closeActiveWorkspaceTabIfPossible()
                        return@setOnClickListener
                    }
                    openPath(parent, forceRefresh = true)
                }
                pathBarHolder.setOnLongClickListener {
                    fileManagerControllerCommands.showPathBarActions(it, currentPath)
                    true
                }
                itemsSwipeRefresh.setOnRefreshListener { refreshFragment() }
                itemsFab.setOnClickListener {
                    if (isCreateDocumentIntent) {
                        fileManagerControllerCommands.createDocumentConfirmed(currentPath)
                    } else {
                        fileManagerControllerCommands.toggleMainFabMenu()
                    }
                }
                itemsFab.beGone()
            }
        }
    }

    override fun onResume(textColor: Int) {
        context!!.updateTextColors(this)
        getRecyclerAdapter()?.apply {
            updatePrimaryColor()
            updateTextColor(textColor)
            initDrawables()
        }

        binding.apply {
            val properPrimaryColor = context!!.getProperPrimaryColor()
            itemsFastscroller.updateColors(properPrimaryColor)
            progressBar.setIndicatorColor(properPrimaryColor)
            progressBar.trackColor = properPrimaryColor.adjustAlpha(LOWER_ALPHA)

            val folderDrawable = resources.getColoredDrawableWithColor(R.drawable.ic_folder_vector, properPrimaryColor)
            folderDrawable.alpha = 180
            pathBarUp.setImageDrawable(folderDrawable)
            pathBarText.setTextColor(textColor)

            itemsSwipeRefresh.isEnabled = lastSearchedText.isEmpty() && activity?.config?.enablePullToRefresh != false
        }
    }

    override fun setupFontSize() {
        getRecyclerAdapter()?.updateFontSizes()
    }

    override fun setupDateTimeFormat() {
        getRecyclerAdapter()?.updateDateTimeFormat()
    }

    override fun finishActMode() {
        getRecyclerAdapter()?.finishActMode()
    }

    fun isShowingFolderRecents(): Boolean = contentMode == ContentMode.RECENTS

    fun toggleFolderRecentsMode(): Boolean {
        return if (isShowingFolderRecents()) {
            showDirectoryContents(forceRefresh = true)
        } else {
            showFolderRecents(forceRefresh = true)
        }
    }

    fun showFolderRecents(forceRefresh: Boolean = false): Boolean {
        val changed = contentMode != ContentMode.RECENTS
        contentMode = ContentMode.RECENTS
        if (changed || forceRefresh) {
            loadCurrentFolderRecents(forceRefresh = true)
        }
        return true
    }

    fun showDirectoryContents(forceRefresh: Boolean = false): Boolean {
        val changed = contentMode != ContentMode.DIRECTORY
        contentMode = ContentMode.DIRECTORY
        recentEntriesByPath.clear()
        if (changed || forceRefresh) {
            openPath(currentPath, forceRefresh = true)
        }
        return changed || forceRefresh
    }

    fun openPath(path: String, forceRefresh: Boolean = false) {
        if ((activity as? BaseSimpleActivity)?.isAskingPermissions == true) {
            return
        }

        contentMode = ContentMode.DIRECTORY
        recentEntriesByPath.clear()
        var realPath = path.trimEnd('/')
        if (realPath.isEmpty()) {
            realPath = "/"
        }
        realPath = clampToVisiblePath(realPath)

        scrollStates[currentPath] = getScrollState()!!
        currentPath = realPath
        pathChangedListener?.invoke(currentPath)
        showHidden = context!!.config.shouldShowHidden()
        showProgressBar()
        getItems(currentPath) { originalPath, listItems ->
            if (currentPath != originalPath) {
                return@getItems
            }

            FileDirItem.sorting = context!!.config.getFolderSorting(currentPath)
            if (!NavigatorFolderHelper.isNavigatorPath(context!!, currentPath)) {
                listItems.sort()
            }

            if (context!!.config.getFolderViewType(currentPath) == VIEW_TYPE_GRID && listItems.none { it.isSectionTitle }) {
                if (listItems.any { it.mIsDirectory } && listItems.any { !it.mIsDirectory }) {
                    val firstFileIndex = listItems.indexOfFirst { !it.mIsDirectory }
                    if (firstFileIndex != -1) {
                        val sectionTitle = ListItem("", "", false, 0, 0, 0, false, true)
                        listItems.add(firstFileIndex, sectionTitle)
                    }
                }
            }

            itemsIgnoringSearch = listItems
            activity?.runOnUiThread {
                fileManagerControllerCommands.refreshMenuItems()
                addItems(listItems, forceRefresh)
                if (context != null && currentViewType != context!!.config.getFolderViewType(currentPath)) {
                    setupLayoutManager()
                }
                hideProgressBar()
            }
        }
    }

    private fun addItems(items: ArrayList<ListItem>, forceRefresh: Boolean = false) {
        activity?.runOnUiThread {
            binding.itemsSwipeRefresh.isRefreshing = false
            binding.pathBarText.text = buildPathBarLabel()
            if (!forceRefresh && items.hashCode() == storedItems.hashCode()) {
                return@runOnUiThread
            }

            storedItems = items
            if (binding.itemsList.adapter == null) {
                ItemsAdapter(
                    activity = activity as SimpleActivity,
                    listItems = storedItems,
                    listener = this,
                    recyclerView = binding.itemsList,
                    isPickMultipleIntent = isPickMultipleIntent,
                    swipeRefreshLayout = binding.itemsSwipeRefresh,
                    showRecentPathMetadata = contentMode == ContentMode.RECENTS
                ) {
                    if ((it as? ListItem)?.isSectionTitle == true) {
                        openDirectory(it.mPath)
                        searchClosed()
                    } else {
                        itemClicked(it as FileDirItem)
                    }
                }.apply {
                    setupZoomListener(zoomListener)
                    binding.itemsList.adapter = this
                }
            } else {
                (binding.itemsList.adapter as? ItemsAdapter)?.apply {
                    setRecentPathMetadataEnabled(contentMode == ContentMode.RECENTS)
                    updateItems(storedItems, "")
                    setupZoomListener(zoomListener)
                }
            }

            if (context.areSystemAnimationsEnabled) {
                binding.itemsList.scheduleLayoutAnimation()
            }

            getRecyclerLayoutManager().onRestoreInstanceState(scrollStates[currentPath])
            applyPendingRevealIfNeeded(items)
        }
    }

    private fun getScrollState() = getRecyclerLayoutManager().onSaveInstanceState()

    private fun getRecyclerLayoutManager() = (binding.itemsList.layoutManager as MyGridLayoutManager)
    @SuppressLint("NewApi")
    private fun getItems(path: String, callback: (originalPath: String, items: ArrayList<ListItem>) -> Unit) {
        ensureBackgroundThread {
            if (activity?.isDestroyed == false && activity?.isFinishing == false) {
                val ctx = context!!
                val config = ctx.config
                if (NavigatorFolderHelper.isNavigatorPath(ctx, path)) {
                    callback(path, NavigatorFolderHelper.buildNavigatorItems(ctx))
                } else if (sessionFileCoordinator.isVirtualPath(ctx, path)) {
                    val result = sessionFileCoordinator.listVirtualPath(ctx, path)
                    if (!result.success) {
                        activity?.runOnUiThread {
                            hideProgressBar()
                            activity?.toast(result.messageCn)
                        }
                        callback(path, ArrayList())
                    } else {
                        callback(path, getListItemsFromRemoteEntries(result.entries))
                    }
                } else if (sessionFileCoordinator.isStaleVirtualPath(ctx, path)) {
                    activity?.runOnUiThread {
                        hideProgressBar()
                        activity?.toast("SFTP \u4f1a\u8bdd\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u65b0\u9009\u62e9\u4f1a\u8bdd\u3002")
                    }
                    callback(path, ArrayList())
                } else if (context.isRestrictedSAFOnlyRoot(path)) {
                    activity?.runOnUiThread { hideProgressBar() }
                    activity?.handleAndroidSAFDialog(path, openInSystemAppAllowed = true) {
                        if (!it) {
                            activity?.toast(R.string.no_storage_permissions)
                            return@handleAndroidSAFDialog
                        }
                        val getProperChildCount = context!!.config.getFolderViewType(currentPath) == VIEW_TYPE_LIST
                        context.getAndroidSAFFileItems(path, context.config.shouldShowHidden(), getProperChildCount) { fileItems ->
                            callback(path, getListItemsFromFileDirItems(fileItems))
                        }
                    }
                } else if (context!!.isPathOnOTG(path) && config.OTGTreeUri.isNotEmpty()) {
                    val getProperFileSize = context!!.config.getFolderSorting(currentPath) and SORT_BY_SIZE != 0
                    context!!.getOTGItems(path, config.shouldShowHidden(), getProperFileSize) {
                        callback(path, getListItemsFromFileDirItems(it))
                    }
                } else if (!config.enableRootAccess || !context!!.isPathOnRoot(path)) {
                    getRegularItemsOf(path, callback)
                } else {
                    RootHelpers(activity!!).getFiles(path, callback)
                }
            }
        }
    }

    private fun getRegularItemsOf(path: String, callback: (originalPath: String, items: ArrayList<ListItem>) -> Unit) {
        val items = ArrayList<ListItem>()
        val files = File(path).listFiles()?.filterNotNull()
        if (context == null || files == null) {
            callback(path, items)
            return
        }

        val isSortingBySize = context!!.config.getFolderSorting(currentPath) and SORT_BY_SIZE != 0
        val getProperChildCount = context!!.config.getFolderViewType(currentPath) == VIEW_TYPE_LIST
        val lastModifieds = context!!.getFolderLastModifieds(path)

        for (file in files) {
            val listItem = getListItemFromFile(file, isSortingBySize, lastModifieds, false)
            if (listItem != null) {
                if (wantedMimeTypes.any { isProperMimeType(it, file.absolutePath, file.isDirectory) }) {
                    items.add(listItem)
                }
            }
        }

        // send out the initial item list asap, get proper child count asynchronously as it can be slow
        callback(path, items)

        if (getProperChildCount) {
            items.filter { it.mIsDirectory }.forEach {
                if (context != null) {
                    val childrenCount = it.getDirectChildrenCount(activity as BaseSimpleActivity, showHidden)
                    if (childrenCount != 0) {
                        activity?.runOnUiThread {
                            getRecyclerAdapter()?.updateChildCount(it.mPath, childrenCount)
                        }
                    }
                }
            }
        }
    }

    private fun getListItemFromFile(file: File, isSortingBySize: Boolean, lastModifieds: HashMap<String, Long>, getProperChildCount: Boolean): ListItem? {
        val curPath = file.absolutePath
        val curName = file.name
        if (!showHidden && curName.startsWith(".")) {
            return null
        }

        var lastModified = lastModifieds.remove(curPath)
        val isDirectory = file.isDirectory
        val children = if (isDirectory && getProperChildCount) file.getDirectChildrenCount(context, showHidden) else 0
        val size = if (isDirectory) {
            if (isSortingBySize) {
                file.getProperSize(showHidden)
            } else {
                0L
            }
        } else {
            file.length()
        }

        if (lastModified == null) {
            lastModified = file.lastModified()
        }

        return ListItem(curPath, curName, isDirectory, children, size, lastModified, false, false)
    }

    private fun getListItemsFromFileDirItems(fileDirItems: ArrayList<FileDirItem>): ArrayList<ListItem> {
        val listItems = ArrayList<ListItem>()
        fileDirItems.forEach {
            val listItem = ListItem(it.path, it.name, it.isDirectory, it.children, it.size, it.modified, false, false)
            if (wantedMimeTypes.any { mimeType -> isProperMimeType(mimeType, it.path, it.isDirectory) }) {
                listItems.add(listItem)
            }
        }
        return listItems
    }

    private fun getListItemsFromRemoteEntries(remoteEntries: ArrayList<SftpProtocolManager.RemoteEntry>): ArrayList<ListItem> {
        val listItems = ArrayList<ListItem>()
        remoteEntries.forEach {
            val listItem = ListItem(it.localPath, it.name, it.directory, 0, it.size, it.modifiedMs, false, false)
            if (wantedMimeTypes.any { mimeType -> isProperMimeType(mimeType, it.localPath, it.directory) }) {
                listItems.add(listItem)
            }
        }
        return listItems
    }

    private fun loadCurrentFolderRecents(forceRefresh: Boolean = false) {
        val ctx = context ?: return
        val folderPath = currentPath
        showProgressBar()
        ensureBackgroundThread {
            val result = getCurrentFolderRecents(folderPath)
            activity?.runOnUiThread {
                if (context == null || currentPath != folderPath || contentMode != ContentMode.RECENTS) {
                    return@runOnUiThread
                }

                recentEntriesByPath = result.entriesByPath
                itemsIgnoringSearch = result.items
                addItems(result.items, forceRefresh)
                if (context != null && currentViewType != context!!.config.getFolderViewType(currentPath)) {
                    setupLayoutManager()
                }
                hideProgressBar()
            }
        }
    }

    private fun getCurrentFolderRecents(folderPath: String): RecentListResult {
        val ctx = context ?: return RecentListResult(arrayListOf(), linkedMapOf())
        val isNavigatorScope = NavigatorFolderHelper.isNavigatorPath(ctx, folderPath)
        val normalizedFolderPath = TermuxPathScope.normalizePath(folderPath).trimEnd('/').ifEmpty { "/" }
        val listItems = arrayListOf<ListItem>()
        val recentEntries = LinkedHashMap<String, RecentFileEntry>()

        try {
            val isTermuxScoped = fileManagerEnvironment.isTermuxScopedFileManager()
            RecentFileHistory.getRecentFiles(ctx, RECENTS_LIMIT).forEach { entry ->
                val listPath = entry.listPath()
                if (!TermuxPathScope.isVisibleInFileManager(ctx, listPath, isTermuxScoped)) {
                    return@forEach
                }

                val normalizedListPath = TermuxPathScope.normalizePath(listPath).trimEnd('/').ifEmpty { "/" }
                if (!recentMatchesScope(normalizedFolderPath, normalizedListPath, isNavigatorScope)) {
                    return@forEach
                }

                val isRemote = entry.remoteOriginPath() != null
                val file = File(entry.path)
                if (!isRemote && (!file.exists() || !file.isFile)) {
                    RecentFileHistory.removePath(ctx, entry.path)
                    return@forEach
                }

                if (wantedMimeTypes.any { isProperMimeType(it, listPath, false) }) {
                    val name = entry.displayName.ifBlank { normalizedListPath.getFilenameFromPath() }
                    val size = entry.resolveRecentSize(file, isRemote)
                    val modified = entry.openedAtMs
                    listItems.add(ListItem(normalizedListPath, name, false, 0, size, modified, false, false))
                    recentEntries[normalizedListPath] = entry
                }
            }
        } catch (_: Exception) {
        }

        return RecentListResult(listItems, recentEntries)
    }

    private fun recentMatchesScope(scopePath: String, filePath: String, isNavigatorScope: Boolean): Boolean {
        if (isNavigatorScope) {
            return true
        }

        val normalizedScope = scopePath.trimEnd('/').ifEmpty { "/" }
        val normalizedFile = filePath.trimEnd('/').ifEmpty { "/" }
        if (normalizedFile == normalizedScope) {
            return false
        }

        return normalizedScope == "/" || normalizedFile.startsWith("$normalizedScope/")
    }

    private fun handleRecentClick(path: String) {
        val ctx = context ?: return
        val entry = recentEntriesByPath[path]
        if (entry == null) {
            clickedPath(path)
            return
        }

        val remoteOriginPath = entry.remoteOriginPath()
        if (remoteOriginPath == null) {
            clickedPath(path)
            return
        }

        showProgressBar()
        ensureBackgroundThread {
            val result = sessionFileCoordinator.materializeVirtualFile(ctx.applicationContext, remoteOriginPath)
            activity?.runOnUiThread {
                hideProgressBar()
                if (!result.success) {
                    activity?.toast(result.messageCn)
                    return@runOnUiThread
                }

                val localPath = result.localPath
                val displayName = entry.displayName.ifBlank { remoteOriginPath.getFilenameFromPath() }
                val extension = displayName.substringAfterLast('.', "").lowercase().ifBlank { null }
                val originDisplayPath = entry.originDisplayPath
                    ?.takeIf { it.isNotBlank() }
                    ?: sessionFileCoordinator.getDisplayPath(ctx, remoteOriginPath)
                clickedPath(
                    localPath,
                    FileOpenRequest(
                        path = localPath,
                        displayName = displayName,
                        readOnly = false,
                        extension = extension,
                        mimeType = localPath.getMimeType(),
                        originType = entry.originType,
                        originPath = remoteOriginPath,
                        originDisplayPath = originDisplayPath,
                        originModifiedMs = result.remoteModifiedMs.takeIf { it >= 0L },
                        originSize = result.remoteSize.takeIf { it >= 0L }
                    )
                )
            }
        }
    }

    private fun itemClicked(item: FileDirItem) {
        if (contentMode == ContentMode.RECENTS) {
            handleRecentClick(item.path)
            return
        }

        if (context != null && NavigatorFolderHelper.isNavigatorPath(context!!, currentPath)) {
            val ctx = context!!
            val selectedSessionKey = NavigatorFolderHelper.resolveSessionKeyForTargetPath(ctx, item.path)
            sessionFileCoordinator.setSelectedSessionKey(ctx, selectedSessionKey)
            if (directoryNavigationHandler?.onDirectoryNavigationRequested(this, currentPath, item, true) == true) {
                return
            }
            openDirectory(item.path)
            return
        }

        if (context != null && sessionFileCoordinator.isVirtualPath(context!!, item.path)) {
            if (item.isDirectory) {
                openDirectory(item.path)
            } else if (item.name.endsWith(".apk", true)) {
                downloadRemoteApkToDownloads(item)
            } else {
                showProgressBar()
                ensureBackgroundThread {
                    val result = sessionFileCoordinator.materializeVirtualFile(context!!, item.path)
                    activity?.runOnUiThread {
                        hideProgressBar()
                        if (result.success) {
                            val localPath = result.localPath
                            val extension = item.name.substringAfterLast('.', "").lowercase().ifBlank { null }
                                clickedPath(
                                    localPath,
                                    FileOpenRequest(
                                        path = localPath,
                                        displayName = item.name,
                                    readOnly = false,
                                    extension = extension,
                                        mimeType = localPath.getMimeType(),
                                        originType = FileOpenRequest.ORIGIN_SFTP_VIRTUAL,
                                        originPath = item.path,
                                        originDisplayPath = sessionFileCoordinator.getDisplayPath(context!!, item.path),
                                        originModifiedMs = result.remoteModifiedMs.takeIf { it >= 0L },
                                        originSize = result.remoteSize.takeIf { it >= 0L }
                                    )
                                )
                            } else {
                            activity?.toast(result.messageCn)
                        }
                    }
                }
            }
            return
        }

        if (item.isDirectory) {
            if (directoryNavigationHandler?.onDirectoryNavigationRequested(this, currentPath, item, false) == true) {
                return
            }
            openDirectory(item.path)
        } else {
            clickedPath(item.path)
        }
    }

    private fun downloadRemoteApkToDownloads(item: FileDirItem) {
        val hostActivity = activity ?: return
        val downloadsPath = DownloadedApkInstallerSupport.getSystemDownloadsPath()
        if (downloadsPath.isBlank()) {
            hostActivity.toast("无法确定系统 Download 目录。")
            return
        }

        hostActivity.handleAndroidSAFDialog(downloadsPath, openInSystemAppAllowed = true) { granted ->
            if (!granted) return@handleAndroidSAFDialog
            hostActivity.handleSAFDialog(downloadsPath) { safGranted ->
                if (!safGranted) return@handleSAFDialog
                startRemoteApkDownload(item, downloadsPath)
            }
        }
    }

    private fun startRemoteApkDownload(item: FileDirItem, downloadsPath: String) {
        val hostActivity = activity ?: return
        val progressDialog = hostActivity.getAlertDialogBuilder()
            .setTitle("下载 APK")
            .setMessage("正在准备下载...")
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .create()
        val cancelled = AtomicBoolean(false)
        progressDialog.setOnShowListener {
            progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                cancelled.set(true)
                it.isEnabled = false
            }
        }
        try {
            progressDialog.show()
        } catch (_: Exception) {
        }

        var lastUiUpdateAt = 0L
        var lastSpeedAt = 0L
        var lastSpeedBytes = 0L
        var speedBytesPerSecond = 0L

        ensureBackgroundThread {
            try {
                val result = sessionFileCoordinator.downloadVirtualPaths(
                    hostActivity,
                    listOf(item.path),
                    downloadsPath,
                    object : SftpProtocolManager.DownloadProgressListener {
                        override fun onProgress(progress: SftpProtocolManager.DownloadProgress) {
                            val now = SystemClock.elapsedRealtime()
                            if (lastSpeedAt == 0L) {
                                lastSpeedAt = now
                                lastSpeedBytes = progress.transferredBytes
                            } else {
                                val deltaMs = now - lastSpeedAt
                                if (deltaMs >= 260L || progress.transferredBytes < lastSpeedBytes) {
                                    val deltaBytes = progress.transferredBytes - lastSpeedBytes
                                    speedBytesPerSecond = if (deltaMs > 0L && deltaBytes > 0L) {
                                        deltaBytes * 1000L / deltaMs
                                    } else {
                                        0L
                                    }
                                    lastSpeedAt = now
                                    lastSpeedBytes = progress.transferredBytes
                                }
                            }

                            if (now - lastUiUpdateAt < 100L && progress.transferredBytes < progress.totalBytes) {
                                return
                            }
                            lastUiUpdateAt = now

                            val finishedCount = progress.completedFiles + progress.failedFiles
                            val percent = if (progress.totalBytes > 0L) {
                                ((progress.transferredBytes * 100L) / progress.totalBytes).coerceIn(0L, 100L)
                            } else {
                                0L
                            }
                            val sizeText = if (progress.totalBytes > 0L) {
                                "${progress.transferredBytes.formatSize()} / ${progress.totalBytes.formatSize()}"
                            } else {
                                "${progress.transferredBytes.formatSize()} / ?"
                            }
                            val speedText = if (speedBytesPerSecond > 0L) {
                                "${speedBytesPerSecond.formatSize()}/s"
                            } else {
                                "--"
                            }
                            val currentFile = progress.currentFile.ifEmpty { item.name.ifBlank { "apk" } }
                            val message = StringBuilder()
                                .append("当前：").append(currentFile).append('\n')
                                .append("进度：").append(finishedCount).append('/').append(progress.totalFiles)
                                .append(" (").append(percent).append("%)").append('\n')
                                .append("大小：").append(sizeText).append('\n')
                                .append("速度：").append(speedText)
                                .toString()

                            hostActivity.runOnUiThread {
                                if (!hostActivity.isDestroyed && !hostActivity.isFinishing && progressDialog.isShowing) {
                                    progressDialog.setMessage(message)
                                }
                            }
                        }
                    },
                    object : SftpProtocolManager.DownloadControl {
                        override fun isCancelled(): Boolean = cancelled.get()
                    }
                )

                hostActivity.runOnUiThread {
                    try {
                        if (progressDialog.isShowing) progressDialog.dismiss()
                    } catch (_: Exception) {
                    }

                    when {
                        result.success -> {
                            hostActivity.toast(
                                "APK 下载完成：${result.downloadedBytes.formatSize()}"
                            )
                        }

                        result.downloadedFiles > 0 -> {
                            hostActivity.toast(
                                "APK 已部分下载：${result.downloadedFiles}/${result.totalFiles}"
                            )
                        }

                        result.messageCn.contains("已取消") -> {
                            hostActivity.toast(result.messageCn)
                        }

                        else -> {
                            hostActivity.toast(result.messageCn.ifBlank { "APK 下载失败，请重试。" })
                        }
                    }

                    val downloadedPath = result.downloadedLocalPaths.firstOrNull()
                        ?.takeIf { it.isNotBlank() && File(it).exists() }
                    if (downloadedPath != null) {
                        hostActivity.rescanPath(downloadedPath)
                        showDownloadedApkDialog(item, downloadedPath)
                    }
                }
            } catch (t: Throwable) {
                hostActivity.runOnUiThread {
                    try {
                        if (progressDialog.isShowing) progressDialog.dismiss()
                    } catch (_: Exception) {
                    }
                    val msg = t.message?.trim().orEmpty()
                    hostActivity.toast(if (msg.isNotEmpty()) "APK 下载异常：$msg" else "APK 下载异常，请重试。")
                }
            }
        }
    }

    private fun showDownloadedApkDialog(item: FileDirItem, downloadedPath: String) {
        val hostActivity = activity ?: return
        val apkInfo = DownloadedApkInstallerSupport.readArchiveInfo(hostActivity, downloadedPath)
        val file = File(downloadedPath)
        val dialogView = hostActivity.layoutInflater.inflate(R.layout.dialog_downloaded_apk_card, null)
        bindDownloadedApkDialogView(
            dialogView = dialogView,
            item = item,
            downloadedPath = downloadedPath,
            apkInfo = apkInfo,
            fileSize = file.length()
        )

        val dialog = hostActivity.getAlertDialogBuilder()
            .setView(dialogView)
            .setPositiveButton("安装后删除") { _, _ ->
                fileManagerControllerCommands.installDownloadedApk(downloadedPath, true)
            }
            .setNegativeButton("保留") { _, _ ->
                hostActivity.toast("已保留到 Download 目录。")
            }
            .create()
        dialog.setOnShowListener {
            val accentColor = hostActivity.getProperPrimaryColor()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accentColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accentColor)
        }
        dialog.show()
    }

    private fun bindDownloadedApkDialogView(
        dialogView: android.view.View,
        item: FileDirItem,
        downloadedPath: String,
        apkInfo: DownloadedApkArchiveInfo?,
        fileSize: Long
    ) {
        val hostActivity = activity ?: return
        val backgroundColor = hostActivity.getProperBackgroundColor()
        val textColor = hostActivity.getProperTextColor()
        val primaryColor = hostActivity.getProperPrimaryColor()
        val secondaryTextColor = blendDialogColor(textColor, backgroundColor, 0.62f)
        val cardColor = blendDialogColor(primaryColor, backgroundColor, 0.12f)
        val borderColor = blendDialogColor(textColor, backgroundColor, 0.18f)
        val iconHolderColor = blendDialogColor(primaryColor, backgroundColor, 0.18f)

        dialogView.findViewById<android.view.View>(R.id.downloaded_apk_card).background =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = hostActivity.resources.displayMetrics.density * 22f
                setColor(cardColor)
                setStroke((hostActivity.resources.displayMetrics.density * 1.2f).toInt(), borderColor)
            }
        dialogView.findViewById<android.view.View>(R.id.downloaded_apk_icon_holder).background =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = hostActivity.resources.displayMetrics.density * 18f
                setColor(iconHolderColor)
            }
        dialogView.findViewById<TextView>(R.id.downloaded_apk_badge).background =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = hostActivity.resources.displayMetrics.density * 999f
                setColor(primaryColor)
            }
        dialogView.findViewById<android.view.View>(R.id.downloaded_apk_divider).setBackgroundColor(borderColor)

        val title = apkInfo?.applicationLabel?.ifBlank { item.name } ?: item.name
        val packageText = apkInfo?.packageName?.ifBlank { "未知" } ?: "未知"
        val versionText = buildString {
            val versionName = apkInfo?.versionName?.trim().orEmpty()
            if (versionName.isNotEmpty()) {
                append(versionName)
            } else {
                append("未知")
            }
            if (apkInfo != null && apkInfo.versionCode > 0L) {
                append(" (").append(apkInfo.versionCode).append(')')
            }
        }
        val sizeText = if (fileSize > 0L) fileSize.formatSize() else "未知"

        dialogView.findViewById<TextView>(R.id.downloaded_apk_title).apply {
            text = title
            setTextColor(textColor)
        }
        dialogView.findViewById<TextView>(R.id.downloaded_apk_filename).apply {
            text = item.name
            setTextColor(secondaryTextColor)
        }
        dialogView.findViewById<TextView>(R.id.downloaded_apk_badge).setTextColor(primaryColor.getContrastColor())
        dialogView.findViewById<TextView>(R.id.downloaded_apk_hint).setTextColor(secondaryTextColor)

        bindDownloadedApkInfoRow(dialogView, R.id.downloaded_apk_package_label, R.id.downloaded_apk_package_value, "包名", packageText, textColor, secondaryTextColor, true)
        bindDownloadedApkInfoRow(dialogView, R.id.downloaded_apk_version_label, R.id.downloaded_apk_version_value, "版本", versionText, textColor, secondaryTextColor, false)
        bindDownloadedApkInfoRow(dialogView, R.id.downloaded_apk_size_label, R.id.downloaded_apk_size_value, "大小", sizeText, textColor, secondaryTextColor, false)
        bindDownloadedApkInfoRow(dialogView, R.id.downloaded_apk_path_label, R.id.downloaded_apk_path_value, "位置", downloadedPath, textColor, secondaryTextColor, true)

        dialogView.findViewById<ImageView>(R.id.downloaded_apk_icon).setImageDrawable(
            DownloadedApkInstallerSupport.loadArchiveIcon(hostActivity, downloadedPath)
                ?: hostActivity.packageManager.defaultActivityIcon
        )
    }

    private fun bindDownloadedApkInfoRow(
        dialogView: android.view.View,
        labelId: Int,
        valueId: Int,
        label: String,
        value: String,
        textColor: Int,
        secondaryTextColor: Int,
        monospace: Boolean
    ) {
        dialogView.findViewById<TextView>(labelId).apply {
            text = label
            setTextColor(secondaryTextColor)
        }
        dialogView.findViewById<TextView>(valueId).apply {
            text = value
            setTextColor(textColor)
            typeface = if (monospace) Typeface.MONOSPACE else Typeface.DEFAULT
        }
    }

    private fun blendDialogColor(foreground: Int, background: Int, ratio: Float): Int {
        val clamped = ratio.coerceIn(0f, 1f)
        val inverse = 1f - clamped
        val a = (Color.alpha(foreground) * clamped + Color.alpha(background) * inverse).toInt()
        val r = (Color.red(foreground) * clamped + Color.red(background) * inverse).toInt()
        val g = (Color.green(foreground) * clamped + Color.green(background) * inverse).toInt()
        val b = (Color.blue(foreground) * clamped + Color.blue(background) * inverse).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun buildPathBarLabel(): String {
        val ctx = context ?: return currentPath
        val base = if (NavigatorFolderHelper.isNavigatorPath(ctx, currentPath)) {
            NavigatorFolderHelper.displayTitle()
        } else {
            sessionFileCoordinator.getDisplayPath(ctx, currentPath)
        }
        return if (contentMode == ContentMode.RECENTS && !NavigatorFolderHelper.isNavigatorPath(ctx, currentPath)) {
            "$base  ·  ${ctx.getString(R.string.recents)}"
        } else {
            base
        }
    }

    private fun RecentFileEntry.remoteOriginPath(): String? {
        val normalizedOriginPath = originPath?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return if (originType == FileOpenRequest.ORIGIN_SFTP_VIRTUAL) normalizedOriginPath else null
    }

    private fun RecentFileEntry.listPath(): String {
        return remoteOriginPath() ?: path
    }

    private fun RecentFileEntry.resolveRecentSize(file: File, isRemote: Boolean): Long {
        if (isRemote) {
            return sizeBytes ?: file.takeIf { it.exists() && it.isFile }?.length() ?: -1L
        }
        return when {
            file.exists() && file.isFile -> file.length()
            else -> -1L
        }
    }

    private fun openDirectory(path: String) {
        contentMode = ContentMode.DIRECTORY
        recentEntriesByPath.clear()
        fileManagerControllerCommands.openedDirectory()
        openPath(path)
    }

    override fun searchQueryChanged(text: String) {
        lastSearchedText = text
        if (context == null) {
            return
        }

        if (contentMode == ContentMode.RECENTS) {
            val normalizedText = text.normalizeString()
            val filtered = if (text.isEmpty()) {
                itemsIgnoringSearch
            } else {
                itemsIgnoringSearch.filter {
                    it.mName.normalizeString().contains(normalizedText, true) ||
                        it.mPath.normalizeString().contains(normalizedText, true)
                }.toMutableList() as ArrayList<ListItem>
            }

            binding.apply {
                itemsSwipeRefresh.isEnabled = text.isEmpty() && activity?.config?.enablePullToRefresh != false
                itemsFastscroller.beVisibleIf(filtered.isNotEmpty())
                itemsPlaceholder.beVisibleIf(filtered.isEmpty())
                itemsPlaceholder2.beGone()
                getRecyclerAdapter()?.updateItems(filtered, text)
                hideProgressBar()
            }
            return
        }

        binding.apply {
            itemsSwipeRefresh.isEnabled = text.isEmpty() && activity?.config?.enablePullToRefresh != false
            when {
                text.isEmpty() -> {
                    itemsFastscroller.beVisible()
                    getRecyclerAdapter()?.updateItems(itemsIgnoringSearch)
                    itemsPlaceholder.beGone()
                    itemsPlaceholder2.beGone()
                    hideProgressBar()
                }

                text.length == 1 -> {
                    itemsFastscroller.beGone()
                    itemsPlaceholder.beVisible()
                    itemsPlaceholder2.beVisible()
                    hideProgressBar()
                }

                else -> {
                    showProgressBar()
                    ensureBackgroundThread {
                        val files = searchFiles(text, currentPath)
                        files.sortBy { it.getParentPath() }

                        if (lastSearchedText != text) {
                            return@ensureBackgroundThread
                        }

                        val listItems = ArrayList<ListItem>()

                        var previousParent = ""
                        files.forEach {
                            val parent = it.mPath.getParentPath()
                            if (!it.isDirectory && parent != previousParent && context != null) {
                                val sectionTitle = ListItem(parent, context!!.humanizePath(parent), false, 0, 0, 0, true, false)
                                listItems.add(sectionTitle)
                                previousParent = parent
                            }

                            if (it.isDirectory) {
                                val sectionTitle = ListItem(it.path, context!!.humanizePath(it.path), true, 0, 0, 0, true, false)
                                listItems.add(sectionTitle)
                                previousParent = parent
                            }

                            if (!it.isDirectory) {
                                listItems.add(it)
                            }
                        }

                        activity?.runOnUiThread {
                            getRecyclerAdapter()?.updateItems(listItems, text)
                            itemsFastscroller.beVisibleIf(listItems.isNotEmpty())
                            itemsPlaceholder.beVisibleIf(listItems.isEmpty())
                            itemsPlaceholder2.beGone()
                            hideProgressBar()
                        }
                    }
                }
            }
        }
    }

    private fun searchFiles(text: String, path: String): ArrayList<ListItem> {
        val files = ArrayList<ListItem>()
        if (context == null) {
            return files
        }

        val normalizedText = text.normalizeString()
        val sorting = context!!.config.getFolderSorting(path)
        FileDirItem.sorting = context!!.config.getFolderSorting(currentPath)
        val isSortingBySize = sorting and SORT_BY_SIZE != 0
        File(path).listFiles()?.sortedBy { it.isDirectory }?.forEach {
            if (!showHidden && it.isHidden) {
                return@forEach
            }

            if (it.isDirectory) {
                if (it.name.normalizeString().contains(normalizedText, true)) {
                    val fileDirItem = getListItemFromFile(it, isSortingBySize, HashMap(), false)
                    if (fileDirItem != null) {
                        files.add(fileDirItem)
                    }
                }

                files.addAll(searchFiles(text, it.absolutePath))
            } else {
                if (it.name.normalizeString().contains(normalizedText, true)) {
                    val fileDirItem = getListItemFromFile(it, isSortingBySize, HashMap(), false)
                    if (fileDirItem != null) {
                        files.add(fileDirItem)
                    }
                }
            }
        }
        return files
    }

    private fun searchClosed() {
        binding.apply {
            lastSearchedText = ""
            itemsSwipeRefresh.isEnabled = activity?.config?.enablePullToRefresh != false
            itemsFastscroller.beVisible()
            itemsPlaceholder.beGone()
            itemsPlaceholder2.beGone()
            hideProgressBar()
        }
    }

    fun showCreateNewItemDialog() {
        CreateNewItemDialog(activity as SimpleActivity, currentPath) { success, createdPath ->
            if (!success) {
                return@CreateNewItemDialog
            }

            val revealPath = createdPath?.trim().orEmpty()
            if (revealPath.isNotEmpty()) {
                openPathAndHighlight(currentPath, arrayListOf(revealPath))
            } else {
                refreshFragment()
            }
        }
    }

    private fun getRecyclerAdapter() = binding.itemsList.adapter as? ItemsAdapter

    private fun setupLayoutManager() {
        if (context!!.config.getFolderViewType(currentPath) == VIEW_TYPE_GRID) {
            currentViewType = VIEW_TYPE_GRID
            setupGridLayoutManager()
        } else {
            currentViewType = VIEW_TYPE_LIST
            setupListLayoutManager()
        }

        binding.itemsList.adapter = null
        initZoomListener()
        addItems(storedItems, true)
    }

    private fun setupGridLayoutManager() {
        val layoutManager = binding.itemsList.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = context?.config?.fileColumnCnt ?: 3

        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (getRecyclerAdapter()?.isASectionTitle(position) == true || getRecyclerAdapter()?.isGridTypeDivider(position) == true) {
                    layoutManager.spanCount
                } else {
                    1
                }
            }
        }
    }

    private fun setupListLayoutManager() {
        val layoutManager = binding.itemsList.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = 1
        zoomListener = null
    }

    private fun initZoomListener() {
        if (context?.config?.getFolderViewType(currentPath) == VIEW_TYPE_GRID) {
            val layoutManager = binding.itemsList.layoutManager as MyGridLayoutManager
            zoomListener = object : MyRecyclerView.MyZoomListener {
                override fun zoomIn() {
                    if (layoutManager.spanCount > 1) {
                        reduceColumnCount()
                        getRecyclerAdapter()?.finishActMode()
                    }
                }

                override fun zoomOut() {
                    if (layoutManager.spanCount < MAX_COLUMN_COUNT) {
                        increaseColumnCount()
                        getRecyclerAdapter()?.finishActMode()
                    }
                }
            }
        } else {
            zoomListener = null
        }
    }

    private fun increaseColumnCount() {
        if (currentViewType == VIEW_TYPE_GRID) {
            context!!.config.fileColumnCnt += 1
            fileManagerControllerCommands.updateFragmentColumnCounts()
        }
    }

    private fun reduceColumnCount() {
        if (currentViewType == VIEW_TYPE_GRID) {
            context!!.config.fileColumnCnt -= 1
            fileManagerControllerCommands.updateFragmentColumnCounts()
        }
    }

    override fun columnCountChanged() {
        (binding.itemsList.layoutManager as MyGridLayoutManager).spanCount = context!!.config.fileColumnCnt
        fileManagerControllerCommands.refreshMenuItems()
        getRecyclerAdapter()?.apply {
            notifyItemRangeChanged(0, listItems.size)
        }
    }

    fun showProgressBar() {
        binding.progressBar.show()
    }

    private fun hideProgressBar() {
        binding.progressBar.hide()
    }

    override fun toggleFilenameVisibility() {
        getRecyclerAdapter()?.updateDisplayFilenamesInGrid()
    }

    override fun refreshFragment() {
        if (contentMode == ContentMode.RECENTS) {
            loadCurrentFolderRecents(forceRefresh = true)
        } else {
            openPath(currentPath)
        }
    }

    override fun openPathAndHighlight(targetPath: String, highlightPaths: ArrayList<String>) {
        fileManagerControllerCommands.openPathAndHighlight(targetPath, highlightPaths)
    }

    fun openPathAndHighlightInCurrentWorkspace(targetPath: String, highlightPaths: ArrayList<String>) {
        val normalizedTarget = clampToVisiblePath(targetPath.trimEnd('/').ifEmpty { "/" })
        val normalizedHighlights = LinkedHashSet<String>()
        highlightPaths.forEach { raw ->
            val value = raw.trim().replace('\\', '/').trimEnd('/')
            if (value.isNotEmpty()) {
                normalizedHighlights.add(value)
            }
        }
        pendingRevealRequest = RevealRequest(
            targetPath = normalizedTarget.trimEnd('/').ifEmpty { "/" },
            highlightPaths = normalizedHighlights
        )
        openPath(normalizedTarget, forceRefresh = true)
    }

    override fun deleteFiles(files: ArrayList<FileDirItem>) {
        val hasFolder = files.any { it.isDirectory }
        handleFileDeleting(files, hasFolder)
    }

    override fun selectedPaths(paths: ArrayList<String>) {
        fileManagerResultHandler.pickedPaths(paths)
    }

    private fun clampToVisiblePath(path: String): String {
        val ctx = context ?: return TermuxPathScope.normalizePath(path)
        val isTermuxScoped = fileManagerEnvironment.isTermuxScopedFileManager()
        val fallback = if (isTermuxScoped) TermuxPathScope.preferredLocalRoot(ctx) else "/"
        return TermuxPathScope.clampVisiblePath(ctx, path, fallback, isTermuxScoped)
    }

    private fun resolveParentPathForNavigation(rawPath: String): String {
        val ctx = context ?: return "/"
        val isTermuxScoped = fileManagerEnvironment.isTermuxScopedFileManager()
        val localRoot = if (isTermuxScoped) TermuxPathScope.preferredLocalRoot(ctx) else "/"
        val current = TermuxPathScope.normalizePath(rawPath).ifEmpty { localRoot }

        if (current == "/" || (isTermuxScoped && current == localRoot)) {
            return localRoot
        }

        if (isVirtualWorkspaceRoot(ctx, current)) {
            return current
        }

        var parent = current.getParentPath().trimEnd('/')
        if (parent.isEmpty() || parent == "/") {
            parent = localRoot
        }
        return if (isTermuxScoped) TermuxPathScope.clampVisiblePath(ctx, parent, localRoot, true) else parent
    }

    private fun isVirtualWorkspaceRoot(ctx: Context, current: String): Boolean {
        if (!sessionFileCoordinator.isVirtualPath(ctx, current)) return false
        val virtualPrefix = "${TermuxPathScope.termuxRootPath(ctx)}/.termux/sftp-virtual/"
        if (!current.startsWith(virtualPrefix)) return false
        val tail = current.removePrefix(virtualPrefix)
        return tail.isNotEmpty() && !tail.contains("/")
    }

    private fun applyPendingRevealIfNeeded(items: ArrayList<ListItem>) {
        val request = pendingRevealRequest ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - request.createdAtMs > 15_000L) {
            pendingRevealRequest = null
            return
        }

        val current = currentPath.trimEnd('/').ifEmpty { "/" }
        if (current != request.targetPath) return
        pendingRevealRequest = null

        if (request.highlightPaths.isEmpty()) return

        val pathCandidates = request.highlightPaths
            .map { it.trimEnd('/').ifEmpty { "/" } }
            .toSet()

        val matchedPaths = ArrayList<String>()
        var firstIndex = -1
        items.forEachIndexed { index, item ->
            if (item.isSectionTitle || item.isGridTypeDivider) return@forEachIndexed
            val normalizedItemPath = item.path.trimEnd('/').ifEmpty { "/" }
            if (pathCandidates.contains(normalizedItemPath)) {
                matchedPaths.add(item.path)
                if (firstIndex == -1) firstIndex = index
            }
        }

        if (matchedPaths.isEmpty()) return

        binding.itemsList.post {
            val adapter = getRecyclerAdapter() ?: return@post
            if (firstIndex >= 0) {
                runCatching { binding.itemsList.smoothScrollToPosition(firstIndex) }
            }
            adapter.highlightPathsOnce(matchedPaths)
        }
    }
}
