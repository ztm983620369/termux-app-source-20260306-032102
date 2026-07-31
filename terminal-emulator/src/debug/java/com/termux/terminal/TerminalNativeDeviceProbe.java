package com.termux.terminal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Debug-only on-device differential and throughput probe for the native terminal parser path. */
public final class TerminalNativeDeviceProbe {

    private static final int SCANNER_CASES = 256;
    private static final int WORKLOAD_BYTES = 1024 * 1024;

    private TerminalNativeDeviceProbe() {
    }

    public static String run() {
        require(TerminalNativeAccelerator.isAvailableForDiagnostics(),
            "native ASCII scanner unavailable or failed its startup self-test");
        String ghosttyLibraryInfo = GhosttyTerminalBackend.libraryInfoForDiagnostics();
        require(GhosttyTerminalBackend.isLibraryAvailableForDiagnostics(),
            "complete libghostty-vt unavailable: " + ghosttyLibraryInfo);
        require(ghosttyLibraryInfo.contains("render_query=batch-v1"),
            "libghostty-vt render batch API is not active: " + ghosttyLibraryInfo);
        require(ghosttyLibraryInfo.contains("grapheme_utf8=ghostty"),
            "libghostty-vt direct UTF-8 render path is not active: " + ghosttyLibraryInfo);
        require(ghosttyLibraryInfo.contains("cell_metadata=batch-v1") &&
                ghosttyLibraryInfo.contains("color_query=selective"),
            "libghostty-vt selective cell query path is not active: " + ghosttyLibraryInfo);

        long nativeScannerNanos = 0L;
        long scalarScannerNanos = 0L;
        long scannedBytes = 0L;
        int scannerChecksum = 1;
        for (int seed = 0; seed < SCANNER_CASES; seed++) {
            Random random = new Random(0x4756544e41544956L + seed);
            byte[] input = new byte[64 + random.nextInt(16321)];
            fillScannerInput(input, random);
            int integerCapacity = 3 + 2 * (seed % 32);
            ByteBuffer nativeStorage = newRangeStorage(integerCapacity);
            ByteBuffer scalarStorage = newRangeStorage(integerCapacity);
            IntBuffer nativeRanges = nativeStorage.asIntBuffer();
            IntBuffer scalarRanges = scalarStorage.asIntBuffer();

            long started = System.nanoTime();
            int nativeCount = TerminalNativeAccelerator.scanAsciiRuns(
                input, input.length, nativeStorage, nativeRanges);
            nativeScannerNanos += System.nanoTime() - started;

            started = System.nanoTime();
            int scalarCount = TerminalNativeAccelerator.scanAsciiRunsScalar(
                input, input.length, scalarRanges);
            scalarScannerNanos += System.nanoTime() - started;

            require(nativeCount == scalarCount,
                "scanner count mismatch seed=" + seed + " native=" + nativeCount +
                    " scalar=" + scalarCount);
            for (int index = 0; index <= nativeCount * 2; index++) {
                int nativeValue = nativeRanges.get(index);
                int scalarValue = scalarRanges.get(index);
                require(nativeValue == scalarValue,
                    "scanner range mismatch seed=" + seed + " index=" + index +
                        " native=" + nativeValue + " scalar=" + scalarValue);
                scannerChecksum = 31 * scannerChecksum + nativeValue;
            }
            scannedBytes += input.length;
        }

        byte[] workload = buildParserWorkload(WORKLOAD_BYTES);
        ProbeOutput optimizedOutput = new ProbeOutput();
        ProbeOutput referenceOutput = new ProbeOutput();
        TerminalEmulator optimized = new TerminalEmulator(
            optimizedOutput, 120, 40, 12, 24, 2000, null);
        TerminalEmulator reference = new TerminalEmulator(
            referenceOutput, 120, 40, 12, 24, 2000, null);
        require(optimized.isGhosttyParserAuthorityActive(),
            "complete libghostty-vt parser authority unavailable");

        GhosttyTerminalBackend ghosttyOnly =
            GhosttyTerminalBackend.createIfEnabled(120, 40, 2000);
        require(ghosttyOnly != null, "complete libghostty-vt benchmark backend unavailable");
        long ghosttyOnlyParserNanos;
        try {
            long ghosttyStarted = System.nanoTime();
            require(ghosttyOnly.write(workload, workload.length),
                "complete libghostty-vt benchmark write failed");
            ghosttyOnlyParserNanos = System.nanoTime() - ghosttyStarted;
        } finally {
            ghosttyOnly.close();
        }

        long started = System.nanoTime();
        optimized.append(workload, workload.length);
        long optimizedParserNanos = System.nanoTime() - started;
        started = System.nanoTime();
        reference.appendByteWiseForTesting(workload, workload.length);
        long referenceParserNanos = System.nanoTime() - started;

        String optimizedTranscript = optimized.getTranscriptText();
        String referenceTranscript = reference.getScreen().getTranscriptText();
        require(optimizedTranscript.equals(referenceTranscript),
            describeTextMismatch("parser transcript", optimizedTranscript, referenceTranscript));
        require(optimized.getCursorRow() == reference.getCursorRow(), "parser cursor-row mismatch");
        require(optimized.getCursorCol() == reference.getCursorCol(), "parser cursor-column mismatch");
        require(Arrays.equals(optimized.mColors.mCurrentColors, reference.mColors.mCurrentColors),
            "parser palette mismatch");
        require(optimizedOutput.checksum == referenceOutput.checksum, "parser output mismatch");
        long[] parserUsage = snapshotNativeUsage(optimized);
        require(parserUsage[0] == 0L && parserUsage[1] == 0L,
            "Java compatibility parser unexpectedly ran in the Ghostty hot path");

        int cursorRowBeforeRollback = optimized.getCursorRow();
        int cursorColumnBeforeRollback = optimized.getCursorCol();
        byte[] rollbackCommand =
            "\033]8900;terminal-backend;java\033\\".getBytes(StandardCharsets.US_ASCII);
        optimized.append(rollbackCommand, rollbackCommand.length);
        try {
            String restoredTranscript = optimized.getTranscriptText();
            require(restoredTranscript.equals(referenceTranscript),
                "one-click Java rollback transcript mismatch");
            require(optimized.getCursorRow() == cursorRowBeforeRollback &&
                    optimized.getCursorCol() == cursorColumnBeforeRollback,
                "one-click Java rollback cursor mismatch");
            require(!optimized.isGhosttyParserAuthorityActive(),
                "one-click Java rollback left Ghostty authority active");
            int bellsBeforeFallbackEffects = optimizedOutput.bells;
            optimizedOutput.takeWrittenText();
            byte[] fallbackEffects =
                "\033]2;Java Rollback\033\\\007\033[6n".getBytes(StandardCharsets.UTF_8);
            optimized.append(fallbackEffects, fallbackEffects.length);
            require("Java Rollback".equals(optimizedOutput.title) &&
                    optimizedOutput.bells == bellsBeforeFallbackEffects + 1 &&
                    optimizedOutput.takeWrittenText().contains("R"),
                "Java rollback did not forward live terminal effects");
        } finally {
            GhosttyTerminalBackend.setProductionEnabled(true);
        }
        String inputEvidence = verifyNativeEffectsAndInput();
        String renderBatchEvidence = verifyRenderBatchPacketsForDiagnostics();

        return "backend=" + TerminalNativeAccelerator.backendNameForDiagnostics() +
            " ghostty_full_library=true" +
            " ghostty_full_info=" + sanitizeEvidence(ghosttyLibraryInfo) +
            " scanner_cases=" + SCANNER_CASES +
            " scanner_bytes=" + scannedBytes +
            " scanner_native_ns=" + nativeScannerNanos +
            " scanner_scalar_ns=" + scalarScannerNanos +
            " scanner_speedup=" + formatRatio(scalarScannerNanos, nativeScannerNanos) +
            " scanner_checksum=" + scannerChecksum +
            " parser_bytes=" + workload.length +
            " parser_native_ns=" + optimizedParserNanos +
            " parser_reference_ns=" + referenceParserNanos +
            " ghostty_only_ns=" + ghosttyOnlyParserNanos +
            " ghostty_only_speedup=" + formatRatio(referenceParserNanos, ghosttyOnlyParserNanos) +
            " parser_speedup=" + formatRatio(referenceParserNanos, optimizedParserNanos) +
            " parser_authority=libghostty-vt" +
            " parser_double_parse=false" +
            " parser_rollback=osc8900-journal-restored-live" +
            " fallback_scanner_calls=" + parserUsage[0] +
            " parser_checksum=" + optimizedTranscript.hashCode() +
            " " + inputEvidence +
            " " + renderBatchEvidence;
    }

