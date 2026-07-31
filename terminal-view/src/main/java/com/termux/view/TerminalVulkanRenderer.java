package com.termux.view;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import android.os.Build;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Vulkan-facing frame compiler. All Android text rasterization happens off the UI thread. */
/** Loaded only after {@link TerminalVulkanView#isSupported(Context)} proves API 33 support. */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
final class TerminalVulkanRenderer {
    private static final int ATLAS_PADDING = 2;
    private static final int INITIAL_MASK_ATLAS = 1024;
    private static final int MAX_MASK_ATLAS = 2048;
    private static final int INITIAL_COLOR_ATLAS = 512;
    private static final int MAX_COLOR_ATLAS = 1024;
    private static final int MAX_REBUILD_ATTEMPTS = 1;
    private static final int MAX_ROW_RETENTION_SCREENS = 4;
    private static final int MAX_GLYPH_CACHE_ENTRIES = 8192;
    private static final int GLYPH_HOT_CACHE_ENTRIES = 512;
    private static final int SINGLE_GLYPH_CACHE_ENTRIES = 1024;
    private static final int SINGLE_GLYPH_CACHE_PROBES = 8;
    private static final int SHORT_RUN_CACHE_ENTRIES = 4096;
    private static final int SHORT_RUN_CACHE_PROBES = 8;
    private static final int SHORT_RUN_CACHE_MAX_CHARS = 16;
    private static final int RUN_ATLAS_PADDING = 2;
    private static final int RUN_ATLAS_MAX_DIMENSION = 4096;
    private static final int RUN_ATLAS_MAX_BYTES = 4 * 1024 * 1024;
    private static final int RUN_RASTER_REBUILD = -1;
    private static final int RUN_RASTER_FALLBACK = 0;
    private static final int RUN_RASTER_CACHED = 1;
    private static final int RUN_RASTER_EMPTY = 2;
    private static final int RUN_RASTER_EXACT_FALLBACK = 3;
    private static final String NATIVE_LIBRARY = "termux-vulkan";

    private static volatile boolean nativeLoadAttempted;
    private static volatile boolean nativeAvailable;

