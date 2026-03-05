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
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.LocalFileHeader
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
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
import org.fossify.commons.extensions.createDirectorySync
import org.fossify.commons.extensions.deleteFile
import org.fossify.commons.extensions.deleteFileBg
import org.fossify.commons.extensions.deleteFolderBg
import org.fossify.commons.extensions.formatDate
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAndroidSAFFileItems
import org.fossify.commons.extensions.getAndroidSAFUri
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getDefaultCopyDestinationPath
import org.fossify.commons.extensions.getDocumentFile
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getFileCount
import org.fossify.commons.extensions.getFileInputStreamSync
import org.fossify.commons.extensions.getFileOutputStreamSync
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getIsPathDirectory
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getProperSize
import org.fossify.commons.extensions.getTextSize
import org.fossify.commons.extensions.getTimeFormat
import org.fossify.commons.extensions.handleDeletePasswordProtection
import org.fossify.commons.extensions.hasOTGConnected
import org.fossify.commons.extensions.highlightTextPart
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.isRestrictedSAFOnlyRoot
import org.fossify.commons.extensions.relativizeWith
import org.fossify.commons.extensions.setupViewBackground
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toFileDirItem
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.CONFLICT_OVERWRITE
import org.fossify.commons.helpers.CONFLICT_SKIP
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
import org.fossify.filemanager.dialogs.CompressAsDialog
import org.fossify.filemanager.extensions.config
import org.fossify.filemanager.extensions.isPathOnRoot
import org.fossify.filemanager.extensions.isZipFile
import org.fossify.filemanager.extensions.setAs
import org.fossify.filemanager.extensions.setLastModified
import org.fossify.filemanager.extensions.sharePaths
import org.fossify.filemanager.extensions.toggleItemVisibility
import org.fossify.filemanager.extensions.tryOpenPathIntent
import org.fossify.filemanager.helpers.OPEN_AS_AUDIO
import org.fossify.filemanager.helpers.OPEN_AS_IMAGE
import org.fossify.filemanager.helpers.OPEN_AS_OTHER
import org.fossify.filemanager.helpers.OPEN_AS_TEXT
import org.fossify.filemanager.helpers.OPEN_AS_VIDEO
import org.fossify.filemanager.helpers.NavigatorFolderHelper
import org.fossify.filemanager.helpers.RootHelpers
import org.fossify.filemanager.interfaces.ItemOperationsListener
import org.fossify.filemanager.models.ListItem
import com.termux.sessionsync.FileRootResolver
import com.termux.sessionsync.SessionFileCoordinator
import com.termux.sessionsync.SftpProtocolManager
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ItemsAdapter(
    activity: SimpleActivity,
    var listItems: MutableList<ListItem>,
    private val listener: ItemOperationsListener?,
    recyclerView: MyRecyclerView,
    private val isPickMultipleIntent: Boolean,
    private val swipeRefreshLayout: SwipeRefreshLayout?,
    canHaveIndividualViewType: Boolean = true,
    itemClick: (Any) -> Unit,
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick),
    RecyclerViewFastScroller.OnPopupTextUpdate {

    private lateinit var fileDrawable: Drawable
    private lateinit var folderDrawable: Drawable
    private var fileDrawables = HashMap<String, Drawable>()
    private var currentItemsHash = listItems.hashCode()
    private var textToHighlight = ""
    private val hasOTGConnected = activity.hasOTGConnected()
    private var fontSize = 0f
    private var smallerFontSize = 0f
    private var dateFormat = ""
    private var timeFormat = ""
    private val sessionFileCoordinator = SessionFileCoordinator.getInstance()
    private val transientHighlightKeys = LinkedHashSet<Int>()
    private var clearTransientHighlightRunnable: Runnable? = null

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

    companion object {
        private const val TYPE_FILE = 1
        private const val TYPE_DIR = 2
        private const val TYPE_SECTION = 3
        private const val TYPE_GRID_TYPE_DIVIDER = 4
        private const val TRANSIENT_HIGHLIGHT_DURATION_MS = 1_600L
        private const val TRANSIENT_HIGHLIGHT_RETRY_MS = 700L
        private val virtualDownloadInProgress = AtomicBoolean(false)
        private val virtualUploadInProgress = AtomicBoolean(false)
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
                getSelectedFileDirItems().map { it.path }.any { it.isZipFile() }
            findItem(R.id.cab_confirm_selection).isVisible = isPickMultipleIntent
            findItem(R.id.cab_copy_path).isVisible = isOneItemSelected()
            findItem(R.id.cab_open_with).isVisible = isOneFileSelected()
            findItem(R.id.cab_open_as).isVisible = isOneFileSelected()
            findItem(R.id.cab_set_as).isVisible = isOneFileSelected()
            findItem(R.id.cab_create_shortcut).isVisible = isOneItemSelected()

            checkHideBtnVisibility(this)
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
            R.id.cab_share -> shareFiles()
            R.id.cab_hide -> toggleFileVisibility(true)
            R.id.cab_unhide -> toggleFileVisibility(false)
            R.id.cab_create_shortcut -> createShortcut()
            R.id.cab_copy_path -> copyPath()
            R.id.cab_set_as -> setAs()
            R.id.cab_open_with -> openWith()
            R.id.cab_open_as -> openAs()
            R.id.cab_copy_to -> copyMoveTo(true)
            R.id.cab_move_to -> tryMoveFiles()
            R.id.cab_compress -> compressSelection()
            R.id.cab_decompress -> decompressSelection()
            R.id.cab_select_all -> selectAll()
            R.id.cab_delete -> if (config.skipDeleteConfirmation) deleteFiles() else askConfirmDelete()
        }
    }

    override fun getSelectableItemCount(): Int {
        return listItems.filter { !it.isSectionTitle && !it.isGridTypeDivider }.size
    }

    override fun getIsItemSelectable(position: Int): Boolean {
        val item = listItems[position]
        return !item.isSectionTitle && !item.isGridTypeDivider && item.children >= 0
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
            showLongPressActionDialog(itemPosition)
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

    private fun checkHideBtnVisibility(menu: Menu) {
        var hiddenCnt = 0
        var unhiddenCnt = 0
        getSelectedFileDirItems().map { it.name }.forEach {
            if (it.startsWith(".")) {
                hiddenCnt++
            } else {
                unhiddenCnt++
            }
        }

        menu.findItem(R.id.cab_hide).isVisible = unhiddenCnt > 0
        menu.findItem(R.id.cab_unhide).isVisible = hiddenCnt > 0
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

        activity.getAlertDialogBuilder()
            .setTitle(dialogTitle)
            .setItems(labels) { _, which ->
                val actionId = actions.getOrNull(which)?.id ?: return@setItems
                actionChosen = true
                actionItemPressed(actionId)
                if (shouldClearSelectionImmediately(actionId)) {
                    finishActMode()
                }
            }
            .setOnDismissListener {
                if (!actionChosen) {
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
        actions.add(LongPressAction(R.id.cab_rename, activity.getString(R.string.rename)))
        actions.add(LongPressAction(R.id.cab_properties, activity.getString(R.string.properties)))
        actions.add(LongPressAction(R.id.cab_share, activity.getString(R.string.share)))

        if (isOneItemSelected()) {
            actions.add(LongPressAction(R.id.cab_copy_path, activity.getString(R.string.copy_path)))
            actions.add(LongPressAction(R.id.cab_create_shortcut, activity.getString(R.string.create_shortcut)))
        }
        if (isOneFileSelected()) {
            actions.add(LongPressAction(R.id.cab_open_with, activity.getString(R.string.open_with)))
            actions.add(LongPressAction(R.id.cab_open_as, activity.getString(R.string.open_as)))
            actions.add(LongPressAction(R.id.cab_set_as, activity.getString(R.string.set_as)))
        }
        if (unhiddenCnt > 0) {
            actions.add(LongPressAction(R.id.cab_hide, activity.getString(R.string.hide)))
        }
        if (hiddenCnt > 0) {
            actions.add(LongPressAction(R.id.cab_unhide, activity.getString(R.string.unhide)))
        }

        actions.add(LongPressAction(R.id.cab_copy_to, activity.getString(R.string.copy_to)))
        actions.add(LongPressAction(R.id.cab_move_to, activity.getString(R.string.move_to)))
        actions.add(LongPressAction(R.id.cab_compress, activity.getString(R.string.compress)))

        val hasZip = selected.any { it.path.isZipFile() }
        if (hasZip) {
            actions.add(LongPressAction(R.id.cab_decompress, activity.getString(R.string.decompress)))
        }

        actions.add(LongPressAction(R.id.cab_select_all, activity.getString(R.string.select_all)))
        actions.add(LongPressAction(R.id.cab_delete, activity.getString(R.string.delete)))
        return actions
    }

    private fun shouldClearSelectionImmediately(actionId: Int): Boolean {
        return when (actionId) {
            R.id.cab_confirm_selection,
            R.id.cab_rename,
            R.id.cab_properties,
            R.id.cab_share,
            R.id.cab_create_shortcut,
            R.id.cab_copy_path,
            R.id.cab_set_as,
            R.id.cab_open_with,
            R.id.cab_open_as,
            R.id.cab_copy_to,
            R.id.cab_move_to,
            R.id.cab_compress,
            R.id.cab_decompress -> true

            else -> false
        }
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
        val selectedItems = getSelectedFileDirItems()
        val paths = ArrayList<String>(selectedItems.size)
        selectedItems.forEach {
            addFileUris(it.path, paths)
        }
        activity.sharePaths(paths)
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

    @SuppressLint("NewApi")
    private fun addFileUris(path: String, paths: ArrayList<String>) {
        if (activity.getIsPathDirectory(path)) {
            val shouldShowHidden = config.shouldShowHidden()
            when {
                activity.isRestrictedSAFOnlyRoot(path) -> {
                    activity.getAndroidSAFFileItems(path, shouldShowHidden, false) { files ->
                        files.forEach {
                            addFileUris(activity.getAndroidSAFUri(it.path).toString(), paths)
                        }
                    }
                }

                activity.isPathOnOTG(path) -> {
                    activity.getDocumentFile(path)?.listFiles()
                        ?.filter { if (shouldShowHidden) true else !it.name!!.startsWith(".") }
                        ?.forEach {
                            addFileUris(it.uri.toString(), paths)
                        }
                }

                else -> {
                    File(path).listFiles()
                        ?.filter { if (shouldShowHidden) true else !it.name.startsWith('.') }
                        ?.forEach {
                            addFileUris(it.absolutePath, paths)
                        }
                }
            }
        } else {
            paths.add(path)
        }
    }

    private fun copyPath() {
        val selectedPath = getFirstSelectedItemPath()
        val clipboardPath = if (sessionFileCoordinator.isVirtualPath(activity, selectedPath)) {
            sessionFileCoordinator.getDisplayPath(activity, selectedPath).takeIf { it.isNotBlank() } ?: selectedPath
        } else {
            selectedPath
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
            return
        }

        if (isCopyOperation && files.any { sessionFileCoordinator.isVirtualPath(activity, it.path) }) {
            copyVirtualSelectionToLocal(files)
            return
        }

        val firstFile = files[0]
        val source = firstFile.getParentPath()
        FilePickerDialog(
            activity = activity,
            currPath = activity.getDefaultCopyDestinationPath(config.shouldShowHidden(), source),
            pickFile = false,
            showHidden = config.shouldShowHidden(),
            showFAB = true,
            canAddShowHiddenButton = true,
            showFavoritesButton = true
        ) {
            config.lastCopyPath = it
            if (sessionFileCoordinator.isVirtualPath(activity, it)) {
                if (!isCopyOperation) {
                    activity.toast("\u4e0a\u4f20\u5230\u670d\u52a1\u5668\u6682\u4e0d\u652f\u6301\u79fb\u52a8\uff0c\u8bf7\u4f7f\u7528\u201c\u590d\u5236\u5230\u201d\u3002")
                    return@FilePickerDialog
                }
                if (files.any { selected -> sessionFileCoordinator.isVirtualPath(activity, selected.path) }) {
                    activity.toast("\u8bf7\u5206\u5f00\u9009\u62e9\u672c\u5730\u4e0e\u8fdc\u7a0b\u9879\u76ee\u540e\u518d\u4e0a\u4f20\u3002")
                    return@FilePickerDialog
                }
                runVirtualUpload(files, it)
                return@FilePickerDialog
            }

            if (activity.isPathOnRoot(it) || activity.isPathOnRoot(firstFile.path)) {
                copyMoveRootItems(files, it, isCopyOperation)
            } else {
                activity.copyMoveFilesTo(
                    fileDirItems = files,
                    source = source,
                    destination = it,
                    isCopyOperation = isCopyOperation,
                    copyPhotoVideoOnly = false,
                    copyHidden = config.shouldShowHidden()
                ) {
                    if (!isCopyOperation) {
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

    private fun copyVirtualSelectionToLocal(files: ArrayList<FileDirItem>) {
        val virtualItems = files.filter { sessionFileCoordinator.isVirtualPath(activity, it.path) }
        if (virtualItems.isEmpty()) {
            return
        }

        if (virtualItems.size != files.size) {
            activity.toast("\u8bf7\u5206\u5f00\u9009\u62e9\u672c\u5730\u4e0e\u8fdc\u7a0b\u9879\u76ee\u540e\u518d\u590d\u5236\u3002")
            return
        }

        val localRoot = FileRootResolver.termuxPrivateRoot(activity)
        var defaultDestination = activity.getDefaultCopyDestinationPath(config.shouldShowHidden(), localRoot)
        if (
            sessionFileCoordinator.isVirtualPath(activity, defaultDestination)
            || !activity.getDoesFilePathExist(defaultDestination)
        ) {
            defaultDestination = localRoot
        }

        FilePickerDialog(
            activity = activity,
            currPath = defaultDestination,
            pickFile = false,
            showHidden = config.shouldShowHidden(),
            showFAB = true,
            canAddShowHiddenButton = true,
            showFavoritesButton = true
        ) { destination ->
            if (sessionFileCoordinator.isVirtualPath(activity, destination)) {
                activity.toast("\u8bf7\u9009\u62e9\u672c\u5730\u76ee\u5f55\u4f5c\u4e3a\u4e0b\u8f7d\u76ee\u6807\u3002")
                return@FilePickerDialog
            }

            config.lastCopyPath = destination
            runVirtualDownload(virtualItems, destination)
        }
    }

    private fun runVirtualDownload(virtualItems: List<FileDirItem>, destination: String) {
        if (activity.isDestroyed || activity.isFinishing) {
            return
        }
        if (!virtualDownloadInProgress.compareAndSet(false, true)) {
            activity.toast("\u5df2\u6709\u4e0b\u8f7d\u4efb\u52a1\u5728\u6267\u884c\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002")
            return
        }

        val progressDialog = activity.getAlertDialogBuilder()
            .setTitle("\u4e0b\u8f7d\u4e2d")
            .setMessage("\u6b63\u5728\u51c6\u5907\u4e0b\u8f7d...")
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
                val virtualPaths = ArrayList<String>(virtualItems.size)
                virtualItems.forEach { virtualPaths.add(it.path) }
                val revealPaths = buildLocalRevealPaths(virtualItems, destination)
                val result = sessionFileCoordinator.downloadVirtualPaths(
                    activity,
                    virtualPaths,
                    destination,
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
                    object : SftpProtocolManager.DownloadControl {
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
                            activity.toast(
                                "\u4e0b\u8f7d\u5b8c\u6210\uff1a${result.downloadedFiles}/${result.totalFiles}\uff0c${
                                    result.downloadedBytes.formatSize()
                                }"
                            )
                        }

                        result.downloadedFiles > 0 -> {
                            val reason = if (result.messageCn.isNotEmpty()) " ${result.messageCn}" else ""
                            activity.toast(
                                "\u90e8\u5206\u5b8c\u6210\uff1a${result.downloadedFiles}/${result.totalFiles}\uff0c${
                                    result.downloadedBytes.formatSize()
                                }$reason"
                            )
                        }

                        else -> {
                            val failure = if (result.messageCn.isNotEmpty()) {
                                result.messageCn
                            } else {
                                "\u4e0b\u8f7d\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u6216\u8ba4\u8bc1\u4fe1\u606f\u3002"
                            }
                            activity.toast(failure)
                        }
                    }

                    if (result.downloadedFiles > 0 && revealPaths.isNotEmpty()) {
                        listener?.openPathAndHighlight(destination, revealPaths)
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
                        activity.toast("\u4e0b\u8f7d\u5f02\u5e38\uff1a$msg")
                    } else {
                        activity.toast("\u4e0b\u8f7d\u5f02\u5e38\uff0c\u8bf7\u91cd\u8bd5\u3002")
                    }
                }
            } finally {
                virtualDownloadInProgress.set(false)
            }
        }
    }

    private fun runVirtualUpload(localItems: List<FileDirItem>, destinationVirtualPath: String) {
        if (activity.isDestroyed || activity.isFinishing) {
            return
        }
        if (!virtualUploadInProgress.compareAndSet(false, true)) {
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
                val revealPaths = buildVirtualRevealPaths(localItems, destinationVirtualPath)
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
                            activity.toast(
                                "\u4e0a\u4f20\u5b8c\u6210\uff1a${result.uploadedFiles}/${result.totalFiles}\uff0c${
                                    result.uploadedBytes.formatSize()
                                }"
                            )
                        }

                        result.uploadedFiles > 0 -> {
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
                            activity.toast(failure)
                        }
                    }

                    if (result.uploadedFiles > 0 && revealPaths.isNotEmpty()) {
                        listener?.openPathAndHighlight(destinationVirtualPath, revealPaths)
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
                        activity.toast("\u4e0a\u4f20\u5f02\u5e38\uff1a$msg")
                    } else {
                        activity.toast("\u4e0a\u4f20\u5f02\u5e38\uff0c\u8bf7\u91cd\u8bd5\u3002")
                    }
                }
            } finally {
                virtualUploadInProgress.set(false)
            }
        }
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
        val firstPath = getFirstSelectedItemPath()
        if (activity.isPathOnOTG(firstPath)) {
            activity.toast(R.string.unknown_error_occurred)
            return
        }

        CompressAsDialog(activity, firstPath) { destination, password ->
            activity.handleAndroidSAFDialog(firstPath) { granted ->
                if (!granted) {
                    return@handleAndroidSAFDialog
                }
                activity.handleSAFDialog(firstPath) {
                    if (!it) {
                        return@handleSAFDialog
                    }

                    activity.toast(R.string.compressing)
                    val paths = getSelectedFileDirItems().map { it.path }
                    ensureBackgroundThread {
                        if (compressPaths(paths, destination, password)) {
                            activity.runOnUiThread {
                                activity.toast(R.string.compression_successful)
                                listener?.refreshFragment()
                                finishActMode()
                            }
                        } else {
                            activity.toast(R.string.compressing_failed)
                        }
                    }
                }
            }
        }
    }

    private fun decompressSelection() {
        val firstPath = getFirstSelectedItemPath()
        if (activity.isPathOnOTG(firstPath)) {
            activity.toast(R.string.unknown_error_occurred)
            return
        }

        activity.handleSAFDialog(firstPath) {
            if (!it) {
                return@handleSAFDialog
            }

            val paths = getSelectedFileDirItems()
                .asSequence()
                .map { it.path }
                .filter { it.isZipFile() }
                .toList()
            ensureBackgroundThread {
                tryDecompressingPaths(paths) { success ->
                    activity.runOnUiThread {
                        if (success) {
                            activity.toast(R.string.decompression_successful)
                            listener?.refreshFragment()
                            finishActMode()
                        } else {
                            activity.toast(R.string.decompressing_failed)
                        }
                    }
                }
            }
        }
    }

    private fun tryDecompressingPaths(
        sourcePaths: List<String>,
        callback: (success: Boolean) -> Unit
    ) {
        sourcePaths.forEach { path ->
            ZipInputStream(BufferedInputStream(activity.getFileInputStreamSync(path))).use { zipInputStream ->
                try {
                    val fileDirItems = ArrayList<FileDirItem>()
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        val currPath = if (entry.isDirectory) {
                            path
                        } else {
                            "${path.getParentPath().trimEnd('/')}/${entry.fileName}"
                        }
                        val fileDirItem = FileDirItem(
                            path = currPath,
                            name = entry.fileName,
                            isDirectory = entry.isDirectory,
                            children = 0,
                            size = entry.uncompressedSize
                        )
                        fileDirItems.add(fileDirItem)
                        entry = zipInputStream.nextEntry
                    }
                    val destinationPath = fileDirItems.first().getParentPath().trimEnd('/')
                    activity.runOnUiThread {
                        activity.checkConflicts(fileDirItems, destinationPath, 0, LinkedHashMap()) {
                            ensureBackgroundThread {
                                decompressPaths(sourcePaths, it, callback)
                            }
                        }
                    }
                } catch (zipException: ZipException) {
                    if (zipException.type == ZipException.Type.WRONG_PASSWORD) {
                        activity.showErrorToast(activity.getString(R.string.invalid_password))
                    } else {
                        activity.showErrorToast(zipException)
                    }
                } catch (exception: Exception) {
                    activity.showErrorToast(exception)
                }
            }
        }
    }

    private fun decompressPaths(
        paths: List<String>,
        conflictResolutions: LinkedHashMap<String, Int>,
        callback: (success: Boolean) -> Unit
    ) {
        paths.forEach { path ->
            val zipInputStream =
                ZipInputStream(BufferedInputStream(activity.getFileInputStreamSync(path)))
            zipInputStream.use {
                try {
                    var entry = zipInputStream.nextEntry
                    val zipFileName = path.getFilenameFromPath()
                    val newFolderName = zipFileName.subSequence(0, zipFileName.length - 4)
                    while (entry != null) {
                        val parentPath = path.getParentPath()
                        val newPath = "$parentPath/$newFolderName/${entry.fileName.trimEnd('/')}"

                        val resolution = getConflictResolution(conflictResolutions, newPath)
                        val doesPathExist = activity.getDoesFilePathExist(newPath)
                        if (doesPathExist && resolution == CONFLICT_OVERWRITE) {
                            val fileDirItem = FileDirItem(
                                path = newPath,
                                name = newPath.getFilenameFromPath(),
                                isDirectory = entry.isDirectory
                            )
                            if (activity.getIsPathDirectory(path)) {
                                activity.deleteFolderBg(fileDirItem, false) {
                                    if (it) {
                                        extractEntry(newPath, entry, zipInputStream)
                                    } else {
                                        callback(false)
                                    }
                                }
                            } else {
                                activity.deleteFileBg(fileDirItem, false, false) {
                                    if (it) {
                                        extractEntry(newPath, entry, zipInputStream)
                                    } else {
                                        callback(false)
                                    }
                                }
                            }
                        } else if (!doesPathExist) {
                            extractEntry(newPath, entry, zipInputStream)
                        }

                        entry = zipInputStream.nextEntry
                    }
                    callback(true)
                } catch (e: Exception) {
                    activity.showErrorToast(e)
                    callback(false)
                }
            }
        }
    }

    private fun extractEntry(
        newPath: String,
        entry: LocalFileHeader,
        zipInputStream: ZipInputStream
    ) {
        if (entry.isDirectory) {
            if (!activity.createDirectorySync(newPath) && !activity.getDoesFilePathExist(newPath)) {
                val error =
                    String.format(activity.getString(R.string.could_not_create_file), newPath)
                activity.showErrorToast(error)
            }
        } else {
            val fos = activity.getFileOutputStreamSync(newPath, newPath.getMimeType())
            if (fos != null) {
                zipInputStream.copyTo(fos)
                File(newPath).setLastModified(entry)
            }
        }
    }

    private fun getConflictResolution(
        conflictResolutions: LinkedHashMap<String, Int>,
        path: String
    ): Int {
        return if (conflictResolutions.size == 1 && conflictResolutions.containsKey("")) {
            conflictResolutions[""]!!
        } else if (conflictResolutions.containsKey(path)) {
            conflictResolutions[path]!!
        } else {
            CONFLICT_SKIP
        }
    }

    @SuppressLint("NewApi")
    private fun compressPaths(
        sourcePaths: List<String>,
        targetPath: String,
        password: String? = null
    ): Boolean {
        val queue = LinkedList<String>()
        val fos = activity.getFileOutputStreamSync(targetPath, "application/zip") ?: return false

        val zout =
            password?.let { ZipOutputStream(fos, password.toCharArray()) } ?: ZipOutputStream(fos)
        var res: Closeable = fos

        fun zipEntry(name: String, lastModified: Long) = ZipParameters().also {
            it.fileNameInZip = name
            it.lastModifiedFileTime = lastModified
            if (password != null) {
                it.isEncryptFiles = true
                it.encryptionMethod = EncryptionMethod.AES
            }
        }

        try {
            sourcePaths.forEach { currentPath ->
                var name: String
                var mainFilePath = currentPath
                val base = "${mainFilePath.getParentPath()}/"
                res = zout
                queue.push(mainFilePath)
                if (activity.getIsPathDirectory(mainFilePath)) {
                    name = "${mainFilePath.getFilenameFromPath()}/"
                    val dirModified = File(mainFilePath).lastModified()
                    zout.putNextEntry(
                        ZipParameters().also {
                            it.fileNameInZip = name
                            it.lastModifiedFileTime = dirModified
                        }
                    )
                }

                while (!queue.isEmpty()) {
                    mainFilePath = queue.pop()
                    if (activity.getIsPathDirectory(mainFilePath)) {
                        if (activity.isRestrictedSAFOnlyRoot(mainFilePath)) {
                            activity.getAndroidSAFFileItems(mainFilePath, true) { files ->
                                for (file in files) {
                                    name = file.path.relativizeWith(base)
                                    if (activity.getIsPathDirectory(file.path)) {
                                        queue.push(file.path)
                                        name = "${name.trimEnd('/')}/"
                                        zout.putNextEntry(zipEntry(name, file.modified))
                                    } else {
                                        zout.putNextEntry(zipEntry(name, file.modified))
                                        activity.getFileInputStreamSync(file.path)!!.copyTo(zout)
                                        zout.closeEntry()
                                    }
                                }
                            }
                        } else {
                            val mainFile = File(mainFilePath)
                            for (file in mainFile.listFiles()) {
                                name = file.path.relativizeWith(base)
                                if (activity.getIsPathDirectory(file.absolutePath)) {
                                    queue.push(file.absolutePath)
                                    name = "${name.trimEnd('/')}/"
                                    zout.putNextEntry(zipEntry(name, file.lastModified()))
                                } else {
                                    zout.putNextEntry(zipEntry(name, file.lastModified()))
                                    activity.getFileInputStreamSync(file.path)!!.copyTo(zout)
                                    zout.closeEntry()
                                }
                            }
                        }

                    } else {
                        name =
                            if (base == currentPath) {
                                currentPath.getFilenameFromPath()
                            } else {
                                mainFilePath.relativizeWith(base)
                            }
                        val fileModified = File(mainFilePath).lastModified()
                        zout.putNextEntry(zipEntry(name, fileModified))
                        activity.getFileInputStreamSync(mainFilePath)!!.copyTo(zout)
                        zout.closeEntry()
                    }
                }
            }
        } catch (exception: Exception) {
            activity.showErrorToast(exception)
            return false
        } finally {
            res.close()
        }
        return true
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
            val positions = ArrayList<Int>()

            ensureBackgroundThread {
                selectedKeys.forEach { key ->
                    config.removeFavorite(getItemWithKey(key)?.path ?: "")
                    val position = listItems.indexOfFirst { it.path.hashCode() == key }
                    if (position != -1) {
                        positions.add(position)
                        files.add(listItems[position])
                    }
                }

                positions.sortDescending()
                activity.runOnUiThread {
                    removeSelectedItems(positions)
                    listener?.deleteFiles(files)
                    positions.forEach {
                        listItems.removeAt(it)
                    }
                }
            }
        }
    }

    private fun getFirstSelectedItemPath() = getSelectedFileDirItems().first().path

    private fun buildLocalRevealPaths(virtualItems: List<FileDirItem>, destination: String): ArrayList<String> {
        val reveal = ArrayList<String>(virtualItems.size)
        virtualItems.forEach { item ->
            val name = item.name.ifBlank { item.path.getFilenameFromPath() }
            if (name.isBlank()) return@forEach
            reveal.add(File(destination, name).absolutePath.replace('\\', '/'))
        }
        return reveal
    }

    private fun buildVirtualRevealPaths(localItems: List<FileDirItem>, destinationVirtualPath: String): ArrayList<String> {
        val reveal = ArrayList<String>(localItems.size)
        val normalizedDestination = destinationVirtualPath.replace('\\', '/').trimEnd('/').ifEmpty { "/" }
        localItems.forEach { item ->
            val name = item.name.ifBlank { item.path.getFilenameFromPath() }
            if (name.isBlank()) return@forEach
            val fullPath = if (normalizedDestination == "/") {
                "/$name"
            } else {
                "$normalizedDestination/$name"
            }
            reveal.add(fullPath)
        }
        return reveal
    }

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

    fun updateChildCount(path: String, count: Int) {
        val position = getItemKeyPosition(path.hashCode())
        val item = listItems.getOrNull(position) ?: return
        item.children = count
        notifyItemChanged(position, Unit)
    }

    fun isASectionTitle(position: Int) = listItems.getOrNull(position)?.isSectionTitle ?: false

    fun isGridTypeDivider(position: Int) = listItems.getOrNull(position)?.isGridTypeDivider ?: false

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            val icon = Binding.getByItemViewType(holder.itemViewType, isListViewType)
                .bind(holder.itemView).itemIcon
            if (icon != null) {
                Glide.with(activity).clear(icon)
            }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        clearPendingHighlightClear()
        transientHighlightKeys.clear()
        super.onDetachedFromRecyclerView(recyclerView)
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

                itemDetails?.setTextColor(textColor)
                itemDetails?.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)

                itemDate?.setTextColor(textColor)
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
                    itemDetails?.text = listItem.size.formatSize()
                    itemDate?.beVisible()
                    itemDate?.text = listItem.modified.formatDate(activity, dateFormat, timeFormat)

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
            }
        }
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
    }

    private class ItemSectionBindingAdapter(val binding: ItemSectionBinding) : ItemViewBinding {
        override val itemFrame: FrameLayout = binding.itemFrame
        override val itemName: TextView? = null
        override val itemIcon: ImageView = binding.itemIcon
        override val itemDetails: TextView? = null
        override val itemDate: TextView? = null
        override val itemCheck: ImageView? = null
        override val itemSection: TextView = binding.itemSection
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

        override fun getRoot(): View = binding.root
    }
}
