package com.termux.terminal;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TerminalNativeAcceleratorTest extends TestCase {

    public void testScalarClassifierReturnsMaximalPrintableRuns() {
        byte[] input = ("tiny\033[31m0123456789abcdef\r\n" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ\u4e2d\u6587tail-run-12345678")
            .getBytes(StandardCharsets.UTF_8);
        IntBuffer ranges = newRanges(17);

        int count = TerminalNativeAccelerator.scanAsciiRunsScalar(input, input.length, ranges);

        assertEquals(input.length, ranges.get(0));
        assertEquals(3, count);
        assertEquals("[5, 25, 27, 53, 59, 76]",
            rangeString(ranges, count));
    }

    public void testScalarClassifierReportsSafeFallbackBoundaryOnOverflow() {
        byte[] input = "12345678\nabcdefgh\nABCDEFGH\nijklmnop"
            .getBytes(StandardCharsets.US_ASCII);
        IntBuffer ranges = newRanges(5);

        int count = TerminalNativeAccelerator.scanAsciiRunsScalar(input, input.length, ranges);

        assertEquals(2, count);
        assertEquals(18, ranges.get(0));
        assertEquals(0, ranges.get(1));
        assertEquals(8, ranges.get(2));
        assertEquals(9, ranges.get(3));
        assertEquals(17, ranges.get(4));
    }

    public void testScalarClassifierMatchesIndependentRandomizedOracle() {
        for (int seed = 0; seed < 128; seed++) {
            Random random = new Random(0x4756545343414eL + seed);
            byte[] input = new byte[random.nextInt(4097)];
            for (int index = 0; index < input.length; index++) {
                input[index] = random.nextInt(10) < 7
                    ? (byte) (0x20 + random.nextInt(0x7f - 0x20))
                    : (byte) random.nextInt(256);
            }

            for (int capacity = 1; capacity <= 9; capacity++) {
                IntBuffer ranges = newRanges(1 + capacity * 2);
                int count = TerminalNativeAccelerator.scanAsciiRunsScalar(
                    input, input.length, ranges);
                OracleResult expected = classifyIndependently(input, capacity);

                assertEquals("seed=" + seed + ", capacity=" + capacity,
                    expected.scannedUntil, ranges.get(0));
                assertEquals("seed=" + seed + ", capacity=" + capacity,
                    expected.ranges.size() / 2, count);
                for (int index = 0; index < expected.ranges.size(); index++) {
                    assertEquals("seed=" + seed + ", capacity=" + capacity + ", index=" + index,
                        expected.ranges.get(index).intValue(), ranges.get(index + 1));
                }
            }
        }
    }

    private static OracleResult classifyIndependently(byte[] input, int capacity) {
        List<Integer> ranges = new ArrayList<>();
        int scannedUntil = input.length;
        int offset = 0;
        while (offset < input.length) {
            while (offset < input.length && !isPrintableAscii(input[offset])) offset++;
            int start = offset;
            while (offset < input.length && isPrintableAscii(input[offset])) offset++;
            if (offset - start < TerminalNativeAccelerator.MIN_ASCII_RUN_BYTES) continue;
            if (ranges.size() / 2 == capacity) {
                scannedUntil = start;
                break;
            }
            ranges.add(start);
            ranges.add(offset);
        }
        return new OracleResult(scannedUntil, ranges);
    }

    private static boolean isPrintableAscii(byte value) {
        int unsigned = value & 0xff;
        return unsigned >= 0x20 && unsigned < 0x7f;
    }

    private static IntBuffer newRanges(int capacity) {
        return ByteBuffer.allocateDirect(capacity * Integer.BYTES)
            .order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    private static String rangeString(IntBuffer ranges, int count) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < count * 2; index++) {
            if (index > 0) result.append(", ");
            result.append(ranges.get(1 + index));
        }
        return result.append(']').toString();
    }

    private static final class OracleResult {
        final int scannedUntil;
        final List<Integer> ranges;

        OracleResult(int scannedUntil, List<Integer> ranges) {
            this.scannedUntil = scannedUntil;
            this.ranges = ranges;
        }
    }
}