    public static void setFullGhosttyValidationEnabled(boolean enabled) {
        GhosttyTerminalBackend.setValidationEnabledForDiagnostics(enabled);
    }

    public static FullGhosttyEvidence captureFullGhosttyEvidence(TerminalEmulator emulator) {
        require(emulator != null, "terminal emulator unavailable");
        GhosttyTerminalBackend.Snapshot snapshot = emulator.getGhosttySnapshotForDiagnostics();
        require(snapshot != null, "complete Ghostty terminal/render-state snapshot unavailable");
        return new FullGhosttyEvidence(snapshot);
    }

    public static void releaseFullGhosttyBackend(TerminalEmulator emulator) {
        if (emulator != null) emulator.releaseNativeResources();
    }

    public static String fullGhosttyLibraryInfo() {
        return GhosttyTerminalBackend.libraryInfoForDiagnostics();
    }

    /** Debug-variant bridge for instrumentation without widening the production public API. */
    public static long[] snapshotNativeUsage(TerminalEmulator emulator) {
        require(emulator != null, "terminal emulator unavailable");
        return new long[] {
            emulator.mNativeAsciiScanCalls,
            emulator.mNativeAsciiScanBytes,
            emulator.mNativeAsciiRangeCount,
            emulator.mNativeAsciiEmittedBytes
        };
    }

