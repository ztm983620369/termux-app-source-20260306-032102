package com.termux.terminal;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Optional NDK acceleration for byte-oriented terminal hot paths.
 *
 * <p>The native boundary is deliberately one call per PTY chunk, never one call per byte or
 * character. Desktop JVM tests and unsupported ABIs transparently use the Java parser path.</p>
 */
final class TerminalNativeAccelerator {

    static final int MIN_ACCELERATED_BYTES = 64;
    static final int MIN_ASCII_RUN_BYTES = 8;
    private static final String LOG_TAG = "TermuxTerminalCore";

    private static volatile boolean sAvailable = loadNativeLibrary();
    private static volatile boolean sActivationLogged;

    private TerminalNativeAccelerator() {
    }

    static int scanAsciiRuns(byte[] input, int length, ByteBuffer rangeStorage, IntBuffer ranges) {
        if (ranges != null && ranges.capacity() > 0) ranges.put(0, -1);
        if (!sAvailable || input == null || rangeStorage == null || ranges == null ||
            !rangeStorage.isDirect() || length < MIN_ACCELERATED_BYTES || length > input.length ||
            ranges.capacity() < 3) {
            return 0;
        }
        try {
            int rangeCount = nativeScanAsciiRuns(input, length, rangeStorage);
            if (validateRanges(length, ranges, rangeCount)) {
                logActivationOnce();
                return rangeCount;
            }
            Log.e(LOG_TAG, "Native ASCII scanner returned an invalid range contract; disabling it");
            ranges.put(0, -1);
            return disable();
        } catch (LinkageError | RuntimeException error) {
            Log.e(LOG_TAG, "Native ASCII scanner failed; disabling it", error);
            ranges.put(0, -1);
            return disable();
        }
    }

    /** Scalar oracle used by local differential tests for the JNI output contract. */
    static int scanAsciiRunsScalar(byte[] input, int length, IntBuffer ranges) {
        if (input == null || ranges == null || length < 0 || length > input.length ||
            ranges.capacity() < 3) {
            return 0;
        }

        int rangeCount = 0;
        int offset = 0;
        int scannedUntil = length;
        final int capacity = (ranges.capacity() - 1) / 2;
        while (offset < length) {
            while (offset < length && !isPrintableAscii(input[offset])) offset++;
            int start = offset;
            while (offset < length && isPrintableAscii(input[offset])) offset++;
            if (offset - start < MIN_ASCII_RUN_BYTES) continue;
            if (rangeCount >= capacity) {
                scannedUntil = start;
                break;
            }
            ranges.put(1 + rangeCount * 2, start);
            ranges.put(2 + rangeCount * 2, offset);
            rangeCount++;
        }
        ranges.put(0, scannedUntil);
        return rangeCount;
    }

    private static boolean validateRanges(int length, IntBuffer ranges, int rangeCount) {
        if (rangeCount < 0 || 1 + rangeCount * 2 > ranges.capacity()) return false;
        int scannedUntil = ranges.get(0);
        if (scannedUntil < 0 || scannedUntil > length) return false;
        int previousEnd = 0;
        for (int index = 0; index < rangeCount; index++) {
            int start = ranges.get(1 + index * 2);
            int end = ranges.get(2 + index * 2);
            if (start < previousEnd || end < start + MIN_ASCII_RUN_BYTES || end > scannedUntil) {
                return false;
            }
            previousEnd = end;
        }
        return true;
    }

    private static boolean isPrintableAscii(byte value) {
        int unsigned = value & 0xff;
        return unsigned >= 0x20 && unsigned < 0x7f;
    }

    private static int disable() {
        sAvailable = false;
        return 0;
    }

    private static boolean loadNativeLibrary() {
        try {
            System.loadLibrary("termux");
            if (runNativeSelfTest()) return true;
            Log.e(LOG_TAG, "Native ASCII scanner self-test failed; using the Java parser path");
            return false;
        } catch (LinkageError | SecurityException ignored) {
            return false;
        } catch (RuntimeException error) {
            Log.e(LOG_TAG, "Native ASCII scanner initialization failed", error);
            return false;
        }
    }

    private static boolean runNativeSelfTest() {
        byte[] input = new byte[384];
        for (int index = 0; index < input.length; index++) {
            int lane = index % 97;
            input[index] = lane < 71
                ? (byte) (0x20 + (index * 29) % (0x7f - 0x20))
                : (byte) ((index * 131) & 0xff);
        }
        ByteBuffer nativeStorage = newRangeStorage(17);
        ByteBuffer scalarStorage = newRangeStorage(17);
        IntBuffer nativeRanges = nativeStorage.asIntBuffer();
        IntBuffer scalarRanges = scalarStorage.asIntBuffer();
        nativeRanges.put(0, -1);
        int nativeCount = nativeScanAsciiRuns(input, input.length, nativeStorage);
        int scalarCount = scanAsciiRunsScalar(input, input.length, scalarRanges);
        if (!validateRanges(input.length, nativeRanges, nativeCount) || nativeCount != scalarCount) {
            return false;
        }
        for (int index = 0; index <= nativeCount * 2; index++) {
            if (nativeRanges.get(index) != scalarRanges.get(index)) return false;
        }
        return true;
    }

    private static ByteBuffer newRangeStorage(int integers) {
        return ByteBuffer.allocateDirect(integers * Integer.BYTES).order(ByteOrder.nativeOrder());
    }

    private static void logActivationOnce() {
        if (sActivationLogged) return;
        synchronized (TerminalNativeAccelerator.class) {
            if (sActivationLogged) return;
            String backend;
            switch (nativeAsciiBackend()) {
                case 2:
                    backend = "NEON-128";
                    break;
                case 3:
                    backend = "SSE2-128";
                    break;
                default:
                    backend = "scalar";
                    break;
            }
            sActivationLogged = true;
            Log.i(LOG_TAG, "Ghostty-derived zero-copy ASCII scanner active; backend=" + backend);
        }
    }

    static boolean isAvailableForDiagnostics() {
        return sAvailable;
    }

    static String backendNameForDiagnostics() {
        if (!sAvailable) return "disabled";
        switch (nativeAsciiBackend()) {
            case 2:
                return "NEON-128";
            case 3:
                return "SSE2-128";
            default:
                return "scalar";
        }
    }

    private static native int nativeScanAsciiRuns(byte[] input, int length, ByteBuffer ranges);
    private static native int nativeAsciiBackend();
}
