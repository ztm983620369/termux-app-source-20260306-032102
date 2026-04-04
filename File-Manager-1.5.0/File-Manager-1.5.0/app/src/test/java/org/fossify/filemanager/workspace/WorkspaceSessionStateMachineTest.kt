package org.fossify.filemanager.workspace

import com.termux.workspaceshell.model.WorkspaceKind
import com.termux.workspaceshell.model.WorkspaceShellState
import com.termux.workspaceshell.model.WorkspaceTabModel
import com.termux.workspaceshell.model.WorkspaceTabTone
import com.termux.workspaceshell.state.WorkspaceShellAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSessionStateMachineTest {

    @Test
    fun `dispatch updates state, notifies listeners and persists latest snapshot`() {
        val store = InMemoryWorkspaceSessionStore()
        val initialState = WorkspaceShellState(
            tabs = listOf(
                WorkspaceTabModel(
                    id = "workspace-home",
                    reuseKey = "workspace-home",
                    kind = WorkspaceKind.HOME,
                    tone = WorkspaceTabTone.HOME,
                    title = "Home",
                    rootRoute = "/nav",
                    currentRoute = "/nav",
                    selected = true,
                    locked = true,
                    closable = false
                )
            ),
            activeTabId = "workspace-home"
        )
        val machine = WorkspaceSessionStateMachine(store, initialState)

        var listenerCalls = 0
        machine.addListener(WorkspaceSessionStateMachine.Listener {
            listenerCalls++
        })

        machine.dispatch(WorkspaceShellAction.ShowSearch)

        assertTrue(store.asyncSaveLatch.await(2, TimeUnit.SECONDS))
        assertTrue(machine.currentState().searchVisible)
        assertTrue(store.lastAsyncSnapshot.searchVisible)
        assertEquals(1, listenerCalls)
    }

    private class InMemoryWorkspaceSessionStore : WorkspaceSessionStore {
        val asyncSaveLatch = CountDownLatch(1)
        var lastAsyncSnapshot: WorkspaceSessionSnapshot = WorkspaceSessionSnapshot.empty()

        override fun save(snapshot: WorkspaceSessionSnapshot) {
            lastAsyncSnapshot = snapshot
            asyncSaveLatch.countDown()
        }

        override fun saveBlocking(snapshot: WorkspaceSessionSnapshot) {
            lastAsyncSnapshot = snapshot
        }

        override fun load(): WorkspaceSessionSnapshot = lastAsyncSnapshot
    }
}
