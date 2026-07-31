package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.termux.terminal.GhosttyRenderDelta;
import com.termux.terminal.TerminalEmulator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Retained, dirty-row consumer for Ghostty's production render state. */
final class GhosttyRenderNodeRenderer {

    private static final String LOG_TAG = "TermuxRenderV2";
    /** Keep retained row commands across reversals and signed fractional overscan. */
    private static final int RETAINED_COMMAND_CACHE_SCREENS = 3;
    /** Retain one extreme small-font viewport across metric changes without rebuilding list storage. */
    private static final int MAX_ROW_POOL_ENTRIES = 512;
    private static final long METRICS_LOG_INTERVAL_MS = 3000L;
    private static final int MAX_COMMAND_POOL_ENTRIES = 8192;
    /** Shape caches only when a packet is small enough to amortize their main-thread cost. */
    private static final int MAX_EAGER_GLYPH_ROWS_PER_PACKET = 8;
    private static final int MAX_EAGER_GLYPH_RUNS_PER_PACKET = 32;
    private static final int GLYPH_SHAPE_CACHE_SIZE = 1024;
    private static final int GLYPH_SHAPE_CACHE_PROBES = 4;
    /** A glyph submission has setup cost; short runs are cheaper through Canvas.drawText(). */
    private static final int MIN_GLYPH_COUNT_FOR_FAST_PATH = 8;
    /** Bound post-pinch work to the visible viewport and amortize it over animation callbacks. */
    private static final int MAX_SCALE_GLYPH_WARM_COMMANDS = 256;
    private static final int UTF8_HAS_NON_SPACE = 1;
    private static final int UTF8_PRINTABLE_ASCII = 1 << 1;
    private static final int UNDERLINE_SHIFT = 12;
    private static final int WIDE_SHIFT = 16;
    private static final int CELL_FOREGROUND = 0;
    private static final int CELL_BACKGROUND = 1;
    private static final int CELL_UNDERLINE = 2;
    private static final int CELL_FLAGS = 3;
    private static final int CELL_TEXT_OFFSET = 4;
    private static final int CELL_TEXT_LENGTH = 5;
    private static final int CELL_RECORD_INTS = GhosttyRenderDelta.CELL_RECORD_INTS;

    private final TerminalRenderer metrics;
    private final int rendererId = System.identityHashCode(this);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Rect clipScratch = new Rect();
    private final TerminalRenderDamageTracker damageTracker = new TerminalRenderDamageTracker();
    private final LinkedHashMap<Integer, RowDisplay> rowCache =
        new LinkedHashMap<>(64, 0.75f, true);
    private final ArrayDeque<RowDisplay> rowPool = new ArrayDeque<>(64);
    private final ArrayDeque<RectCommand> rectCommandPool = new ArrayDeque<>(256);
    private final ArrayDeque<TextCommand> textCommandPool = new ArrayDeque<>(256);
    private final ArrayList<TextCommand> scaleGlyphWarmCommands = new ArrayList<>(128);
    private final FrameDrawStats frameDrawStats = new FrameDrawStats();
    private final GlyphShapeCache glyphShapeCache =
        new GlyphShapeCache(GLYPH_SHAPE_CACHE_SIZE, GLYPH_SHAPE_CACHE_PROBES);
    // Keep the API 33 Canvas.drawGlyphs path behind an API-neutral interface. The retained
    // renderer itself runs on every supported Android version, so it must never directly invoke
    // an API-33-only method merely because a nullable implementation happens to be present.
    private final GlyphBatch glyphBatch = createGlyphBatch();
    private int scaleGlyphWarmIndex;
    private int scaleGlyphWarmPrepared;
    private long retainedCommandGeneration;
    private long glyphWarmCommandGeneration = Long.MIN_VALUE;
    private int[] cellScratch = new int[0];
    private byte[] rowUtf8Scratch = new byte[0];
    private int[] utf8CharIndexScratch = new int[0];

    private boolean initialized;
    private boolean fullFrameRequested = true;
    private boolean prewarmedFramePendingPresentation;
    private int cachedColumns;
    private int cachedRows;
    private int cachedTopRow;
    private int cachedBackground;
    private long cachedScrollbackRows;
    private float lastPixelOffset;
    private long cachedModelRevision = Long.MIN_VALUE;
    private int selectionY1 = Integer.MIN_VALUE;
    private int selectionY2 = Integer.MIN_VALUE;
    private int selectionX1 = Integer.MIN_VALUE;
    private int selectionX2 = Integer.MIN_VALUE;
    private boolean cachedHostCursorVisible;
    private int cachedCursorRow = -1;
    private int cachedCursorColumn = -1;
    private int cachedCursorStyle = -1;
    private boolean cachedCursorEnabled;
    private boolean cachedCursorVisible;
    private boolean cachedAlternateScreen;
    private boolean cachedInlinePrimaryScreen;
    private boolean glyphFastPathEnabled = true;
    private boolean glyphBatchingHealthy = true;
    private boolean realtimeScaleActive;
    private static volatile boolean sActivationLogged;
    private boolean failureLogged;

    private long packetCount;
    private long fullPacketCount;
    private long fullFrameRequests = 1;
    private long fullFrameCompletions;
    private long nativeChangedRows;
    private long decodedRows;
    private long retainedRows;
    private long semanticRowsCompared;
    private long semanticRowsReused;
    private long semanticRowsCaptured;
    private long nativeSemanticRowsCompared;
    private long nativeSemanticRowsSuppressed;
    private long gpuRowSnapshotsBuilt;
    private long gpuRowSnapshotsReused;
    private long viewportRebuilds;
    private long viewportPartialPackets;
    private long viewportFullRetries;
    private long viewportCacheHits;
    private long overscanCacheHits;
    private long nativePacketSkips;
    private long renderNanos;
    private long packetPipelineCalls;
    private long packetPipelineNanos;
    private long maxPacketPipelineNanos;
    private long packetApplyCalls;
    private long packetApplyNanos;
    private long maxPacketApplyNanos;
    private long rowBuildNanos;
    private long maxRowBuildBatchNanos;
    private long canvasFrameCount;
    private long canvasDrawNanos;
    private long maxCanvasDrawNanos;
    private long allocatedRows;
    private long reusedRows;
    private long inPlaceRowRebuilds;
    private long recycledRows;
    private long allocatedRectCommands;
    private long reusedRectCommands;
    private long allocatedTextCommands;
    private long reusedTextCommands;
    private long directCanvasRowDraws;
    private long rowUtf8Decodes;
    private long rowUtf8Bytes;
    private long rowStringDecodes;
    private long asciiRows;
    private long unicodeRows;
    private long blankRows;
    private long textRuns;
    private long asciiFastRuns;
    private long measuredUnicodeRuns;
    private long blankTextRunsSkipped;
    private long shapedTextRuns;
    private long shapedGlyphs;
    private long glyphShapeFailures;
    private long glyphShapeCacheHits;
    private long glyphShapeCacheMisses;
    private long glyphShapeCacheEvictions;
    private long glyphShapeCacheRestoredGlyphs;
    private long glyphCachePackets;
    private long glyphCacheBypassPackets;
    private long glyphCacheViewportBypassPackets;
    private long glyphCacheScaleBypassPackets;
    private long glyphCacheBypassedRuns;
    private long glyphCanvasDraws;
    private long stringCanvasDraws;
    private long glyphBatchDrawCalls;
    private long glyphBatchedCommands;
    private long glyphBatchedGlyphs;
    private long glyphBatchFallbackFrames;
    private long glyphShapeNanos;
    private long scaleGlyphWarmFrames;
    private long scaleGlyphWarmCandidates;
    private long scaleGlyphWarmPreparedRuns;
    private long scaleGlyphWarmNanos;
    private long lastMetricsLogMs;
    private long lastPrewarmedPresentationLogMs;

    GhosttyRenderNodeRenderer(TerminalRenderer metrics) {
        this.metrics = metrics;
        updatePaintMetrics();
        lastMetricsLogMs = SystemClock.uptimeMillis();
    }

    private static GlyphBatch createGlyphBatch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null;
        return createApi33GlyphBatch();
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static GlyphBatch createApi33GlyphBatch() {
        return new Api33GlyphBatch();
    }

    void dispose() {
        realtimeScaleActive = false;
        if (glyphBatch != null) glyphBatch.abort();
        resetRetainedState();
        rowPool.clear();
        rectCommandPool.clear();
        textCommandPool.clear();
    }

    void onMetricsChanged() {
        updatePaintMetrics();
        resetRetainedState();
    }

    void resetForSession() {
        resetRetainedState();
    }

    void setRealtimeScaleActive(boolean active) {
        realtimeScaleActive = active;
        if (active) cancelScaleGlyphWarmup();
    }

    private void updatePaintMetrics() {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setSubpixelText(true);
        paint.setTypeface(metrics.mTypeface);
        paint.setTextSize(metrics.mTextSize);
    }

    private void resetRetainedState() {
        cancelScaleGlyphWarmup();
        retainedCommandGeneration++;
        glyphShapeCache.clear();
        recycleAllRows();
        initialized = false;
        prewarmedFramePendingPresentation = false;
        if (!fullFrameRequested) fullFrameRequests++;
        fullFrameRequested = true;
        cachedColumns = 0;
        cachedRows = 0;
        cachedTopRow = 0;
        cachedModelRevision = Long.MIN_VALUE;
        selectionY1 = Integer.MIN_VALUE;
        selectionY2 = Integer.MIN_VALUE;
        selectionX1 = Integer.MIN_VALUE;
        selectionX2 = Integer.MIN_VALUE;
        cachedHostCursorVisible = false;
        cachedCursorRow = -1;
        cachedCursorColumn = -1;
        cachedCursorStyle = -1;
        cachedCursorEnabled = false;
        cachedCursorVisible = false;
        cachedAlternateScreen = false;
        cachedInlinePrimaryScreen = false;
        lastPixelOffset = 0f;
    }

    void requestFullFrame() {
        if (!fullFrameRequested) fullFrameRequests++;
        fullFrameRequested = true;
    }

    void beginDamageCapture() {
        damageTracker.begin();
    }

    void finishDamageCapture(boolean success) {
        damageTracker.finish(success);
    }

    boolean isPreparedDamageFull() {
        return damageTracker.isFull();
    }

    int getPreparedDamageStart() {
        return damageTracker.start();
    }

    int getPreparedDamageEnd() {
        return damageTracker.end();
    }

    boolean hasCompleteFrame(int topRow, int rows, long modelRevision) {
        return initialized && !fullFrameRequested && cachedModelRevision == modelRevision &&
            matchesViewport(topRow, rows);
    }

    boolean hasCompleteFrame(int topRow, int rows) {
        return initialized && !fullFrameRequested && matchesViewport(topRow, rows);
    }

    boolean hasCompleteFrame() {
        return initialized && !fullFrameRequested;
    }

    /** The current model is prepared; test the one row needed for fractional movement. */
    boolean hasRetainedOverscanRow(int topRow, float pixelOffset) {
        if (!initialized || fullFrameRequested || cachedRows <= 0 ||
            Math.abs(pixelOffset) < 0.01f) {
            return false;
        }
        int logicalRow = pixelOffset < 0f ? topRow - 1 : topRow + cachedRows;
        boolean retained = rowCache.containsKey(logicalRow);
        if (retained) overscanCacheHits++;
        return retained;
    }

    /** Bottommost retained row that would draw pixels, in viewport row coordinates. */
    int findLastVisualScreenRow(int topRow, int firstScreenRow, int rows) {
        if (!initialized || fullFrameRequested || rows <= 0 ||
            !matchesViewport(topRow, rows)) return -1;
        int first = Math.max(0, Math.min(rows - 1, firstScreenRow));
        for (int screenRow = rows - 1; screenRow >= first; screenRow--) {
            RowDisplay display = rowCache.get(topRow + screenRow);
            if (display != null && display.hasVisualContent()) return screenRow;
        }
        return -1;
    }

    /**
     * Bottommost row with terminal semantic content, excluding a plain background fill.
     *
     * <p>IME avoidance must protect a TUI footer or tmux status text, but treating a themed empty
     * background as content would pan a sparse shell offscreen. Decorations count because a cursor
     * or underline is visible terminal state.</p>
     */
    int findLastSemanticScreenRow(int topRow, int firstScreenRow, int rows) {
        if (!initialized || fullFrameRequested || rows <= 0 ||
            !matchesViewport(topRow, rows)) return -1;
        int first = Math.max(0, Math.min(rows - 1, firstScreenRow));
        for (int screenRow = rows - 1; screenRow >= first; screenRow--) {
            RowDisplay display = rowCache.get(topRow + screenRow);
            if (display != null && display.hasSemanticContent()) return screenRow;
        }
        return -1;
    }

