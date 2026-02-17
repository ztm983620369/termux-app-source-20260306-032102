package com.termux.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.shell.command.runner.app.AppShell
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
fun FileActionsDialogHost(
    target: FileEntry?,
    currentDir: File,
    onDismissRequest: () -> Unit,
    onRequestReload: () -> Unit,
    onRevealPath: (String) -> Unit
) {
    if (target == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(false) }
    var runningTitle by remember { mutableStateOf<String?>(null) }
    var runningDetail by remember { mutableStateOf<String?>(null) }
    var runningProgress by remember { mutableStateOf<ProgressSnapshot?>(null) }
    var resultDialog by remember { mutableStateOf<String?>(null) }

    var renameDialogVisible by remember { mutableStateOf(false) }
    var renameValue by remember(target.path) { mutableStateOf(target.name) }

    var copyDialogVisible by remember { mutableStateOf(false) }
    var copyDestDir by remember(target.path, currentDir.absolutePath) { mutableStateOf(currentDir.absolutePath) }

    var cutDialogVisible by remember { mutableStateOf(false) }
    var cutDestDir by remember(target.path, currentDir.absolutePath) { mutableStateOf(currentDir.absolutePath) }

    var compressDialogVisible by remember { mutableStateOf(false) }
    var compressArchivePath by remember(target.path) {
        val parent = File(target.path).parentFile
        val base = File(target.path).name
        mutableStateOf(File(parent ?: currentDir, "$base.tar.gz").absolutePath)
    }

    var extractDialogVisible by remember { mutableStateOf(false) }
    var extractDestDir by remember(target.path, currentDir.absolutePath) {
        val base = File(target.path).nameWithoutExtension
        mutableStateOf(File(currentDir, base).absolutePath)
    }

    var deleteDialogVisible by remember { mutableStateOf(false) }
    var statsDialogVisible by remember { mutableStateOf(false) }
    var statsRunning by remember { mutableStateOf(false) }
    var statsText by remember { mutableStateOf<String?>(null) }

    fun launchOperation(
        title: String? = null,
        revealPathOnSuccess: String? = null,
        block: suspend () -> TermuxToolResult
    ) {
        if (running) return
        running = true
        runningTitle = title
        runningDetail = null
        runningProgress = null
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
                .getOrElse { throwable ->
                    TermuxToolResult(
                        executable = "",
                        args = emptyList(),
                        workingDirectory = "",
                        exitCode = 1,
                        stdout = "",
                        stderr = throwable.stackTraceToString()
                    )
                }
            if (result.exitCode == 0 && revealPathOnSuccess != null) {
                onRevealPath(revealPathOnSuccess)
            }
            resultDialog = result.toDisplayString()
            running = false
            runningTitle = null
            runningDetail = null
            runningProgress = null
            onRequestReload()
        }
    }

    fun launchOperationWithProgress(
        title: String,
        totalUnits: Int?,
        revealPathOnSuccess: String? = null,
        block: suspend (onProgress: (ProgressSnapshot) -> Unit) -> TermuxToolResult
    ) {
        if (running) return
        running = true
        runningTitle = title
        runningDetail = null
        runningProgress = ProgressSnapshot(done = 0, total = totalUnits, lastItem = null)
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    block { snapshot ->
                        scope.launch {
                            runningProgress = snapshot
                            runningDetail = snapshot.lastItem
                        }
                    }
                }
            }.getOrElse { throwable ->
                TermuxToolResult(
                    executable = "",
                    args = emptyList(),
                    workingDirectory = "",
                    exitCode = 1,
                    stdout = "",
                    stderr = throwable.stackTraceToString()
                )
            }
            if (result.exitCode == 0 && revealPathOnSuccess != null) {
                onRevealPath(revealPathOnSuccess)
            }
            resultDialog = result.toDisplayString()
            running = false
            runningTitle = null
            runningDetail = null
            runningProgress = null
            onRequestReload()
        }
    }

    if (resultDialog != null) {
        AlertDialog(
            onDismissRequest = {
                resultDialog = null
                onDismissRequest()
            },
            title = { Text("操作结果") },
            text = {
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                ) {
                    SelectionContainer {
                        Text(resultDialog ?: "")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        resultDialog = null
                        onDismissRequest()
                    }
                ) { Text("关闭") }
            }
        )
        return
    }

    if (deleteDialogVisible) {
        val src = remember(target.path) { File(target.path) }
        AlertDialog(
            onDismissRequest = { if (!running) deleteDialogVisible = false },
            title = { Text("删除") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("确定删除以下路径？")
                    Text(src.absolutePath)
                    if (target.isDirectory) {
                        Text("这是目录，将递归删除。")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        launchOperation(
                            title = "删除",
                            revealPathOnSuccess = (src.parentFile ?: currentDir).absolutePath
                        ) {
                            val rm = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "rm").absolutePath
                            runTermuxTool(
                                context = context,
                                executable = rm,
                                args = arrayOf("-rf", "--", src.absolutePath),
                                workingDirectory = (src.parentFile ?: currentDir).absolutePath
                            )
                        }
                        deleteDialogVisible = false
                    },
                    enabled = !running
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogVisible = false }, enabled = !running) { Text("取消") }
            }
        )
        return
    }

    if (statsDialogVisible) {
        val scroll = rememberScrollState()
        LaunchedEffect(target.path) {
            statsRunning = true
            statsText = null
            statsText = withContext(Dispatchers.IO) {
                buildStatsReport(File(target.path))
            }
            statsRunning = false
        }
        AlertDialog(
            onDismissRequest = { if (!statsRunning) statsDialogVisible = false },
            title = { Text("统计") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (statsRunning) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text("正在统计…")
                        }
                    }
                    if (statsText != null) {
                        SelectionContainer {
                            Text(statsText ?: "")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { if (!statsRunning) statsDialogVisible = false }, enabled = !statsRunning) { Text("关闭") }
            }
        )
        return
    }

    if (renameDialogVisible) {
        AlertDialog(
            onDismissRequest = { if (!running) renameDialogVisible = false },
            title = { Text("重命名") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = renameValue,
                        onValueChange = { renameValue = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Text),
                        label = { Text("新名称") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newName = renameValue.trim()
                        if (newName.isEmpty()) return@TextButton
                        val src = File(target.path)
                        val dest = File(src.parentFile ?: currentDir, newName)
                        launchOperation(title = "重命名", revealPathOnSuccess = dest.absolutePath) {
                            if (dest.exists()) {
                                return@launchOperation TermuxToolResult(
                                    executable = "",
                                    args = emptyList(),
                                    workingDirectory = "",
                                    exitCode = 1,
                                    stdout = "",
                                    stderr = "目标已存在：${dest.absolutePath}"
                                )
                            }
                            val mv = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "mv").absolutePath
                            runTermuxTool(
                                context = context,
                                executable = mv,
                                args = arrayOf("--", src.absolutePath, dest.absolutePath),
                                workingDirectory = (src.parentFile ?: currentDir).absolutePath
                            )
                        }
                        renameDialogVisible = false
                    },
                    enabled = !running
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogVisible = false }, enabled = !running) { Text("取消") }
            }
        )
        return
    }

    if (copyDialogVisible) {
        AlertDialog(
            onDismissRequest = { if (!running) copyDialogVisible = false },
            title = { Text("复制到") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = copyDestDir,
                        onValueChange = { copyDestDir = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Text),
                        label = { Text("目标目录绝对路径") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val destDir = File(copyDestDir.trim())
                        val src = File(target.path)
                        val dest = File(destDir, src.name)
                        launchOperation(title = "复制", revealPathOnSuccess = dest.absolutePath) {
                            if (!destDir.exists() || !destDir.isDirectory) {
                                return@launchOperation TermuxToolResult(
                                    executable = "",
                                    args = emptyList(),
                                    workingDirectory = "",
                                    exitCode = 1,
                                    stdout = "",
                                    stderr = "目标目录不可用：${destDir.absolutePath}"
                                )
                            }
                            val dest = File(destDir, src.name)
                            if (dest.exists()) {
                                return@launchOperation TermuxToolResult(
                                    executable = "",
                                    args = emptyList(),
                                    workingDirectory = "",
                                    exitCode = 1,
                                    stdout = "",
                                    stderr = "目标已存在：${dest.absolutePath}"
                                )
                            }
                            val cp = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "cp").absolutePath
                            runTermuxTool(
                                context = context,
                                executable = cp,
                                args = arrayOf("-a", "--", src.absolutePath, destDir.absolutePath + File.separator),
                                workingDirectory = (src.parentFile ?: currentDir).absolutePath
                            )
                        }
                        copyDialogVisible = false
                    },
                    enabled = !running
                ) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { copyDialogVisible = false }, enabled = !running) { Text("取消") }
            }
        )
        return
    }

    if (cutDialogVisible) {
        AlertDialog(
            onDismissRequest = { if (!running) cutDialogVisible = false },
            title = { Text("剪切到") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = cutDestDir,
                        onValueChange = { cutDestDir = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Text),
                        label = { Text("目标目录绝对路径") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val destDir = File(cutDestDir.trim())
                        val src = File(target.path)
                        val dest = File(destDir, src.name)
                        launchOperation(title = "剪切", revealPathOnSuccess = dest.absolutePath) {
                            if (!destDir.exists() || !destDir.isDirectory) {
                                return@launchOperation TermuxToolResult(
                                    executable = "",
                                    args = emptyList(),
                                    workingDirectory = "",
                                    exitCode = 1,
                                    stdout = "",
                                    stderr = "目标目录不可用：${destDir.absolutePath}"
                                )
                            }
                            val dest = File(destDir, src.name)
                            if (dest.exists()) {
                                return@launchOperation TermuxToolResult(
                                    executable = "",
                                    args = emptyList(),
                                    workingDirectory = "",
                                    exitCode = 1,
                                    stdout = "",
                                    stderr = "目标已存在：${dest.absolutePath}"
                                )
                            }
                            val mv = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "mv").absolutePath
                            runTermuxTool(
                                context = context,
                                executable = mv,
                                args = arrayOf("--", src.absolutePath, destDir.absolutePath + File.separator),
                                workingDirectory = (src.parentFile ?: currentDir).absolutePath
                            )
                        }
                        cutDialogVisible = false
                    },
                    enabled = !running
                ) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { cutDialogVisible = false }, enabled = !running) { Text("取消") }
            }
        )
        return
    }

    if (compressDialogVisible) {
        AlertDialog(
            onDismissRequest = { if (!running) compressDialogVisible = false },
            title = { Text("压缩") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = compressArchivePath,
                        onValueChange = { compressArchivePath = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Text),
                        label = { Text("输出压缩包绝对路径(.tar.gz)") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val src = File(target.path)
                        val parent = src.parentFile ?: currentDir
                        val base = src.name
                        val out = File(compressArchivePath.trim())
                        launchOperationWithProgress(
                            title = "压缩",
                            totalUnits = null,
                            revealPathOnSuccess = out.absolutePath
                        ) { onProgress ->
                            if (out.exists()) {
                                return@launchOperationWithProgress TermuxToolResult(
                                    executable = "",
                                    args = emptyList(),
                                    workingDirectory = "",
                                    exitCode = 1,
                                    stdout = "",
                                    stderr = "目标已存在：${out.absolutePath}"
                                )
                            }
                            val totalUnits = runCatching { estimateNodeCountForTar(src) }.getOrNull()
                            onProgress(ProgressSnapshot(done = 0, total = totalUnits, lastItem = null))
                            val tar = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "tar").absolutePath
                            runTermuxToolWithLineProgress(
                                context = context,
                                executable = tar,
                                args = arrayOf("-czvf", out.absolutePath, "-C", parent.absolutePath, "--", base),
                                workingDirectory = parent.absolutePath,
                                totalUnits = totalUnits,
                                isUnitLine = { line -> line.isNotBlank() },
                                onProgress = onProgress
                            )
                        }
                        compressDialogVisible = false
                    },
                    enabled = !running
                ) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { compressDialogVisible = false }, enabled = !running) { Text("取消") }
            }
        )
        return
    }

    if (extractDialogVisible) {
        AlertDialog(
            onDismissRequest = { if (!running) extractDialogVisible = false },
            title = { Text("解压到") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = extractDestDir,
                        onValueChange = { extractDestDir = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Text),
                        label = { Text("目标目录绝对路径") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val src = File(target.path)
                        val dest = File(extractDestDir.trim())
                        launchOperationWithProgress(
                            title = "解压",
                            totalUnits = null,
                            revealPathOnSuccess = dest.absolutePath
                        ) { onProgress ->
                            dest.mkdirs()
                            if (!dest.exists() || !dest.isDirectory) {
                                return@launchOperationWithProgress TermuxToolResult(
                                    executable = "",
                                    args = emptyList(),
                                    workingDirectory = "",
                                    exitCode = 1,
                                    stdout = "",
                                    stderr = "目标目录不可用：${dest.absolutePath}"
                                )
                            }
                            runExtractWithProgress(
                                context = context,
                                archive = src,
                                destDir = dest,
                                onProgress = onProgress
                            )
                        }
                        extractDialogVisible = false
                    },
                    enabled = !running
                ) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { extractDialogVisible = false }, enabled = !running) { Text("取消") }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismissRequest() },
        title = { Text(target.name) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (running) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val progress = runningProgress
                        if (progress == null || progress.total == null) {
                            CircularProgressIndicator()
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val label = runningTitle ?: "正在执行…"
                            Text(label)
                            if (progress != null && progress.total != null) {
                                val fraction = (progress.done.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
                                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                                Text("${progress.done}/${progress.total}")
                            }
                            val detail = runningDetail
                            if (!detail.isNullOrBlank()) {
                                Text(detail)
                            }
                        }
                    }
                }
                TextButton(onClick = { renameDialogVisible = true }, enabled = !running) { Text("重命名") }
                TextButton(onClick = { copyDialogVisible = true }, enabled = !running) { Text("复制") }
                TextButton(onClick = { cutDialogVisible = true }, enabled = !running) { Text("剪切") }
                TextButton(onClick = { compressDialogVisible = true }, enabled = !running) { Text("压缩") }
                TextButton(onClick = { extractDialogVisible = true }, enabled = !running && !target.isDirectory) { Text("解压") }
                TextButton(onClick = { deleteDialogVisible = true }, enabled = !running) { Text("删除") }
                TextButton(onClick = { statsDialogVisible = true }, enabled = !running) { Text("统计") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismissRequest() }, enabled = !running) { Text("关闭") }
        }
    )
}

