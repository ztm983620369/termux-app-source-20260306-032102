package com.termux.terminal;

import junit.framework.TestCase;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class GhosttyRenderDeltaTest extends TestCase {

    private static final int CELL_RECORD_BYTES = 24;
    private static final int COLUMNS = 2;
    private static final int ROWS = 2;
    private static final int DIRECTORY_BYTES = ROWS * Integer.BYTES;

    public void testDeltaDecodesChangedRowAndRetainsCleanRow() {
        Packet packet = packet();
        GhosttyRenderDelta delta = packet.delta;

        assertEquals(COLUMNS, delta.columns);
        assertEquals(ROWS, delta.rows);
        assertEquals(1, delta.changedRowCount);
        assertFalse(delta.fullFrame);
        assertTrue(delta.hasRow(0));
        assertFalse(delta.hasRow(1));
        assertEquals(0xff112233, delta.foregroundAt(0, 0));
        assertEquals(GhosttyRenderDelta.WIDE_WIDE, delta.wideAt(0, 1));
        assertEquals("A", delta.decodeUtf8(0,
            delta.textOffsetAt(0, 0), delta.textLengthAt(0, 0)));
        assertEquals("界", delta.decodeUtf8(0,
            delta.textOffsetAt(0, 1), delta.textLengthAt(0, 1)));
        assertEquals(-7, delta.topRow);
        assertEquals(91L, delta.stateGeneration);
    }

    public void testReadingRetainedRowFailsClosed() {
        GhosttyRenderDelta delta = packet().delta;
        try {
            delta.flagsAt(1, 0);
            fail("Expected retained row access to fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("retained"));
        }
    }

    public void testBulkRowCopyMatchesScalarAccessors() {
        GhosttyRenderDelta delta = packet().delta;
        int[] foreground = new int[COLUMNS];
        int[] background = new int[COLUMNS];
        int[] underline = new int[COLUMNS];
        int[] flags = new int[COLUMNS];
        int[] textOffsets = new int[COLUMNS];
        int[] textLengths = new int[COLUMNS];

        int utf8Length = delta.copyRowCellsAndGetUtf8Length(0, foreground, background,
            underline, flags, textOffsets, textLengths);

        for (int column = 0; column < COLUMNS; column++) {
            assertEquals(delta.foregroundAt(0, column), foreground[column]);
            assertEquals(delta.backgroundAt(0, column), background[column]);
            assertEquals(delta.underlineColorAt(0, column), underline[column]);
            assertEquals(delta.flagsAt(0, column), flags[column]);
            assertEquals(delta.textOffsetAt(0, column), textOffsets[column]);
            assertEquals(delta.textLengthAt(0, column), textLengths[column]);
        }
        assertEquals("A界".getBytes(StandardCharsets.UTF_8).length, utf8Length);
        assertEquals(textOffsets[0], delta.rowUtf8Offset(0));
    }

    public void testInterleavedRecordBulkCopyMatchesScalarAccessors() {
        GhosttyRenderDelta delta = packet().delta;
        int[] records = new int[COLUMNS * GhosttyRenderDelta.CELL_RECORD_INTS];

        int utf8Length = delta.copyRowRecordsAndGetUtf8Length(0, records);

        for (int column = 0; column < COLUMNS; column++) {
            int record = column * GhosttyRenderDelta.CELL_RECORD_INTS;
            assertEquals(delta.foregroundAt(0, column), records[record]);
            assertEquals(delta.backgroundAt(0, column), records[record + 1]);
            assertEquals(delta.underlineColorAt(0, column), records[record + 2]);
            assertEquals(delta.flagsAt(0, column), records[record + 3]);
            assertEquals(delta.textOffsetAt(0, column), records[record + 4]);
            assertEquals(delta.textLengthAt(0, column), records[record + 5]);
        }
        assertEquals("A界".getBytes(StandardCharsets.UTF_8).length, utf8Length);
    }

    public void testInterleavedRecordBulkCopyRejectsUndersizedDestination() {
        GhosttyRenderDelta delta = packet().delta;
        try {
            delta.copyRowRecordsAndGetUtf8Length(0,
                new int[COLUMNS * GhosttyRenderDelta.CELL_RECORD_INTS - 1]);
            fail("Expected undersized record destination to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("records length"));
        }
    }

    public void testBulkRowCopyRejectsUndersizedDestination() {
        GhosttyRenderDelta delta = packet().delta;
        try {
            delta.copyRowCells(0, new int[COLUMNS - 1], new int[COLUMNS],
                new int[COLUMNS], new int[COLUMNS], new int[COLUMNS], new int[COLUMNS]);
            fail("Expected undersized bulk destination to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("foreground"));
        }
    }

    public void testRowUtf8RangeCopiesIntoReusableStorage() {
        GhosttyRenderDelta delta = packet().delta;
        int firstOffset = delta.rowUtf8Offset(0);
        int[] fields = new int[COLUMNS];
        int length = delta.copyRowCellsAndGetUtf8Length(0, fields, new int[COLUMNS],
            new int[COLUMNS], new int[COLUMNS], new int[COLUMNS], new int[COLUMNS]);
        byte[] destination = new byte[length + 8];
        delta.copyUtf8Range(0, firstOffset, length, destination);
        assertEquals("A界", new String(destination, 0, length, StandardCharsets.UTF_8));
    }

    public void testRetainedRowHasNoAddressableUtf8Arena() {
        GhosttyRenderDelta delta = packet().delta;
        try {
            delta.rowUtf8Offset(1);
            fail("Expected retained row UTF-8 access to fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("retained"));
        }
    }

    public void testRowUtf8RangeCannotCrossIntoAnotherPayload() {
        GhosttyRenderDelta delta = packet().delta;
        try {
            delta.copyUtf8Range(0, delta.textOffsetAt(0, 0), delta.bytesUsed,
                new byte[delta.bytesUsed]);
            fail("Expected cross-payload range to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("grapheme range"));
        }
    }

    public void testConstructorRejectsDirectoryOutsidePayload() {
        Packet packet = packet();
        packet.buffer.putInt(0, packet.buffer.capacity());
        try {
            new GhosttyRenderDelta(packet.buffer, packet.metadata);
            fail("Expected corrupt row payload to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("row payload"));
        }
    }

    public void testFullFrameRequiresEveryRow() {
        Packet packet = packet();
        packet.metadata[19] = 1;
        try {
            new GhosttyRenderDelta(packet.buffer, packet.metadata);
            fail("Expected incomplete full frame to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bounds"));
        }
    }

    private static Packet packet() {
        byte[] first = "A".getBytes(StandardCharsets.UTF_8);
        byte[] second = "界".getBytes(StandardCharsets.UTF_8);
        int rowPayload = DIRECTORY_BYTES;
        int rowTableBytes = COLUMNS * CELL_RECORD_BYTES;
        int textOffset = rowPayload + rowTableBytes;
        int used = textOffset + first.length + second.length;
        ByteBuffer buffer = ByteBuffer.allocateDirect(used).order(ByteOrder.nativeOrder());
        buffer.putInt(0, rowPayload);
        buffer.putInt(4, 0);
        putCell(buffer, rowPayload, 0xff112233, 0xff010203,
            GhosttyRenderDelta.CELL_BOLD, textOffset, first.length);
        putCell(buffer, rowPayload + CELL_RECORD_BYTES, 0xff445566, 0xff040506,
            GhosttyRenderDelta.CELL_ITALIC | (GhosttyRenderDelta.WIDE_WIDE << 16),
            textOffset + first.length, second.length);
        buffer.position(textOffset);
        buffer.put(first);
        buffer.put(second);
        buffer.position(0);

        long[] metadata = new long[26];
        metadata[0] = 3;
        metadata[1] = used;
        metadata[2] = CELL_RECORD_BYTES;
        metadata[3] = COLUMNS;
        metadata[4] = ROWS;
        metadata[5] = 0xff000000L;
        metadata[6] = 0xffffffffL;
        metadata[7] = 0xffccccccL;
        metadata[8] = 1;
        metadata[9] = 0;
        metadata[10] = 1;
        metadata[11] = 1;
        metadata[12] = 0;
        metadata[13] = 1;
        metadata[14] = 37;
        metadata[15] = DIRECTORY_BYTES;
        metadata[16] = 42;
        metadata[17] = 8192;
        metadata[18] = 1;
        metadata[19] = 0;
        metadata[20] = -7;
        metadata[21] = 91;
        return new Packet(buffer, metadata, new GhosttyRenderDelta(buffer, metadata));
    }

    private static void putCell(ByteBuffer buffer, int offset, int foreground, int background,
                                int flags, int textOffset, int textLength) {
        buffer.putInt(offset, foreground);
        buffer.putInt(offset + 4, background);
        buffer.putInt(offset + 8, foreground);
        buffer.putInt(offset + 12, flags);
        buffer.putInt(offset + 16, textOffset);
        buffer.putInt(offset + 20, textLength);
    }

    private static final class Packet {
        final ByteBuffer buffer;
        final long[] metadata;
        final GhosttyRenderDelta delta;

        Packet(ByteBuffer buffer, long[] metadata, GhosttyRenderDelta delta) {
            this.buffer = buffer;
            this.metadata = metadata;
            this.delta = delta;
        }
    }
}