    static {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                System.loadLibrary(NATIVE_LIBRARY);
                nativeAvailable = true;
            } catch (UnsatisfiedLinkError ignored) {
                nativeAvailable = false;
            } finally {
                nativeLoadAttempted = true;
            }
        } else {
            nativeLoadAttempted = true;
        }
    }

    private final FrameComposer composer = new FrameComposer();
    private long nativeHandle;
    private boolean failed;
    private int consecutiveRenderFailures;
    private long lastAcceptNanos;
    private long lastPrepareNanos;
    private long lastNativeNanos;
    private long maxAcceptNanos;
    private long maxPrepareNanos;
    private long maxNativeNanos;

    static boolean isNativeAvailable() {
        return nativeLoadAttempted && nativeAvailable;
    }

    static boolean isSingleGlyphShapeCandidate(CharSequence value, int start, int end) {
        if (value == null || start < 0 || end != start + 1 || end > value.length()) return false;
        char character = value.charAt(start);
        return !Character.isHighSurrogate(character) && !Character.isLowSurrogate(character);
    }

    static boolean isLongAsciiRunCandidate(CharSequence value, int start, int end) {
        if (value == null || start < 0 || end > value.length() ||
            end - start <= SHORT_RUN_CACHE_MAX_CHARS) return false;
        for (int index = start; index < end; index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) return false;
        }
        return true;
    }

    static boolean isLongRunRasterCandidate(CharSequence value, int start, int end) {
        if (value == null || start < 0 || end > value.length() ||
            end - start <= SHORT_RUN_CACHE_MAX_CHARS) return false;
        for (int index = start; index < end; index++) {
            if (Character.isISOControl(value.charAt(index))) return false;
        }
        return true;
    }

    static boolean visualBoundsOverlap(float firstLeft, float firstTop,
                                       float firstRight, float firstBottom,
                                       float secondLeft, float secondTop,
                                       float secondRight, float secondBottom) {
        return firstRight > firstLeft && firstBottom > firstTop &&
            secondRight > secondLeft && secondBottom > secondTop &&
            firstLeft < secondRight && firstRight > secondLeft &&
            firstTop < secondBottom && firstBottom > secondTop;
    }

    boolean create(android.view.Surface surface, int width, int height) {
        if (failed || !isNativeAvailable() || surface == null) return false;
        try {
            nativeHandle = nativeCreate(surface, Math.max(1, width), Math.max(1, height));
            if (nativeHandle == 0L) {
                failed = true;
                return false;
            }
            consecutiveRenderFailures = 0;
            return true;
        } catch (RuntimeException | LinkageError error) {
            failed = true;
            return false;
        }
    }

    void destroy() {
        long handle = nativeHandle;
        nativeHandle = 0L;
        if (handle != 0L) {
            try {
                nativeDestroy(handle);
            } catch (RuntimeException | LinkageError ignored) {
                failed = true;
            }
        }
        composer.reset();
    }

    boolean isFailed() {
        return failed;
    }

    RenderResult render(TerminalGpuFrame frame) {
        if (failed || nativeHandle == 0L || frame == null || !frame.contentReady) {
            return RenderResult.INCOMPLETE;
        }
        long stageStarted = System.nanoTime();
        composer.accept(frame);
        lastAcceptNanos = System.nanoTime() - stageStarted;
        maxAcceptNanos = Math.max(maxAcceptNanos, lastAcceptNanos);
        PreparedFrame prepared = null;
        long prepareNanos = 0L;
        for (int attempt = 0; attempt <= MAX_REBUILD_ATTEMPTS; attempt++) {
            stageStarted = System.nanoTime();
            prepared = composer.prepare(frame);
            prepareNanos += System.nanoTime() - stageStarted;
            if (prepared.complete) break;
            if (!prepared.rebuildRequired || attempt == MAX_REBUILD_ATTEMPTS) {
                lastPrepareNanos = prepareNanos;
                maxPrepareNanos = Math.max(maxPrepareNanos, lastPrepareNanos);
                lastNativeNanos = 0L;
                return RenderResult.INCOMPLETE;
            }
            composer.resetAtlas();
        }
        lastPrepareNanos = prepareNanos;
        maxPrepareNanos = Math.max(maxPrepareNanos, lastPrepareNanos);
        if (prepared == null || !prepared.complete) return RenderResult.INCOMPLETE;

        int result;
        stageStarted = System.nanoTime();
        try {
            float viewportYOffset = (float) (-((double) frame.viewportTopRow *
                frame.fontLineSpacing) - frame.viewportPixelOffset);
            result = nativeRender(nativeHandle,
                frame.viewWidth, frame.viewHeight, frame.backgroundColor, viewportYOffset,
                prepared.vertices, prepared.vertexBytes, prepared.vertexGeneration,
                prepared.mask.bitmap, prepared.mask.generation,
                prepared.mask.dirtyLeft, prepared.mask.dirtyTop,
                prepared.mask.dirtyRight, prepared.mask.dirtyBottom,
                prepared.color.bitmap, prepared.color.generation,
                prepared.color.dirtyLeft, prepared.color.dirtyTop,
                prepared.color.dirtyRight, prepared.color.dirtyBottom,
                prepared.runMask.bitmap, prepared.runMask.generation,
                prepared.runMask.dirtyLeft, prepared.runMask.dirtyTop,
                prepared.runMask.dirtyRight, prepared.runMask.dirtyBottom);
        } catch (RuntimeException | LinkageError error) {
            result = -1;
        }
        lastNativeNanos = System.nanoTime() - stageStarted;
        maxNativeNanos = Math.max(maxNativeNanos, lastNativeNanos);
        if (result < 0) {
            consecutiveRenderFailures++;
            failed = true;
            return RenderResult.FAILED;
        }
        if (result == 0) return RenderResult.RETRY;
        consecutiveRenderFailures = 0;
        composer.markPresented(frame);
        return RenderResult.PRESENTED;
    }

    String diagnostics() {
        return "native=" + nativeHandle + " failed=" + failed +
            " rows=" + composer.rows.size() + " glyphs=" + composer.glyphCache.size() +
            " maskGen=" + composer.mask.generation + " colorGen=" + composer.color.generation +
            " runMaskGen=" + composer.runMask.generation + " runAtlas=" +
            composer.runMask.bitmap.getWidth() + 'x' + composer.runMask.bitmap.getHeight() + ' ' +
            " instances=" + composer.lastInstanceCount + " vertices=" +
            (composer.lastInstanceCount * 6L) + " atlasResets=" + composer.atlasResets +
            " vertexPack=" + composer.vertexPacks + '/' + composer.vertexPackReuses +
            " stageUs=" + (lastAcceptNanos / 1000L) + '/' + (lastPrepareNanos / 1000L) +
            '/' + (lastNativeNanos / 1000L) + " stageMaxUs=" + (maxAcceptNanos / 1000L) +
            '/' + (maxPrepareNanos / 1000L) + '/' + (maxNativeNanos / 1000L) +
            " rowBatch=" + composer.lastCompiledRows + '/' + composer.lastReusedRows +
            '/' + composer.lastBulkCopiedRows + " rowBatchTotal=" + composer.compiledRows +
            '/' + composer.reusedRows + " rowInput=" + composer.lastUpdatedRows + '/' +
            composer.lastUnchangedRows + " rowInputTotal=" + composer.updatedRows + '/' +
            composer.unchangedRows + " rowBuffers=" + composer.rowVertexBufferAllocations +
            '/' + composer.rowVertexBufferReuses + " compileUs=" +
            (composer.lastCompileNanos / 1000L) +
            '/' + (composer.maxCompileNanos / 1000L) + " rowSnapshotUs=" +
            (composer.lastRowSnapshotNanos / 1000L) + '/' +
            (composer.maxRowSnapshotNanos / 1000L) + " framePackUs=" +
            (composer.lastFramePackNanos / 1000L) + '/' +
            (composer.maxFramePackNanos / 1000L) + " shape=" + composer.shapedRuns + " shapeUs=" +
            (composer.shapeNanos / 1000L) + " singleShape=" + composer.singleShapeHits + '/' +
            composer.singleShapeMisses + '/' + composer.singleShapeFallbacks + " raster=" +
            composer.rasterizedGlyphs + '/' + composer.emptyGlyphs + " rasterUs=" +
            (composer.rasterNanos / 1000L) + " runChars=" + composer.shapedTextChars +
            " runLen=" + composer.runLengthOne + '/' + composer.runLengthTwoToFour + '/' +
            composer.runLengthFiveToSixteen + '/' + composer.runLengthLong + " shortShape=" +
            composer.shortShapeHits + '/' + composer.shortShapeMisses + '/' +
            composer.shortRunCache.size() + " directTexture=" + composer.directTextureHits +
            '/' + composer.directTextureMisses + " hotTexture=" + composer.hotTextureHits +
            '/' + composer.hotTextureMisses + " runRaster=" +
            composer.lastRunRasterized + '/' + composer.lastRunFallbacks + '/' +
            composer.lastRunColorFallbacks + " runRasterUs=" +
            (composer.lastRunRasterNanos / 1000L) + '/' +
            (composer.maxRunRasterNanos / 1000L) + " runAtlasUsed=" +
            composer.runMask.currentUsedRight + 'x' + composer.runMask.currentUsedBottom +
            " runAtlasResets=" + composer.runMask.resets + " runMixed=" +
            composer.lastRunHybridized + " runGlyphs=" +
            composer.lastRunMonochromeGlyphs + '/' + composer.lastRunColorGlyphs;
    }

    static int firstPackedRow(int viewportTopRow, float viewportPixelOffset) {
        return viewportPixelOffset < -0.01f && viewportTopRow > Integer.MIN_VALUE
            ? viewportTopRow - 1 : viewportTopRow;
    }

    static int lastPackedRowExclusive(int viewportTopRow, int screenRows,
                                      float viewportPixelOffset) {
        long last = (long) viewportTopRow + Math.max(0, screenRows) +
            (viewportPixelOffset > 0.01f ? 1L : 0L);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(Integer.MIN_VALUE, last));
    }

    static boolean canReusePackedVertices(int packedFirstRow, int packedLastRowExclusive,
                                           long packedCommandGeneration,
                                           long packedModelRevision, long packedAtlasEpoch,
                                           int requiredFirstRow, int requiredLastRowExclusive,
                                           long commandGeneration, long modelRevision,
                                           long atlasEpoch) {
        return packedFirstRow == requiredFirstRow &&
            packedLastRowExclusive == requiredLastRowExclusive &&
            packedCommandGeneration == commandGeneration &&
            packedModelRevision == modelRevision && packedAtlasEpoch == atlasEpoch;
    }

    static boolean needsRowCompilation(long compiledAtlasEpoch, long atlasEpoch,
                                       boolean usesRunMask, int compiledRunMaskGeneration,
                                       int runMaskGeneration) {
        return compiledAtlasEpoch != atlasEpoch ||
            (usesRunMask && compiledRunMaskGeneration != runMaskGeneration);
    }

    enum RenderResult { PRESENTED, RETRY, INCOMPLETE, FAILED }

    private static native long nativeCreate(android.view.Surface surface, int width, int height);
    private static native void nativeDestroy(long handle);
    private static native int nativeRender(long handle, int width, int height, int backgroundColor,
                                           float viewportYOffset,
                                           ByteBuffer vertices, int vertexBytes,
                                           long vertexGeneration,
                                           Bitmap maskBitmap, int maskGeneration,
                                           int maskLeft, int maskTop, int maskRight, int maskBottom,
                                           Bitmap colorBitmap, int colorGeneration,
                                           int colorLeft, int colorTop, int colorRight, int colorBottom,
                                           Bitmap runMaskBitmap, int runMaskGeneration,
                                           int runMaskLeft, int runMaskTop,
                                           int runMaskRight, int runMaskBottom);

    private static final class FrameComposer {
        final LinkedHashMap<Integer, CompiledRow> rows =
            new LinkedHashMap<>(128, 0.75f, true);
        final LinkedHashMap<GlyphKey, GlyphTexture> glyphCache =
            new LinkedHashMap<>(512, 0.75f, true);
        final GlyphHotCache glyphHotCache = new GlyphHotCache(GLYPH_HOT_CACHE_ENTRIES);
        final Atlas mask = new Atlas(INITIAL_MASK_ATLAS, MAX_MASK_ATLAS, Bitmap.Config.ALPHA_8);
        final Atlas color = new Atlas(INITIAL_COLOR_ATLAS, MAX_COLOR_ATLAS, Bitmap.Config.ARGB_8888);
        final RunAtlas runMask = new RunAtlas();
        final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final RectF glyphBounds = new RectF();
        final Rect scratchSource = new Rect();
        final RectF atlasDestination = new RectF();
        final SingleGlyphCache singleGlyphCache =
            new SingleGlyphCache(SINGLE_GLYPH_CACHE_ENTRIES, SINGLE_GLYPH_CACHE_PROBES);
        final ShortRunCache shortRunCache =
            new ShortRunCache(SHORT_RUN_CACHE_ENTRIES, SHORT_RUN_CACHE_PROBES);
        final Canvas scratchCanvas = new Canvas();
        final TerminalVertexBatch rowBuilder = new TerminalVertexBatch(32);
        final int[] glyphIdScratch = new int[1];
        final float[] glyphPositionScratch = new float[2];
        GlyphTexture[] runTextureScratch = new GlyphTexture[256];
        float[] runBoundsScratch = new float[256 * 4];
        int[] runGlyphIdsScratch = new int[256];
        float[] runGlyphPositionsScratch = new float[256 * 2];
        Font[] runFontsScratch = new Font[8];
        Bitmap scratchBitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        int[] scratchPixels = new int[128 * 128];
        int scratchUsedWidth;
        int scratchUsedHeight;
        ByteBuffer vertices = ByteBuffer.allocateDirect(64 * TerminalVertexBatch.INSTANCE_BYTES)
            .order(ByteOrder.nativeOrder());
        IntBuffer vertexWords = vertices.asIntBuffer();
        int vertexBytes;
        long vertexGeneration;
        long vertexPacks;
        long vertexPackReuses;
        int packedFirstRow = Integer.MIN_VALUE;
        int packedLastRowExclusive = Integer.MIN_VALUE;
        long packedCommandGeneration = Long.MIN_VALUE;
        long packedModelRevision = Long.MIN_VALUE;
        long packedAtlasEpoch = Long.MIN_VALUE;
        int lastInstanceCount;
        int lastCompiledRows;
        int lastReusedRows;
        int lastBulkCopiedRows;
        int lastUpdatedRows;
        int lastUnchangedRows;
        long compiledRows;
        long reusedRows;
        long updatedRows;
        long unchangedRows;
        long rowVertexBufferAllocations;
        long rowVertexBufferReuses;
        long lastCompileNanos;
        long maxCompileNanos;
        long lastRowSnapshotNanos;
        long maxRowSnapshotNanos;
        long lastFramePackNanos;
        long maxFramePackNanos;
        long shapedRuns;
        long shapeNanos;
        long singleShapeHits;
        long singleShapeMisses;
        long singleShapeFallbacks;
        long shortShapeHits;
        long shortShapeMisses;
        long rasterizedGlyphs;
        long emptyGlyphs;
        long rasterNanos;
        long shapedTextChars;
        long runLengthOne;
        long runLengthTwoToFour;
        long runLengthFiveToSixteen;
        long runLengthLong;
        long directTextureHits;
        long directTextureMisses;
        long hotTextureHits;
        long hotTextureMisses;
        int lastRunRasterized;
        int lastRunFallbacks;
        int lastRunColorFallbacks;
        int lastRunHybridized;
        int lastRunMonochromeGlyphs;
        int lastRunColorGlyphs;
        long lastRunRasterNanos;
        long maxRunRasterNanos;
        long atlasEpoch = 1L;
        int atlasResets;
        int metricTextSize = -1;
        int metricTypefaceIdentity;
        float metricFontWidth;
        int metricLineSpacing;
        int metricFontAscent;
        boolean shapePaintBold;
        boolean shapePaintItalic;
        boolean compilingRowUsesRunMask;
        int lastTopRow = Integer.MIN_VALUE;
        long lastCommandGeneration = Long.MIN_VALUE;

        FrameComposer() {
            scratchCanvas.setBitmap(scratchBitmap);
            bitmapPaint.setFilterBitmap(false);
        }

        void accept(TerminalGpuFrame frame) {
            boolean metricsChanged = metricTextSize != frame.textSize ||
                metricTypefaceIdentity != System.identityHashCode(frame.typeface) ||
                Math.abs(metricFontWidth - frame.fontWidth) > 0.01f ||
                metricLineSpacing != frame.fontLineSpacing || metricFontAscent != frame.fontAscent;
            if (metricsChanged) {
                rows.clear();
                metricTextSize = frame.textSize;
                metricTypefaceIdentity = System.identityHashCode(frame.typeface);
                metricFontWidth = frame.fontWidth;
                metricLineSpacing = frame.fontLineSpacing;
                metricFontAscent = frame.fontAscent;
                configureShapePaint(frame);
                singleGlyphCache.clear();
                shortRunCache.clear();
                resetAtlas();
            }
            lastUpdatedRows = 0;
            lastUnchangedRows = 0;
            for (TerminalGpuFrame.Row row : frame.rows) {
                CompiledRow retained = rows.get(row.logicalRow);
                if (retained != null && retained.source.hasSameContent(row)) {
                    lastUnchangedRows++;
                    unchangedRows++;
                    continue;
                }
                if (retained == null) {
                    rows.put(row.logicalRow, new CompiledRow(row));
                } else {
                    retained.replaceSource(row);
                }
                lastUpdatedRows++;
                updatedRows++;
            }
            if (frame.fullFrame || lastUpdatedRows > 0) invalidatePackedVertices();
            trimRows(frame.viewportTopRow, frame.screenRows);
            lastTopRow = frame.viewportTopRow;
            lastCommandGeneration = frame.commandGeneration;
        }

        private void configureShapePaint(TerminalGpuFrame frame) {
            shapePaint.reset();
            shapePaint.setAntiAlias(true);
            shapePaint.setSubpixelText(true);
            shapePaint.setTypeface(frame.typeface == null ? Typeface.MONOSPACE : frame.typeface);
            shapePaint.setTextSize(frame.textSize);
            shapePaint.setColor(Color.WHITE);
            shapePaint.setStyle(Paint.Style.FILL);
            shapePaintBold = false;
            shapePaintItalic = false;
        }

        private void configureShapeStyle(boolean bold, boolean italic) {
            if (shapePaintBold != bold) {
                shapePaint.setFakeBoldText(bold);
                shapePaintBold = bold;
            }
            if (shapePaintItalic != italic) {
                shapePaint.setTextSkewX(italic ? -0.35f : 0f);
                shapePaintItalic = italic;
            }
        }

        PreparedFrame prepare(TerminalGpuFrame frame) {
            lastCompiledRows = 0;
            lastReusedRows = 0;
            lastBulkCopiedRows = 0;
            lastCompileNanos = 0L;
            lastRowSnapshotNanos = 0L;
            lastFramePackNanos = 0L;
            lastRunRasterized = 0;
            lastRunFallbacks = 0;
            lastRunColorFallbacks = 0;
            lastRunHybridized = 0;
            lastRunMonochromeGlyphs = 0;
            lastRunColorGlyphs = 0;
            lastRunRasterNanos = 0L;
            int first = firstPackedRow(frame.viewportTopRow, frame.viewportPixelOffset);
            int last = lastPackedRowExclusive(frame.viewportTopRow, frame.screenRows,
                frame.viewportPixelOffset);
            int previousRunMaskGeneration = runMask.generation;
            runMask.beginFrame(frame);
            if (runMask.generation != previousRunMaskGeneration) invalidatePackedVertices();
            if (canReusePackedVertices(packedFirstRow, packedLastRowExclusive,
                    packedCommandGeneration, packedModelRevision, packedAtlasEpoch, first, last,
                    frame.commandGeneration,
                    frame.modelRevision, atlasEpoch)) {
                int reused = Math.max(0, last - first);
                lastReusedRows = reused;
                reusedRows += reused;
                vertexPackReuses++;
                vertices.position(0);
                mask.finishDirty();
                color.finishDirty();
                runMask.finishDirty();
                return PreparedFrame.complete(vertices, vertexBytes, vertexGeneration,
                    mask, color, runMask);
            }

            invalidatePackedVertices();
            vertexBytes = 0;
            vertices.clear();
            boolean rebuildRequired = false;
            for (int logicalRow = first; logicalRow < last; logicalRow++) {
                CompiledRow row = rows.get(logicalRow);
                if (row == null) return PreparedFrame.incomplete(false, mask, color, runMask);
                if (needsRowCompilation(row.compiledAtlasEpoch, atlasEpoch, row.usesRunMask,
                        row.compiledRunMaskGeneration, runMask.generation)) {
                    long compileStarted = System.nanoTime();
                    if (!compileRow(row, frame)) {
                        lastCompileNanos += System.nanoTime() - compileStarted;
                        rebuildRequired = true;
                        break;
                    }
                    lastCompileNanos += System.nanoTime() - compileStarted;
                    lastCompiledRows++;
                    compiledRows++;
                } else {
                    lastReusedRows++;
                    reusedRows++;
                }
                long packStarted = System.nanoTime();
                appendCompiledRow(row);
                lastFramePackNanos += System.nanoTime() - packStarted;
                lastBulkCopiedRows++;
            }
            maxCompileNanos = Math.max(maxCompileNanos, lastCompileNanos);
            maxRowSnapshotNanos = Math.max(maxRowSnapshotNanos, lastRowSnapshotNanos);
            maxFramePackNanos = Math.max(maxFramePackNanos, lastFramePackNanos);
            maxRunRasterNanos = Math.max(maxRunRasterNanos, lastRunRasterNanos);
            if (rebuildRequired) return PreparedFrame.incomplete(true, mask, color, runMask);
            vertices.position(0);
            lastInstanceCount = vertexBytes / TerminalVertexBatch.INSTANCE_BYTES;
            mask.finishDirty();
            color.finishDirty();
            runMask.finishDirty();
            vertexGeneration++;
            vertexPacks++;
            packedFirstRow = first;
            packedLastRowExclusive = last;
            packedCommandGeneration = frame.commandGeneration;
            packedModelRevision = frame.modelRevision;
            packedAtlasEpoch = atlasEpoch;
            return PreparedFrame.complete(vertices, vertexBytes, vertexGeneration,
                mask, color, runMask);
        }

        private boolean compileRow(CompiledRow row, TerminalGpuFrame frame) {
            rowBuilder.clear();
            compilingRowUsesRunMask = false;
            float rowY = (float) ((double) frame.fontLineSpacing + frame.fontAscent +
                (double) row.source.logicalRow * frame.fontLineSpacing);
            TerminalGpuFrame.RectBatch backgrounds = row.source.backgrounds;
            for (int index = 0; index < backgrounds.size(); index++) {
                appendRect(rowBuilder, backgrounds.left(index), backgrounds.top(index) + rowY,
                    backgrounds.right(index), backgrounds.bottom(index) + rowY,
                    backgrounds.color(index));
            }
            float baseline = rowY - frame.fontAscent;
            for (TerminalGpuFrame.TextRun run : row.source.text) {
                if (!compileRun(rowBuilder, run, frame, baseline)) return false;
            }
            TerminalGpuFrame.RectBatch decorations = row.source.decorations;
            for (int index = 0; index < decorations.size(); index++) {
                appendRect(rowBuilder, decorations.left(index), decorations.top(index) + rowY,
                    decorations.right(index), decorations.bottom(index) + rowY,
                    decorations.color(index));
            }
            long snapshotStarted = System.nanoTime();
            int wordCount = rowBuilder.wordCount();
            if (wordCount == 0) {
                row.vertexWordCount = 0;
            } else {
                if (row.vertexData.length >= wordCount) rowVertexBufferReuses++;
                else rowVertexBufferAllocations++;
                row.vertexData = rowBuilder.copyWords(row.vertexData);
                row.vertexWordCount = wordCount;
            }
            lastRowSnapshotNanos += System.nanoTime() - snapshotStarted;
            row.usesRunMask = compilingRowUsesRunMask;
            row.compiledAtlasEpoch = atlasEpoch;
            row.compiledRunMaskGeneration = runMask.generation;
            return true;
        }

        private boolean compileRun(TerminalVertexBatch batch, TerminalGpuFrame.TextRun run,
                                   TerminalGpuFrame frame, float baseline) {
            if (run.value == null || run.valueEnd <= run.valueStart) return true;
            int textLength = run.valueEnd - run.valueStart;
            shapedTextChars += textLength;
            if (textLength == 1) runLengthOne++;
            else if (textLength <= 4) runLengthTwoToFour++;
            else if (textLength <= 16) runLengthFiveToSixteen++;
            else runLengthLong++;
            if (isSingleGlyphShapeCandidate(run.value, run.valueStart, run.valueEnd)) {
                char character = run.value.charAt(run.valueStart);
                SingleGlyph glyph = resolveSingleGlyph(character, run.bold, run.italic);
                if (glyph.valid) return appendCachedSingleGlyph(batch, run, frame, baseline, glyph);
                singleShapeFallbacks++;
            }
            boolean cacheShortRun = textLength > 1 && textLength <= SHORT_RUN_CACHE_MAX_CHARS;
            if (cacheShortRun) {
                ShapedSequence cached = shortRunCache.find(run);
                if (cached != null) {
                    shortShapeHits++;
                    return appendShapedSequence(batch, run, frame, baseline, cached);
                }
                shortShapeMisses++;
            }
            long started = System.nanoTime();
            PositionedGlyphs positioned;
            try {
                configureShapeStyle(run.bold, run.italic);
                positioned = TextRunShaper.shapeTextRun(run.value, run.valueStart,
                    run.valueEnd - run.valueStart, run.valueStart,
                    run.valueEnd - run.valueStart, 0f, 0f, false, shapePaint);
            } catch (RuntimeException | LinkageError error) {
                shapeNanos += System.nanoTime() - started;
                return false;
            }
            shapeNanos += System.nanoTime() - started;
            shapedRuns++;
            if (cacheShortRun) {
                ShapedSequence sequence = shortRunCache.store(run, positioned);
                return appendShapedSequence(batch, run, frame, baseline, sequence);
            }
            if (isLongRunRasterCandidate(run.value, run.valueStart, run.valueEnd)) {
                long rasterStarted = System.nanoTime();
                int result = appendHybridRasterizedRun(batch, run, frame, baseline, positioned);
                lastRunRasterNanos += System.nanoTime() - rasterStarted;
                if (result == RUN_RASTER_REBUILD) return false;
                if (result == RUN_RASTER_CACHED) {
                    lastRunRasterized++;
                    compilingRowUsesRunMask = true;
                    return true;
                }
                if (result == RUN_RASTER_EMPTY) return true;
                if (result == RUN_RASTER_EXACT_FALLBACK) lastRunColorFallbacks++;
                else lastRunFallbacks++;
            }
            return appendPositionedGlyphs(batch, run, frame, baseline, positioned);
        }

        private int appendHybridRasterizedRun(TerminalVertexBatch batch,
                                              TerminalGpuFrame.TextRun run,
                                              TerminalGpuFrame frame, float baseline,
                                              PositionedGlyphs positioned) {
            if (!Float.isFinite(run.left) || !Float.isFinite(run.width) || run.width <= 0f ||
                !Float.isFinite(baseline) || frame.fontLineSpacing <= 0) {
                return RUN_RASTER_FALLBACK;
            }
            float measured = run.measuredWidth > 0f ? run.measuredWidth : positioned.getAdvance();
            float scaleX = measured > 0f && Math.abs(measured - run.width) > 0.01f
                ? run.width / measured : 1f;
            if (!Float.isFinite(scaleX) || scaleX <= 0f) return RUN_RASTER_FALLBACK;

            int glyphCount = positioned.glyphCount();
            ensureRunScratchCapacity(glyphCount);
            int monochromeCount = 0;
            int colorCount = 0;
            float monochromeLeft = Float.POSITIVE_INFINITY;
            float monochromeTop = Float.POSITIVE_INFINITY;
            float monochromeRight = Float.NEGATIVE_INFINITY;
            float monochromeBottom = Float.NEGATIVE_INFINITY;
            for (int index = 0; index < glyphCount; index++) {
                Font font = positioned.getFont(index);
                int boundsOffset = index * 4;
                if (font == null) {
                    runTextureScratch[index] = null;
                    runBoundsScratch[boundsOffset] = Float.NaN;
                    continue;
                }
                GlyphTexture texture = resolveGlyphTexture(font, positioned.getGlyphId(index),
                    frame.textSize, run.bold, run.italic);
                if (texture == null) return RUN_RASTER_REBUILD;
                runTextureScratch[index] = texture;
                if (texture.empty) {
                    runBoundsScratch[boundsOffset] = Float.NaN;
                    continue;
                }
                float left = positioned.getGlyphX(index) * scaleX +
                    texture.originLeft * scaleX;
                float top = positioned.getGlyphY(index) + texture.originTop;
                float right = left + texture.width * scaleX;
                float bottom = top + texture.height;
                runBoundsScratch[boundsOffset] = left;
                runBoundsScratch[boundsOffset + 1] = top;
                runBoundsScratch[boundsOffset + 2] = right;
                runBoundsScratch[boundsOffset + 3] = bottom;
                if (texture.mode == 2) {
                    colorCount++;
                } else {
                    monochromeCount++;
                    monochromeLeft = Math.min(monochromeLeft, left);
                    monochromeTop = Math.min(monochromeTop, top);
                    monochromeRight = Math.max(monochromeRight, right);
                    monochromeBottom = Math.max(monochromeBottom, bottom);
                }
            }
            if (monochromeCount == 0) {
                return colorCount == 0 ? RUN_RASTER_EMPTY : RUN_RASTER_EXACT_FALLBACK;
            }
            if (colorCount > 0 && hasColorMonochromeOverlap(glyphCount, scaleX)) {
                return RUN_RASTER_EXACT_FALLBACK;
            }

            int contentLeft = (int) Math.floor(monochromeLeft);
            int contentTop = (int) Math.floor(monochromeTop);
            int contentRight = (int) Math.ceil(monochromeRight);
            int contentBottom = (int) Math.ceil(monochromeBottom);
            int tileWidth = contentRight - contentLeft;
            int tileHeight = contentBottom - contentTop;
            if (tileWidth <= 0 || tileHeight <= 0 ||
                tileWidth > RUN_ATLAS_MAX_DIMENSION || tileHeight > RUN_ATLAS_MAX_DIMENSION) {
                return RUN_RASTER_FALLBACK;
            }
            if (!runMask.canFit(tileWidth, tileHeight)) return RUN_RASTER_FALLBACK;
            AtlasAllocation allocation = runMask.allocate(tileWidth, tileHeight);
            if (allocation == null) return RUN_RASTER_REBUILD;

            configureShapeStyle(run.bold, run.italic);
            int save = runMask.canvas.save();
            try {
                runMask.canvas.clipRect(allocation.x, allocation.y,
                    allocation.x + tileWidth, allocation.y + tileHeight);
                runMask.canvas.translate(allocation.x - contentLeft,
                    allocation.y - contentTop);
                if (Math.abs(scaleX - 1f) > 0.0001f) runMask.canvas.scale(scaleX, 1f);
                drawMonochromeGlyphs(runMask.canvas, positioned, glyphCount);
            } catch (RuntimeException | LinkageError error) {
                return RUN_RASTER_FALLBACK;
            } finally {
                runMask.canvas.restoreToCount(save);
            }

            float left = run.left + contentLeft;
            float top = baseline + contentTop;
            batch.appendPackedQuad(left, top, left + tileWidth, top + tileHeight,
                allocation.x | (allocation.y << 16),
                (allocation.x + tileWidth) | ((allocation.y + tileHeight) << 16),
                TerminalVertexBatch.packRgba(run.color), 3);
            if (colorCount > 0) appendColorGlyphs(batch, run, baseline, glyphCount);
            if (colorCount > 0) lastRunHybridized++;
            lastRunMonochromeGlyphs += monochromeCount;
            lastRunColorGlyphs += colorCount;
            return RUN_RASTER_CACHED;
        }

        private boolean hasColorMonochromeOverlap(int glyphCount, float scaleX) {
            for (int colorIndex = 0; colorIndex < glyphCount; colorIndex++) {
                GlyphTexture colorTexture = runTextureScratch[colorIndex];
                if (colorTexture == null || colorTexture.empty || colorTexture.mode != 2) continue;
                int colorOffset = colorIndex * 4;
                float colorLeft = runBoundsScratch[colorOffset] + colorTexture.inkLeft * scaleX;
                float colorTop = runBoundsScratch[colorOffset + 1] + colorTexture.inkTop;
                float colorRight = runBoundsScratch[colorOffset] + colorTexture.inkRight * scaleX;
                float colorBottom = runBoundsScratch[colorOffset + 1] + colorTexture.inkBottom;
                for (int monoIndex = 0; monoIndex < glyphCount; monoIndex++) {
                    GlyphTexture monoTexture = runTextureScratch[monoIndex];
                    if (monoTexture == null || monoTexture.empty || monoTexture.mode == 2) continue;
                    int monoOffset = monoIndex * 4;
                    float monoLeft = runBoundsScratch[monoOffset] + monoTexture.inkLeft * scaleX;
                    float monoTop = runBoundsScratch[monoOffset + 1] + monoTexture.inkTop;
                    float monoRight = runBoundsScratch[monoOffset] + monoTexture.inkRight * scaleX;
                    float monoBottom = runBoundsScratch[monoOffset + 1] + monoTexture.inkBottom;
                    if (visualBoundsOverlap(colorLeft, colorTop, colorRight, colorBottom,
                        monoLeft, monoTop, monoRight, monoBottom)) return true;
                }
            }
            return false;
        }

        private void drawMonochromeGlyphs(Canvas canvas, PositionedGlyphs positioned,
                                          int glyphCount) {
            int fontCount = 0;
            for (int index = 0; index < glyphCount; index++) {
                GlyphTexture texture = runTextureScratch[index];
                Font font = positioned.getFont(index);
                if (texture == null || texture.empty || texture.mode == 2 || font == null) continue;
                boolean known = false;
                for (int fontIndex = 0; fontIndex < fontCount; fontIndex++) {
                    if (runFontsScratch[fontIndex].equals(font)) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    if (fontCount == runFontsScratch.length) {
                        runFontsScratch = java.util.Arrays.copyOf(runFontsScratch,
                            runFontsScratch.length * 2);
                    }
                    runFontsScratch[fontCount++] = font;
                }
            }
            for (int fontIndex = 0; fontIndex < fontCount; fontIndex++) {
                Font font = runFontsScratch[fontIndex];
                int drawCount = 0;
                for (int index = 0; index < glyphCount; index++) {
                    GlyphTexture texture = runTextureScratch[index];
                    Font glyphFont = positioned.getFont(index);
                    if (texture == null || texture.empty || texture.mode == 2 ||
                        glyphFont == null || !font.equals(glyphFont)) continue;
                    runGlyphIdsScratch[drawCount] = positioned.getGlyphId(index);
                    runGlyphPositionsScratch[drawCount * 2] = positioned.getGlyphX(index);
                    runGlyphPositionsScratch[drawCount * 2 + 1] = positioned.getGlyphY(index);
                    drawCount++;
                }
                if (drawCount > 0) {
                    canvas.drawGlyphs(runGlyphIdsScratch, 0, runGlyphPositionsScratch, 0,
                        drawCount, font, shapePaint);
                }
            }
        }

        private void appendColorGlyphs(TerminalVertexBatch batch,
                                       TerminalGpuFrame.TextRun run, float baseline,
                                       int glyphCount) {
            int packedColor = TerminalVertexBatch.packRgba(run.color);
            for (int index = 0; index < glyphCount; index++) {
                GlyphTexture texture = runTextureScratch[index];
                if (texture == null || texture.empty || texture.mode != 2) continue;
                int offset = index * 4;
                appendGlyph(batch, run.left + runBoundsScratch[offset],
                    baseline + runBoundsScratch[offset + 1],
                    run.left + runBoundsScratch[offset + 2],
                    baseline + runBoundsScratch[offset + 3], texture, packedColor, texture.mode);
            }
        }

        private void ensureRunScratchCapacity(int glyphCount) {
            if (runTextureScratch.length >= glyphCount) return;
            int capacity = runTextureScratch.length;
            while (capacity < glyphCount) capacity *= 2;
            runTextureScratch = java.util.Arrays.copyOf(runTextureScratch, capacity);
            runBoundsScratch = java.util.Arrays.copyOf(runBoundsScratch, capacity * 4);
            runGlyphIdsScratch = java.util.Arrays.copyOf(runGlyphIdsScratch, capacity);
            runGlyphPositionsScratch = java.util.Arrays.copyOf(runGlyphPositionsScratch,
                capacity * 2);
        }

        private boolean appendPositionedGlyphs(TerminalVertexBatch batch,
                                                TerminalGpuFrame.TextRun run,
                                                TerminalGpuFrame frame, float baseline,
                                                PositionedGlyphs positioned) {
            float measured = run.measuredWidth > 0f ? run.measuredWidth :
                positioned.getAdvance();
            float scaleX = measured > 0f && Math.abs(measured - run.width) > 0.01f
                ? run.width / measured : 1f;
            int glyphCount = positioned.glyphCount();
            int packedColor = TerminalVertexBatch.packRgba(run.color);
            for (int index = 0; index < glyphCount; index++) {
                int glyphId = positioned.getGlyphId(index);
                Font font = positioned.getFont(index);
                if (font == null) continue;
                GlyphTexture texture = resolveGlyphTexture(font, glyphId, frame.textSize,
                    run.bold, run.italic);
                if (texture == null) return false;
                if (texture.empty) continue;
                float left = run.left + positioned.getGlyphX(index) * scaleX +
                    texture.originLeft * scaleX;
                float top = baseline + positioned.getGlyphY(index) + texture.originTop;
                float right = left + texture.width * scaleX;
                float bottom = top + texture.height;
                appendGlyph(batch, left, top, right, bottom, texture,
                    packedColor, texture.mode);
            }
            return true;
        }

        private boolean appendShapedSequence(TerminalVertexBatch batch,
                                             TerminalGpuFrame.TextRun run,
                                             TerminalGpuFrame frame, float baseline,
                                             ShapedSequence positioned) {
            if (positioned.textureAtlasEpoch != atlasEpoch) {
                java.util.Arrays.fill(positioned.textures, null);
                positioned.textureAtlasEpoch = atlasEpoch;
            }
            float measured = run.measuredWidth > 0f ? run.measuredWidth : positioned.advance;
            float scaleX = measured > 0f && Math.abs(measured - run.width) > 0.01f
                ? run.width / measured : 1f;
            int packedColor = TerminalVertexBatch.packRgba(run.color);
            for (int index = 0; index < positioned.glyphCount; index++) {
                int glyphId = positioned.glyphIds[index];
                Font font = positioned.fonts[index];
                if (font == null) continue;
                GlyphTexture texture = positioned.textures[index];
                if (texture == null) {
                    directTextureMisses++;
                    texture = resolveGlyphTexture(font, glyphId, frame.textSize,
                        run.bold, run.italic);
                    if (texture == null) return false;
                    positioned.textures[index] = texture;
                } else {
                    directTextureHits++;
                }
                if (texture.empty) continue;
                float left = run.left + positioned.positions[index * 2] * scaleX +
                    texture.originLeft * scaleX;
                float top = baseline + positioned.positions[index * 2 + 1] + texture.originTop;
                appendGlyph(batch, left, top, left + texture.width * scaleX,
                    top + texture.height, texture, packedColor, texture.mode);
            }
            return true;
        }

        private SingleGlyph resolveSingleGlyph(char character, boolean bold, boolean italic) {
            int style = (bold ? 1 : 0) | (italic ? 2 : 0);
            int key = (style << 16) | character;
            SingleGlyph cached = singleGlyphCache.find(key);
            if (cached != null) {
                singleShapeHits++;
                return cached;
            }
            singleShapeMisses++;
            String source = String.valueOf(character);
            long started = System.nanoTime();
            PositionedGlyphs positioned;
            try {
                configureShapeStyle(bold, italic);
                positioned = TextRunShaper.shapeTextRun(source, 0, 1,
                    0, 1, 0f, 0f, false, shapePaint);
            } catch (RuntimeException | LinkageError error) {
                shapeNanos += System.nanoTime() - started;
                cached = SingleGlyph.INVALID;
                singleGlyphCache.store(key, cached);
                return cached;
            }
            shapeNanos += System.nanoTime() - started;
            shapedRuns++;
            cached = positioned.glyphCount() == 1 && positioned.getFont(0) != null
                ? new SingleGlyph(positioned.getFont(0), positioned.getGlyphId(0),
                    positioned.getGlyphX(0), positioned.getGlyphY(0),
                    positioned.getAdvance(), true)
                : SingleGlyph.INVALID;
            singleGlyphCache.store(key, cached);
            return cached;
        }

        private boolean appendCachedSingleGlyph(TerminalVertexBatch batch,
                                                TerminalGpuFrame.TextRun run,
                                                TerminalGpuFrame frame, float baseline,
                                                SingleGlyph glyph) {
            if (glyph.font == null) return false;
            GlyphTexture texture = glyph.textureAtlasEpoch == atlasEpoch ? glyph.texture : null;
            if (texture == null) {
                directTextureMisses++;
                texture = resolveGlyphTexture(glyph.font, glyph.glyphId, frame.textSize,
                    run.bold, run.italic);
                if (texture == null) return false;
                glyph.texture = texture;
                glyph.textureAtlasEpoch = atlasEpoch;
            } else {
                directTextureHits++;
            }
            if (texture.empty) return true;
            float measured = run.measuredWidth > 0f ? run.measuredWidth : glyph.advance;
            float scaleX = measured > 0f && Math.abs(measured - run.width) > 0.01f
                ? run.width / measured : 1f;
            float left = run.left + glyph.x * scaleX + texture.originLeft * scaleX;
            float top = baseline + glyph.y + texture.originTop;
            appendGlyph(batch, left, top, left + texture.width * scaleX,
                top + texture.height, texture, TerminalVertexBatch.packRgba(run.color),
                texture.mode);
            return true;
        }

        private GlyphTexture resolveGlyphTexture(Font font, int glyphId, int textSize,
                                                  boolean bold, boolean italic) {
            GlyphTexture texture = glyphHotCache.find(font, glyphId, textSize, bold, italic);
            if (texture != null) {
                hotTextureHits++;
                return texture;
            }
            hotTextureMisses++;
            GlyphKey key = new GlyphKey(font, glyphId, textSize, bold, italic);
            texture = glyphCache.get(key);
            if (texture != null) {
                glyphHotCache.store(font, glyphId, textSize, bold, italic, texture);
                return texture;
            }
            configureShapeStyle(bold, italic);
            texture = rasterizeGlyph(font, glyphId, shapePaint);
            if (texture == null) return null;
            glyphCache.put(key, texture);
            trimGlyphCache();
            glyphHotCache.store(font, glyphId, textSize, bold, italic, texture);
            return texture;
        }

        private GlyphTexture rasterizeGlyph(Font font, int glyphId, Paint paint) {
            long rasterStarted = System.nanoTime();
            glyphBounds.set(0f, 0f, 0f, 0f);
            try {
                font.getGlyphBounds(glyphId, paint, glyphBounds);
            } catch (RuntimeException | LinkageError error) {
                rasterNanos += System.nanoTime() - rasterStarted;
                return null;
            }
            int left = (int) Math.floor(glyphBounds.left) - ATLAS_PADDING;
            int top = (int) Math.floor(glyphBounds.top) - ATLAS_PADDING;
            int right = (int) Math.ceil(glyphBounds.right) + ATLAS_PADDING;
            int bottom = (int) Math.ceil(glyphBounds.bottom) + ATLAS_PADDING;
            int width = Math.max(1, right - left);
            int height = Math.max(1, bottom - top);
            if (!ensureScratch(width, height)) {
                rasterNanos += System.nanoTime() - rasterStarted;
                return null;
            }
            int clearWidth = Math.max(width, scratchUsedWidth);
            int clearHeight = Math.max(height, scratchUsedHeight);
            int save = scratchCanvas.save();
            scratchCanvas.clipRect(0, 0, clearWidth, clearHeight);
            scratchCanvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            scratchCanvas.restoreToCount(save);
            scratchUsedWidth = width;
            scratchUsedHeight = height;
            glyphIdScratch[0] = glyphId;
            glyphPositionScratch[0] = -left;
            glyphPositionScratch[1] = -top;
            try {
                scratchCanvas.drawGlyphs(glyphIdScratch, 0, glyphPositionScratch, 0,
                    1, font, paint);
            } catch (RuntimeException | LinkageError error) {
                rasterNanos += System.nanoTime() - rasterStarted;
                return null;
            }
            int pixelCount = width * height;
            if (scratchPixels.length < pixelCount) scratchPixels = new int[pixelCount];
            scratchBitmap.getPixels(scratchPixels, 0, width, 0, 0, width, height);
            int inkLeft = width;
            int inkTop = height;
            int inkRight = 0;
            int inkBottom = 0;
            boolean colored = false;
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    int value = scratchPixels[row * width + column];
                    if (((value >>> 24) & 0xff) > 2) {
                        inkLeft = Math.min(inkLeft, column);
                        inkTop = Math.min(inkTop, row);
                        inkRight = Math.max(inkRight, column + 1);
                        inkBottom = Math.max(inkBottom, row + 1);
                        if (Math.abs(((value >>> 16) & 0xff) - ((value >>> 8) & 0xff)) > 2 ||
                            Math.abs(((value >>> 8) & 0xff) - (value & 0xff)) > 2) {
                            colored = true;
                        }
                    }
                }
            }
            if (inkRight <= inkLeft || inkBottom <= inkTop) {
                emptyGlyphs++;
                rasterNanos += System.nanoTime() - rasterStarted;
                return new GlyphTexture(null, 0, 0, 0, 0, left, top,
                    0, 0, 0, 0, 0, true);
            }
            Atlas atlas = colored ? color : mask;
            AtlasAllocation allocation = atlas.allocate(width, height);
            if (allocation == null) {
                rasterNanos += System.nanoTime() - rasterStarted;
                return null;
            }
            scratchSource.set(0, 0, width, height);
            atlasDestination.set(allocation.x, allocation.y,
                allocation.x + width, allocation.y + height);
            atlas.canvas.drawBitmap(scratchBitmap, scratchSource, atlasDestination, bitmapPaint);
            atlas.markDirty(allocation.x, allocation.y, allocation.x + width, allocation.y + height);
            rasterizedGlyphs++;
            rasterNanos += System.nanoTime() - rasterStarted;
            return new GlyphTexture(atlas, allocation.x, allocation.y, width, height,
                left, top, inkLeft, inkTop, inkRight, inkBottom,
                colored ? 2 : 1, false);
        }

        private boolean ensureScratch(int width, int height) {
            if (width <= scratchBitmap.getWidth() && height <= scratchBitmap.getHeight()) return true;
            int size = Math.max(width, height);
            size = Math.max(128, Integer.highestOneBit(size - 1) << 1);
            if (size > 1024) size = 1024;
            if (size < width || size < height) return false;
            Bitmap old = scratchBitmap;
            scratchBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            scratchCanvas.setBitmap(scratchBitmap);
            old.recycle();
            scratchUsedWidth = scratchUsedHeight = 0;
            return true;
        }

        private void appendCompiledRow(CompiledRow row) {
            int wordCount = row.vertexWordCount;
            if (wordCount == 0) return;
            int byteCount = wordCount * Integer.BYTES;
            ensureVertexCapacity(vertexBytes + byteCount);
            vertexWords.position(vertexBytes / Integer.BYTES);
            vertexWords.put(row.vertexData, 0, wordCount);
            vertexBytes += byteCount;
        }

        private static void appendRect(TerminalVertexBatch batch, float left, float top,
                                       float right, float bottom, int color) {
            batch.appendPackedQuad(left, top, right, bottom,
                0, 0, TerminalVertexBatch.packRgba(color), 0);
        }

        private static void appendGlyph(TerminalVertexBatch batch, float left, float top,
                                        float right, float bottom, GlyphTexture texture,
                                        int packedColor, int mode) {
            batch.appendPackedQuad(left, top, right, bottom,
                texture.uvLeftTop, texture.uvRightBottom, packedColor, mode);
        }

        private void ensureVertexCapacity(int required) {
            if (vertices.capacity() >= required) return;
            int capacity = vertices.capacity();
            while (capacity < required) capacity *= 2;
            ByteBuffer next = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
            ByteBuffer copy = vertices.duplicate();
            copy.position(0).limit(vertexBytes);
            next.put(copy);
            next.clear();
            vertices = next;
            vertexWords = next.asIntBuffer();
        }

        private void trimRows(int topRow, int screenRows) {
            int low = topRow - screenRows * MAX_ROW_RETENTION_SCREENS;
            int high = topRow + screenRows * (MAX_ROW_RETENTION_SCREENS + 1);
            Iterator<Map.Entry<Integer, CompiledRow>> iterator = rows.entrySet().iterator();
            while (iterator.hasNext()) {
                int row = iterator.next().getKey();
                if (row < low || row >= high) iterator.remove();
            }
        }

        private void trimGlyphCache() {
            while (glyphCache.size() > MAX_GLYPH_CACHE_ENTRIES) {
                glyphCache.remove(glyphCache.entrySet().iterator().next().getKey());
            }
        }

        void resetAtlas() {
            invalidatePackedVertices();
            mask.reset();
            color.reset();
            runMask.reset();
            glyphCache.clear();
            glyphHotCache.clear();
            atlasEpoch++;
            for (CompiledRow row : rows.values()) {
                row.compiledAtlasEpoch = Long.MIN_VALUE;
                row.vertexWordCount = 0;
                row.usesRunMask = false;
                row.compiledRunMaskGeneration = Integer.MIN_VALUE;
            }
            atlasResets++;
        }

        void reset() {
            rows.clear();
            resetAtlas();
            lastTopRow = Integer.MIN_VALUE;
            lastCommandGeneration = Long.MIN_VALUE;
        }

        private void invalidatePackedVertices() {
            packedFirstRow = Integer.MIN_VALUE;
            packedLastRowExclusive = Integer.MIN_VALUE;
            packedCommandGeneration = Long.MIN_VALUE;
            packedModelRevision = Long.MIN_VALUE;
            packedAtlasEpoch = Long.MIN_VALUE;
        }

        void markPresented(TerminalGpuFrame frame) {
            mask.clearDirty();
            color.clearDirty();
            runMask.markPresented();
            lastTopRow = frame.viewportTopRow;
            lastCommandGeneration = frame.commandGeneration;
        }

    }

    /** Exact cache for short immutable shaping results; metrics changes clear it. */
    private static final class ShortRunCache {
        private final ShapedSequence[] entries;
        private final int mask;
        private final int probes;
        private long generation;
        private int size;

        ShortRunCache(int capacity, int probes) {
            if (capacity <= 0 || (capacity & (capacity - 1)) != 0 || probes <= 0) {
                throw new IllegalArgumentException("Invalid short-run cache geometry");
            }
            entries = new ShapedSequence[capacity];
            mask = capacity - 1;
            this.probes = Math.min(probes, capacity);
        }

        ShapedSequence find(TerminalGpuFrame.TextRun run) {
            int hash = hash(run);
            int start = spread(hash) & mask;
            for (int probe = 0; probe < probes; probe++) {
                ShapedSequence entry = entries[(start + probe) & mask];
                if (entry == null) return null;
                if (entry.matches(run, hash)) {
                    entry.lastUsed = ++generation;
                    return entry;
                }
            }
            return null;
        }

        ShapedSequence store(TerminalGpuFrame.TextRun run, PositionedGlyphs positioned) {
            int hash = hash(run);
            int start = spread(hash) & mask;
            int target = start;
            long oldest = Long.MAX_VALUE;
            for (int probe = 0; probe < probes; probe++) {
                int index = (start + probe) & mask;
                ShapedSequence entry = entries[index];
                if (entry == null) {
                    target = index;
                    oldest = Long.MIN_VALUE;
                    break;
                }
                if (entry.lastUsed < oldest) {
                    oldest = entry.lastUsed;
                    target = index;
                }
            }
            if (entries[target] == null) size++;
            ShapedSequence stored = new ShapedSequence(run, positioned, hash, ++generation);
            entries[target] = stored;
            return stored;
        }

        int size() {
            return size;
        }

        void clear() {
            java.util.Arrays.fill(entries, null);
            generation = 0L;
            size = 0;
        }

        private static int hash(TerminalGpuFrame.TextRun run) {
            int hash = 0x811c9dc5;
            hash = (hash ^ (run.bold ? 1 : 0)) * 0x01000193;
            hash = (hash ^ (run.italic ? 1 : 0)) * 0x01000193;
            int length = run.valueEnd - run.valueStart;
            hash = (hash ^ length) * 0x01000193;
            for (int index = run.valueStart; index < run.valueEnd; index++) {
                hash = (hash ^ run.value.charAt(index)) * 0x01000193;
            }
            return hash;
        }

        private static int spread(int value) {
            return value ^ (value >>> 16);
        }
    }

    private static final class ShapedSequence {
        final String value;
        final boolean bold;
        final boolean italic;
        final int hash;
        final int glyphCount;
        final int[] glyphIds;
        final float[] positions;
        final Font[] fonts;
        final GlyphTexture[] textures;
        final float advance;
        long lastUsed;
        long textureAtlasEpoch = Long.MIN_VALUE;

        ShapedSequence(TerminalGpuFrame.TextRun run, PositionedGlyphs positioned,
                       int hash, long lastUsed) {
            value = run.value.substring(run.valueStart, run.valueEnd);
            bold = run.bold;
            italic = run.italic;
            this.hash = hash;
            glyphCount = positioned.glyphCount();
            glyphIds = new int[glyphCount];
            positions = new float[glyphCount * 2];
            fonts = new Font[glyphCount];
            textures = new GlyphTexture[glyphCount];
            for (int index = 0; index < glyphCount; index++) {
                glyphIds[index] = positioned.getGlyphId(index);
                positions[index * 2] = positioned.getGlyphX(index);
                positions[index * 2 + 1] = positioned.getGlyphY(index);
                fonts[index] = positioned.getFont(index);
            }
            advance = positioned.getAdvance();
            this.lastUsed = lastUsed;
        }

        boolean matches(TerminalGpuFrame.TextRun run, int candidateHash) {
            int length = run.valueEnd - run.valueStart;
            if (hash != candidateHash || bold != run.bold || italic != run.italic ||
                value.length() != length) return false;
            for (int index = 0; index < length; index++) {
                if (value.charAt(index) != run.value.charAt(run.valueStart + index)) return false;
            }
            return true;
        }
    }

    /** Fixed-size exact cache; metric and typeface changes clear it before reuse. */
    private static final class SingleGlyphCache {
        private final int[] keys;
        private final SingleGlyph[] values;
        private final long[] lastUsed;
        private final int mask;
        private final int probes;
        private long generation;

        SingleGlyphCache(int capacity, int probes) {
            if (capacity <= 0 || (capacity & (capacity - 1)) != 0 || probes <= 0) {
                throw new IllegalArgumentException("Invalid single-glyph cache geometry");
            }
            keys = new int[capacity];
            values = new SingleGlyph[capacity];
            lastUsed = new long[capacity];
            mask = capacity - 1;
            this.probes = Math.min(probes, capacity);
            clear();
        }

        SingleGlyph find(int key) {
            int start = spread(key) & mask;
            for (int probe = 0; probe < probes; probe++) {
                int index = (start + probe) & mask;
                if (keys[index] == key) {
                    lastUsed[index] = ++generation;
                    return values[index];
                }
                if (keys[index] == -1) return null;
            }
            return null;
        }

        void store(int key, SingleGlyph value) {
            int start = spread(key) & mask;
            int target = start;
            long oldest = Long.MAX_VALUE;
            for (int probe = 0; probe < probes; probe++) {
                int index = (start + probe) & mask;
                if (keys[index] == key || keys[index] == -1) {
                    target = index;
                    oldest = Long.MIN_VALUE;
                    break;
                }
                if (lastUsed[index] < oldest) {
                    oldest = lastUsed[index];
                    target = index;
                }
            }
            keys[target] = key;
            values[target] = value;
            lastUsed[target] = ++generation;
        }

        void clear() {
            java.util.Arrays.fill(keys, -1);
            java.util.Arrays.fill(values, null);
            java.util.Arrays.fill(lastUsed, 0L);
            generation = 0L;
        }

        private static int spread(int value) {
            return value ^ (value >>> 11) ^ (value >>> 21);
        }
    }

    private static final class SingleGlyph {
        static final SingleGlyph INVALID = new SingleGlyph(null, 0, 0f, 0f, 0f, false);

        final Font font;
        final int glyphId;
        final float x;
        final float y;
        final float advance;
        final boolean valid;
        GlyphTexture texture;
        long textureAtlasEpoch = Long.MIN_VALUE;

        SingleGlyph(Font font, int glyphId, float x, float y, float advance, boolean valid) {
            this.font = font;
            this.glyphId = glyphId;
            this.x = x;
            this.y = y;
            this.advance = advance;
            this.valid = valid;
        }
    }

    private static final class Atlas {
        final int maxSize;
        final Bitmap.Config config;
        Bitmap bitmap;
        Canvas canvas;
        int size;
        int x;
        int y;
        int rowHeight;
        int generation = 1;
        int dirtyLeft = Integer.MAX_VALUE;
        int dirtyTop = Integer.MAX_VALUE;
        int dirtyRight = Integer.MIN_VALUE;
        int dirtyBottom = Integer.MIN_VALUE;

        Atlas(int initialSize, int maxSize, Bitmap.Config config) {
            this.size = initialSize;
            this.maxSize = maxSize;
            this.config = config;
            bitmap = Bitmap.createBitmap(size, size, config);
            canvas = new Canvas(bitmap);
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        }

        AtlasAllocation allocate(int width, int height) {
            if (width > maxSize || height > maxSize) return null;
            if (x + width > size) {
                x = 0;
                y += rowHeight;
                rowHeight = 0;
            }
            if (y + height > size) {
                if (size >= maxSize) return null;
                int next = Math.min(maxSize, size * 2);
                Bitmap old = bitmap;
                bitmap = Bitmap.createBitmap(next, next, config);
                canvas = new Canvas(bitmap);
                canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
                canvas.drawBitmap(old, 0f, 0f, null);
                old.recycle();
                size = next;
                generation++;
                dirtyLeft = 0;
                dirtyTop = 0;
                dirtyRight = size;
                dirtyBottom = size;
                if (x + width > size) {
                    x = 0;
                    y += rowHeight;
                    rowHeight = 0;
                }
                if (y + height > size) return null;
            }
            AtlasAllocation allocation = new AtlasAllocation(x, y, width, height);
            x += width;
            rowHeight = Math.max(rowHeight, height);
            return allocation;
        }

        void markDirty(int left, int top, int right, int bottom) {
            dirtyLeft = Math.min(dirtyLeft, left);
            dirtyTop = Math.min(dirtyTop, top);
            dirtyRight = Math.max(dirtyRight, right);
            dirtyBottom = Math.max(dirtyBottom, bottom);
        }

        void finishDirty() {
            if (dirtyLeft == Integer.MAX_VALUE) return;
            dirtyLeft = Math.max(0, Math.min(size, dirtyLeft));
            dirtyTop = Math.max(0, Math.min(size, dirtyTop));
            dirtyRight = Math.max(dirtyLeft, Math.min(size, dirtyRight));
            dirtyBottom = Math.max(dirtyTop, Math.min(size, dirtyBottom));
        }

        void clearDirty() {
            dirtyLeft = Integer.MAX_VALUE;
            dirtyTop = Integer.MAX_VALUE;
            dirtyRight = Integer.MIN_VALUE;
            dirtyBottom = Integer.MIN_VALUE;
        }

        void reset() {
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            x = y = rowHeight = 0;
            generation++;
            dirtyLeft = 0;
            dirtyTop = 0;
            dirtyRight = size;
            dirtyBottom = size;
        }
    }

    /** Bounded persistent monochrome atlas for exact whole-run Canvas shaping. */
    private static final class RunAtlas {
        Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(bitmap);
        int generation = 1;
        int dirtyLeft = Integer.MAX_VALUE;
        int dirtyTop = Integer.MAX_VALUE;
        int dirtyRight = Integer.MIN_VALUE;
        int dirtyBottom = Integer.MIN_VALUE;
        int currentUsedRight;
        int currentUsedBottom;
        int x;
        int y;
        int rowHeight;
        boolean available;
        int resets;

        RunAtlas() {
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        }

        void beginFrame(TerminalGpuFrame frame) {
            int requiredWidth = Math.max(1, frame.viewWidth + RUN_ATLAS_PADDING * 2);
            long requiredHeightLong = (long) Math.max(1, frame.screenRows) *
                (Math.max(1, frame.fontLineSpacing) + RUN_ATLAS_PADDING * 2L);
            int requiredHeight = (int) Math.min(RUN_ATLAS_MAX_DIMENSION, requiredHeightLong);
            available = ensureGeometry(requiredWidth, requiredHeight);
        }

        private boolean ensureGeometry(int requiredWidth, int requiredHeight) {
            if (requiredWidth > RUN_ATLAS_MAX_DIMENSION || requiredWidth > RUN_ATLAS_MAX_BYTES) {
                return false;
            }
            int currentWidth = bitmap.getWidth();
            int maximumCurrentHeight = Math.min(RUN_ATLAS_MAX_DIMENSION,
                RUN_ATLAS_MAX_BYTES / currentWidth);
            int usableRequiredHeight = Math.min(requiredHeight, maximumCurrentHeight);
            if (currentWidth >= requiredWidth && bitmap.getHeight() >= usableRequiredHeight) {
                return true;
            }

            int targetWidth = currentWidth >= requiredWidth ? currentWidth :
                alignUp(requiredWidth, 64);
            if (targetWidth > RUN_ATLAS_MAX_DIMENSION) targetWidth = RUN_ATLAS_MAX_DIMENSION;
            if (targetWidth < requiredWidth) return false;
            int maximumHeight = Math.min(RUN_ATLAS_MAX_DIMENSION,
                RUN_ATLAS_MAX_BYTES / targetWidth);
            if (maximumHeight <= 0) return false;
            int headroom = Math.min(512, Math.max(64, requiredHeight / 4));
            int targetHeight = alignUp(Math.min(maximumHeight,
                requiredHeight + headroom), 64);
            if (targetHeight > maximumHeight) targetHeight = maximumHeight;
            targetHeight = Math.max(1, targetHeight);
            try {
                Bitmap next = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ALPHA_8);
                Canvas nextCanvas = new Canvas(next);
                nextCanvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
                Bitmap previous = bitmap;
                bitmap = next;
                canvas = nextCanvas;
                previous.recycle();
                generation++;
                x = y = rowHeight = 0;
                currentUsedRight = currentUsedBottom = 0;
                dirtyLeft = 0;
                dirtyTop = 0;
                dirtyRight = targetWidth;
                dirtyBottom = targetHeight;
                return true;
            } catch (RuntimeException | OutOfMemoryError error) {
                return currentWidth >= requiredWidth &&
                    bitmap.getHeight() >= usableRequiredHeight;
            }
        }

        boolean canFit(int width, int height) {
            return available && width > 0 && height > 0 &&
                width <= bitmap.getWidth() && height <= bitmap.getHeight();
        }

        AtlasAllocation allocate(int width, int height) {
            if (!available || width <= 0 || height <= 0 ||
                width > bitmap.getWidth() || height > bitmap.getHeight()) return null;
            if (x + width > bitmap.getWidth()) {
                x = 0;
                y += rowHeight;
                rowHeight = 0;
            }
            if (y + height > bitmap.getHeight()) return null;
            AtlasAllocation allocation = new AtlasAllocation(x, y, width, height);
            x += width;
            rowHeight = Math.max(rowHeight, height);
            currentUsedRight = Math.max(currentUsedRight, x);
            currentUsedBottom = Math.max(currentUsedBottom, y + height);
            markDirty(allocation.x, allocation.y, allocation.x + width, allocation.y + height);
            return allocation;
        }

        private void markDirty(int left, int top, int right, int bottom) {
            dirtyLeft = Math.min(dirtyLeft, left);
            dirtyTop = Math.min(dirtyTop, top);
            dirtyRight = Math.max(dirtyRight, right);
            dirtyBottom = Math.max(dirtyBottom, bottom);
        }

        void finishDirty() {
            if (dirtyLeft == Integer.MAX_VALUE) return;
            dirtyLeft = Math.max(0, Math.min(bitmap.getWidth(), dirtyLeft));
            dirtyTop = Math.max(0, Math.min(bitmap.getHeight(), dirtyTop));
            dirtyRight = Math.max(dirtyLeft, Math.min(bitmap.getWidth(), dirtyRight));
            dirtyBottom = Math.max(dirtyTop, Math.min(bitmap.getHeight(), dirtyBottom));
        }

        void markPresented() {
            clearDirty();
        }

        void reset() {
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            x = y = rowHeight = 0;
            currentUsedRight = currentUsedBottom = 0;
            generation++;
            resets++;
            dirtyLeft = 0;
            dirtyTop = 0;
            dirtyRight = bitmap.getWidth();
            dirtyBottom = bitmap.getHeight();
        }

        private void clearDirty() {
            dirtyLeft = Integer.MAX_VALUE;
            dirtyTop = Integer.MAX_VALUE;
            dirtyRight = Integer.MIN_VALUE;
            dirtyBottom = Integer.MIN_VALUE;
        }

        private static int alignUp(int value, int alignment) {
            return ((value + alignment - 1) / alignment) * alignment;
        }
    }

    private static final class AtlasAllocation {
        final int x;
        final int y;
        final int width;
        final int height;

        AtlasAllocation(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    /** One-probe exact front cache; collisions fall through to the authoritative map. */
    private static final class GlyphHotCache {
        private final Font[] fonts;
        private final int[] glyphIds;
        private final int[] textSizes;
        private final byte[] styles;
        private final GlyphTexture[] textures;
        private final int mask;

        GlyphHotCache(int capacity) {
            if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
                throw new IllegalArgumentException("Invalid glyph hot-cache geometry");
            }
            fonts = new Font[capacity];
            glyphIds = new int[capacity];
            textSizes = new int[capacity];
            styles = new byte[capacity];
            textures = new GlyphTexture[capacity];
            mask = capacity - 1;
        }

        GlyphTexture find(Font font, int glyphId, int textSize,
                          boolean bold, boolean italic) {
            byte style = style(bold, italic);
            int index = spread(hash(font, glyphId, textSize, style)) & mask;
            Font candidate = fonts[index];
            return candidate != null && glyphIds[index] == glyphId &&
                textSizes[index] == textSize && styles[index] == style &&
                candidate.equals(font) ? textures[index] : null;
        }

        void store(Font font, int glyphId, int textSize, boolean bold, boolean italic,
                   GlyphTexture texture) {
            byte style = style(bold, italic);
            int index = spread(hash(font, glyphId, textSize, style)) & mask;
            fonts[index] = font;
            glyphIds[index] = glyphId;
            textSizes[index] = textSize;
            styles[index] = style;
            textures[index] = texture;
        }

        void clear() {
            java.util.Arrays.fill(fonts, null);
            java.util.Arrays.fill(textures, null);
        }

        private static byte style(boolean bold, boolean italic) {
            return (byte) ((bold ? 1 : 0) | (italic ? 2 : 0));
        }

        private static int hash(Font font, int glyphId, int textSize, byte style) {
            int hash = font.hashCode();
            hash = 31 * hash + glyphId;
            hash = 31 * hash + textSize;
            return 31 * hash + style;
        }

        private static int spread(int value) {
            return value ^ (value >>> 16);
        }
    }

    private static final class GlyphKey {
        final Font font;
        final int glyphId;
        final int textSize;
        final boolean bold;
        final boolean italic;

        GlyphKey(Font font, int glyphId, int textSize, boolean bold, boolean italic) {
            this.font = font;
            this.glyphId = glyphId;
            this.textSize = textSize;
            this.bold = bold;
            this.italic = italic;
        }

        @Override
        public int hashCode() {
            int result = font.hashCode();
            result = 31 * result + glyphId;
            result = 31 * result + textSize;
            result = 31 * result + (bold ? 1 : 0);
            return 31 * result + (italic ? 1 : 0);
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof GlyphKey)) return false;
            GlyphKey other = (GlyphKey) object;
            return glyphId == other.glyphId && textSize == other.textSize &&
                bold == other.bold && italic == other.italic && font.equals(other.font);
        }
    }

    private static final class GlyphTexture {
        final Atlas atlas;
        final int x;
        final int y;
        final int width;
        final int height;
        final int originLeft;
        final int originTop;
        final int inkLeft;
        final int inkTop;
        final int inkRight;
        final int inkBottom;
        final int mode;
        final boolean empty;
        final int uvLeftTop;
        final int uvRightBottom;

        GlyphTexture(Atlas atlas, int x, int y, int width, int height,
                     int originLeft, int originTop,
                     int inkLeft, int inkTop, int inkRight, int inkBottom,
                     int mode, boolean empty) {
            this.atlas = atlas;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.originLeft = originLeft;
            this.originTop = originTop;
            this.inkLeft = inkLeft;
            this.inkTop = inkTop;
            this.inkRight = inkRight;
            this.inkBottom = inkBottom;
            this.mode = mode;
            this.empty = empty;
            uvLeftTop = x | (y << 16);
            uvRightBottom = (x + width) | ((y + height) << 16);
        }
    }

    private static final class CompiledRow {
        static final int[] EMPTY_VERTEX_DATA = new int[0];
        TerminalGpuFrame.Row source;
        int[] vertexData = EMPTY_VERTEX_DATA;
        int vertexWordCount;
        long compiledAtlasEpoch = Long.MIN_VALUE;
        boolean usesRunMask;
        int compiledRunMaskGeneration = Integer.MIN_VALUE;

        CompiledRow(TerminalGpuFrame.Row source) {
            this.source = source;
        }

        void replaceSource(TerminalGpuFrame.Row source) {
            this.source = source;
            vertexWordCount = 0;
            compiledAtlasEpoch = Long.MIN_VALUE;
            usesRunMask = false;
            compiledRunMaskGeneration = Integer.MIN_VALUE;
        }
    }

    private static final class PreparedFrame {
        final boolean complete;
        final boolean rebuildRequired;
        final ByteBuffer vertices;
        final int vertexBytes;
        final long vertexGeneration;
        final Atlas mask;
        final Atlas color;
        final RunAtlas runMask;

        private PreparedFrame(boolean complete, boolean rebuildRequired, ByteBuffer vertices,
                              int vertexBytes, long vertexGeneration, Atlas mask, Atlas color,
                              RunAtlas runMask) {
            this.complete = complete;
            this.rebuildRequired = rebuildRequired;
            this.vertices = vertices;
            this.vertexBytes = vertexBytes;
            this.vertexGeneration = vertexGeneration;
            this.mask = mask;
            this.color = color;
            this.runMask = runMask;
        }

        static PreparedFrame complete(ByteBuffer vertices, int vertexBytes, long vertexGeneration,
                                      Atlas mask, Atlas color, RunAtlas runMask) {
            return new PreparedFrame(true, false, vertices, vertexBytes, vertexGeneration,
                mask, color, runMask);
        }

        static PreparedFrame incomplete(boolean rebuildRequired, Atlas mask, Atlas color,
                                        RunAtlas runMask) {
            return new PreparedFrame(false, rebuildRequired, null, 0, 0L,
                mask, color, runMask);
        }
    }
}
