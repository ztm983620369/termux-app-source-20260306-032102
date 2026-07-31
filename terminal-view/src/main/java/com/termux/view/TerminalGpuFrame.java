package com.termux.view;

import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable batch handed from Ghostty's retained model to the Vulkan render thread. */
final class TerminalGpuFrame {
    final long frameId;
    final long commandGeneration;
    final long modelRevision;
    final int viewWidth;
    final int viewHeight;
    final int textSize;
    final Typeface typeface;
    final float fontWidth;
    final int fontLineSpacing;
    final int fontAscent;
    final int backgroundColor;
    final int screenRows;
    final int viewportTopRow;
    final float viewportPixelOffset;
    final boolean fullFrame;
    final boolean contentReady;
    final List<Row> rows;
    final int cursorRow;
    final int cursorColumn;
    final int cursorStyle;
    final boolean cursorEnabled;
    final boolean cursorVisible;
    /** Bottommost semantic row at or below the cursor in this exact submitted frame. */
    final int imeProtectedBottomScreenRow;

    TerminalGpuFrame(long frameId, long commandGeneration, long modelRevision,
                     int viewWidth, int viewHeight, int textSize, Typeface typeface,
                     float fontWidth, int fontLineSpacing, int fontAscent, int backgroundColor,
                     int screenRows, int viewportTopRow, float viewportPixelOffset,
                     boolean fullFrame, boolean contentReady, List<Row> rows) {
        this(frameId, commandGeneration, modelRevision, viewWidth, viewHeight, textSize,
            typeface, fontWidth, fontLineSpacing, fontAscent, backgroundColor, screenRows,
            viewportTopRow, viewportPixelOffset, fullFrame, contentReady, rows,
            -1, -1, -1, false, false, -1);
    }

    TerminalGpuFrame(long frameId, long commandGeneration, long modelRevision,
                     int viewWidth, int viewHeight, int textSize, Typeface typeface,
                     float fontWidth, int fontLineSpacing, int fontAscent, int backgroundColor,
                     int screenRows, int viewportTopRow, float viewportPixelOffset,
                     boolean fullFrame, boolean contentReady, List<Row> rows,
                     int cursorRow, int cursorColumn, int cursorStyle,
                     boolean cursorEnabled, boolean cursorVisible) {
        this(frameId, commandGeneration, modelRevision, viewWidth, viewHeight, textSize,
            typeface, fontWidth, fontLineSpacing, fontAscent, backgroundColor, screenRows,
            viewportTopRow, viewportPixelOffset, fullFrame, contentReady, rows,
            cursorRow, cursorColumn, cursorStyle, cursorEnabled, cursorVisible, -1);
    }

    TerminalGpuFrame(long frameId, long commandGeneration, long modelRevision,
                     int viewWidth, int viewHeight, int textSize, Typeface typeface,
                     float fontWidth, int fontLineSpacing, int fontAscent, int backgroundColor,
                     int screenRows, int viewportTopRow, float viewportPixelOffset,
                     boolean fullFrame, boolean contentReady, List<Row> rows,
                     int cursorRow, int cursorColumn, int cursorStyle,
                     boolean cursorEnabled, boolean cursorVisible,
                     int imeProtectedBottomScreenRow) {
        this.frameId = frameId;
        this.commandGeneration = commandGeneration;
        this.modelRevision = modelRevision;
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;
        this.textSize = textSize;
        this.typeface = typeface;
        this.fontWidth = fontWidth;
        this.fontLineSpacing = fontLineSpacing;
        this.fontAscent = fontAscent;
        this.backgroundColor = backgroundColor;
        this.screenRows = screenRows;
        this.viewportTopRow = viewportTopRow;
        this.viewportPixelOffset = viewportPixelOffset;
        this.fullFrame = fullFrame;
        this.contentReady = contentReady;
        this.rows = immutableCopy(rows);
        this.cursorRow = cursorRow;
        this.cursorColumn = cursorColumn;
        this.cursorStyle = cursorStyle;
        this.cursorEnabled = cursorEnabled;
        this.cursorVisible = cursorVisible;
        this.imeProtectedBottomScreenRow = imeProtectedBottomScreenRow;
    }

