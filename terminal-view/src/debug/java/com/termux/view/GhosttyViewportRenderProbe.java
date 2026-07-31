package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.HardwareBufferRenderer;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.Typeface;
import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalNativeDeviceProbe;
import com.termux.terminal.TerminalOutput;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Debug-only device regression for scrollback viewport return and retained-row lifetime. */
public final class GhosttyViewportRenderProbe {

    private static final int COLUMNS = 72;
    private static final int ROWS = 34;
    private static final int GENERATED_LINES = 500;

    private GhosttyViewportRenderProbe() {
    }

    public static void main(String[] args) {
        System.out.println(run());
    }

    public static String run() {
        String renderBatchEvidence =
            TerminalNativeDeviceProbe.verifyRenderBatchPacketsForDiagnostics();
        TerminalEmulator emulator = new TerminalEmulator(
            new NullOutput(), COLUMNS, ROWS, 12, 24, 2000, null);
        require(emulator.isGhosttyRenderAuthorityActive(),
            "Ghostty render authority is not active");

        byte[] content = buildContent();
        emulator.append(content, content.length);
        int transcriptRows = emulator.getActiveTranscriptRows();
        require(transcriptRows >= 100,
            "insufficient scrollback rows: " + transcriptRows);

        long resizeSkipsBefore = emulator.getGhosttyDormantJavaResizeSkipsForDiagnostics();
        emulator.resize(COLUMNS + 7, ROWS + 3, 13, 25);
        emulator.resize(COLUMNS, ROWS, 12, 24);
        long dormantJavaResizeSkips =
            emulator.getGhosttyDormantJavaResizeSkipsForDiagnostics() - resizeSkipsBefore;
        require(emulator.isGhosttyRenderAuthorityActive(),
            "Ghostty authority was lost across native resize/reflow");
        require(dormantJavaResizeSkips == 2L,
            "native resize redundantly reflowed the dormant Java screen: " +
                emulator.getGhosttyResizeStatusForDiagnostics());

        TerminalRenderer renderer = new TerminalRenderer(24, Typeface.MONOSPACE);
        int width = Math.max(1, (int) Math.ceil(renderer.mFontWidth * COLUMNS));
        int height = Math.max(1, renderer.mFontLineSpacingAndAscent +
            renderer.mFontLineSpacing * ROWS);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        FrameDigest firstBottom = render(renderer, emulator, bitmap, canvas, 0, 0f);
        require(firstBottom.visualPixels > width,
            "bottom viewport unexpectedly blank: " + firstBottom);
        require(firstBottom.visualRowBands >= ROWS - 2,
            "bottom viewport has missing rendered rows: " + firstBottom);
        require(renderer.hasCompleteGhosttyFrame(),
            "renderer did not publish a complete retained frame");

        int far = -Math.min(120, transcriptRows);
        int middle = -Math.min(47, transcriptRows);
        int[] path = new int[] {-1, -8, -19, middle, far, middle, -19, -8, -1, 0};
        long traversedDigest = 1L;
        int renderedFrames = 1;
        for (int cycle = 0; cycle < 8; cycle++) {
            for (int topRow : path) {
                float offset = topRow == 0 ? 0f :
                    renderer.mFontLineSpacing * ((cycle & 1) == 0 ? 0.35f : 0.72f);
                FrameDigest frame = render(renderer, emulator, bitmap, canvas, topRow, offset);
                require(frame.visualPixels > width,
                    "viewport became blank top=" + topRow + " cycle=" + cycle +
                        " digest=" + frame);
                require(frame.visualRowBands >= ROWS - 2,
                    "viewport became sparse top=" + topRow + " cycle=" + cycle +
                        " digest=" + frame);
                traversedDigest = traversedDigest * 31L + frame.hash;
                renderedFrames++;
            }
        }

        // Exercise both signed sub-row forms. The preceding viewport retains exactly the row
        // that becomes exposed by the next partial frame.
        FrameDigest signedBase = render(renderer, emulator, bitmap, canvas, -12, 0f);
        FrameDigest signedTopOverscan = render(renderer, emulator, bitmap, canvas, -11,
            -renderer.mFontLineSpacing * 0.42f);
        require(renderer.hasCompleteGhosttyFrame(
                -12, ROWS, emulator.getContentRevision()),
            "retained cache did not recognize a fully covered reverse viewport");
        require(!renderer.hasCompleteGhosttyFrame(
                -12, ROWS, emulator.getContentRevision() + 1L),
            "retained cache accepted a stale content revision");
        require(renderer.hasCompleteGhosttyFrame(-12, ROWS),
            "complete viewport was rejected solely because the PTY revision advanced");
        FrameDigest signedBottomOverscan = render(renderer, emulator, bitmap, canvas, -12,
            renderer.mFontLineSpacing * 0.42f);
        require(signedBase.visualPixels > width && signedTopOverscan.visualPixels > width &&
                signedBottomOverscan.visualPixels > width,
            "signed overscan frame became blank");
        require(signedBase.visualRowBands >= ROWS - 2 &&
                signedTopOverscan.visualRowBands >= ROWS - 2 &&
                signedBottomOverscan.visualRowBands >= ROWS - 2,
            "signed overscan frame has missing rendered rows");

        FrameDigest returnedBottom = render(renderer, emulator, bitmap, canvas, 0, 0f);
        require(firstBottom.equals(returnedBottom),
            "bottom viewport changed after up/down traversal: first=" + firstBottom +
                " returned=" + returnedBottom);
        require(emulator.isGhosttyRenderAuthorityActive(),
            "retained renderer fell back: " + emulator.getGhosttyRenderStatusForDiagnostics());

        String hardwareGlyphEvidence = "hardware_glyph_diff=unsupported-api";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            long glyphDrawsBefore = renderer.getGhosttyGlyphCanvasDrawsForTesting();
            long batchCallsBefore = renderer.getGhosttyGlyphBatchDrawCallsForTesting();
            long batchedCommandsBefore = renderer.getGhosttyGlyphBatchedCommandsForTesting();
            long batchedGlyphsBefore = renderer.getGhosttyGlyphBatchedGlyphsForTesting();
            long batchFallbacksBefore =
                renderer.getGhosttyGlyphBatchFallbackFramesForTesting();
            FrameDigest hardwareString = renderHardware(
                renderer, emulator, width, height, false);
            int explicitlyPrepared = renderer.prepareGhosttyRetainedGlyphsForTesting();
            require(renderer.getGhosttyShapedTextRunsForTesting() > 0,
                "hardware probe found no shaped production-safe ASCII run, explicit=" +
                    explicitlyPrepared);
            FrameDigest hardwareGlyph = renderHardware(
                renderer, emulator, width, height, true);
            long hardwareGlyphDraws =
                renderer.getGhosttyGlyphCanvasDrawsForTesting() - glyphDrawsBefore;
            long hardwareBatchCalls =
                renderer.getGhosttyGlyphBatchDrawCallsForTesting() - batchCallsBefore;
            long hardwareBatchedCommands =
                renderer.getGhosttyGlyphBatchedCommandsForTesting() - batchedCommandsBefore;
            long hardwareBatchedGlyphs =
                renderer.getGhosttyGlyphBatchedGlyphsForTesting() - batchedGlyphsBefore;
            long hardwareBatchFallbacks =
                renderer.getGhosttyGlyphBatchFallbackFramesForTesting() - batchFallbacksBefore;
            require(hardwareString.equals(hardwareGlyph),
                "hardware glyph path differs from parent-Canvas String reference: string=" +
                    hardwareString + " glyph=" + hardwareGlyph);
            require(hardwareGlyph.visualPixels > width &&
                    hardwareGlyph.visualRowBands >= ROWS - 2,
                "hardware glyph frame is incomplete: " + hardwareGlyph);
            require(renderer.getGhosttyShapedTextRunsForTesting() > 0 &&
                    renderer.getGhosttyShapedGlyphsForTesting() > 0,
                "hardware probe prepared no shaped glyphs");
            require(hardwareGlyphDraws > 0,
                "hardware probe did not execute Canvas.drawGlyphs");
            require(hardwareBatchCalls > 0 && hardwareBatchedCommands > 0 &&
                    hardwareBatchedGlyphs > hardwareBatchCalls,
                "hardware probe did not execute ordered glyph batching: calls=" +
                    hardwareBatchCalls + " commands=" + hardwareBatchedCommands +
                    " glyphs=" + hardwareBatchedGlyphs);
            require(hardwareBatchFallbacks == 0,
                "ordered glyph batching fell back during hardware probe: " +
                    hardwareBatchFallbacks);
            require(renderer.getGhosttyGlyphShapeFailuresForTesting() == 0,
                "hardware probe had glyph shaping failures=" +
                    renderer.getGhosttyGlyphShapeFailuresForTesting());
            hardwareGlyphEvidence = "hardware_glyph_diff=verified" +
                " hardware_glyph_hash=" + Long.toUnsignedString(hardwareGlyph.hash) +
                " hardware_glyph_draws=" + hardwareGlyphDraws +
                " hardware_glyph_batch_calls=" + hardwareBatchCalls +
                " hardware_glyph_batched_commands=" + hardwareBatchedCommands +
                " hardware_glyph_batched_glyphs=" + hardwareBatchedGlyphs +
                " hardware_glyph_batch_fallbacks=0" +
                " shaped_runs=" + renderer.getGhosttyShapedTextRunsForTesting() +
                " shaped_glyphs=" + renderer.getGhosttyShapedGlyphsForTesting() +
                " glyph_shape_failures=0 " + verifyComplexHardwareGlyphs();
            hardwareGlyphEvidence += " " + verifyBatchCompression();
        }

