package org.fossify.filemanager.workspace

import com.termux.workspaceshell.model.WorkspaceShellState
import com.termux.workspaceshell.model.WorkspaceTabModel

class WorkspaceSessionReconciler(
    private val homeTab: () -> WorkspaceTabModel,
    private val restoreTab: (WorkspaceTabSnapshot) -> WorkspaceTabModel?
) {

    fun reconcile(snapshot: WorkspaceSessionSnapshot): WorkspaceShellState? {
        if (snapshot.isEmpty()) return null

        val restoredTabs = ArrayList<WorkspaceTabModel>()
        val canonicalHome = homeTab().copy(selected = false)
        restoredTabs.add(canonicalHome)

        val seenKeys = HashSet<String>()
        seenKeys.add(dedupeKey(canonicalHome))

        snapshot.tabs.forEach { tab ->
            val restored = restoreTab(tab)?.copy(selected = false) ?: return@forEach
            if (restored.id == canonicalHome.id) return@forEach

            val dedupeKey = dedupeKey(restored)
            if (!seenKeys.add(dedupeKey)) return@forEach
            restoredTabs.add(restored)
        }

        if (restoredTabs.isEmpty()) return null

        val resolvedActiveId = restoredTabs.firstOrNull { it.id == snapshot.activeTabId }?.id ?: restoredTabs.first().id
        val queries = LinkedHashMap<String, String>()
        snapshot.searchQueries.forEach { (tabId, query) ->
            if (query.isNotBlank() && restoredTabs.any { it.id == tabId }) {
                queries[tabId] = query
            }
        }

        return WorkspaceShellState(
            tabs = restoredTabs.map { it.copy(selected = it.id == resolvedActiveId) },
            activeTabId = resolvedActiveId,
            searchVisible = snapshot.searchVisible,
            searchQueries = queries
        )
    }

    private fun dedupeKey(tab: WorkspaceTabModel): String {
        return "${tab.kind.name}:${tab.reuseKey}"
    }
}
