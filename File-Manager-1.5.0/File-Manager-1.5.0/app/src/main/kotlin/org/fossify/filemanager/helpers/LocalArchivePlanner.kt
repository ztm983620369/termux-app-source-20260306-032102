package org.fossify.filemanager.helpers

import java.io.File
import java.util.Locale

internal enum class LocalArchiveBackend {
    GNU_TAR,
    SEVEN_ZIP
}

internal data class LocalExtractPlan(
    val backend: LocalArchiveBackend,
    val displayName: String,
    val preflightCommand: List<String>,
    val command: List<String>,
    val nativeTool: File
)

internal object LocalArchivePlanner {
    fun buildExtractPlan(
        archive: File,
        destination: File,
        binDirectory: File,
        conflictSwitch: String
    ): LocalExtractPlan {
        val tarCompressionOption = tarCompressionOption(archive.name)
        val tar = File(binDirectory, "tar")
        if (tarCompressionOption != null && tar.isFile && tar.canExecute()) {
            val preflightCommand = buildTarCommand(
                tar = tar,
                operation = "--list",
                compressionOption = tarCompressionOption,
                archive = archive,
                destination = null
            )
            val command = buildTarCommand(
                tar = tar,
                operation = "--extract",
                compressionOption = tarCompressionOption,
                archive = archive,
                destination = destination
            )
            return LocalExtractPlan(
                backend = LocalArchiveBackend.GNU_TAR,
                displayName = "tar",
                preflightCommand = preflightCommand,
                command = command,
                nativeTool = tar
            )
        }

        val sevenZip = listOf("7zz", "7z", "7za")
            .map { File(binDirectory, it) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?: throw ArchivePolicyException("本地缺少可用的归档工具：${archive.name}")
        return LocalExtractPlan(
            backend = LocalArchiveBackend.SEVEN_ZIP,
            displayName = sevenZip.name,
            preflightCommand = listOf(
                sevenZip.absolutePath,
                "t",
                "-bb1",
                "-bd",
                archive.absolutePath
            ),
            command = listOf(
                sevenZip.absolutePath,
                "x",
                "-y",
                conflictSwitch,
                "-bb1",
                "-bd",
                "-o${destination.absolutePath}",
                archive.absolutePath
            ),
            nativeTool = sevenZip
        )
    }

    private fun buildTarCommand(
        tar: File,
        operation: String,
        compressionOption: String,
        archive: File,
        destination: File?
    ): List<String> {
        val command = arrayListOf(tar.absolutePath, operation)
        if (compressionOption.isNotEmpty()) command.add(compressionOption)
        command.addAll(listOf("--file", archive.absolutePath))
        if (destination != null) {
            command.addAll(
                listOf(
                    "--directory", destination.absolutePath,
                    "--no-same-owner",
                    "--no-same-permissions",
                    "--delay-directory-restore"
                )
            )
        }
        command.add("--warning=no-unknown-keyword")
        return command
    }

    internal fun tarCompressionOption(fileName: String): String? {
        val lower = fileName.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> "--gzip"
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz") || lower.endsWith(".tbz2") -> "--bzip2"
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> "--xz"
            lower.endsWith(".tar.zst") || lower.endsWith(".tzst") -> "--zstd"
            lower.endsWith(".tar") -> ""
            else -> null
        }
    }
}