        float overscanOffset = renderer.mFontLineSpacing * 0.42f;
        renderer.dispose();
        require(renderer.prewarmGhosttyFrame(emulator, -12, overscanOffset, true,
                -1, -1, -1, -1),
            "bottom overscan prewarm failed");
        FrameDigest prewarmedBottomOverscan = render(
            renderer, emulator, bitmap, canvas, -12, overscanOffset);
        renderer.dispose();
        FrameDigest unshiftedBottomOverscan = render(renderer, emulator, bitmap, canvas, -12, 0f);
        require(!prewarmedBottomOverscan.equals(unshiftedBottomOverscan),
            "bottom overscan prewarm lost its fractional offset");

        // Canvas fallback can begin from an empty retained cache after a full invalidation.
        // It must materialize the adjacent row itself rather than silently flatten the offset.
        renderer.dispose();
        FrameDigest directBottomOverscan = render(
            renderer, emulator, bitmap, canvas, -12, overscanOffset);
        renderer.dispose();
        FrameDigest directUnshiftedBottomOverscan = render(
            renderer, emulator, bitmap, canvas, -12, 0f);
        require(!directBottomOverscan.equals(directUnshiftedBottomOverscan),
            "direct Canvas bottom overscan lost its fractional offset");

        renderer.dispose();
        require(renderer.prewarmGhosttyFrame(emulator, -11, -overscanOffset, true,
                -1, -1, -1, -1),
            "top overscan prewarm failed");
        FrameDigest prewarmedTopOverscan = render(
            renderer, emulator, bitmap, canvas, -11, -overscanOffset);
        renderer.dispose();
        FrameDigest unshiftedTopOverscan = render(renderer, emulator, bitmap, canvas, -11, 0f);
        require(!prewarmedTopOverscan.equals(unshiftedTopOverscan),
            "top overscan prewarm lost its fractional offset");

