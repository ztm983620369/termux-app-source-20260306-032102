package org.fossify.filemanager.controllers

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.viewpager.widget.ViewPager
import com.stericson.RootTools.RootTools
import com.termux.sessionsync.SavedSshProfileStore
import com.termux.sessionsync.SessionFileCoordinator
import com.termux.sessionsync.SessionFileMode
import com.termux.sessionsync.SessionTransport
import com.termux.sshconnectioncore.SshPendingTrustRecord
import com.termux.sshconnectioncore.SshTrustRecord
import com.termux.workspaceshell.model.WorkspaceShellState
import com.termux.workspaceshell.ui.WorkspaceChromePalette
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.checkWhatsNew
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getFilePublicUri
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getRealPathFromURI
import org.fossify.commons.extensions.getStorageDirectories
import org.fossify.commons.extensions.getTimeFormat
import org.fossify.commons.extensions.handleHiddenFolderPasswordProtection
import org.fossify.commons.extensions.hasOTGConnected
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.isPathOnSD
import org.fossify.commons.extensions.launchMoreAppsFromUsIntent
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.extensions.sdCardPath
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.LICENSE_AUTOFITTEXTVIEW
import org.fossify.commons.helpers.LICENSE_GESTURE_VIEWS
import org.fossify.commons.helpers.LICENSE_GLIDE
import org.fossify.commons.helpers.LICENSE_PATTERN
import org.fossify.commons.helpers.LICENSE_REPRINT
import org.fossify.commons.helpers.LICENSE_ZIP4J
import org.fossify.commons.helpers.PERMISSION_WRITE_STORAGE
import org.fossify.commons.helpers.TAB_FILES
import org.fossify.commons.helpers.TAB_RECENT_FILES
import org.fossify.commons.helpers.TAB_STORAGE_ANALYSIS
import org.fossify.commons.helpers.VIEW_TYPE_GRID
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FAQItem
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.Release
import org.fossify.filemanager.BuildConfig
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.SettingsActivity
import org.fossify.filemanager.activities.SimpleActivity
import org.fossify.filemanager.adapters.ViewPagerAdapter
import org.fossify.filemanager.databinding.FmActivityMainBinding
import org.fossify.filemanager.dialogs.ChangeSortingDialog
import org.fossify.filemanager.dialogs.ChangeViewTypeDialog
import org.fossify.filemanager.dialogs.InsertFilenameDialog
import org.fossify.filemanager.extensions.config
import org.fossify.filemanager.extensions.tryOpenPathIntent
import org.fossify.filemanager.fragments.ItemsFragment
import org.fossify.filemanager.fragments.MyViewPagerFragment
import org.fossify.filemanager.fragments.RecentsFragment
import org.fossify.filemanager.fragments.StorageFragment
import org.fossify.filemanager.fragments.WorkspaceFilesFragment
import org.fossify.filemanager.helpers.FavoriteHelper
import org.fossify.filemanager.helpers.MAX_COLUMN_COUNT
import org.fossify.filemanager.helpers.NavigatorFolderHelper
import org.fossify.filemanager.helpers.RootHelpers
import org.fossify.filemanager.helpers.SessionSelfTestRunner
import org.fossify.filemanager.helpers.TermuxPathScope
import org.fossify.filemanager.interfaces.FileManagerControllerCommands
import org.fossify.filemanager.interfaces.FileManagerDependencies
import org.fossify.filemanager.interfaces.FileManagerEnvironment
import org.fossify.filemanager.interfaces.FileManagerExternalActions
import org.fossify.filemanager.interfaces.FileManagerResultHandler
import org.fossify.filemanager.interfaces.ItemOperationsListener
import java.io.File

