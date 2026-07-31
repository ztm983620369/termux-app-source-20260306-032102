package com.termux.terminal;

import java.util.Arrays;
import java.util.Objects;

/**
 * A circular buffer of {@link TerminalRow}:s which keeps notes about what is visible on a logical screen and the scroll
 * history.
 * <p>
 * See {@link #externalToInternalRow(int)} for how to map from logical screen rows to array indices.
 */
public final class TerminalBuffer {

    TerminalRow[] mLines;
    /** The length of {@link #mLines}. */
    int mTotalRows;
    /** The number of rows and columns visible on the screen. */
    int mScreenRows, mColumns;
    /** The number of rows kept in history. */
    private int mActiveTranscriptRows = 0;
    /** The index in the circular buffer where the visible screen starts. */
    private int mScreenFirstRow = 0;

    /** Scratch buffer for row rotations (IL/DL). */
    private TerminalRow[] mRowSwapTmp;
    /** Dirty visible screen rows tracked as [start, end). */
    private int mDirtyStartRow = Integer.MAX_VALUE;
    private int mDirtyEndRow = Integer.MIN_VALUE;
    private volatile long mContentRevision;
    private long[] mSynchronizedOutputRowHashes;
    private int mSynchronizedOutputRows;
    private int mSynchronizedOutputColumns;
    private int mDirtyStartBeforeSynchronizedOutput;
    private int mDirtyEndBeforeSynchronizedOutput;
    private boolean mSynchronizedOutputSnapshotActive;

    /**
     * Create a transcript screen.
     *
     * @param columns    the width of the screen in characters.
     * @param totalRows  the height of the entire text area, in rows of text.
     * @param screenRows the height of just the screen, not including the transcript that holds lines that have scrolled off
     *                   the top of the screen.
     */
    public TerminalBuffer(int columns, int totalRows, int screenRows) {
        mColumns = columns;
        mTotalRows = totalRows;
        mScreenRows = screenRows;
        mLines = new TerminalRow[totalRows];

        blockSet(0, 0, columns, screenRows, ' ', TextStyle.NORMAL);
    }

