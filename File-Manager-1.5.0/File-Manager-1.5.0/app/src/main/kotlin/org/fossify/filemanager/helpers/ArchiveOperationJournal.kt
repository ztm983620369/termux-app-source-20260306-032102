package org.fossify.filemanager.helpers

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.termux.sessionsync.FileRootResolver
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

internal class ArchiveOperationJournal(context: Context) {
    enum class Status {
        SUCCEEDED,
        FAILED,
        CANCELLED,
        INTERRUPTED
    }

    class Handle internal constructor(
        internal val id: String,
        internal val stateFile: File,
        internal val state: JSONObject
    )

    private val appContext = context.applicationContext
    private val filesRoot = appContext.filesDir.canonicalFile
    private val root = File(FileRootResolver.resolveTransferRoot(appContext), JOURNAL_DIRECTORY)
    private val activeRoot = File(root, ACTIVE_DIRECTORY)
    private val eventFile = File(root, EVENT_FILE)

    init {
        synchronized(lock) {
            ensureDirectory(root)
            ensureDirectory(activeRoot)
            if (!recoveryDone) {
                recoverInterruptedLocked()
                cleanupOrphanJobsLocked()
                recoveryDone = true
            }
        }
    }

    fun start(
        operation: String,
        sourcePaths: List<String>,
        destinationPath: String,
        tempPaths: List<String> = emptyList()
    ): Handle {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val id = "archive-${UUID.randomUUID()}"
            val state = JSONObject().apply {
                put("schemaVersion", SCHEMA_VERSION)
                put("operationId", id)
                put("operation", bounded(operation, 64))
                put("status", "ACTIVE")
                put("phase", "STARTED")
                put("createdAtMs", now)
                put("updatedAtMs", now)
                put("sourcePaths", stringArray(sourcePaths, MAX_SOURCE_PATHS))
                put("destinationPath", bounded(destinationPath, MAX_PATH_LENGTH))
                put("tempPaths", stringArray(tempPaths, MAX_TEMP_PATHS))
            }
            val stateFile = File(activeRoot, "$id.json")
            persistStateLocked(stateFile, state)
            appendEventLocked(state)
            return Handle(id, stateFile, state)
        }
    }

    fun phase(
        handle: Handle,
        phase: String,
        message: String = "",
        backend: String = "",
        tempPath: String = ""
    ) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            handle.state.put("phase", bounded(phase.uppercase(Locale.ROOT), 64))
            handle.state.put("updatedAtMs", now)
            if (message.isNotBlank()) handle.state.put("message", bounded(message, MAX_MESSAGE_LENGTH))
            if (backend.isNotBlank()) handle.state.put("backend", bounded(backend, 64))
            if (tempPath.isNotBlank()) addUniqueString(handle.state, "tempPaths", tempPath, MAX_TEMP_PATHS)
            persistStateLocked(handle.stateFile, handle.state)
            appendEventLocked(handle.state)
        }
    }

    fun finish(handle: Handle, status: Status, message: String, targetPath: String = "") {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            handle.state.put("status", status.name)
            handle.state.put("phase", "FINISHED")
            handle.state.put("message", bounded(message, MAX_MESSAGE_LENGTH))
            handle.state.put("updatedAtMs", now)
            handle.state.put("finishedAtMs", now)
            if (targetPath.isNotBlank()) {
                handle.state.put("targetPath", bounded(targetPath, MAX_PATH_LENGTH))
            }
            appendEventLocked(handle.state)
            if (!handle.stateFile.delete() && handle.stateFile.exists()) {
                Log.w(TAG, "Unable to delete archive active state ${handle.stateFile}")
            }
        }
    }

    fun eventLogPath(): String = eventFile.absolutePath

    private fun recoverInterruptedLocked() {
        val activeFiles = activeRoot.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?: return
        activeFiles.forEach { stateFile ->
            val state = runCatching { JSONObject(stateFile.readText(StandardCharsets.UTF_8)) }.getOrNull()
            if (state == null) {
                stateFile.renameTo(File(activeRoot, "${stateFile.name}.corrupt"))
                return@forEach
            }
            state.put("status", Status.INTERRUPTED.name)
            state.put("phase", "RECOVERED")
            state.put("message", "应用进程在归档任务完成前退出，已清理受管临时文件。")
            state.put("updatedAtMs", System.currentTimeMillis())
            state.put("finishedAtMs", System.currentTimeMillis())
            appendEventLocked(state)

            val tempPaths = state.optJSONArray("tempPaths") ?: JSONArray()
            for (index in 0 until tempPaths.length()) {
                val path = tempPaths.optString(index, "")
                if (isManagedTempPath(path)) deleteManagedTree(File(path))
            }
            stateFile.delete()
        }
    }

    private fun isManagedTempPath(rawPath: String): Boolean {
        if (rawPath.isBlank()) return false
        val file = runCatching { File(rawPath).canonicalFile }.getOrNull() ?: return false
        val filesPath = filesRoot.absolutePath.trimEnd(File.separatorChar)
        val path = file.absolutePath
        if (path != filesPath && !path.startsWith("$filesPath${File.separator}")) return false
        val normalized = path.replace('\\', '/')
        return normalized.contains("/.termux/sftp-transfer/archive-jobs/job-") ||
            (file.name.startsWith(".") && file.name.contains(".termux-archive-") && file.name.endsWith(".part"))
    }

    private fun persistStateLocked(target: File, state: JSONObject) {
        ensureDirectory(target.parentFile ?: root)
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.part")
        try {
            FileOutputStream(temp).use { output ->
                output.write(state.toString().toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            Os.rename(temp.absolutePath, target.absolutePath)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun appendEventLocked(state: JSONObject) {
        rotateIfNeededLocked()
        val event = JSONObject(state.toString()).apply {
            put("recordedAtMs", System.currentTimeMillis())
        }
        FileOutputStream(eventFile, true).use { output ->
            output.write(event.toString().toByteArray(StandardCharsets.UTF_8))
            output.write('\n'.code)
            output.fd.sync()
        }
    }

    private fun rotateIfNeededLocked() {
        if (!eventFile.exists() || eventFile.length() < MAX_EVENT_FILE_BYTES) return
        for (index in MAX_ROTATED_FILES downTo 1) {
            val source = if (index == 1) eventFile else File(root, "$EVENT_FILE.${index - 1}")
            val target = File(root, "$EVENT_FILE.$index")
            if (!source.exists()) continue
            if (target.exists()) target.delete()
            source.renameTo(target)
        }
    }

    private fun deleteManagedTree(file: File) {
        if (!file.exists() && !isSymbolicLink(file)) return
        if (!isSymbolicLink(file) && file.isDirectory) {
            file.listFiles()?.forEach(::deleteManagedTree)
        }
        file.delete()
    }

    private fun cleanupOrphanJobsLocked() {
        val archiveJobs = File(FileRootResolver.resolveTransferRoot(appContext), "archive-jobs")
        val now = System.currentTimeMillis()
        archiveJobs.listFiles { file -> file.isDirectory && file.name.startsWith("job-") }
            ?.filter { now - it.lastModified() >= ORPHAN_JOB_MIN_AGE_MS }
            ?.forEach(::deleteManagedTree)
    }

    private fun isSymbolicLink(file: File): Boolean {
        return runCatching { OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode) }.getOrDefault(false)
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw IllegalStateException("无法创建归档日志目录：${directory.absolutePath}")
        }
    }

    private fun addUniqueString(root: JSONObject, key: String, value: String, limit: Int) {
        val normalized = bounded(value, MAX_PATH_LENGTH)
        val array = root.optJSONArray(key) ?: JSONArray().also { root.put(key, it) }
        for (index in 0 until array.length()) {
            if (array.optString(index) == normalized) return
        }
        if (array.length() < limit) array.put(normalized)
    }

    private fun stringArray(values: List<String>, limit: Int): JSONArray {
        return JSONArray().apply {
            values.asSequence()
                .map { bounded(it, MAX_PATH_LENGTH) }
                .filter(String::isNotBlank)
                .distinct()
                .take(limit)
                .forEach(::put)
        }
    }

    private fun bounded(value: String, maxLength: Int): String {
        val normalized = value.replace("\u0000", "").trim()
        return if (normalized.length <= maxLength) normalized else normalized.take(maxLength)
    }

    companion object {
        private const val TAG = "ArchiveJournal"
        private const val SCHEMA_VERSION = 1
        private const val JOURNAL_DIRECTORY = "archive-operations"
        private const val ACTIVE_DIRECTORY = "active"
        private const val EVENT_FILE = "operations.jsonl"
        private const val MAX_EVENT_FILE_BYTES = 4L * 1024L * 1024L
        private const val MAX_ROTATED_FILES = 4
        private const val MAX_PATH_LENGTH = 4096
        private const val MAX_MESSAGE_LENGTH = 4096
        private const val MAX_SOURCE_PATHS = 64
        private const val MAX_TEMP_PATHS = 16
        private const val ORPHAN_JOB_MIN_AGE_MS = 60L * 60L * 1000L
        private val lock = Any()
        @Volatile private var recoveryDone = false
    }
}
