package org.fossify.filemanager.activities

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.viewpager.widget.ViewPager
import com.stericson.RootTools.RootTools
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.checkWhatsNew
import org.fossify.commons.extensions.getAlertDialogBuilder
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
import org.fossify.commons.extensions.humanizePath
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.isPathOnSD
import org.fossify.commons.extensions.launchMoreAppsFromUsIntent
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.extensions.sdCardPath
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
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
import org.fossify.filemanager.helpers.MAX_COLUMN_COUNT
import org.fossify.filemanager.helpers.RootHelpers
import org.fossify.filemanager.helpers.SessionSelfTestRunner
import org.fossify.filemanager.interfaces.FileManagerDependencies
import org.fossify.filemanager.interfaces.FileManagerHost
import org.fossify.filemanager.interfaces.ItemOperationsListener
import java.io.File

class MainActivity : SimpleActivity(), FileManagerHost {
    override var isSearchBarEnabled = true
    
    companion object {
        private const val BACK_PRESS_TIMEOUT = 5000
        private const val PICKED_PATH = "picked_path"
    }

    private val binding by viewBinding(FmActivityMainBinding::inflate)

    private var wasBackJustPressed = false
    private var mTabsToShow = ArrayList<Int>()
    private var mainFabMenu: PopupWindow? = null
    private var selfTestProgressDialog: AlertDialog? = null
    private var selfTestRunning = false

    private var mStoredFontSize = 0
    private var mStoredDateFormat = ""
    private var mStoredTimeFormat = ""
    private var mStoredShowTabs = 0
    private val fileManagerDependencies by lazy {
        FileManagerDependencies(
            environment = this,
            controllerCommands = this,
            resultHandler = this
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupOptionsMenu()
        refreshMenuItems()
        mTabsToShow = getTabsList()

        if (!config.wasStorageAnalysisTabAdded) {
            config.wasStorageAnalysisTabAdded = true
            if (config.showTabs and TAB_STORAGE_ANALYSIS == 0) {
                config.showTabs += TAB_STORAGE_ANALYSIS
            }
        }

        storeStateVariables()
        applyIntentTabRestrictions()
        setupMainFab()
        setupEdgeToEdge(
            padBottomSystem = listOf(binding.mainViewPager),
            moveBottomSystem = listOf(binding.mainFab)
        )

        if (savedInstanceState == null) {
            config.temporarilyShowHidden = false
            initFragments()
            tryInitFileManager()
            checkWhatsNewDialog()
            checkIfRootAvailable()
            checkInvalidFavorites()
        }
    }

    override fun onResume() {
        super.onResume()
        if (mStoredShowTabs != config.showTabs) {
            config.lastUsedViewPagerPage = 0
            System.exit(0)
            return
        }

        refreshMenuItems()
        updateMenuColors()

        getAllFragments().forEach {
            it?.onResume(getProperTextColor())
        }

        if (mStoredFontSize != config.fontSize) {
            getAllFragments().forEach {
                (it as? ItemOperationsListener)?.setupFontSize()
            }
        }

        if (mStoredDateFormat != config.dateFormat || mStoredTimeFormat != getTimeFormat()) {
            getAllFragments().forEach {
                (it as? ItemOperationsListener)?.setupDateTimeFormat()
            }
        }

        if (binding.mainViewPager.adapter == null) {
            initFragments()
        }
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
        config.lastUsedViewPagerPage = binding.mainViewPager.currentItem
    }

    override fun onDestroy() {
        selfTestProgressDialog?.dismiss()
        selfTestProgressDialog = null
        super.onDestroy()
    }

    override fun onBackPressedCompat(): Boolean {
        val currentFragment = getCurrentFragment()
        if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            return true
        } else if (currentFragment is RecentsFragment || currentFragment is StorageFragment) {
            return false
        } else if (currentFragment is ItemsFragment) {
            val currentPath = currentFragment.currentPath.trimEnd('/')
            val homePath = config.homeFolder.trimEnd('/')
            val atRoot = currentPath.isEmpty() || currentPath == "/" || currentPath == homePath
            if (atRoot) {
                if (!wasBackJustPressed && config.pressBackTwice) {
                    wasBackJustPressed = true
                    toast(R.string.press_back_again)
                    Handler().postDelayed({
                        wasBackJustPressed = false
                    }, BACK_PRESS_TIMEOUT.toLong())
                    return true
                } else {
                    appLockManager.lock()
                    finish()
                    return true
                }
            }

            val parentPath = File(currentPath).parent?.trimEnd('/').orEmpty().ifEmpty { "/" }
            openPath(parentPath, forceRefresh = true)
            return true
        }

        return false
    }

