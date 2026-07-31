package com.termux.terminal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Incremental libghostty-vt viewport packet.
 *
 * <p>The packet is valid only during the synchronous decoder callback. A row directory at the
 * beginning of the direct buffer contains an absolute payload offset for every changed row and zero
 * for retained rows. Each changed row owns a fixed-size cell table and a trailing UTF-8 arena.</p>
 */
public final class GhosttyRenderDelta {

    public static final int CELL_BOLD = GhosttyRenderSnapshot.CELL_BOLD;
    public static final int CELL_ITALIC = GhosttyRenderSnapshot.CELL_ITALIC;
    public static final int CELL_UNDERLINE = GhosttyRenderSnapshot.CELL_UNDERLINE;
    public static final int CELL_STRIKETHROUGH = GhosttyRenderSnapshot.CELL_STRIKETHROUGH;
    public static final int CELL_FAINT = GhosttyRenderSnapshot.CELL_FAINT;
    public static final int CELL_BLINK = GhosttyRenderSnapshot.CELL_BLINK;
    public static final int CELL_INVERSE = GhosttyRenderSnapshot.CELL_INVERSE;
    public static final int CELL_INVISIBLE = GhosttyRenderSnapshot.CELL_INVISIBLE;
    public static final int CELL_OVERLINE = GhosttyRenderSnapshot.CELL_OVERLINE;

    public static final int WIDE_NARROW = GhosttyRenderSnapshot.WIDE_NARROW;
    public static final int WIDE_WIDE = GhosttyRenderSnapshot.WIDE_WIDE;
    public static final int WIDE_SPACER_TAIL = GhosttyRenderSnapshot.WIDE_SPACER_TAIL;
    public static final int WIDE_SPACER_HEAD = GhosttyRenderSnapshot.WIDE_SPACER_HEAD;

    private static final int ABI_VERSION = 3;
    private static final int CELL_RECORD_BYTES = 24;
    public static final int CELL_RECORD_INTS = CELL_RECORD_BYTES / Integer.BYTES;
    private static final int UNDERLINE_SHIFT = 12;
    private static final int WIDE_SHIFT = 16;

    private final ByteBuffer mBuffer;
    private final IntBuffer mIntBuffer;
    private final int mDirectoryBytes;
    private final int mRowTableBytes;

    public final int columns;
    public final int rows;
    public final int backgroundColor;
    public final int foregroundColor;
    public final int cursorColor;
    public final int cursorColumn;
    public final int cursorRow;
    public final boolean cursorVisible;
    public final int cursorStyle;
    public final boolean cursorWideTail;
    public final int dirtyState;
    public final long scrollbackRows;
    public final long renderGeneration;
    public final long ptyBytes;
    public final int changedRowCount;
    public final boolean fullFrame;
    public final int topRow;
    public final long stateGeneration;
    /** Rows examined by native exact-content suppression for this packet. */
    public final int semanticCandidateRows;
    /** Exactly equal rows omitted before the JNI packet crossed into Java. */
    public final int semanticSuppressedRows;
    public final long semanticSuppressedRowsTotal;
    public final long semanticPacketsTotal;
    public final int bytesUsed;