private data class ProgressSnapshot(
    val done: Int,
    val total: Int?,
    val lastItem: String?
)

private data class TermuxToolResult(
    val executable: String,
    val args: List<String>,
    val workingDirectory: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String
) {
    fun toDisplayString(): String {
        return buildString {
            if (executable.isNotBlank()) {
                append("命令：").append(executable).append("\n")
                append("参数：").append(args.joinToString(" ")).append("\n")
                append("工作目录：").append(workingDirectory).append("\n")
                append("退出码：").append(exitCode?.toString() ?: "-").append("\n")
            }
            if (stdout.isNotBlank()) append("\nstdout:\n").append(stdout.trim())
            if (stderr.isNotBlank()) append("\nstderr:\n").append(stderr.trim())
            if ((exitCode ?: 0) != 0 && stdout.isBlank() && stderr.isBlank()) append("\n执行失败（无输出）")
        }
    }
}

private suspend fun runExtractWithProgress(
    context: android.content.Context,
    archive: File,
    destDir: File,
    onProgress: (ProgressSnapshot) -> Unit
): TermuxToolResult {
    val name = archive.name.lowercase()
    val binDir = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH)
    val workDir = destDir.absolutePath

    return when {
        name.endsWith(".tar.gz") || name.endsWith(".tgz") -> {
            val tar = File(binDir, "tar").absolutePath
            val total = estimateTarArchiveEntries(context, tar, archive, gz = true, workDir = workDir)
            runTermuxToolWithLineProgress(
                context = context,
                executable = tar,
                args = arrayOf("-xzvf", archive.absolutePath, "-C", destDir.absolutePath),
                workingDirectory = workDir,
                totalUnits = total,
                isUnitLine = { line -> line.isNotBlank() },
                onProgress = onProgress
            )
        }
        name.endsWith(".tar") -> {
            val tar = File(binDir, "tar").absolutePath
            val total = estimateTarArchiveEntries(context, tar, archive, gz = false, workDir = workDir)
            runTermuxToolWithLineProgress(
                context = context,
                executable = tar,
                args = arrayOf("-xvf", archive.absolutePath, "-C", destDir.absolutePath),
                workingDirectory = workDir,
                totalUnits = total,
                isUnitLine = { line -> line.isNotBlank() },
                onProgress = onProgress
            )
        }
        name.endsWith(".zip") -> {
            val unzip = File(binDir, "unzip").absolutePath
            val total = estimateZipArchiveEntries(context, unzip, archive, workDir = workDir)
            runTermuxToolWithLineProgress(
                context = context,
                executable = unzip,
                args = arrayOf("-o", archive.absolutePath, "-d", destDir.absolutePath),
                workingDirectory = workDir,
                totalUnits = total,
                isUnitLine = { line ->
                    val t = line.trimStart()
                    t.startsWith("inflating:") || t.startsWith("extracting:") || t.startsWith("creating:")
                },
                onProgress = onProgress
            )
        }
        else -> {
            TermuxToolResult(
                executable = "",
                args = emptyList(),
                workingDirectory = workDir,
                exitCode = 1,
                stdout = "",
                stderr = "不支持的压缩格式：${archive.name}"
            )
        }
    }
}

