package org.fossify.filemanager.helpers

import android.content.Context
import android.os.Build
import com.termux.sessionsync.FileRootResolver
import com.termux.sessionsync.SavedSshProfileStore
import com.termux.sessionsync.SessionEntry
import com.termux.sessionsync.SessionFileCoordinator
import com.termux.sessionsync.SessionTransport
import com.termux.sessionsync.SftpProtocolManager
import com.termux.sessionsync.SftpTransferRecoveryManager
import org.fossify.filemanager.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.math.max

object SessionSelfTestRunner {

    private const val ROOT_SELF_TEST_DIR = ".termux-selftest"
    private const val FILE_MANAGER_SELF_TEST_DIR = "fm-industrial"

    private enum class CheckStatus {
        PASS,
        FAIL,
        SKIP
    }

    private data class CheckOutcome(
        val status: CheckStatus,
        val detail: String = ""
    )

    private data class PayloadFile(
        val relativePath: String,
        val size: Long,
        val sha256: String
    )

    private data class PayloadBundle(
        val rootDirectory: File,
        val files: List<PayloadFile>,
        val sampleRelativePath: String,
        val totalBytes: Long
    )

    private data class RemoteFileInfo(
        val size: Long
    )

    private data class ServerRuntime(
        val entry: SessionEntry,
        val label: String,
        val index: Int,
        val virtualRoot: String
    ) {
        var probeSucceeded: Boolean = false
        var runDirectoryVirtualPath: String? = null
        var uploadedPayloadVirtualPath: String? = null
        var payloadBundle: PayloadBundle? = null
        val cleanupVirtualPaths = ArrayList<String>()
    }

    data class Report(
        val startedAtMs: Long,
        val finishedAtMs: Long,
        val totalChecks: Int,
        val passedChecks: Int,
        val failedChecks: Int,
        val skippedChecks: Int,
        val reportPath: String,
        val summary: String,
        val content: String
    ) {
        val success: Boolean
            get() = failedChecks == 0
    }

    fun run(context: Context): Report {
        val startedAtMs = System.currentTimeMillis()
        val appContext = context.applicationContext
        val coordinator = SessionFileCoordinator.getInstance()
        val sftpManager = SftpProtocolManager.getInstance()

        var totalChecks = 0
        var passedChecks = 0
        var failedChecks = 0
        var skippedChecks = 0

        val logs = ArrayList<String>()
        val failureHeadlines = ArrayList<String>()

        fun section(title: String) {
            if (logs.isNotEmpty()) {
                logs.add("")
            }
            logs.add("## $title")
        }

        fun record(name: String, outcome: CheckOutcome) {
            totalChecks++
            when (outcome.status) {
                CheckStatus.PASS -> {
                    passedChecks++
                    logs.add("[PASS] $name")
                }
                CheckStatus.FAIL -> {
                    failedChecks++
                    logs.add("[FAIL] $name")
                    failureHeadlines.add(name)
                }
                CheckStatus.SKIP -> {
                    skippedChecks++
                    logs.add("[SKIP] $name")
                }
            }
            val detail = outcome.detail.trim()
            if (detail.isNotEmpty()) {
                logs.add("       $detail")
            }
        }

        fun check(name: String, block: () -> CheckOutcome) {
            try {
                record(name, block())
            } catch (throwable: Throwable) {
                record(name, fail(safeErrorText(throwable)))
            }
        }

        coordinator.initialize(appContext)

        val termuxRoot = FileRootResolver.termuxPrivateRoot(appContext)
        val allEntries = SavedSshProfileStore.loadSessionEntries(appContext)
        val remoteEntries = allEntries.filter { it.transport != SessionTransport.LOCAL }
        val selectedKey = coordinator.getSelectedSessionKey(appContext)
        val runId = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(startedAtMs))
        val reportFile = buildReportFile(appContext, runId)
        val workRoot = File(appContext.cacheDir, "industrial-self-test/$runId")
        if (!workRoot.exists()) {
            workRoot.mkdirs()
        }

        section("环境与配置")

