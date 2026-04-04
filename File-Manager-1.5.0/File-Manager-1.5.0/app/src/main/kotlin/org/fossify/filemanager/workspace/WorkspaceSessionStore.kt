package org.fossify.filemanager.workspace

interface WorkspaceSessionStore {
    fun save(snapshot: WorkspaceSessionSnapshot)
    fun saveBlocking(snapshot: WorkspaceSessionSnapshot)
    fun load(): WorkspaceSessionSnapshot

    fun clear() {
        saveBlocking(WorkspaceSessionSnapshot.empty())
    }
}
