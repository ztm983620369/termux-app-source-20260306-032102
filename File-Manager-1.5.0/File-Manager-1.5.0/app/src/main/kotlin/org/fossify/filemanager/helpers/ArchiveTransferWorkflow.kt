package org.fossify.filemanager.helpers

import android.system.Os
import android.system.OsConstants
import com.termux.sessionsync.FileRootResolver
import com.termux.sessionsync.SessionFileCoordinator
import com.termux.sessionsync.SftpProtocolManager
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAndroidSAFFileItems
import org.fossify.commons.extensions.getFileInputStreamSync
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getIsPathDirectory
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.isRestrictedSAFOnlyRoot
import org.fossify.commons.models.FileDirItem
import org.fossify.filemanager.extensions.isArchiveFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class ArchiveTransferWorkflow(
    private val activity: BaseSimpleActivity,
    private val sessionFileCoordinator: SessionFileCoordinator = SessionFileCoordinator.getInstance()
) {

    data class ArchiveResult(
        val success: Boolean,
        val cancelled: Boolean = false,
        val message: String = "",
        val targetPath: String = "",
        val highlightPaths: ArrayList<String> = arrayListOf(),
        val installCommand: String = "",
        val dialogTitle: String = "",
        val operationId: String = "",
        val diagnosticsPath: String = ""
    )

    enum class CompressionFormat(val sevenZipType: String, val suffix: String) {
        SEVEN_Z("7z", ".7z"),
        ZIP("zip", ".zip"),
        TAR("tar", ".tar"),
        TAR_GZ("tar", ".tar.gz"),
        TAR_BZ2("tar", ".tar.bz2"),
        TAR_XZ("tar", ".tar.xz"),
        TAR_ZST("tar", ".tar.zst")
    }

    enum class CompressionLevel(val sevenZipLevel: String) {
        STORE("0"),
        FAST("1"),
        NORMAL("5"),
        MAXIMUM("9")
    }

    enum class DecompressConflictStrategy(val sevenZipSwitch: String) {
        AUTO_RENAME("-aou"),
        OVERWRITE("-aoa"),
        SKIP_EXISTING("-aos")
    }

    data class CompressionOptions(
        val archiveName: String = "",
        val format: CompressionFormat = CompressionFormat.SEVEN_Z,
        val level: CompressionLevel = CompressionLevel.NORMAL,
        val password: String = "",
        val encryptFileNames: Boolean = true
    )

    data class CompressionFormatChoice(
        val format: CompressionFormat,
        val label: String,
        val toolSummary: String = "",
        val remoteSource: Boolean = false
    )

    data class CompressionCapabilityResult(
        val success: Boolean,
        val remoteSource: Boolean = false,
        val choices: List<CompressionFormatChoice> = emptyList(),
        val detectedTools: List<String> = emptyList(),
        val message: String = ""
    )

    data class DecompressOptions(
        val outputFolderName: String = "",
        val conflictStrategy: DecompressConflictStrategy = DecompressConflictStrategy.AUTO_RENAME
    )

    private class ArchiveWorkflowException(message: String) : Exception(message)
    private class RemoteArchiveToolUnavailableException(message: String) : Exception(message)
    private class ArchiveCancelledException : Exception()
    private class MissingLocalArchiveToolException : Exception(
        "本地缺少 7-Zip 工具链。请在 Termux 终端执行安装命令后重试。"
    )

    private data class LocalArchiveTool(
        val binary: File,
        val displayName: String
    )

    private data class RemoteArchiveItem(
        val item: FileDirItem,
        val virtualRoot: String,
        val remotePath: String,
        val displayName: String
    )

    private data class RemoteArchiveContext(
        val virtualRoot: String,
        val destinationRemotePath: String,
        val items: List<RemoteArchiveItem>
    )

    private data class RemoteArchiveCapabilities(
        val tools: Set<String>
    )

    private val operationJournal = runCatching { ArchiveOperationJournal(activity) }.getOrNull()
    private val activeOperation = ThreadLocal<ArchiveOperationJournal.Handle?>()

    private enum class ArchiveKind(
        val suffixes: List<String>,
        val outputSuffix: String,
        val remoteCreateTool: String,
        val remoteCreateTemplate: String,
        val remoteExtractTool: String,
        val remoteExtractTemplate: String,
        val localZipCompatible: Boolean
    ) {
        ZIP(
            suffixes = listOf(".zip", ".jar", ".apk", ".aar", ".war"),
            outputSuffix = ".zip",
            remoteCreateTool = "zip",
            remoteCreateTemplate = "cd %PARENT% && %TOOL% -q -r -y -%ZIP_LEVEL% %OUTPUT% -- %SOURCES%",
            remoteExtractTool = "unzip",
            remoteExtractTemplate = "%TOOL% -q %ARCHIVE% -d %DEST%",
            localZipCompatible = true
        ),
        TAR(
            suffixes = listOf(".tar"),
            outputSuffix = ".tar",
            remoteCreateTool = "tar",
            remoteCreateTemplate = "cd %PARENT% && %TOOL% -cf %OUTPUT% -- %SOURCES%",
            remoteExtractTool = "tar",
            remoteExtractTemplate = "%TOOL% -xf %ARCHIVE% -C %DEST%",
            localZipCompatible = false
        ),
        TAR_GZ(
            suffixes = listOf(".tar.gz", ".tgz"),
            outputSuffix = ".tar.gz",
            remoteCreateTool = "tar",
            remoteCreateTemplate = "cd %PARENT% && %TOOL% -czf %OUTPUT% -- %SOURCES%",
            remoteExtractTool = "tar",
            remoteExtractTemplate = "%TOOL% -xzf %ARCHIVE% -C %DEST%",
            localZipCompatible = false
        ),
        TAR_BZ2(
            suffixes = listOf(".tar.bz2", ".tbz", ".tbz2"),
            outputSuffix = ".tar.bz2",
            remoteCreateTool = "tar",
            remoteCreateTemplate = "cd %PARENT% && %TOOL% -cjf %OUTPUT% -- %SOURCES%",
            remoteExtractTool = "tar",
            remoteExtractTemplate = "%TOOL% -xjf %ARCHIVE% -C %DEST%",
            localZipCompatible = false
        ),
        TAR_XZ(
            suffixes = listOf(".tar.xz", ".txz"),
            outputSuffix = ".tar.xz",
            remoteCreateTool = "tar",
            remoteCreateTemplate = "cd %PARENT% && %TOOL% -cJf %OUTPUT% -- %SOURCES%",
            remoteExtractTool = "tar",
            remoteExtractTemplate = "%TOOL% -xJf %ARCHIVE% -C %DEST%",
            localZipCompatible = false
        ),
        TAR_ZST(
            suffixes = listOf(".tar.zst", ".tzst"),
            outputSuffix = ".tar.zst",
            remoteCreateTool = "tar",
            remoteCreateTemplate = "cd %PARENT% && %TOOL% --zstd -cf %OUTPUT% -- %SOURCES%",
            remoteExtractTool = "tar",
            remoteExtractTemplate = "%TOOL% --zstd -xf %ARCHIVE% -C %DEST%",
            localZipCompatible = false
        ),
        GZ(
            suffixes = listOf(".gz"),
            outputSuffix = ".gz",
            remoteCreateTool = "gzip",
            remoteCreateTemplate = "%TOOL% -c -- %SOURCE_PATH% > %OUTPUT%",
            remoteExtractTool = "gzip",
            remoteExtractTemplate = "%TOOL% -dc -- %ARCHIVE% > %DEST_FILE%",
            localZipCompatible = false
        ),
        BZ2(
            suffixes = listOf(".bz2"),
            outputSuffix = ".bz2",
            remoteCreateTool = "bzip2",
            remoteCreateTemplate = "%TOOL% -c -- %SOURCE_PATH% > %OUTPUT%",
            remoteExtractTool = "bzip2",
            remoteExtractTemplate = "%TOOL% -dc -- %ARCHIVE% > %DEST_FILE%",
            localZipCompatible = false
        ),
        XZ(
            suffixes = listOf(".xz"),
            outputSuffix = ".xz",
            remoteCreateTool = "xz",
            remoteCreateTemplate = "%TOOL% -c -- %SOURCE_PATH% > %OUTPUT%",
            remoteExtractTool = "xz",
            remoteExtractTemplate = "%TOOL% -dc -- %ARCHIVE% > %DEST_FILE%",
            localZipCompatible = false
        ),
        ZST(
            suffixes = listOf(".zst"),
            outputSuffix = ".zst",
            remoteCreateTool = "zstd",
            remoteCreateTemplate = "%TOOL% -q -c -- %SOURCE_PATH% > %OUTPUT%",
            remoteExtractTool = "zstd",
            remoteExtractTemplate = "%TOOL% -q -dc -- %ARCHIVE% > %DEST_FILE%",
            localZipCompatible = false
        ),
        SEVEN_Z(
            suffixes = listOf(".7z"),
            outputSuffix = ".7z",
            remoteCreateTool = "7z",
            remoteCreateTemplate = "cd %PARENT% && %TOOL% a -t7z -mx=%LEVEL% -mmt=on -y %OUTPUT% -- %SOURCES% >/dev/null",
            remoteExtractTool = "7z",
            remoteExtractTemplate = "%TOOL% x -y %ARCHIVE% -o%DEST% >/dev/null",
            localZipCompatible = false
        ),
        RAR(
            suffixes = listOf(".rar"),
            outputSuffix = ".rar",
            remoteCreateTool = "rar",
            remoteCreateTemplate = "cd %PARENT% && %TOOL% a -idq %OUTPUT% -- %SOURCES%",
            remoteExtractTool = "unrar",
            remoteExtractTemplate = "%TOOL% x -idq -o+ %ARCHIVE% %DEST%/",
            localZipCompatible = false
        );

        companion object {
            fun fromName(name: String): ArchiveKind? {
                val lower = name.lowercase(Locale.ROOT)
                return values().firstOrNull { kind -> kind.suffixes.any { lower.endsWith(it) } }
            }
        }
    }

    init {
        sessionFileCoordinator.initialize(activity)
    }

    fun resolveCompressionFormatChoices(
        selectedItems: List<FileDirItem>,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): CompressionCapabilityResult {
        if (selectedItems.isEmpty()) {
            return CompressionCapabilityResult(success = false, message = "未选择需要压缩的项目。")
        }

        val remoteItems = selectedItems.filter { sessionFileCoordinator.isVirtualPath(activity, it.path) }
        if (remoteItems.isEmpty()) {
            return CompressionCapabilityResult(
                success = true,
                choices = defaultLocalCompressionChoices()
            )
        }
        if (remoteItems.size != selectedItems.size) {
            return CompressionCapabilityResult(
                success = false,
                remoteSource = true,
                message = "不能混选本地文件和服务器文件进行服务器压缩。请分开选择后重试。"
            )
        }

        val remoteContext = buildSingleServerRemoteSourceContext(selectedItems)
            ?: return CompressionCapabilityResult(
                success = false,
                remoteSource = true,
                message = "服务器文件压缩必须只选择同一台服务器上的项目。"
            )

        try {
            requireSameRemoteParent(remoteContext.items)
        } catch (throwable: Throwable) {
            return CompressionCapabilityResult(
                success = false,
                remoteSource = true,
                message = throwable.message?.ifBlank { null } ?: "服务器本地压缩当前要求选中项目位于同一目录。"
            )
        }

        progress("正在检测服务器压缩/解压工具...")
        val anchor = remoteContext.items.firstOrNull()?.item?.path
            ?: return CompressionCapabilityResult(
                success = false,
                remoteSource = true,
                message = "没有可压缩的服务器项目。"
            )
        val capabilities = try {
            detectRemoteArchiveCapabilities(anchor, cancelled)
        } catch (_: ArchiveCancelledException) {
            throw ArchiveCancelledException()
        } catch (throwable: Throwable) {
            return CompressionCapabilityResult(
                success = false,
                remoteSource = true,
                message = throwable.message?.ifBlank { null } ?: "检测服务器压缩工具失败。"
            )
        }

        val choices = remoteCompressionFormatChoices(capabilities)
        if (choices.isEmpty()) {
            return CompressionCapabilityResult(
                success = false,
                remoteSource = true,
                detectedTools = capabilities.tools.sorted(),
                message = buildNoRemoteCompressionFormatsMessage(capabilities)
            )
        }

        return CompressionCapabilityResult(
            success = true,
            remoteSource = true,
            choices = choices,
            detectedTools = capabilities.tools.sorted()
        )
    }

    fun compress(
        selectedItems: List<FileDirItem>,
        destinationDirectory: String,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit,
        options: CompressionOptions = CompressionOptions()
    ): ArchiveResult {
        if (selectedItems.isEmpty()) {
            return ArchiveResult(success = false, message = "未选择需要压缩的项目。")
        }

        buildSingleServerRemoteSourceContext(selectedItems)?.let { remoteSourceContext ->
            val jobDir = createJobDirectory()
            return runArchiveJob(
                jobDir,
                cancelled,
                operation = "压缩",
                sourcePaths = selectedItems.map { it.path },
                destinationPath = destinationDirectory
            ) {
                val destinationInfo = if (sessionFileCoordinator.isVirtualPath(activity, destinationDirectory)) {
                    sessionFileCoordinator.describeVirtualPath(activity, destinationDirectory)
                } else {
                    null
                }

                when {
                    destinationInfo == null -> compressRemoteSourceToLocal(
                        remoteContext = remoteSourceContext,
                        selectedItems = selectedItems,
                        destinationDirectory = destinationDirectory,
                        jobDir = jobDir,
                        cancelled = cancelled,
                        progress = progress,
                        options = options
                    )

                    destinationInfo.success && destinationInfo.virtualRoot == remoteSourceContext.virtualRoot -> {
                        compressOnServer(
                            remoteContext = remoteSourceContext.copy(destinationRemotePath = destinationInfo.remotePath),
                            selectedItems = selectedItems,
                            destinationDirectory = destinationDirectory,
                            cancelled = cancelled,
                            progress = progress,
                            options = options
                        )
                    }

                    destinationInfo.success -> compressRemoteSourceAcrossServers(
                        remoteContext = remoteSourceContext,
                        selectedItems = selectedItems,
                        destinationDirectory = destinationDirectory,
                        jobDir = jobDir,
                        cancelled = cancelled,
                        progress = progress,
                        options = options
                    )

                    else -> throw ArchiveWorkflowException(
                        destinationInfo.messageCn.ifBlank { "无法解析服务器目标目录。" }
                    )
                }
            }
        }

        if (selectedItems.any { sessionFileCoordinator.isVirtualPath(activity, it.path) }) {
            return ArchiveResult(
                success = false,
                message = "服务器文件压缩必须在同一台服务器上执行。请只选择同一服务器、同一目录下的项目后重试。",
                dialogTitle = "无法执行服务器压缩"
            )
        }

        val jobDir = createJobDirectory()
        return runArchiveJob(
            jobDir,
            cancelled,
            operation = "压缩",
            sourcePaths = selectedItems.map { it.path },
            destinationPath = destinationDirectory
        ) {
            requireLocalArchiveTool()
            progress("正在准备压缩任务...")
            val sourceStage = File(jobDir, "sources").also(::ensureDirectory)
            val stagedSources = stageSources(selectedItems, sourceStage, cancelled, progress)
            if (stagedSources.isEmpty()) {
                throw ArchiveWorkflowException("没有可压缩的项目。")
            }

            val requestedName = buildArchiveFileName(selectedItems, options)
            val finalName = resolveUniqueTargetName(destinationDirectory, requestedName, directory = false)
            val artifactDir = File(jobDir, "artifact").also(::ensureDirectory)
            val stagedArchive = File(artifactDir, finalName)
            createLocalArchive(stagedSources, stagedArchive, cancelled, progress, options)

            val committedPath = commitArtifact(
                stagedArtifact = stagedArchive,
                destinationDirectory = destinationDirectory,
                finalName = finalName,
                directory = false,
                cancelled = cancelled,
                progress = progress
            )

            ArchiveResult(
                success = true,
                message = "压缩完成：$finalName",
                targetPath = normalizeDirectoryPath(destinationDirectory),
                highlightPaths = arrayListOf(committedPath)
            )
        }
    }

    fun decompress(
        selectedItems: List<FileDirItem>,
        destinationDirectory: String,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit,
        options: DecompressOptions = DecompressOptions()
    ): ArchiveResult {
        val archiveItems = selectedItems.filter { it.path.isArchiveFile() }
        if (archiveItems.isEmpty()) {
            return ArchiveResult(success = false, message = "未选择可解压的压缩包。")
        }

        buildSameServerRemoteContext(archiveItems, destinationDirectory)?.let { remoteContext ->
            val jobDir = createJobDirectory()
            return runArchiveJob(
                jobDir,
                cancelled,
                operation = "解压",
                sourcePaths = archiveItems.map { it.path },
                destinationPath = destinationDirectory
            ) {
                decompressOnServer(remoteContext, archiveItems, destinationDirectory, cancelled, progress)
            }
        }

        buildCrossServerRemoteDecompressContext(archiveItems, destinationDirectory)?.let { remoteContext ->
            val jobDir = createJobDirectory()
            return runArchiveJob(
                jobDir,
                cancelled,
                operation = "解压",
                sourcePaths = archiveItems.map { it.path },
                destinationPath = destinationDirectory
            ) {
                decompressAcrossServers(remoteContext, archiveItems, destinationDirectory, cancelled, progress)
            }
        }

        val jobDir = createJobDirectory()
        return runArchiveJob(
            jobDir,
            cancelled,
            operation = "解压",
            sourcePaths = archiveItems.map { it.path },
            destinationPath = destinationDirectory
        ) {
            progress("正在准备解压任务...")
            val sourceStage = File(jobDir, "sources").also(::ensureDirectory)
            val stagedArchives = stageSources(archiveItems, sourceStage, cancelled, progress)
                .filter { it.isFile }
            if (stagedArchives.isEmpty()) {
                throw ArchiveWorkflowException("没有可解压的压缩包。")
            }

            val extractionStage = File(jobDir, "extracted").also(::ensureDirectory)
            val requestedFolderName = buildExtractFolderName(stagedArchives, options.outputFolderName)
            val artifact = if (stagedArchives.size == 1) {
                File(extractionStage, requestedFolderName).also(::ensureDirectory)
            } else {
                File(extractionStage, requestedFolderName).also(::ensureDirectory)
            }

            val extractRoots = if (stagedArchives.size == 1) {
                listOf(artifact)
            } else {
                val reservedExtractNames = linkedSetOf<String>()
                stagedArchives.map { archiveFile ->
                    resolveUniqueLocalChild(
                        artifact,
                        baseNameWithoutArchive(archiveFile.name),
                        directory = true,
                        reservedNames = reservedExtractNames
                    ).also(::ensureDirectory)
                }
            }

            stagedArchives.forEachIndexed { index, archiveFile ->
                extractLocalArchive(
                    archiveFile = archiveFile,
                    destinationRoot = extractRoots[index],
                    cancelled = cancelled,
                    progress = progress,
                    archiveIndex = index + 1,
                    archiveTotal = stagedArchives.size,
                    options = options
                )
            }

            val stagedArtifact = selectCommitArtifact(artifact, stagedArchives.size)
            if (stagedArtifact != artifact) {
                journalPhase(
                    phase = "NORMALIZING",
                    message = "折叠归档自带的同名顶层目录：${stagedArtifact.name}"
                )
                progress("正在整理归档目录结构...")
            }
            val finalName = resolveDecompressTargetName(destinationDirectory, artifact.name, options)
            val committedPath = commitArtifact(
                stagedArtifact = stagedArtifact,
                destinationDirectory = destinationDirectory,
                finalName = finalName,
                directory = true,
                conflictStrategy = options.conflictStrategy,
                cancelled = cancelled,
                progress = progress
            )

            ArchiveResult(
                success = true,
                message = "解压完成：$finalName",
                targetPath = normalizeDirectoryPath(destinationDirectory),
                highlightPaths = arrayListOf(committedPath)
            )
        }
    }

    private fun selectCommitArtifact(artifact: File, archiveCount: Int): File {
        val children = artifact.listFiles()?.toList()
            ?: throw ArchiveWorkflowException("无法读取解压结果目录：${artifact.absolutePath}")
        val entries = children.map { child ->
            val mode = runCatching { Os.lstat(child.absolutePath).st_mode }
                .getOrElse { throw ArchiveWorkflowException("无法检查解压结果：${child.absolutePath}") }
            ExtractedRootEntry(
                name = child.name,
                directory = OsConstants.S_ISDIR(mode),
                symbolicLink = OsConstants.S_ISLNK(mode)
            )
        }
        val selectedName = ArchiveLayoutPolicy.redundantSingleRootName(
            archiveCount = archiveCount,
            requestedRootName = artifact.name,
            children = entries
        ) ?: return artifact
        return File(artifact, selectedName)
    }

    private fun runArchiveJob(
        jobDir: File,
        cancelled: AtomicBoolean,
        operation: String,
        sourcePaths: List<String>,
        destinationPath: String,
        block: () -> ArchiveResult
    ): ArchiveResult {
        val handle = runCatching {
            operationJournal?.start(
                operation = operation,
                sourcePaths = sourcePaths,
                destinationPath = destinationPath,
                tempPaths = listOf(jobDir.absolutePath)
            )
        }.getOrNull()
        activeOperation.set(handle)
        return try {
            val rawResult = try {
                block()
            } catch (_: ArchiveCancelledException) {
                ArchiveResult(success = false, cancelled = true, message = "操作已取消。")
            } catch (missingTool: MissingLocalArchiveToolException) {
                ArchiveResult(
                    success = false,
                    message = missingTool.message.orEmpty(),
                    installCommand = TERMUX_7ZIP_INSTALL_COMMAND
                )
            } catch (missingRemoteTool: RemoteArchiveToolUnavailableException) {
                ArchiveResult(
                    success = false,
                    message = missingRemoteTool.message.orEmpty(),
                    dialogTitle = "服务器压缩格式不可用"
                )
            } catch (throwable: Throwable) {
                if (cancelled.get()) {
                    ArchiveResult(success = false, cancelled = true, message = "操作已取消。")
                } else {
                    ArchiveResult(
                        success = false,
                        message = throwable.message?.trim().orEmpty().ifBlank { "归档操作失败，请重试。" }
                    )
                }
            }

            val status = when {
                rawResult.success -> ArchiveOperationJournal.Status.SUCCEEDED
                rawResult.cancelled -> ArchiveOperationJournal.Status.CANCELLED
                else -> ArchiveOperationJournal.Status.FAILED
            }
            runCatching {
                if (handle != null) {
                    operationJournal?.finish(
                        handle,
                        status,
                        rawResult.message,
                        rawResult.highlightPaths.firstOrNull().orEmpty()
                    )
                }
            }

            val diagnosticsPath = operationJournal?.eventLogPath().orEmpty()
            val operationId = handle?.id.orEmpty()
            val diagnosticSuffix = if (!rawResult.success && !rawResult.cancelled && operationId.isNotBlank()) {
                buildString {
                    append("\n\n操作 ID：").append(operationId)
                    if (diagnosticsPath.isNotBlank()) append("\n诊断记录：").append(diagnosticsPath)
                }
            } else {
                ""
            }
            rawResult.copy(
                message = rawResult.message + diagnosticSuffix,
                dialogTitle = if (
                    !rawResult.success && !rawResult.cancelled && rawResult.installCommand.isBlank() && rawResult.dialogTitle.isBlank()
                ) "$operation 失败" else rawResult.dialogTitle,
                operationId = operationId,
                diagnosticsPath = diagnosticsPath
            )
        } finally {
            activeOperation.remove()
            deleteRecursivelySafe(jobDir)
        }
    }

    private fun journalPhase(
        phase: String,
        message: String = "",
        backend: String = "",
        tempPath: String = ""
    ) {
        val handle = activeOperation.get() ?: return
        runCatching {
            operationJournal?.phase(handle, phase, message, backend, tempPath)
        }
    }

    private fun compressOnServer(
        remoteContext: RemoteArchiveContext,
        selectedItems: List<FileDirItem>,
        destinationDirectory: String,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit,
        options: CompressionOptions
    ): ArchiveResult {
        throwIfCancelled(cancelled)
        progress("正在服务器本地压缩...")
        if (options.password.isNotBlank()) {
            throw ArchiveWorkflowException("服务器本地压缩暂不支持密码，请选择本地目标或取消密码。")
        }

        val requestedName = buildArchiveFileName(selectedItems, options)
        val finalName = resolveUniqueRemoteName(destinationDirectory, requestedName)
        val tempName = ".$finalName.termux-archive-${System.currentTimeMillis()}.part"
        val tempRemotePath = joinRemotePath(remoteContext.destinationRemotePath, tempName)
        val finalRemotePath = joinRemotePath(remoteContext.destinationRemotePath, finalName)

        try {
            createRemoteArchive(
                remoteContext = remoteContext,
                outputRemotePath = tempRemotePath,
                finalRemotePath = finalRemotePath,
                anchorVirtualPath = destinationDirectory,
                cancelled = cancelled,
                progress = progress,
                options = options
            )
        } catch (throwable: Throwable) {
            deleteRemoteQuietly(destinationDirectory, tempRemotePath, cancelled)
            throw throwable
        }
        progress("正在提交服务器压缩结果...")
        val highlightPath = remoteContext.virtualRoot + finalRemotePath
        return ArchiveResult(
            success = true,
            message = "服务器压缩完成：$finalName",
            targetPath = normalizeDirectoryPath(destinationDirectory),
            highlightPaths = arrayListOf(highlightPath)
        )
    }

    private fun compressRemoteSourceToLocal(
        remoteContext: RemoteArchiveContext,
        selectedItems: List<FileDirItem>,
        destinationDirectory: String,
        jobDir: File,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit,
        options: CompressionOptions
    ): ArchiveResult {
        throwIfCancelled(cancelled)
        progress("正在源服务器本地压缩...")
        if (options.password.isNotBlank()) {
            throw ArchiveWorkflowException("服务器本地压缩暂不支持密码，请选择本地文件后压缩，或取消密码。")
        }

        val requestedName = buildArchiveFileName(selectedItems, options)
        val finalName = resolveUniqueLocalName(destinationDirectory, requestedName)
        val sourceParent = requireSameRemoteParent(remoteContext.items)
        val tempRemotePath = joinRemotePath(sourceParent, buildRemoteTempArchiveName(finalName))
        val sourceAnchor = remoteContext.items.firstOrNull()?.item?.path
            ?: throw ArchiveWorkflowException("没有可压缩的服务器项目。")

        try {
            createRemoteArchive(
                remoteContext = remoteContext,
                outputRemotePath = tempRemotePath,
                finalRemotePath = null,
                anchorVirtualPath = sourceAnchor,
                cancelled = cancelled,
                progress = progress,
                options = options
            )

            progress("正在拉取服务器压缩包...")
            val downloadRoot = File(jobDir, "remote-archive").also(::ensureDirectory)
            val tempVirtualPath = remoteContext.virtualRoot + tempRemotePath
            val downloadResult = sessionFileCoordinator.downloadVirtualPaths(
                activity,
                listOf(tempVirtualPath),
                downloadRoot.absolutePath,
                object : SftpProtocolManager.DownloadProgressListener {
                    override fun onProgress(progressState: SftpProtocolManager.DownloadProgress) {
                        val current = progressState.currentFile.ifBlank { "准备中..." }
                        val totalBytes = progressState.totalBytes
                        val transferred = progressState.transferredBytes
                        val sizeText = if (totalBytes > 0L) {
                            "${transferred.formatSize()} / ${totalBytes.formatSize()}"
                        } else {
                            "${transferred.formatSize()} / ?"
                        }
                        progress(
                            "正在拉取服务器压缩包\n" +
                                "当前：$current\n" +
                                "进度：${progressState.completedFiles + progressState.failedFiles}/${progressState.totalFiles}\n" +
                                "大小：$sizeText"
                        )
                    }
                },
                object : SftpProtocolManager.DownloadControl {
                    override fun isCancelled(): Boolean = cancelled.get()
                }
            )
            if (!downloadResult.success) {
                if (cancelled.get() || isCancelledMessage(downloadResult.messageCn)) {
                    throw ArchiveCancelledException()
                }
                throw ArchiveWorkflowException(downloadResult.messageCn.ifBlank { "拉取服务器压缩包失败。" })
            }

            val downloadedArchive = downloadResult.downloadedLocalPaths
                .firstOrNull()
                ?.let(::File)
                ?.takeIf { it.exists() && it.isFile }
                ?: throw ArchiveWorkflowException("服务器压缩包下载完成但本地文件不存在。")

            progress("正在提交本地压缩包...")
            val committedPath = commitLocalArtifact(
                stagedArtifact = downloadedArchive,
                destinationDirectory = destinationDirectory,
                finalName = finalName,
                directory = false,
                conflictStrategy = null,
                cancelled = cancelled,
                progress = progress
            )

            return ArchiveResult(
                success = true,
                message = "服务器压缩并拉取完成：$finalName",
                targetPath = normalizeDirectoryPath(destinationDirectory),
                highlightPaths = arrayListOf(committedPath)
            )
        } finally {
            deleteRemoteQuietly(sourceAnchor, tempRemotePath, cancelled)
        }
    }

    private fun compressRemoteSourceAcrossServers(
        remoteContext: RemoteArchiveContext,
        selectedItems: List<FileDirItem>,
        destinationDirectory: String,
        jobDir: File,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit,
        options: CompressionOptions
    ): ArchiveResult {
        throwIfCancelled(cancelled)
        progress("正在源服务器本地压缩...")
        if (options.password.isNotBlank()) {
            throw ArchiveWorkflowException("服务器本地压缩暂不支持密码，请选择本地文件后压缩，或取消密码。")
        }

        val requestedName = buildArchiveFileName(selectedItems, options)
        val finalName = resolveUniqueRemoteName(destinationDirectory, requestedName)
        val sourceParent = requireSameRemoteParent(remoteContext.items)
        val tempRemotePath = joinRemotePath(sourceParent, buildRemoteTempArchiveName(finalName))
        val tempVirtualPath = remoteContext.virtualRoot + tempRemotePath
        val sourceAnchor = remoteContext.items.firstOrNull()?.item?.path
            ?: throw ArchiveWorkflowException("没有可压缩的服务器项目。")
        var uploadedPath = ""

        try {
            createRemoteArchive(
                remoteContext = remoteContext,
                outputRemotePath = tempRemotePath,
                finalRemotePath = null,
                anchorVirtualPath = sourceAnchor,
                cancelled = cancelled,
                progress = progress,
                options = options
            )

            progress("正在传输服务器压缩包...")
            val transferResult = sessionFileCoordinator.transferVirtualPaths(
                activity,
                listOf(tempVirtualPath),
                destinationDirectory,
                object : SftpProtocolManager.RemoteTransferProgressListener {
                    override fun onProgress(progressState: SftpProtocolManager.RemoteTransferProgress) {
                        val current = progressState.currentFile.ifBlank { "准备中..." }
                        val totalBytes = progressState.totalBytes
                        val transferred = progressState.transferredBytes
                        val sizeText = if (totalBytes > 0L) {
                            "${transferred.formatSize()} / ${totalBytes.formatSize()}"
                        } else {
                            "${transferred.formatSize()} / ?"
                        }
                        progress(
                            "正在传输服务器压缩包\n" +
                                "阶段：${progressState.stageLabelCn}\n" +
                                "当前：$current\n" +
                                "进度：${progressState.completedFiles + progressState.failedFiles}/${progressState.totalFiles}\n" +
                                "大小：$sizeText"
                        )
                    }
                },
                object : SftpProtocolManager.RemoteTransferControl {
                    override fun isCancelled(): Boolean = cancelled.get()
                }
            )

            if (!transferResult.success) {
                if (cancelled.get() || isCancelledMessage(transferResult.messageCn)) {
                    throw ArchiveCancelledException()
                }
                throw ArchiveWorkflowException(transferResult.messageCn.ifBlank { "传输服务器压缩包失败。" })
            }

            uploadedPath = transferResult.transferredVirtualPaths.firstOrNull().orEmpty()
            if (uploadedPath.isBlank()) {
                throw ArchiveWorkflowException("服务器压缩包传输完成但未返回目标路径。")
            }

            progress("正在提交目标服务器压缩包...")
            val renameResult = sessionFileCoordinator.renameVirtualPath(activity, uploadedPath, finalName)
            if (!renameResult.success) {
                sessionFileCoordinator.deleteVirtualPath(activity, uploadedPath)
                throw ArchiveWorkflowException(renameResult.messageCn.ifBlank { "提交目标服务器压缩包失败。" })
            }
            uploadedPath = ""

            return ArchiveResult(
                success = true,
                message = "服务器压缩并传输完成：$finalName",
                targetPath = normalizeDirectoryPath(destinationDirectory),
                highlightPaths = arrayListOf(renameResult.virtualPath)
            )
        } finally {
            if (uploadedPath.isNotBlank()) {
                try {
                    sessionFileCoordinator.deleteVirtualPath(activity, uploadedPath)
                } catch (_: Throwable) {
                }
            }
            deleteRemoteQuietly(sourceAnchor, tempRemotePath, cancelled)
            deleteRecursivelySafe(jobDir)
        }
    }

    private fun decompressOnServer(
        remoteContext: RemoteArchiveContext,
        archiveItems: List<FileDirItem>,
        destinationDirectory: String,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): ArchiveResult {
        throwIfCancelled(cancelled)
        progress("正在服务器本地解压...")

        val extractRootName = if (archiveItems.size == 1) {
            baseNameWithoutArchive(archiveItems.first().name.ifBlank { archiveItems.first().path.getFilenameFromPath() })
        } else {
            buildMultiExtractFolderName(archiveItems.map {
                File(it.name.ifBlank { it.path.getFilenameFromPath() })
            })
        }
        val finalName = resolveUniqueRemoteName(destinationDirectory, extractRootName)
        val tempName = ".$finalName.termux-extract-${System.currentTimeMillis()}.part"
        val tempRemotePath = joinRemotePath(remoteContext.destinationRemotePath, tempName)
        val finalRemotePath = joinRemotePath(remoteContext.destinationRemotePath, finalName)

        val command = buildRemoteExtractCommand(
            archives = remoteContext.items,
            destinationRoot = tempRemotePath,
            finalRemotePath = finalRemotePath
        )
        val result = executeRemoteCommand(destinationDirectory, command, cancelled)
        if (!result.success) {
            deleteRemoteQuietly(destinationDirectory, tempRemotePath, cancelled)
            throw ArchiveWorkflowException(result.messageCn.ifBlank { "服务器本地解压失败。" })
        }

        progress("正在提交服务器解压结果...")
        val highlightPath = remoteContext.virtualRoot + finalRemotePath
        return ArchiveResult(
            success = true,
            message = "服务器解压完成：$finalName",
            targetPath = normalizeDirectoryPath(destinationDirectory),
            highlightPaths = arrayListOf(highlightPath)
        )
    }

    private fun decompressAcrossServers(
        sourceContext: RemoteArchiveContext,
        archiveItems: List<FileDirItem>,
        destinationDirectory: String,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): ArchiveResult {
        throwIfCancelled(cancelled)
        progress("正在源服务器本地解压...")

        val sourceAnchor = sourceContext.items.firstOrNull()?.item?.path
            ?: throw ArchiveWorkflowException("没有可解压的服务器压缩包。")
        val extractRootName = if (archiveItems.size == 1) {
            baseNameWithoutArchive(archiveItems.first().name.ifBlank { archiveItems.first().path.getFilenameFromPath() })
        } else {
            buildMultiExtractFolderName(archiveItems.map {
                File(it.name.ifBlank { it.path.getFilenameFromPath() })
            })
        }
        val sourceParent = remoteParentPath(sourceContext.items.first().remotePath)
        val sourceTempName = ".termux-cross-extract-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val sourceTempRemotePath = joinRemotePath(sourceParent, sourceTempName)
        val sourceTempVirtualPath = sourceContext.virtualRoot + sourceTempRemotePath
        var uploadedPath = ""

        try {
            val extractCommand = buildRemoteExtractCommand(
                archives = sourceContext.items,
                destinationRoot = sourceTempRemotePath,
                finalRemotePath = null
            )
            val extractResult = executeRemoteCommand(sourceAnchor, extractCommand, cancelled)
            if (!extractResult.success) {
                throw ArchiveWorkflowException(extractResult.messageCn.ifBlank { "源服务器解压失败。" })
            }

            progress("正在跨服务器传输解压结果...")
            val transferResult = sessionFileCoordinator.transferVirtualPaths(
                activity,
                listOf(sourceTempVirtualPath),
                destinationDirectory,
                object : SftpProtocolManager.RemoteTransferProgressListener {
                    override fun onProgress(progressState: SftpProtocolManager.RemoteTransferProgress) {
                        val current = progressState.currentFile.ifBlank { "准备中..." }
                        val totalBytes = progressState.totalBytes
                        val transferred = progressState.transferredBytes
                        val sizeText = if (totalBytes > 0L) {
                            "${transferred.formatSize()} / ${totalBytes.formatSize()}"
                        } else {
                            "${transferred.formatSize()} / ?"
                        }
                        progress(
                            "正在跨服务器传输解压结果\n" +
                                "阶段：${progressState.stageLabelCn}\n" +
                                "当前：$current\n" +
                                "进度：${progressState.completedFiles + progressState.failedFiles}/${progressState.totalFiles}\n" +
                                "大小：$sizeText"
                        )
                    }
                },
                object : SftpProtocolManager.RemoteTransferControl {
                    override fun isCancelled(): Boolean = cancelled.get()
                }
            )

            if (!transferResult.success) {
                if (cancelled.get() || isCancelledMessage(transferResult.messageCn)) {
                    throw ArchiveCancelledException()
                }
                throw ArchiveWorkflowException(transferResult.messageCn.ifBlank { "跨服务器传输解压结果失败。" })
            }

            uploadedPath = transferResult.transferredVirtualPaths.firstOrNull().orEmpty()
            if (uploadedPath.isBlank()) {
                throw ArchiveWorkflowException("跨服务器传输完成但未返回目标路径。")
            }

            progress("正在提交目标服务器解压结果...")
            val finalName = resolveUniqueTargetName(destinationDirectory, extractRootName, directory = true)
            val renameResult = sessionFileCoordinator.renameVirtualPath(activity, uploadedPath, finalName)
            if (!renameResult.success) {
                sessionFileCoordinator.deleteVirtualPath(activity, uploadedPath)
                throw ArchiveWorkflowException(renameResult.messageCn.ifBlank { "提交目标服务器解压结果失败。" })
            }
            uploadedPath = ""

            return ArchiveResult(
                success = true,
                message = "跨服务器解压完成：$finalName",
                targetPath = normalizeDirectoryPath(destinationDirectory),
                highlightPaths = arrayListOf(renameResult.virtualPath)
            )
        } finally {
            if (uploadedPath.isNotBlank()) {
                try {
                    sessionFileCoordinator.deleteVirtualPath(activity, uploadedPath)
                } catch (_: Throwable) {
                }
            }
            deleteRemoteQuietly(sourceAnchor, sourceTempRemotePath, cancelled)
        }
    }

    private fun buildSameServerRemoteContext(
        selectedItems: List<FileDirItem>,
        destinationDirectory: String
    ): RemoteArchiveContext? {
        if (selectedItems.isEmpty() || !sessionFileCoordinator.isVirtualPath(activity, destinationDirectory)) {
            return null
        }
        if (selectedItems.any { !sessionFileCoordinator.isVirtualPath(activity, it.path) }) {
            return null
        }

        val destinationInfo = sessionFileCoordinator.describeVirtualPath(activity, destinationDirectory)
        if (!destinationInfo.success) {
            return null
        }
        val remoteItems = selectedItems.mapNotNull { item ->
            val info = sessionFileCoordinator.describeVirtualPath(activity, item.path)
            if (!info.success || info.virtualRoot != destinationInfo.virtualRoot) {
                null
            } else {
                RemoteArchiveItem(
                    item = item,
                    virtualRoot = info.virtualRoot,
                    remotePath = info.remotePath,
                    displayName = info.displayName
                )
            }
        }
        if (remoteItems.size != selectedItems.size) {
            return null
        }

        return RemoteArchiveContext(
            virtualRoot = destinationInfo.virtualRoot,
            destinationRemotePath = destinationInfo.remotePath,
            items = remoteItems
        )
    }

    private fun buildSingleServerRemoteSourceContext(
        selectedItems: List<FileDirItem>
    ): RemoteArchiveContext? {
        if (selectedItems.isEmpty()) {
            return null
        }
        if (selectedItems.any { !sessionFileCoordinator.isVirtualPath(activity, it.path) }) {
            return null
        }

        val sourceInfos = selectedItems.map { item ->
            item to sessionFileCoordinator.describeVirtualPath(activity, item.path)
        }
        if (sourceInfos.any { !it.second.success }) {
            return null
        }

        val sourceRoot = sourceInfos.first().second.virtualRoot
        if (sourceInfos.any { it.second.virtualRoot != sourceRoot }) {
            return null
        }

        val remoteItems = sourceInfos.map { (item, info) ->
            RemoteArchiveItem(
                item = item,
                virtualRoot = info.virtualRoot,
                remotePath = info.remotePath,
                displayName = info.displayName
            )
        }

        return RemoteArchiveContext(
            virtualRoot = sourceRoot,
            destinationRemotePath = commonRemoteParent(remoteItems.map { it.remotePath }),
            items = remoteItems
        )
    }

    private fun buildCrossServerRemoteDecompressContext(
        selectedItems: List<FileDirItem>,
        destinationDirectory: String
    ): RemoteArchiveContext? {
        if (selectedItems.isEmpty() || !sessionFileCoordinator.isVirtualPath(activity, destinationDirectory)) {
            return null
        }
        if (selectedItems.any { !sessionFileCoordinator.isVirtualPath(activity, it.path) }) {
            return null
        }
        val sourceInfos = selectedItems.map { item ->
            item to sessionFileCoordinator.describeVirtualPath(activity, item.path)
        }
        if (sourceInfos.any { !it.second.success }) {
            return null
        }
        val sourceRoot = sourceInfos.first().second.virtualRoot
        if (sourceInfos.any { it.second.virtualRoot != sourceRoot }) {
            return null
        }
        val destinationInfo = sessionFileCoordinator.describeVirtualPath(activity, destinationDirectory)
        if (!destinationInfo.success || destinationInfo.virtualRoot == sourceRoot) {
            return null
        }
        val remoteItems = sourceInfos.map { (item, info) ->
            RemoteArchiveItem(
                item = item,
                virtualRoot = info.virtualRoot,
                remotePath = info.remotePath,
                displayName = info.displayName
            )
        }
        return RemoteArchiveContext(
            virtualRoot = sourceRoot,
            destinationRemotePath = destinationInfo.remotePath,
            items = remoteItems
        )
    }

    private fun createRemoteArchive(
        remoteContext: RemoteArchiveContext,
        outputRemotePath: String,
        finalRemotePath: String?,
        anchorVirtualPath: String,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit,
        options: CompressionOptions
    ) {
        throwIfCancelled(cancelled)
        if (options.password.isNotBlank()) {
            throw ArchiveWorkflowException("服务器本地压缩暂不支持密码，请选择本地文件后压缩，或取消密码。")
        }

        val archiveKind = remoteArchiveKindFor(options.format)
        val parentRemote = requireSameRemoteParent(remoteContext.items)
        val sourceNames = remoteContext.items.map { remoteBaseName(it.remotePath) }
        progress("正在检查服务器压缩工具...")
        val capabilities = detectRemoteArchiveCapabilities(anchorVirtualPath, cancelled)
        val createTool = selectRemoteCreateTool(archiveKind, capabilities)
            ?: throw RemoteArchiveToolUnavailableException(
                buildRemoteCompressionUnavailableMessage(archiveKind, capabilities)
            )

        progress(
            "正在源服务器本地压缩\n" +
                "格式：${remoteArchiveDisplayName(archiveKind)}\n" +
                "工具：$createTool\n" +
                "项目：${sourceNames.size}"
        )

        val command = buildRemoteCreateCommand(
            archiveKind = archiveKind,
            parentRemote = parentRemote,
            sourceNames = sourceNames,
            outputRemotePath = outputRemotePath,
            finalRemotePath = finalRemotePath,
            createTool = createTool,
            options = options
        )
        val result = executeRemoteCommand(anchorVirtualPath, command, cancelled)
        if (!result.success) {
            throw ArchiveWorkflowException(result.messageCn.ifBlank { "服务器本地压缩失败。" })
        }
    }

    private fun buildRemoteCreateCommand(
        archiveKind: ArchiveKind,
        parentRemote: String,
        sourceNames: List<String>,
        outputRemotePath: String,
        finalRemotePath: String?,
        createTool: String,
        options: CompressionOptions
    ): String {
        if (sourceNames.isEmpty()) {
            throw ArchiveWorkflowException("没有可压缩的服务器项目。")
        }
        val requiredTools = requiredRemoteCreateTools(archiveKind, createTool)
        val guard = requiredTools.joinToString(" && ") { buildRemoteToolGuard(it) }
        val sourceArgs = sourceNames.joinToString(" ") { shellQuote(it) }
        val createCommand = archiveKind.remoteCreateTemplate
            .replace("%TOOL%", shellQuote(createTool))
            .replace("%PARENT%", shellQuote(parentRemote))
            .replace("%OUTPUT%", shellQuote(outputRemotePath))
            .replace("%SOURCES%", sourceArgs)
            .replace("%LEVEL%", options.level.sevenZipLevel)
            .replace("%ZIP_LEVEL%", options.level.sevenZipLevel)
        val finalExistsGuard = finalRemotePath?.let {
            "if [ -e ${shellQuote(it)} ]; then ${remoteEcho("目标已存在：$it")} >&2; exit 17; fi && "
        }.orEmpty()
        val finalRaceGuard = finalRemotePath?.let {
            "if [ -e ${shellQuote(it)} ]; then rm -rf -- ${shellQuote(outputRemotePath)}; ${remoteEcho("目标已存在：$it")} >&2; exit 17; fi && " +
                "mv -- ${shellQuote(outputRemotePath)} ${shellQuote(it)}"
        } ?: "true"

        return guard + " && " +
            "rm -rf -- ${shellQuote(outputRemotePath)} && " +
            finalExistsGuard +
            createCommand + " && " +
            "if [ ! -s ${shellQuote(outputRemotePath)} ]; then ${remoteEcho("服务器未生成有效压缩包：$outputRemotePath")} >&2; exit 18; fi && " +
            finalRaceGuard
    }

    private fun buildRemoteExtractCommand(
        archives: List<RemoteArchiveItem>,
        destinationRoot: String,
        finalRemotePath: String?
    ): String {
        if (archives.isEmpty()) {
            throw ArchiveWorkflowException("没有可解压的服务器压缩包。")
        }

        val guard = archives
            .mapNotNull { ArchiveKind.fromName(it.remotePath)?.remoteExtractTool }
            .distinct()
            .joinToString(" && ") { buildRemoteToolGuard(it) }
            .ifBlank { "true" }
        val extractCommands = archives.mapIndexed { index, archiveItem ->
            val archiveKind = ArchiveKind.fromName(archiveItem.remotePath)
                ?: throw ArchiveWorkflowException("不支持的压缩格式：${archiveItem.item.name}")
            val targetRoot = if (archives.size == 1) {
                destinationRoot
            } else {
                joinRemotePath(destinationRoot, baseNameWithoutArchive(archiveItem.item.name.ifBlank { archiveItem.remotePath }))
            }
            val prepare = "mkdir -p -- ${shellQuote(targetRoot)}"
            val extract = buildRemoteSingleExtractCommand(archiveKind, archiveItem.remotePath, targetRoot)
            remoteEcho("解压 ${index + 1}/${archives.size}: ${archiveItem.remotePath}") + " && $prepare && $extract"
        }.joinToString(" && ")

        val prepare = guard + " && rm -rf -- ${shellQuote(destinationRoot)} && "
        if (finalRemotePath == null) {
            return prepare + "mkdir -p -- ${shellQuote(destinationRoot)} && " + extractCommands
        }

        return prepare +
            "if [ -e ${shellQuote(finalRemotePath)} ]; then ${remoteEcho("目标已存在：$finalRemotePath")} >&2; exit 17; fi && " +
            "mkdir -p -- ${shellQuote(destinationRoot)} && " +
            extractCommands + " && " +
            "if [ -e ${shellQuote(finalRemotePath)} ]; then rm -rf -- ${shellQuote(destinationRoot)}; ${remoteEcho("目标已存在：$finalRemotePath")} >&2; exit 17; fi && " +
            "mv -- ${shellQuote(destinationRoot)} ${shellQuote(finalRemotePath)}"
    }

    private fun buildRemoteSingleExtractCommand(
        archiveKind: ArchiveKind,
        archiveRemotePath: String,
        destinationRoot: String
    ): String {
        val archiveArg = shellQuote(archiveRemotePath)
        val destinationArg = shellQuote(destinationRoot)
        val destinationFile = shellQuote(joinRemotePath(destinationRoot, baseNameWithoutArchive(archiveRemotePath)))
        return archiveKind.remoteExtractTemplate
            .replace("%TOOL%", shellQuote(archiveKind.remoteExtractTool))
            .replace("%ARCHIVE%", archiveArg)
            .replace("%DEST%", destinationArg)
            .replace("%DEST_FILE%", destinationFile)
    }

    private fun buildRemoteToolGuard(tool: String): String {
        return "command -v ${shellQuote(tool)} >/dev/null 2>&1 || { ${remoteEcho("服务器缺少归档工具：$tool")} >&2; exit 127; }"
    }

    private fun detectRemoteArchiveCapabilities(
        anchorVirtualPath: String,
        cancelled: AtomicBoolean
    ): RemoteArchiveCapabilities {
        throwIfCancelled(cancelled)
        val tools = REMOTE_TOOL_PROBE_TOOLS.joinToString(" ") { shellQuote(it) }
        val command = "for t in $tools; do " +
            "if command -v \"\$t\" >/dev/null 2>&1; then printf 'tool:%s\\n' \"\$t\"; fi; " +
            "done"
        val result = executeRemoteCommand(anchorVirtualPath, command, cancelled)
        if (!result.success) {
            throw ArchiveWorkflowException(result.messageCn.ifBlank { "无法检查服务器压缩工具。" })
        }
        return RemoteArchiveCapabilities(
            tools = result.stdout
                .lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("tool:") }
                .map { it.substringAfter("tool:").trim() }
                .filter { it.isNotBlank() }
                .toSet()
        )
    }

    private fun defaultLocalCompressionChoices(): List<CompressionFormatChoice> {
        return listOf(
            CompressionFormatChoice(CompressionFormat.SEVEN_Z, "7z（推荐）"),
            CompressionFormatChoice(CompressionFormat.ZIP, "ZIP"),
            CompressionFormatChoice(CompressionFormat.TAR, "TAR")
        )
    }

    private fun remoteCompressionFormatChoices(
        capabilities: RemoteArchiveCapabilities
    ): List<CompressionFormatChoice> {
        return REMOTE_CREATE_FORMAT_PREFERENCE.mapNotNull { archiveKind ->
            val createTool = selectRemoteCreateTool(archiveKind, capabilities) ?: return@mapNotNull null
            val format = compressionFormatForArchiveKind(archiveKind) ?: return@mapNotNull null
            CompressionFormatChoice(
                format = format,
                label = "${remoteArchiveDisplayName(archiveKind)} · 服务器：$createTool",
                toolSummary = createTool,
                remoteSource = true
            )
        }
    }

    private fun selectRemoteCreateTool(
        archiveKind: ArchiveKind,
        capabilities: RemoteArchiveCapabilities
    ): String? {
        if (archiveKind == ArchiveKind.SEVEN_Z) {
            return REMOTE_7ZIP_TOOL_CANDIDATES.firstOrNull { capabilities.tools.contains(it) }
        }
        val createTool = when (archiveKind) {
            ArchiveKind.TAR,
            ArchiveKind.TAR_GZ,
            ArchiveKind.TAR_BZ2,
            ArchiveKind.TAR_XZ,
            ArchiveKind.TAR_ZST -> REMOTE_TAR_TOOL_CANDIDATES.firstOrNull { capabilities.tools.contains(it) }
            else -> archiveKind.remoteCreateTool.takeIf { capabilities.tools.contains(it) }
        } ?: return null
        val required = requiredRemoteCreateTools(archiveKind, createTool)
        return if (required.all { capabilities.tools.contains(it) }) {
            createTool
        } else {
            null
        }
    }

    private fun requiredRemoteCreateTools(archiveKind: ArchiveKind, createTool: String): List<String> {
        return when (archiveKind) {
            ArchiveKind.SEVEN_Z -> listOf(createTool)
            ArchiveKind.TAR_GZ -> listOf(createTool, "gzip")
            ArchiveKind.TAR_BZ2 -> listOf(createTool, "bzip2")
            ArchiveKind.TAR_XZ -> listOf(createTool, "xz")
            ArchiveKind.TAR_ZST -> listOf(createTool, "zstd")
            ArchiveKind.TAR -> listOf(createTool)
            else -> listOf(createTool)
        }
    }

    private fun canCreateRemoteArchive(
        archiveKind: ArchiveKind,
        capabilities: RemoteArchiveCapabilities
    ): Boolean {
        return selectRemoteCreateTool(archiveKind, capabilities) != null
    }

    private fun buildRemoteCompressionUnavailableMessage(
        archiveKind: ArchiveKind,
        capabilities: RemoteArchiveCapabilities
    ): String {
        val missing = missingRemoteCreateToolLabels(archiveKind, capabilities).joinToString("、")
        val availableFormats = REMOTE_DIRECTORY_ARCHIVE_KINDS
            .filter { canCreateRemoteArchive(it, capabilities) }
            .joinToString("、") { remoteArchiveDisplayName(it) }
            .ifBlank { "未检测到可用的文件夹压缩格式" }
        val detectedTools = capabilities.tools.sorted().joinToString("、").ifBlank { "无" }
        return "服务器无法创建 ${remoteArchiveDisplayName(archiveKind)} 压缩包，缺少工具：$missing。\n" +
            "服务器可用压缩格式：$availableFormats。\n" +
            "已检测到的命令：$detectedTools。\n" +
            "请在压缩选项中选择可用格式，或在服务器安装 7-Zip/zip/tar 后重试。"
    }

    private fun buildNoRemoteCompressionFormatsMessage(capabilities: RemoteArchiveCapabilities): String {
        val detectedTools = capabilities.tools.sorted().joinToString("、").ifBlank { "无" }
        return "服务器未检测到可用于文件夹压缩的工具。\n" +
            "已检测到的压缩/解压命令：$detectedTools。\n" +
            "至少需要 tar、zip 或 7z/7zz/7za 中的一个。"
    }

    private fun missingRemoteCreateToolLabels(
        archiveKind: ArchiveKind,
        capabilities: RemoteArchiveCapabilities
    ): List<String> {
        if (archiveKind == ArchiveKind.SEVEN_Z) {
            return if (REMOTE_7ZIP_TOOL_CANDIDATES.any { capabilities.tools.contains(it) }) {
                emptyList()
            } else {
                listOf(REMOTE_7ZIP_TOOL_CANDIDATES.joinToString("/"))
            }
        }
        val createTool = when (archiveKind) {
            ArchiveKind.TAR,
            ArchiveKind.TAR_GZ,
            ArchiveKind.TAR_BZ2,
            ArchiveKind.TAR_XZ,
            ArchiveKind.TAR_ZST -> REMOTE_TAR_TOOL_CANDIDATES.firstOrNull { capabilities.tools.contains(it) }
                ?: archiveKind.remoteCreateTool
            else -> archiveKind.remoteCreateTool
        }
        return requiredRemoteCreateTools(archiveKind, createTool)
            .filterNot { capabilities.tools.contains(it) }
    }

    private fun remoteArchiveDisplayName(archiveKind: ArchiveKind): String {
        return when (archiveKind) {
            ArchiveKind.SEVEN_Z -> "7z (.7z)"
            ArchiveKind.ZIP -> "ZIP (.zip)"
            ArchiveKind.TAR -> "TAR (.tar)"
            ArchiveKind.TAR_GZ -> "TAR.GZ (.tar.gz)"
            ArchiveKind.TAR_BZ2 -> "TAR.BZ2 (.tar.bz2)"
            ArchiveKind.TAR_XZ -> "TAR.XZ (.tar.xz)"
            ArchiveKind.TAR_ZST -> "TAR.ZST (.tar.zst)"
            ArchiveKind.GZ -> "GZIP (.gz)"
            ArchiveKind.BZ2 -> "BZIP2 (.bz2)"
            ArchiveKind.XZ -> "XZ (.xz)"
            ArchiveKind.ZST -> "ZSTD (.zst)"
            ArchiveKind.RAR -> "RAR (.rar)"
        }
    }

    private fun compressionFormatForArchiveKind(archiveKind: ArchiveKind): CompressionFormat? {
        return when (archiveKind) {
            ArchiveKind.SEVEN_Z -> CompressionFormat.SEVEN_Z
            ArchiveKind.ZIP -> CompressionFormat.ZIP
            ArchiveKind.TAR -> CompressionFormat.TAR
            ArchiveKind.TAR_GZ -> CompressionFormat.TAR_GZ
            ArchiveKind.TAR_BZ2 -> CompressionFormat.TAR_BZ2
            ArchiveKind.TAR_XZ -> CompressionFormat.TAR_XZ
            ArchiveKind.TAR_ZST -> CompressionFormat.TAR_ZST
            else -> null
        }
    }

    private fun executeRemoteCommand(
        anchorVirtualPath: String,
        command: String,
        cancelled: AtomicBoolean
    ): SftpProtocolManager.RemoteCommandResult {
        throwIfCancelled(cancelled)
        return sessionFileCoordinator.executeRemoteCommand(
            activity,
            anchorVirtualPath,
            command,
            object : SftpProtocolManager.RemoteCommandControl {
                override fun isCancelled(): Boolean = cancelled.get()
            }
        )
    }

    private fun deleteRemoteQuietly(
        anchorVirtualPath: String,
        remotePath: String,
        cancelled: AtomicBoolean
    ) {
        try {
            val info = sessionFileCoordinator.describeVirtualPath(activity, anchorVirtualPath)
            if (!info.success) return
            val virtualPath = info.virtualRoot + remotePath
            sessionFileCoordinator.deleteVirtualPath(activity, virtualPath)
        } catch (_: Throwable) {
            if (cancelled.get()) return
        }
    }

    private fun stageSources(
        selectedItems: List<FileDirItem>,
        sourceStage: File,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): List<File> {
        throwIfCancelled(cancelled)

        val staged = ArrayList<File>(selectedItems.size)
        val remoteItems = selectedItems.filter { sessionFileCoordinator.isVirtualPath(activity, it.path) }
        val localItems = selectedItems.filterNot { sessionFileCoordinator.isVirtualPath(activity, it.path) }

        if (remoteItems.isNotEmpty()) {
            progress("正在下载服务器源文件...")
            val remotePaths = remoteItems.map { it.path }
            val downloadResult = sessionFileCoordinator.downloadVirtualPaths(
                activity,
                remotePaths,
                sourceStage.absolutePath,
                object : SftpProtocolManager.DownloadProgressListener {
                    override fun onProgress(progressState: SftpProtocolManager.DownloadProgress) {
                        val current = progressState.currentFile.ifBlank { "准备中..." }
                        val totalBytes = progressState.totalBytes
                        val transferred = progressState.transferredBytes
                        val sizeText = if (totalBytes > 0L) {
                            "${transferred.formatSize()} / ${totalBytes.formatSize()}"
                        } else {
                            "${transferred.formatSize()} / ?"
                        }
                        progress(
                            "下载服务器源文件\n" +
                                "当前：$current\n" +
                                "进度：${progressState.completedFiles + progressState.failedFiles}/${progressState.totalFiles}\n" +
                                "大小：$sizeText"
                        )
                    }
                },
                object : SftpProtocolManager.DownloadControl {
                    override fun isCancelled(): Boolean = cancelled.get()
                }
            )

            if (!downloadResult.success) {
                if (cancelled.get() || isCancelledMessage(downloadResult.messageCn)) {
                    throw ArchiveCancelledException()
                }
                throw ArchiveWorkflowException(
                    downloadResult.messageCn.ifBlank { "下载服务器源文件失败。" }
                )
            }

            downloadResult.downloadedLocalPaths.forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    staged.add(file)
                }
            }
        }

        if (localItems.isNotEmpty()) {
            val reservedNames = sourceStage.list()?.toCollection(linkedSetOf()) ?: linkedSetOf()
            val totalBytes = localItems.sumOf { safeLocalSize(it) }.coerceAtLeast(0L)
            var copiedBytes = 0L

            localItems.forEachIndexed { index, item ->
                throwIfCancelled(cancelled)
                val target = resolveUniqueLocalChild(
                    parent = sourceStage,
                    preferredName = item.name.ifBlank { item.path.getFilenameFromPath() },
                    directory = item.isDirectory,
                    reservedNames = reservedNames
                )
                progress(
                    "正在暂存本地源文件\n" +
                        "当前：${item.name.ifBlank { item.path.getFilenameFromPath() }}\n" +
                        "进度：$index/${localItems.size}\n" +
                        "大小：${copiedBytes.formatSize()} / ${if (totalBytes > 0L) totalBytes.formatSize() else "?"}"
                )
                copyLocalPathToStage(
                    sourcePath = item.path,
                    destination = target,
                    directory = item.isDirectory || activity.getIsPathDirectory(item.path),
                    cancelled = cancelled
                ) { copied ->
                    copiedBytes += copied
                    progress(
                        "正在暂存本地源文件\n" +
                            "当前：${item.name.ifBlank { item.path.getFilenameFromPath() }}\n" +
                            "进度：${index + 1}/${localItems.size}\n" +
                            "大小：${copiedBytes.formatSize()} / ${if (totalBytes > 0L) totalBytes.formatSize() else "?"}"
                    )
                }
                staged.add(target)
            }
        }

        return staged
    }

    private fun createLocalArchive(
        stagedSources: List<File>,
        outputArchive: File,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit,
        options: CompressionOptions
    ) {
        throwIfCancelled(cancelled)
        if (stagedSources.isEmpty()) {
            throw ArchiveWorkflowException("没有可压缩的项目。")
        }
        if (options.format !in LOCAL_7ZIP_CREATE_FORMATS) {
            throw ArchiveWorkflowException("本地压缩当前不支持 ${options.format.suffix}，请选择 7z、ZIP 或 TAR。")
        }
        if (options.format == CompressionFormat.TAR && options.password.isNotBlank()) {
            throw ArchiveWorkflowException("TAR 格式不支持密码，请选择 7z 或 ZIP。")
        }

        val tool = requireLocalArchiveTool()
        outputArchive.parentFile?.let(::ensureDirectory)
        if (outputArchive.exists()) {
            deleteRecursivelySafe(outputArchive)
        }

        val workingDirectory = stagedSources.first().parentFile
            ?: throw ArchiveWorkflowException("无法定位压缩工作目录。")
        val sourceArgs = stagedSources.map { "./${it.name}" }
        val totalBytes = stagedSources.sumOf { fileTreeSize(it) }.coerceAtLeast(0L)
        progress(
            "正在使用 7-Zip 压缩\n" +
                "工具：${tool.displayName}\n" +
                "格式：${options.format.sevenZipType}\n" +
                "项目：${stagedSources.size}\n" +
                "大小：${if (totalBytes > 0L) totalBytes.formatSize() else "?"}"
        )

        val command = arrayListOf(
            tool.binary.absolutePath,
            "a",
            "-t${options.format.sevenZipType}",
            "-mx=${options.level.sevenZipLevel}",
            "-mmt=on",
            "-y",
            "-bb1",
            "-bd",
            outputArchive.absolutePath
        )
        if (options.password.isNotBlank()) {
            command.add("-p${options.password}")
            if (options.format == CompressionFormat.SEVEN_Z && options.encryptFileNames) {
                command.add("-mhe=on")
            }
        }
        command.addAll(sourceArgs)

        runLocalArchiveCommand(
            command = command,
            workingDirectory = workingDirectory,
            cancelled = cancelled,
            title = "正在使用 7-Zip 压缩",
            failureMessage = "7-Zip 压缩失败。",
            progress = progress
        )

        if (!outputArchive.exists() || outputArchive.length() <= 0L) {
            throw ArchiveWorkflowException("7-Zip 未生成有效压缩包。")
        }
    }

    private fun extractLocalArchive(
        archiveFile: File,
        destinationRoot: File,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit,
        archiveIndex: Int,
        archiveTotal: Int,
        options: DecompressOptions
    ) {
        throwIfCancelled(cancelled)
        ensureDirectory(destinationRoot)

        val sourceBytes = archiveFile.length()
        val sourceModifiedAt = archiveFile.lastModified()
        val binDirectory = File(FileRootResolver.termuxPrivateRoot(activity), "usr/bin")
        val plan = try {
            LocalArchivePlanner.buildExtractPlan(
                archive = archiveFile,
                destination = destinationRoot,
                binDirectory = binDirectory,
                conflictSwitch = options.conflictStrategy.sevenZipSwitch
            )
        } catch (policy: ArchivePolicyException) {
            throw ArchiveWorkflowException(policy.message.orEmpty())
        }

        journalPhase(
            phase = "PREFLIGHT",
            message = "使用 ${plan.displayName} 校验 ${archiveFile.name}",
            backend = plan.backend.name,
            tempPath = destinationRoot.absolutePath
        )
        progress(
            "正在校验归档 ($archiveIndex/$archiveTotal)\n" +
                "工具：${plan.displayName}\n" +
                "当前：${archiveFile.name}\n" +
                "大小：${sourceBytes.coerceAtLeast(0L).formatSize()}"
        )
        runLocalArchiveCommand(
            command = plan.preflightCommand,
            workingDirectory = archiveFile.parentFile ?: destinationRoot,
            cancelled = cancelled,
            title = "正在校验归档 ($archiveIndex/$archiveTotal)",
            failureMessage = "原生归档完整性校验失败：${archiveFile.name}",
            progress = progress,
            emitOutputProgress = false
        )
        requireStableArchive(archiveFile, sourceBytes, sourceModifiedAt)

        journalPhase(
            phase = "EXTRACTING",
            message = "使用 ${plan.displayName} 解压 ${archiveFile.name}",
            backend = plan.backend.name,
            tempPath = destinationRoot.absolutePath
        )
        progress(
            "正在解压 ($archiveIndex/$archiveTotal)\n" +
                "工具：${plan.displayName}\n" +
                "当前：${archiveFile.name}\n" +
                "大小：${sourceBytes.coerceAtLeast(0L).formatSize()}"
        )
        runLocalArchiveCommand(
            command = plan.command,
            workingDirectory = archiveFile.parentFile ?: destinationRoot,
            cancelled = cancelled,
            title = "正在使用 ${plan.displayName} 解压 ($archiveIndex/$archiveTotal)",
            failureMessage = "${plan.displayName} 解压失败：${archiveFile.name}",
            progress = progress
        )
        requireStableArchive(archiveFile, sourceBytes, sourceModifiedAt)

        validateExtractedTree(destinationRoot)
        progress(
            "正在解压 ($archiveIndex/$archiveTotal)\n" +
                "当前：写入完成"
        )
    }

    private fun requireStableArchive(archiveFile: File, expectedBytes: Long, expectedModifiedAt: Long) {
        if (!archiveFile.isFile || archiveFile.length() != expectedBytes || archiveFile.lastModified() != expectedModifiedAt) {
            throw ArchiveWorkflowException("归档在处理期间发生变化，请重试：${archiveFile.name}")
        }
    }

    private fun requireLocalArchiveTool(): LocalArchiveTool {
        val binDir = File(FileRootResolver.termuxPrivateRoot(activity), "usr/bin")
        val candidates = listOf("7zz", "7z", "7za")
        candidates.forEach { command ->
            val binary = File(binDir, command)
            if (binary.exists() && binary.canExecute()) {
                return LocalArchiveTool(binary, command)
            }
        }
        throw MissingLocalArchiveToolException()
    }

    private fun runLocalArchiveCommand(
        command: List<String>,
        workingDirectory: File,
        cancelled: AtomicBoolean,
        title: String,
        failureMessage: String,
        progress: (String) -> Unit,
        emitOutputProgress: Boolean = true
    ) {
        throwIfCancelled(cancelled)
        val outputLines = Collections.synchronizedList(ArrayList<String>())
        val process = ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    val privateRoot = FileRootResolver.termuxPrivateRoot(activity)
                    val prefix = "$privateRoot/usr"
                    this["HOME"] = "$privateRoot/home"
                    this["PREFIX"] = prefix
                    this["TMPDIR"] = activity.cacheDir.absolutePath
                    this["PATH"] = "$prefix/bin:/system/bin:/system/xbin"
                    this["LANG"] = "C.UTF-8"
                    this["LC_ALL"] = "C.UTF-8"
                }
            }
            .start()

        val readerThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isNotEmpty()) {
                        synchronized(outputLines) {
                            outputLines.add(line)
                            while (outputLines.size > MAX_PROCESS_OUTPUT_LINES) {
                                outputLines.removeAt(0)
                            }
                        }
                        if (emitOutputProgress) {
                            progress("$title\n当前：${line.take(MAX_PROGRESS_LINE_LENGTH)}")
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        var exitCode: Int? = null
        try {
            while (exitCode == null) {
                if (cancelled.get()) {
                    terminateLocalProcess(process)
                    throw ArchiveCancelledException()
                }
                if (process.waitFor(PROCESS_POLL_MS, TimeUnit.MILLISECONDS)) {
                    exitCode = process.exitValue()
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            terminateLocalProcess(process)
            throw ArchiveCancelledException()
        } finally {
            runCatching { readerThread.join(PROCESS_READER_JOIN_MS) }
        }

        if (exitCode != 0) {
            val detail = synchronized(outputLines) {
                outputLines.takeLast(8).joinToString("\n")
            }
            throw ArchiveWorkflowException(
                if (detail.isBlank()) {
                    "$failureMessage 退出码：$exitCode"
                } else {
                    "$failureMessage\n退出码：$exitCode\n$detail"
                }
            )
        }
    }

    private fun terminateLocalProcess(process: Process) {
        process.destroy()
        val exited = runCatching {
            process.waitFor(PROCESS_TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!exited) {
            process.destroyForcibly()
            runCatching { process.waitFor(PROCESS_TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS) }
        }
    }

    private fun validateExtractedTree(root: File) {
        val rootCanonical = root.canonicalFile.absolutePath.trimEnd(File.separatorChar)
        val trustedRoots = trustedTermuxArchiveLinkRoots()
        val metadata = arrayListOf<ArchiveEntryMetadata>()
        fun walk(file: File) {
            val mode = try {
                Os.lstat(file.absolutePath).st_mode
            } catch (throwable: Throwable) {
                throw ArchiveWorkflowException("无法检查解压条目：${file.absolutePath} (${throwable.message.orEmpty()})")
            }
            if (OsConstants.S_ISLNK(mode)) {
                val target = try {
                    Os.readlink(file.absolutePath)
                } catch (throwable: Throwable) {
                    throw ArchiveWorkflowException("无法读取符号链接：${file.absolutePath} (${throwable.message.orEmpty()})")
                }
                if (!isAllowedExtractedLink(file, target, rootCanonical, trustedRoots)) {
                    val relative = file.absolutePath.removePrefix(rootCanonical).trimStart(File.separatorChar)
                    throw ArchiveWorkflowException("压缩包包含未受信任符号链接：$relative -> $target")
                }
                metadata.add(
                    ArchiveEntryMetadata(
                        rawPath = relativeExtractedPath(file, rootCanonical),
                        kind = ArchiveEntryKind.SYMBOLIC_LINK,
                        linkTarget = target
                    )
                )
                return
            }

            val canonical = file.canonicalFile.absolutePath
            if (!isPathWithin(canonical, rootCanonical)) {
                throw ArchiveWorkflowException("压缩包包含不安全路径：${file.name}")
            }
            if (OsConstants.S_ISDIR(mode)) {
                metadata.add(
                    ArchiveEntryMetadata(
                        rawPath = relativeExtractedPath(file, rootCanonical),
                        kind = ArchiveEntryKind.DIRECTORY
                    )
                )
                val children = file.listFiles()
                    ?: throw ArchiveWorkflowException("无法读取解压目录：${file.absolutePath}")
                children.forEach(::walk)
            } else if (!OsConstants.S_ISREG(mode)) {
                throw ArchiveWorkflowException("压缩包包含不支持的特殊文件：${file.absolutePath}")
            } else {
                metadata.add(
                    ArchiveEntryMetadata(
                        rawPath = relativeExtractedPath(file, rootCanonical),
                        kind = ArchiveEntryKind.FILE,
                        size = file.length().coerceAtLeast(0L)
                    )
                )
            }
        }
        walk(root)
        try {
            ArchivePathPolicy.validate(metadata, trustedRoots)
        } catch (policy: ArchivePolicyException) {
            throw ArchiveWorkflowException(policy.message.orEmpty())
        }
    }

    private fun relativeExtractedPath(file: File, extractionRoot: String): String {
        return file.absolutePath.removePrefix(extractionRoot).trimStart(File.separatorChar)
    }

    private fun isAllowedExtractedLink(
        link: File,
        target: String,
        extractionRoot: String,
        trustedRoots: List<String>
    ): Boolean {
        if (target.isBlank() || target.indexOf('\u0000') >= 0) return false
        val resolved = runCatching {
            if (target.startsWith(File.separator)) File(target).canonicalFile
            else File(link.parentFile, target).canonicalFile
        }.getOrNull() ?: return false
        val resolvedPath = resolved.absolutePath
        if (isPathWithin(resolvedPath, extractionRoot)) return true

        if (target.startsWith(File.separator) &&
            !ArchivePathPolicy.isTrustedAbsoluteLink(target, trustedRoots)
        ) {
            return false
        }
        if (trustedRoots.none { isPathWithin(resolvedPath, it) }) return false

        // Portable bundles may intentionally reference an optional Termux package that is not
        // installed yet. The trusted prefix boundary is the security decision; existence is not.
        return true
    }

    private fun trustedTermuxArchiveLinkRoots(): List<String> {
        val privateRoot = FileRootResolver.termuxPrivateRoot(activity)
            .replace('\\', '/')
            .trimEnd('/')
        val roots = linkedSetOf("$privateRoot/usr")
        when {
            privateRoot.startsWith("/data/user/0/com.termux/files") ->
                roots.add("/data/data/com.termux/files/usr")
            privateRoot.startsWith("/data/data/com.termux/files") ->
                roots.add("/data/user/0/com.termux/files/usr")
        }
        return roots.toList()
    }

    private fun isPathWithin(path: String, root: String): Boolean {
        val normalizedRoot = root.trimEnd(File.separatorChar)
        return path == normalizedRoot || path.startsWith("$normalizedRoot${File.separator}")
    }

    private fun commitArtifact(
        stagedArtifact: File,
        destinationDirectory: String,
        finalName: String,
        directory: Boolean,
        conflictStrategy: DecompressConflictStrategy? = null,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): String {
        throwIfCancelled(cancelled)
        return if (sessionFileCoordinator.isVirtualPath(activity, destinationDirectory)) {
            commitRemoteArtifact(stagedArtifact, destinationDirectory, finalName, conflictStrategy, cancelled, progress)
        } else {
            commitLocalArtifact(stagedArtifact, destinationDirectory, finalName, directory, conflictStrategy, cancelled, progress)
        }
    }

    private fun commitLocalArtifact(
        stagedArtifact: File,
        destinationDirectory: String,
        finalName: String,
        directory: Boolean,
        conflictStrategy: DecompressConflictStrategy?,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): String {
        val targetDirectory = File(destinationDirectory)
        ensureDirectory(targetDirectory)
        val finalTarget = File(targetDirectory, finalName)
        if (finalTarget.exists()) {
            when (conflictStrategy) {
                DecompressConflictStrategy.OVERWRITE -> Unit
                DecompressConflictStrategy.SKIP_EXISTING -> throw ArchiveWorkflowException("目标已存在，已按跳过策略停止：${finalTarget.absolutePath}")
                else -> throw ArchiveWorkflowException("目标已存在：${finalTarget.absolutePath}")
            }
        }

        val tempTarget = File(
            targetDirectory,
            ".${finalName}.termux-archive-${activeOperation.get()?.id ?: System.currentTimeMillis()}.part"
        )
        if (tempTarget.exists()) {
            deleteRecursivelySafe(tempTarget)
        }

        try {
            journalPhase(
                phase = "COMMITTING",
                message = "提交本地归档结果：$finalName",
                tempPath = tempTarget.absolutePath
            )
            progress("正在提交到本地目录...")
            if (stagedArtifact.renameTo(tempTarget)) {
                progress("正在提交到本地目录\n当前：$finalName\n方式：同文件系统原子搬移")
            } else {
                val totalBytes = fileTreeSize(stagedArtifact)
                var copiedBytes = 0L
                copyFileTree(stagedArtifact, tempTarget, directory, cancelled) { copied ->
                    copiedBytes += copied
                    progress(
                        "正在提交到本地目录\n" +
                            "当前：$finalName\n" +
                            "大小：${copiedBytes.formatSize()} / ${if (totalBytes > 0L) totalBytes.formatSize() else "?"}"
                    )
                }
            }
            syncDirectory(targetDirectory)

            throwIfCancelled(cancelled)
            if (finalTarget.exists()) {
                when (conflictStrategy) {
                    DecompressConflictStrategy.OVERWRITE -> deleteRecursivelySafe(finalTarget)
                    DecompressConflictStrategy.SKIP_EXISTING -> throw ArchiveWorkflowException("目标已存在，已按跳过策略停止：${finalTarget.absolutePath}")
                    else -> throw ArchiveWorkflowException("目标已存在：${finalTarget.absolutePath}")
                }
            }
            if (!tempTarget.renameTo(finalTarget)) {
                throw ArchiveWorkflowException("无法原子提交本地目标：${finalTarget.absolutePath}")
            }
            syncDirectory(targetDirectory)
            return finalTarget.absolutePath.replace('\\', '/')
        } catch (throwable: Throwable) {
            deleteRecursivelySafe(tempTarget)
            throw throwable
        }
    }

    private fun commitRemoteArtifact(
        stagedArtifact: File,
        destinationVirtualDirectory: String,
        finalName: String,
        conflictStrategy: DecompressConflictStrategy?,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): String {
        throwIfCancelled(cancelled)
        val tempLocalName = ".${finalName}.termux-archive-${System.currentTimeMillis()}.part"
        val tempLocalArtifact = File(stagedArtifact.parentFile, tempLocalName)
        if (tempLocalArtifact.exists()) {
            deleteRecursivelySafe(tempLocalArtifact)
        }
        if (!stagedArtifact.renameTo(tempLocalArtifact)) {
            copyFileTree(stagedArtifact, tempLocalArtifact, stagedArtifact.isDirectory, cancelled) {}
            deleteRecursivelySafe(stagedArtifact)
        }

        var uploadedTempVirtualPath = ""
        try {
            progress("正在上传到服务器目标...")
            val uploadResult = sessionFileCoordinator.uploadLocalPathsToVirtual(
                activity,
                listOf(tempLocalArtifact.absolutePath),
                destinationVirtualDirectory,
                object : SftpProtocolManager.UploadProgressListener {
                    override fun onProgress(progressState: SftpProtocolManager.UploadProgress) {
                        val current = progressState.currentFile.ifBlank { "准备中..." }
                        val totalBytes = progressState.totalBytes
                        val transferred = progressState.transferredBytes
                        val sizeText = if (totalBytes > 0L) {
                            "${transferred.formatSize()} / ${totalBytes.formatSize()}"
                        } else {
                            "${transferred.formatSize()} / ?"
                        }
                        progress(
                            "正在上传到服务器目标\n" +
                                "当前：$current\n" +
                                "进度：${progressState.completedFiles + progressState.failedFiles}/${progressState.totalFiles}\n" +
                                "大小：$sizeText"
                        )
                    }
                },
                object : SftpProtocolManager.UploadControl {
                    override fun isCancelled(): Boolean = cancelled.get()
                }
            )

            uploadedTempVirtualPath = uploadResult.uploadedVirtualPaths.firstOrNull().orEmpty()
            if (!uploadResult.success) {
                if (cancelled.get() || isCancelledMessage(uploadResult.messageCn)) {
                    throw ArchiveCancelledException()
                }
                throw ArchiveWorkflowException(uploadResult.messageCn.ifBlank { "上传到服务器目标失败。" })
            }
            if (uploadedTempVirtualPath.isBlank()) {
                throw ArchiveWorkflowException("上传完成但未返回服务器目标路径。")
            }

            throwIfCancelled(cancelled)
            progress("正在提交服务器目标...")
            val finalVirtualPath = remoteVirtualChildPath(destinationVirtualDirectory, finalName)
            if (remoteTargetExists(destinationVirtualDirectory, finalName)) {
                when (conflictStrategy) {
                    DecompressConflictStrategy.OVERWRITE -> sessionFileCoordinator.deleteVirtualPath(activity, finalVirtualPath)
                    DecompressConflictStrategy.SKIP_EXISTING -> throw ArchiveWorkflowException("服务器目标已存在，已按跳过策略停止：$finalName")
                    else -> throw ArchiveWorkflowException("服务器目标已存在：$finalName")
                }
            }
            val renameResult = sessionFileCoordinator.renameVirtualPath(
                activity,
                uploadedTempVirtualPath,
                finalName
            )
            if (!renameResult.success) {
                throw ArchiveWorkflowException(renameResult.messageCn.ifBlank { "提交服务器目标失败。" })
            }
            return renameResult.virtualPath
        } catch (throwable: Throwable) {
            if (uploadedTempVirtualPath.isNotBlank()) {
                sessionFileCoordinator.deleteVirtualPath(activity, uploadedTempVirtualPath)
            }
            throw throwable
        }
    }

    private fun copyLocalPathToStage(
        sourcePath: String,
        destination: File,
        directory: Boolean,
        cancelled: AtomicBoolean,
        onBytes: (Long) -> Unit
    ) {
        throwIfCancelled(cancelled)
        val sourceFile = File(sourcePath)
        if (sourceFile.exists() && isSymbolicLink(sourceFile)) {
            copySymbolicLink(sourceFile, destination)
            return
        }
        if (directory) {
            ensureDirectory(destination)
            if (activity.isRestrictedSAFOnlyRoot(sourcePath)) {
                activity.getAndroidSAFFileItems(sourcePath, true) { children ->
                    children.forEach { child ->
                        val childDestination = File(destination, child.name.ifBlank { child.path.getFilenameFromPath() })
                        copyLocalPathToStage(
                            sourcePath = child.path,
                            destination = childDestination,
                            directory = child.isDirectory,
                            cancelled = cancelled,
                            onBytes = onBytes
                        )
                    }
                }
                return
            }

            val children = sourceFile.listFiles()
                ?: throw ArchiveWorkflowException("无法读取目录：$sourcePath")
            children.sortedBy { it.name.lowercase(Locale.getDefault()) }.forEach { child ->
                copyLocalPathToStage(
                    sourcePath = child.absolutePath,
                    destination = File(destination, child.name),
                    directory = child.isDirectory,
                    cancelled = cancelled,
                    onBytes = onBytes
                )
            }
            return
        }

        destination.parentFile?.let(::ensureDirectory)
        if (sourceFile.isFile && tryCreateHardLink(sourceFile, destination)) {
            onBytes(sourceFile.length().coerceAtLeast(0L))
            return
        }
        val input = activity.getFileInputStreamSync(sourcePath)
            ?: throw ArchiveWorkflowException("无法读取文件：$sourcePath")
        input.use { inputStream ->
            FileOutputStream(destination).use { outputStream ->
                copyStream(inputStream, outputStream, cancelled, onBytes)
                outputStream.fd.sync()
            }
        }
        if (sourceFile.exists()) {
            destination.setLastModified(sourceFile.lastModified())
        }
    }

    private fun copyFileTree(
        source: File,
        target: File,
        directory: Boolean,
        cancelled: AtomicBoolean,
        onBytes: (Long) -> Unit
    ) {
        throwIfCancelled(cancelled)
        if (isSymbolicLink(source)) {
            copySymbolicLink(source, target)
            return
        }
        if (directory || source.isDirectory) {
            ensureDirectory(target)
            val children = source.listFiles()
                ?: throw ArchiveWorkflowException("无法读取目录：${source.absolutePath}")
            children.sortedBy { it.name.lowercase(Locale.getDefault()) }.forEach { child ->
                copyFileTree(child, File(target, child.name), child.isDirectory, cancelled, onBytes)
            }
            target.setLastModified(source.lastModified())
            return
        }

        target.parentFile?.let(::ensureDirectory)
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                copyStream(input, output, cancelled, onBytes)
                output.fd.sync()
            }
        }
        target.setLastModified(source.lastModified())
    }

    private fun copyStream(
        input: InputStream,
        output: OutputStream,
        cancelled: AtomicBoolean,
        onBytes: (Long) -> Unit
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            throwIfCancelled(cancelled)
            val read = input.read(buffer)
            if (read < 0) {
                return
            }
            if (read == 0) {
                continue
            }
            output.write(buffer, 0, read)
            onBytes(read.toLong())
        }
    }

    private fun resolveUniqueTargetName(
        destinationDirectory: String,
        requestedName: String,
        directory: Boolean
    ): String {
        val baseName = sanitizeFileName(requestedName).ifBlank { if (directory) "extracted" else "archive.7z" }
        return if (sessionFileCoordinator.isVirtualPath(activity, destinationDirectory)) {
            resolveUniqueRemoteName(destinationDirectory, baseName)
        } else {
            resolveUniqueLocalName(destinationDirectory, baseName)
        }
    }

    private fun resolveUniqueLocalName(destinationDirectory: String, requestedName: String): String {
        var candidate = requestedName
        var index = 1
        while (File(destinationDirectory, candidate).exists()) {
            candidate = appendNumberSuffix(requestedName, index++)
        }
        return candidate
    }

    private fun resolveUniqueRemoteName(destinationDirectory: String, requestedName: String): String {
        val result = sessionFileCoordinator.listVirtualPath(activity, destinationDirectory)
        if (!result.success) {
            throw ArchiveWorkflowException(result.messageCn.ifBlank { "无法读取服务器目标目录。" })
        }
        val usedNames = result.entries.mapTo(HashSet()) { it.name }
        var candidate = requestedName
        var index = 1
        while (usedNames.contains(candidate)) {
            candidate = appendNumberSuffix(requestedName, index++)
        }
        return candidate
    }

    private fun resolveUniqueLocalChild(
        parent: File,
        preferredName: String,
        directory: Boolean,
        reservedNames: MutableSet<String>
    ): File {
        val cleanName = sanitizeFileName(preferredName).ifBlank { if (directory) "folder" else "file" }
        var candidate = cleanName
        var index = 1
        while (reservedNames.contains(candidate) || File(parent, candidate).exists()) {
            candidate = appendNumberSuffix(cleanName, index++)
        }
        reservedNames.add(candidate)
        return File(parent, candidate)
    }

    private fun appendNumberSuffix(name: String, index: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) {
            "${name.substring(0, dot)} ($index)${name.substring(dot)}"
        } else {
            "$name ($index)"
        }
    }

    private fun buildArchiveFileName(items: List<FileDirItem>, options: CompressionOptions): String {
        val base = if (items.size == 1) {
            val item = items.first()
            val name = item.name.ifBlank { item.path.getFilenameFromPath() }.ifBlank { "archive" }
            if (!item.isDirectory && name.contains('.')) {
                name.substringBeforeLast('.').ifBlank { name }
            } else {
                name
            }
        } else {
            val parentName = items.first().path.getParentPath().getFilenameFromPath()
            parentName.ifBlank { "archive" }
        }
        val requestedName = sanitizeFileName(options.archiveName).ifBlank { base }
        val cleanBase = stripKnownArchiveSuffix(sanitizeFileName(requestedName)).ifBlank { "archive" }
        return "$cleanBase${options.format.suffix}"
    }

    private fun buildMultiExtractFolderName(archiveFiles: List<File>): String {
        val first = archiveFiles.firstOrNull()?.name?.let(::baseNameWithoutArchive).orEmpty()
        return sanitizeFileName(first).ifBlank { "extracted" }
    }

    private fun buildExtractFolderName(archiveFiles: List<File>, requestedOutputName: String): String {
        val fallback = if (archiveFiles.size == 1) {
            baseNameWithoutArchive(archiveFiles.first().name)
        } else {
            buildMultiExtractFolderName(archiveFiles)
        }
        return sanitizeFileName(requestedOutputName).ifBlank { fallback }.ifBlank { "extracted" }
    }

    private fun resolveDecompressTargetName(
        destinationDirectory: String,
        requestedName: String,
        options: DecompressOptions
    ): String {
        val cleanName = sanitizeFileName(requestedName).ifBlank { "extracted" }
        return when (options.conflictStrategy) {
            DecompressConflictStrategy.AUTO_RENAME -> resolveUniqueTargetName(destinationDirectory, cleanName, directory = true)
            DecompressConflictStrategy.OVERWRITE -> cleanName
            DecompressConflictStrategy.SKIP_EXISTING -> {
                if (targetNameExists(destinationDirectory, cleanName)) {
                    throw ArchiveWorkflowException("目标已存在，已按跳过策略停止：$cleanName")
                }
                cleanName
            }
        }
    }

    private fun baseNameWithoutArchive(name: String): String {
        val cleanName = sanitizeFileName(name)
        val kind = ArchiveKind.fromName(cleanName)
        if (kind != null) {
            val suffix = kind.suffixes
                .filter { cleanName.endsWith(it, ignoreCase = true) }
                .maxByOrNull { it.length }
                .orEmpty()
            if (suffix.isNotBlank()) {
                return cleanName.dropLast(suffix.length).ifBlank { "extracted" }
            }
        }
        val localSuffix = LOCAL_7ZIP_ARCHIVE_SUFFIXES
            .filter { cleanName.endsWith(it, ignoreCase = true) }
            .maxByOrNull { it.length }
            .orEmpty()
        if (localSuffix.isNotBlank()) {
            return cleanName.dropLast(localSuffix.length).ifBlank { "extracted" }
        }
        return sanitizeFileName(
            if (cleanName.endsWith(".zip", ignoreCase = true)) cleanName.dropLast(4) else cleanName
        ).ifBlank { "extracted" }
    }

    private fun stripKnownArchiveSuffix(name: String): String {
        val suffix = LOCAL_7ZIP_ARCHIVE_SUFFIXES
            .filter { name.endsWith(it, ignoreCase = true) }
            .maxByOrNull { it.length }
            .orEmpty()
        return if (suffix.isBlank()) name else name.dropLast(suffix.length)
    }

    private fun remoteArchiveKindFor(format: CompressionFormat): ArchiveKind {
        return when (format) {
            CompressionFormat.SEVEN_Z -> ArchiveKind.SEVEN_Z
            CompressionFormat.ZIP -> ArchiveKind.ZIP
            CompressionFormat.TAR -> ArchiveKind.TAR
            CompressionFormat.TAR_GZ -> ArchiveKind.TAR_GZ
            CompressionFormat.TAR_BZ2 -> ArchiveKind.TAR_BZ2
            CompressionFormat.TAR_XZ -> ArchiveKind.TAR_XZ
            CompressionFormat.TAR_ZST -> ArchiveKind.TAR_ZST
        }
    }

    private fun requireSameRemoteParent(items: List<RemoteArchiveItem>): String {
        if (items.isEmpty()) {
            throw ArchiveWorkflowException("没有可压缩的服务器项目。")
        }
        val parentRemote = commonRemoteParent(items.map { it.remotePath })
        if (items.any { remoteParentPath(it.remotePath) != parentRemote }) {
            throw ArchiveWorkflowException("服务器本地压缩当前要求选中项目位于同一目录。")
        }
        return parentRemote
    }

    private fun buildRemoteTempArchiveName(finalName: String): String {
        val safeName = sanitizeFileName(finalName).ifBlank { "archive" }
        return ".$safeName.termux-archive-${System.currentTimeMillis()}-${UUID.randomUUID()}.part"
    }

    private fun targetNameExists(destinationDirectory: String, name: String): Boolean {
        return if (sessionFileCoordinator.isVirtualPath(activity, destinationDirectory)) {
            remoteTargetExists(destinationDirectory, name)
        } else {
            File(destinationDirectory, name).exists()
        }
    }

    private fun remoteTargetExists(destinationVirtualDirectory: String, name: String): Boolean {
        val result = sessionFileCoordinator.listVirtualPath(activity, destinationVirtualDirectory)
        if (!result.success) {
            throw ArchiveWorkflowException(result.messageCn.ifBlank { "无法读取服务器目标目录。" })
        }
        return result.entries.any { it.name == name }
    }

    private fun remoteVirtualChildPath(destinationVirtualDirectory: String, name: String): String {
        val parent = destinationVirtualDirectory.trimEnd('/')
        return if (parent.isBlank()) name else "$parent/$name"
    }

    private fun commonRemoteParent(remotePaths: List<String>): String {
        if (remotePaths.isEmpty()) return "/"
        val parents = remotePaths.map(::remoteParentPath)
        val firstParts = parents.first().trim('/').split('/').filter { it.isNotBlank() }
        if (firstParts.isEmpty()) return "/"
        var commonCount = firstParts.size
        parents.drop(1).forEach { parent ->
            val parts = parent.trim('/').split('/').filter { it.isNotBlank() }
            var index = 0
            while (index < commonCount && index < parts.size && firstParts[index] == parts[index]) {
                index++
            }
            commonCount = index
        }
        if (commonCount <= 0) return "/"
        return "/" + firstParts.take(commonCount).joinToString("/")
    }

    private fun remoteParentPath(remotePath: String): String {
        val normalized = normalizeRemotePath(remotePath)
        if (normalized == "/") return "/"
        val slash = normalized.lastIndexOf('/')
        return if (slash <= 0) "/" else normalized.substring(0, slash)
    }

    private fun remoteBaseName(remotePath: String): String {
        val normalized = normalizeRemotePath(remotePath)
        return normalized.substringAfterLast('/').ifBlank { "item" }
    }

    private fun joinRemotePath(parent: String, child: String): String {
        val cleanParent = normalizeRemotePath(parent)
        val cleanChild = child.replace('\\', '/').trim('/')
        if (cleanChild.isBlank()) return cleanParent
        return if (cleanParent == "/") "/$cleanChild" else "$cleanParent/$cleanChild"
    }

    private fun normalizeRemotePath(path: String): String {
        val parts = ArrayDeque<String>()
        path.replace('\\', '/').split('/').forEach { raw ->
            val part = raw.trim()
            when {
                part.isBlank() || part == "." -> Unit
                part == ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
    }

    private fun shellQuote(raw: String): String {
        return "'" + raw.replace("'", "'\"'\"'") + "'"
    }

    private fun remoteEcho(message: String): String {
        return "printf '%s\\n' ${shellQuote(message)}"
    }

    private fun sanitizeFileName(raw: String): String {
        val candidate = raw.replace('\\', '/').substringAfterLast('/').trim()
        val sanitized = StringBuilder(candidate.length)
        candidate.forEach { char ->
            val invalid = char.code < 32 || char == '/' || char == '\\' || char == ':' ||
                char == '*' || char == '?' || char == '"' || char == '<' || char == '>' || char == '|'
            sanitized.append(if (invalid) '_' else char)
        }
        return sanitized.toString().trim().trimEnd('.').ifBlank { "" }
    }

    private fun safeLocalSize(item: FileDirItem): Long {
        return try {
            max(0L, item.getProperSize(activity, true))
        } catch (_: Throwable) {
            max(0L, item.size)
        }
    }

    private fun fileTreeSize(file: File): Long {
        if (isSymbolicLink(file)) return 0L
        if (file.isFile) {
            return max(0L, file.length())
        }
        return file.listFiles()?.sumOf { fileTreeSize(it) } ?: 0L
    }

    private fun createJobDirectory(): File {
        val primaryRoot = File(FileRootResolver.resolveTransferRoot(activity), "archive-jobs")
        val root = if (primaryRoot.exists() || primaryRoot.mkdirs()) {
            primaryRoot
        } else {
            File(activity.cacheDir, "archive-jobs").also(::ensureDirectory)
        }
        val jobDir = File(root, "job-${System.currentTimeMillis()}-${UUID.randomUUID()}")
        ensureDirectory(jobDir)
        return jobDir
    }

    private fun ensureDirectory(directory: File) {
        if (directory.exists()) {
            if (!directory.isDirectory) {
                throw ArchiveWorkflowException("路径不是目录：${directory.absolutePath}")
            }
            return
        }
        if (!directory.mkdirs() && !directory.exists()) {
            throw ArchiveWorkflowException("无法创建目录：${directory.absolutePath}")
        }
    }

    private fun normalizeDirectoryPath(path: String): String {
        val normalized = path.replace('\\', '/').trimEnd('/')
        return normalized.ifBlank { "/" }
    }

    private fun throwIfCancelled(cancelled: AtomicBoolean) {
        if (cancelled.get()) {
            throw ArchiveCancelledException()
        }
    }

    private fun isCancelledMessage(message: String): Boolean {
        return message.contains("已取消")
    }

    private fun deleteRecursivelySafe(file: File?) {
        if (file == null || (!file.exists() && !isSymbolicLink(file))) {
            return
        }
        try {
            if (!isSymbolicLink(file) && file.isDirectory) {
                file.listFiles()?.forEach { deleteRecursivelySafe(it) }
            }
            file.delete()
        } catch (_: Throwable) {
        }
    }

    private fun tryCreateHardLink(source: File, destination: File): Boolean {
        if (!source.isFile || isSymbolicLink(source)) return false
        return runCatching {
            if (destination.exists() || isSymbolicLink(destination)) deleteRecursivelySafe(destination)
            Os.link(source.absolutePath, destination.absolutePath)
            true
        }.getOrDefault(false)
    }

    private fun copySymbolicLink(source: File, destination: File) {
        val target = try {
            Os.readlink(source.absolutePath)
        } catch (throwable: Throwable) {
            throw ArchiveWorkflowException("无法读取符号链接：${source.absolutePath} (${throwable.message.orEmpty()})")
        }
        destination.parentFile?.let(::ensureDirectory)
        if (destination.exists() || isSymbolicLink(destination)) deleteRecursivelySafe(destination)
        try {
            Os.symlink(target, destination.absolutePath)
        } catch (throwable: Throwable) {
            throw ArchiveWorkflowException("无法创建符号链接：${destination.absolutePath} (${throwable.message.orEmpty()})")
        }
    }

    private fun isSymbolicLink(file: File): Boolean {
        return runCatching { OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode) }.getOrDefault(false)
    }

    private fun syncDirectory(directory: File) {
        runCatching {
            val descriptor = Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY,
                0
            )
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 128 * 1024
        private const val PROCESS_POLL_MS = 250L
        private const val PROCESS_READER_JOIN_MS = 1_000L
        private const val PROCESS_TERMINATION_GRACE_MS = 2_000L
        private const val MAX_PROCESS_OUTPUT_LINES = 40
        private const val MAX_PROGRESS_LINE_LENGTH = 160
        const val TERMUX_7ZIP_INSTALL_COMMAND = "pkg update && (pkg install 7zip || pkg install p7zip)"
        private val REMOTE_7ZIP_TOOL_CANDIDATES = listOf("7zz", "7z", "7za")
        private val REMOTE_TAR_TOOL_CANDIDATES = listOf("tar", "gtar", "bsdtar")
        private val REMOTE_TOOL_PROBE_TOOLS = listOf(
            "7zz", "7z", "7za", "zip", "unzip", "tar", "gtar", "bsdtar",
            "gzip", "bzip2", "xz", "zstd", "rar", "unrar"
        )
        private val REMOTE_CREATE_FORMAT_PREFERENCE = listOf(
            ArchiveKind.TAR_GZ,
            ArchiveKind.ZIP,
            ArchiveKind.SEVEN_Z,
            ArchiveKind.TAR_XZ,
            ArchiveKind.TAR_ZST,
            ArchiveKind.TAR_BZ2,
            ArchiveKind.TAR
        )
        private val REMOTE_DIRECTORY_ARCHIVE_KINDS = listOf(
            ArchiveKind.SEVEN_Z,
            ArchiveKind.ZIP,
            ArchiveKind.TAR,
            ArchiveKind.TAR_GZ,
            ArchiveKind.TAR_BZ2,
            ArchiveKind.TAR_XZ,
            ArchiveKind.TAR_ZST
        )
        private val LOCAL_7ZIP_CREATE_FORMATS = setOf(
            CompressionFormat.SEVEN_Z,
            CompressionFormat.ZIP,
            CompressionFormat.TAR
        )
        private val LOCAL_7ZIP_ARCHIVE_SUFFIXES = listOf(
            ".tar.gz", ".tgz", ".tar.bz2", ".tbz", ".tbz2", ".tar.xz", ".txz", ".tar.zst", ".tzst",
            ".zip", ".jar", ".apk", ".aar", ".war", ".tar", ".gz", ".bz2", ".xz", ".zst", ".7z", ".rar",
            ".001", ".cab", ".iso", ".img", ".dmg", ".wim", ".swm", ".esd", ".ar", ".deb", ".rpm", ".cpio",
            ".lzma", ".lz4", ".br", ".z", ".lzh", ".lha", ".chm", ".msi", ".nsis", ".udf", ".vhd", ".vhdx",
            ".vmdk", ".qcow", ".qcow2", ".squashfs", ".crx", ".xar"
        )
    }
}
