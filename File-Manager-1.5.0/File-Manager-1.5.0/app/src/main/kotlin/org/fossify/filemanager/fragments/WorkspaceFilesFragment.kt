package org.fossify.filemanager.fragments

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.children
import com.termux.sessionsync.SessionFileCoordinator
import com.termux.workspaceshell.model.WorkspaceKind
import com.termux.workspaceshell.model.WorkspaceReusePolicy
import com.termux.workspaceshell.model.WorkspaceShellState
import com.termux.workspaceshell.model.WorkspaceTabModel
import com.termux.workspaceshell.model.WorkspaceTabSpec
import com.termux.workspaceshell.model.WorkspaceTabTone
import com.termux.workspaceshell.state.WorkspaceShellAction
import com.termux.workspaceshell.state.WorkspaceShellReducer
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.humanizePath
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.views.MyFloatingActionButton
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.SimpleActivity
import org.fossify.filemanager.databinding.WorkspaceFilesFragmentBinding
import org.fossify.filemanager.extensions.config
import org.fossify.filemanager.helpers.NavigatorFolderHelper
import org.fossify.filemanager.helpers.TermuxPathScope
import org.fossify.filemanager.interfaces.ItemOperationsListener
import org.fossify.filemanager.workspace.SharedPrefsWorkspaceSessionStore
import org.fossify.filemanager.workspace.WorkspaceSessionReconciler
import org.fossify.filemanager.workspace.WorkspaceSessionSnapshot
import org.fossify.filemanager.workspace.WorkspaceSessionStateMachine
import org.fossify.filemanager.workspace.WorkspaceTabSnapshot
import java.io.File
import java.util.UUID