    static TerminalGpuFrame incomplete(long frameId, int width, int height, int topRow) {
        return new TerminalGpuFrame(frameId, Long.MIN_VALUE, Long.MIN_VALUE,
            Math.max(1, width), Math.max(1, height), 14, Typeface.MONOSPACE,
            8f, 16, -12, 0xff000000, 0, topRow, 0f,
            true, false, Collections.emptyList());
    }

    static final class Row {
        final int logicalRow;
        final RectBatch backgrounds;
        final List<TextRun> text;
        final RectBatch decorations;
        final long contentHash;

        Row(int logicalRow, List<Rect> backgrounds, List<TextRun> text, List<Rect> decorations) {
            this(logicalRow, RectBatch.copyOf(backgrounds), text,
                RectBatch.copyOf(decorations), false);
        }

        Row(int logicalRow, RectBatch backgrounds, List<TextRun> text,
            RectBatch decorations) {
            this(logicalRow, backgrounds, text, decorations, false);
        }

        private Row(int logicalRow, RectBatch backgrounds, List<TextRun> text,
                    RectBatch decorations, boolean takeTextOwnership) {
            this.logicalRow = logicalRow;
            this.backgrounds = backgrounds == null ? RectBatch.EMPTY : backgrounds;
            this.text = takeTextOwnership ? immutableOwned(text) : immutableCopy(text);
            this.decorations = decorations == null ? RectBatch.EMPTY : decorations;
            this.contentHash = computeContentHash();
        }

        static Row fromOwnedCommands(int logicalRow,
                                     float[] backgroundBounds, int[] backgroundColors,
                                     List<TextRun> text,
                                     float[] decorationBounds, int[] decorationColors) {
            return new Row(logicalRow,
                RectBatch.takeOwnership(backgroundBounds, backgroundColors), text,
                RectBatch.takeOwnership(decorationBounds, decorationColors), true);
        }

        boolean hasSameContent(Row other) {
            if (this == other) return true;
            if (other == null || contentHash != other.contentHash ||
                backgrounds.size() != other.backgrounds.size() ||
                text.size() != other.text.size() ||
                decorations.size() != other.decorations.size()) return false;
            for (int index = 0; index < backgrounds.size(); index++) {
                if (!sameRect(backgrounds, index, other.backgrounds, index)) return false;
            }
            for (int index = 0; index < text.size(); index++) {
                if (!sameText(text.get(index), other.text.get(index))) return false;
            }
            for (int index = 0; index < decorations.size(); index++) {
                if (!sameRect(decorations, index, other.decorations, index)) return false;
            }
            return true;
        }

        private long computeContentHash() {
            long hash = 0xcbf29ce484222325L;
            hash = mix(hash, backgrounds.size());
            for (int index = 0; index < backgrounds.size(); index++) {
                hash = hashRect(hash, backgrounds, index);
            }
            hash = mix(hash, text.size());
            for (TextRun run : text) {
                hash = mix(hash, Float.floatToIntBits(run.left));
                hash = mix(hash, Float.floatToIntBits(run.width));
                hash = mix(hash, Float.floatToIntBits(run.measuredWidth));
                hash = mix(hash, run.color);
                hash = mix(hash, run.bold ? 1 : 0);
                hash = mix(hash, run.italic ? 1 : 0);
                int length = Math.max(0, run.valueEnd - run.valueStart);
                hash = mix(hash, length);
                for (int index = 0; index < length; index++) {
                    hash = mix(hash, run.value.charAt(run.valueStart + index));
                }
            }
            hash = mix(hash, decorations.size());
            for (int index = 0; index < decorations.size(); index++) {
                hash = hashRect(hash, decorations, index);
            }
            return hash;
        }

        private static long hashRect(long hash, RectBatch batch, int index) {
            hash = mix(hash, Float.floatToIntBits(batch.left(index)));
            hash = mix(hash, Float.floatToIntBits(batch.top(index)));
            hash = mix(hash, Float.floatToIntBits(batch.right(index)));
            hash = mix(hash, Float.floatToIntBits(batch.bottom(index)));
            return mix(hash, batch.color(index));
        }