    override fun isTermuxScopedFileManager(): Boolean = false

    override fun refreshMenuItems() {
        val currentFragment = getCurrentFragment() ?: return
        val isCreateDocumentIntent = intent.action == Intent.ACTION_CREATE_DOCUMENT
        val currentViewType = config.getFolderViewType(currentFragment.currentPath)
        val favorites = config.favorites
        val isInTermux = isInTermuxStorage(currentFragment.currentPath)

        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.sort).isVisible = currentFragment is ItemsFragment
            findItem(R.id.change_view_type).isVisible = currentFragment !is StorageFragment

            findItem(R.id.add_favorite).isVisible = currentFragment is ItemsFragment && !favorites.contains(currentFragment.currentPath)
            findItem(R.id.remove_favorite).isVisible = currentFragment is ItemsFragment && favorites.contains(currentFragment.currentPath)
            findItem(R.id.go_to_favorite).isVisible = currentFragment is ItemsFragment && favorites.isNotEmpty()

            findItem(R.id.toggle_filename).isVisible = currentViewType == VIEW_TYPE_GRID && currentFragment !is StorageFragment
            findItem(R.id.go_home).isVisible = false
            findItem(R.id.set_as_home).isVisible = currentFragment is ItemsFragment && currentFragment.currentPath != config.homeFolder
            findItem(R.id.toggle_termux_storage).isVisible = currentFragment is ItemsFragment && !isCreateDocumentIntent
            findItem(R.id.toggle_termux_storage).title =
                getString(if (isInTermux) R.string.switch_to_internal_storage else R.string.switch_to_termux)

            findItem(R.id.open_in_terminal).isVisible = currentFragment is ItemsFragment && !isCreateDocumentIntent

            findItem(R.id.temporarily_show_hidden).isVisible = !config.shouldShowHidden() && currentFragment !is StorageFragment
            findItem(R.id.stop_showing_hidden).isVisible = config.temporarilyShowHidden && currentFragment !is StorageFragment

            findItem(R.id.column_count).isVisible = currentViewType == VIEW_TYPE_GRID && currentFragment !is StorageFragment

