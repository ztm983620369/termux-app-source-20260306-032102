package com.termux.terminal;

import junit.framework.TestCase;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class TerminalCompatibilityCheckpointTest extends TestCase {

    private static final int CELL_WIDTH = TerminalTestCase.INITIAL_CELL_WIDTH_PIXELS;
    private static final int CELL_HEIGHT = TerminalTestCase.INITIAL_CELL_HEIGHT_PIXELS;

    public void testSmallHealthyStreamRemainsDiskLazyWithoutJavaWorker() {
        TerminalEmulator owner = new TerminalEmulator(new NoopOutput(), 24, 8,
            CELL_WIDTH, CELL_HEIGHT, 120, null);
        byte[] bytes = "healthy-ghostty-stream".getBytes(StandardCharsets.UTF_8);
        try {
            owner.recordCompatibilityBytesForTesting(bytes);
            String diagnostics = owner.getCompatibilityCheckpointStatusForDiagnostics();
            assertTrue(diagnostics, diagnostics.contains("mode=disk-foreground-idle"));
            assertTrue(diagnostics, diagnostics.contains("activeBytes=" + bytes.length));
            assertTrue(diagnostics, diagnostics.contains("activeJournal=true"));
            assertTrue(diagnostics, diagnostics.contains("batches=0"));
            assertTrue(diagnostics, diagnostics.contains("generation=0/0"));
            assertTrue(diagnostics, diagnostics.contains("thread=none#-1"));
        } finally {
            owner.releaseNativeResources();
        }
    }

    public void testOrderedCheckpointBatchesReplayOffCallerThread() {
        TerminalOutput output = new NoopOutput();
        TerminalEmulator owner = new TerminalEmulator(output, 24, 8,
            CELL_WIDTH, CELL_HEIGHT, 120, null);
        TerminalEmulator reference = new TerminalEmulator(output, 24, 8,
            CELL_WIDTH, CELL_HEIGHT, 120, null);
        byte[] first = "first-line\r\n\033[31mred\033[0m".getBytes(StandardCharsets.UTF_8);
        byte[] second = "\r\nsecond-line\r\nfinal".getBytes(StandardCharsets.UTF_8);

        try {
            owner.recordCompatibilityBytesForTesting(first);
            owner.sealCompatibilityCheckpointForTesting();
            owner.recordCompatibilityResizeForTesting(32, 10, CELL_WIDTH, CELL_HEIGHT);
            owner.recordCompatibilityBytesForTesting(second);

            reference.append(first, first.length);
            reference.resize(32, 10, CELL_WIDTH, CELL_HEIGHT);
            reference.append(second, second.length);

            TerminalEmulator checkpoint = owner.awaitCompatibilityCheckpointForTesting();
            assertEquals(reference.mColumns, checkpoint.mColumns);
            assertEquals(reference.mRows, checkpoint.mRows);
            assertEquals(reference.getCursorCol(), checkpoint.getCursorCol());
            assertEquals(reference.getCursorRow(), checkpoint.getCursorRow());
            assertEquals(reference.getTranscriptTextWithoutJoinedLines(),
                checkpoint.getTranscriptTextWithoutJoinedLines());

            String diagnostics = owner.getCompatibilityCheckpointStatusForDiagnostics();
            assertTrue(diagnostics, diagnostics.contains("generation=2/2"));
            assertTrue(diagnostics, diagnostics.contains("queued=0"));
            assertTrue(diagnostics, diagnostics.contains("thread=TermuxCompatibilityCheckpoint#"));
            assertFalse(diagnostics, diagnostics.contains(
                "thread=" + Thread.currentThread().getName() + '#' +
                    Thread.currentThread().getId()));
        } finally {
            owner.releaseNativeResources();
            reference.releaseNativeResources();
        }
    }

    public void testScrollAndFullRedrawSignalsAreConsumedExactlyOnce() {
        TerminalEmulator emulator = new TerminalEmulator(new NoopOutput(), 24, 8,
            CELL_WIDTH, CELL_HEIGHT, 120, null);
        try {
            assertTrue(emulator.consumeFullRedrawRequired());
            assertFalse(emulator.consumeFullRedrawRequired());

            byte[] lines = "a\r\nb\r\nc\r\nd\r\ne\r\nf\r\ng\r\nh\r\ni\r\n"
                .getBytes(StandardCharsets.UTF_8);
            emulator.appendByteWiseForTesting(lines, lines.length);
            int consumed = emulator.consumeScrollCounter();
            assertTrue(consumed > 0);
            assertEquals(0, emulator.consumeScrollCounter());
        } finally {
            emulator.releaseNativeResources();
        }
    }

    public void testGhosttyAuthoritySkipsOnlyTheDormantOwnerJavaReflow() {
        assertFalse(TerminalEmulator.shouldReflowDormantJavaScreen(true));
        assertTrue(TerminalEmulator.shouldReflowDormantJavaScreen(false));
    }

    public void testExactGeometryDedupeIncludesCellMetrics() {
        TerminalEmulator emulator = new TerminalEmulator(new NoopOutput(), 24, 8,
            CELL_WIDTH, CELL_HEIGHT, 120, null);
        try {
            assertTrue(emulator.hasExactGeometry(24, 8, CELL_WIDTH, CELL_HEIGHT));
            assertFalse(emulator.hasExactGeometry(25, 8, CELL_WIDTH, CELL_HEIGHT));
            assertFalse(emulator.hasExactGeometry(24, 9, CELL_WIDTH, CELL_HEIGHT));
            assertFalse(emulator.hasExactGeometry(24, 8, CELL_WIDTH + 1, CELL_HEIGHT));
            assertFalse(emulator.hasExactGeometry(24, 8, CELL_WIDTH, CELL_HEIGHT + 1));
        } finally {
            emulator.releaseNativeResources();
        }
    }

    public void testStaleJournalCleanupIsExactlyScoped() throws Exception {
        File directory = new File(System.getProperty("java.io.tmpdir", "."),
            "termux-journal-cleanup-" + System.nanoTime());
        assertTrue(directory.mkdirs());
        File stale = new File(directory, "termux-ghostty-crash.vtj");
        File unrelated = new File(directory, "terminal-transcript.txt");
        try {
            try (FileOutputStream output = new FileOutputStream(stale)) {
                output.write(new byte[] {'T', 'V', 'J', '1'});
            }
            try (FileOutputStream output = new FileOutputStream(unrelated)) {
                output.write(7);
            }

            TerminalEmulator.CompatibilityJournalCleanupResult result =
                TerminalEmulator.cleanupStaleCompatibilityJournals(directory);
            assertEquals(1, result.deletedFiles);
            assertEquals(4L, result.deletedBytes);
            assertFalse(stale.exists());
            assertTrue(unrelated.exists());
        } finally {
            stale.delete();
            unrelated.delete();
            directory.delete();
        }
    }

    public void testRemovedCompletedSessionDisposesItsCompatibilityJournal() {
        TerminalSession session = new TerminalSession(
            null, null, null, null, null, null);
        TerminalEmulator emulator = new TerminalEmulator(new NoopOutput(), 24, 8,
            CELL_WIDTH, CELL_HEIGHT, 120, null);
        session.mEmulator = emulator;
        try {
            emulator.recordCompatibilityBytesForTesting(
                "dispose-me".getBytes(StandardCharsets.UTF_8));
            assertTrue(emulator.getCompatibilityCheckpointStatusForDiagnostics()
                .contains("activeJournal=true"));

            session.dispose();

            assertTrue(emulator.getCompatibilityCheckpointStatusForDiagnostics()
                .contains("activeJournal=false"));
        } finally {
            emulator.releaseNativeResources();
        }
    }

    private static final class NoopOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) {}
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
        @Override public void onTerminalHostControlCommand(String command, String argument) {}
    }
}
