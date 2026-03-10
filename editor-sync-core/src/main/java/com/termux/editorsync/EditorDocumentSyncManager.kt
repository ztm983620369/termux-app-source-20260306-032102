package com.termux.editorsync

import android.content.Context
import android.content.SharedPreferences
import com.termux.sessionsync.SessionFileCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicLong

class EditorDocumentSyncManager(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val textSnapshotProvider: () -> String,
    private val sessionFileCoordinator: SessionFileCoordinator = SessionFileCoordinator.getInstance()
) {

    companion object {
        const val PREF_AUTO_SAVE_ENABLED = "autosave.enabled"
        private const val DEFAULT_DEBOUNCE_MS = 350L
    }

    private val stateMachine = EditorSyncStateMachine()
    private val stateFlow = MutableStateFlow(
        stateMachine.bindDocument(
            target = null,
            autoSaveEnabled = isAutoSaveEnabled()
        )
    )
    private val saveMutex = Mutex()
    private val queueLock = Any()
    private val documentGeneration = AtomicLong(0L)
    private val lastSavedHash = AtomicLong(0L)
    private var currentTarget: EditorSyncTarget? = null
    private var debounceJob: Job? = null
    private var saveLoopJob: Job? = null
    private var pendingTrigger: EditorSaveTrigger? = null
    private val pendingWaiters = ArrayList<CompletableDeferred<EditorSaveResult>>()

    val state: StateFlow<EditorSyncSnapshot> = stateFlow.asStateFlow()

    fun isAutoSaveEnabled(): Boolean = prefs.getBoolean(PREF_AUTO_SAVE_ENABLED, false)

    fun setAutoSaveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_AUTO_SAVE_ENABLED, enabled).apply()
        stateFlow.value = stateMachine.setAutoSaveEnabled(enabled)
        if (!enabled) {
            debounceJob?.cancel()
            debounceJob = null
        } else if (stateFlow.value.hasUnsavedChanges) {
            requestSave(EditorSaveTrigger.AUTO)
        }
    }

    fun bindDocument(target: EditorSyncTarget?, initialText: String) {
        documentGeneration.incrementAndGet()
        debounceJob?.cancel()
        debounceJob = null
        currentTarget = target
        lastSavedHash.set(
            if (target?.supportsSaving() == true) fnv1a64(initialText.toByteArray(Charsets.UTF_8)) else 0L
        )
        synchronized(queueLock) {
            pendingTrigger = null
            val waiters = ArrayList(pendingWaiters)
            pendingWaiters.clear()
            for (waiter in waiters) {
                waiter.complete(
                    EditorSaveResult(
                        ok = false,
                        targetPath = target?.localPath,
                        bytes = 0,
                        elapsedMs = 0L,
                        error = "document switched",
                        trigger = EditorSaveTrigger.MANUAL,
                        remoteSynced = false
                    )
                )
            }
        }
        stateFlow.value = stateMachine.bindDocument(target, isAutoSaveEnabled())
    }

    fun onContentChanged() {
        if (currentTarget?.supportsSaving() != true) return
        stateFlow.value = stateMachine.onContentChanged()
        if (isAutoSaveEnabled()) {
            scheduleAutoSave()
        }
    }

    suspend fun saveNow(trigger: EditorSaveTrigger): EditorSaveResult {
        val deferred = CompletableDeferred<EditorSaveResult>()
        requestSave(trigger, deferred)
        return deferred.await()
    }

    suspend fun runSelfTest(): String {
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val target = currentTarget
            val results = ArrayList<String>()
            results.add("timeMs=$now")
            results.add("autoSaveEnabled=${isAutoSaveEnabled()}")
            results.add("target.localPath=${target?.localPath ?: "null"}")
            results.add("target.kind=${target?.kind ?: "null"}")
            results.add("target.originPath=${target?.originPath ?: "null"}")
            results.add("state=${state.value}")

            val random = SecureRandom()
            val sizes = intArrayOf(0, 1, 2, 7, 16, 128, 1024, 32 * 1024)

            fun randomBytes(n: Int): ByteArray {
                val data = ByteArray(n)
                if (n > 0) random.nextBytes(data)
                return data
            }

            fun verifyWriteRead(targetFile: File, payload: ByteArray): String? {
                val error = runCatching {
                    writeAtomic(targetFile, payload)
                    val readBack = targetFile.readBytes()
                    if (!readBack.contentEquals(payload)) {
                        "mismatch bytes=${payload.size} read=${readBack.size}"
                    } else {
                        null
                    }
                }.getOrElse {
                    "${it::class.java.name}:${it.message ?: ""}"
                }
                return error
            }

            fun testDir(dir: File, label: String) {
                val probe = File(dir, "editor-sync-selftest.txt")
                results.add("selftest.$label.path=${probe.absolutePath}")
                results.add("selftest.$label.dir.exists=${dir.exists()} isDir=${dir.isDirectory} canWrite=${dir.canWrite()}")
                for (size in sizes) {
                    val payload = randomBytes(size)
                    val error = verifyWriteRead(probe, payload)
                    results.add("selftest.$label.bytes.$size.ok=${error == null}")
                    if (error != null) results.add("selftest.$label.bytes.$size.error=$error")
                }
                runCatching { probe.delete() }
            }

            target?.localPath?.let {
                val parent = File(it).parentFile
                if (parent != null && parent.exists()) {
                    testDir(parent, "sameDir")
                }
            }
            testDir(context.cacheDir, "cacheDir")
            results.joinToString(separator = "\n")
        }
    }

    private fun scheduleAutoSave() {
        debounceJob?.cancel()
        debounceJob = scope.launch(Dispatchers.IO) {
            delay(DEFAULT_DEBOUNCE_MS)
            requestSave(EditorSaveTrigger.AUTO)
        }
    }

    private fun requestSave(
        trigger: EditorSaveTrigger,
        deferred: CompletableDeferred<EditorSaveResult>? = null
    ) {
        val target = currentTarget
        if (target?.supportsSaving() != true) {
            deferred?.complete(
                EditorSaveResult(
                    ok = false,
                    targetPath = target?.localPath,
                    bytes = 0,
                    elapsedMs = 0L,
                    error = "path not writable",
                    trigger = trigger,
                    remoteSynced = false
                )
            )
            return
        }

        if (trigger != EditorSaveTrigger.AUTO) {
            debounceJob?.cancel()
            debounceJob = null
        }

        synchronized(queueLock) {
            if (deferred != null) pendingWaiters.add(deferred)
            pendingTrigger = coalesceTrigger(pendingTrigger, trigger)
            if (saveLoopJob?.isActive != true) {
                saveLoopJob = scope.launch(Dispatchers.IO) {
                    processQueue()
                }
            }
        }
    }

    private suspend fun processQueue() {
        while (true) {
            val trigger: EditorSaveTrigger
            val waiters: List<CompletableDeferred<EditorSaveResult>>
            synchronized(queueLock) {
                val nextTrigger = pendingTrigger ?: return
                trigger = nextTrigger
                waiters = ArrayList(pendingWaiters)
                pendingTrigger = null
                pendingWaiters.clear()
            }

            val result = performSave(trigger)
            for (waiter in waiters) {
                if (!waiter.isCompleted) waiter.complete(result)
            }
        }
    }

    private suspend fun performSave(trigger: EditorSaveTrigger): EditorSaveResult {
        return saveMutex.withLock {
            val generation = documentGeneration.get()
            val target = currentTarget
            val snapshot = state.value

            if (target?.supportsSaving() != true) {
                return@withLock EditorSaveResult(
                    ok = false,
                    targetPath = target?.localPath,
                    bytes = 0,
                    elapsedMs = 0L,
                    error = "path not writable",
                    trigger = trigger,
                    remoteSynced = false
                )
            }

            val revision = snapshot.currentRevision
            if (revision <= snapshot.lastSuccessfulRevision) {
                return@withLock EditorSaveResult(
                    ok = true,
                    targetPath = target.localPath,
                    bytes = 0,
                    elapsedMs = 0L,
                    error = null,
                    trigger = trigger,
                    remoteSynced = target.supportsRemoteSync()
                )
            }

            publish(stateMachine.onSaveStarted(trigger, revision))

            val startedAt = System.currentTimeMillis()
            val text = withContext(Dispatchers.Default) { textSnapshotProvider() }
            val payload = text.toByteArray(Charsets.UTF_8)
            val hash = fnv1a64(payload)

            val error = runCatching {
                if (hash != lastSavedHash.get()) {
                    persist(target, payload)
                    lastSavedHash.set(hash)
                }
            }.exceptionOrNull()

            val elapsedMs = System.currentTimeMillis() - startedAt

            if (generation != documentGeneration.get()) {
                return@withLock EditorSaveResult(
                    ok = error == null,
                    targetPath = target.localPath,
                    bytes = payload.size,
                    elapsedMs = elapsedMs,
                    error = error?.let { "${it::class.java.name}:${it.message ?: ""}" },
                    trigger = trigger,
                    remoteSynced = target.supportsRemoteSync() && error == null
                )
            }

            if (error == null) {
                publish(stateMachine.onSaveSucceeded(trigger, revision))
                return@withLock EditorSaveResult(
                    ok = true,
                    targetPath = target.localPath,
                    bytes = payload.size,
                    elapsedMs = elapsedMs,
                    error = null,
                    trigger = trigger,
                    remoteSynced = target.supportsRemoteSync()
                )
            }

            val message = "${error::class.java.name}:${error.message ?: ""}"
            publish(stateMachine.onSaveFailed(trigger, revision, message))
            return@withLock EditorSaveResult(
                ok = false,
                targetPath = target.localPath,
                bytes = payload.size,
                elapsedMs = elapsedMs,
                error = message,
                trigger = trigger,
                remoteSynced = false
            )
        }
    }

    private fun persist(target: EditorSyncTarget, payload: ByteArray) {
        writeAtomic(File(target.localPath), payload)
        if (target.supportsRemoteSync()) {
            syncRemoteTarget(target)
        }
    }

    private fun syncRemoteTarget(target: EditorSyncTarget) {
        val originPath = target.originPath ?: return
        val originParent = File(originPath).parent ?: originPath.substringBeforeLast('/', "")
        val destinationVirtualDir = if (originParent.isBlank()) "/" else originParent
        val remoteName = File(originPath).name
        val localFile = File(target.localPath)

        val uploadFile = if (localFile.name == remoteName) {
            localFile
        } else {
            val stagingDir = File(context.cacheDir, "editor-sync-stage").apply { mkdirs() }
            val stagingFile = File(stagingDir, remoteName)
            localFile.copyTo(stagingFile, overwrite = true)
            stagingFile
        }

        try {
            val result = sessionFileCoordinator.uploadLocalPathsToVirtual(
                context.applicationContext,
                listOf(uploadFile.absolutePath),
                destinationVirtualDir,
                null,
                null
            )
            if (!result.success || result.uploadedFiles <= 0) {
                throw IllegalStateException(result.messageCn.ifBlank { "remote sync failed" })
            }
        } finally {
            if (uploadFile.absolutePath != localFile.absolutePath) {
                runCatching { uploadFile.delete() }
            }
        }
    }

    private fun publish(snapshot: EditorSyncSnapshot) {
        stateFlow.value = snapshot
    }

    private fun coalesceTrigger(current: EditorSaveTrigger?, incoming: EditorSaveTrigger): EditorSaveTrigger {
        if (current == null) return incoming
        return when {
            current == EditorSaveTrigger.RUN || incoming == EditorSaveTrigger.RUN -> EditorSaveTrigger.RUN
            current == EditorSaveTrigger.MANUAL || incoming == EditorSaveTrigger.MANUAL -> EditorSaveTrigger.MANUAL
            else -> EditorSaveTrigger.AUTO
        }
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IllegalStateException("no parent dir for $target")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("parent dir not exists: ${parent.absolutePath}")
        }

        val tmp = File(parent, ".${target.name}.editor-sync.tmp")
        if (tmp.exists()) runCatching { tmp.delete() }

        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            runCatching { out.fd.sync() }
            runCatching { out.channel.force(true) }
        }

        if (target.exists()) {
            val backup = File(parent, ".${target.name}.editor-sync.bak")
            if (backup.exists()) runCatching { backup.delete() }
            runCatching { target.renameTo(backup) }
            if (!tmp.renameTo(target)) {
                FileOutputStream(target, false).use { out ->
                    out.write(bytes)
                    out.flush()
                    runCatching { out.fd.sync() }
                    runCatching { out.channel.force(true) }
                }
            }
            runCatching { backup.delete() }
        } else {
            if (!tmp.renameTo(target)) {
                FileOutputStream(target, false).use { out ->
                    out.write(bytes)
                    out.flush()
                    runCatching { out.fd.sync() }
                    runCatching { out.channel.force(true) }
                }
                runCatching { tmp.delete() }
            }
        }
    }

    private fun fnv1a64(bytes: ByteArray): Long {
        var hash = -0x340d631b7bdddcdbL
        for (byte in bytes) {
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= 0x100000001b3L
        }
        return hash
    }
}
