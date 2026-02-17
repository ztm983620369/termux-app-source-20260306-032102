package com.termux.bridge

import android.content.Context
import android.content.Intent
import android.os.Bundle

data class FileOpenRequest(
    val path: String,
    val displayName: String? = null,
    val readOnly: Boolean = false,
    val extension: String? = null,
    val mimeType: String? = null
)

object FileEditorContract {
    const val ACTION_EDIT = "com.termux.bridge.action.EDIT_FILE"

    private const val EXTRA_PATH = "com.termux.bridge.extra.PATH"
    private const val EXTRA_DISPLAY_NAME = "com.termux.bridge.extra.DISPLAY_NAME"
    private const val EXTRA_READ_ONLY = "com.termux.bridge.extra.READ_ONLY"
    private const val EXTRA_EXTENSION = "com.termux.bridge.extra.EXTENSION"
    private const val EXTRA_MIME_TYPE = "com.termux.bridge.extra.MIME_TYPE"

    @JvmStatic
    fun createIntent(context: Context, request: FileOpenRequest): Intent {
        return Intent(ACTION_EDIT)
            .setPackage(context.packageName)
            .putExtras(toBundle(request))
    }

    @JvmStatic
    fun toBundle(request: FileOpenRequest): Bundle {
        return Bundle().apply {
            putString(EXTRA_PATH, request.path)
            putString(EXTRA_DISPLAY_NAME, request.displayName)
            putBoolean(EXTRA_READ_ONLY, request.readOnly)
            putString(EXTRA_EXTENSION, request.extension)
            putString(EXTRA_MIME_TYPE, request.mimeType)
        }
    }

    @JvmStatic
    fun fromIntent(intent: Intent?): FileOpenRequest? {
        val i = intent ?: return null
        val path = i.getStringExtra(EXTRA_PATH) ?: return null
        val displayName = i.getStringExtra(EXTRA_DISPLAY_NAME)
        val readOnly = i.getBooleanExtra(EXTRA_READ_ONLY, false)
        val extension = i.getStringExtra(EXTRA_EXTENSION)
        val mimeType = i.getStringExtra(EXTRA_MIME_TYPE)
        return FileOpenRequest(
            path = path,
            displayName = displayName,
            readOnly = readOnly,
            extension = extension,
            mimeType = mimeType
        )
    }
}
