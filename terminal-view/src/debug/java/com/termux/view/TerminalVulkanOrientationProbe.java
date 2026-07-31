package com.termux.view;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

/** Device-only pixel probe that catches Vulkan X/Y inversion independently of terminal content. */
public final class TerminalVulkanOrientationProbe {
    private static final int SIZE = 96;
    private static final int HALF = SIZE / 2;
    private static final int RESIZED_HEIGHT = SIZE + HALF;
    private static final int PROBE_TOP_ROW = -7;
    private static final int RED = 0xffff2020;
    private static final int GREEN = 0xff20ff20;
    private static final int BLUE = 0xff2020ff;
    private static final int YELLOW = 0xffffff20;
    private static final long TIMEOUT_MS = 5_000L;

    private TerminalVulkanOrientationProbe() {}

    @NonNull
    public static JSONObject run(@NonNull Instrumentation instrumentation,
                                 @NonNull Activity activity) throws Exception {
        JSONObject evidence = new JSONObject();
        evidence.put("available", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return evidence;

        AtomicReference<TerminalVulkanView> viewRef = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            TerminalVulkanView view = new TerminalVulkanView(activity);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(SIZE, SIZE);
            params.gravity = Gravity.TOP | Gravity.START;
            activity.addContentView(view, params);
            viewRef.set(view);
            view.submitFrame(createFrame());
        });
        TerminalVulkanView view = viewRef.get();
        evidence.put("supported", view != null && view.isSupported());
        if (view == null || !view.isSupported()) {
            remove(instrumentation, view);
            return evidence;
        }

