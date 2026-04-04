package org.fossify.filemanager.workspace

import com.termux.workspaceshell.model.WorkspaceKind
import com.termux.workspaceshell.model.WorkspaceShellState
import com.termux.workspaceshell.model.WorkspaceTabModel
import com.termux.workspaceshell.model.WorkspaceTabTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSessionSnapshotTest {

    @Test
    fun `snapshot json round trip preserves workspace state`() {
        val state = WorkspaceShellState(
            tabs = listOf(
                tabModel(
                    id = "workspace-home",
                    reuseKey = "workspace-home",
                    kind = WorkspaceKind.HOME,
                    tone = WorkspaceTabTone.HOME,
                    title = "Home",
                    rootRoute = "/nav",
                    currentRoute = "/nav",
                    selected = false,
                    locked = true,
                    closable = false
                ),
                tabModel(
                    id = "project",
                    reuseKey = "/data/data/com.termux/files/home/project",
                    kind = WorkspaceKind.LOCAL,
                    tone = WorkspaceTabTone.LOCAL,
                    title = "project",
                    rootRoute = "/data/data/com.termux/files/home/project",
                    currentRoute = "/data/data/com.termux/files/home/project/src",
                    selected = true,
                    locked = false,
                    closable = true,
                    badgeText = "FAV"
                )
            ),
            activeTabId = "project",
            searchVisible = true,
            searchQueries = mapOf("project" to "gradle")
        )

        val snapshot = WorkspaceSessionSnapshot.fromState(state, updatedAtMs = 1234L)
        val restored = WorkspaceSessionSnapshot.fromJson(snapshot.toJson()).toShellState()

        assertNotNull(restored)
        assertEquals("project", restored?.activeTabId)
        assertTrue(restored?.searchVisible == true)
        assertEquals("gradle", restored?.queryFor("project"))
        assertEquals(
            listOf("workspace-home", "project"),
            restored?.tabs?.map { it.id }
        )
        assertEquals("/data/data/com.termux/files/home/project/src", restored?.activeTab?.currentRoute)
    }

    private fun tabModel(
        id: String,
        reuseKey: String,
        kind: WorkspaceKind,
        tone: WorkspaceTabTone,
        title: String,
        rootRoute: String,
        currentRoute: String,
        selected: Boolean,
        locked: Boolean,
        closable: Boolean,
        badgeText: String? = null
    ): WorkspaceTabModel {
        return WorkspaceTabModel(
            id = id,
            reuseKey = reuseKey,
            kind = kind,
            tone = tone,
            title = title,
            rootRoute = rootRoute,
            currentRoute = currentRoute,
            selected = selected,
            locked = locked,
            closable = closable,
            badgeText = badgeText,
            contentDescription = title
        )
    }
}
