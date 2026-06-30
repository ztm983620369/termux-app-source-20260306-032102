package com.termux.ecjbridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.database.MatrixCursor
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EcjProjectArchiveProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        requireProjectArchiveUri(uri)
        return "application/zip"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val root = resolveProjectRoot(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add("${root.name}.zip")
                OpenableColumns.SIZE -> row.add(null)
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("ECJ project archives are read-only")
        val root = resolveProjectRoot(uri)
        val pipe = ParcelFileDescriptor.createPipe()
        val input = pipe[0]
        val output = pipe[1]
        Thread({
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { rawOut ->
                ZipOutputStream(rawOut.buffered()).use { zip ->
                    zipProject(root, zip)
                }
            }
        }, "ecj-project-archive-writer").start()
        return input
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = unsupported()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()

    private fun resolveProjectRoot(uri: Uri): File {
        requireProjectArchiveUri(uri)
        val token = uri.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: throw SecurityException("Missing ECJ bridge token")
        val prefs = prefs(context ?: throw SecurityException("Provider not attached"))
        val encoded = prefs.getString(token, null) ?: throw SecurityException("Unknown ECJ bridge token")
        val parts = encoded.split('\n', limit = 2)
        val expiresAt = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() > expiresAt) {
            prefs.edit().remove(token).apply()
            throw SecurityException("Expired ECJ bridge token")
        }
        val rootPath = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: throw SecurityException("Invalid ECJ bridge grant")
        val root = File(rootPath).canonicalFile
        if (!root.exists() || !root.isDirectory) {
            throw IOException("ECJ project no longer exists: ${root.absolutePath}")
        }
        return root
    }

    private fun requireProjectArchiveUri(uri: Uri) {
        if (uri.pathSegments.firstOrNull() != EcjBridgeContract.ARCHIVE_PATH_PREFIX) {
            throw IllegalArgumentException("Unsupported ECJ bridge uri: $uri")
        }
    }

    private fun zipProject(root: File, zip: ZipOutputStream) {
        val canonicalRoot = root.canonicalFile
        var fileCount = 0
        var totalBytes = 0L
        canonicalRoot.walkTopDown().forEach { file ->
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
            if (!isInside(canonicalRoot, canonical)) return@forEach
            if (file == canonicalRoot) return@forEach
            if (fileCount++ > MAX_ARCHIVE_FILES) throw IOException("ECJ project has too many files")

            val relative = canonical.relativeTo(canonicalRoot).invariantSeparatorsPath
            if (shouldSkipArchiveEntry(relative)) return@forEach
            if (file.isDirectory) {
                zip.putNextEntry(ZipEntry("$relative/"))
                zip.closeEntry()
                return@forEach
            }
            if (!file.isFile) return@forEach

            totalBytes += file.length().coerceAtLeast(0L)
            if (totalBytes > MAX_ARCHIVE_BYTES) throw IOException("ECJ project archive is too large")

            zip.putNextEntry(ZipEntry(relative))
            FileInputStream(file).use { input ->
                input.copyTo(zip)
            }
            zip.closeEntry()
        }
    }

    private fun unsupported(): Nothing {
        throw UnsupportedOperationException("ECJ project archive provider is read-only")
    }

    companion object {
        private const val PREFS_NAME = "ecj_bridge_archive_grants"
        private const val GRANT_TTL_MS = 60L * 60L * 1000L
        private const val MAX_ARCHIVE_FILES = 20_000
        private const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L

        fun authorizeProject(context: Context, projectRoot: File): Uri {
            val root = projectRoot.canonicalFile
            if (!root.exists() || !root.isDirectory) {
                throw IOException("ECJ project root is not a directory: ${root.absolutePath}")
            }
            val token = UUID.randomUUID().toString()
            val expiresAt = System.currentTimeMillis() + GRANT_TTL_MS
            prefs(context).edit()
                .putString(token, "$expiresAt\n${root.absolutePath}")
                .apply()

            return Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.ecjbridge")
                .appendPath(EcjBridgeContract.ARCHIVE_PATH_PREFIX)
                .appendPath(token)
                .appendPath("${root.name}.zip")
                .build()
        }

        private fun prefs(context: Context): SharedPreferences {
            return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        private fun isInside(root: File, child: File): Boolean {
            val rootPath = root.canonicalPath.trimEnd(File.separatorChar)
            val childPath = child.canonicalPath
            return childPath == rootPath || childPath.startsWith("$rootPath${File.separator}")
        }

        private fun shouldSkipArchiveEntry(relativePath: String): Boolean {
            val segments = relativePath.split('/')
            if (segments.any { it == ".gradle" || it == "build" || it == ".git" }) return true
            val name = segments.lastOrNull().orEmpty()
            return name.endsWith(".tmp", ignoreCase = true) ||
                name.endsWith(".bak", ignoreCase = true) ||
                name.startsWith(".RealAppScript.java.") ||
                name.contains(".tmp-")
        }
    }
}