        renderer.dispose();
        FrameDigest directTopOverscan = render(
            renderer, emulator, bitmap, canvas, -11, -overscanOffset);
        renderer.dispose();
        FrameDigest directUnshiftedTopOverscan = render(
            renderer, emulator, bitmap, canvas, -11, 0f);
        require(!directTopOverscan.equals(directUnshiftedTopOverscan),
            "direct Canvas top overscan lost its fractional offset");

        long decodedRows = renderer.getGhosttyDecodedRowsForTesting();
        long retainedRows = renderer.getGhosttyRetainedRowsForTesting();
        long viewportPartialPackets = renderer.getGhosttyViewportPartialPacketsForTesting();
        long viewportFullRetries = renderer.getGhosttyViewportFullRetriesForTesting();
        long viewportCacheHits = renderer.getGhosttyViewportCacheHitsForTesting();
        require(viewportPartialPackets > 0,
            "viewport movement never used a partial packet");
        require(retainedRows > 0,
            "viewport movement never reused a retained row");
        require(viewportFullRetries == 0,
            "partial viewport packets left cache gaps: retries=" + viewportFullRetries);
        require(viewportCacheHits > 0,
            "retained viewport traversal never bypassed a native packet");

        // A ViewPager may detach an offscreen terminal and later reuse its TerminalView.
        // The next frame must reconstruct from native authority rather than reuse a black display list.
        renderer.dispose();
        require(!renderer.hasCompleteGhosttyFrame(),
            "disposed renderer incorrectly remained frame-ready");
        FrameDigest reattachedBottom = render(renderer, emulator, bitmap, canvas, 0, 0f);
        require(firstBottom.equals(reattachedBottom),
            "reattached viewport changed: first=" + firstBottom +
                " reattached=" + reattachedBottom);
        require(renderer.hasCompleteGhosttyFrame(),
            "reattached renderer did not become frame-ready");