        int[] sampled = null;
        int glyphPixels = 0;
        int[] resizedSampled = null;
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                AtomicReference<int[]> sampleRef = new AtomicReference<>();
                instrumentation.runOnMainSync(() -> {
                    if (!view.isFrameReady()) return;
                    Bitmap bitmap = view.getBitmap(SIZE, SIZE);
                    if (bitmap == null) return;
                    try {
                        sampleRef.set(new int[] {
                            bitmap.getPixel(HALF / 2, HALF / 2),
                            bitmap.getPixel(HALF + HALF / 2, HALF / 2),
                            bitmap.getPixel(HALF / 2, HALF + HALF / 2),
                            bitmap.getPixel(HALF + HALF / 2, HALF + HALF / 2)
                        });
                    } finally {
                        bitmap.recycle();
                    }
                });
                sampled = sampleRef.get();
                if (sampled != null && matches(sampled)) break;
                SystemClock.sleep(16L);
            }
            if (sampled != null && matches(sampled)) {
                instrumentation.runOnMainSync(() -> view.submitFrame(createGlyphFrame()));
                long glyphDeadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
                while (SystemClock.elapsedRealtime() < glyphDeadline) {
                    AtomicReference<Integer> glyphPixelsRef = new AtomicReference<>();
                    instrumentation.runOnMainSync(() -> {
                        if (!view.isFrameReady() || view.getPresentedFrameId() < 2L) return;
                        Bitmap bitmap = view.getBitmap(SIZE, SIZE);
                        if (bitmap == null) return;
                        try {
                            int visiblePixels = 0;
                            for (int y = 0; y < SIZE; y++) {
                                for (int x = 0; x < SIZE; x++) {
                                    int pixel = bitmap.getPixel(x, y);
                                    if (Color.red(pixel) > 64 || Color.green(pixel) > 64 ||
                                        Color.blue(pixel) > 64) visiblePixels++;
                                }
                            }
                            glyphPixelsRef.set(visiblePixels);
                        } finally {
                            bitmap.recycle();
                        }
                    });
                    Integer value = glyphPixelsRef.get();
                    if (value != null) {
                        glyphPixels = value;
                        if (glyphPixels >= 20) break;
                    }
                    SystemClock.sleep(16L);
                }
            }
            if (sampled != null && matches(sampled) && glyphPixels >= 20) {
                instrumentation.runOnMainSync(() -> {
                    ViewGroup.LayoutParams params = view.getLayoutParams();
                    params.height = RESIZED_HEIGHT;
                    view.setLayoutParams(params);
                    view.submitFrame(createResizedFrame());
                });
                long resizeDeadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
                while (SystemClock.elapsedRealtime() < resizeDeadline) {
                    AtomicReference<int[]> resizeSampleRef = new AtomicReference<>();
                    instrumentation.runOnMainSync(() -> {
                        if (view.getWidth() != SIZE || view.getHeight() != RESIZED_HEIGHT ||
                            !view.isFrameReady() || view.getPresentedFrameId() < 3L) return;
                        Bitmap bitmap = view.getBitmap(SIZE, RESIZED_HEIGHT);
                        if (bitmap == null) return;
                        try {
                            resizeSampleRef.set(new int[] {
                                bitmap.getPixel(HALF, HALF / 2),
                                bitmap.getPixel(HALF, HALF + HALF / 2),
                                bitmap.getPixel(HALF, SIZE + HALF / 2)
                            });
                        } finally {
                            bitmap.recycle();
                        }
                    });
                    resizedSampled = resizeSampleRef.get();
                    if (resizedSampled != null && matchesResize(resizedSampled)) break;
                    SystemClock.sleep(16L);
                }
            }
        } finally {
            remove(instrumentation, view);
        }

        boolean glyphPassed = glyphPixels >= 20;
        boolean resizePassed = resizedSampled != null && matchesResize(resizedSampled);
        boolean passed = sampled != null && matches(sampled) && glyphPassed && resizePassed;
        evidence.put("passed", passed);
        evidence.put("glyph_passed", glyphPassed);
        evidence.put("glyph_pixels", glyphPixels);
        evidence.put("surface_resize_passed", resizePassed);
        evidence.put("surface_resize_expected", "red,green,blue");
        evidence.put("surface_resize_sampled", resizedSampled == null
            ? "none" : colors(resizedSampled));
        evidence.put("expected", "red,green,blue,yellow");
        evidence.put("sampled", sampled == null ? "none" : colors(sampled));
        evidence.put("diagnostics", view.getDiagnostics());
        return evidence;
    }

    private static TerminalGpuFrame createFrame() {
        TerminalGpuFrame.Row top = new TerminalGpuFrame.Row(PROBE_TOP_ROW, Arrays.asList(
            new TerminalGpuFrame.Rect(0f, 0f, HALF, HALF, RED),
            new TerminalGpuFrame.Rect(HALF, 0f, SIZE, HALF, GREEN)),
            Collections.emptyList(), Collections.emptyList());
        TerminalGpuFrame.Row bottom = new TerminalGpuFrame.Row(PROBE_TOP_ROW + 1, Arrays.asList(
            new TerminalGpuFrame.Rect(0f, 0f, HALF, HALF, BLUE),
            new TerminalGpuFrame.Rect(HALF, 0f, SIZE, HALF, YELLOW)),
            Collections.emptyList(), Collections.emptyList());
        return new TerminalGpuFrame(1L, 1L, 1L, SIZE, SIZE, 16, Typeface.MONOSPACE,
            8f, HALF, -HALF, Color.BLACK, 2, PROBE_TOP_ROW, 0f, true, true,
            Arrays.asList(top, bottom));
    }

    private static TerminalGpuFrame createGlyphFrame() {
        String value = "MMMMMMMMMMMMMMMMM";
        TerminalGpuFrame.TextRun glyph = new TerminalGpuFrame.TextRun(
            value, 0, value.length(), 14f, 68f, 0f, Color.WHITE, false, false);
        TerminalGpuFrame.Row row = new TerminalGpuFrame.Row(PROBE_TOP_ROW,
            Collections.emptyList(), Collections.singletonList(glyph),
            Collections.emptyList());
        return new TerminalGpuFrame(2L, 2L, 2L, SIZE, SIZE, 56, Typeface.MONOSPACE,
            34f, 80, -60, Color.BLACK, 1, PROBE_TOP_ROW, 0f, true, true,
            Collections.singletonList(row));
    }

    /** Submit the final tall frame before SurfaceTexture necessarily publishes its new extent. */
    private static TerminalGpuFrame createResizedFrame() {
        TerminalGpuFrame.Row top = solidRow(PROBE_TOP_ROW, RED);
        TerminalGpuFrame.Row middle = solidRow(PROBE_TOP_ROW + 1, GREEN);
        TerminalGpuFrame.Row bottom = solidRow(PROBE_TOP_ROW + 2, BLUE);
        return new TerminalGpuFrame(3L, 3L, 3L, SIZE, RESIZED_HEIGHT, 16,
            Typeface.MONOSPACE, 8f, HALF, -HALF, Color.BLACK, 3, PROBE_TOP_ROW, 0f,
            true, true, Arrays.asList(top, middle, bottom));
    }

    private static TerminalGpuFrame.Row solidRow(int logicalRow, int color) {
        return new TerminalGpuFrame.Row(logicalRow,
            Collections.singletonList(new TerminalGpuFrame.Rect(0f, 0f, SIZE, HALF, color)),
            Collections.emptyList(), Collections.emptyList());
    }

    private static boolean matches(int[] sampled) {
        return sampled.length == 4 && close(sampled[0], RED) && close(sampled[1], GREEN) &&
            close(sampled[2], BLUE) && close(sampled[3], YELLOW);
    }

    private static boolean matchesResize(int[] sampled) {
        return sampled.length == 3 && close(sampled[0], RED) && close(sampled[1], GREEN) &&
            close(sampled[2], BLUE);
    }

    private static boolean close(int actual, int expected) {
        return Math.abs(Color.red(actual) - Color.red(expected)) <= 24 &&
            Math.abs(Color.green(actual) - Color.green(expected)) <= 24 &&
            Math.abs(Color.blue(actual) - Color.blue(expected)) <= 24 &&
            Color.alpha(actual) >= 220;
    }

    private static String colors(int[] sampled) {
        return String.format("%08x,%08x,%08x,%08x", sampled[0], sampled[1], sampled[2], sampled[3]);
    }

    private static void remove(Instrumentation instrumentation, TerminalVulkanView view) {
        if (view == null) return;
        instrumentation.runOnMainSync(() -> {
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
        });
    }
}
