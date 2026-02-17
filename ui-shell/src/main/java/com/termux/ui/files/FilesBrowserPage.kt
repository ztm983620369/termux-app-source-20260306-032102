package com.termux.ui.files

import android.content.Context
import android.content.Intent
import android.content.ContentValues
import com.termux.bridge.FileOpenRequest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import java.io.File
import java.util.Locale
import com.termux.ui.nav.UiShellNavBridge
import com.termux.ui.panel.ProotPrefs
import com.termux.ui.panel.ProotDistroManager
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.net.uri.UriUtils
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast

@Composable
fun FilesBrowserPage(
    modifier: Modifier = Modifier,
    requestedDirPath: String? = null,
    onOpenFile: (FileOpenRequest) -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val filesRoot = remember { File(TermuxConstants.TERMUX_FILES_DIR_PATH) }
    val externalRoot = remember { context.getExternalFilesDir(null) }
    val homeDir = remember { File(TermuxConstants.TERMUX_HOME_DIR_PATH) }
    val prefixDir = remember { File(TermuxConstants.TERMUX_PREFIX_DIR_PATH) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { homeDir.mkdirs() }
    }

    var currentDirPath by rememberSaveable { mutableStateOf(homeDir.absolutePath) }
    val currentDir by remember(currentDirPath) { derivedStateOf { File(currentDirPath) } }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var actionTarget by remember { mutableStateOf<FileEntry?>(null) }
    var pendingRevealPath by remember { mutableStateOf<String?>(null) }
    var highlightedPath by remember { mutableStateOf<String?>(null) }
    var selfTestDialogVisible by remember { mutableStateOf(false) }
    var selfTestRunning by remember { mutableStateOf(false) }
    var selfTestResult by remember { mutableStateOf<String?>(null) }
    var lastLoadedCount by remember { mutableStateOf(0) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    var previewImageName by remember { mutableStateOf<String?>(null) }
    var createDialogVisible by remember { mutableStateOf(false) }
    var createMode by remember { mutableStateOf(CreateMode.FILE) }
    var createName by remember { mutableStateOf("") }
    var createError by remember { mutableStateOf<String?>(null) }
    val sizeCache = remember { mutableStateMapOf<String, Long>() }
    val sizeProbeRequests = remember { Channel<String>(capacity = 256) }
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
    var iconTheme by remember { mutableStateOf<VsCodeFileIconTheme?>(null) }
    LaunchedEffect(Unit) {
        iconTheme = withContext(Dispatchers.IO) { VsCodeFileIconTheme.load(context) }
    }

    val preferLight = !isSystemInDarkTheme()
    val enableSizeProbe by remember {
        derivedStateOf { entries.size <= 400 }
    }
    val listState = rememberLazyListState()
    var fastScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        for (path in sizeProbeRequests) {
            if (sizeCache.containsKey(path)) continue
            val len = runCatching { withContext(Dispatchers.IO) { File(path).length() } }.getOrNull() ?: continue
            sizeCache[path] = len
        }
    }

    LaunchedEffect(currentDirPath, entries, enableSizeProbe, fastScrolling) {
        if (!enableSizeProbe) return@LaunchedEffect
        val queued = HashSet<String>(256)
        snapshotFlow {
            if (fastScrolling) {
                emptyList()
            } else {
                listState.layoutInfo.visibleItemsInfo
                    .map { it.index }
                    .take(24)
            }
        }
            .distinctUntilChanged()
            .collect { visibleIndices ->
                for (idx in visibleIndices) {
                    val entry = entries.getOrNull(idx) ?: continue
                    if (entry.isDirectory) continue
                    if (entry.sizeBytes != null) continue
                    if (sizeCache[entry.path] != null) continue
                    if (!queued.add(entry.path)) continue
                    sizeProbeRequests.trySend(entry.path)
                }
            }
    }
    DisposableEffect(Unit) {
        onDispose { sizeProbeRequests.close() }
    }

    val refreshSignals = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val scope = rememberCoroutineScope()
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var topMenuExpanded by remember { mutableStateOf(false) }

    fun requestReload() {
        refreshSignals.tryEmit(Unit)
    }

    fun scopeRootFor(dir: File): File {
        val ext = externalRoot
        return if (ext != null && isWithinRoot(dir, ext)) ext else filesRoot
    }

    fun setDirIfAllowed(target: File) {
        val ext = externalRoot
        val allowed = isWithinRoot(target, filesRoot) || (ext != null && isWithinRoot(target, ext))
        if (!allowed) return
        currentDirPath = target.absolutePath
    }

    LaunchedEffect(requestedDirPath) {
        val req = requestedDirPath?.trim().orEmpty()
        if (req.isBlank()) return@LaunchedEffect
        val dir = File(req)
        if (!dir.exists() || !dir.isDirectory) return@LaunchedEffect
        setDirIfAllowed(dir)
    }

    LaunchedEffect(Unit) {
        val req = UiShellNavBridge.consumeRequestedFilesDir(context)?.trim().orEmpty()
        if (req.isBlank()) return@LaunchedEffect
        val dir = File(req)
        if (!dir.exists() || !dir.isDirectory) return@LaunchedEffect
        setDirIfAllowed(dir)
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val p = i?.getStringExtra("path")?.trim().orEmpty()
                if (p.isBlank()) return
                val dir = File(p)
                if (!dir.exists() || !dir.isDirectory) return
                setDirIfAllowed(dir)
            }
        }
        val filter = IntentFilter("com.termux.app.action.OPEN_FILES_AT")
        context.registerReceiver(receiver, filter)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    fun goUp() {
        val scopeRoot = scopeRootFor(currentDir)
        if (samePath(currentDir, scopeRoot)) return
        val parent = currentDir.parentFile ?: scopeRoot
        setDirIfAllowed(parent)
    }

    fun goHome() {
        scope.launch {
            withContext(Dispatchers.IO) { homeDir.mkdirs() }
            setDirIfAllowed(homeDir)
        }
    }

    fun goPrefix() {
        setDirIfAllowed(prefixDir)
    }

    fun goExternal() {
        val ext = externalRoot ?: return
        setDirIfAllowed(ext)
    }

    fun goWorkspace() {
        scope.launch {
            withContext(Dispatchers.IO) { File(homeDir, "workspace").mkdirs() }
            setDirIfAllowed(File(homeDir, "workspace"))
        }
    }

    fun goProotWorkspace() {
        val state = ProotPrefs.getState(context)
        val distro = state.defaultDistro
        val dir = File(ProotDistroManager.installedRootHomeDir(distro), "workspace")
        scope.launch {
            withContext(Dispatchers.IO) { dir.mkdirs() }
            setDirIfAllowed(dir)
        }
    }

    fun loadDirectory(dir: File) {
        loadJob?.cancel()
        loadJob = scope.launch {
            isLoading = true
            errorMessage = null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (!dir.exists() || !dir.isDirectory) {
                        throw IllegalStateException("目录不可用: ${dir.absolutePath}")
                    }
                    FilesRepository.listChildren(dir)
                }
            }
            result
                .onSuccess { list ->
                    entries = list
                    lastLoadedCount = list.size
                    for (e in list) {
                        val s = e.sizeBytes
                        if (s != null) sizeCache[e.path] = s
                    }
                }
                .onFailure { e ->
                    entries = emptyList()
                    lastLoadedCount = 0
                    errorMessage = e.message ?: e::class.java.simpleName
                }
            isLoading = false
        }
    }

    LaunchedEffect(currentDirPath) {
        loadDirectory(currentDir)
    }

    LaunchedEffect(currentDirPath) {
        refreshSignals.collectLatest {
            delay(if (lastLoadedCount > 2000) 1200 else 250)
            loadDirectory(currentDir)
        }
    }

    DisposableEffect(currentDirPath) {
        val observer = if (currentDir.exists() && currentDir.isDirectory) {
            DirectoryObserver(currentDir) { _, _ -> requestReload() }.also { it.startWatching() }
        } else {
            null
        }
        onDispose {
            observer?.stopWatching()
        }
    }

    LaunchedEffect(entries, pendingRevealPath) {
        val path = pendingRevealPath ?: return@LaunchedEffect
        val index = entries.indexOfFirst { it.path == path }
        if (index >= 0) {
            listState.scrollToItem(index)
            highlightedPath = path
            pendingRevealPath = null
        }
    }

    LaunchedEffect(highlightedPath) {
        if (highlightedPath == null) return@LaunchedEffect
        delay(1400)
        highlightedPath = null
    }

    fun openCreateDialog(mode: CreateMode) {
        createMode = mode
        createName = ""
        createError = null
        createDialogVisible = true
    }

    fun performCreate(mode: CreateMode, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            createError = "名称不能为空"
            return
        }
        if (trimmed.contains('/') || trimmed.contains(File.separatorChar)) {
            createError = "名称不能包含路径分隔符"
            return
        }
        val target = File(currentDir, trimmed)
        if (target.exists()) {
            createError = "已存在：${target.absolutePath}"
            return
        }
        val ok = runCatching {
            if (mode == CreateMode.FILE) {
                target.parentFile?.mkdirs()
                target.createNewFile()
            } else {
                target.mkdirs()
            }
        }.getOrDefault(false)
        if (!ok) {
            createError = "创建失败"
            return
        }
        createDialogVisible = false
        requestReload()
        pendingRevealPath = target.absolutePath
    }

    val canGoUp by remember(currentDirPath) {
        derivedStateOf { !samePath(currentDir, scopeRootFor(currentDir)) }
    }
    val shouldHandleBack by remember(currentDirPath, previewImagePath, createDialogVisible, actionTarget, selfTestDialogVisible) {
        derivedStateOf { previewImagePath != null || createDialogVisible || actionTarget != null || selfTestDialogVisible || canGoUp }
    }
    BackHandler(enabled = shouldHandleBack) {
        when {
            previewImagePath != null -> previewImagePath = null
            createDialogVisible -> createDialogVisible = false
            actionTarget != null -> actionTarget = null
            selfTestDialogVisible -> selfTestDialogVisible = false
            canGoUp -> goUp()
            else -> Unit
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PathBreadcrumb(
                        modifier = Modifier.weight(1f),
                        items = buildBreadcrumb(currentDir, filesRoot, homeDir, prefixDir),
                        onClick = { dir -> setDirIfAllowed(dir) }
                    )

                    TextButton(
                        onClick = {
                            context.startActivity(Intent(context, CanvasDemoActivity::class.java))
                        }
                    ) {
                        Text(text = "画布")
                    }

                    Box {
                        IconButton(onClick = { topMenuExpanded = true }) {
                            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = null)
                        }

                        DropdownMenu(
                            expanded = topMenuExpanded,
                            onDismissRequest = { topMenuExpanded = false }
                        ) {
                        DropdownMenuItem(
                            text = { Text("上级") },
                            enabled = !samePath(currentDir, scopeRootFor(currentDir)),
                            onClick = {
                                topMenuExpanded = false
                                goUp()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Home") },
                            onClick = {
                                topMenuExpanded = false
                                goHome()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Workspace") },
                            onClick = {
                                topMenuExpanded = false
                                goWorkspace()
                            }
                        )
                        val proot = ProotPrefs.getState(context)
                        if (proot.enabled) {
                            DropdownMenuItem(
                                text = { Text("PRoot Workspace") },
                                onClick = {
                                    topMenuExpanded = false
                                    goProotWorkspace()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Prefix") },
                            onClick = {
                                topMenuExpanded = false
                                goPrefix()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("External") },
                            enabled = externalRoot != null,
                            onClick = {
                                topMenuExpanded = false
                                goExternal()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("刷新") },
                            onClick = {
                                topMenuExpanded = false
                                requestReload()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("新建文件") },
                            onClick = {
                                topMenuExpanded = false
                                openCreateDialog(CreateMode.FILE)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("新建文件夹") },
                            onClick = {
                                topMenuExpanded = false
                                openCreateDialog(CreateMode.FOLDER)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("自测") },
                            onClick = {
                                topMenuExpanded = false
                                scope.launch {
                                    selfTestDialogVisible = true
                                    selfTestRunning = true
                                    errorMessage = null
                                    selfTestResult = runCatching { FilesSelfTest.run(context, filesRoot, homeDir, currentDir) }
                                        .getOrElse { it.stackTraceToString() }
                                    selfTestRunning = false
                                }
                            }
                        )
                    }
                }
            }
            }

            if (selfTestDialogVisible) {
                val scrollState = rememberScrollState()
                val resultText = selfTestResult
                AlertDialog(
                    onDismissRequest = { selfTestDialogVisible = false },
                    title = { Text(text = "自测") },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (selfTestRunning) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator()
                                    Text(text = "正在自测…")
                                }
                            }
                            if (resultText != null) {
                                SelectionContainer {
                                    Text(
                                        text = resultText,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val text = selfTestResult ?: return@TextButton
                                clipboard.setText(AnnotatedString(text))
                            },
                            enabled = !selfTestRunning && !selfTestResult.isNullOrEmpty()
                        ) {
                            Text(text = "复制结果")
                        }
                    },
                    dismissButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    val text = selfTestResult ?: return@TextButton
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                    val chooser = Intent.createChooser(sendIntent, "分享自测结果")
                                    context.startActivity(chooser)
                                },
                                enabled = !selfTestRunning && !selfTestResult.isNullOrEmpty()
                            ) {
                                Text(text = "分享")
                            }
                            TextButton(onClick = { selfTestDialogVisible = false }) {
                                Text(text = "关闭")
                            }
                        }
                    }
                )
            }

            if (isLoading && entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            if (errorMessage != null && entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = errorMessage ?: "未知错误", color = MaterialTheme.colorScheme.error)
                }
                return@Column
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.Top
                ) {
                    items(
                        items = entries,
                        key = { it.path },
                        contentType = { if (it.isDirectory) "dir" else "file" }
                    ) { entry ->
                        FileRow(
                            entry = entry,
                            iconTheme = iconTheme,
                            imageLoader = imageLoader,
                            sizeCache = sizeCache,
                            preferLight = preferLight,
                            enableSizeProbe = enableSizeProbe,
                            fastScrolling = fastScrolling,
                            highlighted = entry.path == highlightedPath,
                            onClick = {
                                if (entry.isDirectory) {
                                    setDirIfAllowed(File(entry.path))
                                } else {
                                    if (isImageFile(entry.path)) {
                                        previewImagePath = entry.path
                                        previewImageName = entry.name
                                    } else {
                                        val ext = entry.name.substringAfterLast('.', "").lowercase().ifBlank { null }
                                        val mimeType = ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                                            ?: "application/octet-stream"
                                        val readOnly = !File(entry.path).canWrite()
                                        onOpenFile(
                                            FileOpenRequest(
                                                path = entry.path,
                                                displayName = entry.name,
                                                readOnly = readOnly,
                                                extension = ext,
                                                mimeType = mimeType
                                            )
                                        )
                                    }
                                }
                            },
                            onLongClick = {
                                actionTarget = entry
                            }
                        )
                    }
                }

                VerticalLazyScrollbar(
                    listState = listState,
                    onFastScrollChanged = { fastScrolling = it },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                )
            }

            FileActionsDialogHost(
                target = actionTarget,
                currentDir = currentDir,
                onDismissRequest = { actionTarget = null },
                onRequestReload = { requestReload() },
                onRevealPath = { path ->
                    val file = File(path)
                    val parent = file.parentFile
                    if (parent != null) setDirIfAllowed(parent)
                    pendingRevealPath = path
                }
            )
        }

        if (createDialogVisible) {
            AlertDialog(
                onDismissRequest = { createDialogVisible = false },
                title = { Text(if (createMode == CreateMode.FILE) "新建文件" else "新建文件夹") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = createName,
                            onValueChange = { createName = it },
                            singleLine = true,
                            label = { Text("名称") }
                        )
                        if (createError != null) {
                            Text(createError ?: "", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { performCreate(createMode, createName) }
                    ) { Text("创建") }
                },
                dismissButton = {
                    TextButton(onClick = { createDialogVisible = false }) { Text("取消") }
                }
            )
        }

        val previewPath = previewImagePath
        if (previewPath != null) {
            val previewFile = remember(previewPath) { File(previewPath) }
            Dialog(onDismissRequest = { previewImagePath = null }) {
                Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { previewImagePath = null }) {
                                Text("关闭", color = Color.White)
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            val request = remember(previewPath) {
                                ImageRequest.Builder(context)
                                    .data(previewFile)
                                    .build()
                            }
                            AsyncImage(
                                model = request,
                                contentDescription = previewImageName,
                                imageLoader = imageLoader,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = previewImageName ?: previewFile.name,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        shareToWeChat(context, previewFile)
                                    }
                                ) {
                                    Text("分享到微信", color = Color.White)
                                }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                saveImageToAlbum(context, previewFile)
                                            }
                                            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Text("保存相册", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerticalLazyScrollbar(
    listState: LazyListState,
    onFastScrollChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollRequests = remember { Channel<Int>(capacity = Channel.CONFLATED) }
    LaunchedEffect(scrollRequests, listState) {
        for (targetIndex in scrollRequests) {
            listState.scrollToItem(targetIndex)
        }
    }
    DisposableEffect(Unit) {
        onDispose { scrollRequests.close() }
    }

    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo
    val viewportHeight = layoutInfo.viewportSize.height.toFloat()

    if (totalItems <= 0 || visibleItems.isEmpty() || viewportHeight <= 0f) return

    val itemRange = max(1, totalItems - 1)
    val scrollFraction = (listState.firstVisibleItemIndex.toFloat() / itemRange.toFloat()).coerceIn(0f, 1f)

    val minThumbPx = 28f
    val thumbHeightPx = (viewportHeight * (visibleItems.size.toFloat() / totalItems.toFloat()))
        .coerceAtLeast(minThumbPx)
        .coerceAtMost(viewportHeight)
    val thumbTravelPx = max(1f, viewportHeight - thumbHeightPx)
    val thumbTopPx = scrollFraction * thumbTravelPx

    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val widthDp = 10.dp

    Canvas(
        modifier = modifier
            .width(widthDp)
            .pointerInput(totalItems, viewportHeight, thumbHeightPx) {
                var lastIndex = -1
                fun requestScrollToY(y: Float) {
                    val newTop = (y - thumbHeightPx / 2f).coerceIn(0f, thumbTravelPx)
                    val newFraction = (newTop / thumbTravelPx).coerceIn(0f, 1f)
                    val targetIndex = (newFraction * itemRange.toFloat()).roundToInt().coerceIn(0, totalItems - 1)
                    if (targetIndex == lastIndex) return
                    lastIndex = targetIndex
                    scrollRequests.trySend(targetIndex)
                }

                detectVerticalDragGestures(
                    onDragStart = { start ->
                        lastIndex = -1
                        onFastScrollChanged(true)
                        requestScrollToY(start.y)
                    },
                    onDragEnd = {
                        onFastScrollChanged(false)
                    },
                    onDragCancel = {
                        onFastScrollChanged(false)
                    },
                    onVerticalDrag = { change, _ ->
                        requestScrollToY(change.position.y)
                    }
                )
            }
    ) {
        val trackWidthPx = size.width * 0.7f
        val x = (size.width - trackWidthPx) / 2f
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(x, 0f),
            size = Size(trackWidthPx, size.height),
            cornerRadius = CornerRadius(trackWidthPx / 2f, trackWidthPx / 2f)
        )
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(x, thumbTopPx),
            size = Size(trackWidthPx, thumbHeightPx),
            cornerRadius = CornerRadius(trackWidthPx / 2f, trackWidthPx / 2f)
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FileRow(
    entry: FileEntry,
    iconTheme: VsCodeFileIconTheme?,
    imageLoader: ImageLoader,
    sizeCache: SnapshotStateMap<String, Long>,
    preferLight: Boolean,
    enableSizeProbe: Boolean,
    fastScrolling: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val highlightColor = if (highlighted) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(highlightColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val iconAssetName = remember(iconTheme, entry.name, entry.isDirectory, preferLight, fastScrolling) {
            if (fastScrolling) null else iconTheme?.resolveAssetNameFast(entry.name, entry.isDirectory, preferLight = preferLight)
        }
        if (iconAssetName.isNullOrBlank()) {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                contentDescription = null
            )
        } else {
            val model = remember(iconAssetName) { "file:///android_asset/$iconAssetName" }
            val fallbackPainter = rememberVectorPainter(
                image = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile
            )
            AsyncImage(
                model = model,
                imageLoader = imageLoader,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                error = fallbackPainter
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)

            val size = entry.sizeBytes ?: sizeCache[entry.path]

            val meta = if (fastScrolling) {
                ""
            } else if (entry.isDirectory) {
                "目录"
            } else {
                if (size != null) formatBytes(size) else if (enableSizeProbe) "…" else ""
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun displayPath(directory: File, filesRoot: File, homeDir: File, prefixDir: File): String {
    val dirPath = directory.absolutePath
    val homePath = homeDir.absolutePath.trimEnd(File.separatorChar)
    val prefixPath = prefixDir.absolutePath.trimEnd(File.separatorChar)
    val filesRootPath = filesRoot.absolutePath.trimEnd(File.separatorChar)

    if (dirPath == homePath) return "~"
    if (dirPath.startsWith(homePath + File.separator)) {
        return "~/" + dirPath.substring((homePath + File.separator).length)
    }

    if (dirPath == prefixPath) return "\$PREFIX"
    if (dirPath.startsWith(prefixPath + File.separator)) {
        return "\$PREFIX/" + dirPath.substring((prefixPath + File.separator).length)
    }

    if (dirPath == filesRootPath) return filesRootPath
    if (dirPath.startsWith(filesRootPath + File.separator)) {
        return filesRootPath + File.separator + dirPath.substring((filesRootPath + File.separator).length)
    }

    return dirPath
}

private data class BreadcrumbItem(
    val label: String,
    val dir: File
)

private fun buildBreadcrumb(
    currentDir: File,
    filesRoot: File,
    homeDir: File,
    prefixDir: File
): List<BreadcrumbItem> {
    val baseDir = when {
        isWithinRoot(currentDir, homeDir) -> homeDir
        isWithinRoot(currentDir, prefixDir) -> prefixDir
        isWithinRoot(currentDir, filesRoot) -> filesRoot
        else -> null
    }
    val baseLabel = when (baseDir) {
        homeDir -> "~"
        prefixDir -> "\$PREFIX"
        filesRoot -> filesRoot.absolutePath
        else -> File.separator
    }
    val rootDir = baseDir ?: File(File.separator)
    val items = ArrayList<BreadcrumbItem>()
    items.add(BreadcrumbItem(baseLabel, rootDir))
    val basePath = runCatching { rootDir.canonicalPath }.getOrDefault(rootDir.absolutePath).trimEnd(File.separatorChar)
    val currentPath = runCatching { currentDir.canonicalPath }.getOrDefault(currentDir.absolutePath)
    if (currentPath != basePath) {
        val rel = currentPath.removePrefix(basePath).trimStart(File.separatorChar)
        if (rel.isNotBlank()) {
            var cursor = rootDir
            for (segment in rel.split(File.separatorChar)) {
                if (segment.isBlank()) continue
                cursor = File(cursor, segment)
                items.add(BreadcrumbItem(segment, cursor))
            }
        }
    }
    return items
}

@Composable
private fun PathBreadcrumb(
    items: List<BreadcrumbItem>,
    onClick: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    LaunchedEffect(items.size) {
        scroll.scrollTo(scroll.maxValue)
    }
    Row(
        modifier = modifier.horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEachIndexed { index, item ->
            val isLast = index == items.lastIndex
            val containerColor = if (isLast) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
            val contentColor = if (isLast) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                color = containerColor,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .clickable(enabled = !isLast) { onClick(item.dir) }
            ) {
                Text(
                    text = item.label,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = if (isLast) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            if (index != items.lastIndex) {
                Text(
                    text = "›",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

private fun isWithinRoot(file: File, root: File): Boolean {
    val rootCanonical = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
    val fileCanonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    if (fileCanonical == rootCanonical) return true
    return fileCanonical.startsWith(rootCanonical.trimEnd(File.separatorChar) + File.separator)
}

private fun samePath(a: File, b: File): Boolean {
    return runCatching { a.canonicalPath == b.canonicalPath }
        .getOrDefault(a.absolutePath == b.absolutePath)
}

private fun formatBytes(bytes: Long): String {
    var v = bytes.toDouble().coerceAtLeast(0.0)
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
    var i = 0
    while (v >= 1024.0 && i < units.lastIndex) {
        v /= 1024.0
        i++
    }
    val decimals = when {
        i == 0 -> 0
        v < 10 -> 2
        v < 100 -> 1
        else -> 0
    }
    return "%.${decimals}f %s".format(Locale.US, v, units[i])
}

private enum class CreateMode {
    FILE,
    FOLDER
}

private fun isImageFile(path: String): Boolean {
    val ext = path.substringAfterLast('.', "").lowercase()
    return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
}

private fun guessMimeType(file: File): String {
    val ext = file.extension.lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    return mime ?: "image/*"
}

private fun shareToWeChat(context: Context, file: File) {
    if (!file.exists() || !file.isFile) {
        Toast.makeText(context, "文件不可用", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = UriUtils.getContentUri(TermuxConstants.TERMUX_FILE_SHARE_URI_AUTHORITY, file.absolutePath)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = guessMimeType(file)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        setPackage("com.tencent.mm")
    }
    val resolved = intent.resolveActivity(context.packageManager)
    if (resolved == null) {
        Toast.makeText(context, "未安装微信", Toast.LENGTH_SHORT).show()
        return
    }
    context.startActivity(intent)
}

private data class SaveResult(val ok: Boolean, val message: String)

private fun saveImageToAlbum(context: Context, file: File): SaveResult {
    if (!file.exists() || !file.isFile) return SaveResult(false, "文件不可用")
    val resolver = context.contentResolver
    val mime = guessMimeType(file)
    val name = file.name
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            dir.mkdirs()
            put(MediaStore.MediaColumns.DATA, File(dir, name).absolutePath)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return SaveResult(false, "创建相册条目失败")
    return try {
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input ->
                input.copyTo(out)
            }
        } ?: return SaveResult(false, "写入失败")
        SaveResult(true, "已保存到相册")
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        SaveResult(false, e.message ?: "保存失败")
    }
}
