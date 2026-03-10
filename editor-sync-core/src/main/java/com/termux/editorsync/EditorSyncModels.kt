package com.termux.editorsync

enum class EditorSyncTargetKind {
    LOCAL_FILE,
    SFTP_VIRTUAL_FILE
}

enum class EditorSaveTrigger {
    AUTO,
    MANUAL,
    RUN
}

enum class EditorSyncIndicatorState {
    HIDDEN,
    SPINNING,
    SYNCED
}

data class EditorSyncTarget(
    val localPath: String,
    val displayName: String? = null,
    val readOnly: Boolean = false,
    val extension: String? = null,
    val mimeType: String? = null,
    val kind: EditorSyncTargetKind = EditorSyncTargetKind.LOCAL_FILE,
    val originPath: String? = null,
    val originDisplayPath: String? = null
) {
    fun supportsSaving(): Boolean {
        return !readOnly && localPath.isNotBlank()
    }

    fun supportsRemoteSync(): Boolean {
        return kind == EditorSyncTargetKind.SFTP_VIRTUAL_FILE && !originPath.isNullOrBlank()
    }
}

data class EditorSaveResult(
    val ok: Boolean,
    val targetPath: String?,
    val bytes: Int,
    val elapsedMs: Long,
    val error: String?,
    val trigger: EditorSaveTrigger,
    val remoteSynced: Boolean
)

data class EditorSyncSnapshot(
    val target: EditorSyncTarget? = null,
    val autoSaveEnabled: Boolean = false,
    val currentRevision: Long = 0L,
    val lastSuccessfulRevision: Long = 0L,
    val inFlightRevision: Long? = null,
    val inFlightTrigger: EditorSaveTrigger? = null,
    val hasUnsavedChanges: Boolean = false,
    val canSave: Boolean = false,
    val indicatorState: EditorSyncIndicatorState = EditorSyncIndicatorState.HIDDEN,
    val lastError: String? = null,
    val lastSuccessfulSaveAtMs: Long = 0L,
    val lastSuccessfulTrigger: EditorSaveTrigger? = null,
    val statusText: String? = null
) {
    val shouldShowIndicator: Boolean
        get() = indicatorState != EditorSyncIndicatorState.HIDDEN
}