private fun runTermuxTool(
    context: android.content.Context,
    executable: String,
    args: Array<String>,
    workingDirectory: String
): TermuxToolResult {
    val exeFile = File(executable)
    if (!exeFile.exists()) {
        return TermuxToolResult(
            executable = executable,
            args = args.toList(),
            workingDirectory = workingDirectory,
            exitCode = 1,
            stdout = "",
            stderr = "找不到可执行文件：$executable"
        )
    }

    val cmd = ExecutionCommand(0).apply {
        this.executable = executable
        this.arguments = args
        this.workingDirectory = workingDirectory
        this.runner = ExecutionCommand.Runner.APP_SHELL.getName()
        this.commandLabel = "ui-file-op-${UUID.randomUUID()}"
        this.setShellCommandShellEnvironment = true
    }

    val appShell = AppShell.execute(
        context,
        cmd,
        null,
        TermuxShellEnvironment(),
        null,
        true
    )

    val exit = appShell?.executionCommand?.resultData?.exitCode
    val out = appShell?.executionCommand?.resultData?.stdout?.toString()?.trim().orEmpty()
    val err = appShell?.executionCommand?.resultData?.stderr?.toString()?.trim().orEmpty()

    return TermuxToolResult(
        executable = executable,
        args = args.toList(),
        workingDirectory = workingDirectory,
        exitCode = exit,
        stdout = out,
        stderr = err
    )
}

