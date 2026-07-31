package com.termux.terminal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Complete row-major libghostty-vt render state returned through one bulk JNI call.
 *
 * <p>The backing direct buffer is private to its terminal backend and is valid until the next
 * snapshot request on that backend. Consumers must decode it synchronously and must not retain the
 * buffer. Each fixed-size cell record points into a trailing UTF-8 grapheme arena.</p>
 */
public final class GhosttyRenderSnapshot {

    public static final int CELL_BOLD = 1;
    public static final int CELL_ITALIC = 1 << 1;
    public static final int CELL_UNDERLINE = 1 << 2;
    public static final int CELL_STRIKETHROUGH = 1 << 3;
    public static final int CELL_FAINT = 1 << 4;
    public static final int CELL_BLINK = 1 << 5;
    public static final int CELL_INVERSE = 1 << 6;
    public static final int CELL_INVISIBLE = 1 << 7;
    public static final int CELL_OVERLINE = 1 << 8;

    public static final int WIDE_NARROW = 0;
    public static final int WIDE_WIDE = 1;
    public static final int WIDE_SPACER_TAIL = 2;
    public static final int WIDE_SPACER_HEAD = 3;

    private static final int ABI_VERSION = 1;
    private static final int CELL_RECORD_BYTES = 24;
    private static final int UNDERLINE_SHIFT = 12;
    private static final int WIDE_SHIFT = 16;

    private final ByteBuffer mBuffer;

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
    public final int bytesUsed;

    GhosttyRenderSnapshot(ByteBuffer buffer, long[] metadata) {
        if (buffer == null || !buffer.isDirect() || metadata == null || metadata.length < 18 ||
            metadata[0] != ABI_VERSION || metadata[2] != CELL_RECORD_BYTES) {
            throw new IllegalArgumentException("Invalid libghostty-vt render packet ABI");
        }
        long used = metadata[1];
        long tableBytes = metadata[15];
        long cellCount = metadata[3] * metadata[4];
        if (metadata[3] <= 0 || metadata[4] <= 0 ||
            cellCount > Integer.MAX_VALUE / CELL_RECORD_BYTES ||
            tableBytes != cellCount * CELL_RECORD_BYTES || used < tableBytes ||
            used > buffer.capacity() || used > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Corrupt libghostty-vt render packet bounds");
        }

        mBuffer = buffer.duplicate().order(ByteOrder.nativeOrder());
        mBuffer.position(0);
        mBuffer.limit((int) used);
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
        renderGeneration = metadata[16];
        ptyBytes = metadata[17];
    }

    public int cellCount() {
        return columns * rows;
    }

    public int foregroundAt(int cellIndex) {
        return mBuffer.getInt(recordOffset(cellIndex));
    }

    public int backgroundAt(int cellIndex) {
        return mBuffer.getInt(recordOffset(cellIndex) + 4);
    }

    public int underlineColorAt(int cellIndex) {
        return mBuffer.getInt(recordOffset(cellIndex) + 8);
    }

    public int flagsAt(int cellIndex) {
        return mBuffer.getInt(recordOffset(cellIndex) + 12);
    }

    public int underlineStyleAt(int cellIndex) {
        return (flagsAt(cellIndex) >>> UNDERLINE_SHIFT) & 0x0f;
    }

    public int wideAt(int cellIndex) {
        return (flagsAt(cellIndex) >>> WIDE_SHIFT) & 0x03;
    }

    public int textOffsetAt(int cellIndex) {
        return mBuffer.getInt(recordOffset(cellIndex) + 16);
    }

    public int textLengthAt(int cellIndex) {
        return mBuffer.getInt(recordOffset(cellIndex) + 20);
    }

    public String decodeUtf8(int offset, int length) {
        if (length <= 0) return "";
        if (offset < columns * rows * CELL_RECORD_BYTES || offset > bytesUsed - length) {
            throw new IllegalArgumentException("Corrupt libghostty-vt grapheme range");
        }
        byte[] value = new byte[length];
        ByteBuffer source = mBuffer.duplicate();
        source.position(offset);
        source.get(value);
        return new String(value, StandardCharsets.UTF_8);
    }

    GhosttyRenderSnapshot immutableCopy() {
        ByteBuffer copy = ByteBuffer.allocateDirect(bytesUsed).order(ByteOrder.nativeOrder());
        ByteBuffer source = mBuffer.duplicate();
        source.position(0);
        source.limit(bytesUsed);
        copy.put(source);
        copy.flip();
        long[] metadata = new long[18];
        metadata[0] = ABI_VERSION;
        metadata[1] = bytesUsed;
        metadata[2] = CELL_RECORD_BYTES;
        metadata[3] = columns;
        metadata[4] = rows;
        metadata[5] = backgroundColor & 0xffffffffL;
        metadata[6] = foregroundColor & 0xffffffffL;
        metadata[7] = cursorColor & 0xffffffffL;
        metadata[8] = cursorColumn;
        metadata[9] = cursorRow;
        metadata[10] = cursorVisible ? 1L : 0L;
        metadata[11] = cursorStyle;
        metadata[12] = cursorWideTail ? 1L : 0L;
        metadata[13] = dirtyState;
        metadata[14] = scrollbackRows;
        metadata[15] = (long) columns * rows * CELL_RECORD_BYTES;
        metadata[16] = renderGeneration;
        metadata[17] = ptyBytes;
        return new GhosttyRenderSnapshot(copy, metadata);
    }

    private int recordOffset(int cellIndex) {
        if (cellIndex < 0 || cellIndex >= cellCount()) {
            throw new IndexOutOfBoundsException("cellIndex=" + cellIndex);
        }
        return cellIndex * CELL_RECORD_BYTES;
    }
}
