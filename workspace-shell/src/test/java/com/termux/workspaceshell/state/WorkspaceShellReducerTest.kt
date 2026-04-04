package com.termux.workspaceshell.state

import com.termux.workspaceshell.model.WorkspaceKind
import com.termux.workspaceshell.model.WorkspaceReusePolicy
import com.termux.workspaceshell.model.WorkspaceTabSpec
import com.termux.workspaceshell.model.WorkspaceTabTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceShellReducerTest {

    @Test
    fun `open tab reuses matching workspace by key`() {
        val initial = WorkspaceShellReducer.createInitialState(
            spec(
                id = "home",
                reuseKey = "home",
                title = "Home",
                rootRoute = "/nav",
                currentRoute = "/nav",
                kind = WorkspaceKind.HOME,
                tone = WorkspaceTabTone.HOME,
                locked = true
            )
        )

        val opened = WorkspaceShellReducer.reduce(
            initial,
            WorkspaceShellAction.OpenTab(
                spec(
                    id = "work-1",
                    reuseKey = "/data/data/com.termux/files/home",
                    title = "Local",
                    rootRoute = "/data/data/com.termux/files/home",
                    currentRoute = "/data/data/com.termux/files/home",
                    kind = WorkspaceKind.LOCAL,
                    tone = WorkspaceTabTone.LOCAL
                )
            )
        )
        val reused = WorkspaceShellReducer.reduce(
            opened,
            WorkspaceShellAction.OpenTab(
                spec(
                    id = "work-2",
                    reuseKey = "/data/data/com.termux/files/home",
                    title = "Local",
                    rootRoute = "/data/data/com.termux/files/home",
                    currentRoute = "/data/data/com.termux/files/home/projects",
                    kind = WorkspaceKind.LOCAL,
                    tone = WorkspaceTabTone.LOCAL
                ),
                reusePolicy = WorkspaceReusePolicy.REUSE_BY_KEY
            )
        )

        assertEquals(2, reused.tabs.size)
        assertEquals("work-1", reused.activeTabId)
        assertEquals("/data/data/com.termux/files/home/projects", reused.activeTab?.currentRoute)
    }

    @Test
    fun `close tab keeps locked home tab`() {
        val initial = WorkspaceShellReducer.createInitialState(
            spec(
                id = "home",
                reuseKey = "home",
                title = "Home",
                rootRoute = "/nav",
                currentRoute = "/nav",
                kind = WorkspaceKind.HOME,
                tone = WorkspaceTabTone.HOME,
                locked = true
            )
        )
        val opened = WorkspaceShellReducer.reduce(
            initial,
            WorkspaceShellAction.OpenTab(
                spec(
                    id = "work-1",
                    reuseKey = "/srv",
                    title = "Remote",
                    rootRoute = "/srv",
                    currentRoute = "/srv",
                    kind = WorkspaceKind.REMOTE,
                    tone = WorkspaceTabTone.REMOTE
                )
            )
        )
        val closed = WorkspaceShellReducer.reduce(opened, WorkspaceShellAction.CloseTab("work-1"))

        assertEquals(1, closed.tabs.size)
        assertEquals("home", closed.activeTabId)
        assertFalse(closed.tabs.first().closable)
        assertTrue(closed.tabs.first().locked)
    }

    private fun spec(
        id: String,
        reuseKey: String,
        title: String,
        rootRoute: String,
        currentRoute: String,
        kind: WorkspaceKind,
        tone: WorkspaceTabTone,
        locked: Boolean = false
    ) = WorkspaceTabSpec(
        id = id,
        reuseKey = reuseKey,
        kind = kind,
        tone = tone,
        title = title,
        rootRoute = rootRoute,
        currentRoute = currentRoute,
        locked = locked,
        closable = !locked
    )
}