            findItem(R.id.more_apps_from_us).isVisible = !resources.getBoolean(R.bool.hide_google_relations)
            findItem(R.id.settings).isVisible = !isCreateDocumentIntent
            findItem(R.id.self_test).isVisible = !isCreateDocumentIntent
            findItem(R.id.about).isVisible = !isCreateDocumentIntent
        }
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.apply {
            requireToolbar().inflateMenu(R.menu.menu)
            toggleHideOnScroll(false)
            setupMenu()

            onSearchClosedListener = {
                getAllFragments().forEach {
                    it?.searchQueryChanged("")
                }
            }

            onSearchTextChangedListener = { text ->
                getCurrentFragment()?.searchQueryChanged(text)
            }

            requireToolbar().setOnMenuItemClickListener { menuItem ->
                if (getCurrentFragment() == null) {
                    return@setOnMenuItemClickListener true
                }

                when (menuItem.itemId) {
                    R.id.go_home -> goHome()
                    R.id.go_to_favorite -> goToFavorite()
                    R.id.sort -> showSortingDialog()
                    R.id.add_favorite -> addFavorite()
                    R.id.remove_favorite -> removeFavorite()
                    R.id.toggle_filename -> toggleFilenameVisibility()
                    R.id.toggle_termux_storage -> toggleTermuxStorage()
                    R.id.open_in_terminal -> openInTerminal(getCurrentFragment()?.currentPath ?: return@setOnMenuItemClickListener true)
                    R.id.set_as_home -> setAsHome()
                    R.id.change_view_type -> changeViewType()
                    R.id.temporarily_show_hidden -> tryToggleTemporarilyShowHidden()
                    R.id.stop_showing_hidden -> tryToggleTemporarilyShowHidden()
                    R.id.column_count -> changeColumnCount()
                    R.id.more_apps_from_us -> launchMoreAppsFromUsIntent()
                    R.id.settings -> launchSettings()
                    R.id.self_test -> runIndustrialSelfTest()
                    R.id.about -> launchAbout()
                    else -> return@setOnMenuItemClickListener false
                }
                return@setOnMenuItemClickListener true
            }
        }
    }

    private fun runIndustrialSelfTest() {
        if (selfTestRunning) {
            toast(R.string.industrial_self_test_running)
            return
        }

        selfTestRunning = true
        selfTestProgressDialog = getAlertDialogBuilder()
            .setTitle(R.string.industrial_self_test_title)
            .setMessage(R.string.industrial_self_test_running_message)
            .setCancelable(false)
            .create()
        selfTestProgressDialog?.show()

        ensureBackgroundThread {
            val report = SessionSelfTestRunner.run(applicationContext)
            runOnUiThread {
                selfTestRunning = false
                selfTestProgressDialog?.dismiss()
                selfTestProgressDialog = null
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }
                showIndustrialSelfTestReport(report)
            }
        }
    }

    private fun showIndustrialSelfTestReport(report: SessionSelfTestRunner.Report) {
        getAlertDialogBuilder()
            .setTitle(
                if (report.success) {
                    R.string.industrial_self_test_title
                } else {
                    R.string.industrial_self_test_failed_title
                }
            )
            .setMessage(report.summary)
            .setPositiveButton(R.string.copy) { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.industrial_self_test_title), report.content)
                )
                toast(R.string.industrial_self_test_report_copied)
            }
            .setNeutralButton(R.string.industrial_self_test_open_report_folder) { _, _ ->
                val reportFile = File(report.reportPath)
                val parent = reportFile.parentFile
                if (parent != null && parent.exists()) {
                    openPath(parent.absolutePath, forceRefresh = true)
                } else {
                    toast(R.string.unknown_error_occurred)
                }
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun toggleTermuxStorage() {
        val currentPath = getCurrentFragment()?.currentPath ?: return
        val termuxRoot = filesDir.absolutePath
        val target = if (isInTermuxStorage(currentPath)) {
            internalStoragePath
        } else {
            termuxRoot
        }
        config.preferTermuxStorage = target == termuxRoot
        openPath(target, forceRefresh = true)
    }

    private fun getPreferredStartPath(): String {
        val homeFolder = config.homeFolder
        return if (config.preferTermuxStorage) {
            if (isInTermuxStorage(homeFolder)) homeFolder else filesDir.absolutePath
        } else {
            if (!isInTermuxStorage(homeFolder)) homeFolder else internalStoragePath
        }
    }

    private fun isInTermuxStorage(path: String): Boolean {
        val root = filesDir.absolutePath.trimEnd('/')
        val p = path.trimEnd('/')
        return p == root || p.startsWith("$root/")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PICKED_PATH, getItemsFragment()?.currentPath ?: "")
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val path = savedInstanceState.getString(PICKED_PATH).takeIf { !it.isNullOrBlank() } ?: getPreferredStartPath()

        if (binding.mainViewPager.adapter == null) {
            binding.mainViewPager.onGlobalLayout {
                openPath(path, true)
            }
        } else {
            openPath(path, true)
        }
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
    }

    private fun storeStateVariables() {
        config.apply {
            mStoredFontSize = fontSize
            mStoredDateFormat = dateFormat
            mStoredTimeFormat = context.getTimeFormat()
            mStoredShowTabs = showTabs
        }
    }

    private fun tryInitFileManager() {
        val hadPermission = hasStoragePermission()
        handleStoragePermission {
            checkOTGPath()
            if (it) {
                if (binding.mainViewPager.adapter == null) {
                    initFragments()
                }

                binding.mainViewPager.onGlobalLayout {
                    initFileManager(!hadPermission)
                }
            } else {
                toast(R.string.no_storage_permissions)
                finish()
            }
        }
    }

    private fun initFileManager(refreshRecents: Boolean) {
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            val data = intent.data
            if (data?.scheme == "file") {
                openPath(data.path!!)
            } else {
                val path = getRealPathFromURI(data!!)
                if (path != null) {
                    openPath(path)
                } else {
                    openPath(getPreferredStartPath())
                }
            }

            if (!File(data.path!!).isDirectory) {
                tryOpenPathIntent(data.path!!, false, finishActivity = true)
            }

            binding.mainViewPager.currentItem = 0
        } else {
            openPath(getPreferredStartPath())
        }

        if (refreshRecents) {
            getRecentsFragment()?.refreshFragment()
        }
    }

    private fun initFragments() {
        binding.mainViewPager.apply {
            adapter = ViewPagerAdapter(this@MainActivity, mTabsToShow, { intent }, fileManagerDependencies)
            offscreenPageLimit = 2
            addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageScrollStateChanged(state: Int) {}

                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

                override fun onPageSelected(position: Int) {
                    getAllFragments().forEach {
                        (it as? ItemOperationsListener)?.finishActMode()
                    }
                    (getCurrentFragment() as? RecentsFragment)?.refreshFragment()
                    refreshMenuItems()
                }
            })
            currentItem = config.lastUsedViewPagerPage

            onGlobalLayout {
                refreshMenuItems()
            }
        }
    }

    private fun applyIntentTabRestrictions() {
        val action = intent.action
        val isPickFileIntent = action == RingtoneManager.ACTION_RINGTONE_PICKER ||
            action == Intent.ACTION_GET_CONTENT ||
            action == Intent.ACTION_PICK
        val isCreateDocumentIntent = action == Intent.ACTION_CREATE_DOCUMENT

        if (isPickFileIntent) {
            mTabsToShow.remove(TAB_STORAGE_ANALYSIS)
            if (mTabsToShow.none { it and config.showTabs != 0 }) {
                config.showTabs = TAB_FILES
                mStoredShowTabs = TAB_FILES
                mTabsToShow = arrayListOf(TAB_FILES)
            }
        } else if (isCreateDocumentIntent) {
            mTabsToShow = arrayListOf(TAB_FILES)
        }
    }

    private fun getEffectiveTabs(): List<Int> {
        return mTabsToShow.filter { it and config.showTabs != 0 }
    }

    private fun setupMainFab() {
        updateMainFabIcon(intent.action == Intent.ACTION_CREATE_DOCUMENT)
        binding.mainFab.setOnClickListener {
            val currentPath = getCurrentFragment()?.currentPath.orEmpty()
            if (intent.action == Intent.ACTION_CREATE_DOCUMENT) {
                createDocumentConfirmed(currentPath)
            } else {
                toggleMainFabMenu()
            }
        }
    }

    private fun updateMainFabIcon(isCreateDocumentIntent: Boolean) {
        val iconId = if (isCreateDocumentIntent) R.drawable.ic_check_vector else R.drawable.ic_plus_vector
        val icon = resources.getColoredDrawableWithColor(iconId, getProperPrimaryColor().getContrastColor())
        binding.mainFab.setImageDrawable(icon)
    }

    override fun toggleMainFabMenu() {
        if (mainFabMenu?.isShowing == true) {
            mainFabMenu?.dismiss()
            return
        }

        if (getEffectiveTabs().size <= 1) {
            openCreateNew()
            return
        }

        showMainFabMenu()
    }

    override fun showSessionSwitcher() {
        // Reserved for Termux integrated host. Standalone file manager keeps current behavior.
    }

    private fun showMainFabMenu() {
        val effectiveTabs = getEffectiveTabs()
        if (effectiveTabs.size <= 1) {
            return
        }

        val content = layoutInflater.inflate(R.layout.fm_popup_main_fab_menu, null)
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
        val textColor = getProperTextColor()
        val selectedColor = getProperPrimaryColor()

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
            icon.setImageDrawable(resources.getColoredDrawableWithColor(iconRes, color))
            label.setTextColor(color)
            row.setOnClickListener {
                binding.mainMenu.closeSearch()
                binding.mainViewPager.currentItem = index
                mainFabMenu?.dismiss()
            }
        }

        val canCreate = effectiveTabs.contains(TAB_FILES) && intent.action != Intent.ACTION_CREATE_DOCUMENT
        createRow.beGoneIf(!canCreate)
        if (canCreate) {
            createIcon.setImageDrawable(resources.getColoredDrawableWithColor(R.drawable.ic_plus_vector, textColor))
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
            elevation = resources.displayMetrics.density * 8f
            setOnDismissListener {
                updateMainFabIcon(intent.action == Intent.ACTION_CREATE_DOCUMENT)
            }
        }
        mainFabMenu = popup

        val closeIcon = resources.getColoredDrawableWithColor(R.drawable.ic_cross_vector, getProperPrimaryColor().getContrastColor())
        binding.mainFab.setImageDrawable(closeIcon)

        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val spacing = (resources.displayMetrics.density * 8).toInt()
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
                (getCurrentFragment() as? ItemsFragment)?.showCreateNewItemDialog()
            }
        } else {
            (getCurrentFragment() as? ItemsFragment)?.showCreateNewItemDialog()
        }
    }

    private fun checkOTGPath() {
        ensureBackgroundThread {
            if (!config.wasOTGHandled && hasPermission(PERMISSION_WRITE_STORAGE) && hasOTGConnected() && config.OTGPath.isEmpty()) {
                getStorageDirectories().firstOrNull { it.trimEnd('/') != internalStoragePath && it.trimEnd('/') != sdCardPath }?.apply {
                    config.wasOTGHandled = true
                    config.OTGPath = trimEnd('/')
                }
            }
        }
    }

    private fun openPath(path: String, forceRefresh: Boolean = false) {
        var newPath = path
        val file = File(path)
        if (config.OTGPath.isNotEmpty() && config.OTGPath == path.trimEnd('/')) {
            newPath = path
        } else if (file.exists() && !file.isDirectory) {
            newPath = file.parent
        } else if (!file.exists() && !isPathOnOTG(newPath)) {
            newPath = internalStoragePath
        }

        getItemsFragment()?.openPath(newPath, forceRefresh)
    }

    private fun goHome() {
        if (config.homeFolder != getCurrentFragment()!!.currentPath) {
            openPath(config.homeFolder)
        }
    }

    private fun showSortingDialog() {
        ChangeSortingDialog(this, getCurrentFragment()!!.currentPath) {
            (getCurrentFragment() as? ItemsFragment)?.refreshFragment()
        }
    }

    private fun addFavorite() {
        config.addFavorite(getCurrentFragment()!!.currentPath)
        refreshMenuItems()
    }

    private fun removeFavorite() {
        config.removeFavorite(getCurrentFragment()!!.currentPath)
        refreshMenuItems()
    }

    private fun toggleFilenameVisibility() {
        config.displayFilenames = !config.displayFilenames
        getAllFragments().forEach {
            (it as? ItemOperationsListener)?.toggleFilenameVisibility()
        }
    }

    private fun changeColumnCount() {
        val items = ArrayList<RadioItem>()
        for (i in 1..MAX_COLUMN_COUNT) {
            items.add(RadioItem(i, resources.getQuantityString(R.plurals.column_counts, i, i)))
        }

        val currentColumnCount = config.fileColumnCnt
        RadioGroupDialog(this, items, config.fileColumnCnt) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                config.fileColumnCnt = newColumnCount
                getAllFragments().forEach {
                    (it as? ItemOperationsListener)?.columnCountChanged()
                }
            }
        }
    }

    override fun updateFragmentColumnCounts() {
        getAllFragments().forEach {
            (it as? ItemOperationsListener)?.columnCountChanged()
        }
    }

    private fun goToFavorite() {
        val favorites = config.favorites
        val items = ArrayList<RadioItem>(favorites.size)
        var currFavoriteIndex = -1

        favorites.forEachIndexed { index, path ->
            val visiblePath = humanizePath(path).replace("/", " / ")
            items.add(RadioItem(index, visiblePath, path))
            if (path == getCurrentFragment()!!.currentPath) {
                currFavoriteIndex = index
            }
        }

        RadioGroupDialog(this, items, currFavoriteIndex, R.string.go_to_favorite) {
            openPath(it.toString())
        }
    }

    private fun setAsHome() {
        config.homeFolder = getCurrentFragment()!!.currentPath
        toast(R.string.home_folder_updated)
    }

    private fun changeViewType() {
        ChangeViewTypeDialog(this, getCurrentFragment()!!.currentPath, getCurrentFragment() is ItemsFragment) {
            getAllFragments().forEach {
                it?.refreshFragment()
            }
        }
    }

    private fun tryToggleTemporarilyShowHidden() {
        if (config.temporarilyShowHidden) {
            toggleTemporarilyShowHidden(false)
        } else {
            handleHiddenFolderPasswordProtection {
                toggleTemporarilyShowHidden(true)
            }
        }
    }

    private fun toggleTemporarilyShowHidden(show: Boolean) {
        config.temporarilyShowHidden = show
        getAllFragments().forEach {
            it?.refreshFragment()
        }
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses = LICENSE_GLIDE or LICENSE_PATTERN or LICENSE_REPRINT or LICENSE_GESTURE_VIEWS or LICENSE_AUTOFITTEXTVIEW or LICENSE_ZIP4J

        val faqItems = arrayListOf(
            FAQItem(R.string.faq_3_title_commons, R.string.faq_3_text_commons),
            FAQItem(R.string.faq_9_title_commons, R.string.faq_9_text_commons)
        )

        if (!resources.getBoolean(R.bool.hide_google_relations)) {
            faqItems.add(FAQItem(R.string.faq_2_title_commons, R.string.faq_2_text_commons))
            faqItems.add(FAQItem(R.string.faq_6_title_commons, R.string.faq_6_text_commons))
            faqItems.add(FAQItem(R.string.faq_7_title_commons, R.string.faq_7_text_commons))
            faqItems.add(FAQItem(R.string.faq_10_title_commons, R.string.faq_10_text_commons))
        }

        startAboutActivity(R.string.app_name, licenses, BuildConfig.VERSION_NAME, faqItems, true)
    }

    private fun checkIfRootAvailable() {
        ensureBackgroundThread {
            config.isRootAvailable = RootTools.isRootAvailable()
            if (config.isRootAvailable && config.enableRootAccess) {
                RootHelpers(this).askRootIfNeeded {
                    config.enableRootAccess = it
                }
            }
        }
    }

    private fun checkInvalidFavorites() {
        ensureBackgroundThread {
            val snapshot = config.favorites.toList()
            val termuxRoot = filesDir.absolutePath.trimEnd('/')
            val virtualPrefix = "$termuxRoot/.termux/sftp-virtual/"
            val mountPrefix = "$termuxRoot/.termux/sftp-mounts/"
            snapshot.forEach { favoritePath ->
                val normalized = favoritePath.trim().trimEnd('/')
                val isRemoteWorkspacePath = normalized.startsWith(virtualPrefix) || normalized.startsWith(mountPrefix)
                if (isRemoteWorkspacePath) {
                    return@forEach
                }

                if (!isPathOnOTG(normalized) && !isPathOnSD(normalized) && !File(normalized).exists()) {
                    config.removeFavorite(favoritePath)
                }
            }
        }
    }

    override fun pickedPath(path: String) {
        val resultIntent = Intent()
        val uri = getFilePublicUri(File(path), BuildConfig.APPLICATION_ID)
        val type = path.getMimeType()
        resultIntent.setDataAndType(uri, type)
        resultIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    // used at apps that have no file access at all, but need to work with files. For example Simple Calendar uses this at exporting events into a file
    override fun createDocumentConfirmed(path: String) {
        val filename = intent.getStringExtra(Intent.EXTRA_TITLE) ?: ""
        if (filename.isEmpty()) {
            InsertFilenameDialog(this, internalStoragePath) { newFilename ->
                finishCreateDocumentIntent(path, newFilename)
            }
        } else {
            finishCreateDocumentIntent(path, filename)
        }
    }

    private fun finishCreateDocumentIntent(path: String, filename: String) {
        val resultIntent = Intent()
        val uri = getFilePublicUri(File(path, filename), BuildConfig.APPLICATION_ID)
        val type = path.getMimeType()
        resultIntent.setDataAndType(uri, type)
        resultIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    override fun pickedRingtone(path: String) {
        val uri = getFilePublicUri(File(path), BuildConfig.APPLICATION_ID)
        val type = path.getMimeType()
        Intent().apply {
            setDataAndType(uri, type)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, uri)
            setResult(Activity.RESULT_OK, this)
        }
        finish()
    }

    override fun pickedPaths(paths: ArrayList<String>) {
        val newPaths = paths.map { getFilePublicUri(File(it), BuildConfig.APPLICATION_ID) } as ArrayList
        val clipData = ClipData("Attachment", arrayOf(paths.getMimeType()), ClipData.Item(newPaths.removeAt(0)))

        newPaths.forEach {
            clipData.addItem(ClipData.Item(it))
        }

        Intent().apply {
            this.clipData = clipData
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            setResult(Activity.RESULT_OK, this)
        }
        finish()
    }

    override fun openedDirectory() {
        if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
        }
    }

    override fun openInTerminal(path: String) {}

    private fun getRecentsFragment() = findViewById<RecentsFragment>(R.id.recents_fragment)
    private fun getItemsFragment() = findViewById<ItemsFragment>(R.id.items_fragment)
    private fun getStorageFragment() = findViewById<StorageFragment>(R.id.storage_fragment)
    private fun getAllFragments(): ArrayList<MyViewPagerFragment<*>?> =
        arrayListOf(getItemsFragment(), getRecentsFragment(), getStorageFragment())

    private fun getCurrentFragment(): MyViewPagerFragment<*>? {
        val fragments = arrayListOf<MyViewPagerFragment<*>>()
        getEffectiveTabs().forEach { tab ->
            when (tab) {
                TAB_FILES -> fragments.add(getItemsFragment())
                TAB_RECENT_FILES -> fragments.add(getRecentsFragment())
                TAB_STORAGE_ANALYSIS -> fragments.add(getStorageFragment())
            }
        }
        return fragments.getOrNull(binding.mainViewPager.currentItem)
    }

    private fun getTabsList() = arrayListOf(TAB_FILES, TAB_RECENT_FILES, TAB_STORAGE_ANALYSIS)

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}
