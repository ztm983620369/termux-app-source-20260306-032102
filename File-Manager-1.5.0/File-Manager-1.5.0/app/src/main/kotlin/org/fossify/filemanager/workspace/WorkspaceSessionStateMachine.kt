package org.fossify.filemanager.workspace

import com.termux.workspaceshell.model.WorkspaceShellState
import com.termux.workspaceshell.state.WorkspaceShellAction
import com.termux.workspaceshell.state.WorkspaceShellReducer
import java.util.concurrent.Executors

class WorkspaceSessionStateMachine(
    private val store: WorkspaceSessionStore,
    initialState: WorkspaceShellState
) {

    fun interface Listener {
        fun onStateChanged(state: WorkspaceShellState)
    }

    private val lock = Any()
    private val listeners = LinkedHashSet<Listener>()

    @Volatile
    private var state: WorkspaceShellState = initialState

    @Volatile
    private var pendingPersistSnapshot: WorkspaceSessionSnapshot? = null

    @Volatile
    private var persistDrainScheduled = false

    fun currentState(): WorkspaceShellState = state

    fun loadPersistedSnapshot(): WorkspaceSessionSnapshot = store.load()

    fun dispatch(action: WorkspaceShellAction): WorkspaceShellState {
        val nextState: WorkspaceShellState
        val changed: Boolean
        synchronized(lock) {
            val current = state
            nextState = WorkspaceShellReducer.reduce(current, action)
            changed = current != nextState
            if (changed) {
                state = nextState
            }
        }
        if (!changed) {
            return nextState
        }
        enqueuePersistSnapshot(WorkspaceSessionSnapshot.fromState(nextState))
        notifyListeners(nextState)
        return nextState
    }

    fun replaceState(nextState: WorkspaceShellState, persist: Boolean = true): WorkspaceShellState {
        val changed: Boolean
        synchronized(lock) {
            changed = state != nextState
            state = nextState
        }
        if (persist) {
            enqueuePersistSnapshot(WorkspaceSessionSnapshot.fromState(nextState))
        }
        if (changed) {
            notifyListeners(nextState)
        }
        return nextState
    }

    fun persistCurrentStateBlocking() {
        store.saveBlocking(WorkspaceSessionSnapshot.fromState(currentState()))
    }

    fun addListener(listener: Listener, emitImmediately: Boolean = false) {
        synchronized(lock) {
            listeners.add(listener)
        }
        if (emitImmediately) {
            listener.onStateChanged(currentState())
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    private fun notifyListeners(nextState: WorkspaceShellState) {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { it.onStateChanged(nextState) }
    }

    private fun enqueuePersistSnapshot(snapshot: WorkspaceSessionSnapshot) {
        synchronized(lock) {
            pendingPersistSnapshot = snapshot
            if (persistDrainScheduled) return
            persistDrainScheduled = true
        }
        persistenceExecutor.execute(::drainPersistQueue)
    }

    private fun drainPersistQueue() {
        while (true) {
            val toPersist = synchronized(lock) {
                val snapshot = pendingPersistSnapshot
                pendingPersistSnapshot = null
                if (snapshot == null) {
                    persistDrainScheduled = false
                }
                snapshot
            } ?: return

            store.save(toPersist)
        }
    }

    companion object {
        private val persistenceExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "workspace-session-store").apply {
                setPriority(Thread.NORM_PRIORITY - 1)
            }
        }
    }
}
