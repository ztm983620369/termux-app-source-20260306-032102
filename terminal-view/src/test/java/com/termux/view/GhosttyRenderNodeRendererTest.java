package com.termux.view;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class GhosttyRenderNodeRendererTest extends TestCase {

    public void testUtf8ByteBoundariesMapToUtf16Ranges() {
        byte[] utf8 = "Ae\u0301界🙂".getBytes(StandardCharsets.UTF_8);
        int[] mapping = new int[utf8.length + 1];
        GhosttyRenderNodeRenderer.buildUtf8CharIndex(utf8, utf8.length, mapping);

        assertEquals(0, mapping[0]);
        assertEquals(1, mapping[1]);
        assertEquals(2, mapping[2]);
        assertEquals(3, mapping[4]);
        assertEquals(4, mapping[7]);
        assertEquals(6, mapping[11]);
        assertEquals(-1, mapping[3]);
        assertEquals(-1, mapping[8]);
    }

    public void testPrintableAsciiFastPathRejectsControlsAndUnicode() {
        byte[] ascii = "terminal-0123".getBytes(StandardCharsets.US_ASCII);
        assertTrue(GhosttyRenderNodeRenderer.isPrintableAscii(ascii, 0, ascii.length));

        byte[] control = "a\nb".getBytes(StandardCharsets.US_ASCII);
        assertFalse(GhosttyRenderNodeRenderer.isPrintableAscii(control, 0, control.length));

        byte[] unicode = "界".getBytes(StandardCharsets.UTF_8);
        assertFalse(GhosttyRenderNodeRenderer.isPrintableAscii(unicode, 0, unicode.length));

        String wrapped = "xxterminal-0123yy";
        assertTrue(GhosttyRenderNodeRenderer.isPrintableAscii(
            wrapped, 2, wrapped.length() - 2));
        assertFalse(GhosttyRenderNodeRenderer.isPrintableAscii("a\nb", 0, 3));
        assertFalse(GhosttyRenderNodeRenderer.isPrintableAscii("界", 0, 1));
        assertFalse(GhosttyRenderNodeRenderer.isPrintableAscii(wrapped, -1, 2));
    }

    public void testSpaceOnlyTextRunCanSkipGlyphSubmission() {
        byte[] spaces = "     ".getBytes(StandardCharsets.US_ASCII);
        assertFalse(GhosttyRenderNodeRenderer.containsNonSpaceUtf8(
            spaces, 0, spaces.length));

        byte[] visible = "  x  ".getBytes(StandardCharsets.US_ASCII);
        assertTrue(GhosttyRenderNodeRenderer.containsNonSpaceUtf8(
            visible, 0, visible.length));

        byte[] ideographicSpace = "　".getBytes(StandardCharsets.UTF_8);
        assertTrue(GhosttyRenderNodeRenderer.containsNonSpaceUtf8(
            ideographicSpace, 0, ideographicSpace.length));
    }

    public void testUtf8ClassificationCombinesSpaceAndAsciiChecks() {
        byte[] ascii = " A9 ".getBytes(StandardCharsets.US_ASCII);
        int classification = GhosttyRenderNodeRenderer.classifyUtf8(ascii, 0, ascii.length);
        assertTrue((classification & 1) != 0);
        assertTrue((classification & 2) != 0);

        byte[] spaces = "   ".getBytes(StandardCharsets.US_ASCII);
        classification = GhosttyRenderNodeRenderer.classifyUtf8(spaces, 0, spaces.length);
        assertEquals(2, classification);

        byte[] unicode = "界".getBytes(StandardCharsets.UTF_8);
        classification = GhosttyRenderNodeRenderer.classifyUtf8(unicode, 0, unicode.length);
        assertTrue((classification & 1) != 0);
        assertEquals(0, classification & 2);
    }

    public void testInvalidUtf8ContinuationFailsClosed() {
        byte[] invalid = {(byte) 0xe4, 0x41, (byte) 0x8c};
        int[] mapping = new int[invalid.length + 1];
        Arrays.fill(mapping, 99);
        try {
            GhosttyRenderNodeRenderer.buildUtf8CharIndex(invalid, invalid.length, mapping);
            fail("Expected invalid UTF-8 continuation to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("continuation"));
        }
    }

    public void testGlyphCachePreparationRequiresBoundedLowChurnPacket() {
        assertTrue(GhosttyRenderNodeRenderer.shouldPrepareGlyphCache(1, 120));
        assertTrue(GhosttyRenderNodeRenderer.shouldPrepareGlyphCache(8, 40));
        assertTrue(GhosttyRenderNodeRenderer.shouldPrepareGlyphCache(8, 8));
        assertFalse(GhosttyRenderNodeRenderer.shouldPrepareGlyphCache(1, 120, true));
        assertTrue(GhosttyRenderNodeRenderer.shouldPrepareGlyphCache(1, 120, false));
        assertFalse(GhosttyRenderNodeRenderer.shouldPrepareGlyphCache(
            1, 120, false, true));
        assertTrue(GhosttyRenderNodeRenderer.shouldPrepareGlyphCache(
            1, 120, false, false));

        assertFalse(GhosttyRenderNodeRenderer.shouldPrepareGlyphCache(9, 120));
        assertFalse(GhosttyRenderNodeRenderer.shouldPrepareGlyphCache(20, 40));
        assertFalse(GhosttyRenderNodeRenderer.shouldPrepareGlyphCache(120, 120));
        assertFalse(GhosttyRenderNodeRenderer.shouldPrepareGlyphCache(0, 120));
        assertFalse(GhosttyRenderNodeRenderer.shouldPrepareGlyphCache(1, 0));
    }

    public void testShortGlyphRunsStayOnLowerSetupCostStringPath() {
        assertFalse(GhosttyRenderNodeRenderer.shouldUseGlyphFastPath(0));
        assertFalse(GhosttyRenderNodeRenderer.shouldUseGlyphFastPath(7));
        assertTrue(GhosttyRenderNodeRenderer.shouldUseGlyphFastPath(8));
        assertTrue(GhosttyRenderNodeRenderer.shouldUseGlyphFastPath(128));
    }

    public void testOrderedGlyphBatchRequiresUnscaledPreparedRun() {
        assertFalse(GhosttyRenderNodeRenderer.shouldBatchGlyphCommand(7, 80f, 80f));
        assertTrue(GhosttyRenderNodeRenderer.shouldBatchGlyphCommand(8, 80f, 80f));
        assertTrue(GhosttyRenderNodeRenderer.shouldBatchGlyphCommand(128, 0f, 80f));
        assertTrue(GhosttyRenderNodeRenderer.shouldBatchGlyphCommand(8, 80.005f, 80f));
        assertFalse(GhosttyRenderNodeRenderer.shouldBatchGlyphCommand(8, 80.02f, 80f));
    }

    public void testGlyphShapeCacheHashUsesOnlyRequestedRange() {
        int plain = GhosttyRenderNodeRenderer.hashGlyphShapeText("terminal-0123", 0, 13);
        int wrapped = GhosttyRenderNodeRenderer.hashGlyphShapeText("xxterminal-0123yy", 2, 15);
        assertEquals(plain, wrapped);
        assertFalse(plain == GhosttyRenderNodeRenderer.hashGlyphShapeText(
            "terminal-0124", 0, 13));
        try {
            GhosttyRenderNodeRenderer.hashGlyphShapeText("abc", -1, 2);
            fail("Expected invalid glyph cache range to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("range"));
        }
    }

    public void testRetainedRowSourceReuseRequiresExactNormalizedPacketContent() {
        GhosttyRenderNodeRenderer.RowDisplay row =
            new GhosttyRenderNodeRenderer.RowDisplay();
        int[] records = {
            1, 4, 7, 10, 100, 1,
            2, 5, 8, 11, 101, 1,
            3, 6, 9, 12, 102, 1,
        };
        byte[] utf8 = "abc".getBytes(StandardCharsets.UTF_8);
        row.captureSource(3, 0xff000000, false, 0, 3,
            false, 0, 0, 0, 0, records, 100, utf8, utf8.length);

        int[] relocated = records.clone();
        relocated[4] = 900;
        relocated[10] = 901;
        relocated[16] = 902;
        assertTrue(row.matchesSource(3, 0xff000000, false, 0, 3,
            false, 0, 0, 0, 0, relocated, 900, utf8, utf8.length));

        int[] changedFlags = relocated.clone();
        changedFlags[9]++;
        assertFalse(row.matchesSource(3, 0xff000000, false, 0, 3,
            false, 0, 0, 0, 0, changedFlags, 900, utf8, utf8.length));
        assertFalse(row.matchesSource(3, 0xff000000, false, 0, 3,
            true, 1, 2, 1, 0xffffffff, relocated, 900, utf8, utf8.length));

        byte[] changedUtf8 = utf8.clone();
        changedUtf8[2] = 'd';
        assertFalse(row.matchesSource(3, 0xff000000, false, 0, 3,
            false, 0, 0, 0, 0, relocated, 900, changedUtf8, changedUtf8.length));
    }

}
