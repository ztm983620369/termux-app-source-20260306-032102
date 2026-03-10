package com.termux.editorsync

class EditorSyncStateMachine {

    private var target: EditorSyncTarget? = null
    private var autoSaveEnabled: Boolean = false
    private var currentRevision: Long = 0L
    private var lastSuccessfulRevision: Long = 0L
    private var inFlightRevision: Long? = null
    private var inFlightTrigger: EditorSaveTrigger? = null
    private var lastError: String? = null
    private var lastSuccessfulSaveAtMs: Long = 0L
    private var lastSuccessfulTrigger: EditorSaveTrigger? = null

    fun bindDocument(target: EditorSyncTarget?, autoSaveEnabled: Boolean, nowMs: Long = System.currentTimeMillis()): EditorSyncSnapshot {
        this.target = target
        this.autoSaveEnabled = autoSaveEnabled
        currentRevision = 0L
        lastSuccessfulRevision = 0L
        inFlightRevision = null
        inFlightTrigger = null
        lastError = null
        lastSuccessfulTrigger = null
        lastSuccessfulSaveAtMs = if (target?.supportsSaving() == true) nowMs else 0L
        return snapshot()
    }

    fun setAutoSaveEnabled(enabled: Boolean): EditorSyncSnapshot {
        autoSaveEnabled = enabled
        return snapshot()
    }

    fun onContentChanged(): EditorSyncSnapshot {
        if (target?.supportsSaving() != true) return snapshot()
        currentRevision += 1L
        lastError = null
        return snapshot()
    }

    fun onSaveStarted(trigger: EditorSaveTrigger, revision: Long): EditorSyncSnapshot {
        if (target?.supportsSaving() != true) return snapshot()
        inFlightRevision = revision
        inFlightTrigger = trigger
        lastError = null
        return snapshot()
    }

    fun onSaveSucceeded(trigger: EditorSaveTrigger, revision: Long, completedAtMs: Long = System.currentTimeMillis()): EditorSyncSnapshot {
        if (target?.supportsSaving() != true) return snapshot()
        if (revision > lastSuccessfulRevision) {
            lastSuccessfulRevision = revision
        }
        inFlightRevision = null
        inFlightTrigger = null
        lastError = null
        lastSuccessfulSaveAtMs = completedAtMs
        lastSuccessfulTrigger = trigger
        return snapshot()
    }

    fun onSaveFailed(trigger: EditorSaveTrigger, revision: Long, error: String): EditorSyncSnapshot {
        if (target?.supportsSaving() != true) return snapshot()
        if (inFlightRevision == revision) {
            inFlightRevision = null
            inFlightTrigger = null
        }
        lastError = error
        lastSuccessfulTrigger = trigger.takeIf { lastSuccessfulTrigger == null }
        return snapshot()
    }

    fun snapshot(): EditorSyncSnapshot {
        val currentTarget = target
        val canSave = currentTarget?.supportsSaving() == true
        val hasUnsavedChanges = canSave && currentRevision > lastSuccessfulRevision
        val saving = inFlightRevision != null
        val indicator = when {
            !canSave -> EditorSyncIndicatorState.HIDDEN
            saving -> EditorSyncIndicatorState.SPINNING
            autoSaveEnabled && hasUnsavedChanges -> EditorSyncIndicatorState.SPINNING
            autoSaveEnabled -> EditorSyncIndicatorState.SYNCED
            else -> EditorSyncIndicatorState.HIDDEN
        }

        val statusText = when (indicator) {
            EditorSyncIndicatorState.HIDDEN -> lastError
            EditorSyncIndicatorState.SPINNING -> {
                if (saving) {
                    when (inFlightTrigger) {
                        EditorSaveTrigger.RUN -> "运行前保存中"
                        EditorSaveTrigger.MANUAL -> "手动保存中"
                        else -> if (currentTarget?.supportsRemoteSync() == true) "自动同步中" else "自动保存中"
                    }
                } else {
                    if (currentTarget?.supportsRemoteSync() == true) "等待自动同步" else "等待自动保存"
                }
            }
            EditorSyncIndicatorState.SYNCED -> if (currentTarget?.supportsRemoteSync() == true) "已同步" else "已保存"
        }

        return EditorSyncSnapshot(
            target = currentTarget,
            autoSaveEnabled = autoSaveEnabled,
            currentRevision = currentRevision,
            lastSuccessfulRevision = lastSuccessfulRevision,
            inFlightRevision = inFlightRevision,
            inFlightTrigger = inFlightTrigger,
            hasUnsavedChanges = hasUnsavedChanges,
            canSave = canSave,
            indicatorState = indicator,
            lastError = lastError,
            lastSuccessfulSaveAtMs = lastSuccessfulSaveAtMs,
            lastSuccessfulTrigger = lastSuccessfulTrigger,
            statusText = statusText
        )
    }
}