private suspend fun runTermuxToolWithLineProgress(
    context: android.content.Context,
    executable: String,
    args: Array<String>,
    workingDirectory: String,
    totalUnits: Int?,
    isUnitLine: (String) -> Boolean,
    onProgress: (ProgressSnapshot) -> Unit
): TermuxToolResult {
    val exeFile = File(executable)
    if (!exeFile.exists()) {
        return TermuxToolResult(
            executable = executable,
            args = args.toList(),
            workingDirectory = workingDirectory,
            exitCode = 1,
            stdout = "",
            stderr = "找不到可执行文件：$executable"
        )
    }

    val cmd = ExecutionCommand(0).apply {
        this.executable = executable
        this.arguments = args
        this.workingDirectory = workingDirectory
        this.runner = ExecutionCommand.Runner.APP_SHELL.getName()
        this.commandLabel = "ui-file-op-${UUID.randomUUID()}"
        this.setShellCommandShellEnvironment = true
    }

    val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    val appShell = AppShell.execute(
        context,
        cmd,
        object : AppShell.AppShellClient {
            override fun onAppShellExited(appShell: AppShell) {
                finished.set(true)
            }
        },
        TermuxShellEnvironment(),
        null,
        false
    )

    if (appShell == null) {
        return TermuxToolResult(
            executable = executable,
            args = args.toList(),
            workingDirectory = workingDirectory,
            exitCode = 1,
            stdout = "",
            stderr = "启动失败"
        )
    }

    var done = 0
    var scannedLen = 0
    var pending = ""
    var lastItem: String? = null
    onProgress(ProgressSnapshot(done = done, total = totalUnits, lastItem = lastItem))

    while (!finished.get()) {
        val stdout = appShell.executionCommand.resultData.stdout.toString()
        if (stdout.length > scannedLen) {
            val delta = stdout.substring(scannedLen)
            scannedLen = stdout.length
            val text = pending + delta
            val lines = text.split('\n')
            pending = if (text.endsWith('\n')) "" else lines.lastOrNull().orEmpty()
            val completeLines = if (text.endsWith('\n')) lines else lines.dropLast(1)
            for (line in completeLines) {
                val trimmed = line.trimEnd().trimStart()
                if (!isUnitLine(trimmed)) continue
                done += 1
                lastItem = trimmed.takeLast(160)
                onProgress(ProgressSnapshot(done = done, total = totalUnits, lastItem = lastItem))
            }
        }
        delay(220)
    }

    val exit = appShell.executionCommand.resultData.exitCode
    val out = appShell.executionCommand.resultData.stdout.toString().trim()
    val err = appShell.executionCommand.resultData.stderr.toString().trim()
    return TermuxToolResult(
        executable = executable,
        args = args.toList(),
        workingDirectory = workingDirectory,
        exitCode = exit,
        stdout = out,
        stderr = err
    )
}