    GhosttyRenderDelta(ByteBuffer buffer, long[] metadata) {
        if (buffer == null || !buffer.isDirect() || metadata == null || metadata.length < 26 ||
            metadata[0] != ABI_VERSION || metadata[2] != CELL_RECORD_BYTES) {
            throw new IllegalArgumentException("Invalid libghostty-vt render delta ABI");
        }

        long used = metadata[1];
        long directoryBytes = metadata[15];
        long rowTableBytes = metadata[3] * CELL_RECORD_BYTES;
        if (metadata[3] <= 0 || metadata[4] <= 0 ||
            metadata[3] > Integer.MAX_VALUE / CELL_RECORD_BYTES ||
            directoryBytes != metadata[4] * Integer.BYTES ||
            directoryBytes > Integer.MAX_VALUE || rowTableBytes > Integer.MAX_VALUE ||
            used < directoryBytes || used > buffer.capacity() || used > Integer.MAX_VALUE ||
            metadata[18] < 0 || metadata[18] > metadata[4] ||
            (metadata[19] != 0 && metadata[18] != metadata[4])) {
            throw new IllegalArgumentException("Corrupt libghostty-vt render delta bounds");
        }

        mBuffer = buffer.duplicate().order(ByteOrder.nativeOrder());
        mBuffer.position(0);
        mBuffer.limit((int) used);
        mIntBuffer = mBuffer.asIntBuffer();
        bytesUsed = (int) used;
        columns = (int) metadata[3];
        rows = (int) metadata[4];
        backgroundColor = (int) metadata[5];
        foregroundColor = (int) metadata[6];
        cursorColor = (int) metadata[7];
        cursorColumn = (int) metadata[8];
        cursorRow = (int) metadata[9];
        cursorVisible = metadata[10] != 0L;
        cursorStyle = (int) metadata[11];
        cursorWideTail = metadata[12] != 0L;
        dirtyState = (int) metadata[13];
        scrollbackRows = metadata[14];
        mDirectoryBytes = (int) directoryBytes;
        mRowTableBytes = (int) rowTableBytes;
        renderGeneration = metadata[16];
        ptyBytes = metadata[17];
        changedRowCount = (int) metadata[18];
        fullFrame = metadata[19] != 0L;
        topRow = (int) metadata[20];
        stateGeneration = metadata[21];
        semanticCandidateRows = (int) metadata[22];
        semanticSuppressedRows = (int) metadata[23];
        semanticSuppressedRowsTotal = metadata[24];
        semanticPacketsTotal = metadata[25];
        if (semanticCandidateRows < 0 || semanticCandidateRows > rows ||
            semanticSuppressedRows < 0 || semanticSuppressedRows > semanticCandidateRows ||
            semanticSuppressedRowsTotal < semanticSuppressedRows || semanticPacketsTotal < 0) {
            throw new IllegalArgumentException("Corrupt libghostty-vt semantic delta metadata");
        }

        int presentRows = 0;
        int previousPayload = mDirectoryBytes;
        for (int row = 0; row < rows; row++) {
            int payload = mBuffer.getInt(row * Integer.BYTES);
            if (payload == 0) continue;
            if (payload < mDirectoryBytes || (payload & (Integer.BYTES - 1)) != 0 ||
                payload < previousPayload ||
                payload > bytesUsed - mRowTableBytes) {
                throw new IllegalArgumentException("Corrupt libghostty-vt row payload bounds");
            }
            previousPayload = payload;
            presentRows++;
        }
        if (presentRows != changedRowCount) {
            throw new IllegalArgumentException("Corrupt libghostty-vt changed row count");
        }
    }

    public boolean hasRow(int row) {
        checkRow(row);
        return mBuffer.getInt(row * Integer.BYTES) != 0;
    }

    public int foregroundAt(int row, int column) {
        return mBuffer.getInt(recordOffset(row, column));
    }

    public int backgroundAt(int row, int column) {
        return mBuffer.getInt(recordOffset(row, column) + 4);
    }

    public int underlineColorAt(int row, int column) {
        return mBuffer.getInt(recordOffset(row, column) + 8);
    }

    public int flagsAt(int row, int column) {
        return mBuffer.getInt(recordOffset(row, column) + 12);
    }

    public int underlineStyleAt(int row, int column) {
        return (flagsAt(row, column) >>> UNDERLINE_SHIFT) & 0x0f;
    }

    public int wideAt(int row, int column) {
        return (flagsAt(row, column) >>> WIDE_SHIFT) & 0x03;
    }

    public int textOffsetAt(int row, int column) {
        return mBuffer.getInt(recordOffset(row, column) + 16);
    }

    public int textLengthAt(int row, int column) {
        return mBuffer.getInt(recordOffset(row, column) + 20);
    }

    /**
     * Copy one changed row's fixed-width cell table into caller-owned reusable arrays.
     *
     * <p>The render consumer visits every cell in a changed row. Resolving and validating the
     * row payload once avoids repeating the same directory and bounds work for each of the six
     * fields while preserving the packet's synchronous zero-copy lifetime.</p>
     */
    public void copyRowCells(int row,
                             int[] foreground, int[] background, int[] underline,
                             int[] flags, int[] textOffsets, int[] textLengths) {
        copyRowCellsAndGetUtf8Length(row, foreground, background, underline, flags,
            textOffsets, textLengths);
    }

    /**
     * Copy one changed row and return the length of its contiguous trailing UTF-8 arena.
     *
     * <p>The native ABI writes every grapheme consecutively after the fixed cell table. Folding
     * the byte count into the mandatory cell-table copy removes a second columns-wide scan from
     * every dirty row without weakening packet bounds checks or allocating a range object.</p>
     */
    public int copyRowCellsAndGetUtf8Length(int row,
                                            int[] foreground, int[] background, int[] underline,
                                            int[] flags, int[] textOffsets, int[] textLengths) {
        requireCellArray(foreground, "foreground");
        requireCellArray(background, "background");
        requireCellArray(underline, "underline");
        requireCellArray(flags, "flags");
        requireCellArray(textOffsets, "textOffsets");
        requireCellArray(textLengths, "textLengths");

        int record = rowPayloadOffset(row);
        int utf8Length = 0;
        for (int column = 0; column < columns; column++, record += CELL_RECORD_BYTES) {
            foreground[column] = mBuffer.getInt(record);
            background[column] = mBuffer.getInt(record + 4);
            underline[column] = mBuffer.getInt(record + 8);
            flags[column] = mBuffer.getInt(record + 12);
            textOffsets[column] = mBuffer.getInt(record + 16);
            int textLength = mBuffer.getInt(record + 20);
            if (textLength < 0 || utf8Length > Integer.MAX_VALUE - textLength) {
                throw new IllegalArgumentException("Corrupt libghostty-vt row grapheme length");
            }
            textLengths[column] = textLength;
            utf8Length += textLength;
        }
        return utf8Length;
    }