        check("本地根目录检查") {
            val rootFile = File(termuxRoot)
            val ok = rootFile.exists() && rootFile.isDirectory && rootFile.canRead()
            if (ok) {
                pass(
                    "path=$termuxRoot, exists=${rootFile.exists()}, dir=${rootFile.isDirectory}, " +
                        "readable=${rootFile.canRead()}, writable=${rootFile.canWrite()}"
                )
            } else {
                fail(
                    "path=$termuxRoot, exists=${rootFile.exists()}, dir=${rootFile.isDirectory}, " +
                        "readable=${rootFile.canRead()}, writable=${rootFile.canWrite()}"
                )
            }
        }

        check("会话选中状态解析") {
            val resolve = coordinator.resolveSelectedRoot(appContext)
            if (resolve.success) {
                pass("selected=${selectedKey ?: "__local__"}, mode=${resolve.mode}, root=${resolve.rootPath}, msg=${resolve.messageCn}")
            } else {
                fail("selected=${selectedKey ?: "__local__"}, mode=${resolve.mode}, root=${resolve.rootPath}, msg=${resolve.messageCn}")
            }
        }

        check("服务器配置加载") {
            val ids = LinkedHashSet<String>()
            val duplicateIds = ArrayList<String>()
            allEntries.forEach {
                if (!ids.add(it.id)) {
                    duplicateIds.add(it.id)
                }
            }
            if (duplicateIds.isEmpty()) {
                pass("all=${allEntries.size}, remote=${remoteEntries.size}, duplicateIds=0")
            } else {
                fail("all=${allEntries.size}, remote=${remoteEntries.size}, duplicateIds=${duplicateIds.joinToString()}")
            }
        }

        check("恢复任务队列状态") {
            pass("hasRecoverableTasks=${SftpTransferRecoveryManager.hasRecoverableTasks(appContext)}")
        }

        check("下载接口参数校验") {
            val result = coordinator.downloadVirtualPaths(appContext, emptyList(), termuxRoot, null, null)
            if (!result.success) {
                pass("success=${result.success}, msg=${result.messageCn}")
            } else {
                fail("success=${result.success}, msg=${result.messageCn}")
            }
        }

        section("主动压测")

