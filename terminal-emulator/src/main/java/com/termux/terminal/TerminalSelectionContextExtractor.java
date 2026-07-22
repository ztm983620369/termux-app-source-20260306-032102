package com.termux.terminal;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;

/**
 * Builds stable text snapshots around terminal selections so the link resolver can preserve
 * selection coordinates while scanning transcript text.
 */
public final class TerminalSelectionContextExtractor {

    private static final int MAX_STRUCTURED_LINK_ROWS = 256;
    private static final int MAX_COLUMN_DRIFT = 8;
    private static final int MAX_SEMANTIC_LINK_TARGETS = 32;

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
        contextStartRow = findLogicalLineStartRow(screen, contextStartRow);
        contextEndRow = findLogicalLineEndRow(screen, contextEndRow);

        StringBuilder builder = new StringBuilder();
        ArrayList<Integer> hardWrapHints = new ArrayList<>();
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

            boolean appendNewLine = !rowLineWrap && row < contextEndRow && row < screen.mScreenRows - 1;
            if (appendNewLine) {
                if (rowFillsScreenWidth(screen, row)) hardWrapHints.add(builder.length());
                builder.append('\n');
                if (row >= startY && row < endY && selectionEnd >= 0) {
                    selectionEnd = builder.length();
                }
            }
        }

        if (selectionStart < 0) selectionStart = 0;
        if (selectionEnd < selectionStart) selectionEnd = selectionStart;

        return new TerminalSelectionContext(
            builder.toString(), selectionStart, selectionEnd, toIntArray(hardWrapHints));
    }

    public static TerminalSelectionContext extractTranscriptContext(TerminalBuffer screen) {
        if (screen == null) return new TerminalSelectionContext("", 0, 0);
        return extractSelectionContext(
            screen,
            0, -screen.getActiveTranscriptRows(),
            Math.max(0, screen.mColumns - 1), screen.mScreenRows - 1,
            0);
    }

    /** Returns validated OSC 8 destinations attached to cells overlapping the active selection. */
    public static LinkedHashSet<String> extractSemanticLinkTargets(TerminalBuffer screen,
                                                                   int selX1, int selY1,
                                                                   int selX2, int selY2) {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        if (screen == null || screen.mColumns <= 0 || screen.mScreenRows <= 0) return targets;

        int minRow = -screen.getActiveTranscriptRows();
        int maxRow = screen.mScreenRows - 1;
        int startY = clamp(Math.min(selY1, selY2), minRow, maxRow);
        int endY = clamp(Math.max(selY1, selY2), minRow, maxRow);
        int startX = selY1 <= selY2 ? selX1 : selX2;
        int endX = selY1 <= selY2 ? selX2 : selX1;
        if (startY == endY && startX > endX) {
            int swap = startX;
            startX = endX;
            endX = swap;
        }
        startX = clamp(startX, 0, screen.mColumns - 1);
        endX = clamp(endX, 0, screen.mColumns - 1);

        for (int row = startY; row <= endY; row++) {
            int from = row == startY ? startX : 0;
            int to = row == endY ? endX : screen.mColumns - 1;
            for (int column = from; column <= to; column++) {
                String target = screen.getHyperlinkAt(row, column);
                String normalized = TerminalLinkResolver.normalizeSemanticUrl(target);
                if (normalized != null) targets.add(normalized);
                if (targets.size() >= MAX_SEMANTIC_LINK_TARGETS) return targets;
            }
        }
        return targets;
    }

    /**
     * Reconstructs a URL rendered as one underlined style run per row, such as a wrapped link in a
     * TUI table cell. Text in sibling columns is deliberately excluded from this context.
     */
    @Nullable
    public static TerminalSelectionContext extractStyledLinkContext(TerminalBuffer screen,
                                                                    int selX1, int selY1,
                                                                    int selX2, int selY2) {
        if (screen == null || screen.mColumns <= 0 || screen.mScreenRows <= 0) return null;

        int minRow = -screen.getActiveTranscriptRows();
        int maxRow = screen.mScreenRows - 1;
        int startY = clamp(Math.min(selY1, selY2), minRow, maxRow);
        int endY = clamp(Math.max(selY1, selY2), minRow, maxRow);
        int startX = selY1 <= selY2 ? selX1 : selX2;
        int endX = selY1 <= selY2 ? selX2 : selX1;
        if (startY == endY && startX > endX) {
            int swap = startX;
            startX = endX;
            endX = swap;
        }
        startX = clamp(startX, 0, screen.mColumns - 1);
        endX = clamp(endX, 0, screen.mColumns - 1);

        StyledRun selectedRun = findSelectedStyledRun(
            screen, startX, startY, endX, endY);
        if (selectedRun == null) return null;

        ArrayList<StyledRun> runs = new ArrayList<>();
        runs.add(selectedRun);
        int rows = 1;
        boolean foundScheme = startsWithScheme(selectedRun.text);

        for (int row = selectedRun.row - 1;
             !foundScheme && row >= minRow && rows < MAX_STRUCTURED_LINK_ROWS;
             row--) {
            StyledRun previous = findMatchingStyledRun(
                screen, row, selectedRun.startColumn, selectedRun.style);
            if (previous == null) break;
            runs.add(previous);
            rows++;
            foundScheme = startsWithScheme(previous.text);
        }
        Collections.sort(runs, (first, second) -> Integer.compare(first.row, second.row));

        StyledRun last = runs.get(runs.size() - 1);
        for (int row = last.row + 1; row <= maxRow && rows < MAX_STRUCTURED_LINK_ROWS; row++) {
            StyledRun next = findMatchingStyledRun(
                screen, row, selectedRun.startColumn, selectedRun.style);
            if (next == null || startsWithScheme(next.text)) break;
            runs.add(next);
            rows++;
        }

        if (runs.size() < 2) return null;
        StringBuilder text = new StringBuilder();
        for (StyledRun run : runs) {
            if (text.length() + run.text.length() > TerminalLinkResolver.DEFAULT_MAX_URL_LENGTH) return null;
            text.append(run.text);
        }
        if (text.length() == 0) return null;
        return new TerminalSelectionContext(
            text.toString(), 0, text.length(), new int[0], true);
    }

    /**
     * Reconstructs a possible URL from vertically aligned whitespace-delimited runs. Unlike the
     * styled variant this is only geometric evidence, so callers must require confirmation.
     */
    @Nullable
    public static TerminalSelectionContext extractColumnLinkContext(TerminalBuffer screen,
                                                                    int selX1, int selY1,
                                                                    int selX2, int selY2) {
        if (screen == null || screen.mColumns <= 0 || screen.mScreenRows <= 0) return null;

        int minRow = -screen.getActiveTranscriptRows();
        int maxRow = screen.mScreenRows - 1;
        int startY = clamp(Math.min(selY1, selY2), minRow, maxRow);
        int endY = clamp(Math.max(selY1, selY2), minRow, maxRow);
        int startX = selY1 <= selY2 ? selX1 : selX2;
        int endX = selY1 <= selY2 ? selX2 : selX1;
        if (startY == endY && startX > endX) {
            int swap = startX;
            startX = endX;
            endX = swap;
        }
        startX = clamp(startX, 0, screen.mColumns - 1);
        endX = clamp(endX, 0, screen.mColumns - 1);

        ColumnRun selectedRun = findSelectedColumnRun(
            screen, startX, startY, endX, endY);
        if (selectedRun == null) return null;

        ArrayList<ColumnRun> runs = new ArrayList<>();
        runs.add(selectedRun);
        int rows = 1;
        boolean foundScheme = startsWithScheme(selectedRun.text);
        boolean hasLinkEvidence = looksLikeColumnLinkFragment(selectedRun.text);

        for (int row = selectedRun.row - 1;
             !foundScheme && row >= minRow && rows < MAX_STRUCTURED_LINK_ROWS;
             row--) {
            ColumnRun previous = findMatchingColumnRun(screen, row, selectedRun.startColumn);
            if (previous == null || isColumnBoundary(previous.text)) break;
            runs.add(previous);
            rows++;
            foundScheme = startsWithScheme(previous.text);
            hasLinkEvidence |= looksLikeColumnLinkFragment(previous.text);
        }
        Collections.sort(runs, (first, second) -> Integer.compare(first.row, second.row));

        ColumnRun last = runs.get(runs.size() - 1);
        for (int row = last.row + 1; row <= maxRow && rows < MAX_STRUCTURED_LINK_ROWS; row++) {
            ColumnRun next = findMatchingColumnRun(screen, row, selectedRun.startColumn);
            if (next == null || startsWithScheme(next.text) || isColumnBoundary(next.text)) break;
            runs.add(next);
            rows++;
            hasLinkEvidence |= looksLikeColumnLinkFragment(next.text);
        }

        if (runs.size() < 2 || !hasLinkEvidence) return null;
        StringBuilder text = new StringBuilder();
        for (ColumnRun run : runs) {
            if (text.length() + run.text.length() > TerminalLinkResolver.DEFAULT_MAX_URL_LENGTH) return null;
            text.append(run.text);
        }
        if (text.length() == 0) return null;
        return new TerminalSelectionContext(
            text.toString(), 0, text.length(), new int[0], true);
    }

    @Nullable
    private static StyledRun findSelectedStyledRun(TerminalBuffer screen,
                                                   int startX, int startY,
                                                   int endX, int endY) {
        for (int row = startY; row <= endY; row++) {
            int from = row == startY ? startX : 0;
            int to = row == endY ? endX : screen.mColumns - 1;
            for (int column = from; column <= to; column++) {
                TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
                long style = effectiveStyleAt(line, column);
                if (isUnderlined(style) && isNonBlankCell(line, column)) {
                    return styledRunAt(screen, row, column, style);
                }
            }
        }
        return null;
    }

    @Nullable
    private static ColumnRun findSelectedColumnRun(TerminalBuffer screen,
                                                   int startX, int startY,
                                                   int endX, int endY) {
        for (int row = startY; row <= endY; row++) {
            int from = row == startY ? startX : 0;
            int to = row == endY ? endX : screen.mColumns - 1;
            TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            for (int column = from; column <= to; column++) {
                if (isNonBlankCell(line, column)) return columnRunAt(screen, row, column);
            }
        }
        return null;
    }

    @Nullable
    private static ColumnRun findMatchingColumnRun(TerminalBuffer screen, int row, int anchorColumn) {
        for (int distance = 0; distance <= MAX_COLUMN_DRIFT; distance++) {
            for (int direction : distance == 0 ? new int[]{0} : new int[]{-1, 1}) {
                int column = anchorColumn + distance * direction;
                if (column < 0 || column >= screen.mColumns) continue;
                ColumnRun run = columnRunAt(screen, row, column);
                if (run != null && Math.abs(run.startColumn - anchorColumn) <= MAX_COLUMN_DRIFT) return run;
            }
        }
        return null;
    }

    @Nullable
    private static StyledRun findMatchingStyledRun(TerminalBuffer screen,
                                                   int row, int anchorColumn, long style) {
        for (int distance = 0; distance <= MAX_COLUMN_DRIFT; distance++) {
            for (int direction : distance == 0 ? new int[]{0} : new int[]{-1, 1}) {
                int column = anchorColumn + distance * direction;
                if (column < 0 || column >= screen.mColumns) continue;
                TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
                if (effectiveStyleAt(line, column) != style || !isNonBlankCell(line, column)) continue;
                StyledRun run = styledRunAt(screen, row, column, style);
                if (run != null && Math.abs(run.startColumn - anchorColumn) <= MAX_COLUMN_DRIFT) return run;
            }
        }
        return null;
    }

    @Nullable
    private static ColumnRun columnRunAt(TerminalBuffer screen, int row, int column) {
        TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
        if (!isNonBlankCell(line, column)) return null;

        int start = column;
        while (start > 0 && isNonBlankCell(line, start - 1)) start--;
        int end = column + 1;
        while (end < screen.mColumns && isNonBlankCell(line, end)) end++;

        int startIndex = line.findStartOfColumn(start);
        int endIndex = end < screen.mColumns ? line.findStartOfColumn(end) : line.getSpaceUsed();
        if (endIndex <= startIndex) return null;
        String text = new String(line.mText, startIndex, endIndex - startIndex);

        int leadingWrappers = 0;
        while (leadingWrappers < text.length() && isOpeningWrapper(text.charAt(leadingWrappers))) {
            leadingWrappers++;
        }
        if (leadingWrappers > 0) {
            text = text.substring(leadingWrappers);
            start = Math.min(end, start + leadingWrappers);
        }
        return text.isEmpty() ? null : new ColumnRun(row, start, text);
    }

    @Nullable
    private static StyledRun styledRunAt(TerminalBuffer screen, int row, int column, long style) {
        TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
        if (effectiveStyleAt(line, column) != style || !isNonBlankCell(line, column)) return null;

        int start = column;
        while (start > 0 && effectiveStyleAt(line, start - 1) == style && isNonBlankCell(line, start - 1)) start--;
        int end = column + 1;
        while (end < screen.mColumns && effectiveStyleAt(line, end) == style && isNonBlankCell(line, end)) end++;

        int startIndex = line.findStartOfColumn(start);
        int endIndex = end < screen.mColumns ? line.findStartOfColumn(end) : line.getSpaceUsed();
        if (endIndex <= startIndex) return null;
        String text = new String(line.mText, startIndex, endIndex - startIndex);
        return text.isEmpty() ? null : new StyledRun(row, start, style, text);
    }

    private static boolean isNonBlankCell(TerminalRow line, int column) {
        int start = line.findStartOfColumn(column);
        int end = column + 1 < line.mStyle.length
            ? line.findStartOfColumn(column + 1)
            : line.getSpaceUsed();
        if (end <= start) return column > 0 && isNonBlankCell(line, column - 1);
        for (int index = start; index < end; index++) {
            if (!Character.isWhitespace(line.mText[index])) return true;
        }
        return false;
    }

    private static long effectiveStyleAt(TerminalRow line, int column) {
        int logicalStart = column;
        while (logicalStart > 0 &&
            line.findStartOfColumn(logicalStart) == line.findStartOfColumn(logicalStart - 1)) {
            logicalStart--;
        }
        return line.getStyle(logicalStart);
    }

    private static boolean isUnderlined(long style) {
        return (TextStyle.decodeEffect(style) & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
    }

    private static boolean startsWithScheme(String value) {
        if (value.isEmpty() || !isAsciiLetter(value.charAt(0))) return false;
        int cursor = 1;
        while (cursor < value.length() && cursor <= 32) {
            char ch = value.charAt(cursor);
            if (ch == ':') return true;
            if (!(isAsciiLetter(ch) || Character.isDigit(ch) || ch == '+' || ch == '-' || ch == '.')) {
                return false;
            }
            cursor++;
        }
        return false;
    }

    private static boolean isOpeningWrapper(char ch) {
        return ch == '(' || ch == '[' || ch == '{' || ch == '<' || ch == '\'' || ch == '"' || ch == '`';
    }

    private static boolean isColumnBoundary(String text) {
        if (text.isEmpty()) return true;
        char first = text.charAt(0);
        if ((first >= '\u2500' && first <= '\u257f') || first == '\u2022' || first == '\u25aa' ||
            first == '\u25e6' || first == '\u2192') return true;
        boolean hasLetterOrDigit = false;
        for (int index = 0; index < text.length(); index++) {
            if (Character.isLetterOrDigit(text.charAt(index))) {
                hasLetterOrDigit = true;
                break;
            }
        }
        return !hasLetterOrDigit;
    }

    private static boolean looksLikeColumnLinkFragment(String text) {
        if (startsWithScheme(text)) return true;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '/' || ch == '?' || ch == '#' || ch == '&' || ch == '=' || ch == '%' ||
                ch == '.' || ch == ':' || ch == '@') return true;
        }
        return false;
    }

    private static boolean isAsciiLetter(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }

    private static final class StyledRun {
        final int row;
        final int startColumn;
        final long style;
        final String text;

        StyledRun(int row, int startColumn, long style, String text) {
            this.row = row;
            this.startColumn = startColumn;
            this.style = style;
            this.text = text;
        }
    }

    private static final class ColumnRun {
        final int row;
        final int startColumn;
        final String text;

        ColumnRun(int row, int startColumn, String text) {
            this.row = row;
            this.startColumn = startColumn;
            this.text = text;
        }
    }

    private static int findLogicalLineStartRow(TerminalBuffer screen, int row) {
        int minRow = -screen.getActiveTranscriptRows();
        int current = clamp(row, minRow, screen.mScreenRows - 1);
        while (current > minRow && screen.getLineWrap(current - 1)) {
            current--;
        }
        return current;
    }

    private static int findLogicalLineEndRow(TerminalBuffer screen, int row) {
        int maxRow = screen.mScreenRows - 1;
        int current = clamp(row, -screen.getActiveTranscriptRows(), maxRow);
        while (current < maxRow && screen.getLineWrap(current)) {
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

    private static int[] toIntArray(ArrayList<Integer> values) {
        int[] result = new int[values.size()];
        for (int index = 0; index < values.size(); index++) result[index] = values.get(index);
        return result;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
