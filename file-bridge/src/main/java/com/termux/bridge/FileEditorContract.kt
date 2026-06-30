package com.termux.bridge

import android.content.Context
import android.content.Intent
import android.os.Bundle

data class FileOpenRequest(
    val path: String,
    val displayName: String? = null,
    val readOnly: Boolean = false,
    val extension: String? = null,
    val mimeType: String? = null,
    val originType: String? = null,
    val originPath: String? = null,
    val originDisplayPath: String? = null,
    val originModifiedMs: Long? = null,
    val originSize: Long? = null,
    val originSha256: String? = null,
    val originFingerprintLevel: String? = null,
    val originFingerprintMethod: String? = null
) {
    companion object {
        const val ORIGIN_LOCAL = "local"
        const val ORIGIN_SFTP_VIRTUAL = "sftp_virtual"
    }
}

object FileEditorContract {
    const val ACTION_EDIT = "com.termux.bridge.action.EDIT_FILE"

    private const val EXTRA_PATH = "com.termux.bridge.extra.PATH"
    private const val EXTRA_DISPLAY_NAME = "com.termux.bridge.extra.DISPLAY_NAME"
    private const val EXTRA_READ_ONLY = "com.termux.bridge.extra.READ_ONLY"
    private const val EXTRA_EXTENSION = "com.termux.bridge.extra.EXTENSION"
    private const val EXTRA_MIME_TYPE = "com.termux.bridge.extra.MIME_TYPE"
    private const val EXTRA_ORIGIN_TYPE = "com.termux.bridge.extra.ORIGIN_TYPE"
    private const val EXTRA_ORIGIN_PATH = "com.termux.bridge.extra.ORIGIN_PATH"
    private const val EXTRA_ORIGIN_DISPLAY_PATH = "com.termux.bridge.extra.ORIGIN_DISPLAY_PATH"
    private const val EXTRA_ORIGIN_MODIFIED_MS = "com.termux.bridge.extra.ORIGIN_MODIFIED_MS"
    private const val EXTRA_ORIGIN_SIZE = "com.termux.bridge.extra.ORIGIN_SIZE"
    private const val EXTRA_ORIGIN_SHA256 = "com.termux.bridge.extra.ORIGIN_SHA256"
    private const val EXTRA_ORIGIN_FINGERPRINT_LEVEL = "com.termux.bridge.extra.ORIGIN_FINGERPRINT_LEVEL"
    private const val EXTRA_ORIGIN_FINGERPRINT_METHOD = "com.termux.bridge.extra.ORIGIN_FINGERPRINT_METHOD"
    private const val NULL_LONG_SENTINEL = Long.MIN_VALUE

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
            putString(EXTRA_ORIGIN_TYPE, request.originType)
            putString(EXTRA_ORIGIN_PATH, request.originPath)
            putString(EXTRA_ORIGIN_DISPLAY_PATH, request.originDisplayPath)
            putLong(EXTRA_ORIGIN_MODIFIED_MS, request.originModifiedMs ?: NULL_LONG_SENTINEL)
            putLong(EXTRA_ORIGIN_SIZE, request.originSize ?: NULL_LONG_SENTINEL)
            putString(EXTRA_ORIGIN_SHA256, request.originSha256)
            putString(EXTRA_ORIGIN_FINGERPRINT_LEVEL, request.originFingerprintLevel)
            putString(EXTRA_ORIGIN_FINGERPRINT_METHOD, request.originFingerprintMethod)
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
        val originType = i.getStringExtra(EXTRA_ORIGIN_TYPE)
        val originPath = i.getStringExtra(EXTRA_ORIGIN_PATH)
        val originDisplayPath = i.getStringExtra(EXTRA_ORIGIN_DISPLAY_PATH)
        val originModifiedMs = i.getLongExtra(EXTRA_ORIGIN_MODIFIED_MS, NULL_LONG_SENTINEL)
            .takeUnless { it == NULL_LONG_SENTINEL }
        val originSize = i.getLongExtra(EXTRA_ORIGIN_SIZE, NULL_LONG_SENTINEL)
            .takeUnless { it == NULL_LONG_SENTINEL }
        val originSha256 = i.getStringExtra(EXTRA_ORIGIN_SHA256)
        val originFingerprintLevel = i.getStringExtra(EXTRA_ORIGIN_FINGERPRINT_LEVEL)
        val originFingerprintMethod = i.getStringExtra(EXTRA_ORIGIN_FINGERPRINT_METHOD)
        return FileOpenRequest(
            path = path,
            displayName = displayName,
            readOnly = readOnly,
            extension = extension,
            mimeType = mimeType,
            originType = originType,
            originPath = originPath,
            originDisplayPath = originDisplayPath,
            originModifiedMs = originModifiedMs,
            originSize = originSize,
            originSha256 = originSha256,
            originFingerprintLevel = originFingerprintLevel,
            originFingerprintMethod = originFingerprintMethod
        )
    }
}