    private static void fillScannerInput(byte[] input, Random random) {
        int offset = 0;
        while (offset < input.length) {
            int printable = Math.min(input.length - offset, 4 + random.nextInt(192));
            for (int index = 0; index < printable; index++) {
                input[offset++] = (byte) (0x20 + random.nextInt(0x7f - 0x20));
            }
            if (offset < input.length) {
                int control = random.nextInt(4);
                input[offset++] = (byte) (control == 0 ? 0x1b
                    : control == 1 ? 0x80
                    : control == 2 ? 0x0a : 0x7f);
            }
        }
    }

    private static byte[] buildParserWorkload(int targetBytes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(targetBytes + 4096);
        int line = 0;
        while (output.size() < targetBytes) {
            String value = "\033[38;5;" + (16 + line % 200) + "m" +
                "pane=" + (line % 8) + " seq=" + line +
                " build output abcdefghijklmnopqrstuvwxyz 0123456789 status=ok" +
                "\033[0m\r\n";
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            output.write(bytes, 0, bytes.length);
            line++;
        }
        return output.toByteArray();
    }

    private static ByteBuffer newRangeStorage(int integers) {
        return ByteBuffer.allocateDirect(integers * Integer.BYTES).order(ByteOrder.nativeOrder());
    }

    private static String formatRatio(long slower, long faster) {
        if (faster <= 0L) return "inf";
        return String.format(Locale.US, "%.2f", (double) slower / faster);
    }

    private static String describeTextMismatch(String label, String actual, String expected) {
        int common = 0;
        int limit = Math.min(actual.length(), expected.length());
        while (common < limit && actual.charAt(common) == expected.charAt(common)) common++;
        int actualEnd = Math.min(actual.length(), common + 80);
        int expectedEnd = Math.min(expected.length(), common + 80);
        return label + " mismatch actual_len=" + actual.length() +
            " expected_len=" + expected.length() + " first_diff=" + common +
            " actual=" + sanitizeEvidence(actual.substring(common, actualEnd)) +
            " expected=" + sanitizeEvidence(expected.substring(common, expectedEnd));
    }

