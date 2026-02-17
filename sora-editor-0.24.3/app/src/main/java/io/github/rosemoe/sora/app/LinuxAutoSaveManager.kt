package io.github.rosemoe.sora.app

import android.content.Context
import android.content.SharedPreferences
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

class LinuxAutoSaveManager(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val editor: CodeEditor,
    private val currentFilePathProvider: () -> String?
) {

    data class SaveResult(
        val ok: Boolean,
        val path: String?,
        val bytes: Int,
        val elapsedMs: Long,
        val error: String?
    )

    companion object {
        const val PREF_AUTO_SAVE_ENABLED = "autosave.enabled"
        private const val DEFAULT_DEBOUNCE_MS = 350L
    }

    private val lastSavedAtMs = AtomicLong(0L)
    private val lastSavedHash = AtomicLong(0L)
    private var pendingJob: Job? = null

    fun isEnabled(): Boolean = prefs.getBoolean(PREF_AUTO_SAVE_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_AUTO_SAVE_ENABLED, enabled).apply()
        if (!enabled) {
            pendingJob?.cancel()
            pendingJob = null
            lastSavedHash.set(0L)
            lastSavedAtMs.set(0L)
        }
    }

    fun onContentChanged() {
        if (!isEnabled()) return
        val path = currentFilePathProvider() ?: return
        if (!canWritePath(path)) return

        pendingJob?.cancel()
        pendingJob = scope.launch(Dispatchers.IO) {
            delay(DEFAULT_DEBOUNCE_MS)
            saveNowInternal(reason = "autosave.debounced")
        }
    }

    fun onFileOpenedFromDisk(path: String) {
        if (!canWritePath(path)) return
        scope.launch(Dispatchers.Default) {
            val hash = computeEditorTextHash()
            lastSavedHash.set(hash)
            lastSavedAtMs.set(System.currentTimeMillis())
        }
    }

    suspend fun saveNow(reason: String): SaveResult {
        return withContext(Dispatchers.IO) {
            saveNowInternal(reason = reason)
        }
    }

    suspend fun runSelfTest(): String {
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val currentPath = currentFilePathProvider()

            val results = ArrayList<String>()
            results.add("timeMs=$now")
            results.add("enabled=${isEnabled()}")
            results.add("current.path=${currentPath ?: "null"}")

            val random = SecureRandom()
            val sizes = intArrayOf(0, 1, 2, 7, 16, 128, 1024, 32 * 1024, 128 * 1024)

            fun randomBytes(n: Int): ByteArray {
                val b = ByteArray(n)
                if (n > 0) random.nextBytes(b)
                return b
            }

            fun verifyWriteRead(target: File, payload: ByteArray, label: String): String? {
                val err = runCatching {
                    writeAtomic(target, payload)
                    val readBack = runCatching { target.readBytes() }.getOrNull()
                    if (readBack == null) return "readBack=null"
                    if (!readBack.contentEquals(payload)) return "mismatch bytes=${payload.size} read=${readBack.size}"
                    val parent = target.parentFile
                    if (parent != null) {
                        val tmp = File(parent, ".${target.name}.autosave.tmp")
                        val bak = File(parent, ".${target.name}.autosave.bak")
                        if (tmp.exists()) return "tmp leftover: ${tmp.absolutePath}"
                        if (bak.exists()) return "bak leftover: ${bak.absolutePath}"
                    }
                    null
                }.exceptionOrNull()
                return err?.let { "${it::class.java.name}:${it.message ?: ""}" }
                    ?: runCatching { null }.getOrNull()
            }

            fun stressWrite(target: File, label: String): String? {
                val err = runCatching {
                    var last: ByteArray = ByteArray(0)
                    repeat(12) { i ->
                        val payload = randomBytes(sizes[i % sizes.size])
                        writeAtomic(target, payload)
                        last = payload
                    }
                    val readBack = target.readBytes()
                    if (!readBack.contentEquals(last)) {
                        "final mismatch last=${last.size} read=${readBack.size}"
                    } else {
                        null
                    }
                }.exceptionOrNull()
                return err?.let { "${it::class.java.name}:${it.message ?: ""}" }
            }

            fun testDir(dir: File, label: String) {
                val target = File(dir, "autosave_selftest.txt")
                val r = runCatching {
                    results.add("selftest.$label.path=${target.absolutePath}")
                    results.add("selftest.$label.dir.exists=${dir.exists()} isDir=${dir.isDirectory} canWrite=${dir.canWrite()}")

                    val textPayload = ("autosave-selftest-$now\n中文-emoji-free\n").toByteArray(Charsets.UTF_8)
                    val e1 = verifyWriteRead(target, textPayload, "$label.text")
                    results.add("selftest.$label.text.ok=${e1 == null}")
                    if (e1 != null) results.add("selftest.$label.text.error=$e1")

                    for (sz in sizes) {
                        val payload = randomBytes(sz)
                        val e = verifyWriteRead(target, payload, "$label.bytes.$sz")
                        results.add("selftest.$label.bytes.$sz.ok=${e == null}")
                        if (e != null) results.add("selftest.$label.bytes.$sz.error=$e")
                    }

                    val eStress = stressWrite(target, "$label.stress")
                    results.add("selftest.$label.stress.ok=${eStress == null}")
                    if (eStress != null) results.add("selftest.$label.stress.error=$eStress")

                    runCatching { target.delete() }
                }.exceptionOrNull()
                if (r != null) {
                    results.add("selftest.$label.error=${r::class.java.name}:${r.message ?: ""}")
                }
            }

            if (currentPath != null) {
                val f = File(currentPath)
                val parent = f.parentFile
                if (parent != null && parent.exists()) {
                    testDir(parent, "sameDir")
                }
            }
            testDir(context.cacheDir, "cacheDir")

            results.joinToString(separator = "\n")
        }
    }

    private suspend fun saveNowInternal(reason: String): SaveResult {
        val path = currentFilePathProvider()
        if (path == null) {
            return SaveResult(ok = false, path = null, bytes = 0, elapsedMs = 0, error = "no file path")
        }
        if (!canWritePath(path)) {
            return SaveResult(ok = false, path = path, bytes = 0, elapsedMs = 0, error = "path not writable")
        }

        val start = System.currentTimeMillis()
        val text = withContext(Dispatchers.Default) { editor.text.toString() }
        val bytesArr = text.toByteArray(Charsets.UTF_8)
        val hash = fnv1a64(bytesArr)
        if (hash == lastSavedHash.get()) {
            return SaveResult(ok = true, path = path, bytes = 0, elapsedMs = 0, error = null)
        }

        val err = runCatching {
            writeAtomic(File(path), bytesArr)
            lastSavedHash.set(hash)
            lastSavedAtMs.set(System.currentTimeMillis())
        }.exceptionOrNull()

        val elapsed = System.currentTimeMillis() - start
        return SaveResult(
            ok = err == null,
            path = path,
            bytes = bytesArr.size,
            elapsedMs = elapsed,
            error = err?.let { "${it::class.java.name}:${it.message ?: ""} reason=$reason" }
        )
    }

    private fun canWritePath(path: String): Boolean {
        return runCatching {
            val f = File(path)
            f.exists() && f.isFile && f.canWrite()
        }.getOrDefault(false)
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IllegalStateException("no parent dir for $target")
        if (!parent.exists()) throw IllegalStateException("parent dir not exists: ${parent.absolutePath}")

        val tmp = File(parent, ".${target.name}.autosave.tmp")
        if (tmp.exists()) runCatching { tmp.delete() }

        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            runCatching { out.fd.sync() }
            runCatching { out.channel.force(true) }
        }

        if (target.exists()) {
            val backup = File(parent, ".${target.name}.autosave.bak")
            if (backup.exists()) runCatching { backup.delete() }
            runCatching { target.renameTo(backup) }
            if (!tmp.renameTo(target)) {
                runCatching {
                    FileOutputStream(target).use { out ->
                        out.write(bytes)
                        out.flush()
                        runCatching { out.fd.sync() }
                        runCatching { out.channel.force(true) }
                    }
                }.getOrElse { throw it }
            }
            runCatching { backup.delete() }
        } else {
            if (!tmp.renameTo(target)) {
                FileOutputStream(target).use { out ->
                    out.write(bytes)
                    out.flush()
                    runCatching { out.fd.sync() }
                    runCatching { out.channel.force(true) }
                }
                runCatching { tmp.delete() }
            }
        }
    }

    private suspend fun computeEditorTextHash(): Long {
        return withContext(Dispatchers.Default) {
            val bytes = editor.text.toString().toByteArray(Charsets.UTF_8)
            fnv1a64(bytes)
        }
    }

    private fun fnv1a64(bytes: ByteArray): Long {
        var hash = -0x340d631b7bdddcdbL
        for (b in bytes) {
            hash = hash xor (b.toLong() and 0xffL)
            hash *= 0x100000001b3L
        }
        return hash
    }
}

