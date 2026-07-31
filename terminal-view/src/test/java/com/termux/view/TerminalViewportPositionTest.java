package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class TerminalViewportPositionTest {

    @Test
    public void movingTowardLiveEdgeUsesNegativeTopOverscan() {
        TerminalViewportPosition.Result result = new TerminalViewportPosition.Result();

        TerminalViewportPosition.resolve(-245f, 100, 24f,
            -264f, -11, 0f, result);

        assertEquals(-10, result.topRow);
        assertEquals(-5f, result.pixelOffset, 0f);
        assertEquals(-245f, result.topRow * 24f + result.pixelOffset, 0f);
    }

    @Test
    public void movingTowardHistoryUsesPositiveBottomOverscan() {
        TerminalViewportPosition.Result result = new TerminalViewportPosition.Result();

        TerminalViewportPosition.resolve(-251f, 100, 24f,
            -240f, -10, 0f, result);

        assertEquals(-11, result.topRow);
        assertEquals(13f, result.pixelOffset, 0f);
        assertEquals(-251f, result.topRow * 24f + result.pixelOffset, 0f);
    }

    @Test
    public void equalPositionKeepsSignedRepresentation() {
        TerminalViewportPosition.Result result = new TerminalViewportPosition.Result();

        TerminalViewportPosition.resolve(-245f, 100, 24f,
            -245f, -10, -5f, result);

        assertEquals(-10, result.topRow);
        assertEquals(-5f, result.pixelOffset, 0f);
    }

    @Test
    public void boundsClampToTranscriptAndLiveEdge() {
        TerminalViewportPosition.Result result = new TerminalViewportPosition.Result();

        TerminalViewportPosition.resolve(-9999f, 12, 20f,
            -100f, -5, 0f, result);
        assertEquals(-12, result.topRow);
        assertEquals(0f, result.pixelOffset, 0f);

        TerminalViewportPosition.resolve(20f, 12, 20f,
            -100f, -5, 0f, result);
        assertEquals(0, result.topRow);
        assertEquals(0f, result.pixelOffset, 0f);
    }
}
