package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import java.util.Arrays;
import java.util.Locale;

/** Device-only cost probe for the exact Canvas shaping path used by whole-run masks. */
public final class TerminalRunRasterProbe {
    private static final int TEXT_SIZE = 8;
    private static final int VIEW_WIDTH = 1184;
    private static final int COLUMNS = 169;
    private static final int ROWS = 159;
    private static final int WARMUP_FRAMES = 5;
    private static final int MEASURED_FRAMES = 21;

    private TerminalRunRasterProbe() {
    }

    public static String run() {
        TerminalRenderer metrics = new TerminalRenderer(TEXT_SIZE, Typeface.MONOSPACE);
        int lineSpacing = Math.max(1, metrics.mFontLineSpacing);
        int height = Math.max(1, lineSpacing * ROWS);
        Bitmap bitmap = Bitmap.createBitmap(VIEW_WIDTH, height, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(TEXT_SIZE);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        String[] lines = createLines();

        for (int index = 0; index < WARMUP_FRAMES; index++) {
            drawFrame(canvas, paint, lines, lineSpacing);
        }
        long[] samples = new long[MEASURED_FRAMES];
        for (int index = 0; index < MEASURED_FRAMES; index++) {
            long started = System.nanoTime();
            drawFrame(canvas, paint, lines, lineSpacing);
            samples[index] = System.nanoTime() - started;
        }
        Arrays.sort(samples);
        long median = samples[samples.length / 2];
        long p95 = samples[(int) Math.ceil(samples.length * 0.95) - 1];
        long maximum = samples[samples.length - 1];
        int visibleSamples = 0;
        for (int row = 0; row < ROWS; row += 7) {
            int y = Math.min(height - 1, row * lineSpacing + lineSpacing / 2);
            for (int x = 0; x < VIEW_WIDTH; x += 17) {
                if (Color.alpha(bitmap.getPixel(x, y)) != 0) visibleSamples++;
            }
        }
        bitmap.recycle();
        return String.format(Locale.US,
            "TERMUX_RUN_RASTER text=%d grid=%dx%d bitmap=%dx%d frames=%d " +
                "median_us=%d p95_us=%d max_us=%d visible_samples=%d",
            TEXT_SIZE, COLUMNS, ROWS, VIEW_WIDTH, height, MEASURED_FRAMES,
            median / 1000L, p95 / 1000L, maximum / 1000L, visibleSamples);
    }

    private static void drawFrame(Canvas canvas, Paint paint, String[] lines, int lineSpacing) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        for (int row = 0; row < lines.length; row++) {
            canvas.drawText(lines[row], 0f, (row + 1f) * lineSpacing, paint);
        }
    }

    private static String[] createLines() {
        String alphabet = "0123456789 abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ " +
            "[]{}()<>=+-*/_|:;,.!?";
        String[] lines = new String[ROWS];
        for (int row = 0; row < ROWS; row++) {
            StringBuilder value = new StringBuilder(COLUMNS);
            for (int column = 0; column < COLUMNS; column++) {
                value.append(alphabet.charAt((row * 29 + column * 17) % alphabet.length()));
            }
            lines[row] = value.toString();
        }
        return lines;
    }
}