    /**
     * Bulk-copy one changed row's interleaved native cell records.
     *
     * <p>This is the production renderer fast path. One direct-buffer bulk transfer replaces six
     * scalar {@code getInt()} calls per cell while preserving the exact ABI fields and callback
     * lifetime. Record order is foreground, background, underline, flags, UTF-8 offset, UTF-8
     * length.</p>
     */
    public int copyRowRecordsAndGetUtf8Length(int row, int[] records) {
        int required = columns * CELL_RECORD_INTS;
        if (records == null || records.length < required) {
            throw new IllegalArgumentException("records length=" +
                (records == null ? -1 : records.length) + " required=" + required);
        }
        int payload = rowPayloadOffset(row);
        if ((payload & (Integer.BYTES - 1)) != 0) {
            throw new IllegalArgumentException("Unaligned libghostty-vt row payload");
        }
        int previousPosition = mIntBuffer.position();
        try {
            mIntBuffer.position(payload / Integer.BYTES);
            mIntBuffer.get(records, 0, required);
        } finally {
            mIntBuffer.position(previousPosition);
        }
        int utf8Length = 0;
        for (int record = CELL_RECORD_INTS - 1; record < required;
             record += CELL_RECORD_INTS) {
            int textLength = records[record];
            if (textLength < 0 || utf8Length > Integer.MAX_VALUE - textLength) {
                throw new IllegalArgumentException("Corrupt libghostty-vt row grapheme length");
            }
            utf8Length += textLength;
        }
        return utf8Length;
    }

    /** Absolute start of a changed row's contiguous trailing UTF-8 arena. */
    public int rowUtf8Offset(int row) {
        return rowPayloadOffset(row) + mRowTableBytes;
    }

    public String decodeUtf8(int row, int offset, int length) {
        if (length <= 0) return "";
        byte[] value = new byte[length];
        copyUtf8Range(row, offset, length, value);
        return new String(value, StandardCharsets.UTF_8);
    }

    /** Copy a validated row-arena range into reusable caller storage without allocating. */
    public void copyUtf8Range(int row, int offset, int length, byte[] destination) {
        int payload = rowPayloadOffset(row);
        int rowEnd = rowPayloadEnd(row);
        if (length < 0 || destination == null || destination.length < length ||
            offset < payload + mRowTableBytes || offset > rowEnd - length) {
            throw new IllegalArgumentException("Corrupt libghostty-vt row grapheme range");
        }
        if (length == 0) return;
        // The delta object is callback-scoped and consumed synchronously. Save/restore its private
        // position to use the API-1 bulk read without allocating one duplicate per dirty row.
        int previousPosition = mBuffer.position();
        try {
            mBuffer.position(offset);
            mBuffer.get(destination, 0, length);
        } finally {
            mBuffer.position(previousPosition);
        }
    }

    private int recordOffset(int row, int column) {
        if (column < 0 || column >= columns) {
            throw new IndexOutOfBoundsException("column=" + column);
        }
        int payload = rowPayloadOffset(row);
        int offset = payload + column * CELL_RECORD_BYTES;
        if (offset < payload || offset > bytesUsed - CELL_RECORD_BYTES) {
            throw new IllegalArgumentException("Corrupt libghostty-vt cell record bounds");
        }
        return offset;
    }

    private int rowPayloadOffset(int row) {
        checkRow(row);
        int payload = mBuffer.getInt(row * Integer.BYTES);
        if (payload == 0) throw new IllegalStateException("Render row is retained: " + row);
        return payload;
    }

    private int rowPayloadEnd(int row) {
        // The directory is ordered by payload offset. Binary search keeps sparse dirty packets
        // from repeatedly walking all retained rows when validating a changed row's arena.
        int low = row + 1;
        int high = rows;
        while (low < high) {
            int middle = low + ((high - low) >>> 1);
            if (mBuffer.getInt(middle * Integer.BYTES) == 0) low = middle + 1;
            else high = middle;
        }
        if (low < rows) return mBuffer.getInt(low * Integer.BYTES);
        return bytesUsed;
    }

    private void checkRow(int row) {
        if (row < 0 || row >= rows) throw new IndexOutOfBoundsException("row=" + row);
    }

    private void requireCellArray(int[] values, String name) {
        if (values == null || values.length < columns) {
            throw new IllegalArgumentException(name + " length=" +
                (values == null ? -1 : values.length) + " columns=" + columns);
        }
    }
}