    private static String verifyNativeEffectsAndInput() {
        ProbeOutput output = new ProbeOutput();
        TerminalEmulator emulator = new TerminalEmulator(output, 80, 24, 8, 16, 2000, null);
        require(emulator.isGhosttyParserAuthorityActive(), "native input probe authority unavailable");

        byte[] effects = ("\033]2;Ghostty Effects\033\\\007" +
            "\033[6n\033]52;c;Y2xpcGJvYXJk\033\\" +
            "\033]8900;probe;native\033\\").getBytes(StandardCharsets.UTF_8);
        emulator.append(effects, effects.length);
        require("Ghostty Effects".equals(output.title), "Ghostty title effect mismatch");
        require(output.bells == 1, "Ghostty bell effect mismatch");
        require("clipboard".equals(output.clipboard), "Ghostty clipboard effect mismatch");
        require("probe".equals(output.hostCommand) && "native".equals(output.hostArgument),
            "Termux OSC 8900 native bridge mismatch");
        require(output.takeWrittenText().contains("\033[1;1R"),
            "Ghostty write-pty DSR effect mismatch");

        byte[] cursorBar = "\033[5 q".getBytes(StandardCharsets.US_ASCII);
        emulator.append(cursorBar, cursorBar.length);
        require(emulator.getCursorStyle() == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR,
            "Ghostty cursor style mapping mismatch");
        emulator.reset();
        require(emulator.getCursorStyle() == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK,
            "Ghostty configured default cursor reset mismatch");

        byte[] cursorMode = "\033[?1h".getBytes(StandardCharsets.US_ASCII);
        emulator.append(cursorMode, cursorMode.length);
        byte[] cursorUp = emulator.encodeKey(19, 0, 1);
        require(Arrays.equals(cursorUp, "\033OA".getBytes(StandardCharsets.US_ASCII)),
            "Ghostty application cursor key encode mismatch");
        byte[] ctrlC = emulator.encodeCodePoint('c', true, false);
        require(Arrays.equals(ctrlC, new byte[] {3}), "Ghostty Ctrl-C encode mismatch");
        byte[] altB = emulator.encodeCodePoint('b', false, true);
        require(Arrays.equals(altB, new byte[] {0x1b, 'b'}), "Ghostty Alt-B encode mismatch");

        byte[] mouseMode = "\033[?1000h\033[?1006h".getBytes(StandardCharsets.US_ASCII);
        emulator.append(mouseMode, mouseMode.length);
        output.takeWrittenText();
        emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 3, 4, true);
        require("\033[<0;3;4M".equals(output.takeWrittenText()),
            "Ghostty SGR mouse encode mismatch");

        byte[] focusAndPasteMode = "\033[?1004h\033[?2004h".getBytes(StandardCharsets.US_ASCII);
        emulator.append(focusAndPasteMode, focusAndPasteMode.length);
        output.takeWrittenText();
        emulator.onHostWindowFocusChanged(true);
        require("\033[I".equals(output.takeWrittenText()), "Ghostty focus encode mismatch");
        emulator.paste("a\nb\033");
        String paste = output.takeWrittenText();
        require(paste.startsWith("\033[200~") && paste.endsWith("\033[201~") &&
                paste.indexOf('\033', 6) == paste.length() - 6,
            "Ghostty bracketed paste sanitization mismatch");