private fun estimateNodeCountForTar(src: File): Int {
    if (!src.exists()) return 0
    if (src.isFile) return 1
    var count = 0
    src.walkTopDown().forEach { node ->
        if (node == src) return@forEach
        count += 1
    }
    return count.coerceAtLeast(1)
}

private fun estimateTarArchiveEntries(
    context: android.content.Context,
    tarExecutable: String,
    archive: File,
    gz: Boolean,
    workDir: String
): Int? {
    val args = if (gz) arrayOf("-tzf", archive.absolutePath) else arrayOf("-tf", archive.absolutePath)
    val result = runTermuxTool(context, tarExecutable, args, workDir)
    if (result.exitCode != 0) return null
    val lines = result.stdout.split('\n').asSequence().map { it.trim() }.filter { it.isNotEmpty() }.count()
    return lines.toInt().coerceAtLeast(1)
}

private fun estimateZipArchiveEntries(
    context: android.content.Context,
    unzipExecutable: String,
    archive: File,
    workDir: String
): Int? {
    val zResult = runTermuxTool(context, unzipExecutable, arrayOf("-Z1", archive.absolutePath), workDir)
    if (zResult.exitCode == 0) {
        val lines = zResult.stdout.split('\n').asSequence().map { it.trim() }.filter { it.isNotEmpty() }.count()
        return lines.toInt().coerceAtLeast(1)
    }

    val lResult = runTermuxTool(context, unzipExecutable, arrayOf("-l", archive.absolutePath), workDir)
    if (lResult.exitCode != 0) return null
    val all = lResult.stdout.lines()
    val start = all.indexOfFirst { it.trimStart().startsWith("Length") }
    if (start < 0) return null
    val end = all.indexOfLast { it.trimEnd().endsWith("files") || it.trimEnd().endsWith("file") }
    if (end <= start) return null
    val entries = all.subList(start + 2, end).count { it.trim().isNotEmpty() }
    return entries.coerceAtLeast(1)
}

