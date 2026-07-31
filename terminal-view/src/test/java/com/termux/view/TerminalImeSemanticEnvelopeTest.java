package com.termux.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TerminalImeSemanticEnvelopeTest {

    @Test
    public void ordinaryPrimaryScreenDoesNotPromoteOutputBelowCursor() {
        assertEquals(20, TerminalImeSemanticEnvelope.resolveProtectedBottomScreenRow(
            false, false, 20, 43, 44));
    }

    @Test
    public void inlinePrimaryTuiProtectsCommittedFooterBelowCursor() {
        assertEquals(43, TerminalImeSemanticEnvelope.resolveProtectedBottomScreenRow(
            false, true, 20, 43, 44));
    }

    @Test
    public void alternateScreenMayProtectCommittedFooterBelowCursor() {
        assertEquals(43, TerminalImeSemanticEnvelope.resolveProtectedBottomScreenRow(
            true, false, 20, 43, 44));
    }

    @Test
    public void invalidAlternateTailFallsBackToCursor() {
        assertEquals(20, TerminalImeSemanticEnvelope.resolveProtectedBottomScreenRow(
            true, false, 20, 44, 44));
        assertEquals(20, TerminalImeSemanticEnvelope.resolveProtectedBottomScreenRow(
            true, false, 20, 19, 44));
    }
}
