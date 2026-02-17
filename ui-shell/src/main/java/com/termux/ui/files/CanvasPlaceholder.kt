@file:Suppress("NAME_SHADOWING", "MemberVisibilityCanBePrivate", "unused")

package com.termux.ui.files

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.*

/**
 * VSCode 风格 + 物理级展开/收起动画版：
 * - 层级线：每层都画（竖线）
 * - 移除横杠、移除 L 形连接线、移除 chevron（三角箭头）
 * - 展开/收起：节点带弹簧位移 + 子节点从父节点下方“弹出/缩回”的关联感
 */
object CanvasPlaceholderOptimized {

    @Composable
    fun FullscreenDemo(visible: Boolean, onDismissRequest: () -> Unit) {
        if (!visible) return
        Screen(onBack = onDismissRequest)
    }

    @Composable
    fun Screen(onBack: () -> Unit) {
        val density = LocalDensity.current
        val style = remember(density) { RenderStyle(density) }

        var debugOverlay by remember { mutableStateOf(false) }

        val root = remember { DemoProject.basicProject() }

        // TreeState 内置“逻辑列表 + 动画渲染列表”
        val treeState = remember(root) { TreeState(root, autoExpandDepth = -1) }

        val scope = rememberCoroutineScope()
        val viewport = remember { ViewportState() }

        val stats = remember(root) { root.stats() }
        val selectedPathText by remember(treeState.selectedId) {
            derivedStateOf {
                val raw = treeState.selectedId.ifBlank { "root" }
                val parts = raw.split('/').filter { it.isNotBlank() }
                if (parts.size <= 4) parts.joinToString("/")
                else "…/" + parts.takeLast(3).joinToString("/")
            }
        }

        val colors = remember { Palette() }
        val paints = remember { PaintBundle(colors) }

        Surface(modifier = Modifier.fillMaxSize(), color = colors.bg) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(
                    onBack = onBack,
                    onReset = {
                        viewport.stopFling()
                        viewport.scale = 1f
                        viewport.offset = Offset.Zero
                    },
                    debugOverlay = debugOverlay,
                    onToggleDebugOverlay = { debugOverlay = !debugOverlay },
                    title = "$selectedPathText  目录=${stats.dirCount} 文件=${stats.fileCount} 深度=${stats.maxDepth}  ${(viewport.scale * 100).toInt()}%",
                    colors = colors
                )

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(treeState.renderRows, treeState.logicalVisibleNodes) {
                            detectTreeGestures(
                                viewport = viewport,
                                treeState = treeState,
                                style = style,
                                scope = scope
                            )
                        }
                ) {
                    renderTree(
                        viewport = viewport,
                        treeState = treeState,
                        style = style,
                        colors = colors,
                        paints = paints,
                        debugOverlay = debugOverlay
                    )
                }
            }
        }
    }

    // ----------------------------
    // Top Bar
    // ----------------------------

    @Composable
    private fun TopBar(
        onBack: () -> Unit,
        onReset: () -> Unit,
        debugOverlay: Boolean,
        onToggleDebugOverlay: () -> Unit,
        title: String,
        colors: Palette
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.topBar)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onBack,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.text)
                ) { Text("返回") }

                TextButton(
                    onClick = onReset,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.text)
                ) { Text("重置") }

                TextButton(
                    onClick = onToggleDebugOverlay,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.text)
                ) { Text(if (debugOverlay) "调试✓" else "调试") }

                Text(
                    modifier = Modifier.weight(1f),
                    text = title,
                    maxLines = 1,
                    color = colors.text
                )
            }
        }
    }

    // ----------------------------
    // Model
    // ----------------------------

    private data class DemoNode(
        val id: String,
        val name: String,
        val isDir: Boolean,
        val children: List<DemoNode> = emptyList()
    )

    private data class VisibleNode(
        val id: String,
        val name: String,
        val depth: Int,
        val isDir: Boolean,
        val hasChildren: Boolean,
        val isExpanded: Boolean
    )

    /**
     * 动画渲染行：同一个 id 在切换前后会有 fromY/toY
     * - appear: 进入列表（alpha 从 0->1）
     * - exit: 离开列表（alpha 从 1->0）
     */
    private data class RenderRow(
        val id: String,
        val name: String,
        val depth: Int,
        val isDir: Boolean,
        val hasChildren: Boolean,
        val isExpanded: Boolean,
        val fromY: Float,
        val toY: Float,
        val appear: Boolean,
        val exit: Boolean,
        val anchorY: Float // 父节点的 y（用于更“有关联感”的弹出/缩回）
    )

    // ----------------------------
    // Tree State (逻辑 + 动画)
    // ----------------------------

    private class TreeState(
        private val root: DemoNode,
        autoExpandDepth: Int
    ) {
        val expanded = mutableStateMapOf<String, Boolean>().apply {
            if (autoExpandDepth < 0) putAll(root.seedExpandedAll()) else putAll(root.seedExpanded(autoExpandDepth))
        }

        var selectedId by mutableStateOf(root.id)
            private set

        /** 逻辑可见节点（切换时会立刻变） */
        val logicalVisibleNodes: List<VisibleNode> by derivedStateOf {
            root.flattenSimple(expanded)
        }

        /** 当前动画渲染列表（切换时会变成 union：包含离开节点直到淡出） */
        var renderRows by mutableStateOf<List<RenderRow>>(emptyList())
            private set

        /** 动画进度 0..1（spring 驱动） */
        var animProgress by mutableStateOf(1f)
            private set

        /** 动画前/后列表行数，用于内容高度插值 */
        private var lastSizeBefore = 0
        private var lastSizeAfter = 0

        private var animJob: Job? = null

        init {
            // 初始直接对齐逻辑列表
            val list = logicalVisibleNodes
            lastSizeBefore = list.size
            lastSizeAfter = list.size
            renderRows = list.mapIndexed { idx, n ->
                val y = idx.toFloat()
                RenderRow(
                    id = n.id,
                    name = n.name,
                    depth = n.depth,
                    isDir = n.isDir,
                    hasChildren = n.hasChildren,
                    isExpanded = n.isExpanded,
                    fromY = y,
                    toY = y,
                    appear = false,
                    exit = false,
                    anchorY = y
                )
            }
        }

        fun select(id: String) {
            selectedId = id
        }

        fun isExpanded(id: String): Boolean = expanded[id] == true

        /**
         * 物理级展开/收起：
         * - 先拿 oldList
         * - toggle expanded
         * - newList
         * - 计算 union 每个节点 from/to
         * - spring 驱动 animProgress
         */
        fun toggleExpandAnimated(id: String, scope: kotlinx.coroutines.CoroutineScope) {
            val oldList = logicalVisibleNodes
            val oldIndex = oldList.indexOfFirst { it.id == id }
            val anchorOldY = if (oldIndex >= 0) oldIndex.toFloat() else 0f

            val cur = expanded[id] == true
            expanded[id] = !cur

            val newList = root.flattenSimple(expanded)
            val newIndex = newList.indexOfFirst { it.id == id }
            val anchorNewY = if (newIndex >= 0) newIndex.toFloat() else anchorOldY

            val oldMap = oldList.associateBy { it.id }
            val newMap = newList.associateBy { it.id }

            val oldPos = oldList.mapIndexed { idx, n -> n.id to idx }.toMap()
            val newPos = newList.mapIndexed { idx, n -> n.id to idx }.toMap()

            val unionIds = LinkedHashSet<String>(oldList.size + newList.size).apply {
                oldList.forEach { add(it.id) }
                newList.forEach { add(it.id) }
            }

            // 用“父节点作为锚点”做弹出/缩回更有“关联感”
            val anchorY = if (!cur) anchorOldY else anchorNewY

            val rows = ArrayList<RenderRow>(unionIds.size)
            for (uid in unionIds) {
                val o = oldMap[uid]
                val n = newMap[uid]
                val appear = (o == null && n != null)
                val exit = (o != null && n == null)

                val props = n ?: o!!
                val oy = oldPos[uid]?.toFloat()
                val ny = newPos[uid]?.toFloat()

                val from = when {
                    oy != null -> oy
                    // 出现：从父节点下方一点点“弹出”
                    else -> anchorY + 0.35f
                }
                val to = when {
                    ny != null -> ny
                    // 消失：缩回父节点位置附近
                    else -> anchorY + 0.35f
                }

                rows += RenderRow(
                    id = props.id,
                    name = props.name,
                    depth = props.depth,
                    isDir = props.isDir,
                    hasChildren = props.hasChildren,
                    isExpanded = props.isExpanded,
                    fromY = from,
                    toY = to,
                    appear = appear,
                    exit = exit,
                    anchorY = anchorY
                )
            }

            // 记录高度插值
            lastSizeBefore = oldList.size
            lastSizeAfter = newList.size

            // 启动 spring
            animJob?.cancel()
            animProgress = 0f
            renderRows = rows

            val progress = Animatable(0f)
            animJob = scope.launch {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        stiffness = 420f,
                        dampingRatio = 0.86f
                    )
                ) {
                    animProgress = value
                }

                // 动画结束：收敛到新列表（不再保留 exit 行）
                animProgress = 1f
                val finalList = newList
                lastSizeBefore = finalList.size
                lastSizeAfter = finalList.size
                renderRows = finalList.mapIndexed { idx, nn ->
                    val y = idx.toFloat()
                    RenderRow(
                        id = nn.id,
                        name = nn.name,
                        depth = nn.depth,
                        isDir = nn.isDir,
                        hasChildren = nn.hasChildren,
                        isExpanded = nn.isExpanded,
                        fromY = y,
                        toY = y,
                        appear = false,
                        exit = false,
                        anchorY = y
                    )
                }
            }
        }

        fun animatedContentHeightPx(style: RenderStyle): Float {
            val a = lastSizeBefore.toFloat()
            val b = lastSizeAfter.toFloat()
            val lines = a + (b - a) * animProgress.coerceIn(0f, 1f)
            return max(1f, lines) * style.rowHeightPx
        }

        fun contentWidthEstimatePx(style: RenderStyle): Float {
            var maxDepth = 0
            var maxNameLen = 0
            // 用逻辑列表估计即可（稳定）
            for (n in logicalVisibleNodes) {
                if (n.depth > maxDepth) maxDepth = n.depth
                val len = n.name.length
                if (len > maxNameLen) maxNameLen = len
            }
            val leftPadding = style.leftPaddingPx
            val iconAndGap = style.fileIconSizePx + style.iconGapPx
            val avgCharWidth = style.nodeTextSizePx * 0.62f
            return leftPadding +
                (maxDepth * style.indentPx) +
                iconAndGap +
                (maxNameLen * avgCharWidth) +
                96f
        }
    }

    // ----------------------------
    // Viewport State
    // ----------------------------

    private class ViewportState {
        var scale by mutableStateOf(1f)
        var offset by mutableStateOf(Offset.Zero)

        private var flingJob: Job? = null

        val minScale = 0.12f
        val maxScale = 8.0f

        fun stopFling() {
            flingJob?.cancel()
            flingJob = null
        }

        fun setFlingJob(job: Job?) {
            flingJob?.cancel()
            flingJob = job
        }

        fun clampOffset(
            viewportW: Float,
            viewportH: Float,
            contentW: Float,
            contentH: Float
        ) {
            val (minX, maxX, minY, maxY) = bounds(viewportW, viewportH, contentW, contentH)
            offset = Offset(
                x = offset.x.coerceIn(minX, maxX),
                y = offset.y.coerceIn(minY, maxY)
            )
        }

        fun bounds(
            viewportW: Float,
            viewportH: Float,
            contentW: Float,
            contentH: Float
        ): Bounds {
            val minX = if (contentW <= viewportW) 0f else (viewportW - contentW)
            val maxX = 0f
            val minY = if (contentH <= viewportH) 0f else (viewportH - contentH)
            val maxY = 0f
            return Bounds(minX, maxX, minY, maxY)
        }
    }

    private data class Bounds(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float)

    // ----------------------------
    // Render Style & Colors
    // ----------------------------

    private class RenderStyle(density: androidx.compose.ui.unit.Density) {
        val rowHeightPx = with(density) { 24.dp.toPx() }
        val indentPx = with(density) { 14.dp.toPx() }
        val fileIconSizePx = with(density) { 12.dp.toPx() }
        val nodeTextSizePx = with(density) { 13.sp.toPx() }

        val scrollBarThicknessPx = with(density) { 3.dp.toPx() }
        val scrollBarMinThumbPx = with(density) { 18.dp.toPx() }
        val scrollBarPaddingPx = with(density) { 4.dp.toPx() }
        val scrollBarTouchWidthPx = with(density) { 22.dp.toPx() }

        val rowCornerRadiusPx = with(density) { 4.dp.toPx() }
        val rowPaddingPx = with(density) { 6.dp.toPx() }

        val leftPaddingPx = 12f
        val iconGapPx = 8f

        // VSCode 风格：竖线放在每层 indent 的中间
        fun guideXForLevel(level: Int): Float {
            val base = leftPaddingPx + level * indentPx
            return base + indentPx * 0.5f
        }

        val lineStrokeW = 1.0f
        fun alignHalfPx(v: Float): Float = v.roundToInt().toFloat() + 0.5f
    }

    private class Palette {
        val bg = Color(0xFF0E0F12)
        val topBar = Color(0xFF15161A)
        val treeBg = Color(0xFF121319)

        val rowA = treeBg
        val rowB = treeBg

        val selection = Color(0xFF2D6CDF).copy(alpha = 0.25f)

        // 淡竖线（每层都画）
        val guide = Color(0xFF6D778A).copy(alpha = 0.38f)

        val folderBody = Color(0xFF3A4151).copy(alpha = 0.88f)
        val folderTab = Color(0xFF4A556A).copy(alpha = 0.85f)

        val text = Color(0xFFE6E9F2)
        val dimText = Color(0xFFB6BCC9)

        val scrollbar = Color(0xFFE6E9F2).copy(alpha = 0.40f)
    }

    private class PaintBundle(colors: Palette) {
        val dirPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            color = colors.text.toArgb()
        }
        val filePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
        }
        val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            color = colors.dimText.toArgb()
            textAlign = Paint.Align.RIGHT
        }
        val tmpPath = Path()
    }

    // ----------------------------
    // Gestures
    // ----------------------------

    private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTreeGestures(
        viewport: ViewportState,
        treeState: TreeState,
        style: RenderStyle,
        scope: kotlinx.coroutines.CoroutineScope
    ) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val touchSlop = viewConfiguration.touchSlop

            viewport.stopFling()

            var pressedIndex: Int? = null

            // 使用“动画内容高度”计算滚动条/边界更稳定
            val viewportW0 = size.width.toFloat()
            val viewportH0 = size.height.toFloat()
            val contentW0 = treeState.contentWidthEstimatePx(style) * viewport.scale
            val contentH0 = treeState.animatedContentHeightPx(style) * viewport.scale

            val vBarActive = contentH0 > viewportH0 + 1f && down.position.x >= (viewportW0 - style.scrollBarTouchWidthPx)
            val hBarActive = contentW0 > viewportW0 + 1f && down.position.y >= (viewportH0 - style.scrollBarTouchWidthPx)

            var scrollDragMode = when {
                vBarActive -> 1
                hBarActive -> 2
                else -> 0
            }

            fun boundsFor(currentScale: Float): Bounds {
                val contentW = treeState.contentWidthEstimatePx(style) * currentScale
                val contentH = treeState.animatedContentHeightPx(style) * currentScale
                return viewport.bounds(viewportW0, viewportH0, contentW, contentH)
            }

            fun applyVerticalScrollbar(pointerY: Float) {
                val viewportH = size.height.toFloat()
                val contentH = treeState.animatedContentHeightPx(style) * viewport.scale
                if (contentH <= viewportH + 1f) return

                val trackH = viewportH - style.scrollBarPaddingPx * 2f
                val thumbH = max(style.scrollBarMinThumbPx, trackH * (viewportH / contentH))
                val scrollRange = contentH - viewportH
                val denom = max(1f, (trackH - thumbH))

                val t = ((pointerY - style.scrollBarPaddingPx - thumbH * 0.5f) / denom).coerceIn(0f, 1f)
                val scrollY = t * scrollRange

                val b = boundsFor(viewport.scale)
                viewport.offset = viewport.offset.copy(y = (-scrollY).coerceIn(b.minY, b.maxY))
            }

            fun applyHorizontalScrollbar(pointerX: Float) {
                val viewportW = size.width.toFloat()
                val contentW = treeState.contentWidthEstimatePx(style) * viewport.scale
                if (contentW <= viewportW + 1f) return

                val trackW = viewportW - style.scrollBarPaddingPx * 2f
                val thumbW = max(style.scrollBarMinThumbPx, trackW * (viewportW / contentW))
                val scrollRange = contentW - viewportW
                val denom = max(1f, (trackW - thumbW))

                val t = ((pointerX - style.scrollBarPaddingPx - thumbW * 0.5f) / denom).coerceIn(0f, 1f)
                val scrollX = t * scrollRange

                val b = boundsFor(viewport.scale)
                viewport.offset = viewport.offset.copy(x = (-scrollX).coerceIn(b.minX, b.maxX))
            }

            if (scrollDragMode == 0) {
                val worldDown = (down.position - viewport.offset) / viewport.scale
                if (worldDown.y >= 0f) {
                    val idx = floor(worldDown.y / style.rowHeightPx).toInt()
                    pressedIndex = idx
                }
            }

            var maxPressedPointers = 1
            var totalPan = Offset.Zero
            var totalZoom = 1f
            val velocityTracker = VelocityTracker()

            var axisLockY = false
            var lastPressedCount = 1

            var lastEvent = awaitPointerEvent()
            while (lastEvent.changes.any { it.pressed }) {
                val pressedCount = lastEvent.changes.count { it.pressed }
                if (pressedCount > maxPressedPointers) maxPressedPointers = pressedCount

                if (scrollDragMode != 0 && pressedCount == 1) {
                    val primary = lastEvent.changes.firstOrNull { it.pressed } ?: break
                    if (scrollDragMode == 1) applyVerticalScrollbar(primary.position.y)
                    if (scrollDragMode == 2) applyHorizontalScrollbar(primary.position.x)
                    lastEvent.changes.forEach { it.consume() }
                    lastPressedCount = pressedCount
                    lastEvent = awaitPointerEvent()
                    continue
                } else if (pressedCount > 1) {
                    scrollDragMode = 0
                    pressedIndex = null
                }

                val zoomRaw = lastEvent.calculateZoom()
                val panRaw = lastEvent.calculatePan()
                val centroid = lastEvent.calculateCentroid()

                val zoom = if (pressedCount != lastPressedCount) 1f else zoomRaw
                var pan = if (pressedCount != lastPressedCount) Offset.Zero else panRaw

                totalZoom *= zoomRaw
                totalPan += panRaw

                if (totalPan.getDistance() >= touchSlop) pressedIndex = null

                if (pressedCount == 1 && !axisLockY && totalPan.getDistance() > touchSlop) {
                    val ax = abs(totalPan.x)
                    val ay = abs(totalPan.y)
                    axisLockY = ay > ax * 3.0f
                }
                if (pressedCount == 1 && axisLockY) pan = Offset(0f, pan.y)

                val oldScale = viewport.scale
                val newScale = (oldScale * zoom).coerceIn(viewport.minScale, viewport.maxScale)
                val scaleFactor = if (oldScale == 0f) 1f else (newScale / oldScale)

                val nextOffset = viewport.offset * scaleFactor + centroid * (1f - scaleFactor) + pan

                val contentW = treeState.contentWidthEstimatePx(style) * newScale
                val contentH = treeState.animatedContentHeightPx(style) * newScale
                val b = viewport.bounds(size.width.toFloat(), size.height.toFloat(), contentW, contentH)

                viewport.scale = newScale
                viewport.offset = Offset(
                    x = nextOffset.x.coerceIn(b.minX, b.maxX),
                    y = nextOffset.y.coerceIn(b.minY, b.maxY)
                )

                if (pressedCount == 1) {
                    val primary = lastEvent.changes.firstOrNull { it.pressed }
                    if (primary != null) velocityTracker.addPosition(primary.uptimeMillis, primary.position)
                }

                lastEvent.changes.forEach { it.consume() }
                lastPressedCount = pressedCount
                lastEvent = awaitPointerEvent()
            }

            val treatAsTap =
                maxPressedPointers == 1 &&
                    (totalPan.getDistance() < touchSlop) &&
                    (abs(totalZoom - 1f) < 0.02f)

            if (scrollDragMode != 0) {
                if (scrollDragMode == 1) applyVerticalScrollbar(down.position.y)
                if (scrollDragMode == 2) applyHorizontalScrollbar(down.position.x)
                return@awaitEachGesture
            }

            if (treatAsTap) {
                val viewportW = size.width.toFloat()
                val viewportH = size.height.toFloat()
                val contentW = treeState.contentWidthEstimatePx(style) * viewport.scale
                val contentH = treeState.animatedContentHeightPx(style) * viewport.scale

                val onVBar = contentH > viewportH + 1f && down.position.x >= (viewportW - style.scrollBarTouchWidthPx)
                val onHBar = contentW > viewportW + 1f && down.position.y >= (viewportH - style.scrollBarTouchWidthPx)
                if (onVBar) { applyVerticalScrollbar(down.position.y); return@awaitEachGesture }
                if (onHBar) { applyHorizontalScrollbar(down.position.x); return@awaitEachGesture }

                val world = (down.position - viewport.offset) / viewport.scale
                if (world.y < 0f) return@awaitEachGesture

                val idx = pressedIndex ?: floor(world.y / style.rowHeightPx).toInt()
                val node = treeState.logicalVisibleNodes.getOrNull(idx) ?: return@awaitEachGesture

                // ✅ 文件夹：用物理动画展开/收起
                if (node.isDir && node.hasChildren) {
                    treeState.toggleExpandAnimated(node.id, scope)
                }

                treeState.select(node.id)

                // 状态变化后 clamp（用动画内容高度）
                val contentW2 = treeState.contentWidthEstimatePx(style) * viewport.scale
                val contentH2 = treeState.animatedContentHeightPx(style) * viewport.scale
                viewport.clampOffset(viewportW, viewportH, contentW2, contentH2)
                return@awaitEachGesture
            }

            // fling：只在明显拖动时触发
            if (maxPressedPointers == 1 && totalPan.getDistance() >= touchSlop) {
                val v = velocityTracker.calculateVelocity()
                val initialVelocity = Offset(v.x, v.y)

                val viewportW = size.width.toFloat()
                val viewportH = size.height.toFloat()

                val contentW = treeState.contentWidthEstimatePx(style) * viewport.scale
                val contentH = treeState.animatedContentHeightPx(style) * viewport.scale
                val b = viewport.bounds(viewportW, viewportH, contentW, contentH)

                viewport.setFlingJob(
                    scope.launch {
                        var vel = initialVelocity
                        var pos = viewport.offset
                        var lastFrameNs = 0L
                        val frictionPerSecond = 5.7f

                        while (isActive) {
                            val frameNs = withFrameNanos { it }
                            if (lastFrameNs == 0L) { lastFrameNs = frameNs; continue }
                            val dt = (frameNs - lastFrameNs) / 1_000_000_000f
                            lastFrameNs = frameNs
                            if (dt <= 0f) continue

                            pos += vel * dt

                            if (pos.x > b.maxX) { pos = pos.copy(x = b.maxX); vel = vel.copy(x = 0f) }
                            if (pos.x < b.minX) { pos = pos.copy(x = b.minX); vel = vel.copy(x = 0f) }
                            if (pos.y > b.maxY) { pos = pos.copy(y = b.maxY); vel = vel.copy(y = 0f) }
                            if (pos.y < b.minY) { pos = pos.copy(y = b.minY); vel = vel.copy(y = 0f) }

                            val decay = exp(-frictionPerSecond * dt)
                            vel = Offset(vel.x * decay, vel.y * decay)

                            viewport.offset = pos

                            if (hypot(vel.x.toDouble(), vel.y.toDouble()) < 22.0) break
                        }

                        val target = Offset(
                            x = viewport.offset.x.coerceIn(b.minX, b.maxX),
                            y = viewport.offset.y.coerceIn(b.minY, b.maxY)
                        )

                        if (target != viewport.offset) {
                            val spec = spring<Float>(
                                stiffness = Spring.StiffnessLow,
                                dampingRatio = 0.86f
                            )
                            var x = viewport.offset.x
                            var y = viewport.offset.y
                            val ax = Animatable(x)
                            val ay = Animatable(y)

                            coroutineScope {
                                launch {
                                    ax.animateTo(target.x, spec) {
                                        x = value
                                        viewport.offset = Offset(x, y)
                                    }
                                }
                                launch {
                                    ay.animateTo(target.y, spec) {
                                        y = value
                                        viewport.offset = Offset(x, y)
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    // ----------------------------
    // Rendering
    // ----------------------------

    private fun DrawScope.renderTree(
        viewport: ViewportState,
        treeState: TreeState,
        style: RenderStyle,
        colors: Palette,
        paints: PaintBundle,
        debugOverlay: Boolean
    ) {
        val viewportW = size.width
        val viewportH = size.height

        val contentH = treeState.animatedContentHeightPx(style) * viewport.scale
        val contentW = treeState.contentWidthEstimatePx(style) * viewport.scale

        drawRect(colors.treeBg)

        clipRect {
            // 可见行范围（用“当前渲染列表”的 y 插值来判定，避免只按 index）
            val worldTopY = ((0f - viewport.offset.y) / viewport.scale)
            val worldBottomY = ((viewportH - viewport.offset.y) / viewport.scale)

            // 粗略索引范围：用逻辑列表兜底 + 额外余量
            val approxStart = max(0, floor(worldTopY / style.rowHeightPx).toInt() - 8)
            val approxEnd = ceil(worldBottomY / style.rowHeightPx).toInt() + 8

            val rowHScreen = style.rowHeightPx * viewport.scale
            val rowCorner = CornerRadius(style.rowCornerRadiusPx, style.rowCornerRadiusPx)

            val progress = treeState.animProgress.coerceIn(0f, 1f)

            // 先画条纹背景（按 screen y 来画）
            // 这里用 approxStart..approxEnd 扫一遍就够，避免空白闪烁
            run {
                val start = approxStart
                val end = max(start, approxEnd)
                for (i in start until end) {
                    val top = i * style.rowHeightPx * viewport.scale + viewport.offset.y
                    val bottom = top + rowHScreen
                    if (bottom < 0f || top > viewportH) continue
                    drawRect(
                        color = if ((i and 1) == 0) colors.rowA else colors.rowB,
                        topLeft = Offset(0f, top),
                        size = Size(viewportW, rowHScreen)
                    )
                }
            }

            // ✅ 竖向层级线：每层都画（按 depth）
            // 注意：退出节点会渐隐
            val lineStroke = style.lineStrokeW
            for (row in treeState.renderRows) {
                val yWorld = lerp(row.fromY, row.toY, progress) * style.rowHeightPx
                val top = yWorld * viewport.scale + viewport.offset.y
                val bottom = top + rowHScreen
                if (bottom < 0f || top > viewportH) continue

                val alpha = when {
                    row.appear -> progress
                    row.exit -> (1f - progress)
                    else -> 1f
                }.coerceIn(0f, 1f)

                if (row.depth <= 0 || alpha <= 0.001f) continue

                // “每层都画”：0..depth-1
                for (level in 0 until row.depth) {
                    val xWorld = style.guideXForLevel(level)
                    val x = style.alignHalfPx(xWorld * viewport.scale + viewport.offset.x)
                    drawLine(
                        color = colors.guide.copy(alpha = colors.guide.alpha * alpha),
                        start = Offset(x, style.alignHalfPx(top)),
                        end = Offset(x, style.alignHalfPx(bottom)),
                        strokeWidth = lineStroke
                    )
                }
            }

            // 选中背景（按渲染 y 画，避免切换时“飘”）
            for (row in treeState.renderRows) {
                if (row.id != treeState.selectedId) continue
                val yWorld = lerp(row.fromY, row.toY, progress) * style.rowHeightPx
                val top = yWorld * viewport.scale + viewport.offset.y
                val bottom = top + rowHScreen
                if (bottom < 0f || top > viewportH) continue

                val alpha = when {
                    row.appear -> progress
                    row.exit -> (1f - progress)
                    else -> 1f
                }.coerceIn(0f, 1f)

                drawRoundRect(
                    color = colors.selection.copy(alpha = colors.selection.alpha * alpha),
                    topLeft = Offset(style.rowPaddingPx, top),
                    size = Size(viewportW - style.rowPaddingPx * 2, rowHScreen),
                    cornerRadius = rowCorner
                )
            }

            // 文本 + 图标：world space
            withTransform({
                translate(viewport.offset.x, viewport.offset.y)
                scale(viewport.scale, viewport.scale, pivot = Offset.Zero)
            }) {
                paints.dirPaint.textSize = style.nodeTextSizePx
                paints.filePaint.textSize = style.nodeTextSizePx

                for (row in treeState.renderRows) {
                    val alpha = when {
                        row.appear -> progress
                        row.exit -> (1f - progress)
                        else -> 1f
                    }.coerceIn(0f, 1f)

                    if (alpha <= 0.001f) continue

                    // 物理感：轻微“挤压/弹性”——展开时靠近锚点会更紧密一点
                    // 让子节点看起来像从父节点下方弹出来（不是纯线性移动）
                    val t = progress
                    val yIdx = lerp(row.fromY, row.toY, t)
                    val y = yIdx * style.rowHeightPx
                    val centerY = y + style.rowHeightPx * 0.5f

                    val baseX = style.leftPaddingPx + row.depth * style.indentPx

                    // icon
                    val iconX = baseX
                    val iconTop = centerY - style.fileIconSizePx * 0.5f
                    if (row.isDir) {
                        drawFolderIcon(
                            x = iconX,
                            top = iconTop,
                            size = style.fileIconSizePx,
                            open = row.isExpanded,
                            body = Color(
                                red = colors.folderBody.red,
                                green = colors.folderBody.green,
                                blue = colors.folderBody.blue,
                                alpha = colors.folderBody.alpha * alpha
                            ),
                            tab = Color(
                                red = colors.folderTab.red,
                                green = colors.folderTab.green,
                                blue = colors.folderTab.blue,
                                alpha = colors.folderTab.alpha * alpha
                            )
                        )
                    } else {
                        val accent = FileTypeHighlight.accentForFileName(row.name).copy(alpha = 0.92f * alpha)
                        val markerW = max(2f, style.fileIconSizePx * 0.18f)
                        val markerH = style.fileIconSizePx * 0.72f
                        val markerX = iconX + (style.fileIconSizePx - markerW) * 0.5f
                        drawRoundRect(
                            color = accent,
                            topLeft = Offset(markerX, centerY - markerH * 0.5f),
                            size = Size(markerW, markerH),
                            cornerRadius = CornerRadius(markerW, markerW)
                        )
                    }

                    // text
                    val textX = iconX + style.fileIconSizePx + style.iconGapPx
                    val baseline = y + style.rowHeightPx * 0.72f

                    val p = if (row.isDir) paints.dirPaint else paints.filePaint
                    if (!row.isDir) {
                        p.color = FileTypeHighlight.accentForFileName(row.name).copy(alpha = 0.92f * alpha).toArgb()
                    } else {
                        p.color = colors.text.copy(alpha = colors.text.alpha * alpha).toArgb()
                    }

                    drawContext.canvas.nativeCanvas.drawText(row.name, textX, baseline, p)

                    if (debugOverlay) {
                        paints.debugPaint.textSize = style.nodeTextSizePx * 0.82f
                        paints.debugPaint.color = colors.dimText.copy(alpha = colors.dimText.alpha * alpha).toArgb()
                        val dbg = "d=${row.depth} a=${"%.2f".format(alpha)} y=${"%.2f".format(yIdx)}"
                        drawContext.canvas.nativeCanvas.drawText(dbg, 400f, baseline, paints.debugPaint)
                    }
                }
            }

            drawScrollbars(
                viewportW = viewportW,
                viewportH = viewportH,
                contentW = contentW,
                contentH = contentH,
                offset = viewport.offset,
                style = style,
                color = colors.scrollbar
            )
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun DrawScope.drawScrollbars(
        viewportW: Float,
        viewportH: Float,
        contentW: Float,
        contentH: Float,
        offset: Offset,
        style: RenderStyle,
        color: Color
    ) {
        val barThickness = style.scrollBarThicknessPx
        val barPadding = style.scrollBarPaddingPx
        val minThumb = style.scrollBarMinThumbPx
        val radius = CornerRadius(barThickness * 0.5f, barThickness * 0.5f)

        if (contentH > viewportH + 1f) {
            val trackH = viewportH - barPadding * 2f
            val thumbH = max(minThumb, trackH * (viewportH / contentH))
            val scrollRange = contentH - viewportH
            val scrollY = (-offset.y).coerceIn(0f, scrollRange)
            val t = if (scrollRange <= 0f) 0f else (scrollY / scrollRange)
            val thumbY = barPadding + (trackH - thumbH) * t

            drawRoundRect(
                color = color,
                topLeft = Offset(viewportW - barPadding - barThickness, thumbY),
                size = Size(barThickness, thumbH),
                cornerRadius = radius
            )
        }

        if (contentW > viewportW + 1f) {
            val trackW = viewportW - barPadding * 2f
            val thumbW = max(minThumb, trackW * (viewportW / contentW))
            val scrollRange = contentW - viewportW
            val scrollX = (-offset.x).coerceIn(0f, scrollRange)
            val t = if (scrollRange <= 0f) 0f else (scrollX / scrollRange)
            val thumbX = barPadding + (trackW - thumbW) * t

            drawRoundRect(
                color = color,
                topLeft = Offset(thumbX, viewportH - barPadding - barThickness),
                size = Size(thumbW, barThickness),
                cornerRadius = radius
            )
        }
    }

    // ----------------------------
    // Flatten (逻辑列表)
    // ----------------------------

    private fun DemoNode.flattenSimple(expanded: Map<String, Boolean>): List<VisibleNode> {
        val out = ArrayList<VisibleNode>(512)

        fun addNode(node: DemoNode, depth: Int) {
            val open = expanded[node.id] == true
            out += VisibleNode(
                id = node.id,
                name = node.name,
                depth = depth,
                isDir = node.isDir,
                hasChildren = node.children.isNotEmpty(),
                isExpanded = open
            )
            if (node.isDir && open) {
                for (c in node.children) addNode(c, depth + 1)
            }
        }

        addNode(this, 0)
        return out
    }

    // ----------------------------
    // Icons & Highlights
    // ----------------------------

    private fun DrawScope.drawFolderIcon(
        x: Float,
        top: Float,
        size: Float,
        open: Boolean,
        body: Color,
        tab: Color
    ) {
        val w = size
        val h = size
        val tabH = h * 0.26f
        val tabW = w * 0.58f
        val r = CornerRadius(w * 0.14f, w * 0.14f)
        val tabR = CornerRadius(w * 0.12f, w * 0.12f)
        val bodyTop = top + h * 0.20f
        val outline = Color.White.copy(alpha = 0.12f * body.alpha.coerceIn(0f, 1f))
        val outlineW = max(1f, w * 0.08f)

        val tabLeft = x + w * 0.10f
        val tabTop = top + h * 0.04f
        drawRoundRect(
            color = tab,
            topLeft = Offset(tabLeft, tabTop),
            size = Size(tabW, tabH),
            cornerRadius = tabR
        )
        drawRoundRect(
            color = outline,
            topLeft = Offset(tabLeft, tabTop),
            size = Size(tabW, tabH),
            cornerRadius = tabR,
            style = Stroke(width = outlineW)
        )

        val bodyColor = if (open) body.copy(alpha = min(1f, body.alpha + 0.06f)) else body
        drawRoundRect(
            color = bodyColor,
            topLeft = Offset(x, bodyTop),
            size = Size(w, h - (bodyTop - top)),
            cornerRadius = r
        )
        drawRoundRect(
            color = outline,
            topLeft = Offset(x, bodyTop),
            size = Size(w, h - (bodyTop - top)),
            cornerRadius = r,
            style = Stroke(width = outlineW)
        )
    }

    private object FileTypeHighlight {
        fun accentForFileName(name: String): Color {
            val ext = name.substringAfterLast('.', "")
            return when (ext.lowercase()) {
                "kt" -> Color(0xFF7C3AED)
                "java" -> Color(0xFFEF4444)
                "xml" -> Color(0xFF22C55E)
                "json" -> Color(0xFF06B6D4)
                "md" -> Color(0xFFF59E0B)
                "gradle" -> Color(0xFF10B981)
                "yml", "yaml" -> Color(0xFF3B82F6)
                "png", "jpg", "jpeg", "webp" -> Color(0xFFE879F9)
                "txt", "properties" -> Color(0xFF9CA3AF)
                else -> Color(0xFF60A5FA)
            }
        }
    }

    // ----------------------------
    // Demo Data
    // ----------------------------

    private object DemoProject {
        fun basicProject(): DemoNode {
            fun file(parentId: String, name: String): DemoNode =
                DemoNode(id = "$parentId/$name", name = name, isDir = false)

            fun dir(parentId: String, name: String, children: List<DemoNode>): DemoNode =
                DemoNode(id = "$parentId/$name", name = name, isDir = true, children = children)

            val rootId = "root"

            val app = dir(
                parentId = rootId,
                name = "app",
                children = listOf(
                    file("$rootId/app", "build.gradle"),
                    dir(
                        parentId = "$rootId/app",
                        name = "src",
                        children = listOf(
                            dir(
                                parentId = "$rootId/app/src",
                                name = "main",
                                children = listOf(
                                    file("$rootId/app/src/main", "AndroidManifest.xml"),
                                    dir(
                                        parentId = "$rootId/app/src/main",
                                        name = "java",
                                        children = listOf(
                                            dir(
                                                parentId = "$rootId/app/src/main/java",
                                                name = "com",
                                                children = listOf(
                                                    dir(
                                                        parentId = "$rootId/app/src/main/java/com",
                                                        name = "termux",
                                                        children = listOf(
                                                            dir(
                                                                parentId = "$rootId/app/src/main/java/com/termux",
                                                                name = "demo",
                                                                children = listOf(
                                                                    file("$rootId/app/src/main/java/com/termux/demo", "MainActivity.kt"),
                                                                    dir(
                                                                        parentId = "$rootId/app/src/main/java/com/termux/demo",
                                                                        name = "ui",
                                                                        children = listOf(
                                                                            file("$rootId/app/src/main/java/com/termux/demo/ui", "HomeScreen.kt"),
                                                                            dir(
                                                                                parentId = "$rootId/app/src/main/java/com/termux/demo/ui",
                                                                                name = "components",
                                                                                children = listOf(
                                                                                    file("$rootId/app/src/main/java/com/termux/demo/ui/components", "Button.kt")
                                                                                )
                                                                            )
                                                                        )
                                                                    )
                                                                )
                                                            )
                                                        )
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )

            val gradle = dir(
                parentId = rootId,
                name = "gradle",
                children = listOf(
                    dir(
                        parentId = "$rootId/gradle",
                        name = "wrapper",
                        children = listOf(
                            file("$rootId/gradle/wrapper", "gradle-wrapper.properties")
                        )
                    )
                )
            )

            val rootChildren = listOf(
                app,
                gradle,
                file(rootId, "settings.gradle"),
                file(rootId, "build.gradle"),
                file(rootId, "README.md"),
                file(rootId, ".gitignore")
            )

            return DemoNode(id = rootId, name = "root", isDir = true, children = rootChildren)
        }
    }

    // ----------------------------
    // Helpers
    // ----------------------------

    private data class DemoStats(val dirCount: Int, val fileCount: Int, val maxDepth: Int)

    private fun DemoNode.stats(): DemoStats {
        var dirs = 0
        var files = 0
        var maxDepth = 0
        val stack = ArrayDeque<Pair<DemoNode, Int>>()
        stack.addLast(this to 0)
        while (stack.isNotEmpty()) {
            val (n, d) = stack.removeLast()
            if (d > maxDepth) maxDepth = d
            if (n.isDir) dirs++ else files++
            for (c in n.children) stack.addLast(c to (d + 1))
        }
        return DemoStats(dirCount = dirs, fileCount = files, maxDepth = maxDepth)
    }

    private fun DemoNode.seedExpanded(maxAutoExpandDepth: Int): Map<String, Boolean> {
        val maxD = maxAutoExpandDepth.coerceIn(0, 10)
        val out = LinkedHashMap<String, Boolean>(128)
        val stack = ArrayDeque<Pair<DemoNode, Int>>()
        stack.addLast(this to 0)
        while (stack.isNotEmpty()) {
            val (n, d) = stack.removeLast()
            if (!n.isDir) continue
            if (d <= maxD) out[n.id] = true
            if (d >= maxD) continue
            for (c in n.children.asReversed()) stack.addLast(c to (d + 1))
        }
        return out
    }

    private fun DemoNode.seedExpandedAll(): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>(128)
        val stack = ArrayDeque<DemoNode>()
        stack.addLast(this)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (!n.isDir) continue
            out[n.id] = true
            for (c in n.children.asReversed()) stack.addLast(c)
        }
        return out
    }
}
