package com.termux.terminal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Debug-only ART benchmark for the terminal byte parser. */
public final class TerminalParserPerformanceProbe {

    private static final int COLUMNS = 120;
    private static final int ROWS = 40;
    private static final int ITERATIONS = 7;

    private TerminalParserPerformanceProbe() {
    }

    public static void main(String[] args) {
        byte[] workload = buildWorkload(1024 * 1024);
        for (int i = 0; i < 3; i++) {
            run(workload, true);
            run(workload, false);
        }

        long[] batched = new long[ITERATIONS];
        long[] scalar = new long[ITERATIONS];
        int batchedChecksum = 0;
        int scalarChecksum = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            Result fast = run(workload, true);
            Result reference = run(workload, false);
            batched[i] = fast.elapsedNanos;
            scalar[i] = reference.elapsedNanos;
            batchedChecksum = fast.checksum;
            scalarChecksum = reference.checksum;
        }
        if (batchedChecksum != scalarChecksum) {
            throw new AssertionError("Parser checksum mismatch: " + batchedChecksum + " != " + scalarChecksum);
        }

        long batchedMedian = median(batched);
        long scalarMedian = median(scalar);
        double fastMibPerSecond = throughputMibPerSecond(workload.length, batchedMedian);
        double scalarMibPerSecond = throughputMibPerSecond(workload.length, scalarMedian);
        System.out.println("TERMUX_PERF parser_bytes=" + workload.length +
            " batch_ns=" + batchedMedian +
            " scalar_ns=" + scalarMedian +
            " batch_mib_s=" + format(fastMibPerSecond) +
            " scalar_mib_s=" + format(scalarMibPerSecond) +
            " speedup=" + format((double) scalarMedian / batchedMedian) +
            " checksum=" + batchedChecksum);
    }

    private static Result run(byte[] workload, boolean batched) {
        NullOutput output = new NullOutput();
        TerminalEmulator emulator = new TerminalEmulator(output, COLUMNS, ROWS, 12, 24, 2000, null);
        long started = System.nanoTime();
        if (batched) {
            emulator.append(workload, workload.length);
        } else {
            emulator.appendByteWiseForTesting(workload, workload.length);
        }
        long elapsed = System.nanoTime() - started;
        TerminalBuffer screen = emulator.getScreen();
        int checksum = screen.getTranscriptText().hashCode();
        checksum = 31 * checksum + emulator.getCursorRow();
        checksum = 31 * checksum + emulator.getCursorCol();
        checksum = 31 * checksum + screen.getActiveTranscriptRows();
        return new Result(elapsed, checksum);
    }

    private static byte[] buildWorkload(int targetBytes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(targetBytes + 4096);
        int line = 0;
        while (output.size() < targetBytes) {
            String value = "\033[38;5;" + (16 + line % 200) + "m" +
                "pane=" + (line % 8) + " seq=" + line +
                " build output: abcdefghijklmnopqrstuvwxyz 0123456789 status=ok" +
                "\033[0m\r\n";
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            output.write(bytes, 0, bytes.length);
            line++;
        }
        return output.toByteArray();
    }

    private static long median(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static double throughputMibPerSecond(int bytes, long nanos) {
        return bytes / (1024.0 * 1024.0) / (nanos / 1_000_000_000.0);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    private static final class Result {
        final long elapsedNanos;
        final int checksum;

        Result(long elapsedNanos, int checksum) {
            this.elapsedNanos = elapsedNanos;
            this.checksum = checksum;
        }
    }

    private static final class NullOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
        @Override public void onTerminalHostControlCommand(String command, String argument) { }
    }
}
