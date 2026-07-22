package com.termux.view;

import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

final class TerminalRenderSnapshot {

    static final int FLAG_BOLD = 1;
    static final int FLAG_ITALIC = 1 << 1;
    static final int FLAG_UNDERLINE = 1 << 2;
    static final int FLAG_STRIKETHROUGH = 1 << 3;

    final int viewWidth;
    final int viewHeight;
    final long contentGeneration;
    final boolean contentReady;
    final int textSize;
    final Typeface typeface;
    final float fontWidth;
    final int fontLineSpacing;
    final int fontAscent;
    final int backgroundColor;
    final int screenRows;
    final boolean fullFrame;
    final int dirtyRowStart;
    final int dirtyRowEnd;
    final int scrollRows;
    final List<RenderRect> backgroundRects;
    final List<TextRun> textRuns;
    final List<RenderRect> decorationRects;

    TerminalRenderSnapshot(int viewWidth, int viewHeight, long contentGeneration, boolean contentReady,
                           int textSize, Typeface typeface,
                           float fontWidth, int fontLineSpacing, int fontAscent, int backgroundColor,
                           int screenRows, boolean fullFrame, int dirtyRowStart, int dirtyRowEnd, int scrollRows,
                           List<RenderRect> backgroundRects, List<TextRun> textRuns,
                           List<RenderRect> decorationRects) {
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;
        this.contentGeneration = contentGeneration;
        this.contentReady = contentReady;
        this.textSize = textSize;
        this.typeface = typeface;
        this.fontWidth = fontWidth;
        this.fontLineSpacing = fontLineSpacing;
        this.fontAscent = fontAscent;
        this.backgroundColor = backgroundColor;
        this.screenRows = screenRows;
        this.fullFrame = fullFrame;
        this.dirtyRowStart = dirtyRowStart;
        this.dirtyRowEnd = dirtyRowEnd;
        this.scrollRows = scrollRows;
        this.backgroundRects = backgroundRects;
        this.textRuns = textRuns;
        this.decorationRects = decorationRects;
    }

    static TerminalRenderSnapshot empty(int width, int height) {
        return empty(width, height, 0L);
    }

    static TerminalRenderSnapshot empty(int width, int height, long contentGeneration) {
        return new TerminalRenderSnapshot(
            width,
            height,
            contentGeneration,
            false,
            14,
            Typeface.MONOSPACE,
            8f,
            16,
            -12,
            0xff000000,
            0,
            true,
            0,
            0,
            0,
            new ArrayList<>(),
            new ArrayList<>(),
            new ArrayList<>()
        );
    }

    static final class RenderRect {
        final int row;
        final float left;
        final float top;
        final float right;
        final float bottom;
        final int color;

        RenderRect(int row, float left, float top, float right, float bottom, int color) {
            this.row = row;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.color = color;
        }
    }

    static final class TextRun {
        final int row;
        final String text;
        final float left;
        final float top;
        final float width;
        final float measuredWidth;
        final int color;
        final int flags;

        TextRun(int row, String text, float left, float top, float width, float measuredWidth, int color, int flags) {
            this.row = row;
            this.text = text;
            this.left = left;
            this.top = top;
            this.width = width;
            this.measuredWidth = measuredWidth;
            this.color = color;
            this.flags = flags;
        }
    }
}