    /**
     * Build the retained row display lists without waiting for this view to receive an onDraw.
     * ViewPager deliberately clips adjacent pages, so relying on their invalidation to prewarm a
     * terminal leaves the newly selected page black until its first visible traversal.
     */
    boolean prewarm(TerminalEmulator emulator, int topRow, boolean forceFull,
                    int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final long started = System.nanoTime();
        final long modelRevision = emulator.getContentRevision();
        cachedAlternateScreen = emulator.isAlternateBufferActive();
        cachedInlinePrimaryScreen = TerminalTuiResizePolicy.isInlinePrimaryScreen(
            cachedAlternateScreen, emulator.isMouseTrackingActive(),
            emulator.shouldSendFocusEvents(), emulator.isCursorKeysApplicationMode(),
            emulator.isKeypadApplicationMode(), emulator.isCursorEnabled());
        final boolean hostCursorVisible = emulator.shouldCursorBeVisible();
        final boolean selectionChanged = this.selectionY1 != selectionY1 ||
            this.selectionY2 != selectionY2 || this.selectionX1 != selectionX1 ||
            this.selectionX2 != selectionX2;
        final boolean cursorVisibilityChanged = initialized &&
            cachedHostCursorVisible != hostCursorVisible;
        // A changed viewport is deliberately not a full-frame condition. libghostty-vt emits the
        // newly exposed rows and keeps the overlap in this retained cache. applyDelta() validates
        // the resulting viewport coverage and the fallback below requests a full packet if that
        // contract is ever not met.
        final boolean requiresFullFrame = forceFull || fullFrameRequested || !initialized ||
            selectionChanged || cursorVisibilityChanged;
        try {
            if (!requiresFullFrame && cachedModelRevision == modelRevision &&
                matchesPreparedViewport(topRow, emulator.mRows)) {
                cachedTopRow = topRow;
                viewportCacheHits++;
                nativePacketSkips++;
                prewarmedFramePendingPresentation = true;
                renderNanos += System.nanoTime() - started;
                maybeLogMetrics();
                return true;
            }
            long pipelineStarted = System.nanoTime();
            Boolean applied = emulator.decodeGhosttyRenderDelta(topRow, requiresFullFrame, delta ->
                applyDelta(delta, hostCursorVisible,
                    selectionY1, selectionY2, selectionX1, selectionX2));
            recordPacketPipeline(System.nanoTime() - pipelineStarted);
            if (!Boolean.TRUE.equals(applied) && !requiresFullFrame) {
                viewportFullRetries++;
                pipelineStarted = System.nanoTime();
                applied = emulator.decodeGhosttyRenderDelta(topRow, true, delta ->
                    applyDelta(delta, hostCursorVisible,
                        selectionY1, selectionY2, selectionX1, selectionX2));
                recordPacketPipeline(System.nanoTime() - pipelineStarted);
            }
            if (!Boolean.TRUE.equals(applied)) return false;

            fullFrameRequested = false;
            prewarmedFramePendingPresentation = true;
            if (requiresFullFrame) fullFrameCompletions++;
            renderNanos += System.nanoTime() - started;
            if (requiresFullFrame) {
                Log.i(LOG_TAG, "full-frame-prewarmed renderer=" + rendererId + " top=" + cachedTopRow + " grid=" +
                    cachedColumns + 'x' + cachedRows + " scrollback=" + cachedScrollbackRows +
                    " request=" + fullFrameCompletions + '/' + fullFrameRequests);
            }
            maybeLogMetrics();
            return true;
        } catch (RuntimeException | LinkageError | OutOfMemoryError error) {
            if (!failureLogged) {
                failureLogged = true;
                Log.e(LOG_TAG, "Ghostty retained renderer prewarm failed", error);
            }
            return false;
        }
    }

    boolean requestRetiredRowRelease() {
        // Row commands are plain Java data drawn synchronously into the parent Canvas. They own no
        // RenderNode or GPU resource, so replacement recycles them immediately on the UI thread.
        return false;
    }

    void releaseRetiredRows() {
        // Kept as a compatibility hook for existing diagnostics.
    }

    long getDecodedRowsForTesting() {
        return decodedRows;
    }

    long getRetainedRowsForTesting() {
        return retainedRows;
    }

    long getViewportPartialPacketsForTesting() {
        return viewportPartialPackets;
    }

    long getViewportFullRetriesForTesting() {
        return viewportFullRetries;
    }

    long getViewportCacheHitsForTesting() {
        return viewportCacheHits;
    }

    long getCachedModelRevision() {
        return cachedModelRevision;
    }

    long getRetainedCommandGeneration() {
        return retainedCommandGeneration;
    }

    /**
     * Export only rows that the GPU consumer cannot already have. The export is a deep copy of the
     * retained command objects, so pooled rows can continue to be rebuilt on the UI thread after
     * this method returns.
     */
    TerminalGpuFrame buildGpuFrame(long frameId, int viewWidth, int viewHeight,
                                   int viewportTopRow, float viewportPixelOffset,
                                   long consumedCommandGeneration, int consumedTopRow,
                                   boolean forceFull) {
        if (!initialized || fullFrameRequested || cachedRows <= 0) {
            return TerminalGpuFrame.incomplete(frameId, viewWidth, viewHeight, viewportTopRow);
        }

        int visibleRows = cachedRows;
        boolean jumpOutsideRetention = consumedTopRow == Integer.MIN_VALUE ||
            Math.abs((long) viewportTopRow - consumedTopRow) >= visibleRows;
        boolean full = forceFull || consumedCommandGeneration == Long.MIN_VALUE || jumpOutsideRetention;
        int firstLogicalRow = viewportTopRow;
        int lastLogicalRow = viewportTopRow + visibleRows;
        ArrayList<TerminalGpuFrame.Row> exported = new ArrayList<>(full ? visibleRows : 8);

        float effectivePixelOffset = viewportPixelOffset;
        int oldestTopRow = (int) Math.max(Integer.MIN_VALUE + 1L,
            -Math.max(0L, cachedScrollbackRows));
        if ((effectivePixelOffset < -0.01f && viewportTopRow <= oldestTopRow) ||
            (effectivePixelOffset > 0.01f && viewportTopRow >= 0)) {
            effectivePixelOffset = 0f;
        }
        int overscanRow = effectivePixelOffset < -0.01f
            ? viewportTopRow - 1 : viewportTopRow + visibleRows;
        int requiredFirst = Math.min(firstLogicalRow, overscanRow);
        int requiredLast = Math.max(lastLogicalRow, overscanRow + 1);
        for (int logicalRow = requiredFirst; logicalRow < requiredLast; logicalRow++) {
            RowDisplay display = rowCache.get(logicalRow);
            if (display == null) continue;
            boolean inViewport = logicalRow >= firstLogicalRow && logicalRow < lastLogicalRow;
            boolean newlyExposed = logicalRow < consumedTopRow ||
                logicalRow >= consumedTopRow + visibleRows;
            if (!full && !newlyExposed && display.commandGeneration <= consumedCommandGeneration) {
                continue;
            }
            if (!inViewport && logicalRow != overscanRow) continue;
            exported.add(snapshotGpuRow(logicalRow, display));
        }

        boolean complete = true;
        for (int logicalRow = firstLogicalRow; logicalRow < lastLogicalRow; logicalRow++) {
            if (!rowCache.containsKey(logicalRow)) {
                complete = false;
                break;
            }
        }
        if (Math.abs(effectivePixelOffset) > 0.01f && !rowCache.containsKey(overscanRow)) {
            complete = false;
        }
        if (!complete) {
            return TerminalGpuFrame.incomplete(frameId, viewWidth, viewHeight, viewportTopRow);
        }

        int semanticTail = cachedCursorRow;
        if (cachedCursorRow >= 0 && cachedCursorRow < cachedRows) {
            semanticTail = findLastSemanticScreenRow(
                viewportTopRow, cachedCursorRow, cachedRows);
        }
        int imeProtectedBottomScreenRow =
            TerminalImeSemanticEnvelope.resolveProtectedBottomScreenRow(
                cachedAlternateScreen, cachedInlinePrimaryScreen, cachedCursorRow,
                semanticTail, cachedRows);

        return new TerminalGpuFrame(
            frameId,
            retainedCommandGeneration,
            cachedModelRevision,
            Math.max(1, viewWidth),
            Math.max(1, viewHeight),
            metrics.mTextSize,
            metrics.mTypeface,
            metrics.mFontWidth,
            metrics.mFontLineSpacing,
            metrics.mFontAscent,
            cachedBackground,
            cachedRows,
            viewportTopRow,
            effectivePixelOffset,
            full,
            true,
            exported,
            cachedCursorRow,
            cachedCursorColumn,
            cachedCursorStyle,
            cachedCursorEnabled,
            cachedCursorVisible,
            imeProtectedBottomScreenRow
        );
    }

    private TerminalGpuFrame.Row snapshotGpuRow(int logicalRow, RowDisplay display) {
        if (display.gpuSnapshot != null && display.gpuSnapshot.logicalRow == logicalRow) {
            gpuRowSnapshotsReused++;
            return display.gpuSnapshot;
        }
        float[] backgroundBounds = display.backgrounds.copyBounds();
        int[] backgroundColors = display.backgrounds.copyColors();
        ArrayList<TerminalGpuFrame.TextRun> text = new ArrayList<>(display.text.size());
        for (TextCommand command : display.text) {
            if (command.value == null || command.valueEnd <= command.valueStart) continue;
            text.add(new TerminalGpuFrame.TextRun(command.value, command.valueStart,
                command.valueEnd, command.left, command.width, command.measuredWidth,
                command.color, command.bold, command.italic));
        }
        int decorationCount = display.decorations.size();
        float[] decorationBounds = new float[decorationCount * 4];
        int[] decorationColors = new int[decorationCount];
        for (int index = 0; index < decorationCount; index++) {
            RectCommand command = display.decorations.get(index);
            int offset = index * 4;
            decorationBounds[offset] = command.left;
            decorationBounds[offset + 1] = command.top;
            decorationBounds[offset + 2] = command.right;
            decorationBounds[offset + 3] = command.bottom;
            decorationColors[index] = command.color;
        }
        display.gpuSnapshot = TerminalGpuFrame.Row.fromOwnedCommands(logicalRow,
            backgroundBounds, backgroundColors, text, decorationBounds, decorationColors);
        gpuRowSnapshotsBuilt++;
        return display.gpuSnapshot;
    }

    void setGlyphFastPathEnabledForTesting(boolean enabled) {
        glyphFastPathEnabled = enabled;
    }

    long getShapedTextRunsForTesting() {
        return shapedTextRuns;
    }

    long getShapedGlyphsForTesting() {
        return shapedGlyphs;
    }

    long getGlyphShapeFailuresForTesting() {
        return glyphShapeFailures;
    }

    long getGlyphCanvasDrawsForTesting() {
        return glyphCanvasDraws;
    }

    long getGlyphBatchDrawCallsForTesting() {
        return glyphBatchDrawCalls;
    }

    long getGlyphBatchedCommandsForTesting() {
        return glyphBatchedCommands;
    }

    long getGlyphBatchedGlyphsForTesting() {
        return glyphBatchedGlyphs;
    }

    long getGlyphBatchFallbackFramesForTesting() {
        return glyphBatchFallbackFrames;
    }

