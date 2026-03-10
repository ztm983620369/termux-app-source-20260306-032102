package org.fossify.filemanager.fragments

import android.content.Context
import android.util.AttributeSet
import com.termux.bridge.FileOpenRequest
import com.termux.bridge.RecentFileEntry
import com.termux.bridge.RecentFileHistory
import com.termux.sessionsync.SessionFileCoordinator
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.VIEW_TYPE_GRID
import org.fossify.commons.helpers.VIEW_TYPE_LIST
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.views.MyGridLayoutManager
import org.fossify.commons.views.MyRecyclerView
import org.fossify.filemanager.activities.SimpleActivity
import org.fossify.filemanager.adapters.ItemsAdapter
import org.fossify.filemanager.databinding.RecentsFragmentBinding
import org.fossify.filemanager.extensions.config
import org.fossify.filemanager.helpers.MAX_COLUMN_COUNT
import org.fossify.filemanager.helpers.TermuxPathScope
import org.fossify.filemanager.interfaces.ItemOperationsListener
import org.fossify.filemanager.models.ListItem
import java.io.File

class RecentsFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.RecentsInnerBinding>(context, attributeSet),
    ItemOperationsListener {
    companion object {
        private const val RECENTS_LIMIT = 200
    }

    private data class RecentListResult(
        val items: ArrayList<ListItem>,
        val entriesByPath: LinkedHashMap<String, RecentFileEntry>
    )

    private val sessionFileCoordinator = SessionFileCoordinator.getInstance()
    private var filesIgnoringSearch = ArrayList<ListItem>()
    private var lastSearchedText = ""
    private var recentEntriesByPath = linkedMapOf<String, RecentFileEntry>()
    private var zoomListener: MyRecyclerView.MyZoomListener? = null
    private var storedItems = ArrayList<ListItem>()
    private lateinit var binding: RecentsFragmentBinding

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = RecentsFragmentBinding.bind(this)
        innerBinding = RecentsInnerBinding(binding)
    }

    override fun setupFragment(activity: SimpleActivity) {
        if (this.activity == null) {
            this.activity = activity
            binding.recentsSwipeRefresh.setOnRefreshListener { refreshFragment() }
        }

        refreshFragment()
    }

    override fun refreshFragment() {
        ensureBackgroundThread {
            getRecents { result ->
                binding.apply {
                    recentsSwipeRefresh.isRefreshing = false
                    recentsList.beVisibleIf(result.items.isNotEmpty())
                    recentsPlaceholder.beVisibleIf(result.items.isEmpty())
                }
                recentEntriesByPath = result.entriesByPath
                filesIgnoringSearch = result.items
                addItems(result.items, false)

                if (context != null && currentViewType != context!!.config.getFolderViewType("")) {
                    setupLayoutManager()
                }
            }
        }
    }

    private fun addItems(recents: ArrayList<ListItem>, forceRefresh: Boolean) {
        if (!forceRefresh && recents.hashCode() == storedItems.hashCode()) {
            return
        }

        storedItems = recents
        val existingAdapter = binding.recentsList.adapter as? ItemsAdapter
        if (existingAdapter == null || forceRefresh) {
            ItemsAdapter(activity as SimpleActivity, storedItems, this, binding.recentsList, isPickMultipleIntent, binding.recentsSwipeRefresh, false, false) {
                handleRecentClick((it as FileDirItem).path)
            }.apply {
                setupZoomListener(zoomListener)
                binding.recentsList.adapter = this
            }
        } else {
            existingAdapter.apply {
                updateItems(storedItems)
                setupZoomListener(zoomListener)
            }
        }

        if (context.areSystemAnimationsEnabled) {
            binding.recentsList.scheduleLayoutAnimation()
        }
    }

    override fun onResume(textColor: Int) {
        binding.recentsPlaceholder.setTextColor(textColor)

        getRecyclerAdapter()?.apply {
            updatePrimaryColor()
            updateTextColor(textColor)
            initDrawables()
        }

        binding.recentsSwipeRefresh.isEnabled = lastSearchedText.isEmpty() && activity?.config?.enablePullToRefresh != false
    }

    private fun setupLayoutManager() {
        if (context!!.config.getFolderViewType("") == VIEW_TYPE_GRID) {
            currentViewType = VIEW_TYPE_GRID
            setupGridLayoutManager()
        } else {
            currentViewType = VIEW_TYPE_LIST
            setupListLayoutManager()
        }

        val oldItems = (binding.recentsList.adapter as? ItemsAdapter)?.listItems?.toMutableList() as ArrayList<ListItem>
        binding.recentsList.adapter = null
        initZoomListener()
        addItems(oldItems, true)
    }

    private fun setupGridLayoutManager() {
        val layoutManager = binding.recentsList.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = context?.config?.fileColumnCnt ?: 3
    }

    private fun setupListLayoutManager() {
        val layoutManager = binding.recentsList.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = 1
        zoomListener = null
    }

    private fun initZoomListener() {
        if (context?.config?.getFolderViewType("") == VIEW_TYPE_GRID) {
            val layoutManager = binding.recentsList.layoutManager as MyGridLayoutManager
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

    private fun handleRecentClick(path: String) {
        val context = context ?: return
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

        binding.recentsSwipeRefresh.isRefreshing = true
        ensureBackgroundThread {
            val result = sessionFileCoordinator.materializeVirtualFile(context.applicationContext, remoteOriginPath)
            activity?.runOnUiThread {
                binding.recentsSwipeRefresh.isRefreshing = false
                if (!result.success) {
                    activity?.toast(result.messageCn)
                    return@runOnUiThread
                }

                val localPath = result.localPath
                val displayName = entry.displayName.ifBlank { remoteOriginPath.getFilenameFromPath() }
                val extension = displayName.substringAfterLast('.', "").lowercase().ifBlank { null }
                val originDisplayPath = entry.originDisplayPath
                    ?.takeIf { it.isNotBlank() }
                    ?: sessionFileCoordinator.getDisplayPath(context, remoteOriginPath)
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
                        originDisplayPath = originDisplayPath
                    )
                )
            }
        }
    }

    private fun getRecents(callback: (result: RecentListResult) -> Unit) {
        val context = context ?: return
        val listItems = arrayListOf<ListItem>()
        val recentEntries = LinkedHashMap<String, RecentFileEntry>()

        try {
            val isTermuxScoped = fileManagerEnvironment.isTermuxScopedFileManager()
            RecentFileHistory.getRecentFiles(context, RECENTS_LIMIT).forEach { entry ->
                val listPath = entry.listPath()
                if (!TermuxPathScope.isVisibleInFileManager(context, listPath, isTermuxScoped)) {
                    return@forEach
                }

                val isRemote = entry.remoteOriginPath() != null
                val file = File(entry.path)
                if (!isRemote) {
                    if (!file.exists() || !file.isFile) {
                        RecentFileHistory.removePath(context, entry.path)
                        return@forEach
                    }
                }

                if (wantedMimeTypes.any { isProperMimeType(it, listPath, false) }) {
                    val name = entry.displayName.ifBlank { listPath.getFilenameFromPath() }
                    val size = if (file.exists() && file.isFile) file.length() else 0L
                    val modified = entry.openedAtMs
                    listItems.add(ListItem(listPath, name, false, 0, size, modified, false, false))
                    recentEntries[listPath] = entry
                }
            }
        } catch (e: Exception) {
            activity?.showErrorToast(e)
        }

        activity?.runOnUiThread {
            callback(RecentListResult(listItems, recentEntries))
        }
    }

    private fun RecentFileEntry.remoteOriginPath(): String? {
        val normalizedOriginPath = originPath?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return if (originType == FileOpenRequest.ORIGIN_SFTP_VIRTUAL) normalizedOriginPath else null
    }

    private fun RecentFileEntry.listPath(): String {
        return remoteOriginPath() ?: path
    }

    private fun getRecyclerAdapter() = binding.recentsList.adapter as? ItemsAdapter

    override fun toggleFilenameVisibility() {
        getRecyclerAdapter()?.updateDisplayFilenamesInGrid()
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
        (binding.recentsList.layoutManager as MyGridLayoutManager).spanCount = context!!.config.fileColumnCnt
        fileManagerControllerCommands.refreshMenuItems()
        getRecyclerAdapter()?.apply {
            notifyItemRangeChanged(0, listItems.size)
        }
    }

    override fun setupFontSize() {
        getRecyclerAdapter()?.updateFontSizes()
    }

    override fun setupDateTimeFormat() {
        getRecyclerAdapter()?.updateDateTimeFormat()
    }

    override fun selectedPaths(paths: ArrayList<String>) {
        fileManagerResultHandler.pickedPaths(paths)
    }

    override fun deleteFiles(files: ArrayList<FileDirItem>) {
        handleFileDeleting(files, false)
    }

    override fun searchQueryChanged(text: String) {
        lastSearchedText = text
        val normalizedText = text.normalizeString()
        val filtered = filesIgnoringSearch.filter {
            it.mName.normalizeString().contains(normalizedText, true)
        }.toMutableList() as ArrayList<ListItem>

        binding.apply {
            (recentsList.adapter as? ItemsAdapter)?.updateItems(filtered, text)
            recentsPlaceholder.beVisibleIf(filtered.isEmpty())
            recentsSwipeRefresh.isEnabled = lastSearchedText.isEmpty() && activity?.config?.enablePullToRefresh != false
        }
    }

    override fun finishActMode() {
        getRecyclerAdapter()?.finishActMode()
    }
}
