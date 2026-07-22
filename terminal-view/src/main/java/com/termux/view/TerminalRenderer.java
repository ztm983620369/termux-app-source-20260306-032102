package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();

    // Cache glyph widths for non-ASCII code points. Terminal output often reuses a small set of
    // Unicode symbols (e.g. tmux box-drawing). Caching avoids repeated measureText() calls.
    private static final int WIDTH_CACHE_SIZE = 4096; // must be power of two
    private static final int WIDTH_CACHE_PROBE_STEPS = 4;
    private final int[] mWidthCacheKeys = new int[WIDTH_CACHE_SIZE];
    private final float[] mWidthCacheValues = new float[WIDTH_CACHE_SIZE];

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    private float getMeasuredCodePointWidth(int codePoint, char[] line, int start, int charsForCodePoint) {
        if (codePoint < asciiMeasures.length) return asciiMeasures[codePoint];

        // Cheap hash, overwrite on collision (good enough for small working sets).
        int slot = (codePoint * 0x9E3779B9) & (WIDTH_CACHE_SIZE - 1);
        for (int i = 0; i < WIDTH_CACHE_PROBE_STEPS; i++) {
            int idx = (slot + i) & (WIDTH_CACHE_SIZE - 1);
            if (mWidthCacheKeys[idx] == codePoint) return mWidthCacheValues[idx];
        }

        float measured = mTextPaint.measureText(line, start, charsForCodePoint);
        // Insert into the first free slot if present; otherwise overwrite the base slot.
        for (int i = 0; i < WIDTH_CACHE_PROBE_STEPS; i++) {
            int idx = (slot + i) & (WIDTH_CACHE_SIZE - 1);
            if (mWidthCacheKeys[idx] == 0 || mWidthCacheKeys[idx] == codePoint) {
                mWidthCacheKeys[idx] = codePoint;
                mWidthCacheValues[idx] = measured;
                return measured;
            }
        }
        mWidthCacheKeys[slot] = codePoint;
        mWidthCacheValues[slot] = measured;
        return measured;
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        renderInternal(mEmulator, canvas, topRow, selectionY1, selectionY2, selectionX1, selectionX2, true);
    }

    /** Scalar row decoder used for pixel-level differential tests. */
    final void renderReferenceForTesting(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                                         int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        renderInternal(mEmulator, canvas, topRow, selectionY1, selectionY2, selectionX1, selectionX2, false);
    }

    private void renderInternal(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                                int selectionY1, int selectionY2, int selectionX1, int selectionX2,
                                boolean useSimpleRowFastPath) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();
        final Rect clipRect = canvas.getClipBounds();
        if (clipRect.isEmpty()) return;

        if (reverseVideo) {
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);
        } else {
            mTextPaint.setColor(palette[TextStyle.COLOR_INDEX_BACKGROUND]);
            canvas.drawRect(clipRect, mTextPaint);
        }

        final int contentTop = mFontLineSpacingAndAscent;
        final int firstScreenRow = Math.max(0,
            (Math.max(contentTop, clipRect.top) - contentTop) / mFontLineSpacing);
        final int lastScreenRowExclusive = Math.min(
            mEmulator.mRows,
            (Math.max(contentTop, clipRect.bottom) - contentTop + mFontLineSpacing - 1) /
                mFontLineSpacing
        );
        float heightOffset = mFontLineSpacingAndAscent + firstScreenRow * mFontLineSpacing;
        for (int screenRowIndex = firstScreenRow; screenRowIndex < lastScreenRowExclusive; screenRowIndex++) {
            heightOffset += mFontLineSpacing;
            final int row = topRow + screenRowIndex;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();
            final boolean simpleRow = useSimpleRowFastPath && lineObject.hasOnlyOneWidthCharacters();

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = !simpleRow && Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final int codePointWcWidth = simpleRow ? 1 : WcWidth.width(codePoint);
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;
                final long style = lineObject.getStyle(column);

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                final float measuredCodePointWidth = getMeasuredCodePointWidth(codePoint, line, currentCharIndex, charsForCodePoint);
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (column == 0) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (!simpleRow && currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
        }
    }

    final TerminalRenderSnapshot buildRenderSnapshot(TerminalEmulator mEmulator, int viewWidth, int viewHeight,
                                                     long contentGeneration, int topRow,
                                                     int selectionY1, int selectionY2, int selectionX1, int selectionX2,
                                                     boolean fullFrame, int dirtyRowStart, int dirtyRowEnd, int scrollRows) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();
        final int backgroundColor = reverseVideo
            ? palette[TextStyle.COLOR_INDEX_FOREGROUND]
            : palette[TextStyle.COLOR_INDEX_BACKGROUND];

        if (fullFrame) {
            dirtyRowStart = 0;
            dirtyRowEnd = mEmulator.mRows;
            scrollRows = 0;
        } else {
            dirtyRowStart = Math.max(0, dirtyRowStart);
            dirtyRowEnd = Math.min(mEmulator.mRows, dirtyRowEnd);
            if (dirtyRowEnd <= dirtyRowStart) {
                dirtyRowStart = 0;
                dirtyRowEnd = 0;
            }
        }

        final int dirtyRows = Math.max(0, dirtyRowEnd - dirtyRowStart);
        List<TerminalRenderSnapshot.RenderRect> backgroundRects =
            new ArrayList<>(estimateSnapshotRectCapacity(dirtyRows));
        List<TerminalRenderSnapshot.TextRun> textRuns =
            new ArrayList<>(estimateSnapshotTextRunCapacity(dirtyRows, columns));

        for (int screenRowIndex = dirtyRowStart; screenRowIndex < dirtyRowEnd; screenRowIndex++) {
            float heightOffset = mFontLineSpacingAndAscent + (screenRowIndex + 1) * mFontLineSpacing;
            final int row = topRow + screenRowIndex;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final int codePointWcWidth = WcWidth.width(codePoint);
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;
                final long style = lineObject.getStyle(column);
                final float measuredCodePointWidth = getMeasuredCodePointWidth(codePoint, line, currentCharIndex, charsForCodePoint);
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (column != 0) {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        appendSnapshotTextRun(backgroundRects, textRuns, screenRowIndex, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            appendSnapshotTextRun(backgroundRects, textRuns, screenRowIndex, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
        }

        return new TerminalRenderSnapshot(
            Math.max(1, viewWidth),
            Math.max(1, viewHeight),
            contentGeneration,
            true,
            mTextSize,
            mTypeface,
            mFontWidth,
            mFontLineSpacing,
            mFontAscent,
            backgroundColor,
            mEmulator.mRows,
            fullFrame,
            dirtyRowStart,
            dirtyRowEnd,
            scrollRows,
            backgroundRects,
            textRuns,
            Collections.emptyList()
        );
    }

    private static int estimateSnapshotRectCapacity(int dirtyRows) {
        if (dirtyRows <= 0) return 0;
        return Math.max(4, dirtyRows * 2);
    }

    private static int estimateSnapshotTextRunCapacity(int dirtyRows, int columns) {
        if (dirtyRows <= 0) return 0;
        return dirtyRows * Math.max(1, Math.min(columns, 8));
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= ((right - left) * 3) / 4.;
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // The text alignment is the default Paint.Align.LEFT.
            canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint);
        }

        if (savedMatrix) canvas.restore();
    }

    private void appendSnapshotTextRun(List<TerminalRenderSnapshot.RenderRect> backgroundRects,
                                       List<TerminalRenderSnapshot.TextRun> textRuns,
                                       int screenRow, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                                       int startCharIndex, int runWidthChars, float measuredWidth, int cursor, int cursorStyle,
                                       long textStyle, boolean reverseVideo) {
        if (startColumn < 0 || runWidthColumns <= 0) return;

        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        final boolean reverseVideoHere = reverseVideo ^ ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0);
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        if (dim) {
            int red = (0xFF & (foreColor >> 16));
            int green = (0xFF & (foreColor >> 8));
            int blue = (0xFF & foreColor);
            red = red * 2 / 3;
            green = green * 2 / 3;
            blue = blue * 2 / 3;
            foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
        }

        final float left = startColumn * mFontWidth;
        final float right = left + runWidthColumns * mFontWidth;
        final float top = y - mFontLineSpacingAndAscent + mFontAscent;

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            backgroundRects.add(new TerminalRenderSnapshot.RenderRect(screenRow, left, top, right, y, backColor));
        }

        if (cursor != 0) {
            float cursorRight = right;
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) cursorRight -= ((right - left) * 3) / 4.;
            backgroundRects.add(new TerminalRenderSnapshot.RenderRect(screenRow, left, y - cursorHeight, cursorRight, y, cursor));
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0 || runWidthChars <= 0) return;
        if (!underline && !strikeThrough && !hasNonSpaceText(text, startCharIndex, runWidthChars)) return;

        int flags = 0;
        if (bold) flags |= TerminalRenderSnapshot.FLAG_BOLD;
        if (italic) flags |= TerminalRenderSnapshot.FLAG_ITALIC;
        if (underline) flags |= TerminalRenderSnapshot.FLAG_UNDERLINE;
        if (strikeThrough) flags |= TerminalRenderSnapshot.FLAG_STRIKETHROUGH;

        textRuns.add(new TerminalRenderSnapshot.TextRun(
            screenRow,
            new String(text, startCharIndex, runWidthChars),
            left,
            top,
            Math.max(1f, right - left),
            measuredWidth,
            foreColor,
            flags
        ));
    }

    private static boolean hasNonSpaceText(char[] text, int start, int count) {
        for (int i = 0; i < count; i++) {
            if (text[start + i] != ' ') return true;
        }
        return false;
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