    /**
     * Deterministically prepare the production-safe glyph subset for the hardware differential
     * probe. Production packet decoding deliberately skips eager shaping for full/high-churn
     * packets, so the probe must not depend on packet size to exercise Canvas.drawGlyphs().
     */
    int prepareRetainedGlyphsForTesting() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return 0;
        int preparedRuns = 0;
        for (RowDisplay display : rowCache.values()) {
            for (TextCommand command : display.text) {
                if (command.glyphCount > 0 || command.bold || command.italic ||
                    command.value == null ||
                    command.valueEnd - command.valueStart < MIN_GLYPH_COUNT_FOR_FAST_PATH ||
                    !isPrintableAscii(command.value, command.valueStart, command.valueEnd)) {
                    continue;
                }
                if (prepareGlyphCommand(command)) {
                    preparedRuns++;
                }
            }
        }
        return preparedRuns;
    }

    /** Capture only the current complete viewport after the final real pinch-reflow frame. */
    int beginScaleGlyphWarmup() {
        cancelScaleGlyphWarmup();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || realtimeScaleActive ||
            !initialized || fullFrameRequested || !matchesViewport(cachedTopRow, cachedRows)) {
            return 0;
        }
        for (int row = 0; row < cachedRows &&
                scaleGlyphWarmCommands.size() < MAX_SCALE_GLYPH_WARM_COMMANDS; row++) {
            RowDisplay display = rowCache.get(cachedTopRow + row);
            if (display == null) {
                cancelScaleGlyphWarmup();
                return 0;
            }
            for (TextCommand command : display.text) {
                if (isGlyphCacheEligible(command) && command.glyphCount == 0) {
                    scaleGlyphWarmCommands.add(command);
                    if (scaleGlyphWarmCommands.size() == MAX_SCALE_GLYPH_WARM_COMMANDS) break;
                }
            }
        }
        scaleGlyphWarmCandidates += scaleGlyphWarmCommands.size();
        glyphWarmCommandGeneration = retainedCommandGeneration;
        return scaleGlyphWarmCommands.size();
    }

    /** Prepare at most {@code maxRuns}; return the number of captured commands still pending. */
    int warmScaleGlyphCache(int maxRuns) {
        if (maxRuns <= 0 || realtimeScaleActive || scaleGlyphWarmCommands.isEmpty()) return 0;
        if (glyphWarmCommandGeneration != retainedCommandGeneration) {
            cancelScaleGlyphWarmup();
            return 0;
        }
        long started = System.nanoTime();
        int attempted = 0;
        while (scaleGlyphWarmIndex < scaleGlyphWarmCommands.size() && attempted < maxRuns) {
            TextCommand command = scaleGlyphWarmCommands.get(scaleGlyphWarmIndex++);
            if (command.glyphCount > 0 || !isGlyphCacheEligible(command)) continue;
            attempted++;
            if (prepareGlyphCommand(command)) {
                scaleGlyphWarmPrepared++;
                scaleGlyphWarmPreparedRuns++;
            }
        }
        scaleGlyphWarmFrames++;
        scaleGlyphWarmNanos += System.nanoTime() - started;
        int remaining = scaleGlyphWarmCommands.size() - scaleGlyphWarmIndex;
        if (remaining == 0) scaleGlyphWarmCommands.clear();
        return remaining;
    }

    int getScaleGlyphWarmPrepared() {
        return scaleGlyphWarmPrepared;
    }

    private void cancelScaleGlyphWarmup() {
        scaleGlyphWarmCommands.clear();
        scaleGlyphWarmIndex = 0;
        scaleGlyphWarmPrepared = 0;
        glyphWarmCommandGeneration = Long.MIN_VALUE;
    }

    private static boolean isGlyphCacheEligible(TextCommand command) {
        return command != null && command.value != null && !command.bold && !command.italic &&
            command.valueEnd - command.valueStart >= MIN_GLYPH_COUNT_FOR_FAST_PATH &&
            isPrintableAscii(command.value, command.valueStart, command.valueEnd);
    }

    private boolean prepareGlyphCommand(TextCommand command) {
        paint.setFakeBoldText(false);
        paint.setTextSkewX(0f);
        if (glyphShapeCache.restore(command)) {
            glyphShapeCacheHits++;
            glyphShapeCacheRestoredGlyphs += command.glyphCount;
            return true;
        }
        glyphShapeCacheMisses++;
        long shapeStart = System.nanoTime();
        int glyphCount = command.prepareGlyphs(paint);
        glyphShapeNanos += System.nanoTime() - shapeStart;
        if (glyphCount > 0) {
            shapedTextRuns++;
            shapedGlyphs += glyphCount;
            if (glyphShapeCache.store(command)) glyphShapeCacheEvictions++;
            return true;
        }
        glyphShapeFailures++;
        return false;
    }

    String getDiagnostics() {
        return "renderer=" + rendererId + " initialized=" + initialized +
            " fullPending=" + fullFrameRequested + " cacheTop=" + cachedTopRow +
            " cacheRows=" + cachedRows + " cacheColumns=" + cachedColumns +
            " cacheSize=" + rowCache.size() + " offset=" + Math.round(lastPixelOffset) +
            " scaleActive=" + realtimeScaleActive +
            " stateRevision=" + cachedModelRevision + " commandGeneration=" +
            retainedCommandGeneration + " viewportHits=" +
            viewportCacheHits + " overscanHits=" + overscanCacheHits +
            " packetSkips=" + nativePacketSkips +
            " semanticRows=" + semanticRowsCompared + '/' + semanticRowsReused + '/' +
            semanticRowsCaptured + " nativeSemantic=" + nativeSemanticRowsCompared + '/' +
            nativeSemanticRowsSuppressed + " gpuRows=" + gpuRowSnapshotsBuilt + '/' +
            gpuRowSnapshotsReused +
            " glyphBatchHealthy=" + glyphBatchingHealthy + " glyphBatchCalls=" +
            glyphBatchDrawCalls + " glyphBatchedCommands=" + glyphBatchedCommands +
            " glyphBatchedGlyphs=" + glyphBatchedGlyphs + " glyphBatchFallbacks=" +
            glyphBatchFallbackFrames + " glyphShapeCache=" + glyphShapeCacheHits + '/' +
            glyphShapeCacheMisses + '/' + glyphShapeCacheEvictions +
            " pipelineUs=" + averageMicros(packetPipelineNanos, packetPipelineCalls) + '/' +
            nanosToMicros(maxPacketPipelineNanos) + " applyUs=" +
            averageMicros(packetApplyNanos, packetApplyCalls) + '/' +
            nanosToMicros(maxPacketApplyNanos) + " rowUs=" +
            averageMicros(rowBuildNanos, decodedRows) + '/' +
            nanosToMicros(maxRowBuildBatchNanos) + " canvasUs=" +
            averageMicros(canvasDrawNanos, canvasFrameCount) + '/' +
            nanosToMicros(maxCanvasDrawNanos);
    }

    boolean render(TerminalEmulator emulator, Canvas canvas, int topRow, float pixelOffset,
                   int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final long started = System.nanoTime();
        final long modelRevision = emulator.getContentRevision();
        final boolean hostCursorVisible = emulator.shouldCursorBeVisible();
        final boolean selectionChanged = this.selectionY1 != selectionY1 ||
            this.selectionY2 != selectionY2 || this.selectionX1 != selectionX1 ||
            this.selectionX2 != selectionX2;
        final boolean cursorVisibilityChanged = initialized &&
            cachedHostCursorVisible != hostCursorVisible;
        final boolean completingRequestedFullFrame = fullFrameRequested;
        final boolean forceFull = fullFrameRequested || !initialized || selectionChanged ||
            cursorVisibilityChanged;

        try {
            boolean reusedViewport = !forceFull && cachedModelRevision == modelRevision &&
                matchesPreparedViewport(topRow, emulator.mRows);
            if (reusedViewport) {
                cachedTopRow = topRow;
                viewportCacheHits++;
                nativePacketSkips++;
            } else {
                long pipelineStarted = System.nanoTime();
                Boolean applied = emulator.decodeGhosttyRenderDelta(topRow, forceFull, delta ->
                    applyDelta(delta, hostCursorVisible,
                        selectionY1, selectionY2, selectionX1, selectionX2));
                recordPacketPipeline(System.nanoTime() - pipelineStarted);
                if (!Boolean.TRUE.equals(applied) && !forceFull) {
                    viewportFullRetries++;
                    pipelineStarted = System.nanoTime();
                    applied = emulator.decodeGhosttyRenderDelta(topRow, true, delta ->
                        applyDelta(delta, hostCursorVisible,
                            selectionY1, selectionY2, selectionX1, selectionX2));
                    recordPacketPipeline(System.nanoTime() - pipelineStarted);
                }
                if (!Boolean.TRUE.equals(applied)) return false;
            }

            if (!canvas.getClipBounds(clipScratch) || clipScratch.isEmpty()) return true;
            long canvasStarted = System.nanoTime();
            canvas.drawColor(cachedBackground, PorterDuff.Mode.SRC);
            boolean hasBottomOverscan = pixelOffset > 0.001f &&
                rowCache.containsKey(topRow + cachedRows);
            boolean hasTopOverscan = pixelOffset < -0.001f &&
                rowCache.containsKey(topRow - 1);
            float effectivePixelOffset = hasBottomOverscan || hasTopOverscan ? pixelOffset : 0f;
            lastPixelOffset = effectivePixelOffset;
            int firstRow = hasTopOverscan ? -1 : 0;
            int lastRowExclusive = cachedRows + (hasBottomOverscan ? 1 : 0);
            int clippedFirstRow = (int) Math.floor(
                (clipScratch.top - metrics.mFontLineSpacingAndAscent + effectivePixelOffset) /
                    metrics.mFontLineSpacing);
            int clippedLastRow = (int) Math.ceil(
                (clipScratch.bottom - metrics.mFontLineSpacingAndAscent + effectivePixelOffset) /
                    metrics.mFontLineSpacing);
            firstRow = Math.max(firstRow, clippedFirstRow);
            lastRowExclusive = Math.min(lastRowExclusive, clippedLastRow);
            int fontAscent = (int) Math.ceil(paint.ascent());
            boolean useOrderedGlyphBatch = glyphBatchingHealthy && glyphFastPathEnabled &&
                glyphBatch != null && canvas.isHardwareAccelerated();
            boolean rowsDrawn;
            try {
                rowsDrawn = drawVisibleRows(canvas, topRow, effectivePixelOffset,
                    firstRow, lastRowExclusive, fontAscent, useOrderedGlyphBatch,
                    glyphFastPathEnabled, frameDrawStats);
            } catch (GlyphBatchFailure failure) {
                glyphBatchingHealthy = false;
                glyphBatchFallbackFrames++;
                if (glyphBatch != null) glyphBatch.abort();
                Log.e(LOG_TAG, "ordered glyph batch failed; redrawing frame with String path",
                    failure.getCause());
                canvas.drawColor(cachedBackground, PorterDuff.Mode.SRC);
                rowsDrawn = drawVisibleRows(canvas, topRow, effectivePixelOffset,
                    firstRow, lastRowExclusive, fontAscent, false, false, frameDrawStats);
            }
            if (!rowsDrawn) {
                recordCanvasDraw(System.nanoTime() - canvasStarted);
                return false;
            }
            glyphCanvasDraws += frameDrawStats.glyphCommands;
            stringCanvasDraws += frameDrawStats.stringCommands;
            directCanvasRowDraws += frameDrawStats.rows;
            glyphBatchDrawCalls += frameDrawStats.batchDrawCalls;
            glyphBatchedCommands += frameDrawStats.batchedCommands;
            glyphBatchedGlyphs += frameDrawStats.batchedGlyphs;
            recordCanvasDraw(System.nanoTime() - canvasStarted);
            if (prewarmedFramePendingPresentation) {
                prewarmedFramePendingPresentation = false;
                long now = SystemClock.uptimeMillis();
                if (now - lastPrewarmedPresentationLogMs >= METRICS_LOG_INTERVAL_MS) {
                    lastPrewarmedPresentationLogMs = now;
                    Log.i(LOG_TAG, "prewarmed-frame-presented renderer=" + rendererId +
                        " top=" + cachedTopRow + " grid=" + cachedColumns + 'x' + cachedRows +
                        " scrollback=" + cachedScrollbackRows);
                }
            }
            renderNanos += System.nanoTime() - started;
            fullFrameRequested = false;
            if (completingRequestedFullFrame) {
                fullFrameCompletions++;
                Log.i(LOG_TAG, "full-frame-ready renderer=" + rendererId + " top=" + cachedTopRow + " grid=" +
                    cachedColumns + 'x' + cachedRows + " scrollback=" + cachedScrollbackRows +
                    " request=" + fullFrameCompletions + '/' + fullFrameRequests);
            }
            maybeLogMetrics();
            return true;
        } catch (RuntimeException | LinkageError | OutOfMemoryError error) {
            if (!failureLogged) {
                failureLogged = true;
                Log.e(LOG_TAG, "Ghostty retained renderer failed", error);
            }
            return false;
        }
    }

    private boolean drawVisibleRows(Canvas canvas, int topRow, float pixelOffset,
                                    int firstRow, int lastRowExclusive, int fontAscent,
                                    boolean useOrderedGlyphBatch, boolean allowGlyphFastPath,
                                    FrameDrawStats stats) {
        stats.reset();
        GlyphBatch batch = useOrderedGlyphBatch ? glyphBatch : null;
        if (batch != null) batch.begin(canvas, paint);
        try {
            for (int row = firstRow; row < lastRowExclusive; row++) {
                RowDisplay display = rowCache.get(topRow + row);
                if (display == null) {
                    if (batch != null) batch.abort();
                    return false;
                }
                float y = metrics.mFontLineSpacingAndAscent +
                    row * metrics.mFontLineSpacing - pixelOffset;
                int glyphCommands = display.draw(canvas, paint, y, fontAscent,
                    allowGlyphFastPath, batch);
                stats.glyphCommands += glyphCommands;
                stats.stringCommands += display.text.size() - glyphCommands;
                stats.rows++;
            }
            if (batch != null) {
                batch.finish();
                stats.batchDrawCalls = batch.getDrawCalls();
                stats.batchedCommands = batch.getBatchedCommands();
                stats.batchedGlyphs = batch.getBatchedGlyphs();
            }
            return true;
        } catch (RuntimeException | LinkageError error) {
            if (batch != null) batch.abort();
            throw error;
        }
    }

    private boolean applyDelta(GhosttyRenderDelta delta, boolean hostCursorVisible,
                               int selectionY1, int selectionY2,
                               int selectionX1, int selectionX2) {
        final long applyStarted = System.nanoTime();
        boolean gridChanged = !initialized || cachedColumns != delta.columns ||
            cachedRows != delta.rows;
        boolean selectionChanged = this.selectionY1 != selectionY1 ||
            this.selectionY2 != selectionY2 || this.selectionX1 != selectionX1 ||
            this.selectionX2 != selectionX2;
        boolean viewportChanged = initialized && delta.topRow != cachedTopRow;
        boolean backgroundChanged = initialized && cachedBackground != delta.backgroundColor;
        if (delta.fullFrame && delta.changedRowCount != delta.rows) {
            throw new IllegalStateException("Incomplete Ghostty full frame: changed=" +
                delta.changedRowCount + " rows=" + delta.rows + " top=" + delta.topRow);
        }
        if (gridChanged || selectionChanged) {
            retireAllRows();
        }

        if (delta.fullFrame || gridChanged || selectionChanged || viewportChanged ||
            backgroundChanged) {
            damageTracker.markFull();
        } else {
            for (int row = 0; row < delta.rows; row++) {
                if (delta.hasRow(row)) damageTracker.markRow(row);
            }
        }

        ensureScratch(delta.columns);
        int cursorColumn = delta.cursorWideTail
            ? Math.max(0, delta.cursorColumn - 1) : delta.cursorColumn;
        int cursorWidth = delta.cursorWideTail ? 2 : 1;
        if (delta.cursorVisible && delta.cursorRow >= 0 && delta.cursorRow < delta.rows &&
            cursorColumn >= 0 && cursorColumn < delta.columns &&
            delta.hasRow(delta.cursorRow) &&
            delta.wideAt(delta.cursorRow, cursorColumn) == GhosttyRenderDelta.WIDE_WIDE) {
            cursorWidth = 2;
        }
        boolean drawCursor = delta.cursorVisible && hostCursorVisible;

        long decodedThisPacket = 0;
        long retainedThisPacket = 0;
        long packetCommandGeneration = retainedCommandGeneration +
            (delta.changedRowCount > 0 ? 1L : 0L);
        int glyphPreparationBudget = shouldPrepareGlyphCache(
            delta.changedRowCount, delta.rows, viewportChanged, realtimeScaleActive) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? MAX_EAGER_GLYPH_RUNS_PER_PACKET : 0;
        if (glyphPreparationBudget > 0) glyphCachePackets++;
        else if (delta.changedRowCount > 0) {
            glyphCacheBypassPackets++;
            if (viewportChanged) glyphCacheViewportBypassPackets++;
            if (realtimeScaleActive) glyphCacheScaleBypassPackets++;
        }
        long rowBuildStarted = System.nanoTime();
        for (int row = 0; row < delta.rows; row++) {
            int logicalRow = delta.topRow + row;
            if (!delta.hasRow(row)) {
                if (rowCache.containsKey(logicalRow)) retainedThisPacket++;
                continue;
            }
            boolean selectionRowActive = selectionX1 >= 0 && selectionX2 >= 0 &&
                selectionY2 >= selectionY1 && logicalRow >= selectionY1 &&
                logicalRow <= selectionY2;
            int selectionStart = selectionRowActive && logicalRow == selectionY1
                ? selectionX1 : 0;
            int selectionEnd = selectionRowActive && logicalRow == selectionY2
                ? selectionX2 : delta.columns;
            boolean cursorRowActive = drawCursor && row == delta.cursorRow;
            int rowTextBytes = loadRowScratch(delta, row);
            int rowTextStart = delta.rowUtf8Offset(row);
            RowDisplay display = rowCache.get(logicalRow);
            if (display != null) {
                semanticRowsCompared++;
                if (display.matchesSource(delta.columns, delta.backgroundColor,
                    selectionRowActive, selectionStart, selectionEnd,
                    cursorRowActive, cursorRowActive ? cursorColumn : 0,
                    cursorRowActive ? cursorColumn + cursorWidth : 0,
                    cursorRowActive ? delta.cursorStyle : 0,
                    cursorRowActive ? delta.cursorColor : 0,
                    cellScratch, rowTextStart,
                    rowUtf8Scratch, rowTextBytes)) {
                    semanticRowsReused++;
                    retainedThisPacket++;
                    continue;
                }
                // Rebuild retained commands in the existing row object. Drawing remains direct on
                // the terminal parent Canvas; nested row RenderNodes previously lost middle rows.
                display.reset(rectCommandPool, textCommandPool,
                    maxRectCommandPoolEntries(), maxTextCommandPoolEntries());
                inPlaceRowRebuilds++;
            } else {
                display = obtainRowDisplay();
            }
            display.ensureCapacity(delta.columns);
            display.captureSource(delta.columns, delta.backgroundColor,
                selectionRowActive, selectionStart, selectionEnd,
                cursorRowActive, cursorRowActive ? cursorColumn : 0,
                cursorRowActive ? cursorColumn + cursorWidth : 0,
                cursorRowActive ? delta.cursorStyle : 0,
                cursorRowActive ? delta.cursorColor : 0,
                cellScratch, rowTextStart,
                rowUtf8Scratch, rowTextBytes);
            semanticRowsCaptured++;
            try {
                glyphPreparationBudget = buildRow(display, delta, row,
                    cursorRowActive, cursorColumn, cursorWidth,
                    selectionRowActive, selectionStart, selectionEnd,
                    glyphPreparationBudget, rowTextBytes, rowTextStart);
            } catch (RuntimeException | LinkageError | OutOfMemoryError error) {
                display.sourceValid = false;
                throw error;
            }
            rowCache.put(logicalRow, display);
            display.commandGeneration = packetCommandGeneration;
            decodedThisPacket++;
        }
        long rowBuildElapsed = System.nanoTime() - rowBuildStarted;
        rowBuildNanos += rowBuildElapsed;
        maxRowBuildBatchNanos = Math.max(maxRowBuildBatchNanos, rowBuildElapsed);

        cachedColumns = delta.columns;
        cachedRows = delta.rows;
        cachedTopRow = delta.topRow;
        cachedBackground = delta.backgroundColor;
        cachedScrollbackRows = delta.scrollbackRows;
        cachedModelRevision = delta.stateGeneration;
        cachedHostCursorVisible = hostCursorVisible;
        cachedCursorRow = delta.cursorRow;
        cachedCursorColumn = cursorColumn;
        cachedCursorStyle = delta.cursorStyle;
        cachedCursorEnabled = delta.cursorVisible;
        cachedCursorVisible = drawCursor;
        this.selectionY1 = selectionY1;
        this.selectionY2 = selectionY2;
        this.selectionX1 = selectionX1;
        this.selectionX2 = selectionX2;
        initialized = true;
        trimCache(Math.max(delta.rows + 2,
            delta.rows * RETAINED_COMMAND_CACHE_SCREENS));

        for (int row = 0; row < delta.rows; row++) {
            if (!rowCache.containsKey(delta.topRow + row)) {
                recordPacketApply(System.nanoTime() - applyStarted);
                return false;
            }
        }

        packetCount++;
        if (delta.fullFrame) fullPacketCount++;
        if (viewportChanged && !delta.fullFrame) viewportPartialPackets++;
        nativeChangedRows += delta.changedRowCount;
        nativeSemanticRowsCompared += delta.semanticCandidateRows;
        nativeSemanticRowsSuppressed += delta.semanticSuppressedRows;
        decodedRows += decodedThisPacket;
        retainedRows += retainedThisPacket;
        if (decodedThisPacket > 0) retainedCommandGeneration = packetCommandGeneration;
        if (viewportChanged) viewportRebuilds++;
        if (!sActivationLogged) {
            sActivationLogged = true;
            Log.i(LOG_TAG, "retained-v2 active authority=ghostty transport=dirty-row " +
                "displayList=parent-canvas-retained-rows nestedRenderNodes=false grid=" +
                delta.columns + 'x' +
                delta.rows);
        }
        recordPacketApply(System.nanoTime() - applyStarted);
        return true;
    }

    private int loadRowScratch(GhosttyRenderDelta source, int row) {
        int rowTextBytes = source.copyRowRecordsAndGetUtf8Length(row, cellScratch);
        if (rowTextBytes > 0) {
            ensureUtf8Scratch(rowTextBytes);
            source.copyUtf8Range(row, source.rowUtf8Offset(row), rowTextBytes, rowUtf8Scratch);
            rowUtf8Decodes++;
            rowUtf8Bytes += rowTextBytes;
        }
        return rowTextBytes;
    }

    private int buildRow(RowDisplay display, GhosttyRenderDelta source,
                         int row, boolean cursorRowActive,
                         int cursorColumn, int cursorWidth,
                         boolean selectionRowActive, int selectionStart, int selectionEnd,
                         int glyphPreparationBudget, int rowTextBytes, int rowTextStart) {
        RectCommandBatch backgrounds = display.backgrounds;
        List<TextCommand> text = display.text;
        List<RectCommand> decorations = display.decorations;
        int columns = source.columns;
        int cursorStart = cursorColumn;
        int cursorEnd = cursorColumn + cursorWidth;

        String rowText = "";
        boolean rowAscii = false;
        if (rowTextBytes > 0) {
            int utf8Class = classifyUtf8(rowUtf8Scratch, 0, rowTextBytes);
            if ((utf8Class & UTF8_HAS_NON_SPACE) != 0) {
                rowText = new String(rowUtf8Scratch, 0, rowTextBytes,
                    StandardCharsets.UTF_8);
                rowStringDecodes++;
                rowAscii = (utf8Class & UTF8_PRINTABLE_ASCII) != 0;
                if (rowAscii) {
                    asciiRows++;
                } else {
                    buildUtf8CharIndex(rowUtf8Scratch, rowTextBytes,
                        utf8CharIndexScratch);
                    unicodeRows++;
                }
            } else {
                blankRows++;
            }
        }
        int backgroundStart = 0;
        int backgroundColor = source.backgroundColor;
        for (int column = 0; column < columns; column++) {
            int cell = cellOffset(column);
            int flags = cellScratch[cell + CELL_FLAGS];
            int foreground = cellScratch[cell + CELL_FOREGROUND];
            int background = cellScratch[cell + CELL_BACKGROUND];
            boolean selected = selectionRowActive && column >= selectionStart &&
                column <= selectionEnd;
            boolean cursor = cursorRowActive && column >= cursorStart && column < cursorEnd;
            boolean blockCursor = cursor && source.cursorStyle == 1;
            boolean invert = (flags & GhosttyRenderDelta.CELL_INVERSE) != 0;
            if (invert ^ (selected || blockCursor)) {
                int swap = foreground;
                foreground = background;
                background = swap;
            }
            if ((flags & GhosttyRenderDelta.CELL_FAINT) != 0) {
                foreground = dimColor(foreground);
            }
            if (blockCursor) background = source.cursorColor;

            cellScratch[cell + CELL_FOREGROUND] = foreground;
            cellScratch[cell + CELL_BACKGROUND] = background;
            if (column == 0) {
                backgroundColor = background;
            } else if (background != backgroundColor) {
                if (backgroundColor != source.backgroundColor) {
                    backgrounds.add(
                        backgroundStart * metrics.mFontWidth, 0f,
                        column * metrics.mFontWidth, metrics.mFontLineSpacing,
                        backgroundColor);
                }
                backgroundStart = column;
                backgroundColor = background;
            }
        }
        if (backgroundColor != source.backgroundColor) {
            backgrounds.add(
                backgroundStart * metrics.mFontWidth, 0f,
                columns * metrics.mFontWidth, metrics.mFontLineSpacing,
                backgroundColor);
        }

        int lineThickness = Math.max(1, Math.round(metrics.mTextSize / 16f));
        if (cursorRowActive && source.cursorStyle != 1 &&
            cursorColumn >= 0 && cursorColumn < columns) {
            addCursorDecorations(decorations, cursorColumn, cursorWidth,
                lineThickness, source.cursorStyle, source.cursorColor, columns);
        }

        int column = 0;
        while (column < columns) {
            int cell = cellOffset(column);
            int textLength = cellScratch[cell + CELL_TEXT_LENGTH];
            int flags = cellScratch[cell + CELL_FLAGS];
            int wide = wideState(flags);
            if (textLength <= 0 || wide == GhosttyRenderDelta.WIDE_SPACER_TAIL ||
                wide == GhosttyRenderDelta.WIDE_SPACER_HEAD ||
                (flags & GhosttyRenderDelta.CELL_INVISIBLE) != 0) {
                column++;
                continue;
            }

            int startColumn = column;
            int textOffset = cellScratch[cell + CELL_TEXT_OFFSET];
            int totalTextLength = textLength;
            int foreground = cellScratch[cell + CELL_FOREGROUND];
            int underlineColor = cellScratch[cell + CELL_UNDERLINE];
            int styleFlags = styleFlags(flags);
            int scan = column + 1;
            while (scan < columns) {
                int nextCell = cellOffset(scan);
                int nextWide = wideState(cellScratch[nextCell + CELL_FLAGS]);
                if (nextWide == GhosttyRenderDelta.WIDE_SPACER_TAIL) {
                    scan++;
                    continue;
                }
                int nextLength = cellScratch[nextCell + CELL_TEXT_LENGTH];
                int nextFlags = cellScratch[nextCell + CELL_FLAGS];
                if (nextLength <= 0 || nextWide == GhosttyRenderDelta.WIDE_SPACER_HEAD ||
                    (nextFlags & GhosttyRenderDelta.CELL_INVISIBLE) != 0 ||
                    cellScratch[nextCell + CELL_FOREGROUND] != foreground ||
                    cellScratch[nextCell + CELL_UNDERLINE] != underlineColor ||
                    styleFlags(nextFlags) != styleFlags ||
                    cellScratch[nextCell + CELL_TEXT_OFFSET] !=
                        textOffset + totalTextLength) break;
                totalTextLength += nextLength;
                scan++;
            }

            int relativeTextOffset = textOffset - rowTextStart;
            int relativeTextEnd = relativeTextOffset + totalTextLength;
            if (relativeTextOffset < 0 || relativeTextEnd < relativeTextOffset ||
                relativeTextEnd > rowTextBytes) {
                throw new IllegalArgumentException("Corrupt UTF-8 command boundary");
            }
            float left = startColumn * metrics.mFontWidth;
            float right = scan * metrics.mFontWidth;
            boolean visibleGlyph = containsNonSpaceUtf8(rowUtf8Scratch,
                relativeTextOffset, totalTextLength);
            if (visibleGlyph) {
                boolean asciiFast = rowAscii || isPrintableAscii(rowUtf8Scratch,
                    relativeTextOffset, totalTextLength);
                int valueStart;
                int valueEnd;
                if (rowAscii) {
                    // Printable ASCII has an exact 1:1 UTF-8 byte to UTF-16 index mapping.
                    valueStart = relativeTextOffset;
                    valueEnd = relativeTextEnd;
                } else {
                    if (relativeTextEnd >= utf8CharIndexScratch.length ||
                        utf8CharIndexScratch[relativeTextOffset] < 0 ||
                        utf8CharIndexScratch[relativeTextEnd] < 0) {
                        throw new IllegalArgumentException("Corrupt UTF-8 command boundary");
                    }
                    valueStart = utf8CharIndexScratch[relativeTextOffset];
                    valueEnd = utf8CharIndexScratch[relativeTextEnd];
                }
                boolean bold = (styleFlags &
                    (GhosttyRenderDelta.CELL_BOLD | GhosttyRenderDelta.CELL_BLINK)) != 0;
                boolean italic = (styleFlags & GhosttyRenderDelta.CELL_ITALIC) != 0;
                paint.setFakeBoldText(bold);
                paint.setTextSkewX(italic ? -0.35f : 0f);
                float allocatedWidth = Math.max(1f, right - left);
                float measured = asciiFast ? allocatedWidth :
                    paint.measureText(rowText, valueStart, valueEnd);
                TextCommand command = obtainTextCommand(rowText, valueStart, valueEnd,
                    left, allocatedWidth, measured, foreground, bold, italic);
                // Android's public glyph API is pixel-identical to drawText for ordinary
                // monospace ASCII. Complex fallback fonts and synthetic bold/italic are kept on
                // Minikin's String path; the hardware differential probe enforces that boundary.
                if (asciiFast && !bold && !italic &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    totalTextLength >= MIN_GLYPH_COUNT_FOR_FAST_PATH) {
                    if (glyphPreparationBudget > 0) {
                        glyphPreparationBudget--;
                        prepareGlyphCommand(command);
                    } else {
                        glyphCacheBypassedRuns++;
                    }
                }
                text.add(command);
                textRuns++;
                if (asciiFast) asciiFastRuns++;
                else measuredUnicodeRuns++;
            } else {
                blankTextRunsSkipped++;
            }

            if ((styleFlags & GhosttyRenderDelta.CELL_UNDERLINE) != 0) {
                addUnderline(decorations, left, right, metrics.mFontLineSpacing,
                    lineThickness, underlineStyle(flags), underlineColor);
            }
            if ((styleFlags & GhosttyRenderDelta.CELL_STRIKETHROUGH) != 0) {
                float strikeTop = metrics.mFontLineSpacing * 0.55f;
                decorations.add(obtainRectCommand(
                    left, strikeTop, right, strikeTop + lineThickness, foreground));
            }
            if ((styleFlags & GhosttyRenderDelta.CELL_OVERLINE) != 0) {
                decorations.add(obtainRectCommand(
                    left, 0f, right, lineThickness, foreground));
            }
            column = scan;
        }

        return glyphPreparationBudget;
    }

    private void ensureScratch(int columns) {
        int required = columns * CELL_RECORD_INTS;
        if (cellScratch.length >= required) return;
        cellScratch = new int[required];
    }

    private static int cellOffset(int column) {
        return column * CELL_RECORD_INTS;
    }

    private void ensureUtf8Scratch(int bytes) {
        if (rowUtf8Scratch.length < bytes) rowUtf8Scratch = new byte[bytes];
        if (utf8CharIndexScratch.length < bytes + 1) {
            utf8CharIndexScratch = new int[bytes + 1];
        }
    }

    /** Build UTF-8 byte-boundary to UTF-16 index mapping for one trusted Ghostty row arena. */
    static void buildUtf8CharIndex(byte[] utf8, int length, int[] output) {
        java.util.Arrays.fill(output, 0, length + 1, -1);
        int byteIndex = 0;
        int charIndex = 0;
        output[0] = 0;
        while (byteIndex < length) {
            int first = utf8[byteIndex] & 0xff;
            int sequenceBytes;
            int utf16Chars;
            if (first < 0x80) {
                sequenceBytes = 1;
                utf16Chars = 1;
            } else if (first < 0xe0) {
                sequenceBytes = 2;
                utf16Chars = 1;
            } else if (first < 0xf0) {
                sequenceBytes = 3;
                utf16Chars = 1;
            } else {
                sequenceBytes = 4;
                utf16Chars = 2;
            }
            if (byteIndex + sequenceBytes > length) {
                throw new IllegalArgumentException("Truncated Ghostty UTF-8 row arena");
            }
            for (int index = 1; index < sequenceBytes; index++) {
                if ((utf8[byteIndex + index] & 0xc0) != 0x80) {
                    throw new IllegalArgumentException("Invalid Ghostty UTF-8 continuation");
                }
            }
            byteIndex += sequenceBytes;
            charIndex += utf16Chars;
            output[byteIndex] = charIndex;
        }
    }

    static boolean isPrintableAscii(byte[] utf8, int offset, int length) {
        int end = offset + length;
        for (int index = offset; index < end; index++) {
            int value = utf8[index] & 0xff;
            if (value < 0x20 || value >= 0x7f) return false;
        }
        return true;
    }

    static boolean isPrintableAscii(CharSequence value, int start, int end) {
        if (value == null || start < 0 || end < start || end > value.length()) return false;
        for (int index = start; index < end; index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character >= 0x7f) return false;
        }
        return true;
    }

    /** Classify one row arena in one pass: bit 0 has non-space bytes, bit 1 is printable ASCII. */
    static int classifyUtf8(byte[] utf8, int offset, int length) {
        int classification = length > 0 ? UTF8_PRINTABLE_ASCII : 0;
        int end = offset + length;
        for (int index = offset; index < end; index++) {
            int value = utf8[index] & 0xff;
            if (value != 0x20) classification |= UTF8_HAS_NON_SPACE;
            if (value < 0x20 || value >= 0x7f) classification &= ~UTF8_PRINTABLE_ASCII;
        }
        return classification;
    }

    static boolean containsNonSpaceUtf8(byte[] utf8, int offset, int length) {
        int end = offset + length;
        for (int index = offset; index < end; index++) {
            if ((utf8[index] & 0xff) != 0x20) return true;
        }
        return false;
    }

    /**
     * Eager glyph shaping is a cache build, not a rendering requirement. Large/high-churn packets
     * use the exact String path in the same frame so small fonts cannot multiply cache setup work.
     */
    static boolean shouldPrepareGlyphCache(int changedRows, int rows) {
        if (changedRows <= 0 || rows <= 0 || changedRows > rows ||
            changedRows > MAX_EAGER_GLYPH_ROWS_PER_PACKET) {
            return false;
        }
        return rows <= MAX_EAGER_GLYPH_ROWS_PER_PACKET || changedRows * 4 <= rows;
    }

    /**
     * Viewport-only packets are on the direct touch/fling path. Their newly exposed rows must be
     * drawable immediately; speculative glyph shaping would put cache construction on every
     * scroll frame and is therefore deliberately deferred to a later content packet.
     */
    static boolean shouldPrepareGlyphCache(int changedRows, int rows, boolean viewportChanged) {
        return !viewportChanged && shouldPrepareGlyphCache(changedRows, rows);
    }

    static boolean shouldPrepareGlyphCache(int changedRows, int rows, boolean viewportChanged,
                                           boolean realtimeScaleActive) {
        return !realtimeScaleActive &&
            shouldPrepareGlyphCache(changedRows, rows, viewportChanged);
    }

    static boolean shouldUseGlyphFastPath(int glyphCount) {
        return glyphCount >= MIN_GLYPH_COUNT_FOR_FAST_PATH;
    }

    static boolean shouldBatchGlyphCommand(int glyphCount, float measuredWidth, float width) {
        boolean scaled = measuredWidth > 0f && Math.abs(measuredWidth - width) > 0.01f;
        return shouldUseGlyphFastPath(glyphCount) && !scaled;
    }

    static int hashGlyphShapeText(CharSequence value, int start, int end) {
        if (value == null || start < 0 || end < start || end > value.length()) {
            throw new IllegalArgumentException("Invalid glyph shape cache range");
        }
        int hash = 0x811c9dc5;
        for (int index = start; index < end; index++) {
            hash ^= value.charAt(index);
            hash *= 0x01000193;
        }
        hash ^= end - start;
        return hash;
    }

    private boolean matchesViewport(int topRow, int rows) {
        if (!initialized || cachedRows != rows || rows <= 0) {
            return false;
        }
        for (int row = 0; row < rows; row++) {
            if (!rowCache.containsKey(topRow + row)) return false;
        }
        return true;
    }

    private boolean matchesPreparedViewport(int topRow, int rows) {
        if (!initialized || cachedRows != rows || rows <= 0) return false;
        // Visible rows are protected from trimCache(). With the same integer viewport, coverage
        // therefore remains complete and does not need another O(rows) map walk.
        return cachedTopRow == topRow || matchesViewport(topRow, rows);
    }

    private void trimCache(int maximumRows) {
        Iterator<Map.Entry<Integer, RowDisplay>> iterator = rowCache.entrySet().iterator();
        while (rowCache.size() > maximumRows && iterator.hasNext()) {
            Map.Entry<Integer, RowDisplay> entry = iterator.next();
            int logicalRow = entry.getKey();
            if (logicalRow >= cachedTopRow && logicalRow < cachedTopRow + cachedRows) continue;
            recycleRow(entry.getValue());
            iterator.remove();
        }
    }

    private void retireAllRows() {
        recycleAllRows();
    }

    private void recycleAllRows() {
        for (RowDisplay display : rowCache.values()) recycleRow(display);
        rowCache.clear();
    }

    private RowDisplay obtainRowDisplay() {
        RowDisplay display = rowPool.pollFirst();
        if (display == null) {
            allocatedRows++;
            return new RowDisplay();
        }
        reusedRows++;
        return display;
    }

    private void recycleRow(RowDisplay display) {
        if (display == null) return;
        display.reset(rectCommandPool, textCommandPool,
            maxRectCommandPoolEntries(), maxTextCommandPoolEntries());
        if (rowPool.size() < MAX_ROW_POOL_ENTRIES) {
            rowPool.addFirst(display);
            recycledRows++;
        }
    }

    private int maxRectCommandPoolEntries() {
        long target = (long) Math.max(1, cachedRows) * 32L;
        return (int) Math.max(256L, Math.min(MAX_COMMAND_POOL_ENTRIES, target));
    }

    private int maxTextCommandPoolEntries() {
        long target = (long) Math.max(1, cachedRows) * 16L;
        return (int) Math.max(128L, Math.min(MAX_COMMAND_POOL_ENTRIES, target));
    }

    private void trimCommandPools() {
        int rectLimit = maxRectCommandPoolEntries();
        while (rectCommandPool.size() > rectLimit) rectCommandPool.pollLast();
        int textLimit = maxTextCommandPoolEntries();
        while (textCommandPool.size() > textLimit) textCommandPool.pollLast();
    }

    private void recordPacketPipeline(long elapsedNanos) {
        packetPipelineCalls++;
        packetPipelineNanos += elapsedNanos;
        maxPacketPipelineNanos = Math.max(maxPacketPipelineNanos, elapsedNanos);
    }

    private void recordPacketApply(long elapsedNanos) {
        packetApplyCalls++;
        packetApplyNanos += elapsedNanos;
        maxPacketApplyNanos = Math.max(maxPacketApplyNanos, elapsedNanos);
    }

    private void recordCanvasDraw(long elapsedNanos) {
        canvasFrameCount++;
        canvasDrawNanos += elapsedNanos;
        maxCanvasDrawNanos = Math.max(maxCanvasDrawNanos, elapsedNanos);
    }

    private static long averageMicros(long totalNanos, long operations) {
        return operations == 0 ? 0 : totalNanos / operations / 1000L;
    }

    private static long nanosToMicros(long nanos) {
        return nanos / 1000L;
    }

    private void maybeLogMetrics() {
        long now = SystemClock.uptimeMillis();
        if (now - lastMetricsLogMs < METRICS_LOG_INTERVAL_MS) return;
        lastMetricsLogMs = now;
        long renderOperations = packetCount + nativePacketSkips;
        long averageMicros = renderOperations == 0 ? 0 :
            renderNanos / renderOperations / 1000L;
        int visualRows = 0;
        for (int row = 0; row < cachedRows; row++) {
            RowDisplay display = rowCache.get(cachedTopRow + row);
            if (display != null && display.hasVisualContent()) visualRows++;
        }
        trimCommandPools();
        long estimatedBytes = estimateRetainedBytes();
        Log.i(LOG_TAG, "packets=" + packetCount + " full=" + fullPacketCount +
            " fullRequests=" + fullFrameRequests + " fullReady=" + fullFrameCompletions +
            " nativeRows=" + nativeChangedRows + " decodedRows=" + decodedRows +
            " retainedRows=" + retainedRows + " cachedRows=" + rowCache.size() +
            " semanticRowsCompared=" + semanticRowsCompared +
            " semanticRowsReused=" + semanticRowsReused +
            " semanticRowsCaptured=" + semanticRowsCaptured +
            " nativeSemanticRowsCompared=" + nativeSemanticRowsCompared +
            " nativeSemanticRowsSuppressed=" + nativeSemanticRowsSuppressed +
            " retiredPending=0 rowPool=" + rowPool.size() +
            " rowAllocated=" + allocatedRows + " rowReused=" + reusedRows +
            " rowInPlace=" + inPlaceRowRebuilds +
            " rowRecycled=" + recycledRows +
            " rectPool=" + rectCommandPool.size() + " rectAllocated=" +
            allocatedRectCommands + " rectReused=" + reusedRectCommands +
            " textPool=" + textCommandPool.size() + " textAllocated=" +
            allocatedTextCommands + " textReused=" + reusedTextCommands +
            " directCanvasRows=" + directCanvasRowDraws +
            " renderNodeRecords=0 renderNodeDraws=0 renderNodeDiscards=0" +
            " layerPromotions=0 hardwareNodes=0 layeredNodes=0" +
            " rowUtf8Decodes=" + rowUtf8Decodes +
            " rowUtf8Bytes=" + rowUtf8Bytes +
            " rowStringDecodes=" + rowStringDecodes +
            " asciiRows=" + asciiRows +
            " unicodeRows=" + unicodeRows +
            " blankRows=" + blankRows +
            " textRuns=" + textRuns +
            " asciiFastRuns=" + asciiFastRuns +
            " measuredUnicodeRuns=" + measuredUnicodeRuns +
            " blankRunsSkipped=" + blankTextRunsSkipped +
            " shapedRuns=" + shapedTextRuns +
            " shapedGlyphs=" + shapedGlyphs +
            " glyphShapeFailures=" + glyphShapeFailures +
            " glyphShapeCacheHits=" + glyphShapeCacheHits +
            " glyphShapeCacheMisses=" + glyphShapeCacheMisses +
            " glyphShapeCacheEvictions=" + glyphShapeCacheEvictions +
            " glyphShapeCacheRestoredGlyphs=" + glyphShapeCacheRestoredGlyphs +
            " glyphCachePackets=" + glyphCachePackets +
            " glyphCacheBypassPackets=" + glyphCacheBypassPackets +
            " glyphCacheViewportBypassPackets=" + glyphCacheViewportBypassPackets +
            " glyphCacheScaleBypassPackets=" + glyphCacheScaleBypassPackets +
            " glyphCacheBypassedRuns=" + glyphCacheBypassedRuns +
            " glyphCanvasDraws=" + glyphCanvasDraws +
            " stringCanvasDraws=" + stringCanvasDraws +
            " glyphBatchHealthy=" + glyphBatchingHealthy +
            " glyphBatchDrawCalls=" + glyphBatchDrawCalls +
            " glyphBatchedCommands=" + glyphBatchedCommands +
            " glyphBatchedGlyphs=" + glyphBatchedGlyphs +
            " glyphBatchFallbackFrames=" + glyphBatchFallbackFrames +
            " glyphShapeUs=" + (glyphShapeNanos / 1000L) +
            " scaleGlyphWarmFrames=" + scaleGlyphWarmFrames +
            " scaleGlyphWarmCandidates=" + scaleGlyphWarmCandidates +
            " scaleGlyphWarmPrepared=" + scaleGlyphWarmPreparedRuns +
            " scaleGlyphWarmUs=" + (scaleGlyphWarmNanos / 1000L) +
            " estimatedBytes=" + estimatedBytes +
            " grid=" + cachedColumns + 'x' + cachedRows + " top=" + cachedTopRow +
            " offsetPx=" + Math.round(lastPixelOffset) + " scrollback=" +
            cachedScrollbackRows + " visualRows=" + visualRows + " viewportRebuilds=" +
            viewportRebuilds + " viewportPartial=" + viewportPartialPackets +
            " viewportRetries=" + viewportFullRetries + " viewportHits=" +
            viewportCacheHits + " packetSkips=" + nativePacketSkips +
            " packetPipelineUs=" +
            averageMicros(packetPipelineNanos, packetPipelineCalls) + '/' +
            nanosToMicros(maxPacketPipelineNanos) +
            " packetApplyUs=" + averageMicros(packetApplyNanos, packetApplyCalls) + '/' +
            nanosToMicros(maxPacketApplyNanos) +
            " rowBuildUs=" + averageMicros(rowBuildNanos, decodedRows) + '/' +
            nanosToMicros(maxRowBuildBatchNanos) +
            " canvasDrawUs=" + averageMicros(canvasDrawNanos, canvasFrameCount) + '/' +
            nanosToMicros(maxCanvasDrawNanos) +
            " avgRenderUs=" + averageMicros);
    }

    private long estimateRetainedBytes() {
        long bytes = (long) cellScratch.length * Integer.BYTES;
        for (RowDisplay display : rowPool) bytes += display.estimateBytes();
        bytes += (long) rectCommandPool.size() * RectCommand.ESTIMATED_BYTES;
        bytes += (long) textCommandPool.size() * TextCommand.ESTIMATED_BYTES;
        bytes += glyphShapeCache.estimateBytes();
        for (RowDisplay display : rowCache.values()) bytes += display.estimateBytes();
        return bytes;
    }

    private static int styleFlags(int flags) {
        return flags & (GhosttyRenderDelta.CELL_BOLD |
            GhosttyRenderDelta.CELL_ITALIC |
            GhosttyRenderDelta.CELL_UNDERLINE |
            GhosttyRenderDelta.CELL_STRIKETHROUGH |
            GhosttyRenderDelta.CELL_BLINK |
            GhosttyRenderDelta.CELL_OVERLINE);
    }

    private static int underlineStyle(int flags) {
        return (flags >>> UNDERLINE_SHIFT) & 0x0f;
    }

    private static int wideState(int flags) {
        return (flags >>> WIDE_SHIFT) & 0x03;
    }

    private static int dimColor(int color) {
        return (color & 0xff000000) |
            ((((color >>> 16) & 0xff) * 2 / 3) << 16) |
            ((((color >>> 8) & 0xff) * 2 / 3) << 8) |
            ((color & 0xff) * 2 / 3);
    }

    private RectCommand obtainRectCommand(float left, float top, float right,
                                          float bottom, int color) {
        RectCommand command = rectCommandPool.pollFirst();
        if (command == null) {
            allocatedRectCommands++;
            command = new RectCommand();
        } else {
            reusedRectCommands++;
        }
        command.set(left, top, right, bottom, color);
        return command;
    }

    private TextCommand obtainTextCommand(String value, int valueStart, int valueEnd,
                                          float left, float width,
                                          float measuredWidth, int color,
                                          boolean bold, boolean italic) {
        TextCommand command = textCommandPool.pollFirst();
        if (command == null) {
            allocatedTextCommands++;
            command = new TextCommand();
        } else {
            reusedTextCommands++;
        }
        command.set(value, valueStart, valueEnd, left, width, measuredWidth,
            color, bold, italic);
        return command;
    }

    private void addCursorDecorations(List<RectCommand> output,
                                      int column, int width, int thickness,
                                      int style, int color, int columns) {
        float left = column * metrics.mFontWidth;
        float right = Math.min((column + width) * metrics.mFontWidth,
            columns * metrics.mFontWidth);
        float bottom = metrics.mFontLineSpacing;
        if (style == 0) {
            output.add(obtainRectCommand(left, 0f,
                Math.min(right, left + Math.max(thickness, metrics.mFontWidth / 4f)),
                bottom, color));
        } else if (style == 2) {
            output.add(obtainRectCommand(left,
                bottom - Math.max(thickness, metrics.mFontLineSpacing / 4f),
                right, bottom, color));
        } else {
            output.add(obtainRectCommand(left, 0f, right, thickness, color));
            output.add(obtainRectCommand(left, bottom - thickness, right, bottom, color));
            output.add(obtainRectCommand(left, 0f, left + thickness, bottom, color));
            output.add(obtainRectCommand(right - thickness, 0f, right, bottom, color));
        }
    }

    private void addUnderline(List<RectCommand> output,
                              float left, float right, float bottom,
                              int thickness, int style, int color) {
        float baseline = bottom - thickness;
        if (style == 2) {
            output.add(obtainRectCommand(left, baseline - thickness * 2f,
                right, baseline - thickness, color));
            output.add(obtainRectCommand(left, baseline,
                right, baseline + thickness, color));
            return;
        }
        if (style == 3 || style == 4 || style == 5) {
            float segment = style == 4 ? thickness : thickness * 4f;
            float gap = style == 4 ? thickness : thickness * 2f;
            int wave = 0;
            for (float x = left; x < right; x += segment + gap) {
                float y = style == 3 && (wave++ & 1) != 0 ? baseline - thickness : baseline;
                output.add(obtainRectCommand(x, y,
                    Math.min(right, x + segment), y + thickness, color));
            }
            return;
        }
        output.add(obtainRectCommand(left, baseline, right, baseline + thickness, color));
    }

    private static final class GlyphShapeCache {
        private final GlyphShapeEntry[] entries;
        private final int mask;
        private final int probes;
        private long generation;

        GlyphShapeCache(int capacity, int probes) {
            if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
                throw new IllegalArgumentException("Glyph shape cache capacity must be power of two");
            }
            this.entries = new GlyphShapeEntry[capacity];
            this.mask = capacity - 1;
            this.probes = Math.max(1, Math.min(capacity, probes));
        }

        boolean restore(TextCommand command) {
            if (command == null || command.value == null) return false;
            int hash = hashGlyphShapeText(command.value, command.valueStart, command.valueEnd);
            int slot = spread(hash) & mask;
            for (int probe = 0; probe < probes; probe++) {
                GlyphShapeEntry entry = entries[(slot + probe) & mask];
                if (entry == null) continue;
                if (!entry.matches(command, hash)) continue;
                entry.lastUsed = ++generation;
                command.ensureGlyphCapacity(entry.glyphIds.length);
                System.arraycopy(entry.glyphIds, 0, command.glyphIds, 0,
                    entry.glyphIds.length);
                System.arraycopy(entry.glyphPositions, 0, command.glyphPositions, 0,
                    entry.glyphPositions.length);
                System.arraycopy(entry.glyphFonts, 0, command.glyphFonts, 0,
                    entry.glyphFonts.length);
                command.glyphCount = entry.glyphIds.length;
                return true;
            }
            return false;
        }

        /** Return true when a live slot was replaced. */
        boolean store(TextCommand command) {
            if (command == null || command.value == null || command.glyphCount <= 0) return false;
            int hash = hashGlyphShapeText(command.value, command.valueStart, command.valueEnd);
            int slot = spread(hash) & mask;
            int victimIndex = slot;
            long oldestGeneration = Long.MAX_VALUE;
            for (int probe = 0; probe < probes; probe++) {
                int index = (slot + probe) & mask;
                GlyphShapeEntry entry = entries[index];
                if (entry == null) {
                    victimIndex = index;
                    break;
                }
                if (entry.matches(command, hash)) {
                    entry.capture(command, hash, ++generation);
                    return false;
                }
                if (entry.lastUsed < oldestGeneration) {
                    oldestGeneration = entry.lastUsed;
                    victimIndex = index;
                }
            }
            boolean evicted = entries[victimIndex] != null;
            if (entries[victimIndex] == null) entries[victimIndex] = new GlyphShapeEntry();
            entries[victimIndex].capture(command, hash, ++generation);
            return evicted;
        }

        void clear() {
            java.util.Arrays.fill(entries, null);
            generation = 0;
        }

        long estimateBytes() {
            long bytes = (long) entries.length * Long.BYTES;
            for (GlyphShapeEntry entry : entries) {
                if (entry == null) continue;
                bytes += 64L + (long) entry.glyphIds.length * Integer.BYTES +
                    (long) entry.glyphPositions.length * Float.BYTES +
                    (long) entry.glyphFonts.length * Long.BYTES +
                    40L + (entry.value == null ? 0L : entry.value.length() * 2L);
            }
            return bytes;
        }

        private static int spread(int hash) {
            return hash ^ (hash >>> 16);
        }
    }

    private static final class GlyphShapeEntry {
        String value;
        int valueStart;
        int valueEnd;
        int hash;
        int[] glyphIds = new int[0];
        float[] glyphPositions = new float[0];
        Object[] glyphFonts = new Object[0];
        long lastUsed;

        boolean matches(TextCommand command, int candidateHash) {
            int length = valueEnd - valueStart;
            if (hash != candidateHash || command.valueEnd - command.valueStart != length) {
                return false;
            }
            for (int index = 0; index < length; index++) {
                if (value.charAt(valueStart + index) !=
                    command.value.charAt(command.valueStart + index)) {
                    return false;
                }
            }
            return true;
        }

        void capture(TextCommand command, int hash, long generation) {
            value = command.value.substring(command.valueStart, command.valueEnd);
            valueStart = 0;
            valueEnd = value.length();
            this.hash = hash;
            glyphIds = java.util.Arrays.copyOf(command.glyphIds, command.glyphCount);
            glyphPositions = java.util.Arrays.copyOf(command.glyphPositions,
                command.glyphCount * 2);
            glyphFonts = java.util.Arrays.copyOf(command.glyphFonts, command.glyphCount);
            lastUsed = generation;
        }
    }

    private static final class FrameDrawStats {
        long rows;
        long glyphCommands;
        long stringCommands;
        long batchDrawCalls;
        long batchedCommands;
        long batchedGlyphs;

        void reset() {
            rows = 0;
            glyphCommands = 0;
            stringCommands = 0;
            batchDrawCalls = 0;
            batchedCommands = 0;
            batchedGlyphs = 0;
        }
    }

    private static final class GlyphBatchFailure extends RuntimeException {
        GlyphBatchFailure(Throwable cause) {
            super(cause);
        }
    }

    private abstract static class GlyphBatch {
        abstract void begin(Canvas canvas, Paint paint);
        abstract boolean append(TextCommand command, float baseline);
        abstract void flush();
        abstract void finish();
        abstract void abort();
        abstract long getDrawCalls();
        abstract long getBatchedCommands();
        abstract long getBatchedGlyphs();
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static final class Api33GlyphBatch extends GlyphBatch {
        private Canvas canvas;
        private Paint paint;
        private android.graphics.fonts.Font font;
        private int color;
        private int[] glyphIds = new int[256];
        private float[] glyphPositions = new float[512];
        private int glyphCount;
        long drawCalls;
        long batchedCommands;
        long batchedGlyphs;

        @Override
        void begin(Canvas canvas, Paint paint) {
            abort();
            this.canvas = canvas;
            this.paint = paint;
        }

        @Override
        boolean append(TextCommand command, float baseline) {
            if (canvas == null || paint == null || command == null || command.bold ||
                command.italic || !shouldBatchGlyphCommand(command.glyphCount,
                    command.measuredWidth, command.width)) {
                return false;
            }
            if (command.glyphIds.length < command.glyphCount ||
                command.glyphPositions.length < command.glyphCount * 2 ||
                command.glyphFonts.length < command.glyphCount) {
                return false;
            }
            for (int index = 0; index < command.glyphCount; index++) {
                if (!(command.glyphFonts[index] instanceof android.graphics.fonts.Font)) {
                    return false;
                }
            }

            for (int index = 0; index < command.glyphCount; index++) {
                android.graphics.fonts.Font glyphFont =
                    (android.graphics.fonts.Font) command.glyphFonts[index];
                if (font == null || color != command.color || !font.equals(glyphFont)) {
                    flush();
                    font = glyphFont;
                    color = command.color;
                }
                ensureCapacity(glyphCount + 1);
                glyphIds[glyphCount] = command.glyphIds[index];
                glyphPositions[glyphCount * 2] =
                    command.left + command.glyphPositions[index * 2];
                glyphPositions[glyphCount * 2 + 1] =
                    baseline + command.glyphPositions[index * 2 + 1];
                glyphCount++;
            }
            batchedCommands++;
            batchedGlyphs += command.glyphCount;
            return true;
        }

        @Override
        void flush() {
            if (glyphCount <= 0) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setFakeBoldText(false);
            paint.setTextSkewX(0f);
            paint.setUnderlineText(false);
            paint.setStrikeThruText(false);
            try {
                canvas.drawGlyphs(glyphIds, 0, glyphPositions, 0, glyphCount, font, paint);
            } catch (RuntimeException | LinkageError error) {
                throw new GlyphBatchFailure(error);
            }
            drawCalls++;
            glyphCount = 0;
        }

        @Override
        void finish() {
            flush();
            canvas = null;
            paint = null;
            font = null;
        }

        @Override
        void abort() {
            canvas = null;
            paint = null;
            font = null;
            glyphCount = 0;
            drawCalls = 0;
            batchedCommands = 0;
            batchedGlyphs = 0;
        }

        @Override
        long getDrawCalls() {
            return drawCalls;
        }

        @Override
        long getBatchedCommands() {
            return batchedCommands;
        }

        @Override
        long getBatchedGlyphs() {
            return batchedGlyphs;
        }

        private void ensureCapacity(int requiredGlyphs) {
            if (glyphIds.length >= requiredGlyphs) return;
            int capacity = glyphIds.length;
            while (capacity < requiredGlyphs) capacity = Math.max(capacity + 1, capacity * 2);
            glyphIds = java.util.Arrays.copyOf(glyphIds, capacity);
            glyphPositions = java.util.Arrays.copyOf(glyphPositions, capacity * 2);
        }
    }

    static final class RowDisplay {
        final RectCommandBatch backgrounds = new RectCommandBatch(4);
        final ArrayList<TextCommand> text = new ArrayList<>(8);
        final ArrayList<RectCommand> decorations = new ArrayList<>(2);
        int[] sourceRecords = new int[0];
        byte[] sourceUtf8 = new byte[0];
        int sourceColumns;
        int sourceUtf8Length;
        int sourceBackgroundColor;
        int sourceSelectionStart;
        int sourceSelectionEnd;
        int sourceCursorStart;
        int sourceCursorEnd;
        int sourceCursorStyle;
        int sourceCursorColor;
        boolean sourceSelectionActive;
        boolean sourceCursorActive;
        boolean sourceValid;
        long commandGeneration = Long.MIN_VALUE;
        TerminalGpuFrame.Row gpuSnapshot;

        boolean matchesSource(int columns, int backgroundColor,
                              boolean selectionActive, int selectionStart, int selectionEnd,
                              boolean cursorActive, int cursorStart, int cursorEnd,
                              int cursorStyle, int cursorColor,
                              int[] records, int textStart,
                              byte[] utf8, int utf8Length) {
            if (!sourceValid || sourceColumns != columns || sourceUtf8Length != utf8Length ||
                sourceBackgroundColor != backgroundColor ||
                sourceSelectionActive != selectionActive ||
                sourceSelectionStart != selectionStart || sourceSelectionEnd != selectionEnd ||
                sourceCursorActive != cursorActive || sourceCursorStart != cursorStart ||
                sourceCursorEnd != cursorEnd || sourceCursorStyle != cursorStyle ||
                sourceCursorColor != cursorColor) return false;
            for (int column = 0; column < columns; column++) {
                int cell = cellOffset(column);
                int textLength = records[cell + CELL_TEXT_LENGTH];
                int relativeTextOffset = textLength > 0
                    ? records[cell + CELL_TEXT_OFFSET] - textStart : 0;
                if (sourceRecords[cell + CELL_FOREGROUND] !=
                        records[cell + CELL_FOREGROUND] ||
                    sourceRecords[cell + CELL_BACKGROUND] !=
                        records[cell + CELL_BACKGROUND] ||
                    sourceRecords[cell + CELL_UNDERLINE] !=
                        records[cell + CELL_UNDERLINE] ||
                    sourceRecords[cell + CELL_FLAGS] != records[cell + CELL_FLAGS] ||
                    sourceRecords[cell + CELL_TEXT_OFFSET] != relativeTextOffset ||
                    sourceRecords[cell + CELL_TEXT_LENGTH] != textLength) return false;
            }
            for (int index = 0; index < utf8Length; index++) {
                if (sourceUtf8[index] != utf8[index]) return false;
            }
            return true;
        }

        void captureSource(int columns, int backgroundColor,
                           boolean selectionActive, int selectionStart, int selectionEnd,
                           boolean cursorActive, int cursorStart, int cursorEnd,
                           int cursorStyle, int cursorColor,
                           int[] records, int textStart,
                           byte[] utf8, int utf8Length) {
            ensureSourceCapacity(columns, utf8Length);
            System.arraycopy(records, 0, sourceRecords, 0, columns * CELL_RECORD_INTS);
            for (int column = 0; column < columns; column++) {
                int cell = cellOffset(column);
                sourceRecords[cell + CELL_TEXT_OFFSET] =
                    sourceRecords[cell + CELL_TEXT_LENGTH] > 0
                        ? sourceRecords[cell + CELL_TEXT_OFFSET] - textStart : 0;
            }
            if (utf8Length > 0) System.arraycopy(utf8, 0, sourceUtf8, 0, utf8Length);
            sourceColumns = columns;
            sourceUtf8Length = utf8Length;
            sourceBackgroundColor = backgroundColor;
            sourceSelectionActive = selectionActive;
            sourceSelectionStart = selectionStart;
            sourceSelectionEnd = selectionEnd;
            sourceCursorActive = cursorActive;
            sourceCursorStart = cursorStart;
            sourceCursorEnd = cursorEnd;
            sourceCursorStyle = cursorStyle;
            sourceCursorColor = cursorColor;
            sourceValid = true;
        }

        private void ensureSourceCapacity(int columns, int utf8Length) {
            int required = columns * CELL_RECORD_INTS;
            if (sourceRecords.length < required) sourceRecords = new int[required];
            if (sourceUtf8.length < utf8Length) sourceUtf8 = new byte[utf8Length];
        }

        int draw(Canvas canvas, Paint paint, float y, int fontAscent,
                 boolean glyphFastPathEnabled, GlyphBatch glyphBatch) {
            return drawCommands(canvas, paint, y, fontAscent, glyphFastPathEnabled,
                glyphBatch);
        }

        int drawCommands(Canvas canvas, Paint paint, float y, int fontAscent,
                         boolean glyphFastPathEnabled, GlyphBatch glyphBatch) {
            if (glyphBatch != null && !backgrounds.isEmpty()) glyphBatch.flush();
            backgrounds.draw(canvas, paint, y);
            int glyphDraws = 0;
            for (TextCommand command : text) {
                if (glyphBatch != null && glyphBatch.append(command, y - fontAscent)) {
                    glyphDraws++;
                    continue;
                }
                if (glyphBatch != null) glyphBatch.flush();
                if (command.draw(canvas, paint, y, fontAscent, glyphFastPathEnabled)) {
                    glyphDraws++;
                }
            }
            if (glyphBatch != null && !decorations.isEmpty()) glyphBatch.flush();
            for (RectCommand command : decorations) command.draw(canvas, paint, y);
            return glyphDraws;
        }

        static final int ESTIMATED_BASE_BYTES = 96;

        void ensureCapacity(int columns) {
            int cellCapacity = Math.min(256, Math.max(8, columns));
            backgrounds.ensureCapacity(cellCapacity);
            text.ensureCapacity(cellCapacity);
            decorations.ensureCapacity(Math.min(64, cellCapacity));
        }

        void reset(ArrayDeque<RectCommand> rectPool, ArrayDeque<TextCommand> textPool,
                   int rectPoolLimit, int textPoolLimit) {
            for (RectCommand command : decorations) {
                if (rectPool.size() < rectPoolLimit) {
                    command.clear();
                    rectPool.addFirst(command);
                }
            }
            for (TextCommand command : text) {
                if (textPool.size() < textPoolLimit) {
                    command.clear();
                    textPool.addFirst(command);
                }
            }
            backgrounds.clear();
            text.clear();
            decorations.clear();
            gpuSnapshot = null;
            sourceValid = false;
            sourceColumns = 0;
            sourceUtf8Length = 0;
        }

        boolean hasVisualContent() {
            return !backgrounds.isEmpty() || !text.isEmpty() || !decorations.isEmpty();
        }

        boolean hasSemanticContent() {
            return !text.isEmpty() || !decorations.isEmpty();
        }

        long estimateBytes() {
            long bytes = ESTIMATED_BASE_BYTES;
            bytes += backgrounds.estimateBytes();
            bytes += (long) decorations.size() * RectCommand.ESTIMATED_BYTES;
            for (TextCommand command : text) bytes += command.estimateBytes();
            bytes += (long) sourceRecords.length * Integer.BYTES + sourceUtf8.length;
            return bytes;
        }
    }

    /**
     * Reusable structure-of-arrays storage for the high-cardinality true-color background path.
     * A 169-column row can alternate color at every cell; retaining primitive arrays avoids one
     * Java object plus ArrayList traffic per run while preserving exact command order and bounds.
     */
    private static final class RectCommandBatch {
        private static final int FLOATS_PER_RECT = 4;
        private float[] bounds;
        private int[] colors;
        private int size;

        RectCommandBatch(int initialCapacity) {
            int capacity = Math.max(1, initialCapacity);
            bounds = new float[capacity * FLOATS_PER_RECT];
            colors = new int[capacity];
        }

        void add(float left, float top, float right, float bottom, int color) {
            ensureCapacity(size + 1);
            int offset = size * FLOATS_PER_RECT;
            bounds[offset] = left;
            bounds[offset + 1] = top;
            bounds[offset + 2] = right;
            bounds[offset + 3] = bottom;
            colors[size] = color;
            size++;
        }

        void ensureCapacity(int required) {
            if (colors.length >= required) return;
            int capacity = colors.length;
            while (capacity < required) capacity = Math.max(capacity + 1, capacity * 2);
            bounds = java.util.Arrays.copyOf(bounds, capacity * FLOATS_PER_RECT);
            colors = java.util.Arrays.copyOf(colors, capacity);
        }

        int size() {
            return size;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void clear() {
            size = 0;
        }

        void draw(Canvas canvas, Paint paint, float y) {
            paint.setStyle(Paint.Style.FILL);
            for (int index = 0; index < size; index++) {
                int offset = index * FLOATS_PER_RECT;
                paint.setColor(colors[index]);
                canvas.drawRect(bounds[offset], bounds[offset + 1] + y,
                    bounds[offset + 2], bounds[offset + 3] + y, paint);
            }
        }

        float[] copyBounds() {
            return size == 0 ? new float[0] :
                java.util.Arrays.copyOf(bounds, size * FLOATS_PER_RECT);
        }

        int[] copyColors() {
            return size == 0 ? new int[0] : java.util.Arrays.copyOf(colors, size);
        }

        long estimateBytes() {
            return (long) bounds.length * Float.BYTES + (long) colors.length * Integer.BYTES;
        }
    }

    private static final class RectCommand {
        static final int ESTIMATED_BYTES = 40;
        float left;
        float top;
        float right;
        float bottom;
        int color;

        RectCommand() {
        }

        void set(float left, float top, float right, float bottom, int color) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.color = color;
        }

        void clear() {
            left = top = right = bottom = 0f;
            color = 0;
        }

        void draw(Canvas canvas, Paint paint, float y) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawRect(left, top + y, right, bottom + y, paint);
        }
    }

    private static final class TextCommand {
        static final int ESTIMATED_BYTES = 64;
        String value;
        int valueStart;
        int valueEnd;
        float left;
        float width;
        float measuredWidth;
        int color;
        boolean bold;
        boolean italic;
        int glyphCount;
        int[] glyphIds = new int[0];
        float[] glyphPositions = new float[0];
        Object[] glyphFonts = new Object[0];

        TextCommand() {
        }

        void set(String value, int valueStart, int valueEnd,
                 float left, float width, float measuredWidth,
                 int color, boolean bold, boolean italic) {
            this.value = value;
            this.valueStart = valueStart;
            this.valueEnd = valueEnd;
            this.left = left;
            this.width = width;
            this.measuredWidth = measuredWidth;
            this.color = color;
            this.bold = bold;
            this.italic = italic;
            clearGlyphs();
        }

        int prepareGlyphs(Paint paint) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return 0;
            try {
                return Api33Glyphs.shape(this, paint);
            } catch (RuntimeException | LinkageError | OutOfMemoryError ignored) {
                clearGlyphs();
                return 0;
            }
        }

        void clear() {
            clearGlyphs();
            value = null;
            valueStart = valueEnd = 0;
            left = width = measuredWidth = 0f;
            color = 0;
            bold = italic = false;
        }

        long estimateBytes() {
            long bytes = ESTIMATED_BYTES + (value == null ? 0L :
                40L + Math.max(0, valueEnd - valueStart) * 2L);
            bytes += (long) glyphIds.length * Integer.BYTES;
            bytes += (long) glyphPositions.length * Float.BYTES;
            bytes += (long) glyphFonts.length * Long.BYTES;
            return bytes;
        }

        boolean draw(Canvas canvas, Paint paint, float y, int fontAscent,
                     boolean glyphFastPathEnabled) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setFakeBoldText(bold);
            paint.setTextSkewX(italic ? -0.35f : 0f);
            paint.setUnderlineText(false);
            paint.setStrikeThruText(false);
            float baseline = y - fontAscent;
            boolean scale = measuredWidth > 0f && Math.abs(measuredWidth - width) > 0.01f;
            if (glyphFastPathEnabled && shouldUseGlyphFastPath(glyphCount) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                canvas.isHardwareAccelerated() && glyphCount > 0) {
                int save = canvas.save();
                try {
                    canvas.translate(left, baseline);
                    if (scale) canvas.scale(width / measuredWidth, 1f);
                    if (Api33Glyphs.draw(canvas, this, paint)) return true;
                } catch (RuntimeException | LinkageError ignored) {
                    clearGlyphs();
                } finally {
                    canvas.restoreToCount(save);
                }
            }
            if (scale) {
                canvas.save();
                canvas.translate(left, y);
                canvas.scale(width / measuredWidth, 1f);
                canvas.drawText(value, valueStart, valueEnd, 0f, -fontAscent, paint);
                canvas.restore();
            } else {
                canvas.drawText(value, valueStart, valueEnd, left, baseline, paint);
            }
            return false;
        }

        void ensureGlyphCapacity(int count) {
            if (glyphIds.length < count) glyphIds = new int[count];
            if (glyphPositions.length < count * 2) glyphPositions = new float[count * 2];
            if (glyphFonts.length < count) glyphFonts = new Object[count];
        }

        void clearGlyphs() {
            for (int index = 0; index < glyphCount; index++) glyphFonts[index] = null;
            glyphCount = 0;
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static final class Api33Glyphs {
        static int shape(TextCommand command, Paint paint) {
            int charCount = command.valueEnd - command.valueStart;
            if (charCount <= 0) return 0;
            android.graphics.text.PositionedGlyphs shaped =
                android.graphics.text.TextRunShaper.shapeTextRun(
                    command.value, command.valueStart, charCount,
                    command.valueStart, charCount, 0f, 0f, false, paint);
            int count = shaped.glyphCount();
            if (count <= 0) return 0;
            command.ensureGlyphCapacity(count);
            for (int index = 0; index < count; index++) {
                command.glyphIds[index] = shaped.getGlyphId(index);
                command.glyphPositions[index * 2] = shaped.getGlyphX(index);
                command.glyphPositions[index * 2 + 1] = shaped.getGlyphY(index);
                command.glyphFonts[index] = shaped.getFont(index);
            }
            command.glyphCount = count;
            return count;
        }

        static boolean draw(Canvas canvas, TextCommand command, Paint paint) {
            int start = 0;
            while (start < command.glyphCount) {
                Object fontObject = command.glyphFonts[start];
                if (!(fontObject instanceof android.graphics.fonts.Font)) return false;
                android.graphics.fonts.Font font = (android.graphics.fonts.Font) fontObject;
                int end = start + 1;
                while (end < command.glyphCount && font.equals(command.glyphFonts[end])) end++;
                canvas.drawGlyphs(command.glyphIds, start,
                    command.glyphPositions, start * 2, end - start, font, paint);
                start = end;
            }
            return true;
        }
    }

}
