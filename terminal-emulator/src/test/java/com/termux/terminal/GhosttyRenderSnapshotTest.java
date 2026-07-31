package com.termux.terminal;

import junit.framework.TestCase;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class GhosttyRenderSnapshotTest extends TestCase {

    private static final int CELL_RECORD_BYTES = 24;

    public void testPacketDecodesCompleteCellContract() {
        Packet packet = packet("界", 0xff102030, 0xff405060);
        GhosttyRenderSnapshot snapshot = packet.snapshot;

        assertEquals(1, snapshot.columns);
        assertEquals(1, snapshot.rows);
        assertEquals(1, snapshot.cellCount());
        assertEquals(0xff102030, snapshot.foregroundAt(0));
        assertEquals(0xff405060, snapshot.backgroundAt(0));
        assertEquals(0xff708090, snapshot.underlineColorAt(0));
        assertTrue((snapshot.flagsAt(0) & GhosttyRenderSnapshot.CELL_BOLD) != 0);
        assertEquals(3, snapshot.underlineStyleAt(0));
        assertEquals(GhosttyRenderSnapshot.WIDE_WIDE, snapshot.wideAt(0));
        assertEquals("界", snapshot.decodeUtf8(
            snapshot.textOffsetAt(0), snapshot.textLengthAt(0)));
        assertEquals(41L, snapshot.renderGeneration);
        assertEquals(8192L, snapshot.ptyBytes);
    }

    public void testImmutableCopyDoesNotAliasReusableNativePacket() {
        Packet packet = packet("A", 0xff112233, 0xff445566);
        GhosttyRenderSnapshot copy = packet.snapshot.immutableCopy();

        packet.buffer.putInt(0, 0xffabcdef);
        packet.buffer.put(CELL_RECORD_BYTES, (byte) 'Z');

        assertEquals(0xffabcdef, packet.snapshot.foregroundAt(0));
        assertEquals("Z", packet.snapshot.decodeUtf8(CELL_RECORD_BYTES, 1));
        assertEquals(0xff112233, copy.foregroundAt(0));
        assertEquals("A", copy.decodeUtf8(copy.textOffsetAt(0), copy.textLengthAt(0)));
    }

    public void testCorruptGraphemeRangeFailsClosed() {
        Packet packet = packet("A", 1, 2);
        packet.buffer.putInt(16, 0);

        try {
            packet.snapshot.decodeUtf8(
                packet.snapshot.textOffsetAt(0), packet.snapshot.textLengthAt(0));
            fail("Expected corrupt grapheme offset to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("grapheme"));
        }
    }

    public void testConstructorRejectsTruncatedPacket() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(CELL_RECORD_BYTES - 1)
            .order(ByteOrder.nativeOrder());
        long[] metadata = metadata(CELL_RECORD_BYTES, 1);

        try {
            new GhosttyRenderSnapshot(buffer, metadata);
            fail("Expected truncated packet to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bounds"));
        }
    }

    private static Packet packet(String text, int foreground, int background) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        int bytesUsed = CELL_RECORD_BYTES + utf8.length;
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytesUsed).order(ByteOrder.nativeOrder());
        buffer.putInt(0, foreground);
        buffer.putInt(4, background);
        buffer.putInt(8, 0xff708090);
        buffer.putInt(12, GhosttyRenderSnapshot.CELL_BOLD |
            (3 << 12) | (GhosttyRenderSnapshot.WIDE_WIDE << 16));
        buffer.putInt(16, CELL_RECORD_BYTES);
        buffer.putInt(20, utf8.length);
        buffer.position(CELL_RECORD_BYTES);
        buffer.put(utf8);
        buffer.position(0);
        return new Packet(buffer, new GhosttyRenderSnapshot(buffer, metadata(bytesUsed, 1)));
    }

    private static long[] metadata(int bytesUsed, int cells) {
        long[] metadata = new long[18];
        metadata[0] = 1;
        metadata[1] = bytesUsed;
        metadata[2] = CELL_RECORD_BYTES;
        metadata[3] = cells;
        metadata[4] = 1;
        metadata[5] = 0xff000000L;
        metadata[6] = 0xffffffffL;
        metadata[7] = 0xffccccccL;
        metadata[8] = 0;
        metadata[9] = 0;
        metadata[10] = 1;
        metadata[11] = 1;
        metadata[12] = 0;
        metadata[13] = 2;
        metadata[14] = 37;
        metadata[15] = (long) cells * CELL_RECORD_BYTES;
        metadata[16] = 41;
        metadata[17] = 8192;
        return metadata;
    }

    private static final class Packet {
        final ByteBuffer buffer;
        final GhosttyRenderSnapshot snapshot;

        Packet(ByteBuffer buffer, GhosttyRenderSnapshot snapshot) {
            this.buffer = buffer;
            this.snapshot = snapshot;
        }
    }
}