class WorkspaceFilesFragment(context: Context, attributeSet: AttributeSet) :
    MyViewPagerFragment<WorkspaceFilesFragment.WorkspaceFilesInnerBinding>(context, attributeSet),
    ItemOperationsListener {

    companion object {
        private const val HOME_TAB_ID = "workspace-home"
        private const val STATE_SNAPSHOT_JSON = "workspace_snapshot_json"
    }

    private data class WorkspaceSurface(
        val tabId: String,
        val fragment: ItemsFragment
    )

    private lateinit var binding: WorkspaceFilesFragmentBinding
    private val surfaces = LinkedHashMap<String, WorkspaceSurface>()
    private val sessionFileCoordinator = SessionFileCoordinator.getInstance()
    private lateinit var workspaceSessionStateMachine: WorkspaceSessionStateMachine
    private var shellState: WorkspaceShellState? = null
    private var pendingRestoreState: Bundle? = null
    var workspaceStateChangedListener: ((WorkspaceShellState) -> Unit)? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = WorkspaceFilesFragmentBinding.bind(this)
        innerBinding = WorkspaceFilesInnerBinding()
    }

    override fun setupFragment(activity: SimpleActivity) {
        if (this.activity != null) return
        this.activity = activity
        sessionFileCoordinator.initialize(activity)
        initializeWorkspaceSessionStateMachine(activity)

        if (pendingRestoreState != null) {
            restoreWorkspaceState(requireNotNull(pendingRestoreState))
            pendingRestoreState = null
        } else {
            ensureHomeWorkspace(forceRefresh = true)
        }
    }

    override fun onResume(textColor: Int) {
        surfaces.values.forEach { surface ->
            surface.fragment.onResume(textColor)
        }
    }

    override fun refreshFragment() {
        activeItemsFragment()?.refreshFragment()
    }

    override fun searchQueryChanged(text: String) {
        val activeTab = shellState?.activeTab ?: return
        dispatch(WorkspaceShellAction.UpdateSearchQuery(activeTab.id, text))
        activeItemsFragment()?.searchQueryChanged(text)
        notifyWorkspaceStateChanged()
    }

    override fun deleteFiles(files: ArrayList<FileDirItem>) {
        activeItemsFragment()?.deleteFiles(files)
    }

    override fun selectedPaths(paths: ArrayList<String>) {
        activeItemsFragment()?.selectedPaths(paths)
    }

    override fun setupDateTimeFormat() {
        surfaces.values.forEach { it.fragment.setupDateTimeFormat() }
    }

    override fun setupFontSize() {
        surfaces.values.forEach { it.fragment.setupFontSize() }
    }

    override fun toggleFilenameVisibility() {
        surfaces.values.forEach { it.fragment.toggleFilenameVisibility() }
    }

    override fun columnCountChanged() {
        surfaces.values.forEach { it.fragment.columnCountChanged() }
    }

    override fun finishActMode() {
        activeItemsFragment()?.finishActMode()
    }

    override fun openPathAndHighlight(targetPath: String, highlightPaths: ArrayList<String>) {
        openManagedPath(targetPath, forceRefresh = true)
        activeItemsFragment()?.openPathAndHighlight(targetPath, highlightPaths)
    }

    fun shellState(): WorkspaceShellState = requireState()

    fun isSearchVisible(): Boolean = requireState().searchVisible

    fun setSearchVisible(visible: Boolean) {
        dispatch(if (visible) WorkspaceShellAction.ShowSearch else WorkspaceShellAction.HideSearch)
        notifyWorkspaceStateChanged()
    }

    fun activeItemsFragment(): ItemsFragment? {
        val activeTabId = shellState?.activeTabId ?: return null
        return surfaces[activeTabId]?.fragment
    }

    fun activePath(): String = activeItemsFragment()?.currentPath.orEmpty()

    fun activeWorkspacePathForMenu(): String = activeItemsFragment()?.currentPath.orEmpty()

    fun isNavigatorActive(): Boolean {
        val ctx = context ?: return false
        return NavigatorFolderHelper.isNavigatorPath(ctx, activeWorkspacePathForMenu())
    }

    fun openHomeWorkspace(forceRefresh: Boolean = false) {
        ensureHomeWorkspace(forceRefresh = false)
        selectWorkspaceTab(HOME_TAB_ID)
        if (forceRefresh) {
            activeItemsFragment()?.openPath(homeRoute(), true)
        }
    }

    fun openManagedPath(path: String, forceRefresh: Boolean = false) {
        if (context == null) return
        ensureHomeWorkspace()
        val normalized = normalizePath(path)
        if (NavigatorFolderHelper.isNavigatorPath(context!!, normalized)) {
            selectWorkspaceTab(HOME_TAB_ID)
            if (forceRefresh) {
                activeItemsFragment()?.openPath(normalized, true)
            }
            return
        }

        val spec = buildWorkspaceSpecForPath(normalized)
        openOrSelectWorkspace(spec, forceRefresh = forceRefresh)
    }

    fun selectWorkspaceTab(tabId: String) {
        dispatch(WorkspaceShellAction.SelectTab(tabId))
        showActiveSurface(forceRefresh = false)
        notifyWorkspaceStateChanged()
    }

    fun closeWorkspaceTab(tabId: String) {
        val state = requireState()
        val beforeIds = state.tabs.map { it.id }.toSet()
        val nextState = WorkspaceShellReducer.reduce(state, WorkspaceShellAction.CloseTab(tabId))
        val afterIds = nextState.tabs.map { it.id }.toSet()
        val removedIds = beforeIds - afterIds
        removedIds.forEach(::removeSurface)
        replaceState(nextState, persist = true)
        showActiveSurface(forceRefresh = false)
        notifyWorkspaceStateChanged()
    }

    fun handleBackPressedWithinWorkspaces(): Boolean {
        val state = shellState ?: return false
        val activeTab = state.activeTab ?: return false
        val activeFragment = activeItemsFragment() ?: return false
        val current = normalizePath(activeFragment.currentPath)
        val root = normalizePath(activeTab.rootRoute)

        if (current != root) {
            activeFragment.openPath(resolveParentPath(current, root), true)
            return true
        }

        if (activeTab.id != HOME_TAB_ID) {
            selectWorkspaceTab(HOME_TAB_ID)
            return true
        }

        return false
    }

    fun restoreWorkspaceState(bundle: Bundle) {
        if (activity == null) {
            pendingRestoreState = Bundle(bundle)
            return
        }

        val snapshot = WorkspaceSessionSnapshot.fromJsonString(bundle.getString(STATE_SNAPSHOT_JSON))
        if (snapshot.isEmpty()) {
            ensureHomeWorkspace(forceRefresh = true)
            return
        }

        val restoredState = reconcileSnapshot(snapshot) ?: run {
            ensureHomeWorkspace(forceRefresh = true)
            return
        }

        replaceState(restoredState, persist = true)
        rebuildSurfaces(forceRefresh = true)
        notifyWorkspaceStateChanged()
    }

    fun saveWorkspaceState(): Bundle {
        val state = shellState ?: return Bundle()
        val normalizedTabs = state.tabs.map { tab ->
            val currentRoute = surfaces[tab.id]?.fragment?.currentPath
            if (currentRoute.isNullOrBlank()) {
                tab
            } else {
                tab.copy(currentRoute = normalizePath(currentRoute))
            }
        }
        val snapshot = WorkspaceSessionSnapshot.fromState(state.copy(tabs = normalizedTabs))
        return Bundle().apply {
            putString(STATE_SNAPSHOT_JSON, snapshot.toJson().toString())
        }
    }

    fun restorePersistedWorkspaceSession(): Boolean {
        if (activity == null) return false
        val snapshot = workspaceSessionStateMachine.loadPersistedSnapshot()
        if (snapshot.isEmpty()) return false

        val restoredState = reconcileSnapshot(snapshot) ?: return false
        replaceState(restoredState, persist = true)
        rebuildSurfaces(forceRefresh = true)
        notifyWorkspaceStateChanged()
        return true
    }

    fun flushWorkspaceSessionPersistence() {
        if (::workspaceSessionStateMachine.isInitialized) {
            workspaceSessionStateMachine.persistCurrentStateBlocking()
        }
    }

    private fun ensureHomeWorkspace(forceRefresh: Boolean = false) {
        if (shellState == null) {
            replaceState(WorkspaceShellReducer.createInitialState(buildHomeSpec()), persist = true)
            rebuildSurfaces(forceRefresh = true)
            notifyWorkspaceStateChanged()
            return
        }
        if (forceRefresh) {
            selectWorkspaceTab(HOME_TAB_ID)
            activeItemsFragment()?.openPath(homeRoute(), true)
        }
    }

    private fun openOrSelectWorkspace(spec: WorkspaceTabSpec, forceRefresh: Boolean) {
        val previousState = requireState()
        val previousTabIds = previousState.tabs.map { it.id }.toSet()
        val nextState = WorkspaceShellReducer.reduce(previousState, WorkspaceShellAction.OpenTab(spec, WorkspaceReusePolicy.REUSE_BY_KEY))
        replaceState(nextState, persist = true)
        val addedIds = nextState.tabs.map { it.id }.toSet() - previousTabIds
        if (addedIds.isNotEmpty()) {
            ensureSurface(nextState.activeTab ?: return, forceRefresh = true)
        } else if (forceRefresh) {
            ensureSurface(nextState.activeTab ?: return, forceRefresh = true)
        }
        showActiveSurface(forceRefresh = forceRefresh)
        notifyWorkspaceStateChanged()
    }

    private fun showActiveSurface(forceRefresh: Boolean) {
        val state = shellState ?: return
        val activeTab = state.activeTab ?: return
        ensureSurface(activeTab, forceRefresh = forceRefresh)
        binding.workspaceFilesSurfaceHost.children.forEach { child ->
            child.visibility = if (child === surfaces[activeTab.id]?.fragment) View.VISIBLE else View.GONE
        }
        val activeFragment = surfaces[activeTab.id]?.fragment
        if (activeFragment != null) {
            currentPath = activeFragment.currentPath
            activeFragment.onResume(activity?.getProperTextColor() ?: return)
            activeFragment.searchQueryChanged(state.queryFor(activeTab.id))
            activeFragment.bringToFront()
        }
        fileManagerControllerCommands.refreshMenuItems()
    }

    private fun rebuildSurfaces(forceRefresh: Boolean) {
        surfaces.keys.toList().forEach(::removeSurface)
        val state = shellState ?: return
        state.tabs.forEach { ensureSurface(it, forceRefresh = forceRefresh) }
        showActiveSurface(forceRefresh = forceRefresh)
    }

    private fun ensureSurface(tab: WorkspaceTabModel, forceRefresh: Boolean) {
        val existing = surfaces[tab.id]?.fragment
        if (existing != null) {
            val desiredPath = normalizePath(tab.currentRoute)
            if (forceRefresh || normalizePath(existing.currentPath) != desiredPath) {
                existing.openPath(desiredPath, forceRefresh)
            }
            return
        }

        val fragment = activity!!.layoutInflater.inflate(
            R.layout.items_fragment,
            binding.workspaceFilesSurfaceHost,
            false
        ) as ItemsFragment
        fragment.id = View.generateViewId()
        fragment.isGetRingtonePicker = isGetRingtonePicker
        fragment.isGetContentIntent = isGetContentIntent
        fragment.isPickMultipleIntent = isPickMultipleIntent
        fragment.wantedMimeTypes = wantedMimeTypes
        fragment.bindDependencies(fileManagerDependencies)
        fragment.updateIsCreateDocumentIntent(isCreateDocumentIntent)
        fragment.setupFragment(activity!!)
        fragment.directoryNavigationHandler = object : ItemsFragment.DirectoryNavigationHandler {
            override fun onDirectoryNavigationRequested(
                fragment: ItemsFragment,
                currentPath: String,
                item: FileDirItem,
                fromNavigatorRoot: Boolean
            ): Boolean {
                if (!fromNavigatorRoot) return false
                openOrSelectWorkspace(buildWorkspaceSpecFromNavigatorItem(item), forceRefresh = true)
                return true
            }
        }
        fragment.pathChangedListener = { newPath ->
            dispatch(WorkspaceShellAction.UpdateTabRoute(tab.id, normalizePath(newPath)))
            if (requireState().activeTabId == tab.id) {
                currentPath = normalizePath(newPath)
                fileManagerControllerCommands.refreshMenuItems()
            }
            notifyWorkspaceStateChanged()
        }
        binding.workspaceFilesSurfaceHost.addView(
            fragment,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        fragment.visibility = View.GONE
        surfaces[tab.id] = WorkspaceSurface(tab.id, fragment)
        fragment.openPath(tab.currentRoute, forceRefresh)
    }

    private fun removeSurface(tabId: String) {
        val surface = surfaces.remove(tabId) ?: return
        binding.workspaceFilesSurfaceHost.removeView(surface.fragment)
    }

    private fun requireState(): WorkspaceShellState {
        shellState?.let { return it }
        val initial = WorkspaceShellReducer.createInitialState(buildHomeSpec())
        shellState = initial
        if (::workspaceSessionStateMachine.isInitialized) {
            workspaceSessionStateMachine.replaceState(initial, persist = false)
        }
        return initial
    }

    private fun notifyWorkspaceStateChanged() {
        val state = shellState ?: return
        workspaceStateChangedListener?.invoke(state)
    }

    private fun buildHomeSpec(): WorkspaceTabSpec {
        val route = homeRoute()
        return WorkspaceTabSpec(
            id = HOME_TAB_ID,
            reuseKey = HOME_TAB_ID,
            kind = WorkspaceKind.HOME,
            tone = WorkspaceTabTone.HOME,
            title = NavigatorFolderHelper.displayTitle(),
            rootRoute = route,
            currentRoute = route,
            locked = true,
            closable = false
        )
    }

    private fun buildWorkspaceSpecFromNavigatorItem(item: FileDirItem): WorkspaceTabSpec {
        val targetPath = normalizePath(item.path)
        val title = sanitizeNavigatorTitle(item.name.ifBlank { targetPath.getFilenameFromPath() })
        return buildWorkspaceSpecForRoutes(
            id = UUID.randomUUID().toString(),
            rootRoute = targetPath,
            currentRoute = targetPath,
            titleOverride = title,
            contentDescriptionOverride = "$title ${sessionFileCoordinator.getDisplayPath(requireNotNull(context), targetPath)}"
        )
    }

    private fun buildWorkspaceSpecForPath(path: String): WorkspaceTabSpec {
        val normalized = normalizePath(path)
        return buildWorkspaceSpecForRoutes(
            id = UUID.randomUUID().toString(),
            rootRoute = normalized,
            currentRoute = normalized
        )
    }

    private fun resolveWorkspaceTitle(ctx: Context, path: String): String {
        val normalized = normalizePath(path)
        if (ctx.config.isFavorite(normalized)) {
            return ctx.config.getFavoriteRemark(normalized)
                ?: normalized.getFilenameFromPath().ifBlank { ctx.humanizePath(normalized) }
        }
        if (sessionFileCoordinator.isVirtualPath(ctx, normalized)) {
            return sessionFileCoordinator.getDisplayPath(ctx, normalized)
                .trimEnd('/')
                .substringAfterLast('/')
                .ifBlank { normalized.getFilenameFromPath() }
        }
        val termuxRoot = normalizePath(TermuxPathScope.termuxRootPath(ctx))
        if (normalized == termuxRoot) {
            return ctx.getString(R.string.termux_system_dirs)
        }
        return normalized.getFilenameFromPath().ifBlank { ctx.humanizePath(normalized) }
    }

    private fun resolveWorkspaceKind(path: String): WorkspaceKind {
        val ctx = context ?: return WorkspaceKind.GENERIC
        val normalized = normalizePath(path)
        return when {
            NavigatorFolderHelper.isNavigatorPath(ctx, normalized) -> WorkspaceKind.HOME
            sessionFileCoordinator.isVirtualPath(ctx, normalized) -> WorkspaceKind.REMOTE
            ctx.config.isFavorite(normalized) -> WorkspaceKind.FAVORITE
            normalized == normalizePath(TermuxPathScope.termuxRootPath(ctx)) -> WorkspaceKind.SYSTEM
            else -> WorkspaceKind.LOCAL
        }
    }

    private fun resolveWorkspaceTone(path: String): WorkspaceTabTone {
        return when (resolveWorkspaceKind(path)) {
            WorkspaceKind.HOME -> WorkspaceTabTone.HOME
            WorkspaceKind.LOCAL -> WorkspaceTabTone.LOCAL
            WorkspaceKind.FAVORITE -> WorkspaceTabTone.FAVORITE
            WorkspaceKind.REMOTE -> WorkspaceTabTone.REMOTE
            WorkspaceKind.SYSTEM -> WorkspaceTabTone.SYSTEM
            WorkspaceKind.GENERIC -> WorkspaceTabTone.NEUTRAL
        }
    }

    private fun resolveWorkspaceBadge(path: String): String? {
        return when (resolveWorkspaceKind(path)) {
            WorkspaceKind.REMOTE -> "SSH"
            WorkspaceKind.FAVORITE -> "FAV"
            WorkspaceKind.SYSTEM -> "SYS"
            else -> null
        }
    }

    private fun sanitizeNavigatorTitle(title: String): String {
        return title.removePrefix("【当前】").trim().ifBlank { NavigatorFolderHelper.displayTitle() }
    }

    private fun homeRoute(): String {
        return NavigatorFolderHelper.rootPath(requireNotNull(activity))
    }

    private fun resolveParentPath(current: String, root: String): String {
        val normalizedCurrent = normalizePath(current)
        val normalizedRoot = normalizePath(root)
        if (normalizedCurrent == normalizedRoot) {
            return normalizedRoot
        }
        val parent = File(normalizedCurrent).parent?.trimEnd('/').orEmpty().ifBlank { normalizedRoot }
        return if (normalizedCurrent.startsWith("$normalizedRoot/") && parent.length < normalizedRoot.length) {
            normalizedRoot
        } else {
            parent
        }
    }

    private fun normalizePath(path: String): String {
        return TermuxPathScope.normalizePath(path).ifEmpty { "/" }
    }

    private fun initializeWorkspaceSessionStateMachine(activity: SimpleActivity) {
        if (::workspaceSessionStateMachine.isInitialized) return
        workspaceSessionStateMachine = WorkspaceSessionStateMachine(
            store = SharedPrefsWorkspaceSessionStore(activity),
            initialState = WorkspaceShellReducer.createInitialState(buildHomeSpec())
        )
        workspaceSessionStateMachine.addListener(WorkspaceSessionStateMachine.Listener { state ->
            shellState = state
        }, emitImmediately = true)
    }

    private fun dispatch(action: WorkspaceShellAction): WorkspaceShellState {
        return workspaceSessionStateMachine.dispatch(action).also { shellState = it }
    }

    private fun replaceState(nextState: WorkspaceShellState, persist: Boolean): WorkspaceShellState {
        return workspaceSessionStateMachine.replaceState(nextState, persist).also { shellState = it }
    }

    private fun reconcileSnapshot(snapshot: WorkspaceSessionSnapshot): WorkspaceShellState? {
        return WorkspaceSessionReconciler(
            homeTab = {
                val spec = buildHomeSpec()
                WorkspaceTabModel(
                    id = spec.id,
                    reuseKey = spec.reuseKey,
                    kind = spec.kind,
                    tone = spec.tone,
                    title = spec.title,
                    rootRoute = spec.rootRoute,
                    currentRoute = spec.currentRoute,
                    selected = false,
                    locked = spec.locked,
                    closable = spec.closable,
                    badgeText = spec.badgeText,
                    contentDescription = spec.contentDescription
                )
            },
            restoreTab = ::restoreSnapshotTab
        ).reconcile(snapshot)
    }

    private fun restoreSnapshotTab(snapshot: WorkspaceTabSnapshot): WorkspaceTabModel? {
        val ctx = context ?: return null
        if (snapshot.id == HOME_TAB_ID || snapshot.kind == WorkspaceKind.HOME || NavigatorFolderHelper.isNavigatorPath(ctx, snapshot.rootRoute)) {
            val spec = buildHomeSpec()
            return WorkspaceTabModel(
                id = spec.id,
                reuseKey = spec.reuseKey,
                kind = spec.kind,
                tone = spec.tone,
                title = spec.title,
                rootRoute = spec.rootRoute,
                currentRoute = spec.currentRoute,
                selected = false,
                locked = spec.locked,
                closable = spec.closable,
                badgeText = spec.badgeText,
                contentDescription = spec.contentDescription
            )
        }

        val restoredRoot = resolveRestorableRootRoute(ctx, snapshot.rootRoute) ?: return null
        val restoredCurrent = resolveRestorableCurrentRoute(ctx, snapshot.currentRoute, restoredRoot)
        val spec = buildWorkspaceSpecForRoutes(
            id = snapshot.id,
            rootRoute = restoredRoot,
            currentRoute = restoredCurrent,
            locked = snapshot.locked,
            closable = snapshot.closable && !snapshot.locked
        )
        return WorkspaceTabModel(
            id = spec.id,
            reuseKey = spec.reuseKey,
            kind = spec.kind,
            tone = spec.tone,
            title = spec.title,
            rootRoute = spec.rootRoute,
            currentRoute = spec.currentRoute,
            selected = false,
            locked = spec.locked,
            closable = spec.closable,
            badgeText = spec.badgeText,
            contentDescription = spec.contentDescription
        )
    }

    private fun resolveRestorableRootRoute(ctx: Context, rawRoot: String): String? {
        val normalized = normalizePath(rawRoot)
        if (normalized.isBlank()) return null
        if (!TermuxPathScope.isVisibleInFileManager(ctx, normalized)) return null
        if (sessionFileCoordinator.isStaleVirtualPath(ctx, normalized)) return null
        if (sessionFileCoordinator.isVirtualPath(ctx, normalized)) {
            return normalized
        }
        val file = File(normalized)
        return normalized.takeIf { file.exists() && file.isDirectory }
    }

    private fun resolveRestorableCurrentRoute(ctx: Context, rawCurrent: String, restoredRoot: String): String {
        val normalized = normalizePath(rawCurrent)
        if (normalized == restoredRoot) return restoredRoot

        if (sessionFileCoordinator.isStaleVirtualPath(ctx, normalized)) {
            return restoredRoot
        }
        if (sessionFileCoordinator.isVirtualPath(ctx, normalized)) {
            return if (normalized.startsWith("$restoredRoot/") || normalized == restoredRoot) normalized else restoredRoot
        }

        if (!normalized.startsWith("$restoredRoot/")) {
            return restoredRoot
        }

        var cursor = normalized
        while (cursor.startsWith("$restoredRoot/")) {
            val file = File(cursor)
            if (file.exists() && file.isDirectory && TermuxPathScope.isVisibleInFileManager(ctx, cursor)) {
                return cursor
            }
            val parent = file.parent?.trimEnd('/').orEmpty().ifBlank { restoredRoot }
            if (parent == cursor || parent.length < restoredRoot.length) {
                break
            }
            cursor = parent
        }
        return restoredRoot
    }

    private fun buildWorkspaceSpecForRoutes(
        id: String,
        rootRoute: String,
        currentRoute: String,
        locked: Boolean = false,
        closable: Boolean = !locked,
        titleOverride: String? = null,
        contentDescriptionOverride: String? = null
    ): WorkspaceTabSpec {
        val ctx = requireNotNull(context)
        val normalizedRoot = normalizePath(rootRoute)
        val normalizedCurrent = normalizePath(currentRoute)
        return WorkspaceTabSpec(
            id = id,
            reuseKey = normalizedRoot,
            kind = resolveWorkspaceKind(normalizedRoot),
            tone = resolveWorkspaceTone(normalizedRoot),
            title = titleOverride?.trim().orEmpty().ifBlank { resolveWorkspaceTitle(ctx, normalizedRoot) },
            rootRoute = normalizedRoot,
            currentRoute = normalizedCurrent,
            locked = locked,
            closable = closable,
            badgeText = resolveWorkspaceBadge(normalizedRoot),
            contentDescription = contentDescriptionOverride?.trim().orEmpty()
                .ifBlank { sessionFileCoordinator.getDisplayPath(ctx, normalizedRoot) }
        )
    }

    class WorkspaceFilesInnerBinding : InnerBinding {
        override val itemsFab: MyFloatingActionButton? = null
    }
}