        if (remoteEntries.isEmpty()) {
            check("保存的远程服务器数量") {
                fail("未发现已保存的远程服务器，无法执行真实上传/下载/互传压测。")
            }
        } else {
            val serverStates = remoteEntries.mapIndexed { index, entry ->
                ServerRuntime(
                    entry = entry,
                    label = entry.displayName.ifBlank { entry.id },
                    index = index,
                    virtualRoot = FileRootResolver.resolveVirtualRoot(appContext, entry)
                )
            }

            try {
                serverStates.forEach { server ->
                    section("服务器 ${server.label}")

                    check("探活: ${server.label}") {
                        val (probe, elapsedMs) = measure {
                            sftpManager.probeSession(appContext, server.entry)
                        }
                        if (probe.success) {
                            server.probeSucceeded = true
                            pass("cost=${elapsedMs} ms, root=${probe.virtualRootPath}, msg=${probe.messageCn}")
                        } else {
                            fail("cost=${elapsedMs} ms, msg=${probe.messageCn}")
                        }
                    }

                    if (!server.probeSucceeded) {
                        check("根目录冷热列表性能: ${server.label}") { skip("探活失败，跳过后续主动链路测试。") }
                        check("远端工作目录准备: ${server.label}") { skip("探活失败，跳过。") }
                        check("并发上传压测: ${server.label}") { skip("探活失败，跳过。") }
                        check("远端目录结构校验: ${server.label}") { skip("探活失败，跳过。") }
                        check("缓存 materialize 校验: ${server.label}") { skip("探活失败，跳过。") }
                        check("并发下载压测: ${server.label}") { skip("探活失败，跳过。") }
                        return@forEach
                    }

                    check("根目录冷热列表性能: ${server.label}") {
                        val timings = ArrayList<Long>()
                        var lastEntries = 0
                        repeat(4) {
                            val (result, elapsedMs) = measure {
                                coordinator.listVirtualPath(appContext, server.virtualRoot)
                            }
                            if (!result.success) {
                                return@check fail("run=${it + 1}, cost=${elapsedMs} ms, msg=${result.messageCn}")
                            }
                            lastEntries = result.entries.size
                            timings.add(elapsedMs)
                        }
                        pass("entries=$lastEntries, timingsMs=${timings.joinToString()}, avg=${averageMs(timings)} ms")
                    }

                    check("远端工作目录准备: ${server.label}") {
                        val baseVirtual = ensureRemoteDirectory(appContext, coordinator, server.virtualRoot, ROOT_SELF_TEST_DIR)
                            ?: return@check fail("无法创建或定位 $ROOT_SELF_TEST_DIR")
                        val fmVirtual = ensureRemoteDirectory(appContext, coordinator, baseVirtual, FILE_MANAGER_SELF_TEST_DIR)
                            ?: return@check fail("无法创建或定位 $FILE_MANAGER_SELF_TEST_DIR")
                        val runVirtual = ensureRemoteDirectory(
                            appContext,
                            coordinator,
                            fmVirtual,
                            "run-$runId-${safeName(server.label)}"
                        ) ?: return@check fail("无法创建或定位 run 目录")

                        server.runDirectoryVirtualPath = runVirtual
                        server.cleanupVirtualPaths.add(runVirtual)
                        pass("virtualRoot=${server.virtualRoot}, runDir=$runVirtual")
                    }

                    val runDirectoryVirtualPath = server.runDirectoryVirtualPath
                    if (runDirectoryVirtualPath.isNullOrBlank()) {
                        check("并发上传压测: ${server.label}") { skip("工作目录准备失败，跳过。") }
                        check("远端目录结构校验: ${server.label}") { skip("工作目录准备失败，跳过。") }
                        check("缓存 materialize 校验: ${server.label}") { skip("工作目录准备失败，跳过。") }
                        check("并发下载压测: ${server.label}") { skip("工作目录准备失败，跳过。") }
                        return@forEach
                    }

                    val payloadWorkRoot = File(workRoot, safeName(server.label))
                    val payloadBundle = buildPayloadBundle(payloadWorkRoot)
                    server.payloadBundle = payloadBundle

                    check("并发上传压测: ${server.label}") {
                        val (result, elapsedMs) = measure {
                            coordinator.uploadLocalPathsToVirtual(
                                appContext,
                                listOf(payloadBundle.rootDirectory.absolutePath),
                                runDirectoryVirtualPath,
                                null,
                                null
                            )
                        }
                        if (!result.success) {
                            return@check fail("cost=${elapsedMs} ms, msg=${result.messageCn}")
                        }

                        val uploadedPayloadVirtualPath = joinVirtualPath(runDirectoryVirtualPath, payloadBundle.rootDirectory.name)
                        server.uploadedPayloadVirtualPath = uploadedPayloadVirtualPath
                        pass(
                            "cost=${elapsedMs} ms, files=${result.totalFiles}, uploaded=${result.uploadedFiles}, " +
                                "bytes=${result.uploadedBytes}, throughput=${throughputText(result.uploadedBytes, elapsedMs)}, " +
                                "virtual=$uploadedPayloadVirtualPath"
                        )
                    }

                    val uploadedPayloadVirtualPath = server.uploadedPayloadVirtualPath
                    if (uploadedPayloadVirtualPath.isNullOrBlank()) {
                        check("远端目录结构校验: ${server.label}") { skip("上传失败，跳过。") }
                        check("缓存 materialize 校验: ${server.label}") { skip("上传失败，跳过。") }
                        check("并发下载压测: ${server.label}") { skip("上传失败，跳过。") }
                        return@forEach
                    }

                    check("远端目录结构校验: ${server.label}") {
                        verifyRemotePayload(appContext, coordinator, uploadedPayloadVirtualPath, payloadBundle)
                    }

                    check("缓存 materialize 校验: ${server.label}") {
                        val sampleVirtualPath = joinVirtualPath(uploadedPayloadVirtualPath, payloadBundle.sampleRelativePath)
                        val expected = payloadBundle.files.firstOrNull { it.relativePath == payloadBundle.sampleRelativePath }
                            ?: return@check fail("缺少 sample 文件定义")
                        val (result, elapsedMs) = measure {
                            coordinator.materializeVirtualFile(appContext, sampleVirtualPath)
                        }
                        if (!result.success) {
                            return@check fail("cost=${elapsedMs} ms, msg=${result.messageCn}")
                        }
                        val localFile = File(result.localPath)
                        if (!localFile.exists() || !localFile.isFile) {
                            return@check fail("cost=${elapsedMs} ms, localPath=${result.localPath}, exists=${localFile.exists()}")
                        }
                        val digest = sha256(localFile)
                        if (digest != expected.sha256) {
                            return@check fail("cost=${elapsedMs} ms, sha256_mismatch expected=${expected.sha256}, actual=$digest")
                        }
                        pass("cost=${elapsedMs} ms, localPath=${result.localPath}, size=${localFile.length()}")
                    }

                    check("并发下载压测: ${server.label}") {
                        val downloadRoot = File(workRoot, "${safeName(server.label)}-download")
                        deleteRecursively(downloadRoot)
                        if (!downloadRoot.exists()) {
                            downloadRoot.mkdirs()
                        }

                        val (result, elapsedMs) = measure {
                            coordinator.downloadVirtualPaths(
                                appContext,
                                listOf(uploadedPayloadVirtualPath),
                                downloadRoot.absolutePath,
                                null,
                                null
                            )
                        }
                        if (!result.success) {
                            return@check fail("cost=${elapsedMs} ms, msg=${result.messageCn}")
                        }

                        val localPayloadRoot = File(downloadRoot, payloadBundle.rootDirectory.name)
                        val verification = verifyLocalPayload(localPayloadRoot, payloadBundle)
                        if (verification.status == CheckStatus.FAIL) {
                            return@check verification
                        }
                        pass(
                            "cost=${elapsedMs} ms, files=${result.totalFiles}, downloaded=${result.downloadedFiles}, " +
                                "bytes=${result.downloadedBytes}, throughput=${throughputText(result.downloadedBytes, elapsedMs)}, " +
                                "localRoot=${localPayloadRoot.absolutePath}"
                        )
                    }
                }

                section("服务器互传")

                if (serverStates.count { it.probeSucceeded } < 2) {
                    check("服务器互传覆盖") {
                        skip("可用服务器不足 2 台，跳过跨服务器互传压测。")
                    }
                } else {
                    serverStates.forEach { source ->
                        val sourcePayload = source.uploadedPayloadVirtualPath
                        val payloadBundle = source.payloadBundle
                        if (!source.probeSucceeded || sourcePayload.isNullOrBlank() || payloadBundle == null) {
                            check("互传: ${source.label}") {
                                skip("源服务器未完成上传链路，跳过。")
                            }
                            return@forEach
                        }

                        val destination = nextAvailableDestination(serverStates, source.index)
                        if (destination == null) {
                            check("互传: ${source.label}") { skip("未找到可用目标服务器。") }
                            return@forEach
                        }

                        check("互传: ${source.label} -> ${destination.label}") {
                            val baseVirtual = ensureRemoteDirectory(appContext, coordinator, destination.virtualRoot, ROOT_SELF_TEST_DIR)
                                ?: return@check fail("目标服务器无法创建 $ROOT_SELF_TEST_DIR")
                            val fmVirtual = ensureRemoteDirectory(appContext, coordinator, baseVirtual, FILE_MANAGER_SELF_TEST_DIR)
                                ?: return@check fail("目标服务器无法创建 $FILE_MANAGER_SELF_TEST_DIR")
                            val relayVirtual = ensureRemoteDirectory(
                                appContext,
                                coordinator,
                                fmVirtual,
                                "relay-$runId-${safeName(source.label)}-to-${safeName(destination.label)}"
                            ) ?: return@check fail("目标服务器无法创建 relay 目录")

                            destination.cleanupVirtualPaths.add(relayVirtual)

                            val (result, elapsedMs) = measure {
                                coordinator.transferVirtualPaths(
                                    appContext,
                                    listOf(sourcePayload),
                                    relayVirtual,
                                    null,
                                    null
                                )
                            }
                            if (!result.success) {
                                return@check fail("cost=${elapsedMs} ms, msg=${result.messageCn}")
                            }

                            val relayedPayloadVirtualPath = joinVirtualPath(relayVirtual, payloadBundle.rootDirectory.name)
                            val remoteVerification = verifyRemotePayload(appContext, coordinator, relayedPayloadVirtualPath, payloadBundle)
                            if (remoteVerification.status == CheckStatus.FAIL) {
                                return@check remoteVerification
                            }

                            val sampleVirtualPath = joinVirtualPath(relayedPayloadVirtualPath, payloadBundle.sampleRelativePath)
                            val sampleResult = coordinator.materializeVirtualFile(appContext, sampleVirtualPath)
                            if (!sampleResult.success) {
                                return@check fail("relay sample materialize msg=${sampleResult.messageCn}")
                            }
                            val sampleDigest = sha256(File(sampleResult.localPath))
                            val expectedDigest = payloadBundle.files.firstOrNull { it.relativePath == payloadBundle.sampleRelativePath }?.sha256
                            if (sampleDigest != expectedDigest) {
                                return@check fail("relay sample sha256 mismatch expected=$expectedDigest actual=$sampleDigest")
                            }

                            pass(
                                "cost=${elapsedMs} ms, files=${result.totalFiles}, transferred=${result.transferredFiles}, " +
                                    "bytes=${result.transferredBytes}, throughput=${throughputText(result.transferredBytes, elapsedMs)}, " +
                                    "dest=$relayVirtual"
                            )
                        }
                    }
                }

                section("清理")

                serverStates.forEach { server ->
                    if (server.cleanupVirtualPaths.isEmpty()) {
                        check("清理: ${server.label}") {
                            skip("没有待清理的远端测试目录。")
                        }
                        return@forEach
                    }

                    server.cleanupVirtualPaths.distinct().forEach { virtualPath ->
                        check("清理: ${server.label} -> ${virtualPath.substringAfterLast('/')}") {
                            val result = coordinator.deleteVirtualPath(appContext, virtualPath)
                            if (result.success) {
                                pass("virtualPath=${result.virtualPath}")
                            } else {
                                fail("virtualPath=$virtualPath, msg=${result.messageCn}")
                            }
                        }
                    }
                }
            } finally {
                deleteRecursively(workRoot)
            }
        }

