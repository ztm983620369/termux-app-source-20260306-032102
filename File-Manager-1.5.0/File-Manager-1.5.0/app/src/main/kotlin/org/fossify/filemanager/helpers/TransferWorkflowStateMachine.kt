package org.fossify.filemanager.helpers

import org.fossify.commons.dialogs.FilePickerDialog
import java.util.concurrent.atomic.AtomicReference

enum class TransferSelectionKind {
    NONE,
    LOCAL_ONLY,
    REMOTE_ONLY,
    MIXED
}

enum class TransferWorkflowStage {
    IDLE,
    PICKING_TARGET,
    RELAYING,
    UPLOADING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class TransferSourceSnapshot(
    val selectionKind: TransferSelectionKind,
    val isCopyOperation: Boolean,
    val totalCount: Int,
    val localCount: Int,
    val remoteCount: Int
)

sealed class TransferExecutionPlan {
    data class LocalCopy(val destinationPath: String, val isMoveOperation: Boolean) : TransferExecutionPlan()
    data class Upload(val destinationVirtualPath: String) : TransferExecutionPlan()
    data class Download(val destinationLocalPath: String) : TransferExecutionPlan()
    data class RemoteTransfer(val destinationVirtualPath: String) : TransferExecutionPlan()
    data class Unsupported(val message: String) : TransferExecutionPlan()
}

data class TransferWorkflowSnapshot(
    val stage: TransferWorkflowStage = TransferWorkflowStage.IDLE,
    val selection: TransferSourceSnapshot? = null,
    val label: String? = null,
    val detail: String? = null
)

class TransferWorkflowStateMachine {

    private val snapshotRef = AtomicReference(TransferWorkflowSnapshot())

    fun analyzeSources(paths: List<String>, isCopyOperation: Boolean, isVirtualPath: (String) -> Boolean): TransferSourceSnapshot {
        if (paths.isEmpty()) {
            return TransferSourceSnapshot(
                selectionKind = TransferSelectionKind.NONE,
                isCopyOperation = isCopyOperation,
                totalCount = 0,
                localCount = 0,
                remoteCount = 0
            )
        }

        var localCount = 0
        var remoteCount = 0
        paths.forEach { path ->
            if (isVirtualPath(path)) remoteCount++ else localCount++
        }

        val selectionKind = when {
            localCount > 0 && remoteCount > 0 -> TransferSelectionKind.MIXED
            remoteCount > 0 -> TransferSelectionKind.REMOTE_ONLY
            localCount > 0 -> TransferSelectionKind.LOCAL_ONLY
            else -> TransferSelectionKind.NONE
        }

        return TransferSourceSnapshot(
            selectionKind = selectionKind,
            isCopyOperation = isCopyOperation,
            totalCount = paths.size,
            localCount = localCount,
            remoteCount = remoteCount
        )
    }

    fun pickerScopeFor(source: TransferSourceSnapshot): FilePickerDialog.TargetScope {
        return when {
            !source.isCopyOperation -> FilePickerDialog.TargetScope.LOCAL_ONLY
            source.selectionKind == TransferSelectionKind.REMOTE_ONLY -> FilePickerDialog.TargetScope.ANY
            else -> FilePickerDialog.TargetScope.ANY
        }
    }

    fun onPickerOpened(source: TransferSourceSnapshot) {
        snapshotRef.set(
            TransferWorkflowSnapshot(
                stage = TransferWorkflowStage.PICKING_TARGET,
                selection = source,
                label = "pick-target",
                detail = source.selectionKind.name
            )
        )
    }

    fun resolvePlan(
        source: TransferSourceSnapshot,
        targetPath: String,
        isVirtualPath: (String) -> Boolean
    ): TransferExecutionPlan {
        val targetIsVirtual = isVirtualPath(targetPath)

        return when (source.selectionKind) {
            TransferSelectionKind.NONE -> {
                TransferExecutionPlan.Unsupported("未选择任何传输项目。")
            }

            TransferSelectionKind.MIXED -> {
                TransferExecutionPlan.Unsupported("请分开选择本地与服务器项目后再传输。")
            }

            TransferSelectionKind.LOCAL_ONLY -> {
                if (!source.isCopyOperation && targetIsVirtual) {
                    TransferExecutionPlan.Unsupported("上传到服务器暂不支持“移动到”，请使用“复制到”。")
                } else if (targetIsVirtual) {
                    TransferExecutionPlan.Upload(targetPath)
                } else {
                    TransferExecutionPlan.LocalCopy(
                        destinationPath = targetPath,
                        isMoveOperation = !source.isCopyOperation
                    )
                }
            }

            TransferSelectionKind.REMOTE_ONLY -> {
                if (!source.isCopyOperation) {
                    TransferExecutionPlan.Unsupported("服务器项目暂不支持“移动到”，请使用“复制到”。")
                } else if (targetIsVirtual) {
                    TransferExecutionPlan.RemoteTransfer(targetPath)
                } else {
                    TransferExecutionPlan.Download(targetPath)
                }
            }
        }
    }

    fun begin(stage: TransferWorkflowStage, label: String): Boolean {
        while (true) {
            val current = snapshotRef.get()
            val currentStage = current.stage
            if (
                currentStage == TransferWorkflowStage.UPLOADING
                || currentStage == TransferWorkflowStage.DOWNLOADING
                || currentStage == TransferWorkflowStage.RELAYING
            ) {
                return false
            }
            val next = current.copy(stage = stage, label = label, detail = null)
            if (snapshotRef.compareAndSet(current, next)) {
                return true
            }
        }
    }

    fun markCompleted(detail: String? = null) {
        snapshotRef.updateAndGet {
            it.copy(stage = TransferWorkflowStage.COMPLETED, detail = detail)
        }
    }

    fun markFailed(detail: String? = null) {
        snapshotRef.updateAndGet {
            it.copy(stage = TransferWorkflowStage.FAILED, detail = detail)
        }
    }

    fun markCancelled(detail: String? = null) {
        snapshotRef.updateAndGet {
            it.copy(stage = TransferWorkflowStage.CANCELLED, detail = detail)
        }
    }

    fun reset() {
        snapshotRef.set(TransferWorkflowSnapshot())
    }

    fun snapshot(): TransferWorkflowSnapshot = snapshotRef.get()
}