    public String getTranscriptText() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows).trim();
    }

    public String getTranscriptTextWithoutJoinedLines() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows, false).trim();
    }

    public String getTranscriptTextWithFullLinesJoined() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows, true, true).trim();
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2) {
        return getSelectedText(selX1, selY1, selX2, selY2, true);
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2, boolean joinBackLines) {
        return getSelectedText(selX1, selY1, selX2, selY2, joinBackLines, false);
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2, boolean joinBackLines, boolean joinFullLines) {
        final StringBuilder builder = new StringBuilder();
        final int columns = mColumns;

        if (selY1 < -getActiveTranscriptRows()) selY1 = -getActiveTranscriptRows();
        if (selY2 >= mScreenRows) selY2 = mScreenRows - 1;

        for (int row = selY1; row <= selY2; row++) {
            int x1 = (row == selY1) ? selX1 : 0;
            int x2;
            if (row == selY2) {
                x2 = selX2 + 1;
                if (x2 > columns) x2 = columns;
            } else {
                x2 = columns;
            }
            TerminalRow lineObject = mLines[externalToInternalRow(row)];
            int x1Index = lineObject.findStartOfColumn(x1);
            int x2Index = (x2 < mColumns) ? lineObject.findStartOfColumn(x2) : lineObject.getSpaceUsed();
            if (x2Index == x1Index) {
                // Selected the start of a wide character.
                x2Index = lineObject.findStartOfColumn(x2 + 1);
            }
            char[] line = lineObject.mText;
            int lastPrintingCharIndex = -1;
            int i;
            boolean rowLineWrap = getLineWrap(row);
            if (rowLineWrap && x2 == columns) {
                // If the line was wrapped, we shouldn't lose trailing space:
                lastPrintingCharIndex = x2Index - 1;
            } else {
                for (i = x1Index; i < x2Index; ++i) {
                    char c = line[i];
                    if (c != ' ') lastPrintingCharIndex = i;
                }
            }

            int len = lastPrintingCharIndex - x1Index + 1;
            if (lastPrintingCharIndex != -1 && len > 0)
                builder.append(line, x1Index, len);

            boolean lineFillsWidth = lastPrintingCharIndex == x2Index - 1;
            if ((!joinBackLines || !rowLineWrap) && (!joinFullLines || !lineFillsWidth)
                && row < selY2 && row < mScreenRows - 1) builder.append('\n');
        }
        return builder.toString();
    }

    public String getWordAtLocation(int x, int y) {
        // Support both visible screen (0..mScreenRows-1) and transcript (-activeTranscriptRows..-1).
        final int minRow = -getActiveTranscriptRows();
        final int maxRow = mScreenRows - 1;
        if (y < minRow) y = minRow;
        if (y > maxRow) y = maxRow;
        if (x < 0) x = 0;
        if (x >= mColumns) x = mColumns - 1;

        // Set y1 and y2 to the rows where the wrapped line starts and ends.
        // A row "continues" if it wraps to the next row (mLineWrap), or if it completely fills the
        // screen width. The latter is a heuristic to handle long tokens that were split without
        // line-wrap metadata (e.g. due to explicit newlines or output formatting).
        int y1 = y;
        int y2 = y;
        while (y1 > minRow && (getLineWrap(y1 - 1) || rowFillsScreenWidth(y1 - 1))) {
            y1--;
        }
        while (y2 < maxRow && (getLineWrap(y2) || rowFillsScreenWidth(y2))) {
            y2++;
        }

        // Get the text for the whole wrapped line
        String text = getSelectedText(0, y1, mColumns, y2, true, true);
        // The index of x in text
        int textOffset = (y - y1) * mColumns + x;

        if (textOffset < 0) textOffset = 0;
        if (textOffset >= text.length()) {
          // The click was to the right of the last word on the line, so
          // there's no word to return
          return "";
        }

        // Set x1 and x2 to the indices of the last space before x and the
        // first space after x in text respectively
        int x1 = text.lastIndexOf(' ', textOffset);
        int x2 = text.indexOf(' ', textOffset);
        if (x2 == -1) {
            x2 = text.length();
        }

        if (x1 == x2) {
          // The click was on a space, so there's no word to return
          return "";
        }
        return text.substring(x1 + 1, x2);
    }

    private boolean rowFillsScreenWidth(int row) {
        if (row < -getActiveTranscriptRows() || row >= mScreenRows) return false;
        TerminalRow line = allocateFullLineIfNecessary(externalToInternalRow(row));
        if (line == null) return false;
        // Consider the row "filled" if its last cell is not whitespace. This matches the
        // getSelectedText(..., joinFullLines=true) heuristic for joining full-width lines.
        int lastColumnIndex = line.findStartOfColumn(mColumns - 1);
        return line.mText[lastColumnIndex] != ' ';
    }

    public int getActiveTranscriptRows() {
        return mActiveTranscriptRows;
    }

    public int getActiveRows() {
        return mActiveTranscriptRows + mScreenRows;
    }

    /** Returns the last visible screen row containing text, or {@code -1} when none does. */
    public int findLastNonBlankScreenRow(int firstScreenRow) {
        if (mScreenRows <= 0) return -1;
        int first = Math.max(0, Math.min(mScreenRows - 1, firstScreenRow));
        for (int row = mScreenRows - 1; row >= first; row--) {
            TerminalRow line = mLines[externalToInternalRow(row)];
            if (line != null && !line.isBlank()) return row;
        }
        return -1;
    }

    public boolean hasDirtyRows() {
        return mDirtyStartRow < mDirtyEndRow;
    }

    public int getDirtyStartRow() {
        return hasDirtyRows() ? mDirtyStartRow : 0;
    }

    public int getDirtyEndRow() {
        return hasDirtyRows() ? mDirtyEndRow : 0;
    }

    /** Monotonic signal for text, style, row-order, or wrapping changes in this buffer. */
    public long getContentRevision() {
        return mContentRevision;
    }

    public void clearDirtyRows() {
        mDirtyStartRow = Integer.MAX_VALUE;
        mDirtyEndRow = Integer.MIN_VALUE;
    }

    public void markAllScreenRowsDirty() {
        markDirtyRows(0, mScreenRows);
    }

    public void markDirtyRows(int startRow, int endRow) {
        if (mScreenRows <= 0) return;
        if (startRow < 0) startRow = 0;
        if (endRow > mScreenRows) endRow = mScreenRows;
        if (endRow <= startRow) return;
        mContentRevision++;
        if (startRow < mDirtyStartRow) mDirtyStartRow = startRow;
        if (endRow > mDirtyEndRow) mDirtyEndRow = endRow;
    }

    void beginSynchronizedOutput() {
        if (mSynchronizedOutputSnapshotActive) return;
        if (mSynchronizedOutputRowHashes == null || mSynchronizedOutputRowHashes.length < mScreenRows) {
            mSynchronizedOutputRowHashes = new long[mScreenRows];
        }
        mSynchronizedOutputRows = mScreenRows;
        mSynchronizedOutputColumns = mColumns;
        mDirtyStartBeforeSynchronizedOutput = mDirtyStartRow;
        mDirtyEndBeforeSynchronizedOutput = mDirtyEndRow;
        for (int row = 0; row < mScreenRows; row++) {
            TerminalRow line = allocateFullLineIfNecessary(externalToInternalRow(row));
            mSynchronizedOutputRowHashes[row] = line.getContentHash();
        }
        mSynchronizedOutputSnapshotActive = true;
    }

    void finishSynchronizedOutput() {
        if (!mSynchronizedOutputSnapshotActive) return;
        mSynchronizedOutputSnapshotActive = false;

        clearDirtyRows();
        markDirtyRows(mDirtyStartBeforeSynchronizedOutput, mDirtyEndBeforeSynchronizedOutput);
        if (mScreenRows != mSynchronizedOutputRows || mColumns != mSynchronizedOutputColumns) {
            markAllScreenRowsDirty();
            return;
        }

        for (int row = 0; row < mScreenRows; row++) {
            TerminalRow line = allocateFullLineIfNecessary(externalToInternalRow(row));
            if (line.getContentHash() != mSynchronizedOutputRowHashes[row]) {
                markDirtyRows(row, row + 1);
            }
        }
    }

    void cancelSynchronizedOutputSnapshot() {
        mSynchronizedOutputSnapshotActive = false;
    }

    /**
     * Convert a row value from the public external coordinate system to our internal private coordinate system.
     *
     * <pre>
     * - External coordinate system: -mActiveTranscriptRows to mScreenRows-1, with the screen being 0..mScreenRows-1.
     * - Internal coordinate system: the mScreenRows lines starting at mScreenFirstRow comprise the screen, while the
     *   mActiveTranscriptRows lines ending at mScreenFirstRow-1 form the transcript (as a circular buffer).
     *
     * External ↔ Internal:
     *
     * [ ...                            ]     [ ...                                     ]
     * [ -mActiveTranscriptRows         ]     [ mScreenFirstRow - mActiveTranscriptRows ]
     * [ ...                            ]     [ ...                                     ]
     * [ 0 (visible screen starts here) ]  ↔  [ mScreenFirstRow                         ]
     * [ ...                            ]     [ ...                                     ]
     * [ mScreenRows-1                  ]     [ mScreenFirstRow + mScreenRows-1         ]
     * </pre>
     *
     * @param externalRow a row in the external coordinate system.
     * @return The row corresponding to the input argument in the private coordinate system.
     */
    public int externalToInternalRow(int externalRow) {
        if (externalRow < -mActiveTranscriptRows || externalRow > mScreenRows)
            throw new IllegalArgumentException("extRow=" + externalRow + ", mScreenRows=" + mScreenRows + ", mActiveTranscriptRows=" + mActiveTranscriptRows);
        final int internalRow = mScreenFirstRow + externalRow;
        return (internalRow < 0) ? (mTotalRows + internalRow) : (internalRow % mTotalRows);
    }

    public void setLineWrap(int row) {
        TerminalRow line = mLines[externalToInternalRow(row)];
        if (!line.mLineWrap) {
            line.mLineWrap = true;
            mContentRevision++;
        }
    }

    public boolean getLineWrap(int row) {
        return mLines[externalToInternalRow(row)].mLineWrap;
    }

    public void clearLineWrap(int row) {
        TerminalRow line = mLines[externalToInternalRow(row)];
        if (line.mLineWrap) {
            line.mLineWrap = false;
            mContentRevision++;
        }
    }

    /**
     * Resize the screen which this transcript backs. Currently, this only works if the number of columns does not
     * change or the rows expand (that is, it only works when shrinking the number of rows).
     *
     * @param newColumns The number of columns the screen should have.
     * @param newRows    The number of rows the screen should have.
     * @param cursor     An int[2] containing the (column, row) cursor location.
     */
    public void resize(int newColumns, int newRows, int newTotalRows, int[] cursor, long currentStyle, boolean altScreen) {
        // newRows > mTotalRows should not normally happen since mTotalRows is TRANSCRIPT_ROWS (10000):
        if (newColumns == mColumns && newRows <= mTotalRows) {
            // Fast resize where just the rows changed.
            int shiftDownOfTopRow = mScreenRows - newRows;
            if (shiftDownOfTopRow > 0 && shiftDownOfTopRow < mScreenRows) {
                // Shrinking. Check if we can skip blank rows at bottom below cursor.
                for (int i = mScreenRows - 1; i > 0; i--) {
                    if (cursor[1] >= i) break;
                    int r = externalToInternalRow(i);
                    if (mLines[r] == null || mLines[r].isBlank()) {
                        if (--shiftDownOfTopRow == 0) break;
                    }
                }
            } else if (shiftDownOfTopRow < 0) {
                // Negative shift down = expanding. Only move screen up if there is transcript to show:
                int actualShift = Math.max(shiftDownOfTopRow, -mActiveTranscriptRows);
                if (shiftDownOfTopRow != actualShift) {
                    // The new lines revealed by the resizing are not all from the transcript. Blank the below ones.
                    for (int i = 0; i < actualShift - shiftDownOfTopRow; i++)
                        allocateFullLineIfNecessary((mScreenFirstRow + mScreenRows + i) % mTotalRows).clear(currentStyle);
                    shiftDownOfTopRow = actualShift;
                }
            }
            mScreenFirstRow += shiftDownOfTopRow;
            mScreenFirstRow = (mScreenFirstRow < 0) ? (mScreenFirstRow + mTotalRows) : (mScreenFirstRow % mTotalRows);
            mTotalRows = newTotalRows;
            mActiveTranscriptRows = altScreen ? 0 : Math.max(0, mActiveTranscriptRows + shiftDownOfTopRow);
            cursor[1] -= shiftDownOfTopRow;
            mScreenRows = newRows;
        } else {
            // Copy away old state and update new:
            TerminalRow[] oldLines = mLines;
            mLines = new TerminalRow[newTotalRows];
            for (int i = 0; i < newTotalRows; i++)
                mLines[i] = new TerminalRow(newColumns, currentStyle);

            final int oldActiveTranscriptRows = mActiveTranscriptRows;
            final int oldScreenFirstRow = mScreenFirstRow;
            final int oldScreenRows = mScreenRows;
            final int oldTotalRows = mTotalRows;
            mTotalRows = newTotalRows;
            mScreenRows = newRows;
            mActiveTranscriptRows = mScreenFirstRow = 0;
            mColumns = newColumns;

            int newCursorRow = -1;
            int newCursorColumn = -1;
            int oldCursorRow = cursor[1];
            int oldCursorColumn = cursor[0];
            boolean newCursorPlaced = false;

            int currentOutputExternalRow = 0;
            int currentOutputExternalColumn = 0;

            // Loop over every character in the initial state.
            // Blank lines should be skipped only if at end of transcript (just as is done in the "fast" resize), so we
            // keep track how many blank lines we have skipped if we later on find a non-blank line.
            int skippedBlankLines = 0;
            for (int externalOldRow = -oldActiveTranscriptRows; externalOldRow < oldScreenRows; externalOldRow++) {
                // Do what externalToInternalRow() does but for the old state:
                int internalOldRow = oldScreenFirstRow + externalOldRow;
                internalOldRow = (internalOldRow < 0) ? (oldTotalRows + internalOldRow) : (internalOldRow % oldTotalRows);

                TerminalRow oldLine = oldLines[internalOldRow];
                boolean cursorAtThisRow = externalOldRow == oldCursorRow;
                // The cursor may only be on a non-null line, which we should not skip:
                if (oldLine == null || (!(!newCursorPlaced && cursorAtThisRow)) && oldLine.isBlank()) {
                    skippedBlankLines++;
                    continue;
                } else if (skippedBlankLines > 0) {
                    // After skipping some blank lines we encounter a non-blank line. Insert the skipped blank lines.
                    for (int i = 0; i < skippedBlankLines; i++) {
                        if (currentOutputExternalRow == mScreenRows - 1) {
                            scrollDownOneLine(0, mScreenRows, currentStyle);
                        } else {
                            currentOutputExternalRow++;
                        }
                        currentOutputExternalColumn = 0;
                    }
                    skippedBlankLines = 0;
                }

                int lastNonSpaceIndex = 0;
                boolean justToCursor = false;
                if (cursorAtThisRow || oldLine.mLineWrap) {
                    // Take the whole line, either because of cursor on it, or if line wrapping.
                    lastNonSpaceIndex = oldLine.getSpaceUsed();
                    if (cursorAtThisRow) justToCursor = true;
                } else {
                    for (int i = 0; i < oldLine.getSpaceUsed(); i++)
                        // NEWLY INTRODUCED BUG! Should not index oldLine.mStyle with char indices
                        if (oldLine.mText[i] != ' '/* || oldLine.mStyle[i] != currentStyle */)
                            lastNonSpaceIndex = i + 1;
                }

                int currentOldCol = 0;
                long styleAtCol = 0;
                String hyperlinkAtCol = null;
                for (int i = 0; i < lastNonSpaceIndex; i++) {
                    // Note that looping over java character, not cells.
                    char c = oldLine.mText[i];
                    int codePoint = (Character.isHighSurrogate(c)) ? Character.toCodePoint(c, oldLine.mText[++i]) : c;
                    int displayWidth = WcWidth.width(codePoint);
                    // Use the last style if this is a zero-width character:
                    if (displayWidth > 0) {
                        styleAtCol = oldLine.getStyle(currentOldCol);
                        hyperlinkAtCol = oldLine.getHyperlink(currentOldCol);
                    }

                    // Line wrap as necessary:
                    if (currentOutputExternalColumn + displayWidth > mColumns) {
                        setLineWrap(currentOutputExternalRow);
                        if (currentOutputExternalRow == mScreenRows - 1) {
                            if (newCursorPlaced) newCursorRow--;
                            scrollDownOneLine(0, mScreenRows, currentStyle);
                        } else {
                            currentOutputExternalRow++;
                        }
                        currentOutputExternalColumn = 0;
                    }

                    int offsetDueToCombiningChar = ((displayWidth <= 0 && currentOutputExternalColumn > 0) ? 1 : 0);
                    int outputColumn = currentOutputExternalColumn - offsetDueToCombiningChar;
                    setChar(outputColumn, currentOutputExternalRow, codePoint, styleAtCol, hyperlinkAtCol);

                    if (displayWidth > 0) {
                        if (oldCursorRow == externalOldRow && oldCursorColumn == currentOldCol) {
                            newCursorColumn = currentOutputExternalColumn;
                            newCursorRow = currentOutputExternalRow;
                            newCursorPlaced = true;
                        }
                        currentOldCol += displayWidth;
                        currentOutputExternalColumn += displayWidth;
                        if (justToCursor && newCursorPlaced) break;
                    }
                }
                // Old row has been copied. Check if we need to insert newline if old line was not wrapping:
                if (externalOldRow != (oldScreenRows - 1) && !oldLine.mLineWrap) {
                    if (currentOutputExternalRow == mScreenRows - 1) {
                        if (newCursorPlaced) newCursorRow--;
                        scrollDownOneLine(0, mScreenRows, currentStyle);
                    } else {
                        currentOutputExternalRow++;
                    }
                    currentOutputExternalColumn = 0;
                }
            }

            cursor[0] = newCursorColumn;
            cursor[1] = newCursorRow;
        }

        // Handle cursor scrolling off screen:
        if (cursor[0] < 0 || cursor[1] < 0) cursor[0] = cursor[1] = 0;
        markAllScreenRowsDirty();
    }

    /**
     * Block copy lines and associated metadata from one location to another in the circular buffer, taking wraparound
     * into account.
     *
     * @param srcInternal The first line to be copied.
     * @param len         The number of lines to be copied.
     */
    private void blockCopyLinesDown(int srcInternal, int len) {
        if (len == 0) return;
        int totalRows = mTotalRows;

        int start = len - 1;
        // Save away line to be overwritten:
        TerminalRow lineToBeOverWritten = mLines[(srcInternal + start + 1) % totalRows];
        // Do the copy from bottom to top.
        for (int i = start; i >= 0; --i)
            mLines[(srcInternal + i + 1) % totalRows] = mLines[(srcInternal + i) % totalRows];
        // Put back overwritten line, now above the block:
        mLines[(srcInternal) % totalRows] = lineToBeOverWritten;
    }

    /**
     * Scroll the screen down one line. To scroll the whole screen of a 24 line screen, the arguments would be (0, 24).
     *
     * @param topMargin    First line that is scrolled.
     * @param bottomMargin One line after the last line that is scrolled.
     * @param style        the style for the newly exposed line.
     */
    public void scrollDownOneLine(int topMargin, int bottomMargin, long style) {
        if (topMargin > bottomMargin - 1 || topMargin < 0 || bottomMargin > mScreenRows)
            throw new IllegalArgumentException("topMargin=" + topMargin + ", bottomMargin=" + bottomMargin + ", mScreenRows=" + mScreenRows);

        // Copy the fixed topMargin lines one line down so that they remain on screen in same position:
        blockCopyLinesDown(mScreenFirstRow, topMargin);
        // Copy the fixed mScreenRows-bottomMargin lines one line down so that they remain on screen in same
        // position:
        blockCopyLinesDown(externalToInternalRow(bottomMargin), mScreenRows - bottomMargin);

        // Update the screen location in the ring buffer:
        mScreenFirstRow = (mScreenFirstRow + 1) % mTotalRows;
        // Note that the history has grown if not already full:
        if (mActiveTranscriptRows < mTotalRows - mScreenRows) mActiveTranscriptRows++;

        // Blank the newly revealed line above the bottom margin:
        int blankRow = externalToInternalRow(bottomMargin - 1);
        if (mLines[blankRow] == null) {
            mLines[blankRow] = new TerminalRow(mColumns, style);
        } else {
            mLines[blankRow].clear(style);
        }
        // Clearing a row should never keep a stale wrap flag, otherwise selection/join logic may
        // treat the blank row as a continuation of previous output.
        mLines[blankRow].mLineWrap = false;
        markDirtyRows(topMargin, bottomMargin);
    }

    /**
     * Rotate screen rows in-place by moving {@link TerminalRow} references.
     *
     * <p>This is used as a fast-path for operations like CSI L/M (insert/delete lines) which
     * logically shift whole rows within a region. Callers are expected to clear the vacated rows
     * (e.g. to create blank inserted/deleted lines) after the rotation.
     *
     * @param startRow External start row (inclusive), in [0, mScreenRows).
     * @param regionHeight Number of rows in region. Must be > 0 and within screen bounds.
     * @param shift Positive rotates down (last rows move to front), negative rotates up.
     */
    void rotateScreenRows(int startRow, int regionHeight, int shift) {
        if (regionHeight <= 1 || shift == 0) return;
        if (startRow < 0 || startRow >= mScreenRows || startRow + regionHeight > mScreenRows) {
            throw new IllegalArgumentException("rotateScreenRows(" + startRow + ", " + regionHeight + ", " + shift + "), mScreenRows=" + mScreenRows);
        }

        int k = shift % regionHeight;
        if (k == 0) return;
        if (k < 0) {
            rotateScreenRowsUp(startRow, regionHeight, -k);
        } else {
            rotateScreenRowsDown(startRow, regionHeight, k);
        }
        markDirtyRows(startRow, startRow + regionHeight);
    }

    private TerminalRow[] ensureRowSwapTmp(int size) {
        if (mRowSwapTmp == null || mRowSwapTmp.length < size) {
            mRowSwapTmp = new TerminalRow[size];
        }
        return mRowSwapTmp;
    }

    private void rotateScreenRowsDown(int startRow, int regionHeight, int downBy) {
        // Save bottom segment.
        TerminalRow[] tmp = ensureRowSwapTmp(downBy);
        for (int i = 0; i < downBy; i++) {
            int extRow = startRow + regionHeight - downBy + i;
            tmp[i] = mLines[externalToInternalRow(extRow)];
        }

        // Shift remaining rows down.
        for (int extRow = startRow + regionHeight - downBy - 1; extRow >= startRow; extRow--) {
            int from = externalToInternalRow(extRow);
            int to = externalToInternalRow(extRow + downBy);
            mLines[to] = mLines[from];
        }

        // Restore saved segment to top.
        for (int i = 0; i < downBy; i++) {
            int to = externalToInternalRow(startRow + i);
            mLines[to] = tmp[i];
            tmp[i] = null;
        }
    }

    private void rotateScreenRowsUp(int startRow, int regionHeight, int upBy) {
        // Save top segment.
        TerminalRow[] tmp = ensureRowSwapTmp(upBy);
        for (int i = 0; i < upBy; i++) {
            int extRow = startRow + i;
            tmp[i] = mLines[externalToInternalRow(extRow)];
        }

        // Shift remaining rows up.
        for (int extRow = startRow + upBy; extRow < startRow + regionHeight; extRow++) {
            int from = externalToInternalRow(extRow);
            int to = externalToInternalRow(extRow - upBy);
            mLines[to] = mLines[from];
        }

        // Restore saved segment to bottom.
        for (int i = 0; i < upBy; i++) {
            int to = externalToInternalRow(startRow + regionHeight - upBy + i);
            mLines[to] = tmp[i];
            tmp[i] = null;
        }
    }

    /**
     * Block copy characters from one position in the screen to another. The two positions can overlap. All characters
     * of the source and destination must be within the bounds of the screen, or else an InvalidParameterException will
     * be thrown.
     *
     * @param sx source X coordinate
     * @param sy source Y coordinate
     * @param w  width
     * @param h  height
     * @param dx destination X coordinate
     * @param dy destination Y coordinate
     */
    public void blockCopy(int sx, int sy, int w, int h, int dx, int dy) {
        if (w == 0) return;
        if (sx < 0 || sx + w > mColumns || sy < 0 || sy + h > mScreenRows || dx < 0 || dx + w > mColumns || dy < 0 || dy + h > mScreenRows)
            throw new IllegalArgumentException();
        boolean copyingUp = sy > dy;
        for (int y = 0; y < h; y++) {
            int y2 = copyingUp ? y : (h - (y + 1));
            TerminalRow sourceRow = allocateFullLineIfNecessary(externalToInternalRow(sy + y2));
            allocateFullLineIfNecessary(externalToInternalRow(dy + y2)).copyInterval(sourceRow, sx, sx + w, dx);
        }
        markDirtyRows(Math.min(sy, dy), Math.max(sy + h, dy + h));
    }

    /**
     * Block set characters. All characters must be within the bounds of the screen, or else and
     * InvalidParemeterException will be thrown. Typically this is called with a "val" argument of 32 to clear a block
     * of characters.
     */
    public void blockSet(int sx, int sy, int w, int h, int val, long style) {
        if (sx < 0 || sx + w > mColumns || sy < 0 || sy + h > mScreenRows) {
            throw new IllegalArgumentException(
                "Illegal arguments! blockSet(" + sx + ", " + sy + ", " + w + ", " + h + ", " + val + ", " + mColumns + ", " + mScreenRows + ")");
        }
        markDirtyRows(sy, sy + h);

        // Fast path: full-width clears are extremely common (tmux, full-screen TUIs).
        // Avoid per-cell setChar() overhead when we can clear whole rows at once.
        if (val == ' ' && sx == 0 && w == mColumns) {
            for (int y = 0; y < h; y++) {
                int row = externalToInternalRow(sy + y);
                TerminalRow line = allocateFullLineIfNecessary(row);
                line.clear(style);
                line.mLineWrap = false;
            }
            return;
        }

        // Fast path: partial clears on ASCII rows. This covers common sequences like EL/ED and the
        // blanking step after DCH. We must not take this path if the line may contain non-1-width
        // code points (wide, surrogate pairs, combining), since those require setChar() to handle
        // clearing halves of wide chars correctly.
        if (val == ' ') {
            for (int y = 0; y < h; y++) {
                int row = externalToInternalRow(sy + y);
                TerminalRow line = allocateFullLineIfNecessary(row);
                if (!line.mHasNonOneWidthOrSurrogateChars) {
                    Arrays.fill(line.mText, sx, sx + w, ' ');
                    Arrays.fill(line.mStyle, sx, sx + w, style);
                    line.clearHyperlinks(sx, sx + w);
                } else {
                    for (int x = 0; x < w; x++) {
                        setChar(sx + x, sy + y, val, style);
                    }
                }
            }
            return;
        }

        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                setChar(sx + x, sy + y, val, style);
    }

    public TerminalRow allocateFullLineIfNecessary(int row) {
        return (mLines[row] == null) ? (mLines[row] = new TerminalRow(mColumns, 0)) : mLines[row];
    }

    public void setChar(int column, int row, int codePoint, long style) {
        setChar(column, row, codePoint, style, null);
    }

    void setChar(int column, int row, int codePoint, long style, String hyperlink) {
        if (row  < 0 || row >= mScreenRows || column < 0 || column >= mColumns)
            throw new IllegalArgumentException("TerminalBuffer.setChar(): row=" + row + ", column=" + column + ", mScreenRows=" + mScreenRows + ", mColumns=" + mColumns);
        int internalRow = externalToInternalRow(row);
        TerminalRow line = allocateFullLineIfNecessary(internalRow);
        if (line.hasOnlyOneWidthCharacters() && codePoint < Character.MIN_SUPPLEMENTARY_CODE_POINT &&
            WcWidth.width(codePoint) == 1 && line.mText[column] == (char) codePoint &&
            line.getStyle(column) == style && Objects.equals(line.getHyperlink(column), hyperlink)) {
            return;
        }
        markDirtyRows(row, row + 1);
        line.setChar(column, codePoint, style, hyperlink);
    }

    boolean setAsciiRunIfSimple(int column, int row, byte[] bytes, int offset, int length,
                                long style) {
        return setAsciiRunIfSimple(column, row, bytes, offset, length, style, null);
    }

    boolean setAsciiRunIfSimple(int column, int row, byte[] bytes, int offset, int length,
                                long style, String hyperlink) {
        if (row < 0 || row >= mScreenRows || column < 0 || length < 0 || column + length > mColumns)
            throw new IllegalArgumentException("TerminalBuffer.setAsciiRunIfSimple(): row=" + row + ", column=" + column + ", length=" + length + ", mScreenRows=" + mScreenRows + ", mColumns=" + mColumns);
        if (length == 0) return true;

        TerminalRow line = allocateFullLineIfNecessary(externalToInternalRow(row));
        if (line.mHasNonOneWidthOrSurrogateChars) return false;
        boolean changed = false;
        for (int i = 0; i < length; i++) {
            if (line.mText[column + i] != (char) (bytes[offset + i] & 0x7f) ||
                line.getStyle(column + i) != style ||
                !Objects.equals(line.getHyperlink(column + i), hyperlink)) {
                changed = true;
                break;
            }
        }
        if (!changed) return true;
        line.setAsciiRun(column, bytes, offset, length, style, hyperlink);
        markDirtyRows(row, row + 1);
        return true;
    }

    public long getStyleAt(int externalRow, int column) {
        return allocateFullLineIfNecessary(externalToInternalRow(externalRow)).getStyle(column);
    }

    public String getHyperlinkAt(int externalRow, int column) {
        if (externalRow < -mActiveTranscriptRows || externalRow >= mScreenRows ||
            column < 0 || column >= mColumns) return null;
        return allocateFullLineIfNecessary(externalToInternalRow(externalRow)).getHyperlink(column);
    }

    /** Support for http://vt100.net/docs/vt510-rm/DECCARA and http://vt100.net/docs/vt510-rm/DECCARA */
    public void setOrClearEffect(int bits, boolean setOrClear, boolean reverse, boolean rectangular, int leftMargin, int rightMargin, int top, int left,
                                 int bottom, int right) {
        markDirtyRows(top, bottom);
        for (int y = top; y < bottom; y++) {
            TerminalRow line = mLines[externalToInternalRow(y)];
            int startOfLine = (rectangular || y == top) ? left : leftMargin;
            int endOfLine = (rectangular || y + 1 == bottom) ? right : rightMargin;
            for (int x = startOfLine; x < endOfLine; x++) {
                long currentStyle = line.getStyle(x);
                int foreColor = TextStyle.decodeForeColor(currentStyle);
                int backColor = TextStyle.decodeBackColor(currentStyle);
                int effect = TextStyle.decodeEffect(currentStyle);
                if (reverse) {
                    // Clear out the bits to reverse and add them back in reversed:
                    effect = (effect & ~bits) | (bits & ~effect);
                } else if (setOrClear) {
                    effect |= bits;
                } else {
                    effect &= ~bits;
                }
                line.mStyle[x] = TextStyle.encode(foreColor, backColor, effect);
            }
        }
    }

    public void clearTranscript() {
        if (mScreenFirstRow < mActiveTranscriptRows) {
            Arrays.fill(mLines, mTotalRows + mScreenFirstRow - mActiveTranscriptRows, mTotalRows, null);
            Arrays.fill(mLines, 0, mScreenFirstRow, null);
        } else {
            Arrays.fill(mLines, mScreenFirstRow - mActiveTranscriptRows, mScreenFirstRow, null);
        }
        mActiveTranscriptRows = 0;
    }

}
