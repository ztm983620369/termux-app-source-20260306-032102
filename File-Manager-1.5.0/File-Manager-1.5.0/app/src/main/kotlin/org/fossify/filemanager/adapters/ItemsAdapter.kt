package org.fossify.filemanager.adapters

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import com.stericson.RootTools.RootTools
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.dialogs.PropertiesDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.dialogs.RenameDialog
import org.fossify.commons.dialogs.RenameItemDialog
import org.fossify.commons.dialogs.RenameItemsDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.convertToBitmap
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.deleteFile
import org.fossify.commons.extensions.formatDate
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAndroidSAFUri
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getDefaultCopyDestinationPath
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getFileCount
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getIsPathDirectory
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getProperSize
import org.fossify.commons.extensions.getTextSize
import org.fossify.commons.extensions.getTimeFormat
import org.fossify.commons.extensions.handleDeletePasswordProtection
import org.fossify.commons.extensions.hasOTGConnected
import org.fossify.commons.extensions.highlightTextPart
import org.fossify.commons.extensions.isAValidFilename
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.isRestrictedSAFOnlyRoot
import org.fossify.commons.extensions.setupViewBackground
import org.fossify.commons.extensions.toFileDirItem
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.VIEW_TYPE_LIST
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.getFilePlaceholderDrawables
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.models.RadioItem
import org.fossify.commons.views.MyRecyclerView
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.SimpleActivity
import org.fossify.filemanager.activities.SplashActivity
import org.fossify.filemanager.databinding.ItemDirGridBinding
import org.fossify.filemanager.databinding.ItemEmptyBinding
import org.fossify.filemanager.databinding.ItemFileDirListBinding
import org.fossify.filemanager.databinding.ItemFileGridBinding
import org.fossify.filemanager.databinding.ItemSectionBinding
import org.fossify.filemanager.extensions.config
import org.fossify.filemanager.extensions.isArchiveFile
import org.fossify.filemanager.extensions.isPathOnRoot
import org.fossify.filemanager.extensions.setAs
import org.fossify.filemanager.extensions.toggleItemVisibility
import org.fossify.filemanager.extensions.tryOpenPathIntent
import org.fossify.filemanager.helpers.ActiveTransferMode
import org.fossify.filemanager.helpers.ActiveTransferRegistry
import org.fossify.filemanager.helpers.ActiveTransferStatus
import org.fossify.filemanager.helpers.ArchiveTransferWorkflow
import org.fossify.filemanager.helpers.ClipboardPathFormatter
import org.fossify.filemanager.helpers.FavoriteHelper
import org.fossify.filemanager.helpers.OPEN_AS_AUDIO
import org.fossify.filemanager.helpers.OPEN_AS_IMAGE
import org.fossify.filemanager.helpers.OPEN_AS_OTHER
import org.fossify.filemanager.helpers.OPEN_AS_TEXT
import org.fossify.filemanager.helpers.OPEN_AS_VIDEO
import org.fossify.filemanager.helpers.NavigatorFolderHelper
import org.fossify.filemanager.helpers.RecentPathFormatter
import org.fossify.filemanager.helpers.RemoteDownloadCoordinator
import org.fossify.filemanager.helpers.RootHelpers
import org.fossify.filemanager.helpers.ShareSelectionWorkflow
import org.fossify.filemanager.helpers.TransferExecutionPlan
import org.fossify.filemanager.helpers.TransferSelectionKind
import org.fossify.filemanager.helpers.TransferWorkflowStage
import org.fossify.filemanager.helpers.TransferWorkflowStateMachine
import org.fossify.filemanager.interfaces.ItemOperationsListener
import org.fossify.filemanager.models.ListItem
import org.fossify.filemanager.views.InlineTransferProgressView
import com.termux.sessionsync.FileRootResolver
import com.termux.sessionsync.SessionFileCoordinator
import com.termux.sessionsync.SftpProtocolManager
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ItemsAdapter(
    activity: SimpleActivity,
    var listItems: MutableList<ListItem>,
    private val listener: ItemOperationsListener?,
    recyclerView: MyRecyclerView,
    private val isPickMultipleIntent: Boolean,
    private val swipeRefreshLayout: SwipeRefreshLayout?,
    canHaveIndividualViewType: Boolean = true,
    private val showFileDate: Boolean = true,
    private var showRecentPathMetadata: Boolean = false,
    itemClick: (Any) -> Unit,
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick),
    RecyclerViewFastScroller.OnPopupTextUpdate {

    private val simpleActivity: SimpleActivity = activity
    private lateinit var fileDrawable: Drawable
    private lateinit var folderDrawable: Drawable
    private var fileDrawables = HashMap<String, Drawable>()
    private var currentItemsHash = listItems.hashCode()
    private var textToHighlight = ""
    private val hasOTGConnected = activity.hasOTGConnected()
    private var fontSize = 0f
    private var smallerFontSize = 0f
    private var recentMetadataFontSize = 0f
    private var dateFormat = ""
    private var timeFormat = ""
    private val sessionFileCoordinator = SessionFileCoordinator.getInstance()
    private val transientHighlightKeys = LinkedHashSet<Int>()
    private val recentDisplayPathCache = HashMap<String, String>()
    private var clearTransientHighlightRunnable: Runnable? = null
    private val transferProgressListener: (Set<String>) -> Unit = { changedPaths ->
        activity.runOnUiThread {
            notifyTransferProgressChanged(changedPaths)
        }
    }

    private val config = activity.config
    private val viewType = if (canHaveIndividualViewType) {
        config.getFolderViewType(
            path = listItems.firstOrNull { !it.isSectionTitle }?.mPath?.getParentPath().orEmpty()
        )
    } else {
        config.viewType
    }
    private val isListViewType = viewType == VIEW_TYPE_LIST
    private var displayFilenamesInGrid = config.displayFilenames
    private val shareSelectionWorkflow = ShareSelectionWorkflow(
        activity = activity,
        sessionFileCoordinator = sessionFileCoordinator,
        shouldShowHidden = { config.shouldShowHidden() }
    )

    companion object {
        private const val ACTION_ENTER_MULTI_SELECT = -1
        private const val TYPE_FILE = 1
        private const val TYPE_DIR = 2
        private const val TYPE_SECTION = 3
        private const val TYPE_GRID_TYPE_DIVIDER = 4
        private const val TRANSIENT_HIGHLIGHT_DURATION_MS = 1_600L
        private const val TRANSIENT_HIGHLIGHT_RETRY_MS = 700L
        private const val PAYLOAD_TRANSFER_PROGRESS = "transfer-progress"
        private val virtualDownloadInProgress = AtomicBoolean(false)
        private val transferWorkflowStateMachine = TransferWorkflowStateMachine()
        private val virtualUploadInProgress = AtomicBoolean(false)
        private val ARCHIVE_DIALOG_SUFFIXES = listOf(
            ".tar.gz", ".tgz", ".tar.bz2", ".tbz", ".tbz2", ".tar.xz", ".txz", ".tar.zst", ".tzst",
            ".zip", ".jar", ".apk", ".aar", ".war", ".tar", ".gz", ".bz2", ".xz", ".zst", ".7z", ".rar",
            ".001", ".cab", ".iso", ".img", ".dmg", ".wim", ".swm", ".esd", ".ar", ".deb", ".rpm", ".cpio",
            ".lzma", ".lz4", ".br", ".z", ".lzh", ".lha", ".chm", ".msi", ".nsis", ".udf", ".vhd", ".vhdx",
            ".vmdk", ".qcow", ".qcow2", ".squashfs", ".crx", ".xar"
        )
    }

    init {
        setupDragListener(true)
        initDrawables()
        updateFontSizes()
        dateFormat = config.dateFormat
        timeFormat = activity.getTimeFormat()
    }

    override fun getActionMenuId() = R.menu.cab

    override fun prepareActionMode(menu: Menu) {
        menu.apply {
            findItem(R.id.cab_decompress).isVisible =
                getSelectedFileDirItems().map { it.path }.any { it.isArchiveFile() }
            findItem(R.id.cab_share).isVisible = shareSelectionWorkflow.canShare(getSelectedFileDirItems())
            findItem(R.id.cab_confirm_selection).isVisible = isPickMultipleIntent
            findItem(R.id.cab_copy_path).isVisible = isOneItemSelected()
            findItem(R.id.cab_open_with).isVisible = isOneFileSelected()
            findItem(R.id.cab_open_as).isVisible = isOneFileSelected()
            findItem(R.id.cab_set_as).isVisible = isOneFileSelected()
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_confirm_selection -> confirmSelection()
            R.id.cab_rename -> displayRenameDialog()
            R.id.cab_properties -> showProperties()
            R.id.add_favorite -> addSelectedItemToFavorites()
            R.id.remove_favorite -> removeSelectedItemFromFavorites()
            R.id.cab_copy_path -> copyPath()
            R.id.cab_set_as -> setAs()
            R.id.cab_share -> shareFiles()
            R.id.cab_open_with -> openWith()
            R.id.cab_open_as -> openAs()
            R.id.cab_copy_to -> copyMoveTo(true)
            R.id.cab_move_to -> tryMoveFiles()
            R.id.cab_compress -> compressSelection()
            R.id.cab_decompress -> decompressSelection()
            R.id.cab_delete -> if (config.skipDeleteConfirmation) deleteFiles() else askConfirmDelete()
        }
    }

    override fun getSelectableItemCount(): Int {
        return listItems.filter { !it.isSectionTitle && !it.isGridTypeDivider }.size
    }

    override fun getIsItemSelectable(position: Int): Boolean {
        val item = listItems[position]
        return !item.isSectionTitle && !item.isGridTypeDivider
    }

    override fun getItemSelectionKey(position: Int): Int? {
        return listItems.getOrNull(position)?.path?.hashCode()
    }

    override fun getItemKeyPosition(key: Int): Int {
        return listItems.indexOfFirst { it.path.hashCode() == key }
    }

    override fun onActionModeCreated() {
        swipeRefreshLayout?.isRefreshing = false
        swipeRefreshLayout?.isEnabled = false
    }

    override fun onActionModeDestroyed() {
        swipeRefreshLayout?.isEnabled = config.enablePullToRefresh
    }

    override fun getItemViewType(position: Int): Int {
        return when {
            listItems[position].isGridTypeDivider -> TYPE_GRID_TYPE_DIVIDER
            listItems[position].isSectionTitle -> TYPE_SECTION
            listItems[position].mIsDirectory -> TYPE_DIR
            else -> TYPE_FILE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = Binding.getByItemViewType(viewType, isListViewType)
            .inflate(layoutInflater, parent, false)

        return createViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: MyRecyclerViewAdapter.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_TRANSFER_PROGRESS)) {
            val listItem = listItems.getOrNull(position)
            if (listItem != null && !listItem.isSectionTitle && !listItem.isGridTypeDivider) {
                val binding = Binding.getByItemViewType(holder.itemViewType, isListViewType).bind(holder.itemView)
                bindTransferProgress(binding, listItem)
                return
            }
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: MyRecyclerViewAdapter.ViewHolder, position: Int) {
        val fileDirItem = listItems[position]
        val rowView = holder.bindView(
            any = fileDirItem,
            allowSingleClick = true,
            allowLongClick = false
        ) { itemView, layoutPosition ->
            val viewType = getItemViewType(position)
            setupView(
                binding = Binding.getByItemViewType(viewType, isListViewType).bind(itemView),
                listItem = fileDirItem
            )
        }
        rowView.setOnLongClickListener {
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition == RecyclerView.NO_POSITION) return@setOnLongClickListener true
            val itemPosition = adapterPosition - positionOffset
            if (!getIsItemSelectable(itemPosition)) return@setOnLongClickListener true
            if (actModeCallback.isSelectable) {
                enterSelectionMode(itemPosition)
            } else {
                showLongPressActionDialog(itemPosition)
            }
            true
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = listItems.size

    private fun getItemWithKey(key: Int): FileDirItem? {
        return listItems.firstOrNull { it.path.hashCode() == key }
    }

    private fun isOneFileSelected(): Boolean {
        return isOneItemSelected() && getItemWithKey(selectedKeys.first())?.isDirectory == false
    }

    private data class LongPressAction(val id: Int, val title: String)

    private fun showLongPressActionDialog(position: Int) {
        if (position !in 0 until listItems.size) return
        finishActMode()
        toggleItemSelection(true, position, updateTitle = false)

        val actions = buildLongPressActions()
        if (actions.isEmpty()) {
            finishActMode()
            return
        }

        val selectedItem = listItems.getOrNull(position)
        val dialogTitle = selectedItem?.name?.takeIf { it.isNotBlank() } ?: activity.getString(R.string.app_launcher_name)
        val labels = actions.map { it.title }.toTypedArray()
        var actionChosen = false
        var dismissAction: (() -> Unit)? = null

        activity.getAlertDialogBuilder()
            .setTitle(dialogTitle)
            .setItems(labels) { _, which ->
                val actionId = actions.getOrNull(which)?.id ?: return@setItems
                actionChosen = true
                if (actionId == ACTION_ENTER_MULTI_SELECT) {
                    dismissAction = { enterSelectionMode(position) }
                    return@setItems
                }
                actionItemPressed(actionId)
                if (shouldClearSelectionImmediately(actionId)) {
                    finishActMode()
                }
            }
            .setOnDismissListener {
                if (dismissAction != null) {
                    dismissAction?.invoke()
                } else if (!actionChosen) {
                    finishActMode()
                }
            }
            .show()
    }

    private fun buildLongPressActions(): ArrayList<LongPressAction> {
        if (selectedKeys.isEmpty()) return arrayListOf()
        val selected = getSelectedFileDirItems()
        if (selected.isEmpty()) return arrayListOf()

        var hiddenCnt = 0
        var unhiddenCnt = 0
        selected.forEach {
            if (it.name.startsWith(".")) hiddenCnt++ else unhiddenCnt++
        }

        val actions = ArrayList<LongPressAction>(14)
        if (isPickMultipleIntent) {
            actions.add(LongPressAction(R.id.cab_confirm_selection, activity.getString(R.string.confirm_selection)))
        }
        actions.add(LongPressAction(ACTION_ENTER_MULTI_SELECT, activity.getString(R.string.multi_select)))
        if (shareSelectionWorkflow.canShare(selected)) {
            actions.add(LongPressAction(R.id.cab_share, activity.getString(R.string.share)))
        }
        actions.add(LongPressAction(R.id.cab_rename, activity.getString(R.string.rename)))
        actions.add(LongPressAction(R.id.cab_properties, activity.getString(R.string.properties)))

        if (isOneItemSelected()) {
            actions.add(LongPressAction(R.id.cab_copy_path, activity.getString(R.string.copy_path)))
            val selectedPath = getFirstSelectedItemPath()
            val isFavorite = config.isFavorite(selectedPath)
            actions.add(
                LongPressAction(
                    if (isFavorite) R.id.remove_favorite else R.id.add_favorite,
                    activity.getString(if (isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites)
                )
            )
        }
        if (isOneFileSelected()) {
            actions.add(LongPressAction(R.id.cab_open_with, activity.getString(R.string.open_with)))
            actions.add(LongPressAction(R.id.cab_open_as, activity.getString(R.string.open_as)))
            actions.add(LongPressAction(R.id.cab_set_as, activity.getString(R.string.set_as)))
        }

        actions.add(LongPressAction(R.id.cab_copy_to, activity.getString(R.string.copy_to)))
        actions.add(LongPressAction(R.id.cab_move_to, activity.getString(R.string.move_to)))
        actions.add(LongPressAction(R.id.cab_compress, activity.getString(R.string.compress)))

        val hasArchive = selected.any { it.path.isArchiveFile() }
        if (hasArchive) {
            actions.add(LongPressAction(R.id.cab_decompress, activity.getString(R.string.decompress)))
        }

        actions.add(LongPressAction(R.id.cab_delete, activity.getString(R.string.delete)))
        return actions
    }

    private fun shouldClearSelectionImmediately(actionId: Int): Boolean {
        return when (actionId) {
            R.id.cab_confirm_selection,
            R.id.cab_rename,
            R.id.cab_properties,
            R.id.add_favorite,
            R.id.remove_favorite,
            R.id.cab_copy_path,
            R.id.cab_set_as,
            R.id.cab_share,
            R.id.cab_open_with,
            R.id.cab_open_as,
            R.id.cab_copy_to,
            R.id.cab_move_to,
            R.id.cab_compress,
            R.id.cab_decompress -> true

            else -> false
        }
    }

    private fun addSelectedItemToFavorites() {
        if (!isOneItemSelected()) return
        val selectedPath = getFirstSelectedItemPath()
        FavoriteHelper.showAddFavoriteDialog(activity) { remark ->
            config.addFavorite(selectedPath, remark)
            listener?.refreshFragment()
        }
    }

    private fun removeSelectedItemFromFavorites() {
        if (!isOneItemSelected()) return
        config.removeFavorite(getFirstSelectedItemPath())
    }

    private fun confirmSelection() {
        if (selectedKeys.isNotEmpty()) {
            val paths = getSelectedFileDirItems()
                .asSequence()
                .filter { !it.isDirectory }.map { it.path }
                .toMutableList() as ArrayList<String>
            if (paths.isEmpty()) {
                finishActMode()
            } else {
                listener?.selectedPaths(paths)
            }
        }
    }

    private fun displayRenameDialog() {
        val fileDirItems = getSelectedFileDirItems()
        val paths = fileDirItems.asSequence().map { it.path }.toMutableList() as ArrayList<String>
        val remotePaths = paths.filter { sessionFileCoordinator.isVirtualPath(activity, it) }
        if (remotePaths.isNotEmpty()) {
            if (remotePaths.size != paths.size) {
                activity.toast("请分开选择本地与服务器项目后再重命名。")
                return
            }
            if (paths.size != 1) {
                activity.toast("服务器项目当前仅支持单个重命名。")
                return
            }
            showRemoteRenameDialog(paths.first())
            return
        }
        when {
            paths.size == 1 -> {
                val oldPath = paths.first()
                RenameItemDialog(activity, oldPath) {
                    config.moveFavorite(oldPath, it)
                    activity.runOnUiThread {
                        listener?.refreshFragment()
                        finishActMode()
                    }
                }
            }

            fileDirItems.any { it.isDirectory } -> RenameItemsDialog(activity, paths) {
                activity.runOnUiThread {
                    listener?.refreshFragment()
                    finishActMode()
                }
            }

            else -> RenameDialog(activity, paths, false) {
                activity.runOnUiThread {
                    listener?.refreshFragment()
                    finishActMode()
                }
            }
        }
    }

    private fun showProperties() {
        if (selectedKeys.size <= 1) {
            PropertiesDialog(activity, getFirstSelectedItemPath(), config.shouldShowHidden())
        } else {
            val paths = getSelectedFileDirItems().map { it.path }
            PropertiesDialog(activity, paths, config.shouldShowHidden())
        }
    }

    private fun shareFiles() {
        shareSelectionWorkflow.share(getSelectedFileDirItems()) {
            finishActMode()
        }
    }

    private fun toggleFileVisibility(hide: Boolean) {
        ensureBackgroundThread {
            getSelectedFileDirItems().forEach {
                activity.toggleItemVisibility(it.path, hide)
            }
            activity.runOnUiThread {
                listener?.refreshFragment()
                finishActMode()
            }
        }
    }

    @SuppressLint("NewApi")
    private fun createShortcut() {
        val manager = activity.getSystemService(ShortcutManager::class.java)
        if (manager.isRequestPinShortcutSupported) {
            val path = getFirstSelectedItemPath()
            val drawable = resources.getDrawable(R.drawable.shortcut_folder).mutate()
            getShortcutImage(path, drawable) {
                val intent = Intent(activity, SplashActivity::class.java)
                intent.action = Intent.ACTION_VIEW
                intent.flags =
                    intent.flags or
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NO_HISTORY
                intent.data = Uri.fromFile(File(path))

                val shortcut = ShortcutInfo.Builder(activity, path)
                    .setShortLabel(path.getFilenameFromPath())
                    .setIcon(Icon.createWithBitmap(drawable.convertToBitmap()))
                    .setIntent(intent)
                    .build()

                manager.requestPinShortcut(shortcut, null)
            }
        }
    }

    private fun getShortcutImage(path: String, drawable: Drawable, callback: () -> Unit) {
        val appIconColor = baseConfig.appIconColor
        (drawable as LayerDrawable).findDrawableByLayerId(R.id.shortcut_folder_background)
            .applyColorFilter(appIconColor)
        if (activity.getIsPathDirectory(path)) {
            callback()
        } else {
            ensureBackgroundThread {
                val options = RequestOptions()
                    .format(DecodeFormat.PREFER_ARGB_8888)
                    .skipMemoryCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .fitCenter()

                val size = activity.resources.getDimension(R.dimen.shortcut_size).toInt()
                val builder = Glide.with(activity)
                    .asDrawable()
                    .load(getImagePathToLoad(path))
                    .apply(options)
                    .centerCrop()
                    .into(size, size)

                try {
                    val bitmap = builder.get()
                    drawable.findDrawableByLayerId(R.id.shortcut_folder_background)
                        .applyColorFilter(0)
                    drawable.setDrawableByLayerId(R.id.shortcut_folder_image, bitmap)
                } catch (e: Exception) {
                    val fileIcon = fileDrawables
                        .getOrElse(
                            key = path.substringAfterLast(".").lowercase(Locale.getDefault()),
                            defaultValue = { fileDrawable }
                        )
                    drawable.setDrawableByLayerId(R.id.shortcut_folder_image, fileIcon)
                }

                activity.runOnUiThread {
                    callback()
                }
            }
        }
    }

    private fun copyPath() {
        val selectedPath = getFirstSelectedItemPath()
        val clipboardPath = if (sessionFileCoordinator.isVirtualPath(activity, selectedPath)) {
            val info = sessionFileCoordinator.describeVirtualPath(activity, selectedPath)
            if (info.success) {
                ClipboardPathFormatter.remoteLinuxPath(info.remotePath)
            } else {
                ClipboardPathFormatter.localPath(selectedPath)
            }
        } else {
            ClipboardPathFormatter.localPath(selectedPath)
        }

        activity.copyToClipboard(clipboardPath)
        finishActMode()
    }

    private fun setAs() {
        activity.setAs(getFirstSelectedItemPath())
    }

    private fun openWith() {
        activity.tryOpenPathIntent(getFirstSelectedItemPath(), true)
    }

    private fun openAs() {
        val res = activity.resources
        val items = arrayListOf(
            RadioItem(OPEN_AS_TEXT, res.getString(R.string.text_file)),
            RadioItem(OPEN_AS_IMAGE, res.getString(R.string.image_file)),
            RadioItem(OPEN_AS_AUDIO, res.getString(R.string.audio_file)),
            RadioItem(OPEN_AS_VIDEO, res.getString(R.string.video_file)),
            RadioItem(OPEN_AS_OTHER, res.getString(R.string.other_file))
        )

        RadioGroupDialog(activity, items) {
            activity.tryOpenPathIntent(getFirstSelectedItemPath(), false, it as Int)
        }
    }

    private fun tryMoveFiles() {
        activity.handleDeletePasswordProtection {
            copyMoveTo(false)
        }
    }

    private fun copyMoveTo(isCopyOperation: Boolean) {
        val files = getSelectedFileDirItems()
        if (files.isEmpty()) {
            transferWorkflowStateMachine.markCancelled("empty-selection")
            return
        }

        val sourceSnapshot = transferWorkflowStateMachine.analyzeSources(
            paths = files.map { it.path },
            isCopyOperation = isCopyOperation,
            isVirtualPath = { path -> sessionFileCoordinator.isVirtualPath(activity, path) }
        )

        if (sourceSnapshot.selectionKind == TransferSelectionKind.MIXED) {
            val message = "请分开选择本地与服务器项目后再传输。"
            transferWorkflowStateMachine.markFailed(message)
            activity.toast(message)
            return
        }

        if (sourceSnapshot.selectionKind == TransferSelectionKind.NONE) {
            transferWorkflowStateMachine.markCancelled("no-transferable-source")
            return
        }

        val firstFile = files[0]
        val source = firstFile.getParentPath()
        val defaultTargetPath = resolveTransferPickerStartPath(source)
        val targetScope = transferWorkflowStateMachine.pickerScopeFor(sourceSnapshot)
        transferWorkflowStateMachine.onPickerOpened(sourceSnapshot)
        FilePickerDialog(
            activity = activity,
            currPath = defaultTargetPath,
            pickFile = false,
            showHidden = config.shouldShowHidden(),
            showFAB = true,
            canAddShowHiddenButton = true,
            showFavoritesButton = true,
            targetScope = targetScope,
            startAtCurrentPath = true
        ) { pickedPath ->
            config.lastCopyPath = pickedPath

            when (
                val executionPlan = transferWorkflowStateMachine.resolvePlan(
                    source = sourceSnapshot,
                    targetPath = pickedPath,
                    isVirtualPath = { path -> sessionFileCoordinator.isVirtualPath(activity, path) }
                )
            ) {
                is TransferExecutionPlan.Unsupported -> {
                    transferWorkflowStateMachine.markFailed(executionPlan.message)
                    activity.toast(executionPlan.message)
                }

                is TransferExecutionPlan.Upload -> {
                    runVirtualUpload(files, executionPlan.destinationVirtualPath)
                }

                is TransferExecutionPlan.Download -> {
                    runVirtualDownload(files, executionPlan.destinationLocalPath)
                }

                is TransferExecutionPlan.RemoteTransfer -> {
                    runVirtualRelay(files, executionPlan.destinationVirtualPath)
                }

                is TransferExecutionPlan.RemoteMove -> {
                    runVirtualMove(files, executionPlan.destinationVirtualPath)
                }

                is TransferExecutionPlan.LocalCopy -> {
                    transferWorkflowStateMachine.reset()
                    if (activity.isPathOnRoot(executionPlan.destinationPath) || activity.isPathOnRoot(firstFile.path)) {
                        copyMoveRootItems(files, executionPlan.destinationPath, !executionPlan.isMoveOperation)
                    } else {
                        activity.copyMoveFilesTo(
                            fileDirItems = files,
                            source = source,
                            destination = executionPlan.destinationPath,
                            isCopyOperation = !executionPlan.isMoveOperation,
                            copyPhotoVideoOnly = false,
                            copyHidden = config.shouldShowHidden()
                        ) {
                            if (executionPlan.isMoveOperation) {
                                files.forEach { sourceFileDir ->
                                    val sourcePath = sourceFileDir.path
                                    if (
                                        activity.isRestrictedSAFOnlyRoot(sourcePath)
                                        && activity.getDoesFilePathExist(sourcePath)
                                    ) {
                                        activity.deleteFile(sourceFileDir, true) {
                                            listener?.refreshFragment()
                                            activity.runOnUiThread {
                                                finishActMode()
                                            }
                                        }
                                    } else {
                                        val sourceFile = File(sourcePath)
                                        if (
                                            activity.getDoesFilePathExist(source)
                                            && activity.getIsPathDirectory(source)
                                            && sourceFile.list()?.isEmpty() == true
                                            && sourceFile.getProperSize(true) == 0L
                                            && sourceFile.getFileCount(true) == 0
                                        ) {
                                            val sourceFolder = sourceFile.toFileDirItem(activity)
                                            activity.deleteFile(sourceFolder, true) {
                                                listener?.refreshFragment()
                                                activity.runOnUiThread {
                                                    finishActMode()
                                                }
                                            }
                                        } else {
                                            listener?.refreshFragment()
                                            finishActMode()
                                        }
                                    }
                                }
                            } else {
                                listener?.refreshFragment()
                                finishActMode()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun resolveTransferPickerStartPath(sourcePath: String): String {
        val sourceDirectory = sourcePath.trimEnd('/')
        if (
            sourceDirectory.isNotEmpty() &&
            (sessionFileCoordinator.isVirtualPath(activity, sourceDirectory) || activity.getDoesFilePathExist(sourceDirectory))
        ) {
            return sourceDirectory
        }

        val lastCopyPath = config.lastCopyPath.trimEnd('/')
        if (lastCopyPath.isNotEmpty() && sessionFileCoordinator.isVirtualPath(activity, lastCopyPath)) {
            return lastCopyPath
        }

        val defaultPath = activity.getDefaultCopyDestinationPath(config.shouldShowHidden(), sourcePath)
        if (
            sessionFileCoordinator.isVirtualPath(activity, defaultPath)
            || activity.getDoesFilePathExist(defaultPath)
        ) {
            return defaultPath
        }

        return FileRootResolver.termuxPrivateRoot(activity)
    }

    private fun runVirtualRelay(remoteItems: List<FileDirItem>, destinationVirtualPath: String) {
        if (activity.isDestroyed || activity.isFinishing) {
            return
        }
        if (!transferWorkflowStateMachine.begin(TransferWorkflowStage.RELAYING, "remote-relay")) {
            activity.toast("\u5df2\u6709\u4f20\u8f93\u4efb\u52a1\u5728\u6267\u884c\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002")
            return
        }

        val progressDialog = activity.getAlertDialogBuilder()
            .setTitle("\u670d\u52a1\u5668\u4e92\u4f20\u4e2d")
            .setMessage("\u6b63\u5728\u51c6\u5907\u4e2d\u8f6c...")
            .setNegativeButton("\u53d6\u6d88", null)
            .setCancelable(false)
            .create()
        val cancelled = AtomicBoolean(false)
        progressDialog.setOnShowListener {
            progressDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                cancelled.set(true)
                it.isEnabled = false
            }
        }
        try {
            progressDialog.show()
        } catch (_: Exception) {
        }

        ensureBackgroundThread {
            try {
                val remotePaths = ArrayList<String>(remoteItems.size)
                remoteItems.forEach { remotePaths.add(it.path) }
                val progressTracker = TransferSpeedTracker()
                val result = sessionFileCoordinator.transferVirtualPaths(
                    activity,
                    remotePaths,
                    destinationVirtualPath,
                    object : SftpProtocolManager.RemoteTransferProgressListener {
                        override fun onProgress(progress: SftpProtocolManager.RemoteTransferProgress) {
                            val message = buildPhaseProgressMessage(
                                phaseLabel = progress.stageLabelCn,
                                currentFile = progress.currentFile,
                                completedFiles = progress.completedFiles,
                                failedFiles = progress.failedFiles,
                                totalFiles = progress.totalFiles,
                                transferredBytes = progress.transferredBytes,
                                totalBytes = progress.totalBytes,
                                tracker = progressTracker,
                                detailMessage = progress.messageCn
                            )
                            if (message.isEmpty()) {
                                return
                            }
                            activity.runOnUiThread {
                                if (!activity.isDestroyed && !activity.isFinishing && progressDialog.isShowing) {
                                    progressDialog.setMessage(message)
                                }
                            }
                        }
                    },
                    object : SftpProtocolManager.RemoteTransferControl {
                        override fun isCancelled(): Boolean = cancelled.get()
                    }
                )

                activity.runOnUiThread {
                    dismissTransferDialog(progressDialog)

                    when {
                        result.success -> {
                            transferWorkflowStateMachine.markCompleted("relay:${result.transferredFiles}/${result.totalFiles}")
                            activity.toast(
                                "\u670d\u52a1\u5668\u4e92\u4f20\u5b8c\u6210\uff1a${result.transferredFiles}/${result.totalFiles}\uff0c${
                                    result.transferredBytes.formatSize()
                                }"
                            )
                        }

                        isCancelledTransferMessage(result.messageCn) || cancelled.get() -> {
                            transferWorkflowStateMachine.markCancelled(result.messageCn.ifBlank { "relay-cancelled" })
                            activity.toast(result.messageCn.ifBlank { "\u670d\u52a1\u5668\u4e92\u4f20\u5df2\u53d6\u6d88" })
                        }

                        result.transferredFiles > 0 -> {
                            transferWorkflowStateMachine.markFailed("relay-partial:${result.transferredFiles}/${result.totalFiles}")
                            val reason = if (result.messageCn.isNotEmpty()) " ${result.messageCn}" else ""
                            activity.toast(
                                "\u670d\u52a1\u5668\u4e92\u4f20\u90e8\u5206\u5b8c\u6210\uff1a${result.transferredFiles}/${result.totalFiles}\uff0c${
                                    result.transferredBytes.formatSize()
                                }$reason"
                            )
                        }

                        else -> {
                            val failure = if (result.messageCn.isNotEmpty()) {
                                result.messageCn
                            } else {
                                "\u670d\u52a1\u5668\u4e92\u4f20\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u4e24\u7aef\u670d\u52a1\u5668\u8fde\u63a5\u4e0e\u8ba4\u8bc1\u72b6\u6001\u3002"
                            }
                            transferWorkflowStateMachine.markFailed(failure)
                            activity.toast(failure)
                        }
                    }

                    if (result.transferredFiles > 0 && result.transferredVirtualPaths.isNotEmpty()) {
                        listener?.openPathAndHighlight(destinationVirtualPath, ArrayList(result.transferredVirtualPaths))
                    } else {
                        listener?.refreshFragment()
                    }
                    finishActMode()
                }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    dismissTransferDialog(progressDialog)
                    val msg = t.message?.trim().orEmpty()
                    if (msg.isNotEmpty()) {
                        transferWorkflowStateMachine.markFailed(msg)
                        activity.toast("\u670d\u52a1\u5668\u4e92\u4f20\u5f02\u5e38\uff1a$msg")
                    } else {
                        transferWorkflowStateMachine.markFailed("relay-exception")
                        activity.toast("\u670d\u52a1\u5668\u4e92\u4f20\u5f02\u5e38\uff0c\u8bf7\u91cd\u8bd5\u3002")
                    }
                }
            }
        }
    }

    private fun dismissTransferDialog(progressDialog: androidx.appcompat.app.AlertDialog) {
        if (!activity.isDestroyed && !activity.isFinishing && progressDialog.isShowing) {
            try {
                progressDialog.dismiss()
            } catch (_: Exception) {
            }
        }
    }

    private fun buildPhaseProgressMessage(
        phaseLabel: String,
        currentFile: String,
        completedFiles: Int,
        failedFiles: Int,
        totalFiles: Int,
        transferredBytes: Long,
        totalBytes: Long,
        tracker: TransferSpeedTracker,
        detailMessage: String = ""
    ): String {
        val speedBytesPerSecond = tracker.updateAndGetSpeed(transferredBytes)
        if (!tracker.shouldRefresh(totalBytes, transferredBytes)) {
            return ""
        }

        val finishedCount = completedFiles + failedFiles
        val percent = if (totalBytes > 0L) {
            ((transferredBytes * 100L) / totalBytes).coerceIn(0L, 100L)
        } else {
            0L
        }
        val sizeText = if (totalBytes > 0L) {
            "${transferredBytes.formatSize()} / ${totalBytes.formatSize()}"
        } else {
            "${transferredBytes.formatSize()} / ?"
        }
        val speedText = if (speedBytesPerSecond > 0L) {
            "${speedBytesPerSecond.formatSize()}/s"
        } else {
            "--"
        }
        val fileLabel = if (currentFile.isNotEmpty()) currentFile else "\u51c6\u5907\u4e2d..."
        return StringBuilder()
            .append(phaseLabel).append('\n')
            .append("\u5f53\u524d\uff1a").append(fileLabel).append('\n')
            .append("\u8fdb\u5ea6\uff1a").append(finishedCount).append('/').append(totalFiles)
            .append(" (").append(percent).append("%)").append('\n')
            .append("\u5927\u5c0f\uff1a").append(sizeText).append('\n')
            .append("\u901f\u5ea6\uff1a").append(speedText)
            .apply {
                if (detailMessage.isNotBlank()) {
                    append('\n').append("\u72b6\u6001\uff1a").append(detailMessage)
                }
            }
            .toString()
    }

    private class TransferSpeedTracker {
        private var lastUiUpdateAt = 0L
        private var lastSpeedAt = 0L
        private var lastSpeedBytes = 0L
        private var lastComputedSpeed = 0L

        fun updateAndGetSpeed(transferredBytes: Long): Long {
            val now = SystemClock.elapsedRealtime()
            if (lastSpeedAt == 0L) {
                lastSpeedAt = now
                lastSpeedBytes = transferredBytes
                lastComputedSpeed = 0L
                return lastComputedSpeed
            }

            val deltaMs = now - lastSpeedAt
            if (deltaMs >= 260L || transferredBytes < lastSpeedBytes) {
                val deltaBytes = transferredBytes - lastSpeedBytes
                lastComputedSpeed = if (deltaMs > 0L && deltaBytes > 0L) {
                    deltaBytes * 1000L / deltaMs
                } else {
                    0L
                }
                lastSpeedAt = now
                lastSpeedBytes = transferredBytes
                return lastComputedSpeed
            }
            return lastComputedSpeed
        }

        fun shouldRefresh(totalBytes: Long, transferredBytes: Long): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (now - lastUiUpdateAt < 100L && transferredBytes < totalBytes) {
                return false
            }
            lastUiUpdateAt = now
            return true
        }
    }

    private fun runVirtualDownload(virtualItems: List<FileDirItem>, destination: String) {
        if (activity.isDestroyed || activity.isFinishing) {
            return
        }
        if (!transferWorkflowStateMachine.begin(TransferWorkflowStage.DOWNLOADING, "download")) {
            activity.toast("已有传输任务在执行，请稍后再试。")
            return
        }
        if (!virtualDownloadInProgress.compareAndSet(false, true)) {
            transferWorkflowStateMachine.markFailed("download-busy")
            activity.toast("已有下载任务在执行，请稍后再试。")
            return
        }

        RemoteDownloadCoordinator.start(
            activity = simpleActivity,
            sessionFileCoordinator = sessionFileCoordinator,
            sourceItems = virtualItems,
            destinationPath = destination,
            mode = ActiveTransferMode.NORMAL_DOWNLOAD,
            title = "下载中",
            fallbackFileName = "准备中...",
            onOutcome = { outcome ->
                when (outcome.status) {
                    ActiveTransferStatus.SUCCESS -> {
                        transferWorkflowStateMachine.markCompleted("download:${outcome.downloadedFiles}/${outcome.totalFiles}")
                        activity.toast("下载完成：${outcome.downloadedFiles}/${outcome.totalFiles}，${outcome.downloadedBytes.formatSize()}")
                    }

                    ActiveTransferStatus.CANCELLED -> {
                        val message = outcome.messageCn.ifBlank { "下载已取消" }
                        transferWorkflowStateMachine.markCancelled(message)
                        if (outcome.downloadedFiles > 0) {
                            activity.toast("下载已取消：${outcome.downloadedFiles}/${outcome.totalFiles}，${outcome.downloadedBytes.formatSize()}")
                        } else {
                            activity.toast(message)
                        }
                    }

                    ActiveTransferStatus.PARTIAL -> {
                        transferWorkflowStateMachine.markFailed("download-partial:${outcome.downloadedFiles}/${outcome.totalFiles}")
                        val reason = if (outcome.messageCn.isNotEmpty()) " ${outcome.messageCn}" else ""
                        activity.toast("部分完成：${outcome.downloadedFiles}/${outcome.totalFiles}，${outcome.downloadedBytes.formatSize()}$reason")
                    }

                    else -> {
                        val failure = if (outcome.throwable != null && outcome.messageCn.isNotBlank()) {
                            "下载异常：${outcome.messageCn}"
                        } else {
                            outcome.messageCn.ifBlank { "下载失败，请检查网络或认证信息。" }
                        }
                        transferWorkflowStateMachine.markFailed(failure)
                        activity.toast(failure)
                    }
                }

                if (outcome.downloadedFiles > 0 && outcome.downloadedLocalPaths.isNotEmpty()) {
                    listener?.openPathAndHighlight(destination, ArrayList(outcome.downloadedLocalPaths))
                } else {
                    listener?.refreshFragment()
                }
            },
            onFinish = {
                finishActMode()
                virtualDownloadInProgress.set(false)
            }
        )
    }

    private fun runVirtualUpload(localItems: List<FileDirItem>, destinationVirtualPath: String) {
        if (activity.isDestroyed || activity.isFinishing) {
            return
        }
        if (!transferWorkflowStateMachine.begin(TransferWorkflowStage.UPLOADING, "upload")) {
            activity.toast("\u5df2\u6709\u4f20\u8f93\u4efb\u52a1\u5728\u6267\u884c\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002")
            return
        }
        if (!virtualUploadInProgress.compareAndSet(false, true)) {
            transferWorkflowStateMachine.markFailed("upload-busy")
            activity.toast("\u5df2\u6709\u4e0a\u4f20\u4efb\u52a1\u5728\u6267\u884c\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002")
            return
        }

        val progressDialog = activity.getAlertDialogBuilder()
            .setTitle("\u4e0a\u4f20\u4e2d")
            .setMessage("\u6b63\u5728\u51c6\u5907\u4e0a\u4f20...")
            .setNegativeButton("\u53d6\u6d88", null)
            .setCancelable(false)
            .create()
        val cancelled = AtomicBoolean(false)
        progressDialog.setOnShowListener {
            progressDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
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
                val localPaths = ArrayList<String>(localItems.size)
                localItems.forEach { localPaths.add(it.path) }
                val result = sessionFileCoordinator.uploadLocalPathsToVirtual(
                    activity,
                    localPaths,
                    destinationVirtualPath,
                    object : SftpProtocolManager.UploadProgressListener {
                        override fun onProgress(progress: SftpProtocolManager.UploadProgress) {
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
                            val currentFile = if (progress.currentFile.isNotEmpty()) {
                                progress.currentFile
                            } else {
                                "\u51c6\u5907\u4e2d..."
                            }
                            val message = StringBuilder()
                                .append("\u5f53\u524d\uff1a").append(currentFile).append('\n')
                                .append("\u8fdb\u5ea6\uff1a").append(finishedCount).append('/').append(progress.totalFiles)
                                .append(" (").append(percent).append("%)").append('\n')
                                .append("\u5927\u5c0f\uff1a").append(sizeText).append('\n')
                                .append("\u901f\u5ea6\uff1a").append(speedText)
                                .toString()

                            activity.runOnUiThread {
                                if (!activity.isDestroyed && !activity.isFinishing && progressDialog.isShowing) {
                                    progressDialog.setMessage(message)
                                }
                            }
                        }
                    },
                    object : SftpProtocolManager.UploadControl {
                        override fun isCancelled(): Boolean = cancelled.get()
                    }
                )

                activity.runOnUiThread {
                    if (!activity.isDestroyed && !activity.isFinishing && progressDialog.isShowing) {
                        try {
                            progressDialog.dismiss()
                        } catch (_: Exception) {
                        }
                    }

                    when {
                        result.success -> {
                            transferWorkflowStateMachine.markCompleted("upload:${result.uploadedFiles}/${result.totalFiles}")
                            activity.toast(
                                "\u4e0a\u4f20\u5b8c\u6210\uff1a${result.uploadedFiles}/${result.totalFiles}\uff0c${
                                    result.uploadedBytes.formatSize()
                                }"
                            )
                        }

                        isCancelledTransferMessage(result.messageCn) -> {
                            transferWorkflowStateMachine.markCancelled(result.messageCn)
                            if (result.uploadedFiles > 0) {
                                activity.toast(
                                    "\u4e0a\u4f20\u5df2\u53d6\u6d88\uff1a${result.uploadedFiles}/${result.totalFiles}\uff0c${
                                        result.uploadedBytes.formatSize()
                                    }"
                                )
                            } else {
                                activity.toast(result.messageCn)
                            }
                        }

                        result.uploadedFiles > 0 -> {
                            transferWorkflowStateMachine.markFailed("upload-partial:${result.uploadedFiles}/${result.totalFiles}")
                            val reason = if (result.messageCn.isNotEmpty()) " ${result.messageCn}" else ""
                            activity.toast(
                                "\u90e8\u5206\u5b8c\u6210\uff1a${result.uploadedFiles}/${result.totalFiles}\uff0c${
                                    result.uploadedBytes.formatSize()
                                }$reason"
                            )
                        }

                        else -> {
                            val failure = if (result.messageCn.isNotEmpty()) {
                                result.messageCn
                            } else {
                                "\u4e0a\u4f20\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u6216\u8ba4\u8bc1\u4fe1\u606f\u3002"
                            }
                            transferWorkflowStateMachine.markFailed(failure)
                            activity.toast(failure)
                        }
                    }

                    if (result.uploadedFiles > 0 && result.uploadedVirtualPaths.isNotEmpty()) {
                        listener?.openPathAndHighlight(destinationVirtualPath, ArrayList(result.uploadedVirtualPaths))
                    } else {
                        listener?.refreshFragment()
                    }
                    finishActMode()
                }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    if (!activity.isDestroyed && !activity.isFinishing && progressDialog.isShowing) {
                        try {
                            progressDialog.dismiss()
                        } catch (_: Exception) {
                        }
                    }
                    val msg = t.message?.trim().orEmpty()
                    if (msg.isNotEmpty()) {
                        transferWorkflowStateMachine.markFailed(msg)
                        activity.toast("\u4e0a\u4f20\u5f02\u5e38\uff1a$msg")
                    } else {
                        transferWorkflowStateMachine.markFailed("upload-exception")
                        activity.toast("\u4e0a\u4f20\u5f02\u5e38\uff0c\u8bf7\u91cd\u8bd5\u3002")
                    }
                }
            } finally {
                virtualUploadInProgress.set(false)
            }
        }
    }

    private fun isCancelledTransferMessage(message: String): Boolean {
        return message.contains("\u5df2\u53d6\u6d88")
    }

    private fun copyMoveRootItems(
        files: ArrayList<FileDirItem>,
        destinationPath: String,
        isCopyOperation: Boolean
    ) {
        activity.toast(R.string.copying)
        ensureBackgroundThread {
            val fileCnt = files.size
            RootHelpers(activity).copyMoveFiles(files, destinationPath, isCopyOperation) {
                when (it) {
                    fileCnt -> activity.toast(R.string.copying_success)
                    0 -> activity.toast(R.string.copy_failed)
                    else -> activity.toast(R.string.copying_success_partial)
                }

                activity.runOnUiThread {
                    listener?.refreshFragment()
                    finishActMode()
                }
            }
        }
    }

    private fun compressSelection() {
        val selectedItems = getSelectedFileDirItems()
        if (selectedItems.isEmpty()) return

        showArchiveDestinationPicker(selectedItems, archiveModeCompress = true)
    }

    private fun decompressSelection() {
        val selectedItems = getSelectedFileDirItems()
            .filter { it.path.isArchiveFile() }
            .toCollection(ArrayList())
        if (selectedItems.isEmpty()) {
            activity.toast(R.string.decompressing_failed)
            return
        }

        showArchiveDestinationPicker(selectedItems, archiveModeCompress = false)
    }

    private fun showArchiveDestinationPicker(
        selectedItems: ArrayList<FileDirItem>,
        archiveModeCompress: Boolean
    ) {
        val firstPath = selectedItems.firstOrNull()?.path ?: return
        val startPath = resolveArchivePickerStartPath(firstPath)
        FilePickerDialog(
            activity = activity,
            currPath = startPath,
            pickFile = false,
            showHidden = config.shouldShowHidden(),
            showFAB = true,
            canAddShowHiddenButton = true,
            showFavoritesButton = true,
            targetScope = FilePickerDialog.TargetScope.ANY,
            startAtCurrentPath = true
        ) { destinationDirectory ->
            if (archiveModeCompress) {
                showArchiveCompressionOptionsDialog(selectedItems, destinationDirectory)
            } else {
                showArchiveDecompressionOptionsDialog(selectedItems, destinationDirectory)
            }
        }
    }

    private fun resolveArchivePickerStartPath(firstPath: String): String {
        val parent = firstPath.getParentPath().trimEnd('/')
        if (sessionFileCoordinator.isVirtualPath(activity, parent) || activity.getDoesFilePathExist(parent)) {
            return parent
        }

        val lastCopyPath = config.lastCopyPath.trimEnd('/')
        if (
            lastCopyPath.isNotEmpty() &&
            (sessionFileCoordinator.isVirtualPath(activity, lastCopyPath) || activity.getDoesFilePathExist(lastCopyPath))
        ) {
            return lastCopyPath
        }

        return FileRootResolver.termuxPrivateRoot(activity)
    }

    private fun showArchiveCompressionOptionsDialog(
        selectedItems: ArrayList<FileDirItem>,
        destinationDirectory: String
    ) {
        val hasRemoteSource = selectedItems.any { sessionFileCoordinator.isVirtualPath(activity, it.path) }
        if (!hasRemoteSource) {
            showArchiveCompressionOptionsDialogWithChoices(
                selectedItems = selectedItems,
                destinationDirectory = destinationDirectory,
                formatOptions = defaultLocalArchiveFormatChoices(),
                remoteSource = false,
                detectedTools = emptyList()
            )
            return
        }

        val cancelled = AtomicBoolean(false)
        val probeDialog = activity.getAlertDialogBuilder()
            .setTitle("检测服务器压缩工具")
            .setMessage("正在检测服务器压缩/解压工具...")
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .create()
        probeDialog.setOnShowListener {
            probeDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                cancelled.set(true)
                it.isEnabled = false
                probeDialog.setMessage("正在取消...")
            }
        }
        try {
            probeDialog.show()
        } catch (_: Exception) {
        }

        ensureBackgroundThread {
            val workflow = ArchiveTransferWorkflow(activity, sessionFileCoordinator)
            val result = workflow.resolveCompressionFormatChoices(selectedItems, cancelled) { message ->
                activity.runOnUiThread {
                    if (!activity.isDestroyed && !activity.isFinishing && probeDialog.isShowing) {
                        probeDialog.setMessage(message)
                    }
                }
            }

            activity.runOnUiThread {
                dismissTransferDialog(probeDialog)
                when {
                    cancelled.get() -> activity.toast("操作已取消。")
                    result.success -> showArchiveCompressionOptionsDialogWithChoices(
                        selectedItems = selectedItems,
                        destinationDirectory = destinationDirectory,
                        formatOptions = result.choices,
                        remoteSource = result.remoteSource,
                        detectedTools = result.detectedTools
                    )
                    else -> showArchiveFailureDialog(
                        ArchiveTransferWorkflow.ArchiveResult(
                            success = false,
                            message = result.message.ifBlank { "检测服务器压缩工具失败。" },
                            dialogTitle = "服务器压缩工具不可用"
                        )
                    )
                }
            }
        }
    }

    private fun showArchiveCompressionOptionsDialogWithChoices(
        selectedItems: ArrayList<FileDirItem>,
        destinationDirectory: String,
        formatOptions: List<ArchiveTransferWorkflow.CompressionFormatChoice>,
        remoteSource: Boolean,
        detectedTools: List<String>
    ) {
        if (formatOptions.isEmpty()) {
            showArchiveFailureDialog(
                ArchiveTransferWorkflow.ArchiveResult(
                    success = false,
                    message = "没有可用的压缩格式。",
                    dialogTitle = "服务器压缩工具不可用"
                )
            )
            return
        }

        val container = createArchiveOptionsContainer()
        val nameInput = createArchiveEditText(defaultCompressionName(selectedItems), "压缩包名称")
        val levelOptions = listOf(
            "标准（-mx=5）" to ArchiveTransferWorkflow.CompressionLevel.NORMAL,
            "最快（-mx=1）" to ArchiveTransferWorkflow.CompressionLevel.FAST,
            "最大（-mx=9）" to ArchiveTransferWorkflow.CompressionLevel.MAXIMUM,
            "仅打包（-mx=0）" to ArchiveTransferWorkflow.CompressionLevel.STORE
        )
        val formatSpinner = createArchiveSpinner(formatOptions.map { it.label })
        val levelSpinner = createArchiveSpinner(levelOptions.map { it.first })
        val passwordCheck = CheckBox(activity).apply {
            text = "使用密码"
        }
        val passwordInput = createArchiveEditText("", "密码").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            visibility = View.GONE
        }
        val encryptNamesCheck = CheckBox(activity).apply {
            text = "加密文件名（仅 7z）"
            isChecked = true
            visibility = View.GONE
        }
        fun selectedFormat(): ArchiveTransferWorkflow.CompressionFormat {
            return formatOptions[formatSpinner.selectedItemPosition.coerceAtLeast(0)].format
        }
        fun syncPasswordControls() {
            val format = selectedFormat()
            val canUsePassword = !remoteSource && archiveFormatSupportsPassword(format)
            passwordCheck.beVisibleIf(canUsePassword)
            if (!canUsePassword) {
                passwordCheck.isChecked = false
                passwordInput.text?.clear()
            }
            passwordInput.beVisibleIf(canUsePassword && passwordCheck.isChecked)
            encryptNamesCheck.beVisibleIf(
                canUsePassword &&
                    passwordCheck.isChecked &&
                    format == ArchiveTransferWorkflow.CompressionFormat.SEVEN_Z
            )
        }
        passwordCheck.setOnCheckedChangeListener { _, _ ->
            syncPasswordControls()
        }
        formatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                syncPasswordControls()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                syncPasswordControls()
            }
        }

        addArchiveOption(container, "名称", nameInput)
        addArchiveOption(container, "格式", formatSpinner)
        addArchiveOption(container, "压缩等级", levelSpinner)
        if (remoteSource && detectedTools.isNotEmpty()) {
            container.addView(TextView(activity).apply {
                text = "服务器工具：${detectedTools.joinToString("、")}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }, archiveOptionLayoutParams())
        }
        container.addView(passwordCheck, archiveOptionLayoutParams())
        container.addView(passwordInput, archiveOptionLayoutParams())
        container.addView(encryptNamesCheck, archiveOptionLayoutParams())
        syncPasswordControls()

        val dialog = activity.getAlertDialogBuilder()
            .setTitle("压缩选项")
            .setView(container)
            .setPositiveButton("开始压缩", null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val archiveName = nameInput.text?.toString()?.trim().orEmpty()
                val password = if (passwordCheck.isChecked) passwordInput.text?.toString().orEmpty() else ""
                val format = formatOptions[formatSpinner.selectedItemPosition].format
                if (!validateArchiveName(archiveName)) {
                    return@setOnClickListener
                }
                if (passwordCheck.isChecked && password.isEmpty()) {
                    activity.toast(R.string.empty_password_new)
                    return@setOnClickListener
                }
                if (!archiveFormatSupportsPassword(format) && password.isNotBlank()) {
                    activity.toast("${format.suffix} 格式不支持密码")
                    return@setOnClickListener
                }

                dialog.dismiss()
                runArchiveCompressWorkflow(
                    selectedItems = selectedItems,
                    destinationDirectory = destinationDirectory,
                    options = ArchiveTransferWorkflow.CompressionOptions(
                        archiveName = archiveName,
                        format = format,
                        level = levelOptions[levelSpinner.selectedItemPosition].second,
                        password = password,
                        encryptFileNames = encryptNamesCheck.isChecked
                    )
                )
            }
        }
        dialog.show()
    }

    private fun defaultLocalArchiveFormatChoices(): List<ArchiveTransferWorkflow.CompressionFormatChoice> {
        return listOf(
            ArchiveTransferWorkflow.CompressionFormatChoice(
                format = ArchiveTransferWorkflow.CompressionFormat.SEVEN_Z,
                label = "7z（推荐）"
            ),
            ArchiveTransferWorkflow.CompressionFormatChoice(
                format = ArchiveTransferWorkflow.CompressionFormat.ZIP,
                label = "ZIP"
            ),
            ArchiveTransferWorkflow.CompressionFormatChoice(
                format = ArchiveTransferWorkflow.CompressionFormat.TAR,
                label = "TAR"
            )
        )
    }

    private fun archiveFormatSupportsPassword(format: ArchiveTransferWorkflow.CompressionFormat): Boolean {
        return format == ArchiveTransferWorkflow.CompressionFormat.SEVEN_Z ||
            format == ArchiveTransferWorkflow.CompressionFormat.ZIP
    }

    private fun showArchiveDecompressionOptionsDialog(
        selectedItems: ArrayList<FileDirItem>,
        destinationDirectory: String
    ) {
        val container = createArchiveOptionsContainer()
        val nameInput = createArchiveEditText(defaultDecompressionName(selectedItems), "输出文件夹名称")
        val conflictOptions = listOf(
            "自动重命名（-aou）" to ArchiveTransferWorkflow.DecompressConflictStrategy.AUTO_RENAME,
            "覆盖已有（-aoa）" to ArchiveTransferWorkflow.DecompressConflictStrategy.OVERWRITE,
            "跳过已有（-aos）" to ArchiveTransferWorkflow.DecompressConflictStrategy.SKIP_EXISTING
        )
        val conflictSpinner = createArchiveSpinner(conflictOptions.map { it.first })

        addArchiveOption(container, "输出文件夹", nameInput)
        addArchiveOption(container, "冲突策略", conflictSpinner)

        val dialog = activity.getAlertDialogBuilder()
            .setTitle("解压选项")
            .setView(container)
            .setPositiveButton("开始解压", null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val outputName = nameInput.text?.toString()?.trim().orEmpty()
                if (!validateArchiveName(outputName)) {
                    return@setOnClickListener
                }

                dialog.dismiss()
                runArchiveDecompressWorkflow(
                    selectedItems = selectedItems,
                    destinationDirectory = destinationDirectory,
                    options = ArchiveTransferWorkflow.DecompressOptions(
                        outputFolderName = outputName,
                        conflictStrategy = conflictOptions[conflictSpinner.selectedItemPosition].second
                    )
                )
            }
        }
        dialog.show()
    }

    private fun createArchiveOptionsContainer(): LinearLayout {
        val margin = resources.getDimensionPixelSize(R.dimen.activity_margin)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, margin / 2, margin, 0)
        }
    }

    private fun addArchiveOption(container: LinearLayout, label: String, view: View) {
        container.addView(TextView(activity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }, archiveOptionLayoutParams())
        container.addView(view, archiveOptionLayoutParams())
    }

    private fun createArchiveEditText(value: String, hintText: String): EditText {
        return EditText(activity).apply {
            setText(value)
            hint = hintText
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            selectAll()
        }
    }

    private fun createArchiveSpinner(labels: List<String>): Spinner {
        return Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
    }

    private fun archiveOptionLayoutParams(): LinearLayout.LayoutParams {
        val topMargin = resources.getDimensionPixelSize(R.dimen.small_margin)
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.topMargin = topMargin
        }
    }

    private fun validateArchiveName(name: String): Boolean {
        return when {
            name.isBlank() -> {
                activity.toast(R.string.empty_name)
                false
            }
            !name.isAValidFilename() -> {
                activity.toast(R.string.invalid_name)
                false
            }
            else -> true
        }
    }

    private fun defaultCompressionName(selectedItems: List<FileDirItem>): String {
        val base = if (selectedItems.size == 1) {
            val item = selectedItems.first()
            val name = item.name.ifBlank { item.path.getFilenameFromPath() }.ifBlank { "archive" }
            if (!item.isDirectory && name.contains('.')) {
                name.substringBeforeLast('.').ifBlank { name }
            } else {
                name
            }
        } else {
            selectedItems.firstOrNull()?.path?.getParentPath()?.getFilenameFromPath().orEmpty().ifBlank { "archive" }
        }
        return sanitizeArchiveDialogName(base).ifBlank { "archive" }
    }

    private fun defaultDecompressionName(selectedItems: List<FileDirItem>): String {
        val firstName = selectedItems.firstOrNull()?.name
            ?: selectedItems.firstOrNull()?.path?.getFilenameFromPath()
            ?: "extracted"
        return sanitizeArchiveDialogName(stripArchiveSuffix(firstName)).ifBlank { "extracted" }
    }

    private fun stripArchiveSuffix(name: String): String {
        val suffix = ARCHIVE_DIALOG_SUFFIXES
            .filter { name.endsWith(it, ignoreCase = true) }
            .maxByOrNull { it.length }
            .orEmpty()
        return if (suffix.isBlank()) name else name.dropLast(suffix.length)
    }

    private fun sanitizeArchiveDialogName(raw: String): String {
        return raw.replace('\\', '/')
            .substringAfterLast('/')
            .trim()
            .trimEnd('.')
    }

    private fun runArchiveCompressWorkflow(
        selectedItems: ArrayList<FileDirItem>,
        destinationDirectory: String,
        options: ArchiveTransferWorkflow.CompressionOptions
    ) {
        runArchiveWorkflow(
            title = "压缩中",
            initialMessage = "正在准备压缩...",
            destinationDirectory = destinationDirectory
        ) { workflow, cancelled, progress ->
            workflow.compress(selectedItems, destinationDirectory, cancelled, progress, options)
        }
    }

    private fun runArchiveDecompressWorkflow(
        selectedItems: ArrayList<FileDirItem>,
        destinationDirectory: String,
        options: ArchiveTransferWorkflow.DecompressOptions
    ) {
        runArchiveWorkflow(
            title = "解压中",
            initialMessage = "正在准备解压...",
            destinationDirectory = destinationDirectory
        ) { workflow, cancelled, progress ->
            workflow.decompress(selectedItems, destinationDirectory, cancelled, progress, options)
        }
    }

    private fun runArchiveWorkflow(
        title: String,
        initialMessage: String,
        destinationDirectory: String,
        operation: (
            workflow: ArchiveTransferWorkflow,
            cancelled: AtomicBoolean,
            progress: (String) -> Unit
        ) -> ArchiveTransferWorkflow.ArchiveResult
    ) {
        if (activity.isDestroyed || activity.isFinishing) {
            return
        }

        config.lastCopyPath = destinationDirectory
        val progressDialog = activity.getAlertDialogBuilder()
            .setTitle(title)
            .setMessage(initialMessage)
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .create()
        val cancelled = AtomicBoolean(false)
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

        ensureBackgroundThread {
            val workflow = ArchiveTransferWorkflow(activity, sessionFileCoordinator)
            val result = operation(workflow, cancelled) { message ->
                activity.runOnUiThread {
                    if (!activity.isDestroyed && !activity.isFinishing && progressDialog.isShowing) {
                        progressDialog.setMessage(message)
                    }
                }
            }

            activity.runOnUiThread {
                dismissTransferDialog(progressDialog)
                when {
                    result.success -> {
                        activity.toast(result.message.ifBlank { "归档操作完成。" })
                        if (result.targetPath.isNotBlank() && result.highlightPaths.isNotEmpty()) {
                            listener?.openPathAndHighlight(result.targetPath, result.highlightPaths)
                        } else {
                            listener?.refreshFragment()
                        }
                    }

                    result.cancelled -> {
                        activity.toast(result.message.ifBlank { "操作已取消。" })
                        listener?.refreshFragment()
                    }

                    result.installCommand.isNotBlank() -> {
                        showArchiveToolInstallDialog(result)
                        listener?.refreshFragment()
                    }

                    result.dialogTitle.isNotBlank() -> {
                        showArchiveFailureDialog(result)
                        listener?.refreshFragment()
                    }

                    else -> {
                        activity.toast(result.message.ifBlank { "归档操作失败，请重试。" })
                        listener?.refreshFragment()
                    }
                }
                finishActMode()
            }
        }
    }

    private fun showArchiveToolInstallDialog(result: ArchiveTransferWorkflow.ArchiveResult) {
        val command = result.installCommand
        val message = result.message.ifBlank { "本地缺少 7-Zip 工具链。请在 Termux 终端执行安装命令后重试。" } +
            "\n\n安装命令：\n$command"
        activity.getAlertDialogBuilder()
            .setTitle("需要安装 7-Zip")
            .setMessage(message)
            .setPositiveButton("复制命令") { _, _ ->
                activity.copyToClipboard(command)
                activity.toast("安装命令已复制")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showArchiveFailureDialog(result: ArchiveTransferWorkflow.ArchiveResult) {
        activity.getAlertDialogBuilder()
            .setTitle(result.dialogTitle.ifBlank { "归档操作失败" })
            .setMessage(result.message.ifBlank { "归档操作失败，请重试。" })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun askConfirmDelete() {
        activity.handleDeletePasswordProtection {
            val itemsCnt = selectedKeys.size
            val items = if (itemsCnt == 1) {
                "\"${getFirstSelectedItemPath().getFilenameFromPath()}\""
            } else {
                resources.getQuantityString(R.plurals.delete_items, itemsCnt, itemsCnt)
            }

            val question = String.format(resources.getString(R.string.deletion_confirmation), items)
            ConfirmationDialog(activity, question) {
                deleteFiles()
            }
        }
    }

    private fun deleteFiles() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val SAFPath = getFirstSelectedItemPath()
        if (activity.isPathOnRoot(SAFPath) && !RootTools.isRootAvailable()) {
            activity.toast(R.string.rooted_device_only)
            return
        }

        activity.handleSAFDialog(SAFPath) { granted ->
            if (!granted) {
                return@handleSAFDialog
            }

            val files = ArrayList<FileDirItem>(selectedKeys.size)

            ensureBackgroundThread {
                selectedKeys.forEach { key ->
                    val position = listItems.indexOfFirst { it.path.hashCode() == key }
                    if (position != -1) {
                        files.add(listItems[position])
                    }
                }

                activity.runOnUiThread {
                    listener?.deleteFiles(files)
                    finishActMode()
                }
            }
        }
    }

    private fun showRemoteRenameDialog(virtualPath: String) {
        val currentName = virtualPath.getFilenameFromPath()
        val input = EditText(activity).apply {
            setText(currentName)
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            val dotAt = if (activity.getIsPathDirectory(virtualPath)) -1 else currentName.lastIndexOf('.')
            val selectionEnd = if (dotAt > 0) dotAt else currentName.length
            post { setSelection(0, selectionEnd.coerceAtLeast(0)) }
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = activity.getAlertDialogBuilder()
            .setTitle(R.string.rename)
            .setView(container)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    activity.toast(R.string.empty_name)
                    return@setOnClickListener
                }
                if (!newName.isAValidFilename()) {
                    activity.toast(R.string.invalid_name)
                    return@setOnClickListener
                }
                ensureBackgroundThread {
                    val result = sessionFileCoordinator.renameVirtualPath(
                        activity.applicationContext,
                        virtualPath,
                        newName
                    )
                    activity.runOnUiThread {
                        if (result.success) {
                            config.moveFavorite(virtualPath, result.virtualPath)
                            listener?.refreshFragment()
                            finishActMode()
                            dialog.dismiss()
                        } else {
                            activity.toast(result.messageCn)
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun runVirtualMove(remoteItems: List<FileDirItem>, destinationVirtualPath: String) {
        if (activity.isDestroyed || activity.isFinishing) {
            return
        }
        if (!transferWorkflowStateMachine.begin(TransferWorkflowStage.RELAYING, "remote-move")) {
            activity.toast("已有传输任务在执行，请稍后再试。")
            return
        }

        val progressDialog = activity.getAlertDialogBuilder()
            .setTitle("服务器移动中")
            .setMessage("正在准备移动...")
            .setCancelable(false)
            .create()
        try {
            progressDialog.show()
        } catch (_: Exception) {
        }

        ensureBackgroundThread {
            try {
                val remotePaths = ArrayList<String>(remoteItems.size)
                remoteItems.forEach { remotePaths.add(it.path) }
                activity.runOnUiThread {
                    if (!activity.isDestroyed && !activity.isFinishing && progressDialog.isShowing) {
                        progressDialog.setMessage("正在移动 ${remotePaths.size} 项...")
                    }
                }
                val result = sessionFileCoordinator.moveVirtualPaths(
                    activity.applicationContext,
                    remotePaths,
                    destinationVirtualPath
                )
                activity.runOnUiThread {
                    dismissTransferDialog(progressDialog)
                    if (result.success) {
                        transferWorkflowStateMachine.markCompleted("remote-move:${result.movedVirtualPaths.size}")
                        remoteItems.forEachIndexed { index, file ->
                            val newPath = result.movedVirtualPaths.getOrNull(index) ?: return@forEachIndexed
                            config.moveFavorite(file.path, newPath)
                        }
                        activity.toast("服务器移动完成：${result.movedVirtualPaths.size} 项")
                        if (result.movedVirtualPaths.isNotEmpty()) {
                            listener?.openPathAndHighlight(destinationVirtualPath, ArrayList(result.movedVirtualPaths))
                        } else {
                            listener?.refreshFragment()
                        }
                    } else {
                        transferWorkflowStateMachine.markFailed(result.messageCn)
                        activity.toast(result.messageCn)
                        listener?.refreshFragment()
                    }
                    finishActMode()
                }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    dismissTransferDialog(progressDialog)
                    val message = t.message?.trim().orEmpty().ifEmpty { "服务器移动异常，请重试。" }
                    transferWorkflowStateMachine.markFailed(message)
                    activity.toast(message)
                    listener?.refreshFragment()
                }
            }
        }
    }

    private fun getFirstSelectedItemPath() = getSelectedFileDirItems().first().path

    private fun getSelectedFileDirItems(): ArrayList<FileDirItem> {
        return listItems.filter {
            selectedKeys.contains(it.path.hashCode())
        } as ArrayList<FileDirItem>
    }

    fun updateItems(newItems: ArrayList<ListItem>, highlightText: String = "") {
        if (newItems.hashCode() != currentItemsHash) {
            currentItemsHash = newItems.hashCode()
            textToHighlight = highlightText
            listItems = newItems.clone() as ArrayList<ListItem>
            recentDisplayPathCache.clear()
            notifyDataSetChanged()
            finishActMode()
        } else if (textToHighlight != highlightText) {
            textToHighlight = highlightText
            notifyDataSetChanged()
        }
    }

    fun highlightPathsOnce(paths: List<String>) {
        if (paths.isEmpty()) return

        val newHighlightKeys = LinkedHashSet<Int>(paths.size)
        paths.forEach { path ->
            val key = path.hashCode()
            if (getItemKeyPosition(key) != -1) {
                newHighlightKeys.add(key)
            }
        }
        if (newHighlightKeys.isEmpty()) return

        clearPendingHighlightClear()
        clearTransientHighlightsNow()

        val changedPositions = LinkedHashSet<Int>()
        newHighlightKeys.forEach { key ->
            val position = getItemKeyPosition(key)
            if (position == -1 || !getIsItemSelectable(position)) return@forEach
            if (selectedKeys.add(key)) {
                changedPositions.add(position + positionOffset)
            }
            transientHighlightKeys.add(key)
        }
        changedPositions.forEach(::notifyItemChanged)
        scheduleTransientHighlightClear(TRANSIENT_HIGHLIGHT_DURATION_MS)
    }

    private fun scheduleTransientHighlightClear(delayMs: Long) {
        if (transientHighlightKeys.isEmpty()) return
        clearPendingHighlightClear()
        val runnable = Runnable {
            if (transientHighlightKeys.isEmpty()) {
                clearTransientHighlightRunnable = null
                return@Runnable
            }
            if (actMode != null) {
                scheduleTransientHighlightClear(TRANSIENT_HIGHLIGHT_RETRY_MS)
                return@Runnable
            }
            clearTransientHighlightsNow()
            clearTransientHighlightRunnable = null
        }
        clearTransientHighlightRunnable = runnable
        recyclerView.postDelayed(runnable, delayMs)
    }

    private fun clearPendingHighlightClear() {
        clearTransientHighlightRunnable?.let { recyclerView.removeCallbacks(it) }
        clearTransientHighlightRunnable = null
    }

    private fun clearTransientHighlightsNow() {
        if (transientHighlightKeys.isEmpty()) return
        val changedPositions = LinkedHashSet<Int>()
        val keys = ArrayList(transientHighlightKeys)
        transientHighlightKeys.clear()
        keys.forEach { key ->
            if (!selectedKeys.remove(key)) return@forEach
            val position = getItemKeyPosition(key)
            if (position != -1) {
                changedPositions.add(position + positionOffset)
            }
        }
        changedPositions.forEach(::notifyItemChanged)
    }

    fun updateFontSizes() {
        fontSize = activity.getTextSize()
        smallerFontSize = fontSize * 0.8f
        recentMetadataFontSize = fontSize * 0.68f
        notifyDataSetChanged()
    }

    fun updateDateTimeFormat() {
        dateFormat = config.dateFormat
        timeFormat = activity.getTimeFormat()
        notifyDataSetChanged()
    }

    fun updateDisplayFilenamesInGrid() {
        displayFilenamesInGrid = config.displayFilenames
        notifyDataSetChanged()
    }

    fun setRecentPathMetadataEnabled(enabled: Boolean) {
        if (showRecentPathMetadata == enabled) return
        showRecentPathMetadata = enabled
        if (!enabled) {
            recentDisplayPathCache.clear()
        }
        notifyDataSetChanged()
    }

    fun updateChildCount(path: String, count: Int) {
        val position = getItemKeyPosition(path.hashCode())
        val item = listItems.getOrNull(position) ?: return
        item.children = count
        notifyItemChanged(position, Unit)
    }

    fun isASectionTitle(position: Int) = listItems.getOrNull(position)?.isSectionTitle ?: false

    fun isGridTypeDivider(position: Int) = listItems.getOrNull(position)?.isGridTypeDivider ?: false

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        ActiveTransferRegistry.addListener(transferProgressListener)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        Binding.getByItemViewType(holder.itemViewType, isListViewType)
            .bind(holder.itemView).itemTransferProgress?.bind(null)
        if (!activity.isDestroyed && !activity.isFinishing) {
            val icon = Binding.getByItemViewType(holder.itemViewType, isListViewType)
                .bind(holder.itemView).itemIcon
            if (icon != null) {
                Glide.with(activity).clear(icon)
            }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        ActiveTransferRegistry.removeListener(transferProgressListener)
        clearPendingHighlightClear()
        transientHighlightKeys.clear()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private fun notifyTransferProgressChanged(changedPaths: Set<String>) {
        if (activity.isDestroyed || activity.isFinishing || changedPaths.isEmpty()) return
        changedPaths.forEach { path ->
            val position = listItems.indexOfFirst { it.path == path }
            if (position != -1) {
                notifyItemChanged(position + positionOffset, PAYLOAD_TRANSFER_PROGRESS)
            }
        }
    }

    private fun bindTransferProgress(binding: ItemViewBinding, listItem: ListItem) {
        binding.itemTransferProgress?.bind(ActiveTransferRegistry.stateFor(listItem.path))
    }

    private fun setupView(binding: ItemViewBinding, listItem: ListItem) {
        val isSelected = selectedKeys.contains(listItem.path.hashCode())
        binding.apply {
            if (listItem.isSectionTitle) {
                itemIcon?.setImageDrawable(folderDrawable)
                itemSection?.text =
                    if (textToHighlight.isEmpty()) {
                        listItem.mName
                    } else {
                        listItem.mName.highlightTextPart(
                            textToHighlight = textToHighlight,
                            color = properPrimaryColor
                        )
                    }
                itemSection?.setTextColor(textColor)
                itemSection?.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
            } else if (!listItem.isGridTypeDivider) {
                root.setupViewBackground(activity)
                itemFrame.isSelected = isSelected
                val fileName = listItem.name
                itemName?.text =
                    if (textToHighlight.isEmpty()) {
                        fileName
                    } else {
                        fileName.highlightTextPart(
                            textToHighlight = textToHighlight,
                            color = properPrimaryColor
                        )
                    }
                itemName?.setTextColor(textColor)
                itemName?.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    if (isListViewType) fontSize else smallerFontSize
                )

                val isNavigatorSubtitle = listItem.children < 0
                itemDetails?.setTextColor(textColor)
                itemDetails?.alpha = if (isNavigatorSubtitle) 0.5f else 0.6f
                itemDetails?.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    if (isNavigatorSubtitle) smallerFontSize else fontSize
                )

                itemDate?.setTextColor(textColor)
                itemDate?.alpha = 0.6f
                itemDate?.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallerFontSize)

                itemCheck?.beVisibleIf(isSelected)
                if (isSelected) {
                    itemCheck?.background?.applyColorFilter(properPrimaryColor)
                    itemCheck?.applyColorFilter(contrastColor)
                }

                if (!isListViewType && !listItem.isDirectory) {
                    itemName?.beVisibleIf(displayFilenamesInGrid)
                } else {
                    itemName?.beVisible()
                }

                if (listItem.isDirectory) {
                    itemIcon?.setImageDrawable(folderDrawable)
                    itemDetails?.text = getChildrenCnt(listItem)
                    itemDate?.beGone()
                } else {
                    if (showRecentPathMetadata && isListViewType) {
                        itemDetails?.text = listItem.getRecentDisplayPath()
                        itemDetails?.alpha = 0.48f
                        itemDetails?.setTextSize(TypedValue.COMPLEX_UNIT_PX, recentMetadataFontSize)
                        itemDate?.beVisible()
                        itemDate?.alpha = 0.5f
                        itemDate?.setTextSize(TypedValue.COMPLEX_UNIT_PX, recentMetadataFontSize)
                        itemDate?.text = listItem.getRecentMetadataText()
                    } else {
                        itemDetails?.text = listItem.size.formatSize()
                        if (showFileDate) {
                            itemDate?.beVisible()
                            itemDate?.text = listItem.modified.formatDate(activity, dateFormat, timeFormat)
                        } else {
                            itemDate?.beGone()
                        }
                    }

                    val drawable = fileDrawables.getOrElse(
                        key = fileName.substringAfterLast(".").lowercase(Locale.getDefault()),
                        defaultValue = { fileDrawable }
                    )
                    val options = RequestOptions()
                        .signature(listItem.getKey())
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .error(drawable)
                        .transform(CenterCrop(), RoundedCorners(10))

                    val itemToLoad = getImagePathToLoad(listItem.path)
                    if (!activity.isDestroyed && itemIcon != null) {
                        Glide.with(activity)
                            .load(itemToLoad)
                            .transition(DrawableTransitionOptions.withCrossFade())
                            .apply(options)
                            .into(itemIcon!!)
                    }
                }
                bindTransferProgress(this, listItem)
            } else {
                itemTransferProgress?.bind(null)
            }
        }
    }

    private fun ListItem.getRecentDisplayPath(): String {
        recentDisplayPath?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return recentDisplayPathCache.getOrPut(path) {
            RecentPathFormatter.displayPath(activity, path, sessionFileCoordinator)
        }
    }

    private fun ListItem.getRecentMetadataText(): String {
        val sizeText = if (size >= 0L) {
            size.formatSize()
        } else {
            activity.getString(R.string.recent_file_size_unknown)
        }
        val timeText = modified.formatRecentTime()
        return if (timeText.isEmpty()) sizeText else "$sizeText · $timeText"
    }

    private fun Long.formatRecentTime(): String {
        if (this <= 0L) {
            return ""
        }

        val now = System.currentTimeMillis()
        val diffDays = (now.localEpochDay() - localEpochDay()).toInt()
        return when {
            diffDays < 0 -> formatDate(activity, dateFormat, timeFormat)
            diffDays == 0 -> activity.getString(R.string.recent_time_today)
            diffDays == 1 -> activity.getString(R.string.recent_time_yesterday)
            diffDays == 2 -> activity.getString(R.string.recent_time_day_before_yesterday)
            diffDays < 7 -> activity.resources.getQuantityString(R.plurals.recent_time_days_ago, diffDays, diffDays)
            diffDays < 14 -> activity.getString(R.string.recent_time_last_week)
            diffDays < 30 -> {
                val weeks = (diffDays / 7).coerceAtLeast(2)
                activity.resources.getQuantityString(R.plurals.recent_time_weeks_ago, weeks, weeks)
            }
            diffDays < 60 -> activity.getString(R.string.recent_time_last_month)
            diffDays < 365 -> {
                val months = (diffDays / 30).coerceAtLeast(2)
                activity.resources.getQuantityString(R.plurals.recent_time_months_ago, months, months)
            }
            else -> formatDate(activity, dateFormat, timeFormat)
        }
    }

    private fun Long.localEpochDay(): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = this
        val offset = calendar.timeZone.getOffset(this)
        return TimeUnit.MILLISECONDS.toDays(this + offset)
    }

    private fun getChildrenCnt(item: FileDirItem): String {
        val children = item.children
        if (children < 0) {
            return NavigatorFolderHelper.getItemSubtitle(activity, item.path)
        }
        return activity.resources.getQuantityString(R.plurals.items, children, children)
    }

    private fun getOTGPublicPath(itemToLoad: String): String {
        return "${baseConfig.OTGTreeUri}/document/${baseConfig.OTGPartition}%3A${
            itemToLoad.substring(baseConfig.OTGPath.length).replace("/", "%2F")
        }"
    }

    private fun getImagePathToLoad(path: String): Any {
        var itemToLoad = if (path.endsWith(".apk", true)) {
            val packageInfo =
                activity.packageManager.getPackageArchiveInfo(path, PackageManager.GET_ACTIVITIES)
            val appInfo = packageInfo?.applicationInfo
            if (appInfo != null) {
                appInfo.sourceDir = path
                appInfo.publicSourceDir = path
                appInfo.loadIcon(activity.packageManager)
            } else {
                path
            }
        } else {
            path
        }

        if (activity.isRestrictedSAFOnlyRoot(path)) {
            itemToLoad = activity.getAndroidSAFUri(path)
        } else if (hasOTGConnected && itemToLoad is String && activity.isPathOnOTG(itemToLoad) && baseConfig.OTGTreeUri.isNotEmpty() && baseConfig.OTGPartition.isNotEmpty()) {
            itemToLoad = getOTGPublicPath(itemToLoad)
        }

        return itemToLoad
    }

    fun initDrawables() {
        folderDrawable =
            resources.getColoredDrawableWithColor(R.drawable.ic_folder_vector, properPrimaryColor)
        folderDrawable.alpha = 180
        fileDrawable = resources.getDrawable(R.drawable.ic_file_generic)
        fileDrawables = getFilePlaceholderDrawables(activity)
    }

    override fun onChange(position: Int): String {
        return listItems.getOrNull(position)?.getBubbleText(activity, dateFormat, timeFormat) ?: ""
    }

    private sealed interface Binding {
        companion object {
            fun getByItemViewType(viewType: Int, isListViewType: Boolean): Binding {
                return when (viewType) {
                    TYPE_SECTION -> ItemSection
                    TYPE_GRID_TYPE_DIVIDER -> ItemEmpty
                    else -> {
                        if (isListViewType) {
                            ItemFileDirList
                        } else if (viewType == TYPE_DIR) {
                            ItemDirGrid
                        } else {
                            ItemFileGrid
                        }
                    }
                }
            }
        }

        fun inflate(
            layoutInflater: LayoutInflater,
            viewGroup: ViewGroup,
            attachToRoot: Boolean
        ): ItemViewBinding

        fun bind(view: View): ItemViewBinding

        data object ItemSection : Binding {
            override fun inflate(
                layoutInflater: LayoutInflater,
                viewGroup: ViewGroup,
                attachToRoot: Boolean
            ): ItemViewBinding {
                return ItemSectionBindingAdapter(
                    ItemSectionBinding.inflate(layoutInflater, viewGroup, attachToRoot)
                )
            }

            override fun bind(view: View): ItemViewBinding {
                return ItemSectionBindingAdapter(ItemSectionBinding.bind(view))
            }
        }

        data object ItemEmpty : Binding {
            override fun inflate(
                layoutInflater: LayoutInflater,
                viewGroup: ViewGroup,
                attachToRoot: Boolean
            ): ItemViewBinding {
                return ItemEmptyBindingAdapter(
                    ItemEmptyBinding.inflate(layoutInflater, viewGroup, attachToRoot)
                )
            }

            override fun bind(view: View): ItemViewBinding {
                return ItemEmptyBindingAdapter(ItemEmptyBinding.bind(view))
            }
        }

        data object ItemFileDirList : Binding {
            override fun inflate(
                layoutInflater: LayoutInflater,
                viewGroup: ViewGroup,
                attachToRoot: Boolean
            ): ItemViewBinding {
                return ItemFileDirListBindingAdapter(
                    ItemFileDirListBinding.inflate(layoutInflater, viewGroup, attachToRoot)
                )
            }

            override fun bind(view: View): ItemViewBinding {
                return ItemFileDirListBindingAdapter(ItemFileDirListBinding.bind(view))
            }
        }

        data object ItemDirGrid : Binding {
            override fun inflate(
                layoutInflater: LayoutInflater,
                viewGroup: ViewGroup,
                attachToRoot: Boolean
            ): ItemViewBinding {
                return ItemDirGridBindingAdapter(
                    ItemDirGridBinding.inflate(layoutInflater, viewGroup, attachToRoot)
                )
            }

            override fun bind(view: View): ItemViewBinding {
                return ItemDirGridBindingAdapter(ItemDirGridBinding.bind(view))
            }
        }

        data object ItemFileGrid : Binding {
            override fun inflate(
                layoutInflater: LayoutInflater,
                viewGroup: ViewGroup,
                attachToRoot: Boolean
            ): ItemViewBinding {
                return ItemFileGridBindingAdapter(
                    ItemFileGridBinding.inflate(layoutInflater, viewGroup, attachToRoot)
                )
            }

            override fun bind(view: View): ItemViewBinding {
                return ItemFileGridBindingAdapter(ItemFileGridBinding.bind(view))
            }
        }
    }

    private interface ItemViewBinding : ViewBinding {
        val itemFrame: FrameLayout
        val itemName: TextView?
        val itemIcon: ImageView?
        val itemCheck: ImageView?
        val itemDetails: TextView?
        val itemDate: TextView?
        val itemSection: TextView?
        val itemTransferProgress: InlineTransferProgressView?
    }

    private class ItemSectionBindingAdapter(val binding: ItemSectionBinding) : ItemViewBinding {
        override val itemFrame: FrameLayout = binding.itemFrame
        override val itemName: TextView? = null
        override val itemIcon: ImageView = binding.itemIcon
        override val itemDetails: TextView? = null
        override val itemDate: TextView? = null
        override val itemCheck: ImageView? = null
        override val itemSection: TextView = binding.itemSection
        override val itemTransferProgress: InlineTransferProgressView? = null
        override fun getRoot(): View = binding.root
    }

    private class ItemEmptyBindingAdapter(val binding: ItemEmptyBinding) : ItemViewBinding {
        override val itemFrame: FrameLayout = binding.itemFrame
        override val itemName: TextView? = null
        override val itemIcon: ImageView? = null
        override val itemDetails: TextView? = null
        override val itemDate: TextView? = null
        override val itemCheck: ImageView? = null
        override val itemSection: TextView? = null
        override val itemTransferProgress: InlineTransferProgressView? = null

        override fun getRoot(): View = binding.root
    }

    private class ItemFileDirListBindingAdapter(
        val binding: ItemFileDirListBinding
    ) : ItemViewBinding {
        override val itemFrame: FrameLayout = binding.itemFrame
        override val itemName: TextView = binding.itemName
        override val itemIcon: ImageView = binding.itemIcon
        override val itemDetails: TextView = binding.itemDetails
        override val itemDate: TextView = binding.itemDate
        override val itemCheck: ImageView? = null
        override val itemSection: TextView? = null
        override val itemTransferProgress: InlineTransferProgressView = binding.itemTransferProgress

        override fun getRoot(): View = binding.root
    }

    private class ItemDirGridBindingAdapter(val binding: ItemDirGridBinding) : ItemViewBinding {
        override val itemFrame: FrameLayout = binding.itemFrame
        override val itemName: TextView = binding.itemName
        override val itemIcon: ImageView = binding.itemIcon
        override val itemDetails: TextView? = null
        override val itemDate: TextView? = null
        override val itemCheck: ImageView = binding.itemCheck
        override val itemSection: TextView? = null
        override val itemTransferProgress: InlineTransferProgressView = binding.itemTransferProgress

        override fun getRoot(): View = binding.root
    }

    private class ItemFileGridBindingAdapter(val binding: ItemFileGridBinding) : ItemViewBinding {
        override val itemFrame: FrameLayout = binding.itemFrame
        override val itemName: TextView = binding.itemName
        override val itemIcon: ImageView = binding.itemIcon
        override val itemDetails: TextView? = null
        override val itemDate: TextView? = null
        override val itemCheck: ImageView? = null
        override val itemSection: TextView? = null
        override val itemTransferProgress: InlineTransferProgressView = binding.itemTransferProgress

        override fun getRoot(): View = binding.root
    }
}
