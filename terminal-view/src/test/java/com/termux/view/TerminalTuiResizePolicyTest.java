package com.termux.view;

import org.junit.Assert;
import org.junit.Test;

public class TerminalTuiResizePolicyTest {

    @Test
    public void onlyLivePrimaryInlineTuiPinsTheViewport() {
        Assert.assertTrue(TerminalTuiResizePolicy.shouldPinLiveEdge(
            true, false, false, true, false, false, true));
        Assert.assertFalse(TerminalTuiResizePolicy.shouldPinLiveEdge(
            false, false, false, true, false, false, true));
        Assert.assertFalse(TerminalTuiResizePolicy.shouldPinLiveEdge(
            true, false, false, false, false, false, true));
        Assert.assertFalse(TerminalTuiResizePolicy.shouldPinLiveEdge(
            true, true, true, true, true, true, false));
    }

    @Test
    public void allStandardInteractiveModesIdentifyInlineTui() {
        Assert.assertTrue(TerminalTuiResizePolicy.shouldPinLiveEdge(
            true, false, true, false, false, false, true));
        Assert.assertTrue(TerminalTuiResizePolicy.shouldPinLiveEdge(
            true, false, false, false, true, false, true));
        Assert.assertTrue(TerminalTuiResizePolicy.shouldPinLiveEdge(
            true, false, false, false, false, true, true));
        Assert.assertTrue(TerminalTuiResizePolicy.shouldPinLiveEdge(
            true, false, false, false, false, false, false));
    }

    @Test
    public void inlinePrimaryClassificationIsReusableOutsideResize() {
        Assert.assertTrue(TerminalTuiResizePolicy.isInlinePrimaryScreen(
            false, false, true, false, false, true));
        Assert.assertFalse(TerminalTuiResizePolicy.isInlinePrimaryScreen(
            false, false, false, false, false, true));
        Assert.assertFalse(TerminalTuiResizePolicy.isInlinePrimaryScreen(
            true, true, true, true, true, false));
    }
}
