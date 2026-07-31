package com.termux.terminal;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class TerminalSessionInputBatchTest {

    @Test
    public void drainsAlreadyAvailableSmallReadsUpToBatchCapacity() throws Exception {
        byte[] source = sequence(40 * 1024);
        InputStream input = new ChunkedInputStream(source, 4096, true);
        byte[] batch = new byte[32 * 1024];

        int first = TerminalSession.readProcessOutputBatch(input, batch);
        Assert.assertEquals(batch.length, first);
        Assert.assertArrayEquals(slice(source, 0, first), batch);

        int second = TerminalSession.readProcessOutputBatch(input, batch);
        Assert.assertEquals(source.length - first, second);
        Assert.assertArrayEquals(slice(source, first, source.length), slice(batch, 0, second));
        Assert.assertEquals(-1, TerminalSession.readProcessOutputBatch(input, batch));
    }

    @Test
    public void returnsImmediatelyWhenNoAdditionalBytesAreReportedAvailable() throws Exception {
        byte[] source = sequence(12 * 1024);
        InputStream input = new ChunkedInputStream(source, 4096, false);
        byte[] batch = new byte[32 * 1024];

        Assert.assertEquals(4096, TerminalSession.readProcessOutputBatch(input, batch));
        Assert.assertEquals(4096, TerminalSession.readProcessOutputBatch(input, batch));
    }

    private static byte[] sequence(int length) {
        byte[] value = new byte[length];
        for (int index = 0; index < length; index++) value[index] = (byte) index;
        return value;
    }

    private static byte[] slice(byte[] source, int start, int end) {
        byte[] value = new byte[end - start];
        System.arraycopy(source, start, value, 0, value.length);
        return value;
    }

    private static final class ChunkedInputStream extends ByteArrayInputStream {
        private final int maximumRead;
        private final boolean reportAvailable;

        ChunkedInputStream(byte[] source, int maximumRead, boolean reportAvailable) {
            super(source);
            this.maximumRead = maximumRead;
            this.reportAvailable = reportAvailable;
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            return super.read(buffer, offset, Math.min(length, maximumRead));
        }

        @Override
        public synchronized int available() {
            return reportAvailable ? super.available() : 0;
        }
    }
}
