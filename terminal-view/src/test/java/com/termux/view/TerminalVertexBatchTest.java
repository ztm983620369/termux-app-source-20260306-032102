package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class TerminalVertexBatchTest {

    @Test
    public void encodesQuadInNativeInstanceLayout() {
        TerminalVertexBatch batch = new TerminalVertexBatch(1);
        batch.appendQuad(1f, 2f, 11f, 22f, 101f, 202f, 808f, 909f,
            0x7f123456, 2);

        assertEquals(1, batch.instanceCount());
        assertEquals(TerminalVertexBatch.INSTANCE_BYTES, batch.byteCount());
        ByteBuffer encoded = ByteBuffer.wrap(batch.copyBytes()).order(ByteOrder.nativeOrder());
        assertEquals(1f, encoded.getFloat(0), 0f);
        assertEquals(2f, encoded.getFloat(4), 0f);
        assertEquals(11f, encoded.getFloat(8), 0f);
        assertEquals(22f, encoded.getFloat(12), 0f);
        assertEquals(101, Short.toUnsignedInt(encoded.getShort(16)));
        assertEquals(202, Short.toUnsignedInt(encoded.getShort(18)));
        assertEquals(808, Short.toUnsignedInt(encoded.getShort(20)));
        assertEquals(909, Short.toUnsignedInt(encoded.getShort(22)));
        assertEquals(TerminalVertexBatch.packRgba(0x7f123456), encoded.getInt(24));
        assertEquals(2, encoded.getInt(28));
    }

    @Test
    public void growsAndCanBeReusedWithoutRetainingOldVertices() {
        TerminalVertexBatch batch = new TerminalVertexBatch(1);
        for (int index = 0; index < 128; index++) {
            batch.appendQuad(index, 0f, index + 1f, 1f,
                0f, 0f, 1f, 1f, 0xffffffff, 0);
        }
        assertEquals(128, batch.instanceCount());

        batch.clear();
        batch.appendQuad(2f, 3f, 4f, 5f, 0f, 0f, 0f, 0f, 0xff000000, 0);
        assertEquals(1, batch.instanceCount());
        ByteBuffer encoded = ByteBuffer.wrap(batch.copyBytes()).order(ByteOrder.nativeOrder());
        assertEquals(2f, encoded.getFloat(0), 0f);
        assertEquals(3f, encoded.getFloat(4), 0f);
    }

    @Test
    public void reusesRetainedRowStorageAndOverwritesItsPreviousVertices() {
        TerminalVertexBatch batch = new TerminalVertexBatch(2);
        batch.appendQuad(1f, 2f, 3f, 4f, 0f, 0f, 1f, 1f, 0xff112233, 0);
        int[] retained = batch.copyWords();

        batch.clear();
        batch.appendQuad(9f, 8f, 17f, 16f, 0f, 0f, 1f, 1f, 0xff445566, 2);
        int[] reused = batch.copyWords(retained);

        assertSame(retained, reused);
        assertEquals(Float.floatToRawIntBits(9f), reused[0]);
        assertEquals(Float.floatToRawIntBits(8f), reused[1]);
        assertEquals(Float.floatToRawIntBits(17f), reused[2]);
        assertEquals(Float.floatToRawIntBits(16f), reused[3]);
        assertEquals(TerminalVertexBatch.packRgba(0xff445566), reused[6]);
        assertEquals(2, reused[7]);
    }
}