        private static boolean sameRect(RectBatch first, int firstIndex,
                                        RectBatch second, int secondIndex) {
            return Float.floatToIntBits(first.left(firstIndex)) ==
                    Float.floatToIntBits(second.left(secondIndex)) &&
                Float.floatToIntBits(first.top(firstIndex)) ==
                    Float.floatToIntBits(second.top(secondIndex)) &&
                Float.floatToIntBits(first.right(firstIndex)) ==
                    Float.floatToIntBits(second.right(secondIndex)) &&
                Float.floatToIntBits(first.bottom(firstIndex)) ==
                    Float.floatToIntBits(second.bottom(secondIndex)) &&
                first.color(firstIndex) == second.color(secondIndex);
        }

        private static boolean sameText(TextRun first, TextRun second) {
            int firstLength = first.valueEnd - first.valueStart;
            int secondLength = second.valueEnd - second.valueStart;
            if (firstLength != secondLength ||
                Float.floatToIntBits(first.left) != Float.floatToIntBits(second.left) ||
                Float.floatToIntBits(first.width) != Float.floatToIntBits(second.width) ||
                Float.floatToIntBits(first.measuredWidth) !=
                    Float.floatToIntBits(second.measuredWidth) ||
                first.color != second.color || first.bold != second.bold ||
                first.italic != second.italic) return false;
            for (int index = 0; index < firstLength; index++) {
                if (first.value.charAt(first.valueStart + index) !=
                    second.value.charAt(second.valueStart + index)) return false;
            }
            return true;
        }

        private static long mix(long hash, int value) {
            hash ^= value & 0xffffffffL;
            return hash * 0x100000001b3L;
        }
    }

    /** Immutable structure-of-arrays storage for the high-volume true-color rectangle path. */
    static final class RectBatch {
        static final RectBatch EMPTY = new RectBatch(new float[0], new int[0]);

        private static final int FLOATS_PER_RECT = 4;
        private final float[] bounds;
        private final int[] colors;

        private RectBatch(float[] bounds, int[] colors) {
            if (bounds == null || colors == null ||
                bounds.length != colors.length * FLOATS_PER_RECT) {
                throw new IllegalArgumentException("Rectangle storage has inconsistent lengths");
            }
            this.bounds = bounds;
            this.colors = colors;
        }

        static RectBatch copyOf(List<Rect> source) {
            if (source == null || source.isEmpty()) return EMPTY;
            int count = source.size();
            float[] bounds = new float[count * FLOATS_PER_RECT];
            int[] colors = new int[count];
            for (int index = 0; index < count; index++) {
                Rect rect = source.get(index);
                int offset = index * FLOATS_PER_RECT;
                bounds[offset] = rect.left;
                bounds[offset + 1] = rect.top;
                bounds[offset + 2] = rect.right;
                bounds[offset + 3] = rect.bottom;
                colors[index] = rect.color;
            }
            return new RectBatch(bounds, colors);
        }

        static RectBatch takeOwnership(float[] bounds, int[] colors) {
            if ((bounds == null || bounds.length == 0) &&
                (colors == null || colors.length == 0)) return EMPTY;
            return new RectBatch(bounds, colors);
        }

        int size() {
            return colors.length;
        }

        float left(int index) {
            return bounds[index * FLOATS_PER_RECT];
        }

        float top(int index) {
            return bounds[index * FLOATS_PER_RECT + 1];
        }

        float right(int index) {
            return bounds[index * FLOATS_PER_RECT + 2];
        }

        float bottom(int index) {
            return bounds[index * FLOATS_PER_RECT + 3];
        }

        int color(int index) {
            return colors[index];
        }
    }

    static final class Rect {
        final float left;
        final float top;
        final float right;
        final float bottom;
        final int color;

        Rect(float left, float top, float right, float bottom, int color) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.color = color;
        }
    }

    static final class TextRun {
        final String value;
        final int valueStart;
        final int valueEnd;
        final float left;
        final float width;
        final float measuredWidth;
        final int color;
        final boolean bold;
        final boolean italic;

        TextRun(String value, int valueStart, int valueEnd,
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
        }
    }

    private static <T> List<T> immutableCopy(List<T> source) {
        return source == null || source.isEmpty() ? Collections.emptyList() :
            Collections.unmodifiableList(new ArrayList<>(source));
    }

    private static <T> List<T> immutableOwned(List<T> source) {
        return source == null || source.isEmpty() ? Collections.emptyList() :
            Collections.unmodifiableList(source);
    }
}
