package com.termux.view;

import junit.framework.TestCase;

public final class TerminalVulkanRendererTest extends TestCase {

    public void testSingleGlyphShapeCandidateHasNoShapingContext() {
        assertTrue(TerminalVulkanRenderer.isSingleGlyphShapeCandidate("A", 0, 1));
        assertTrue(TerminalVulkanRenderer.isSingleGlyphShapeCandidate("x!y", 1, 2));
        assertTrue(TerminalVulkanRenderer.isSingleGlyphShapeCandidate("界", 0, 1));
        assertTrue(TerminalVulkanRenderer.isSingleGlyphShapeCandidate("─", 0, 1));
        assertFalse(TerminalVulkanRenderer.isSingleGlyphShapeCandidate("fi", 0, 2));
        assertFalse(TerminalVulkanRenderer.isSingleGlyphShapeCandidate("😀", 0, 1));
        assertFalse(TerminalVulkanRenderer.isSingleGlyphShapeCandidate("😀", 0, 2));
        assertFalse(TerminalVulkanRenderer.isSingleGlyphShapeCandidate("", 0, 0));
        assertFalse(TerminalVulkanRenderer.isSingleGlyphShapeCandidate(null, 0, 1));
        assertFalse(TerminalVulkanRenderer.isSingleGlyphShapeCandidate("A", -1, 0));
        assertFalse(TerminalVulkanRenderer.isSingleGlyphShapeCandidate("A", 1, 2));
    }

    public void testLongAsciiRunCandidateIsExactAndBounded() {
        assertTrue(TerminalVulkanRenderer.isLongAsciiRunCandidate(
            "0123456789abcdefg", 0, 17));
        assertTrue(TerminalVulkanRenderer.isLongAsciiRunCandidate(
            "x0123456789abcdefg!", 1, 18));
        assertFalse(TerminalVulkanRenderer.isLongAsciiRunCandidate(
            "0123456789abcdef", 0, 16));
        assertFalse(TerminalVulkanRenderer.isLongAsciiRunCandidate(
            "0123456789abcdef界", 0, 17));
        assertFalse(TerminalVulkanRenderer.isLongAsciiRunCandidate(
            "0123456789abcdef\n", 0, 17));
        assertFalse(TerminalVulkanRenderer.isLongAsciiRunCandidate(null, 0, 17));
        assertFalse(TerminalVulkanRenderer.isLongAsciiRunCandidate("short", -1, 5));
        assertFalse(TerminalVulkanRenderer.isLongAsciiRunCandidate("short", 0, 8));
    }

    public void testLongRunRasterCandidateAcceptsComplexTextButNotControls() {
        assertTrue(TerminalVulkanRenderer.isLongRunRasterCandidate(
            "终端框线─│┌┐0123456789", 0, 18));
        assertTrue(TerminalVulkanRenderer.isLongRunRasterCandidate(
            "emoji😀still-shaped-as-one-run", 0, 30));
        assertFalse(TerminalVulkanRenderer.isLongRunRasterCandidate(
            "0123456789abcdef\n", 0, 17));
        assertFalse(TerminalVulkanRenderer.isLongRunRasterCandidate(
            "0123456789abcdef", 0, 16));
    }

    public void testVisualBoundsOverlapRequiresPositiveArea() {
        assertTrue(TerminalVulkanRenderer.visualBoundsOverlap(
            0f, 0f, 4f, 4f, 3.5f, 1f, 8f, 3f));
        assertFalse(TerminalVulkanRenderer.visualBoundsOverlap(
            0f, 0f, 4f, 4f, 4f, 0f, 8f, 4f));
        assertFalse(TerminalVulkanRenderer.visualBoundsOverlap(
            0f, 0f, 4f, 4f, 1f, 4f, 3f, 8f));
        assertFalse(TerminalVulkanRenderer.visualBoundsOverlap(
            0f, 0f, 0f, 4f, -1f, 1f, 1f, 3f));
    }

    public void testFractionalViewportGeometryIncludesOnlyTheRequiredOverscanRow() {
        assertEquals(-8, TerminalVulkanRenderer.firstPackedRow(-7, -3f));
        assertEquals(13, TerminalVulkanRenderer.lastPackedRowExclusive(-7, 20, -3f));
        assertEquals(-7, TerminalVulkanRenderer.firstPackedRow(-7, 0f));
        assertEquals(13, TerminalVulkanRenderer.lastPackedRowExclusive(-7, 20, 0f));
        assertEquals(-7, TerminalVulkanRenderer.firstPackedRow(-7, 3f));
        assertEquals(14, TerminalVulkanRenderer.lastPackedRowExclusive(-7, 20, 3f));
    }

    public void testPackedVerticesReuseRequiresIdenticalAuthoritativeGeometry() {
        assertTrue(TerminalVulkanRenderer.canReusePackedVertices(
            -7, 14, 19L, 23L, 5L,
            -7, 14, 19L, 23L, 5L));
        assertFalse(TerminalVulkanRenderer.canReusePackedVertices(
            -7, 14, 19L, 23L, 5L,
            -8, 13, 19L, 23L, 5L));
        assertFalse(TerminalVulkanRenderer.canReusePackedVertices(
            -7, 14, 19L, 23L, 5L,
            -7, 14, 20L, 23L, 5L));
        assertFalse(TerminalVulkanRenderer.canReusePackedVertices(
            -7, 14, 19L, 23L, 5L,
            -7, 14, 19L, 24L, 5L));
        assertFalse(TerminalVulkanRenderer.canReusePackedVertices(
            -7, 14, 19L, 23L, 5L,
            -7, 14, 19L, 23L, 6L));
    }

    public void testPersistentRunMaskRowsOnlyRecompileWhenTheirAtlasIsInvalidated() {
        assertFalse(TerminalVulkanRenderer.needsRowCompilation(7L, 7L,
            false, Integer.MIN_VALUE, 11));
        assertFalse(TerminalVulkanRenderer.needsRowCompilation(7L, 7L,
            true, 11, 11));
        assertTrue(TerminalVulkanRenderer.needsRowCompilation(7L, 8L,
            false, Integer.MIN_VALUE, 11));
        assertTrue(TerminalVulkanRenderer.needsRowCompilation(7L, 7L,
            true, 10, 11));
    }
}
