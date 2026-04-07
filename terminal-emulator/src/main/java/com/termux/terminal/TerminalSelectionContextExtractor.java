package com.termux.terminal;

/**
 * Builds stable text snapshots around terminal selections so higher layers can run URL
 * classification against a bounded piece of transcript text instead of raw screen coordinates.
 */
public final class TerminalSelectionContextExtractor {

    private TerminalSelectionContextExtractor() {}

    public static TerminalSelectionContext extractSelectionContext(TerminalBuffer screen,
                                                                  int selX1, int selY1,
                                                                  int selX2, int selY2,
                                                                  int paddingRows) {
        if (screen == null) return new TerminalSelectionContext("", 0, 0);

        int startY = selY1;
        int endY = selY2;
        int startX = selX1;
        int endX = selX2;
        if (startY > endY || (startY == endY && startX > endX)) {
            startY = selY2;
            endY = selY1;
            startX = selX2;
            endX = selX1;
        }

        int minRow = -screen.getActiveTranscriptRows();
        int maxRow = screen.mScreenRows - 1;
        startY = clamp(startY, minRow, maxRow);
        endY = clamp(endY, minRow, maxRow);
        startX = clamp(startX, 0, Math.max(0, screen.mColumns - 1));
        endX = clamp(endX, 0, Math.max(0, screen.mColumns - 1));

        int contextStartRow = Math.max(minRow, findLogicalLineStartRow(screen, startY) - Math.max(0, paddingRows));
        int contextEndRow = Math.min(maxRow, findLogicalLineEndRow(screen, endY) + Math.max(0, paddingRows));

        StringBuilder builder = new StringBuilder();
        int selectionStart = -1;
        int selectionEnd = -1;

        for (int row = contextStartRow; row <= contextEndRow; row++) {
            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            int rowStartColumn = 0;
            int rowEndExclusiveColumn = screen.mColumns;

            int rowStartIndex = lineObject.findStartOfColumn(rowStartColumn);
            int rowEndIndex = lineObject.getSpaceUsed();
            char[] line = lineObject.mText;

            int lastPrintingCharIndex = -1;
            boolean rowLineWrap = screen.getLineWrap(row);
            if (rowLineWrap && rowEndExclusiveColumn == screen.mColumns) {
                lastPrintingCharIndex = rowEndIndex - 1;
            } else {
                for (int i = rowStartIndex; i < rowEndIndex; i++) {
                    if (line[i] != ' ') lastPrintingCharIndex = i;
                }
            }

            int appendedLength = Math.max(0, lastPrintingCharIndex - rowStartIndex + 1);
            int rowBuilderStart = builder.length();

            if (row >= startY && row <= endY) {
                int rowSelStartColumn = (row == startY) ? startX : 0;
                int rowSelEndExclusiveColumn = (row == endY) ? Math.min(screen.mColumns, endX + 1) : screen.mColumns;
                int rowSelStartIndex = lineObject.findStartOfColumn(rowSelStartColumn);
                int rowSelEndIndex = (rowSelEndExclusiveColumn < screen.mColumns)
                    ? lineObject.findStartOfColumn(rowSelEndExclusiveColumn)
                    : lineObject.getSpaceUsed();
                if (rowSelEndIndex == rowSelStartIndex && rowSelEndExclusiveColumn < screen.mColumns) {
                    rowSelEndIndex = lineObject.findStartOfColumn(rowSelEndExclusiveColumn + 1);
                }

                int rowRelativeSelectionStart = clamp(rowSelStartIndex - rowStartIndex, 0, appendedLength);
                int rowRelativeSelectionEnd = clamp(rowSelEndIndex - rowStartIndex,
                    rowRelativeSelectionStart, appendedLength);

                if (selectionStart < 0) {
                    selectionStart = rowBuilderStart + rowRelativeSelectionStart;
                }
                selectionEnd = rowBuilderStart + rowRelativeSelectionEnd;
            }

            if (appendedLength > 0) {
                builder.append(line, rowStartIndex, appendedLength);
            }

            boolean lineFillsWidth = appendedLength > 0 && lastPrintingCharIndex == rowEndIndex - 1;
            boolean appendNewLine = ((!rowLineWrap) && (!lineFillsWidth)) && row < contextEndRow && row < screen.mScreenRows - 1;
            if (appendNewLine) {
                builder.append('\n');
                if (row >= startY && row < endY && selectionEnd >= 0) {
                    selectionEnd = builder.length();
                }
            }
        }

        if (selectionStart < 0) selectionStart = 0;
        if (selectionEnd < selectionStart) selectionEnd = selectionStart;

        return new TerminalSelectionContext(builder.toString(), selectionStart, selectionEnd);
    }

    private static int findLogicalLineStartRow(TerminalBuffer screen, int row) {
        int minRow = -screen.getActiveTranscriptRows();
        int current = clamp(row, minRow, screen.mScreenRows - 1);
        while (current > minRow && (screen.getLineWrap(current - 1) || rowFillsScreenWidth(screen, current - 1))) {
            current--;
        }
        return current;
    }

    private static int findLogicalLineEndRow(TerminalBuffer screen, int row) {
        int maxRow = screen.mScreenRows - 1;
        int current = clamp(row, -screen.getActiveTranscriptRows(), maxRow);
        while (current < maxRow && (screen.getLineWrap(current) || rowFillsScreenWidth(screen, current))) {
            current++;
        }
        return current;
    }

    private static boolean rowFillsScreenWidth(TerminalBuffer screen, int row) {
        if (row < -screen.getActiveTranscriptRows() || row >= screen.mScreenRows) return false;
        TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
        int lastColumnIndex = line.findStartOfColumn(screen.mColumns - 1);
        return line.mText[lastColumnIndex] != ' ';
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
