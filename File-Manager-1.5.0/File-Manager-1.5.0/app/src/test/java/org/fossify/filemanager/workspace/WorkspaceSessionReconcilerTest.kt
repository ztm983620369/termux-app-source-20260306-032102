package org.fossify.filemanager.workspace

import com.termux.workspaceshell.model.WorkspaceKind
import com.termux.workspaceshell.model.WorkspaceTabModel
import com.termux.workspaceshell.model.WorkspaceTabTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSessionReconcilerTest {

    @Test
    fun `reconciler injects canonical home and drops invalid or duplicate tabs`() {
        val canonicalHome = tabModel(
            id = "workspace-home",
            reuseKey = "workspace-home",
            kind = WorkspaceKind.HOME,
            tone = WorkspaceTabTone.HOME,
            title = "Home",
            rootRoute = "/nav",
            currentRoute = "/nav",
            locked = true,
            closable = false
        )
        val validProject = tabModel(
            id = "project",
            reuseKey = "/home/project",
            kind = WorkspaceKind.LOCAL,
            tone = WorkspaceTabTone.LOCAL,
            title = "project",
            rootRoute = "/home/project",
            currentRoute = "/home/project/src"
        )
        val duplicateProject = validProject.copy(id = "project-duplicate", selected = false)

        val reconciler = WorkspaceSessionReconciler(
            homeTab = { canonicalHome },
            restoreTab = { snapshot ->
                when (snapshot.id) {
                    "project" -> validProject
                    "project-duplicate" -> duplicateProject
                    else -> null
                }
            }
        )

        val state = reconciler.reconcile(
            WorkspaceSessionSnapshot(
                tabs = listOf(
                    WorkspaceTabSnapshot(
                        id = "workspace-home",
                        reuseKey = "workspace-home",
                        kind = WorkspaceKind.HOME,
                        tone = WorkspaceTabTone.HOME,
                        title = "Old Home",
                        rootRoute = "/old-nav",
                        currentRoute = "/old-nav",
                        locked = true,
                        closable = false
                    ),
                    WorkspaceTabSnapshot(
                        id = "project",
                        reuseKey = "/home/project",
                        kind = WorkspaceKind.LOCAL,
                        tone = WorkspaceTabTone.LOCAL,
                        title = "project",
                        rootRoute = "/home/project",
                        currentRoute = "/home/project/src",
                        locked = false,
                        closable = true
                    ),
                    WorkspaceTabSnapshot(
                        id = "project-duplicate",
                        reuseKey = "/home/project",
                        kind = WorkspaceKind.LOCAL,
                        tone = WorkspaceTabTone.LOCAL,
                        title = "project",
                        rootRoute = "/home/project",
                        currentRoute = "/home/project/src",
                        locked = false,
                        closable = true
                    ),
                    WorkspaceTabSnapshot(
                        id = "missing",
                        reuseKey = "/missing",
                        kind = WorkspaceKind.LOCAL,
                        tone = WorkspaceTabTone.LOCAL,
                        title = "missing",
                        rootRoute = "/missing",
                        currentRoute = "/missing",
                        locked = false,
                        closable = true
                    )
                ),
                activeTabId = "missing",
                searchVisible = true,
                searchQueries = mapOf(
                    "project" to "gradle",
                    "missing" to "orphan"
                )
            )
        )

        assertEquals(listOf("workspace-home", "project"), state?.tabs?.map { it.id })
        assertEquals("workspace-home", state?.activeTabId)
        assertEquals("gradle", state?.queryFor("project"))
        assertTrue(state?.tabs?.firstOrNull()?.selected == true)
    }

    private fun tabModel(
        id: String,
        reuseKey: String,
        kind: WorkspaceKind,
        tone: WorkspaceTabTone,
        title: String,
        rootRoute: String,
        currentRoute: String,
        locked: Boolean = false,
        closable: Boolean = true
    ): WorkspaceTabModel {
        return WorkspaceTabModel(
            id = id,
            reuseKey = reuseKey,
            kind = kind,
            tone = tone,
            title = title,
            rootRoute = rootRoute,
            currentRoute = currentRoute,
            selected = false,
            locked = locked,
            closable = closable,
            badgeText = null,
            contentDescription = title
        )
    }
}