private data class StatsSummary(
    val root: String,
    val fileCount: Long,
    val dirCount: Long,
    val totalBytes: Long,
    val textFileCount: Long,
    val codeFileCount: Long,
    val codeLineCount: Long,
    val extensionCounts: Map<String, Long>
)

private fun buildStatsReport(root: File): String {
    val summary = collectStats(root)
    val sortedExt = summary.extensionCounts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
    val top = 30
    val head = sortedExt.take(top)
    val tailCount = sortedExt.drop(top).sumOf { it.value }

    return buildString {
        append("路径：").append(summary.root).append("\n")
        append("目录数：").append(summary.dirCount).append("\n")
        append("文件数：").append(summary.fileCount).append("\n")
        append("总大小：").append(formatBytes(summary.totalBytes)).append("\n")
        append("\n")
        append("文本文件：").append(summary.textFileCount).append("\n")
        append("代码文件：").append(summary.codeFileCount).append("\n")
        append("代码行数：").append(summary.codeLineCount).append("\n")
        append("\n")
        append("文件类型分布（按扩展名）：\n")
        for ((ext, count) in head) {
            append(ext).append("：").append(count).append("\n")
        }
        if (tailCount > 0) {
            append("其他：").append(tailCount).append("\n")
        }
    }
}

private fun collectStats(root: File): StatsSummary {
    val extCounts = linkedMapOf<String, Long>()
    var files = 0L
    var dirs = 0L
    var bytes = 0L
    var textFiles = 0L
    var codeFiles = 0L
    var codeLines = 0L

    fun addExt(path: File) {
        val ext = path.extension.lowercase().ifBlank { "(无扩展名)" }
        extCounts[ext] = (extCounts[ext] ?: 0L) + 1L
    }

    val targets = if (root.isDirectory) root.walkTopDown() else sequenceOf(root)
    for (f in targets) {
        if (!f.exists()) continue
        if (f.isDirectory) {
            dirs += 1
            continue
        }
        files += 1
        addExt(f)
        bytes += runCatching { f.length() }.getOrNull() ?: 0L

        val isText = runCatching { looksLikeTextFile(f) }.getOrNull() == true
        if (isText) textFiles += 1

        if (isCodeFile(f)) {
            codeFiles += 1
            if (isText) {
                codeLines += runCatching { countLinesFast(f) }.getOrNull() ?: 0L
            }
        }
    }

    return StatsSummary(
        root = root.absolutePath,
        fileCount = files,
        dirCount = dirs,
        totalBytes = bytes,
        textFileCount = textFiles,
        codeFileCount = codeFiles,
        codeLineCount = codeLines,
        extensionCounts = extCounts
    )
}

private fun isCodeFile(file: File): Boolean {
    val ext = file.extension.lowercase()
    return ext in setOf(
        "kt", "kts", "java", "c", "cc", "cpp", "h", "hpp",
        "py", "js", "ts", "jsx", "tsx", "go", "rs", "rb", "php",
        "sh", "bash", "zsh", "fish",
        "xml", "json", "yml", "yaml", "toml", "ini", "properties", "gradle", "mk",
        "md", "txt"
    )
}

private fun looksLikeTextFile(file: File): Boolean {
    val max = 4096
    val bytes = file.inputStream().use { input ->
        val buf = ByteArray(max)
        val read = input.read(buf)
        if (read <= 0) return true
        buf.copyOf(read)
    }
    if (bytes.any { it == 0.toByte() }) return false
    return true
}

private fun countLinesFast(file: File): Long {
    file.bufferedReader().useLines { seq ->
        var n = 0L
        for (@Suppress("UNUSED_VARIABLE") line in seq) n += 1
        return n
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes.toDouble() / 1024.0
    if (kb < 1024) return String.format("%.1fKB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1fMB", mb)
    val gb = mb / 1024.0
    return String.format("%.1fGB", gb)
}
