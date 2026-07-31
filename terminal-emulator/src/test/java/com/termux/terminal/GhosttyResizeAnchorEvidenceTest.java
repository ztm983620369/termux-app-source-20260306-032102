package com.termux.terminal;

import junit.framework.TestCase;

public final class GhosttyResizeAnchorEvidenceTest extends TestCase {

    public void testAcceptsExactInteriorCommit() {
        long[] values = evidence(1, 930, 800, 1000, 930, 20, 950, -70);
        assertTrue(GhosttyTerminalBackend.resizeAnchorCommitValid(values));
    }

    public void testAcceptsOnlyProvenBoundaryClamp() {
        long[] oldestClamp = evidence(2, 790, 800, 1000, 800, 10, 810, -200);
        assertTrue(GhosttyTerminalBackend.resizeAnchorCommitValid(oldestClamp));

        long[] liveClamp = evidence(2, 1010, 800, 1000, 1000, 20, 1020, 0);
        assertTrue(GhosttyTerminalBackend.resizeAnchorCommitValid(liveClamp));

        long[] fakeClamp = evidence(2, 930, 800, 1000, 930, 20, 950, -70);
        assertFalse(GhosttyTerminalBackend.resizeAnchorCommitValid(fakeClamp));
    }

    public void testRejectsBrokenPreconditionAndReadback() {
        long[] values = evidence(1, 930, 800, 1000, 930, 20, 950, -70);
        values[12] = 0;
        assertFalse(GhosttyTerminalBackend.resizeAnchorCommitValid(values));

        values[12] = 1;
        values[19] = 929;
        assertFalse(GhosttyTerminalBackend.resizeAnchorCommitValid(values));
    }

    private static long[] evidence(int outcome, long requested, long minimum, long maximum,
                                   long committed, long resolved, long screen, long top) {
        long[] values = new long[20];
        values[0] = 1;
        values[1] = 1;
        values[2] = outcome;
        values[5] = 20;
        values[6] = resolved;
        values[7] = committed;
        values[12] = 1;
        values[13] = 1000;
        values[14] = screen;
        values[15] = requested;
        values[16] = minimum;
        values[17] = maximum;
        values[18] = top;
        values[19] = committed;
        return values;
    }
}