class FileManagerController(
    private val activity: SimpleActivity,
    private val binding: FmActivityMainBinding,
    private val intentProvider: () -> Intent,
    private val externalActions: FileManagerExternalActions,
    private val enableEdgeToEdge: Boolean = true
) : FileManagerEnvironment, FileManagerControllerCommands, FileManagerResultHandler {
    companion object {
        private const val BACK_PRESS_TIMEOUT = 5000
        private const val PICKED_PATH = "picked_path"
        private const val WORKSPACE_FILES_STATE = "workspace_files_state"
        private const val MENU_SWITCH_CORE_NAV = 10001
        private const val MENU_SWITCH_FILES = 10002
        private const val MENU_SWITCH_RECENTS = 10003
        private const val MENU_SWITCH_STORAGE = 10004
    }

    private var wasBackJustPressed = false
    private var mTabsToShow = ArrayList<Int>()
    private var mainFabMenu: PopupWindow? = null
    private val termuxRootPath: String by lazy { TermuxPathScope.termuxRootPath(activity) }
    private val termuxHomePath: String by lazy { TermuxPathScope.termuxHomePath(activity) }

    private var mStoredFontSize = 0
    private var mStoredDateFormat = ""
    private var mStoredTimeFormat = ""
    private var mStoredShowTabs = 0
    private val mSessionFileCoordinator = SessionFileCoordinator.getInstance()
    private var mSelectedSessionId: String? = null
    private var mInitialSessionApplied = false
    private var mSwitchingSession = false
    private val fileManagerDependencies by lazy {
        FileManagerDependencies(
            environment = this,
            controllerCommands = this,
            resultHandler = this
        )
    }

    val rootView: View
        get() = binding.root

    fun attachTo(container: ViewGroup) {
        if (binding.root.parent != container) {
            (binding.root.parent as? ViewGroup)?.removeView(binding.root)
            container.removeAllViews()
            container.addView(
                binding.root,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
    }

    fun onCreate(savedInstanceState: Bundle?) {
        activity.isSearchBarEnabled = false
        mSessionFileCoordinator.initialize(activity)
        mSelectedSessionId = mSessionFileCoordinator.getSelectedSessionKey(activity)
        setupOptionsMenu()
        applyContainerColors()
        syncWorkspaceChrome()
        refreshMenuItems()
        val scopedHome = clampToVisibleTermuxPath(activity.config.homeFolder, termuxHomePath)
        if (activity.config.homeFolder != scopedHome) {
            activity.config.homeFolder = scopedHome
        }
        mTabsToShow = getTabsList()

        if (!activity.config.wasStorageAnalysisTabAdded) {
            activity.config.wasStorageAnalysisTabAdded = true
            if (activity.config.showTabs and TAB_STORAGE_ANALYSIS == 0) {
                activity.config.showTabs += TAB_STORAGE_ANALYSIS
            }
        }

        storeStateVariables()
        applyIntentTabRestrictions()
        setupMainFab()
        if (enableEdgeToEdge) {
            activity.setupEdgeToEdge(
                padBottomSystem = listOf(binding.mainViewPager),
                moveBottomSystem = listOf(binding.mainFab, binding.mainMoreFab, binding.mainSearchFab)
            )
        }

        if (savedInstanceState == null) {
            activity.config.temporarilyShowHidden = false
            initFragments()
            tryInitFileManager()
            checkWhatsNewDialog()
            checkIfRootAvailable()
            checkInvalidFavorites()
        }
    }

    fun onResume() {
        if (mStoredShowTabs != activity.config.showTabs) {
            activity.config.lastUsedViewPagerPage = 0
            System.exit(0)
            return
        }

        applyContainerColors()
        refreshMenuItems()
        updateMenuColors()
        syncWorkspaceChrome()

        getAllFragments().forEach {
            it?.onResume(activity.getProperTextColor())
        }

        if (mStoredFontSize != activity.config.fontSize) {
            getAllFragments().forEach {
                (it as? ItemOperationsListener)?.setupFontSize()
            }
        }

        if (mStoredDateFormat != activity.config.dateFormat || mStoredTimeFormat != activity.getTimeFormat()) {
            getAllFragments().forEach {
                (it as? ItemOperationsListener)?.setupDateTimeFormat()
            }
        }

        if (binding.mainViewPager.adapter == null) {
            initFragments()
        }

        if (!mInitialSessionApplied && binding.mainViewPager.adapter != null) {
            mInitialSessionApplied = true
        }
    }

    fun onHostTabVisible() {
        applyContainerColors()
        updateMenuColors()
        syncWorkspaceChrome()
        refreshMenuItems()
    }

    fun onPause() {
        storeStateVariables()
        activity.config.lastUsedViewPagerPage = binding.mainViewPager.currentItem
    }

    fun onStop() {
        getFilesFragment()?.flushWorkspaceSessionPersistence()
    }

    fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PICKED_PATH, getFilesFragment()?.activeItemsFragment()?.currentPath ?: "")
        getFilesFragment()?.saveWorkspaceState()?.let {
            outState.putBundle(WORKSPACE_FILES_STATE, it)
        }
    }

    fun onRestoreInstanceState(savedInstanceState: Bundle) {
        val workspaceState = savedInstanceState.getBundle(WORKSPACE_FILES_STATE)
        val path = savedInstanceState.getString(PICKED_PATH).takeIf { !it.isNullOrBlank() } ?: NavigatorFolderHelper.rootPath(activity)

        if (binding.mainViewPager.adapter == null) {
            binding.mainViewPager.onGlobalLayout {
                if (workspaceState != null) {
                    getFilesFragment()?.restoreWorkspaceState(workspaceState)
                } else {
                    openPath(path, true)
                }
                syncWorkspaceChrome()
            }
        } else {
            if (workspaceState != null) {
                getFilesFragment()?.restoreWorkspaceState(workspaceState)
            } else {
                openPath(path, true)
            }
            syncWorkspaceChrome()
        }
    }

    fun onBackPressedCompat(): Boolean {
        val currentFragment = getCurrentFragment()
        if (binding.workspaceChrome.isSearchVisible()) {
            closeSearchPanel(clearQuery = true)
            return true
        } else if (currentFragment is RecentsFragment || currentFragment is StorageFragment) {
            return false
        } else if (currentFragment is WorkspaceFilesFragment) {
            if (currentFragment.handleBackPressedWithinWorkspaces()) {
                syncWorkspaceChrome()
                return true
            }
            val currentPath = currentFragment.activePath().trimEnd('/')
            val navigatorRoot = NavigatorFolderHelper.rootPath(activity).trimEnd('/')
            val atRoot = currentPath.isEmpty() || currentPath == navigatorRoot
            if (atRoot) {
                if (!wasBackJustPressed && activity.config.pressBackTwice) {
                    wasBackJustPressed = true
                    activity.toast(R.string.press_back_again)
                    Handler().postDelayed({
                        wasBackJustPressed = false
                    }, BACK_PRESS_TIMEOUT.toLong())
                    return true
                } else {
                    activity.appLockManager.lock()
                    activity.finish()
                    return true
                }
            }
        }

        return false
    }

    fun openPath(path: String, forceRefresh: Boolean = false) {
        var newPath = path
        val file = File(path)
        if (file.exists() && !file.isDirectory) {
            newPath = file.parent ?: getPreferredStartPath()
        }

        val scopedPath = clampToVisibleTermuxPath(newPath, getPreferredStartPath())
        getFilesFragment()?.openManagedPath(scopedPath, forceRefresh)
        syncWorkspaceChrome()
    }
    override fun isTermuxScopedFileManager(): Boolean = true

    override fun showSessionSwitcher() {
        mSelectedSessionId = mSessionFileCoordinator.getSelectedSessionKey(activity)
        val targets = mSessionFileCoordinator.listTargets(activity)
        val items = ArrayList<RadioItem>(targets.size + 1)
        var checkedIndex = 0

        items.add(RadioItem(0, "\u672c\u5730\u76ee\u5f55", SessionFileCoordinator.LOCAL_TARGET_KEY))
        if (mSelectedSessionId == null) checkedIndex = 0

        targets.forEachIndexed { index, target ->
            val entry = target.entry
            val title = buildString {
                append(entry.displayName)
                append("  -  ")
                append(transportLabelCn(entry.transport))
                if (target.active) append("  -  \u5df2\u9009\u4e2d")
            }
            items.add(RadioItem(index + 1, title, target.key))

            if (!mSelectedSessionId.isNullOrEmpty() && mSelectedSessionId == target.key) {
                checkedIndex = index + 1
            } else if (mSelectedSessionId.isNullOrEmpty() && target.active) {
                checkedIndex = index + 1
            }
        }

        RadioGroupDialog(activity, items, checkedIndex, R.string.app_name) {
            val target = it.toString()
            mSelectedSessionId = if (target == SessionFileCoordinator.LOCAL_TARGET_KEY) null else target
            mSessionFileCoordinator.setSelectedSessionKey(activity, mSelectedSessionId)
            applySelectedSessionContext(forceRefresh = true)
        }
    }

    override fun closeActiveWorkspaceTabIfPossible(): Boolean {
        val filesFragment = getFilesFragment() ?: return false
        val closed = filesFragment.closeActiveWorkspaceTabIfPossible()
        if (closed) {
            syncWorkspaceChrome()
            refreshMenuItems()
        }
        return closed
    }

    override fun toggleMainFabMenu() {
        openCreateNew()
    }

    override fun createDocumentConfirmed(path: String) {
        val filename = intentProvider().getStringExtra(Intent.EXTRA_TITLE) ?: ""
        if (filename.isEmpty()) {
            InsertFilenameDialog(activity, path.ifBlank { getPreferredStartPath() }) { newFilename ->
                finishCreateDocumentIntent(path, newFilename)
            }
        } else {
            finishCreateDocumentIntent(path, filename)
        }
    }

    override fun openPathAndHighlight(targetPath: String, highlightPaths: ArrayList<String>) {
        val filesIndex = getEffectiveTabs().indexOf(TAB_FILES)
        if (filesIndex != -1 && binding.mainViewPager.currentItem != filesIndex) {
            binding.mainViewPager.currentItem = filesIndex
        }
        getFilesFragment()?.openPathAndHighlight(targetPath, highlightPaths)
        syncWorkspaceChrome()
        refreshMenuItems()
    }

    override fun installDownloadedApk(path: String, deleteAfterInstall: Boolean) {
        externalActions.installDownloadedApk(path, deleteAfterInstall)
    }

    override fun pickedPath(path: String) {
        val resultIntent = Intent()
        val uri = activity.getFilePublicUri(File(path), BuildConfig.APPLICATION_ID)
        val type = path.getMimeType()
        resultIntent.setDataAndType(uri, type)
        resultIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        activity.setResult(Activity.RESULT_OK, resultIntent)
        activity.finish()
    }

    override fun pickedPaths(paths: ArrayList<String>) {
        val newPaths = paths.map { activity.getFilePublicUri(File(it), BuildConfig.APPLICATION_ID) } as ArrayList
        val clipData = ClipData("Attachment", arrayOf(paths.getMimeType()), ClipData.Item(newPaths.removeAt(0)))

        newPaths.forEach {
            clipData.addItem(ClipData.Item(it))
        }

        Intent().apply {
            this.clipData = clipData
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            activity.setResult(Activity.RESULT_OK, this)
        }
        activity.finish()
    }

    override fun pickedRingtone(path: String) {
        val uri = activity.getFilePublicUri(File(path), BuildConfig.APPLICATION_ID)
        val type = path.getMimeType()
        Intent().apply {
            setDataAndType(uri, type)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, uri)
            activity.setResult(Activity.RESULT_OK, this)
        }
        activity.finish()
    }

    override fun refreshMenuItems() {
        val currentFragment = getCurrentFragment() ?: return
        val isCreateDocumentIntent = intentProvider().action == Intent.ACTION_CREATE_DOCUMENT
        val currentItems = activeItemsFragment()
        val currentPath = currentItems?.currentPath ?: currentFragment.currentPath
        val currentViewType = activity.config.getFolderViewType(currentPath)
        val isNavigator = currentItems != null && NavigatorFolderHelper.isNavigatorPath(activity, currentPath)
        val isFavorite = currentItems != null && activity.config.isFavorite(currentPath)

        binding.workspaceChrome.toolbar().menu.apply {
            findItem(R.id.sort).isVisible = currentItems != null && !isNavigator
            findItem(R.id.change_view_type).isVisible = currentFragment !is StorageFragment

            findItem(R.id.add_favorite).isVisible = currentItems != null && !isNavigator && !isFavorite
            findItem(R.id.remove_favorite).isVisible = currentItems != null && !isNavigator && isFavorite
            findItem(R.id.go_to_favorite).isVisible = false
            findItem(R.id.go_to_favorite).title = NavigatorFolderHelper.displayTitle()

            findItem(R.id.toggle_filename).isVisible = currentItems != null && currentViewType == VIEW_TYPE_GRID && !isNavigator
            findItem(R.id.go_home).isVisible = false
            findItem(R.id.set_as_home).isVisible = currentItems != null && !isNavigator && currentPath != activity.config.homeFolder
            findItem(R.id.toggle_termux_storage).isVisible = false
            findItem(R.id.toggle_termux_system_dirs).isVisible = currentItems != null && !isCreateDocumentIntent
            findItem(R.id.toggle_termux_system_dirs).title =
                activity.getString(
                    if (activity.config.showTermuxSystemDirs) R.string.hide_termux_system_dirs
                    else R.string.show_termux_system_dirs
                )

            findItem(R.id.open_in_terminal).isVisible = currentItems != null && !isCreateDocumentIntent && !isNavigator

            findItem(R.id.temporarily_show_hidden).isVisible = currentItems != null && !activity.config.shouldShowHidden() && !isNavigator
            findItem(R.id.stop_showing_hidden).isVisible = currentItems != null && activity.config.temporarilyShowHidden && !isNavigator

            findItem(R.id.column_count).isVisible = currentItems != null && currentViewType == VIEW_TYPE_GRID && !isNavigator

            findItem(R.id.more_apps_from_us).isVisible = !activity.resources.getBoolean(R.bool.hide_google_relations)
            findItem(R.id.self_test).isVisible = !isCreateDocumentIntent
            findItem(R.id.self_test).title = "\u5de5\u4e1a\u7ea7\u81ea\u68c0"
            findItem(R.id.manage_session_trust).isVisible =
                !isCreateDocumentIntent && mSessionFileCoordinator.resolveSelectedEntry(activity) != null
            findItem(R.id.settings).isVisible = !isCreateDocumentIntent
            findItem(R.id.about).isVisible = !isCreateDocumentIntent
        }
    }

    override fun updateFragmentColumnCounts() {
        getAllFragments().forEach {
            (it as? ItemOperationsListener)?.columnCountChanged()
        }
    }

    override fun openedDirectory() {
        if (binding.workspaceChrome.isSearchVisible()) {
            closeSearchPanel(clearQuery = true)
        }
    }

    private fun openInTerminal(path: String) {
        externalActions.openInTerminal(path)
    }

    private fun setupOptionsMenu() {
        binding.workspaceChrome.apply {
            toolbar().inflateMenu(R.menu.menu)
            tabsView().onTabSelectedListener = object : com.termux.workspaceshell.ui.WorkspaceTabsBarView.OnTabSelectedListener {
                override fun onTabSelected(index: Int, tab: com.termux.workspaceshell.model.WorkspaceTabModel) {
                    getFilesFragment()?.selectWorkspaceTab(tab.id)
                    syncWorkspaceChrome()
                    refreshMenuItems()
                }
            }
            tabsView().onTabCloseListener = object : com.termux.workspaceshell.ui.WorkspaceTabsBarView.OnTabCloseListener {
                override fun onTabClose(index: Int, tab: com.termux.workspaceshell.model.WorkspaceTabModel) {
                    getFilesFragment()?.closeWorkspaceTab(tab.id)
                    syncWorkspaceChrome()
                    refreshMenuItems()
                }
            }
            onSearchQueryChangedListener = { text ->
                getCurrentFragment()?.searchQueryChanged(text)
                syncWorkspaceChrome()
            }
            onSearchCloseListener = {
                (getCurrentFragment() as? WorkspaceFilesFragment)?.setSearchVisible(false)
                syncWorkspaceChrome()
            }
            toolbar().setOnMenuItemClickListener { menuItem -> handleChromeMenuAction(menuItem.itemId) }
        }
    }

    private fun toggleTermuxStorage() {
        openPath(getPreferredStartPath(), forceRefresh = true)
    }

    private fun getPreferredStartPath(): String {
        return clampToVisibleTermuxPath(activity.config.homeFolder, termuxHomePath)
    }

    private fun isInTermuxStorage(path: String): Boolean {
        return TermuxPathScope.isVisibleInFileManager(activity, path)
    }

    private fun clampToVisibleTermuxPath(path: String?, fallback: String): String {
        return TermuxPathScope.clampVisiblePath(activity, path, fallback)
    }

    private fun updateMenuColors() {
        val backgroundColor = activity.getProperBackgroundColor()
        val surfaceColor = blendColor(activity.getProperPrimaryColor(), backgroundColor, 0.12f)
        val palette = WorkspaceChromePalette(
            backgroundColor = backgroundColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = activity.getProperTextColor(),
            accentColor = activity.getProperPrimaryColor()
        )
        binding.workspaceChrome.setPalette(palette)
    }

    private fun applyContainerColors() {
        val backgroundColor = activity.getProperBackgroundColor()
        binding.root.setBackgroundColor(backgroundColor)
        binding.mainCoordinator.setBackgroundColor(backgroundColor)
        binding.mainHolder.setBackgroundColor(backgroundColor)
        binding.mainViewPager.setBackgroundColor(backgroundColor)
        getAllFragments().forEach { fragment ->
            fragment?.setBackgroundColor(backgroundColor)
        }
        binding.mainSearchFab.backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getProperPrimaryColor())
        binding.mainMoreFab.backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getProperPrimaryColor())
    }

    private fun storeStateVariables() {
        activity.config.apply {
            mStoredFontSize = fontSize
            mStoredDateFormat = dateFormat
            mStoredTimeFormat = activity.getTimeFormat()
            mStoredShowTabs = showTabs
        }
    }

    private fun tryInitFileManager() {
        val hadPermission = activity.hasStoragePermission()
        activity.handleStoragePermission {
            checkOTGPath()
            if (it) {
                if (binding.mainViewPager.adapter == null) {
                    initFragments()
                }

                binding.mainViewPager.onGlobalLayout {
                    initFileManager(!hadPermission)
                }
            } else {
                activity.toast(R.string.no_storage_permissions)
                activity.finish()
            }
        }
    }

    private fun initFileManager(refreshRecents: Boolean) {
        val intent = intentProvider()
        val hasExplicitPath = intent.action == Intent.ACTION_VIEW && intent.data != null
        if (hasExplicitPath) {
            val data = intent.data
            if (data?.scheme == "file") {
                openPath(data.path!!)
            } else {
                val path = activity.getRealPathFromURI(data!!)
                if (path != null) {
                    openPath(path)
                } else {
                    getFilesFragment()?.openHomeWorkspace(forceRefresh = true)
                }
            }

            if (!File(data.path!!).isDirectory && isInTermuxStorage(data.path ?: "")) {
                activity.tryOpenPathIntent(data.path!!, false, finishActivity = true)
            }

            binding.mainViewPager.currentItem = 0
        } else {
            val restoredWorkspace = shouldRestorePersistedWorkspace(intent) &&
                getFilesFragment()?.restorePersistedWorkspaceSession() == true
            if (!restoredWorkspace) {
                getFilesFragment()?.openHomeWorkspace(forceRefresh = true)
            }
        }

        if (refreshRecents) {
            getRecentsFragment()?.refreshFragment()
        }

        if (!hasExplicitPath && !mInitialSessionApplied) {
            mInitialSessionApplied = true
        }
        syncWorkspaceChrome()
    }

    private fun shouldRestorePersistedWorkspace(intent: Intent): Boolean {
        return when (intent.action) {
            null,
            Intent.ACTION_MAIN -> true
            else -> false
        }
    }

    private fun initFragments() {
        binding.mainViewPager.apply {
            adapter = ViewPagerAdapter(activity, mTabsToShow, intentProvider, fileManagerDependencies)
            offscreenPageLimit = 2
            addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageScrollStateChanged(state: Int) {}

                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

                override fun onPageSelected(position: Int) {
                    getAllFragments().forEach {
                        (it as? ItemOperationsListener)?.finishActMode()
                    }
                    (getCurrentFragment() as? RecentsFragment)?.refreshFragment()
                    if (getCurrentFragment() !is WorkspaceFilesFragment) {
                        closeSearchPanel(clearQuery = false)
                    }
                    syncWorkspaceChrome()
                    refreshMenuItems()
                }
            })
            currentItem = activity.config.lastUsedViewPagerPage

            onGlobalLayout {
                syncWorkspaceChrome()
                refreshMenuItems()
            }
        }
    }

    private fun applyIntentTabRestrictions() {
        val action = intentProvider().action
        val isPickFileIntent = action == RingtoneManager.ACTION_RINGTONE_PICKER ||
            action == Intent.ACTION_GET_CONTENT ||
            action == Intent.ACTION_PICK
        val isCreateDocumentIntent = action == Intent.ACTION_CREATE_DOCUMENT

        if (isPickFileIntent) {
            mTabsToShow.remove(TAB_STORAGE_ANALYSIS)
            if (mTabsToShow.none { it and activity.config.showTabs != 0 }) {
                activity.config.showTabs = TAB_FILES
                mStoredShowTabs = TAB_FILES
                mTabsToShow = arrayListOf(TAB_FILES)
            }
        } else if (isCreateDocumentIntent) {
            mTabsToShow = arrayListOf(TAB_FILES)
        }
    }

    private fun getEffectiveTabs(): List<Int> {
        return mTabsToShow.filter { it and activity.config.showTabs != 0 }
    }

    private fun setupMainFab() {
        updateMainFabIcon(intentProvider().action == Intent.ACTION_CREATE_DOCUMENT)
        binding.mainFab.setOnClickListener {
            val currentPath = getCurrentFragment()?.currentPath.orEmpty()
            if (intentProvider().action == Intent.ACTION_CREATE_DOCUMENT) {
                createDocumentConfirmed(currentPath)
            } else {
                openCreateNew()
            }
        }
        binding.mainMoreFab.setOnClickListener {
            showBottomOverflowMenu()
        }
        binding.mainSearchFab.setOnClickListener {
            val currentFragment = getCurrentFragment() ?: return@setOnClickListener
            if (currentFragment !is WorkspaceFilesFragment) {
                return@setOnClickListener
            }
            val nextVisible = !currentFragment.isSearchVisible()
            currentFragment.setSearchVisible(nextVisible)
            if (!nextVisible) {
                closeSearchPanel(clearQuery = true)
            } else {
                binding.workspaceChrome.setSearchVisible(true)
                binding.workspaceChrome.focusSearch()
            }
            syncWorkspaceChrome()
        }
    }

    private fun updateMainFabIcon(isCreateDocumentIntent: Boolean) {
        val iconId = if (isCreateDocumentIntent) R.drawable.ic_check_vector else R.drawable.ic_plus_vector
        val icon = activity.resources.getColoredDrawableWithColor(iconId, activity.getProperPrimaryColor().getContrastColor())
        binding.mainFab.setImageDrawable(icon)
    }

    private fun showBottomOverflowMenu() {
        val popup = PopupMenu(activity, binding.mainMoreFab, Gravity.END)
        val menu = popup.menu
        var order = 0
        val currentFragment = getCurrentFragment() ?: return
        val effectiveTabs = getEffectiveTabs()
        val selectedTabId = effectiveTabs.getOrNull(binding.mainViewPager.currentItem)

        if (currentFragment is WorkspaceFilesFragment) {
            menu.add(0, MENU_SWITCH_CORE_NAV, order++, NavigatorFolderHelper.displayTitle())
        }

        fun addSwitchItem(itemId: Int, tabId: Int, title: String) {
            if (!effectiveTabs.contains(tabId)) return
            val item = menu.add(1, itemId, order++, title)
            item.isCheckable = true
            item.isChecked = selectedTabId == tabId
        }

        addSwitchItem(MENU_SWITCH_FILES, TAB_FILES, activity.getString(R.string.files_tab))
        addSwitchItem(MENU_SWITCH_RECENTS, TAB_RECENT_FILES, activity.getString(R.string.recents))
        addSwitchItem(MENU_SWITCH_STORAGE, TAB_STORAGE_ANALYSIS, activity.getString(R.string.storage))
        menu.setGroupCheckable(1, true, true)

        val toolbarMenu = binding.workspaceChrome.toolbar().menu
        for (index in 0 until toolbarMenu.size()) {
            val sourceItem = toolbarMenu.getItem(index)
            if (!sourceItem.isVisible) continue
            if (sourceItem.itemId == R.id.go_home || sourceItem.itemId == R.id.go_to_favorite) continue
            val target = menu.add(2, sourceItem.itemId, order++, sourceItem.title)
            sourceItem.icon?.let { target.icon = it }
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SWITCH_CORE_NAV -> {
                    goToFavorite()
                    true
                }
                MENU_SWITCH_FILES -> {
                    switchBottomView(TAB_FILES)
                    true
                }
                MENU_SWITCH_RECENTS -> {
                    switchBottomView(TAB_RECENT_FILES)
                    true
                }
                MENU_SWITCH_STORAGE -> {
                    switchBottomView(TAB_STORAGE_ANALYSIS)
                    true
                }
                else -> handleChromeMenuAction(item.itemId)
            }
        }
        popup.show()
    }

    private fun switchBottomView(tabId: Int) {
        val index = getEffectiveTabs().indexOf(tabId)
        if (index != -1) {
            closeSearchPanel(clearQuery = false)
            binding.mainViewPager.currentItem = index
        }
    }

    private fun showMainFabMenu() {
        val effectiveTabs = getEffectiveTabs()
        if (effectiveTabs.size <= 1) {
            return
        }

        val content = activity.layoutInflater.inflate(R.layout.fm_popup_main_fab_menu, null)
        val createRow = content.findViewById<View>(R.id.main_fab_menu_create)
        val createIcon = content.findViewById<ImageView>(R.id.main_fab_menu_create_icon)
        val createLabel = content.findViewById<TextView>(R.id.main_fab_menu_create_label)

        val filesRow = content.findViewById<View>(R.id.main_fab_menu_files)
        val filesIcon = content.findViewById<ImageView>(R.id.main_fab_menu_files_icon)
        val filesLabel = content.findViewById<TextView>(R.id.main_fab_menu_files_label)

        val recentsRow = content.findViewById<View>(R.id.main_fab_menu_recents)
        val recentsIcon = content.findViewById<ImageView>(R.id.main_fab_menu_recents_icon)
        val recentsLabel = content.findViewById<TextView>(R.id.main_fab_menu_recents_label)

        val storageRow = content.findViewById<View>(R.id.main_fab_menu_storage)
        val storageIcon = content.findViewById<ImageView>(R.id.main_fab_menu_storage_icon)
        val storageLabel = content.findViewById<TextView>(R.id.main_fab_menu_storage_label)

        val selectedIndex = binding.mainViewPager.currentItem
        val selectedTabId = effectiveTabs.getOrNull(selectedIndex)
        val textColor = activity.getProperTextColor()
        val selectedColor = activity.getProperPrimaryColor()

        fun setRow(
            tabId: Int,
            row: View,
            icon: ImageView,
            label: TextView,
            iconRes: Int
        ) {
            val index = effectiveTabs.indexOf(tabId)
            val visible = index != -1
            row.beGoneIf(!visible)
            if (!visible) return

            val isSelected = tabId == selectedTabId
            val color = if (isSelected) selectedColor else textColor
            icon.setImageDrawable(activity.resources.getColoredDrawableWithColor(iconRes, color))
            label.setTextColor(color)
            row.setOnClickListener {
                closeSearchPanel(clearQuery = false)
                binding.mainViewPager.currentItem = index
                mainFabMenu?.dismiss()
            }
        }

        val canCreate = effectiveTabs.contains(TAB_FILES) && intentProvider().action != Intent.ACTION_CREATE_DOCUMENT
        createRow.beGoneIf(!canCreate)
        if (canCreate) {
            createIcon.setImageDrawable(activity.resources.getColoredDrawableWithColor(R.drawable.ic_plus_vector, textColor))
            createLabel.setTextColor(textColor)
            createRow.setOnClickListener {
                mainFabMenu?.dismiss()
                openCreateNew()
            }
        }

        setRow(TAB_FILES, filesRow, filesIcon, filesLabel, R.drawable.ic_folder_vector)
        setRow(TAB_RECENT_FILES, recentsRow, recentsIcon, recentsLabel, R.drawable.ic_clock_vector)
        setRow(TAB_STORAGE_ANALYSIS, storageRow, storageIcon, storageLabel, R.drawable.ic_storage_vector)

        val popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = activity.resources.displayMetrics.density * 8f
            setOnDismissListener {
                updateMainFabIcon(intentProvider().action == Intent.ACTION_CREATE_DOCUMENT)
            }
        }
        mainFabMenu = popup

        val closeIcon = activity.resources.getColoredDrawableWithColor(R.drawable.ic_cross_vector, activity.getProperPrimaryColor().getContrastColor())
        binding.mainFab.setImageDrawable(closeIcon)

        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val spacing = (activity.resources.displayMetrics.density * 8).toInt()
        val yOff = -(content.measuredHeight + binding.mainFab.height + spacing)
        popup.showAsDropDown(binding.mainFab, 0, yOff, Gravity.END)
    }

    private fun openCreateNew() {
        val effectiveTabs = getEffectiveTabs()
        val filesIndex = effectiveTabs.indexOf(TAB_FILES)
        if (filesIndex == -1) {
            return
        }

        if (binding.mainViewPager.currentItem != filesIndex) {
            binding.mainViewPager.currentItem = filesIndex
            binding.mainViewPager.post {
                getFilesFragment()?.activeItemsFragment()?.showCreateNewItemDialog()
            }
        } else {
            getFilesFragment()?.activeItemsFragment()?.showCreateNewItemDialog()
        }
    }

    private fun checkOTGPath() {
        if (mTabsToShow.size == 1 && mTabsToShow.contains(TAB_FILES)) return
        ensureBackgroundThread {
            if (!activity.config.wasOTGHandled && activity.hasPermission(PERMISSION_WRITE_STORAGE) && activity.hasOTGConnected() && activity.config.OTGPath.isEmpty()) {
                activity.getStorageDirectories().firstOrNull { it.trimEnd('/') != activity.internalStoragePath && it.trimEnd('/') != activity.sdCardPath }?.apply {
                    activity.config.wasOTGHandled = true
                    activity.config.OTGPath = trimEnd('/')
                }
            }
        }
    }

    private fun goHome() {
        if (getCurrentFragment() is WorkspaceFilesFragment) {
            getFilesFragment()?.openHomeWorkspace()
            syncWorkspaceChrome()
        }
    }

    private fun showSortingDialog() {
        val path = activeFilePath().ifBlank { return }
        ChangeSortingDialog(activity, path) {
            activeItemsFragment()?.refreshFragment()
        }
    }

    private fun addFavorite() {
        val path = activeFilePath().ifBlank { return }
        FavoriteHelper.showAddFavoriteDialog(activity) { remark ->
            activity.config.addFavorite(path, remark)
            refreshMenuItems()
            activeItemsFragment()?.refreshFragment()
        }
    }

    private fun removeFavorite() {
        val path = activeFilePath().ifBlank { return }
        activity.config.removeFavorite(path)
        refreshMenuItems()
    }

    private fun toggleFilenameVisibility() {
        activity.config.displayFilenames = !activity.config.displayFilenames
        getAllFragments().forEach {
            (it as? ItemOperationsListener)?.toggleFilenameVisibility()
        }
    }

    private fun changeColumnCount() {
        val items = ArrayList<RadioItem>()
        for (i in 1..MAX_COLUMN_COUNT) {
            items.add(RadioItem(i, activity.resources.getQuantityString(R.plurals.column_counts, i, i)))
        }

        val currentColumnCount = activity.config.fileColumnCnt
        RadioGroupDialog(activity, items, activity.config.fileColumnCnt) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                activity.config.fileColumnCnt = newColumnCount
                getAllFragments().forEach {
                    (it as? ItemOperationsListener)?.columnCountChanged()
                }
            }
        }
    }

    private fun goToFavorite() {
        openPath(NavigatorFolderHelper.rootPath(activity), forceRefresh = true)
    }

    private fun dp(value: Int): Int {
        return (activity.resources.displayMetrics.density * value).toInt()
    }

    private fun setAsHome() {
        val path = activeFilePath().ifBlank { return }
        activity.config.homeFolder = clampToVisibleTermuxPath(path, termuxHomePath)
        activity.toast(R.string.home_folder_updated)
    }

    private fun changeViewType() {
        val path = activeFilePath().ifBlank { return }
        ChangeViewTypeDialog(activity, path, activeItemsFragment() != null) {
            getAllFragments().forEach {
                it?.refreshFragment()
            }
        }
    }

    private fun tryToggleTemporarilyShowHidden() {
        if (activity.config.temporarilyShowHidden) {
            toggleTemporarilyShowHidden(false)
        } else {
            activity.handleHiddenFolderPasswordProtection {
                toggleTemporarilyShowHidden(true)
            }
        }
    }

    private fun toggleTemporarilyShowHidden(show: Boolean) {
        activity.config.temporarilyShowHidden = show
        getAllFragments().forEach {
            it?.refreshFragment()
        }
    }

    private fun runSessionIndustrialSelfTest() {
        if (activity.isFinishing || activity.isDestroyed) return

        val runningDialog = AlertDialog.Builder(activity)
            .setTitle("\u81ea\u68c0\u4e2d")
            .setMessage("\u6b63\u5728\u6267\u884c\u5168\u94fe\u8def\u81ea\u68c0\uff0c\u8bf7\u7a0d\u5019...")
            .setCancelable(false)
            .create()

        try {
            runningDialog.show()
        } catch (_: Exception) {
        }

        ensureBackgroundThread {
            val report = SessionSelfTestRunner.run(activity)
            activity.runOnUiThread {
                try {
                    if (runningDialog.isShowing) {
                        runningDialog.dismiss()
                    }
                } catch (_: Exception) {
                }
                showSessionSelfTestReport(report)
            }
        }
    }

    private fun showSessionSelfTestReport(report: SessionSelfTestRunner.Report) {
        val logView = TextView(activity).apply {
            text = report.content
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val scrollView = ScrollView(activity).apply {
            addView(
                logView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val title = if (report.success) {
            "\u81ea\u68c0\u5b8c\u6210\uff1a\u5168\u90e8\u901a\u8fc7"
        } else {
            "\u81ea\u68c0\u5b8c\u6210\uff1a\u53d1\u73b0\u95ee\u9898"
        }
        val summary = "\u68c0\u67e5${report.totalChecks}\u9879\uff0c\u901a\u8fc7${report.passedChecks}\u9879\uff0c\u5931\u8d25${report.failedChecks}\u9879"

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(summary)
            .setView(scrollView)
            .setPositiveButton("\u590d\u5236\u65e5\u5fd7") { _, _ ->
                activity.copyToClipboard(report.content)
            }
            .setNeutralButton("\u91cd\u65b0\u81ea\u68c0") { _, _ ->
                runSessionIndustrialSelfTest()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchSettings() {
        activity.hideKeyboard()
        activity.startActivity(Intent(activity.applicationContext, SettingsActivity::class.java))
    }

    private fun showCurrentSessionTrustDialog() {
        val entry = mSessionFileCoordinator.resolveSelectedEntry(activity)
        if (entry == null) {
            activity.toast("当前未选择远程服务器。")
            return
        }

        val records = ArrayList<SshTrustRecord>(mSessionFileCoordinator.listTrustedHostsForEntry(activity, entry))
        val pending = mSessionFileCoordinator.getPendingTrustForEntry(activity, entry)
        val message = StringBuilder()
            .append("服务器：").append(entry.displayName).append('\n').append('\n')
        if (pending != null) {
            message.append(if (pending.replacementRequired) "待替换指纹：" else "待批准指纹：")
                .append('\n')
                .append("• ").append(pending.algorithm).append('\n')
                .append("  ").append(pending.observedFingerprintSha256).append('\n')
            if (pending.existingFingerprintSha256.isNotEmpty()) {
                message.append("现有指纹：").append('\n')
                    .append("  ").append(pending.existingFingerprintSha256).append('\n')
            }
            message.append('\n')
        }
        if (records.isEmpty()) {
            message.append("当前没有已信任的主机指纹记录。")
        } else {
            message.append("已信任指纹：").append('\n')
            records.forEach { record ->
                message.append("• ").append(record.algorithm).append('\n')
                    .append("  ").append(record.fingerprintSha256).append('\n')
            }
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.manage_session_trust)
            .setMessage(message.toString())
            .setNegativeButton(android.R.string.cancel, null)
        if (pending != null) {
            builder.setNeutralButton(if (pending.replacementRequired) "替换指纹" else "批准指纹") { _, _ ->
                val approved = mSessionFileCoordinator.approvePendingTrustForEntry(activity, entry)
                if (approved) {
                    activity.toast(if (pending.replacementRequired) "已替换当前服务器指纹。" else "已批准当前服务器指纹。")
                } else {
                    activity.toast("未找到待处理的主机指纹。")
                }
            }
        }
        if (records.isNotEmpty()) {
            builder.setPositiveButton("清除指纹") { _, _ ->
                val cleared = mSessionFileCoordinator.clearTrustedHostForEntry(activity, entry)
                if (cleared) {
                    activity.toast("已清除当前服务器指纹，下次连接将重新建立信任。")
                } else {
                    activity.toast("未找到可清除的主机指纹。")
                }
            }
        }
        builder.show()
    }

    private fun launchAbout() {
        val licenses = LICENSE_GLIDE or LICENSE_PATTERN or LICENSE_REPRINT or LICENSE_GESTURE_VIEWS or LICENSE_AUTOFITTEXTVIEW or LICENSE_ZIP4J

        val faqItems = arrayListOf(
            FAQItem(R.string.faq_3_title_commons, R.string.faq_3_text_commons),
            FAQItem(R.string.faq_9_title_commons, R.string.faq_9_text_commons)
        )

        if (!activity.resources.getBoolean(R.bool.hide_google_relations)) {
            faqItems.add(FAQItem(R.string.faq_2_title_commons, R.string.faq_2_text_commons))
            faqItems.add(FAQItem(R.string.faq_6_title_commons, R.string.faq_6_text_commons))
            faqItems.add(FAQItem(R.string.faq_7_title_commons, R.string.faq_7_text_commons))
            faqItems.add(FAQItem(R.string.faq_10_title_commons, R.string.faq_10_text_commons))
        }

        activity.startAboutActivity(R.string.app_name, licenses, BuildConfig.VERSION_NAME, faqItems, true)
    }

    private fun checkIfRootAvailable() {
        ensureBackgroundThread {
            activity.config.isRootAvailable = RootTools.isRootAvailable()
            if (activity.config.isRootAvailable && activity.config.enableRootAccess) {
                RootHelpers(activity).askRootIfNeeded {
                    activity.config.enableRootAccess = it
                }
            }
        }
    }

    private fun checkInvalidFavorites() {
        ensureBackgroundThread {
            val snapshot = activity.config.favorites.toList()
            val virtualPrefix = "$termuxRootPath/.termux/sftp-virtual/"
            val mountPrefix = "$termuxRootPath/.termux/sftp-mounts/"
            snapshot.forEach { favoritePath ->
                val normalized = favoritePath.trim().trimEnd('/')
                val isTermuxScoped = isInTermuxStorage(normalized)
                // Virtual SFTP favorites are logical paths and may not exist as local files.
                // Keep them persistent across app restarts.
                val isRemoteWorkspacePath = normalized.startsWith(virtualPrefix) || normalized.startsWith(mountPrefix)
                val keep = isTermuxScoped && (isRemoteWorkspacePath || File(normalized).exists())
                if (!keep) {
                    activity.config.removeFavorite(favoritePath)
                }
            }
        }
    }

    private fun applySelectedSessionContext(forceRefresh: Boolean) {
        if (mSwitchingSession) return

        mSwitchingSession = true
        ensureBackgroundThread {
            val result = mSessionFileCoordinator.resolveSelectedRoot(activity)
            activity.runOnUiThread {
                mSwitchingSession = false
                mSelectedSessionId = mSessionFileCoordinator.getSelectedSessionKey(activity)

                if (result.success) {
                    if (result.mode == SessionFileMode.SFTP_PROTOCOL && result.messageCn.isNotBlank()) {
                        activity.toast(result.messageCn)
                    }
                    openPath(result.rootPath, forceRefresh)
                } else {
                    if (result.messageCn.isNotBlank()) {
                        activity.toast(result.messageCn)
                    }
                    val fallback = if (result.rootPath.isBlank()) getPreferredStartPath() else result.rootPath
                    openPath(fallback, forceRefresh)
                }
            }
        }
    }
    private fun transportLabelCn(transport: SessionTransport): String {
        return when (transport) {
            SessionTransport.LOCAL -> "\u672c\u5730"
            SessionTransport.SSH -> "SSH"
            SessionTransport.SSH_PERSIST -> "\u6301\u4e45\u5316"
            else -> "\u672a\u77e5"
        }
    }

    private fun resolveParentPathForNavigation(rawPath: String): String {
        val current = rawPath.trimEnd('/').ifEmpty { "/" }
        val localRoot = getPreferredStartPath()
        if (current == "/" || current == localRoot) return localRoot

        if (isVirtualWorkspaceRoot(current)) {
            return current
        }

        return File(current).parent?.trimEnd('/').orEmpty().ifEmpty { localRoot }
    }

    private fun isVirtualWorkspaceRoot(current: String): Boolean {
        if (!mSessionFileCoordinator.isVirtualPath(activity, current)) return false
        val virtualPrefix = "$termuxRootPath/.termux/sftp-virtual/"
        if (!current.startsWith(virtualPrefix)) return false
        val tail = current.removePrefix(virtualPrefix)
        return tail.isNotEmpty() && !tail.contains("/")
    }

    private fun toggleTermuxSystemDirs() {
        activity.config.showTermuxSystemDirs = !activity.config.showTermuxSystemDirs

        val currentPath = activeFilePath()
        if (!TermuxPathScope.isVisibleInFileManager(activity, currentPath)) {
            getFilesFragment()?.openHomeWorkspace(forceRefresh = true)
        } else {
            refreshMenuItems()
            activeItemsFragment()?.refreshFragment()
        }
        syncWorkspaceChrome()
    }

    private fun finishCreateDocumentIntent(path: String, filename: String) {
        val resultIntent = Intent()
        val uri = activity.getFilePublicUri(File(path, filename), BuildConfig.APPLICATION_ID)
        val type = path.getMimeType()
        resultIntent.setDataAndType(uri, type)
        resultIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        activity.setResult(Activity.RESULT_OK, resultIntent)
        activity.finish()
    }

    private fun getRecentsFragment() = activity.findViewById<RecentsFragment>(R.id.recents_fragment)
    private fun getFilesFragment(): WorkspaceFilesFragment? {
        val fragment = activity.findViewById<WorkspaceFilesFragment>(R.id.workspace_files_fragment)
        fragment?.workspaceStateChangedListener = {
            syncWorkspaceChrome()
            refreshMenuItems()
        }
        return fragment
    }
    private fun getStorageFragment() = activity.findViewById<StorageFragment>(R.id.storage_fragment)
    private fun getAllFragments(): ArrayList<MyViewPagerFragment<*>?> =
        arrayListOf(getFilesFragment(), getRecentsFragment(), getStorageFragment())

    private fun activeItemsFragment(): ItemsFragment? =
        (getCurrentFragment() as? WorkspaceFilesFragment)?.activeItemsFragment()

    private fun activeFilePath(): String = getFilesFragment()?.activeWorkspacePathForMenu().orEmpty()

    private fun handleChromeMenuAction(itemId: Int): Boolean {
        if (getCurrentFragment() == null) {
            return true
        }

        when (itemId) {
            R.id.go_home -> goHome()
            R.id.go_to_favorite -> goToFavorite()
            R.id.sort -> showSortingDialog()
            R.id.add_favorite -> addFavorite()
            R.id.remove_favorite -> removeFavorite()
            R.id.toggle_filename -> toggleFilenameVisibility()
            R.id.toggle_termux_storage -> toggleTermuxStorage()
            R.id.toggle_termux_system_dirs -> toggleTermuxSystemDirs()
            R.id.open_in_terminal -> openInTerminal(activeFilePath().ifBlank { return true })
            R.id.set_as_home -> setAsHome()
            R.id.change_view_type -> changeViewType()
            R.id.temporarily_show_hidden -> tryToggleTemporarilyShowHidden()
            R.id.stop_showing_hidden -> tryToggleTemporarilyShowHidden()
            R.id.column_count -> changeColumnCount()
            R.id.more_apps_from_us -> activity.launchMoreAppsFromUsIntent()
            R.id.self_test -> runSessionIndustrialSelfTest()
            R.id.manage_session_trust -> showCurrentSessionTrustDialog()
            R.id.settings -> launchSettings()
            R.id.about -> launchAbout()
            else -> return false
        }
        return true
    }

    private fun getCurrentFragment(): MyViewPagerFragment<*>? {
        val fragments = arrayListOf<MyViewPagerFragment<*>>()
        getEffectiveTabs().forEach { tab ->
            when (tab) {
                TAB_FILES -> getFilesFragment()?.let { fragments.add(it) }
                TAB_RECENT_FILES -> getRecentsFragment()?.let { fragments.add(it) }
                TAB_STORAGE_ANALYSIS -> getStorageFragment()?.let { fragments.add(it) }
            }
        }
        return fragments.getOrNull(binding.mainViewPager.currentItem)
    }

    private fun closeSearchPanel(clearQuery: Boolean) {
        binding.workspaceChrome.setSearchVisible(false, clearQuery = clearQuery)
        if (clearQuery) {
            getCurrentFragment()?.searchQueryChanged("")
        }
        getFilesFragment()?.setSearchVisible(false)
        syncWorkspaceChrome()
    }

    private fun syncWorkspaceChrome() {
        val currentFragment = getCurrentFragment()
        val isFilesSelected = currentFragment is WorkspaceFilesFragment
        binding.workspaceChrome.tabsView().visibility = if (isFilesSelected) View.VISIBLE else View.GONE
        binding.mainSearchFab.visibility = if (isFilesSelected) View.VISIBLE else View.GONE
        binding.mainMoreFab.visibility = if (currentFragment == null) View.GONE else View.VISIBLE

        if (isFilesSelected) {
            val filesFragment = getFilesFragment() ?: return
            binding.workspaceChrome.render(filesFragment.shellState())
        } else {
            binding.workspaceChrome.setSearchVisible(false, clearQuery = false)
            binding.workspaceChrome.render(WorkspaceShellState(emptyList(), "", false))
        }
    }

    private fun blendColor(foreground: Int, background: Int, ratio: Float): Int {
        val clamped = ratio.coerceIn(0f, 1f)
        val inverse = 1f - clamped
        val a = (Color.alpha(foreground) * clamped + Color.alpha(background) * inverse).toInt()
        val r = (Color.red(foreground) * clamped + Color.red(background) * inverse).toInt()
        val g = (Color.green(foreground) * clamped + Color.green(background) * inverse).toInt()
        val b = (Color.blue(foreground) * clamped + Color.blue(background) * inverse).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun getTabsList() = arrayListOf(TAB_FILES, TAB_RECENT_FILES)

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            activity.checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}
