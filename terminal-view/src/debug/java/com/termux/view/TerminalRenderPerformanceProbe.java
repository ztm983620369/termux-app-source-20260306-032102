package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Debug-only Android Canvas benchmark and pixel differential for terminal rows. */
public final class TerminalRenderPerformanceProbe {

    private static final int COLUMNS = 100;
    private static final int ROWS = 42;
    private static final int ITERATIONS = 9;

    private TerminalRenderPerformanceProbe() {
    }

    public static void main(String[] args) {
        TerminalEmulator emulator = createTerminal();
        TerminalRenderer renderer = new TerminalRenderer(24, Typeface.MONOSPACE);
        int width = Math.max(1, (int) Math.ceil(renderer.mFontWidth * COLUMNS));
        int height = Math.max(1, renderer.mFontLineSpacingAndAscent + renderer.mFontLineSpacing * ROWS);

        assertPixelEquivalent(renderer, emulator, width, height, null);
        int clipTop = renderer.mFontLineSpacingAndAscent + 15 * renderer.mFontLineSpacing;
        Rect clip = new Rect(0, clipTop, width, clipTop + 3 * renderer.mFontLineSpacing);
        assertPixelEquivalent(renderer, emulator, width, height, clip);

        Bitmap optimizedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Bitmap referenceBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas optimizedCanvas = new Canvas(optimizedBitmap);
        Canvas referenceCanvas = new Canvas(referenceBitmap);
        for (int i = 0; i < 4; i++) {
            renderer.render(emulator, optimizedCanvas, 0, -1, -1, -1, -1);
            renderer.renderReferenceForTesting(emulator, referenceCanvas, 0, -1, -1, -1, -1);
        }

        long[] optimized = new long[ITERATIONS];
        long[] reference = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            optimized[i] = measure(renderer, emulator, optimizedCanvas, true, 160);
            reference[i] = measure(renderer, emulator, referenceCanvas, false, 160);
        }
        long optimizedMedian = median(optimized);
        long referenceMedian = median(reference);
        System.out.println("TERMUX_PERF render_frames=160" +
            " optimized_ns=" + optimizedMedian +
            " reference_ns=" + referenceMedian +
            " optimized_us_frame=" + format(optimizedMedian / 160000.0) +
            " reference_us_frame=" + format(referenceMedian / 160000.0) +
            " speedup=" + format((double) referenceMedian / optimizedMedian) +
            " pixel_diff=0 size=" + width + 'x' + height);

        optimizedBitmap.recycle();
        referenceBitmap.recycle();
    }

    private static TerminalEmulator createTerminal() {
        TerminalEmulator emulator = new TerminalEmulator(new NullOutput(), COLUMNS, ROWS, 12, 24, 200, null);
        StringBuilder content = new StringBuilder();
        for (int row = 0; row < ROWS + 6; row++) {
            content.append("\033[38;5;").append(16 + row % 200).append('m')
                .append("row=").append(row)
                .append(" terminal renderer ascii fast path 0123456789 abcdefghijklmnopqrstuvwxyz")
                .append("\033[0m\r\n");
        }
        content.append("\033[10;4H\033[1;4;38;2;80;220;120mStyled text\033[0m")
            .append("\033[12;8H\u4e2d\u6587 e\u0301 \ud83d\ude80")
            .append("\033[20;25Hcursor");
        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
        return emulator;
    }

    private static void assertPixelEquivalent(TerminalRenderer renderer, TerminalEmulator emulator,
                                              int width, int height, Rect clip) {
        Bitmap optimized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Bitmap reference = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        optimized.eraseColor(Color.MAGENTA);
        reference.eraseColor(Color.MAGENTA);
        Canvas optimizedCanvas = new Canvas(optimized);
        Canvas referenceCanvas = new Canvas(reference);
        if (clip != null) {
            optimizedCanvas.clipRect(clip);
            referenceCanvas.clipRect(clip);
        }
        renderer.render(emulator, optimizedCanvas, 0, 8, 13, 5, 40);
        renderer.renderReferenceForTesting(emulator, referenceCanvas, 0, 8, 13, 5, 40);

        int[] optimizedPixels = new int[width * height];
        int[] referencePixels = new int[width * height];
        optimized.getPixels(optimizedPixels, 0, width, 0, 0, width, height);
        reference.getPixels(referencePixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < optimizedPixels.length; i++) {
            if (optimizedPixels[i] != referencePixels[i]) {
                throw new AssertionError("Pixel mismatch at index=" + i +
                    " optimized=0x" + Integer.toHexString(optimizedPixels[i]) +
                    " reference=0x" + Integer.toHexString(referencePixels[i]));
            }
        }
        optimized.recycle();
        reference.recycle();
    }

    private static long measure(TerminalRenderer renderer, TerminalEmulator emulator, Canvas canvas,
                                boolean optimized, int frames) {
        long started = System.nanoTime();
        for (int frame = 0; frame < frames; frame++) {
            if (optimized) {
                renderer.render(emulator, canvas, 0, -1, -1, -1, -1);
            } else {
                renderer.renderReferenceForTesting(emulator, canvas, 0, -1, -1, -1, -1);
            }
        }
        return System.nanoTime() - started;
    }

    private static long median(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static String format(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
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