        renderer.dispose();
        bitmap.recycle();
        return "TERMUX_VIEWPORT_PROBE status=PASS" +
            " generated_lines=" + GENERATED_LINES +
            " transcript_rows=" + transcriptRows +
            " rendered_frames=" + (renderedFrames + 1) +
            " bottom_hash=" + Long.toUnsignedString(returnedBottom.hash) +
            " bottom_visual_pixels=" + returnedBottom.visualPixels +
            " bottom_visual_rows=" + returnedBottom.visualRowBands +
            " traversal_hash=" + Long.toUnsignedString(traversedDigest) +
            " decoded_rows=" + decodedRows +
            " retained_rows=" + retainedRows +
            " viewport_partial_packets=" + viewportPartialPackets +
            " viewport_full_retries=" + viewportFullRetries +
            " viewport_cache_hits=" + viewportCacheHits +
            " dormant_java_resize_skips=" + dormantJavaResizeSkips +
            " authority=ghostty retained_rows=true signed_overscan=true" +
            " prewarmed_overscan=true direct_canvas_overscan=true frame_ready_lifecycle=true" +
            " reattachment=true black_return=false " + hardwareGlyphEvidence +
            " " + renderBatchEvidence;
    }

    private static FrameDigest render(TerminalRenderer renderer, TerminalEmulator emulator,
                                      Bitmap bitmap, Canvas canvas, int topRow,
                                      float pixelOffset) {
        bitmap.eraseColor(0xffff00ff);
        renderer.render(emulator, canvas, topRow, pixelOffset, -1, -1, -1, -1);
        FrameDigest result = digest(bitmap, renderer.mFontLineSpacing);
        renderer.releaseRetiredRows();
        return result;
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static String verifyComplexHardwareGlyphs() {
        final int columns = 48;
        final int rows = 8;
        TerminalEmulator emulator = new TerminalEmulator(
            new NullOutput(), columns, rows, 12, 24, 100, null);
        String content =
            "\033[1;3;38;2;120;220;255mASCII e\u0301 界 🙂 👩‍💻\033[0m\r\n" +
            "\033[4:3;38;5;214mwave underline ╭─┼─╮\033[0m\r\n" +
            "\033[7mreverse ＡＢＣ café Ελληνικά\033[0m\r\n" +
            "wide: 你好世界  emoji: 🚀✅  combining: A\u0308\u0323\r\n" +
            "plain ASCII hardware fast path 0123456789\r\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
        require(emulator.isGhosttyRenderAuthorityActive(),
            "complex glyph probe lost Ghostty authority");

        TerminalRenderer renderer = new TerminalRenderer(28, Typeface.MONOSPACE);
        int width = Math.max(1, (int) Math.ceil(renderer.mFontWidth * columns));
        int height = Math.max(1, renderer.mFontLineSpacingAndAscent +
            renderer.mFontLineSpacing * rows);
        long glyphDrawsBefore = renderer.getGhosttyGlyphCanvasDrawsForTesting();
        FrameDigest stringFrame = renderHardware(renderer, emulator, width, height, false);
        int explicitlyPrepared = renderer.prepareGhosttyRetainedGlyphsForTesting();
        require(renderer.getGhosttyShapedTextRunsForTesting() > 0,
            "complex glyph probe found no shaped production-safe ASCII run, explicit=" +
                explicitlyPrepared);
        FrameDigest glyphFrame = renderHardware(renderer, emulator, width, height, true);
        long glyphDraws = renderer.getGhosttyGlyphCanvasDrawsForTesting() - glyphDrawsBefore;
        require(stringFrame.equals(glyphFrame),
            "complex hardware glyph path differs: string=" + stringFrame +
                " glyph=" + glyphFrame);
        require(glyphDraws > 0 && renderer.getGhosttyShapedGlyphsForTesting() > 0,
            "complex glyph probe did not execute shaped drawing");
        require(renderer.getGhosttyGlyphShapeFailuresForTesting() == 0,
            "complex glyph shaping failed count=" +
                renderer.getGhosttyGlyphShapeFailuresForTesting());
        renderer.dispose();
        return "complex_glyph_diff=verified complex_glyph_hash=" +
            Long.toUnsignedString(glyphFrame.hash) +
            " complex_glyph_draws=" + glyphDraws;
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static String verifyBatchCompression() {
        final int columns = 72;
        final int rows = 34;
        TerminalEmulator emulator = new TerminalEmulator(
            new NullOutput(), columns, rows, 12, 24, 100, null);
        StringBuilder content = new StringBuilder(rows * columns);
        for (int row = 0; row < rows; row++) {
            content.append(String.format(Locale.US,
                "batch-row=%02d plain ASCII retained glyph submission 0123456789", row));
            content.append("\r\n");
        }
        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
        require(emulator.isGhosttyRenderAuthorityActive(),
            "batch compression probe lost Ghostty authority");

        TerminalRenderer renderer = new TerminalRenderer(24, Typeface.MONOSPACE);
        int width = Math.max(1, (int) Math.ceil(renderer.mFontWidth * columns));
        int height = Math.max(1, renderer.mFontLineSpacingAndAscent +
            renderer.mFontLineSpacing * rows);
        FrameDigest stringFrame = renderHardware(renderer, emulator, width, height, false);
        int prepared = renderer.prepareGhosttyRetainedGlyphsForTesting();
        require(prepared >= 2,
            "batch compression probe prepared too few independent runs: " + prepared);
        long callsBefore = renderer.getGhosttyGlyphBatchDrawCallsForTesting();
        long commandsBefore = renderer.getGhosttyGlyphBatchedCommandsForTesting();
        long glyphsBefore = renderer.getGhosttyGlyphBatchedGlyphsForTesting();
        long fallbacksBefore = renderer.getGhosttyGlyphBatchFallbackFramesForTesting();
        FrameDigest glyphFrame = renderHardware(renderer, emulator, width, height, true);
        long calls = renderer.getGhosttyGlyphBatchDrawCallsForTesting() - callsBefore;
        long commands = renderer.getGhosttyGlyphBatchedCommandsForTesting() - commandsBefore;
        long glyphs = renderer.getGhosttyGlyphBatchedGlyphsForTesting() - glyphsBefore;
        long fallbacks = renderer.getGhosttyGlyphBatchFallbackFramesForTesting() - fallbacksBefore;
        require(stringFrame.equals(glyphFrame),
            "batch compression changed pixels: string=" + stringFrame +
                " glyph=" + glyphFrame);
        require(calls > 0 && commands > calls && glyphs >= commands && fallbacks == 0,
            "ordered glyph batching did not compress compatible runs: calls=" + calls +
                " commands=" + commands + " glyphs=" + glyphs +
                " fallbacks=" + fallbacks);
        renderer.dispose();
        return "batch_compression=verified batch_compression_calls=" + calls +
            " batch_compression_commands=" + commands +
            " batch_compression_glyphs=" + glyphs +
            " batch_compression_fallbacks=0";
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static FrameDigest renderHardware(TerminalRenderer renderer,
                                              TerminalEmulator emulator,
                                              int width, int height,
                                              boolean glyphFastPath) {
        final long usage = HardwareBuffer.USAGE_GPU_COLOR_OUTPUT |
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE;
        require(HardwareBuffer.isSupported(width, height, HardwareBuffer.RGBA_8888, 1, usage),
            "RGBA hardware render target is unsupported");
        ColorSpace colorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        try (HardwareBuffer buffer = HardwareBuffer.create(
                 width, height, HardwareBuffer.RGBA_8888, 1, usage);
             HardwareBufferRenderer hardwareRenderer = new HardwareBufferRenderer(buffer)) {
            RenderNode root = new RenderNode("TermuxGhosttyHardwareProbeRoot");
            root.setPosition(0, 0, width, height);
            renderer.setGhosttyGlyphFastPathEnabledForTesting(glyphFastPath);
            RecordingCanvas recording = root.beginRecording(width, height);
            try {
                require(recording.isHardwareAccelerated(),
                    "probe root did not provide a hardware Canvas");
                require(renderer.renderFrame(emulator, recording, 0, 0f,
                        -1, -1, -1, -1),
                    "hardware parent-Canvas render failed");
            } finally {
                root.endRecording();
                renderer.setGhosttyGlyphFastPathEnabledForTesting(true);
            }
            hardwareRenderer.setContentRoot(root);

            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<HardwareBufferRenderer.RenderResult> result =
                new AtomicReference<>();
            hardwareRenderer.obtainRenderRequest().setColorSpace(colorSpace).draw(
                Runnable::run, value -> {
                    result.set(value);
                    completed.countDown();
                });
            try {
                require(completed.await(10, TimeUnit.SECONDS),
                    "hardware renderer callback timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("hardware renderer interrupted", interrupted);
            }
            HardwareBufferRenderer.RenderResult renderResult = result.get();
            require(renderResult != null &&
                    renderResult.getStatus() == HardwareBufferRenderer.RenderResult.SUCCESS,
                "hardware renderer failed status=" +
                    (renderResult == null ? -1 : renderResult.getStatus()));
            try (SyncFence fence = renderResult.getFence()) {
                require(fence.await(Duration.ofSeconds(10)),
                    "hardware renderer fence timed out");
            }

            Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
            require(wrapped != null, "failed to wrap hardware render target");
            Bitmap readable = wrapped.copy(Bitmap.Config.ARGB_8888, false);
            require(readable != null, "failed to read hardware render target");
            try {
                return digest(readable, renderer.mFontLineSpacing);
            } finally {
                readable.recycle();
                wrapped.recycle();
                root.discardDisplayList();
            }
        }
    }

    private static FrameDigest digest(Bitmap bitmap, int lineHeight) {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0,
            bitmap.getWidth(), bitmap.getHeight());
        long hash = 0xcbf29ce484222325L;
        int background = pixels.length == 0 ? 0 : pixels[0];
        int visualPixels = 0;
        int visualRowBands = 0;
        for (int pixel : pixels) {
            hash ^= pixel & 0xffffffffL;
            hash *= 0x100000001b3L;
            if (pixel != background) visualPixels++;
        }
        lineHeight = Math.max(1, lineHeight);
        for (int row = 0; row < ROWS; row++) {
            int top = Math.max(0, row * lineHeight);
            int bottom = Math.min(bitmap.getHeight(), top + lineHeight);
            boolean visual = false;
            for (int y = top; y < bottom && !visual; y++) {
                int offset = y * bitmap.getWidth();
                for (int x = 0; x < bitmap.getWidth(); x++) {
                    if (pixels[offset + x] != background) {
                        visual = true;
                        break;
                    }
                }
            }
            if (visual) visualRowBands++;
        }
        return new FrameDigest(hash, background, visualPixels, visualRowBands);
    }

    private static byte[] buildContent() {
        StringBuilder output = new StringBuilder(GENERATED_LINES * COLUMNS);
        for (int line = 1; line <= GENERATED_LINES; line++) {
            output.append("\033[38;5;").append(16 + line % 216).append('m')
                .append(String.format(Locale.US, "seq=%03d ", line))
                .append("Ghostty retained viewport regression abcdefghijklmnopqrstuvwxyz")
                .append("\033[0m\r\n");
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FrameDigest {
        final long hash;
        final int background;
        final int visualPixels;
        final int visualRowBands;

        FrameDigest(long hash, int background, int visualPixels, int visualRowBands) {
            this.hash = hash;
            this.background = background;
            this.visualPixels = visualPixels;
            this.visualRowBands = visualRowBands;
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof FrameDigest)) return false;
            FrameDigest other = (FrameDigest) value;
            return hash == other.hash && background == other.background &&
                visualPixels == other.visualPixels && visualRowBands == other.visualRowBands;
        }

        @Override
        public int hashCode() {
            return ((int) (hash ^ (hash >>> 32)) * 31 + visualPixels) * 31 + visualRowBands;
        }

        @Override
        public String toString() {
            return "hash=" + Long.toUnsignedString(hash) +
                " background=0x" + Integer.toHexString(background) +
                " visualPixels=" + visualPixels +
                " visualRows=" + visualRowBands;
        }
    }

    private static final class NullOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
        @Override public void onTerminalHostControlCommand(String command, String argument) { }
    }
}