        byte[] selection = ("alpha https://example.com/path omega\r\n" +
            "\033]8;;https://ghostty.org/docs\033\\linked\033]8;;\033\\\r\n" +
            "中x").getBytes(StandardCharsets.UTF_8);
        emulator.append(selection, selection.length);
        int[] word = emulator.getWordBounds(18, 0);
        require(word.length == 4 && word[0] <= 18 && word[2] >= 18,
            "Ghostty word selection bounds mismatch");
        require(emulator.getSelectedText(word[0], word[1], word[2], word[3])
                .contains("example.com/path"),
            "Ghostty word selection text mismatch");
        TerminalLinkResolver.SelectionResult textLink =
            emulator.resolveSelectionLinks(18, 0, 18, 0, true);
        require(textLink.getUrls().contains("https://example.com/path"),
            "Ghostty textual URL selection mismatch: " + textLink.getUrls());
        TerminalLinkResolver.SelectionResult semanticLink =
            emulator.resolveSelectionLinks(1, 1, 1, 1, true);
        require(semanticLink.getUrls().contains("https://ghostty.org/docs"),
            "Ghostty OSC 8 URL selection mismatch: " + semanticLink.getUrls());
        require(emulator.snapSelectionColumn(1, 2, true) == 0 &&
                emulator.snapSelectionColumn(0, 2, false) == 1,
            "Ghostty wide-grapheme selection snapping mismatch");
        byte[] kittyMode = "\033[>3u".getBytes(StandardCharsets.US_ASCII);
        emulator.append(kittyMode, kittyMode.length);
        byte[] kittyPress = emulator.encodeCodePoint(29, 1, 'a', 'a', 0);
        byte[] kittyRelease = emulator.encodeCodePoint(29, 0, 'a', 'a', 0);
        require(kittyPress != null && kittyPress.length > 0 &&
                kittyRelease != null && kittyRelease.length > 0 &&
                new String(kittyRelease, StandardCharsets.US_ASCII).contains(":3u"),
            "Ghostty Kitty physical-key press/release mismatch");
        require(snapshotNativeUsage(emulator)[0] == 0L,
            "Ghostty selection path materialized the Java parser");
        emulator.releaseNativeResources();
        return "ghostty_effects=write-pty,bell,title,clipboard,host-osc" +
            " ghostty_input=key,text,mouse,focus,paste,kitty-release" +
            " ghostty_selection=word,wide,url,osc8-native";
    }

    /** Debug-variant native packet proof shared by the on-device viewport instrumentation. */
    public static String verifyRenderBatchPacketsForDiagnostics() {
        String libraryInfo = GhosttyTerminalBackend.libraryInfoForDiagnostics();
        require(GhosttyTerminalBackend.isLibraryAvailableForDiagnostics(),
            "complete libghostty-vt unavailable: " + libraryInfo);
        require(libraryInfo.contains("render_query=batch-v1"),
            "libghostty-vt render batch API is not active: " + libraryInfo);
        require(libraryInfo.contains("grapheme_utf8=ghostty"),
            "libghostty-vt direct UTF-8 render path is not active: " + libraryInfo);
        require(libraryInfo.contains("cell_metadata=batch-v1") &&
                libraryInfo.contains("color_query=selective"),
            "libghostty-vt selective cell query path is not active: " + libraryInfo);
        GhosttyTerminalBackend backend = GhosttyTerminalBackend.createIfEnabled(16, 4, 100);
        require(backend != null, "libghostty-vt render batch probe backend unavailable");
        try {
            String complexText = "A\u754ce\u0301\ud83d\ude42";
            byte[] initial = ("\033[2J\033[H\033[1;31mA\033[0m" +
                "\u754ce\u0301\ud83d\ude42\033[48;5;46m \033[0m")
                .getBytes(StandardCharsets.UTF_8);
            require(backend.write(initial, initial.length),
                "libghostty-vt render batch probe write failed");

            GhosttyRenderSnapshot snapshot = backend.renderSnapshotCopy(0);
            require(snapshot != null && snapshot.columns == 16 && snapshot.rows == 4,
                "libghostty-vt batch snapshot unavailable");
            StringBuilder snapshotText = new StringBuilder();
            int boldCell = -1;
            int wideCell = -1;
            int combiningBytes = 0;
            int emojiBytes = 0;
            boolean backgroundOverride = false;
            for (int cell = 0; cell < snapshot.cellCount(); cell++) {
                int length = snapshot.textLengthAt(cell);
                backgroundOverride |= snapshot.backgroundAt(cell) != snapshot.backgroundColor;
                if (length == 0) continue;
                String grapheme = snapshot.decodeUtf8(snapshot.textOffsetAt(cell), length);
                snapshotText.append(grapheme);
                if ("A".equals(grapheme)) boldCell = cell;
                if ("\u754c".equals(grapheme)) wideCell = cell;
                if ("e\u0301".equals(grapheme)) combiningBytes = length;
                if ("\ud83d\ude42".equals(grapheme)) emojiBytes = length;
            }
            require(snapshotText.indexOf(complexText) >= 0,
                "batch snapshot UTF-8 mismatch: " + sanitizeEvidence(snapshotText.toString()));
            require(boldCell >= 0 &&
                    (snapshot.flagsAt(boldCell) & GhosttyRenderSnapshot.CELL_BOLD) != 0,
                "batch snapshot style mismatch");
            require(wideCell >= 0 &&
                    snapshot.wideAt(wideCell) == GhosttyRenderSnapshot.WIDE_WIDE &&
                    wideCell + 1 < snapshot.cellCount() &&
                    snapshot.wideAt(wideCell + 1) == GhosttyRenderSnapshot.WIDE_SPACER_TAIL,
                "batch snapshot wide-cell metadata mismatch");
            require(combiningBytes == 3 && emojiBytes == 4,
                "Ghostty direct UTF-8 lengths mismatch combining=" + combiningBytes +
                    " emoji=" + emojiBytes);
            require(backgroundOverride, "batch snapshot background color resolution mismatch");

            byte[] update = "\033[2;1H\033[3;4mZ\033[0m".getBytes(StandardCharsets.US_ASCII);
            require(backend.write(update, update.length),
                "libghostty-vt render delta probe write failed");
            GhosttyRenderDelta delta = backend.renderDelta(0, true);
            require(delta != null && delta.fullFrame && delta.changedRowCount == delta.rows,
                "libghostty-vt forced render delta is incomplete");
            boolean styledDeltaCell = false;
            int[] rowRecords = new int[delta.columns * GhosttyRenderDelta.CELL_RECORD_INTS];
            for (int row = 0; row < delta.rows; row++) {
                require(delta.hasRow(row), "forced render delta omitted row=" + row);
                int bulkUtf8Length = delta.copyRowRecordsAndGetUtf8Length(row, rowRecords);
                int scalarUtf8Length = 0;
                for (int column = 0; column < delta.columns; column++) {
                    int record = column * GhosttyRenderDelta.CELL_RECORD_INTS;
                    require(rowRecords[record] == delta.foregroundAt(row, column) &&
                            rowRecords[record + 1] == delta.backgroundAt(row, column) &&
                            rowRecords[record + 2] == delta.underlineColorAt(row, column) &&
                            rowRecords[record + 3] == delta.flagsAt(row, column) &&
                            rowRecords[record + 4] == delta.textOffsetAt(row, column) &&
                            rowRecords[record + 5] == delta.textLengthAt(row, column),
                        "bulk render record mismatch row=" + row + " column=" + column);
                    int length = delta.textLengthAt(row, column);
                    scalarUtf8Length += length;
                    if (length == 0 ||
                        !"Z".equals(delta.decodeUtf8(row, delta.textOffsetAt(row, column), length))) {
                        continue;
                    }
                    int flags = delta.flagsAt(row, column);
                    styledDeltaCell = (flags & GhosttyRenderDelta.CELL_ITALIC) != 0 &&
                        (flags & GhosttyRenderDelta.CELL_UNDERLINE) != 0;
                }
                require(bulkUtf8Length == scalarUtf8Length,
                    "bulk render UTF-8 length mismatch row=" + row);
            }
            require(styledDeltaCell, "batch render delta style/UTF-8 mismatch");

            // Repaint the complete screen to the exact same final content. Ghostty may report a
            // full dirty render state, but the retained native packet cache must prove equality
            // byte-for-byte and omit every non-cursor row before Java sees the packet.
            byte[] exactRepaint = ("\033[2J\033[H\033[1;31mA\033[0m" +
                "\u754ce\u0301\ud83d\ude42\033[48;5;46m \033[0m" +
                "\033[2;1H\033[3;4mZ\033[0m").getBytes(StandardCharsets.UTF_8);
            require(backend.write(exactRepaint, exactRepaint.length),
                "libghostty-vt exact repaint probe write failed");
            GhosttyRenderDelta semanticDelta = backend.renderDelta(0, false);
            require(semanticDelta != null && !semanticDelta.fullFrame &&
                    semanticDelta.semanticCandidateRows >= semanticDelta.rows - 1 &&
                    semanticDelta.semanticSuppressedRows > 0 &&
                    semanticDelta.semanticSuppressedRows <= semanticDelta.semanticCandidateRows,
                "native exact-row suppression inactive: " + backend.renderStatus());
            require(semanticDelta.changedRowCount + semanticDelta.semanticSuppressedRows <=
                    semanticDelta.rows,
                "native exact-row suppression produced impossible row counts");

            // A render-state failure must not discard or replay the live terminal. Force the Java
            // render gate closed, rebuild only Ghostty's derived render objects, then require an
            // immediately complete packet containing the state written before recovery.
            backend.forceRenderFailureForTesting();
            require(backend.renderDelta(0, true) == null,
                "forced render failure did not close the packet gate");
            require(backend.recoverRender(),
                "libghostty-vt render state could not recover in place");
            GhosttyRenderDelta recovered = backend.renderDelta(0, true);
            require(recovered != null && recovered.fullFrame &&
                    recovered.changedRowCount == recovered.rows,
                "recovered libghostty-vt packet is incomplete");
            boolean recoveredStyledCell = false;
            for (int row = 0; row < recovered.rows; row++) {
                require(recovered.hasRow(row),
                    "recovered render packet omitted row=" + row);
                for (int column = 0; column < recovered.columns; column++) {
                    int length = recovered.textLengthAt(row, column);
                    if (length == 0 || !"Z".equals(recovered.decodeUtf8(row,
                        recovered.textOffsetAt(row, column), length))) continue;
                    int flags = recovered.flagsAt(row, column);
                    recoveredStyledCell =
                        (flags & GhosttyRenderDelta.CELL_ITALIC) != 0 &&
                        (flags & GhosttyRenderDelta.CELL_UNDERLINE) != 0;
                }
            }
            require(recoveredStyledCell,
                "in-place render recovery lost pre-existing terminal state");
            return "ghostty_render_query=batch-v1" +
                " ghostty_render_utf8=direct" +
                " ghostty_render_cell_metadata=batch-v1" +
                " ghostty_render_color_query=selective" +
                " ghostty_render_packets=snapshot-v1,delta-v3" +
                " ghostty_render_bulk_records=verified" +
                " ghostty_render_semantic_rows=" + semanticDelta.semanticCandidateRows + '/' +
                semanticDelta.semanticSuppressedRows +
                " ghostty_render_complex_graphemes=verified" +
                " ghostty_render_colors=foreground,background" +
                " ghostty_render_recovery=in-place" +
                " ghostty_render_library=" + sanitizeEvidence(libraryInfo);
        } finally {
            backend.close();
        }
    }

    private static String sanitizeEvidence(String value) {
        return value == null ? "unknown" : value.trim().replace(' ', '_');
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class ProbeOutput extends TerminalOutput {
        int checksum;
        int bells;
        String title;
        String hostCommand;
        String hostArgument;
        String clipboard;
        final ByteArrayOutputStream written = new ByteArrayOutputStream();

        @Override public void write(byte[] data, int offset, int count) {
            for (int index = 0; index < count; index++) checksum = 31 * checksum + data[offset + index];
            written.write(data, offset, count);
        }
        @Override public void titleChanged(String oldTitle, String newTitle) { title = newTitle; }
        @Override public void onCopyTextToClipboard(String text) { clipboard = text; }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { bells++; }
        @Override public void onColorsChanged() { }
        @Override public void onTerminalHostControlCommand(String command, String argument) {
            hostCommand = command;
            hostArgument = argument;
        }

        String takeWrittenText() {
            String value = new String(written.toByteArray(), StandardCharsets.UTF_8);
            written.reset();
            return value;
        }
    }

    public static final class FullGhosttyEvidence {
        public final long writes;
        public final long bytes;
        public final int columns;
        public final int rows;
        public final int cursorColumn;
        public final int cursorRow;
        public final boolean cursorVisible;
        public final long rowCount;
        public final long cellCount;
        public final long textCellCount;
        public final long graphemeCodepoints;
        public final long contentHash;
        public final long styledCellCount;
        public final long scrollbackRows;
        public final boolean vtProcessingError;
        public final long renderUpdates;
        public final boolean simd;
        public final int optimizeMode;

        FullGhosttyEvidence(GhosttyTerminalBackend.Snapshot snapshot) {
            writes = snapshot.writes;
            bytes = snapshot.bytes;
            columns = snapshot.columns;
            rows = snapshot.rows;
            cursorColumn = snapshot.cursorColumn;
            cursorRow = snapshot.cursorRow;
            cursorVisible = snapshot.cursorVisible;
            rowCount = snapshot.rowCount;
            cellCount = snapshot.cellCount;
            textCellCount = snapshot.textCellCount;
            graphemeCodepoints = snapshot.graphemeCodepoints;
            contentHash = snapshot.contentHash;
            styledCellCount = snapshot.styledCellCount;
            scrollbackRows = snapshot.scrollbackRows;
            vtProcessingError = snapshot.vtProcessingError;
            renderUpdates = snapshot.renderUpdates;
            simd = snapshot.simd;
            optimizeMode = snapshot.optimizeMode;
        }

        public String toEvidenceString() {
            return "ghostty_full_writes=" + writes +
                " ghostty_full_bytes=" + bytes +
                " ghostty_full_grid=" + columns + 'x' + rows +
                " ghostty_full_cursor=" + cursorColumn + ',' + cursorRow +
                " ghostty_full_rows=" + rowCount +
                " ghostty_full_cells=" + cellCount +
                " ghostty_full_text_cells=" + textCellCount +
                " ghostty_full_graphemes=" + graphemeCodepoints +
                " ghostty_full_styled_cells=" + styledCellCount +
                " ghostty_full_scrollback=" + scrollbackRows +
                " ghostty_full_screen_hash=" + Long.toHexString(contentHash) +
                " ghostty_full_vt_error=" + vtProcessingError +
                " ghostty_full_render_updates=" + renderUpdates +
                " ghostty_full_simd=" + simd +
                " ghostty_full_optimize=" + optimizeMode;
        }
    }
}
