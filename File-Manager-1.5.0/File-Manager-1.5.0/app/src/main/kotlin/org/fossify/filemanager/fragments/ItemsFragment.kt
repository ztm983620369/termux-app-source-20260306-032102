package org.fossify.filemanager.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.os.Parcelable
import android.os.SystemClock
import android.util.AttributeSet
import androidx.recyclerview.widget.GridLayoutManager
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
import org.fossify.filemanager.interfaces.ItemOperationsListener
import org.fossify.filemanager.models.ListItem
import com.termux.bridge.FileOpenRequest
import com.termux.sessionsync.SessionFileCoordinator
import com.termux.sessionsync.SftpProtocolManager
import java.io.File

class ItemsFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.ItemsInnerBinding>(context, attributeSet),
    ItemOperationsListener {
    private val sessionFileCoordinator = SessionFileCoordinator.getInstance()
    private var showHidden = false
    private var lastSearchedText = ""
    private var scrollStates = HashMap<String, Parcelable>()
    private var zoomListener: MyRecyclerView.MyZoomListener? = null
    private var pendingRevealRequest: RevealRequest? = null

    private var storedItems = ArrayList<ListItem>()
    private var itemsIgnoringSearch = ArrayList<ListItem>()
    private lateinit var binding: ItemsFragmentBinding

    private data class RevealRequest(
        val targetPath: String,
        val highlightPaths: Set<String>,
        val createdAtMs: Long = SystemClock.elapsedRealtime()
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
                    if (NavigatorFolderHelper.isNavigatorPath(activity, currentPath)) {
                        return@setOnClickListener
                    }
                    val parent = resolveParentPathForNavigation(currentPath)
                    if (parent == currentPath.trimEnd('/')) {
                        return@setOnClickListener
                    }
                    openPath(parent, forceRefresh = true)
                }
                pathBarHolder.setOnLongClickListener {
                    fileManagerControllerCommands.showSessionSwitcher()
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

    fun openPath(path: String, forceRefresh: Boolean = false) {
        if ((activity as? BaseSimpleActivity)?.isAskingPermissions == true) {
            return
        }

        var realPath = path.trimEnd('/')
        if (realPath.isEmpty()) {
            realPath = "/"
        }
        realPath = clampToVisiblePath(realPath)

        scrollStates[currentPath] = getScrollState()!!
        currentPath = realPath
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
            val ctx = context
            binding.pathBarText.text = if (ctx != null) {
                if (NavigatorFolderHelper.isNavigatorPath(ctx, currentPath)) {
                    NavigatorFolderHelper.displayTitle()
                } else {
                    sessionFileCoordinator.getDisplayPath(ctx, currentPath)
                }
            } else currentPath
            if (!forceRefresh && items.hashCode() == storedItems.hashCode()) {
                return@runOnUiThread
            }

            storedItems = items
            if (binding.itemsList.adapter == null) {
                ItemsAdapter(activity as SimpleActivity, storedItems, this, binding.itemsList, isPickMultipleIntent, binding.itemsSwipeRefresh) {
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

    private fun itemClicked(item: FileDirItem) {
        if (context != null && NavigatorFolderHelper.isNavigatorPath(context!!, currentPath)) {
            val ctx = context!!
            val selectedSessionKey = NavigatorFolderHelper.resolveSessionKeyForTargetPath(ctx, item.path)
            sessionFileCoordinator.setSelectedSessionKey(ctx, selectedSessionKey)
            openDirectory(item.path)
            return
        }

        if (context != null && sessionFileCoordinator.isVirtualPath(context!!, item.path)) {
            if (item.isDirectory) {
                openDirectory(item.path)
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
                                    originDisplayPath = sessionFileCoordinator.getDisplayPath(context!!, item.path)
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
            openDirectory(item.path)
        } else {
            clickedPath(item.path)
        }
    }

    private fun openDirectory(path: String) {
        fileManagerControllerCommands.openedDirectory()
        openPath(path)
    }

    override fun searchQueryChanged(text: String) {
        lastSearchedText = text
        if (context == null) {
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
        openPath(currentPath)
    }

    override fun openPathAndHighlight(targetPath: String, highlightPaths: ArrayList<String>) {
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
        val nameCandidates = request.highlightPaths
            .map { it.getFilenameFromPath() }
            .filter { it.isNotBlank() }
            .toSet()

        val matchedPaths = ArrayList<String>()
        var firstIndex = -1
        items.forEachIndexed { index, item ->
            if (item.isSectionTitle || item.isGridTypeDivider) return@forEachIndexed
            val normalizedItemPath = item.path.trimEnd('/').ifEmpty { "/" }
            val directMatch = pathCandidates.contains(normalizedItemPath)
            val nameMatch = nameCandidates.any { expected -> isSameOrIndexedConflictName(expected, item.name) }
            if (directMatch || nameMatch) {
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

    private fun isSameOrIndexedConflictName(expectedName: String, actualName: String): Boolean {
        if (expectedName == actualName) return true
        if (expectedName.isBlank() || actualName.isBlank()) return false

        val expectedDot = expectedName.lastIndexOf('.')
        val actualDot = actualName.lastIndexOf('.')
        if (expectedDot > 0 && actualDot > 0) {
            val expectedExt = expectedName.substring(expectedDot)
            val actualExt = actualName.substring(actualDot)
            if (!expectedExt.equals(actualExt, ignoreCase = true)) return false
            val expectedStem = expectedName.substring(0, expectedDot)
            val actualStem = actualName.substring(0, actualDot)
            return actualStem == expectedStem || hasIndexedSuffix(actualStem, expectedStem)
        }

        return hasIndexedSuffix(actualName, expectedName)
    }

    private fun hasIndexedSuffix(actual: String, base: String): Boolean {
        if (!actual.startsWith(base)) return false
        if (actual.length <= base.length + 2) return false
        if (actual[base.length] != '(' || actual.last() != ')') return false
        val number = actual.substring(base.length + 1, actual.length - 1)
        return number.isNotEmpty() && number.all { it.isDigit() }
    }
}