        val finishedAtMs = System.currentTimeMillis()
        val stateDump = runCatching { coordinator.dumpState(appContext) }.getOrElse { safeErrorText(it) }
        val traceDump = runCatching { coordinator.dumpRecentTrace(appContext, 320) }.getOrElse { safeErrorText(it) }

        val content = buildContent(
            appContext = appContext,
            reportPath = reportFile.absolutePath,
            startedAtMs = startedAtMs,
            finishedAtMs = finishedAtMs,
            termuxRoot = termuxRoot,
            selectedKey = selectedKey,
            allEntries = allEntries,
            remoteEntries = remoteEntries,
            totalChecks = totalChecks,
            passedChecks = passedChecks,
            failedChecks = failedChecks,
            skippedChecks = skippedChecks,
            logs = logs,
            stateDump = stateDump,
            traceDump = traceDump
        )

        writeReportFile(reportFile, content)

        val summary = buildSummary(
            success = failedChecks == 0,
            totalChecks = totalChecks,
            passedChecks = passedChecks,
            failedChecks = failedChecks,
            skippedChecks = skippedChecks,
            costMs = finishedAtMs - startedAtMs,
            reportPath = reportFile.absolutePath,
            failureHeadlines = failureHeadlines
        )

        return Report(
            startedAtMs = startedAtMs,
            finishedAtMs = finishedAtMs,
            totalChecks = totalChecks,
            passedChecks = passedChecks,
            failedChecks = failedChecks,
            skippedChecks = skippedChecks,
            reportPath = reportFile.absolutePath,
            summary = summary,
            content = content
        )
    }

    private fun ensureRemoteDirectory(
        context: Context,
        coordinator: SessionFileCoordinator,
        parentVirtualPath: String,
        childName: String
    ): String? {
        val listResult = coordinator.listVirtualPath(context, parentVirtualPath)
        if (!listResult.success) {
            return null
        }

        val existing = listResult.entries.firstOrNull { it.name == childName }
        if (existing != null) {
            return if (existing.directory) {
                existing.localPath
            } else {
                null
            }
        }

        val createResult = coordinator.createVirtualItem(context, parentVirtualPath, childName, true)
        return if (createResult.success) createResult.virtualPath else null
    }

    private fun verifyRemotePayload(
        context: Context,
        coordinator: SessionFileCoordinator,
        payloadVirtualPath: String,
        payloadBundle: PayloadBundle
    ): CheckOutcome {
        val remoteFiles = LinkedHashMap<String, RemoteFileInfo>()
        val listError = collectRemoteFiles(context, coordinator, payloadVirtualPath, "", remoteFiles)
        if (listError != null) {
            return fail(listError)
        }

        val expectedMap = payloadBundle.files.associateBy { normalizeRelativePath(it.relativePath) }
        val missing = ArrayList<String>()
        val sizeMismatch = ArrayList<String>()

        expectedMap.forEach { (relativePath, expected) ->
            val remote = remoteFiles[relativePath]
            if (remote == null) {
                missing.add(relativePath)
            } else if (remote.size != expected.size) {
                sizeMismatch.add("$relativePath expected=${expected.size} actual=${remote.size}")
            }
        }

        val extras = remoteFiles.keys.filter { !expectedMap.containsKey(it) }
        return if (missing.isEmpty() && sizeMismatch.isEmpty() && extras.isEmpty()) {
            pass("files=${remoteFiles.size}, totalBytes=${payloadBundle.totalBytes}")
        } else {
            fail(
                "missing=${missing.joinToString()}, sizeMismatch=${sizeMismatch.joinToString()}, " +
                    "extras=${extras.joinToString()}"
            )
        }
    }

    private fun collectRemoteFiles(
        context: Context,
        coordinator: SessionFileCoordinator,
        currentVirtualPath: String,
        relativePrefix: String,
        out: LinkedHashMap<String, RemoteFileInfo>
    ): String? {
        val result = coordinator.listVirtualPath(context, currentVirtualPath)
        if (!result.success) {
            return result.messageCn
        }

        result.entries.forEach { entry ->
            val relativePath = normalizeRelativePath(joinRelativePath(relativePrefix, entry.name))
            if (entry.directory) {
                val error = collectRemoteFiles(context, coordinator, entry.localPath, relativePath, out)
                if (error != null) {
                    return error
                }
            } else {
                out[relativePath] = RemoteFileInfo(entry.size)
            }
        }
        return null
    }

    private fun verifyLocalPayload(rootDirectory: File, payloadBundle: PayloadBundle): CheckOutcome {
        if (!rootDirectory.exists() || !rootDirectory.isDirectory) {
            return fail("本地下载目录不存在: ${rootDirectory.absolutePath}")
        }

        val actualFiles = LinkedHashMap<String, File>()
        collectLocalFiles(rootDirectory, "", actualFiles)

        val expectedMap = payloadBundle.files.associateBy { normalizeRelativePath(it.relativePath) }
        val missing = ArrayList<String>()
        val digestMismatch = ArrayList<String>()

        expectedMap.forEach { (relativePath, expected) ->
            val file = actualFiles[relativePath]
            if (file == null) {
                missing.add(relativePath)
            } else {
                if (file.length() != expected.size) {
                    digestMismatch.add("$relativePath size expected=${expected.size} actual=${file.length()}")
                } else {
                    val actualDigest = sha256(file)
                    if (actualDigest != expected.sha256) {
                        digestMismatch.add("$relativePath sha256 mismatch")
                    }
                }
            }
        }

        val extras = actualFiles.keys.filter { !expectedMap.containsKey(it) }
        return if (missing.isEmpty() && digestMismatch.isEmpty() && extras.isEmpty()) {
            pass("files=${actualFiles.size}, totalBytes=${payloadBundle.totalBytes}")
        } else {
            fail(
                "missing=${missing.joinToString()}, mismatch=${digestMismatch.joinToString()}, " +
                    "extras=${extras.joinToString()}"
            )
        }
    }

    private fun collectLocalFiles(
        current: File,
        relativePrefix: String,
        out: LinkedHashMap<String, File>
    ) {
        val children = current.listFiles() ?: return
        children.sortedBy { it.name.lowercase(Locale.US) }.forEach { child ->
            val relativePath = normalizeRelativePath(joinRelativePath(relativePrefix, child.name))
            if (child.isDirectory) {
                collectLocalFiles(child, relativePath, out)
            } else if (child.isFile) {
                out[relativePath] = child
            }
        }
    }

    private fun buildPayloadBundle(workRoot: File): PayloadBundle {
        deleteRecursively(workRoot)
        workRoot.mkdirs()

        val rootDirectory = File(workRoot, "payload")
        rootDirectory.mkdirs()

        val files = ArrayList<PayloadFile>()
        val fileDefinitions = listOf(
            Triple("zero.bin", 0, 7),
            Triple("ascii/notes.txt", 4 * 1024, 11),
            Triple("utf8/文件-自检.txt", 8 * 1024, 19),
            Triple("nested/medium.bin", 256 * 1024, 23),
            Triple("nested/deeper/large.bin", 1024 * 1024, 31)
        )

        var totalBytes = 0L
        fileDefinitions.forEach { (relativePath, size, salt) ->
            val file = File(rootDirectory, relativePath)
            file.parentFile?.mkdirs()
            if (size <= 0) {
                if (!file.exists()) {
                    file.createNewFile()
                } else {
                    FileOutputStream(file, false).use { it.flush() }
                }
            } else {
                writePatternFile(file, size, salt)
            }
            val fileSize = file.length()
            totalBytes += fileSize
            files.add(
                PayloadFile(
                    relativePath = normalizeRelativePath(relativePath),
                    size = fileSize,
                    sha256 = sha256(file)
                )
            )
        }

        return PayloadBundle(
            rootDirectory = rootDirectory,
            files = files,
            sampleRelativePath = normalizeRelativePath("nested/deeper/large.bin"),
            totalBytes = totalBytes
        )
    }

    private fun writePatternFile(file: File, size: Int, salt: Int) {
        FileOutputStream(file, false).use { output ->
            val buffer = ByteArray(16 * 1024)
            var written = 0
            while (written < size) {
                val chunk = minOf(buffer.size, size - written)
                for (index in 0 until chunk) {
                    buffer[index] = (((written + index) * 31 + salt) and 0xFF).toByte()
                }
                output.write(buffer, 0, chunk)
                written += chunk
            }
            output.flush()
        }
    }

    private fun buildContent(
        appContext: Context,
        reportPath: String,
        startedAtMs: Long,
        finishedAtMs: Long,
        termuxRoot: String,
        selectedKey: String?,
        allEntries: List<SessionEntry>,
        remoteEntries: List<SessionEntry>,
        totalChecks: Int,
        passedChecks: Int,
        failedChecks: Int,
        skippedChecks: Int,
        logs: List<String>,
        stateDump: String,
        traceDump: String
    ): String {
        val sb = StringBuilder(16 * 1024)
        sb.append("==== FILE MANAGER INDUSTRIAL SELF-TEST ====\n")
        sb.append("report_file: ").append(reportPath).append('\n')
        sb.append("start: ").append(formatTime(startedAtMs)).append('\n')
        sb.append("end:   ").append(formatTime(finishedAtMs)).append('\n')
        sb.append("cost:  ").append(finishedAtMs - startedAtMs).append(" ms\n")
        sb.append("app: ").append(appContext.packageName).append(" / ").append(BuildConfig.VERSION_NAME).append('\n')
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("abi: ").append(Build.SUPPORTED_ABIS?.firstOrNull().orEmpty()).append('\n')
        sb.append("root: ").append(termuxRoot).append('\n')
        sb.append("selected: ").append(selectedKey ?: "__local__").append('\n')
        sb.append("profiles_all: ").append(allEntries.size).append('\n')
        sb.append("profiles_remote: ").append(remoteEntries.size).append('\n')
        if (allEntries.isNotEmpty()) {
            sb.append("profiles_detail:\n")
            allEntries.forEach { entry ->
                sb.append("  - id=").append(entry.id)
                    .append(", title=").append(entry.displayName.ifBlank { entry.id })
                    .append(", transport=").append(entry.transport.name)
                    .append(", active=").append(entry.active)
                    .append(", running=").append(entry.running)
                    .append(", ssh=").append(sanitizeSshCommand(entry.sshCommand))
                    .append('\n')
            }
        }
        sb.append('\n')

        sb.append("summary: total=").append(totalChecks)
            .append(", pass=").append(passedChecks)
            .append(", fail=").append(failedChecks)
            .append(", skip=").append(skippedChecks)
            .append('\n')
        sb.append('\n')

        logs.forEach { line ->
            sb.append(line).append('\n')
        }
        sb.append('\n')
        sb.append("---- state_dump ----\n")
        sb.append(stateDump).append('\n')
        sb.append('\n')
        sb.append("---- trace_recent ----\n")
        sb.append(traceDump).append('\n')
        return sb.toString()
    }

    private fun buildSummary(
        success: Boolean,
        totalChecks: Int,
        passedChecks: Int,
        failedChecks: Int,
        skippedChecks: Int,
        costMs: Long,
        reportPath: String,
        failureHeadlines: List<String>
    ): String {
        val sb = StringBuilder(512)
        sb.append(if (success) "工业级自检测完成：未发现失败项" else "工业级自检测完成：发现失败项")
        sb.append('\n')
        sb.append("总计 ").append(totalChecks)
            .append(" 项，通过 ").append(passedChecks)
            .append("，失败 ").append(failedChecks)
            .append("，跳过 ").append(skippedChecks)
            .append('\n')
        sb.append("耗时 ").append(costMs).append(" ms").append('\n')
        sb.append("报告已保存到：").append(reportPath)
        if (failureHeadlines.isNotEmpty()) {
            sb.append('\n')
            sb.append("失败项：").append(failureHeadlines.take(6).joinToString("；"))
        }
        return sb.toString()
    }

    private fun buildReportFile(context: Context, runId: String): File {
        val reportDirectory = File(FileRootResolver.termuxPrivateRoot(context), ".termux/self-test-reports")
        if (!reportDirectory.exists()) {
            reportDirectory.mkdirs()
        }
        return File(reportDirectory, "industrial-self-test-$runId.txt")
    }

    private fun writeReportFile(reportFile: File, content: String) {
        try {
            reportFile.parentFile?.mkdirs()
            FileOutputStream(reportFile, false).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.flush()
                try {
                    output.fd.sync()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun nextAvailableDestination(
        serverStates: List<ServerRuntime>,
        sourceIndex: Int
    ): ServerRuntime? {
        if (serverStates.size <= 1) return null
        for (offset in 1 until serverStates.size) {
            val candidate = serverStates[(sourceIndex + offset) % serverStates.size]
            if (candidate.probeSucceeded) {
                return candidate
            }
        }
        return null
    }

    private fun <T> measure(block: () -> T): Pair<T, Long> {
        val started = System.nanoTime()
        val result = block()
        val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        return result to elapsedMs
    }

    private fun averageMs(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        return values.sum() / max(1, values.size)
    }

    private fun throughputText(bytes: Long, costMs: Long): String {
        if (bytes <= 0L || costMs <= 0L) {
            return "0 B/s"
        }
        val perSecond = bytes * 1000.0 / costMs.toDouble()
        return humanizeBytes(perSecond.toLong()) + "/s"
    }

    private fun humanizeBytes(bytes: Long): String {
        val safe = max(0L, bytes)
        if (safe < 1024L) return "$safe B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = safe.toDouble()
        var unitIndex = -1
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format(Locale.US, "%.2f %s", value, units[max(0, unitIndex)])
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun formatTime(ms: Long): String {
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))
        } catch (_: Exception) {
            ms.toString()
        }
    }

    private fun safeErrorText(throwable: Throwable): String {
        val name = throwable.javaClass.simpleName.ifBlank { "Throwable" }
        val message = throwable.message?.trim().orEmpty()
        return if (message.isEmpty()) name else "$name: $message"
    }

    private fun sanitizeSshCommand(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var out = raw
        out = out.replace(
            Regex("sshpass\\s+-p\\s+('[^']*'|\"[^\"]*\"|\\S+)", RegexOption.IGNORE_CASE),
            "sshpass -p ******"
        )
        out = out.replace(
            Regex("(?i)(password\\s*=\\s*)([^\\s]+)"),
            "$1******"
        )
        return out
    }

    private fun safeName(raw: String): String {
        val trimmed = raw.trim().ifBlank { "server" }
        return trimmed.replace(Regex("[^a-zA-Z0-9._-]+"), "_").take(48).ifBlank { "server" }
    }

    private fun joinVirtualPath(parent: String, child: String): String {
        val base = parent.replace('\\', '/').trimEnd('/')
        val name = child.replace('\\', '/').trimStart('/')
        return if (base.isEmpty()) "/$name" else "$base/$name"
    }

    private fun joinRelativePath(parent: String, child: String): String {
        return if (parent.isBlank()) child else "$parent/$child"
    }

    private fun normalizeRelativePath(path: String): String {
        return path.replace('\\', '/').trimStart('/').trim()
    }

    private fun deleteRecursively(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        try {
            file.delete()
        } catch (_: Throwable) {
        }
    }

    private fun pass(detail: String = "") = CheckOutcome(CheckStatus.PASS, detail)

    private fun fail(detail: String = "") = CheckOutcome(CheckStatus.FAIL, detail)

    private fun skip(detail: String = "") = CheckOutcome(CheckStatus.SKIP, detail)
}
