package org.fossify.filemanager.helpers

import java.util.concurrent.atomic.AtomicLong

enum class ActiveTransferStatus {
    PREPARING,
    RUNNING,
    CANCELLING,
    SUCCESS,
    PARTIAL,
    FAILED,
    CANCELLED
}

enum class ActiveTransferMode {
    NORMAL_DOWNLOAD,
    APK_DOWNLOAD,
    REMOTE_DELETE
}

data class ActiveDownloadSession(
    val id: Long,
    val sourceTopLevelPaths: Set<String>,
    val destinationPath: String,
    val mode: ActiveTransferMode,
    val title: String,
    val startedAtMs: Long,
    val status: ActiveTransferStatus,
    val aggregate: TransferProgressState
)

data class RowTransferState(
    val sessionId: Long,
    val sourcePath: String,
    val status: ActiveTransferStatus,
    val percent: Int,
    val completedFiles: Int,
    val failedFiles: Int,
    val totalFiles: Int,
    val transferredBytes: Long,
    val totalBytes: Long,
    val currentFile: String,
    val message: String
) {
    val isTerminal: Boolean
        get() = status == ActiveTransferStatus.SUCCESS ||
            status == ActiveTransferStatus.PARTIAL ||
            status == ActiveTransferStatus.FAILED ||
            status == ActiveTransferStatus.CANCELLED
}

object ActiveTransferRegistry {
    private val nextSessionId = AtomicLong(1L)
    private val lock = Any()
    private val sessions = LinkedHashMap<Long, ActiveDownloadSession>()
    private val rowStates = LinkedHashMap<String, RowTransferState>()
    private val listeners = LinkedHashSet<(Set<String>) -> Unit>()

    fun addListener(listener: (Set<String>) -> Unit) {
        synchronized(lock) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: (Set<String>) -> Unit) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    fun stateFor(path: String): RowTransferState? {
        return synchronized(lock) {
            rowStates[path]
        }
    }

    fun beginSession(
        sourceTopLevelPaths: Collection<String>,
        destinationPath: String,
        mode: ActiveTransferMode,
        title: String,
        initialFileName: String
    ): Long {
        val sources = sourceTopLevelPaths.filter { it.isNotBlank() }.toCollection(LinkedHashSet())
        if (sources.isEmpty()) return -1L

        val sessionId = nextSessionId.getAndIncrement()
        val aggregate = TransferProgressState(
            phaseLabel = title,
            currentFile = initialFileName.ifBlank { "准备中..." },
            totalFiles = sources.size.coerceAtLeast(1),
            completedFiles = 0,
            failedFiles = 0,
            totalBytes = 0L,
            transferredBytes = 0L,
            currentFileTransferred = 0L,
            currentFileSize = 0L,
            speedBytesPerSecond = 0L,
            detailMessage = "准备中..."
        )
        val session = ActiveDownloadSession(
            id = sessionId,
            sourceTopLevelPaths = sources,
            destinationPath = destinationPath,
            mode = mode,
            title = title,
            startedAtMs = System.currentTimeMillis(),
            status = ActiveTransferStatus.PREPARING,
            aggregate = aggregate
        )
        val changed = synchronized(lock) {
            sessions[sessionId] = session
            sources.forEach { source ->
                rowStates[source] = aggregate.toRowState(
                    sessionId = sessionId,
                    sourcePath = source,
                    status = ActiveTransferStatus.PREPARING,
                    message = "准备中..."
                )
            }
            sources to listeners.toList()
        }
        notifyListeners(changed.first, changed.second)
        return sessionId
    }

    fun updateProgress(sessionId: Long, aggregate: TransferProgressState, message: String = "") {
        updateSession(sessionId, ActiveTransferStatus.RUNNING, aggregate, message.ifBlank { aggregate.detailMessage })
    }

    fun markCancelling(sessionId: Long, message: String = "正在取消...") {
        val session = synchronized(lock) { sessions[sessionId] } ?: return
        updateSession(sessionId, ActiveTransferStatus.CANCELLING, session.aggregate.copy(detailMessage = message), message)
    }

    fun markTerminal(sessionId: Long, status: ActiveTransferStatus, message: String) {
        val terminalStatus = when (status) {
            ActiveTransferStatus.SUCCESS,
            ActiveTransferStatus.PARTIAL,
            ActiveTransferStatus.FAILED,
            ActiveTransferStatus.CANCELLED -> status
            else -> ActiveTransferStatus.FAILED
        }
        val session = synchronized(lock) { sessions[sessionId] } ?: return
        val aggregate = session.aggregate.copy(detailMessage = message)
        updateSession(sessionId, terminalStatus, aggregate, message)
    }

    fun clearSession(sessionId: Long) {
        val changed = synchronized(lock) {
            val session = sessions.remove(sessionId) ?: return
            session.sourceTopLevelPaths.forEach { rowStates.remove(it) }
            session.sourceTopLevelPaths to listeners.toList()
        }
        notifyListeners(changed.first, changed.second)
    }

    private fun updateSession(
        sessionId: Long,
        status: ActiveTransferStatus,
        aggregate: TransferProgressState,
        message: String
    ) {
        val changed = synchronized(lock) {
            val session = sessions[sessionId] ?: return
            val updated = session.copy(status = status, aggregate = aggregate)
            sessions[sessionId] = updated
            updated.sourceTopLevelPaths.forEach { source ->
                rowStates[source] = aggregate.toRowState(
                    sessionId = sessionId,
                    sourcePath = source,
                    status = status,
                    message = message
                )
            }
            updated.sourceTopLevelPaths to listeners.toList()
        }
        notifyListeners(changed.first, changed.second)
    }

    private fun TransferProgressState.toRowState(
        sessionId: Long,
        sourcePath: String,
        status: ActiveTransferStatus,
        message: String
    ): RowTransferState {
        return RowTransferState(
            sessionId = sessionId,
            sourcePath = sourcePath,
            status = status,
            percent = percent(),
            completedFiles = completedFiles,
            failedFiles = failedFiles,
            totalFiles = totalFiles,
            transferredBytes = transferredBytes,
            totalBytes = totalBytes,
            currentFile = currentFile,
            message = message
        )
    }

    private fun notifyListeners(changedPaths: Set<String>, snapshot: List<(Set<String>) -> Unit>) {
        if (changedPaths.isEmpty()) return
        snapshot.forEach { listener ->
            listener(changedPaths)
        }
    }
}
