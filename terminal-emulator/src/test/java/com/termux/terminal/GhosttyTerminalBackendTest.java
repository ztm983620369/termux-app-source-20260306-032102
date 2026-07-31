package com.termux.terminal;

import junit.framework.TestCase;

public class GhosttyTerminalBackendTest extends TestCase {

    public void testSnapshotContractMapsCompleteTerminalAndRenderState() {
        long[] values = {
            7, 8192, 120, 40, 17, 23, 1, 2, 40, 4800,
            913, 919, 0x123456789abcdef0L, 81, 1, 144, 0, 3, 1, 3
        };

        GhosttyTerminalBackend.Snapshot snapshot =
            new GhosttyTerminalBackend.Snapshot(values);

        assertEquals(7L, snapshot.writes);
        assertEquals(8192L, snapshot.bytes);
        assertEquals(120, snapshot.columns);
        assertEquals(40, snapshot.rows);
        assertEquals(17, snapshot.cursorColumn);
        assertEquals(23, snapshot.cursorRow);
        assertTrue(snapshot.cursorVisible);
        assertEquals(4800L, snapshot.cellCount);
        assertEquals(919L, snapshot.graphemeCodepoints);
        assertEquals(81L, snapshot.styledCellCount);
        assertEquals(144L, snapshot.scrollbackRows);
        assertFalse(snapshot.vtProcessingError);
        assertTrue(snapshot.simd);
        assertEquals(3, snapshot.optimizeMode);
        assertTrue(snapshot.toEvidenceString().contains("ghostty_full_grid=120x40"));
        assertTrue(snapshot.toEvidenceString().contains("ghostty_full_screen_hash=123456789abcdef0"));
    }

    public void testSnapshotRejectsTruncatedNativeContract() {
        try {
            new GhosttyTerminalBackend.Snapshot(new long[19]);
            fail("Expected truncated snapshot to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Incomplete"));
        }
    }

    public void testStateMapsGhosttyCursorEnumsToTermuxCursorEnums() {
        long[] values = new long[22];
        values[0] = 1;
        values[1] = 80;
        values[2] = 24;

        values[6] = 0; // Ghostty bar.
        assertEquals(TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR,
            new GhosttyTerminalBackend.State(values).cursorStyle);
        values[6] = 1; // Ghostty block.
        assertEquals(TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK,
            new GhosttyTerminalBackend.State(values).cursorStyle);
        values[6] = 2; // Ghostty underline.
        assertEquals(TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE,
            new GhosttyTerminalBackend.State(values).cursorStyle);
        values[6] = 3; // Ghostty hollow block degrades to Termux block.
        assertEquals(TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK,
            new GhosttyTerminalBackend.State(values).cursorStyle);
    }

    public void testPinnedGhosttyScrollbackByteBudgetIsBoundedAndMonotonic() {
        assertEquals(0L, GhosttyTerminalBackend.scrollbackBytesForRows(80, 24, 0));
        long small = GhosttyTerminalBackend.scrollbackBytesForRows(80, 24, 100);
        long large = GhosttyTerminalBackend.scrollbackBytesForRows(160, 48, 2000);
        assertTrue(small > 0L);
        assertTrue(large > small);
        assertEquals(512L * 1024L * 1024L,
            GhosttyTerminalBackend.scrollbackBytesForRows(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

}
