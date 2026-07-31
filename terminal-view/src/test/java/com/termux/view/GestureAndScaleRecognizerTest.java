package com.termux.view;

import android.view.MotionEvent;

import org.junit.Assert;
import org.junit.Test;

public class GestureAndScaleRecognizerTest {

    @Test
    public void terminalStreamEventsFinishTwoPointerScale() {
        Assert.assertTrue(GestureAndScaleRecognizer.isTerminalScaleStreamEvent(
            MotionEvent.ACTION_POINTER_UP, 2));
        Assert.assertTrue(GestureAndScaleRecognizer.isTerminalScaleStreamEvent(
            MotionEvent.ACTION_UP, 1));
        Assert.assertTrue(GestureAndScaleRecognizer.isTerminalScaleStreamEvent(
            MotionEvent.ACTION_CANCEL, 2));
    }

    @Test
    public void scaleContinuesWhenTwoPointersRemain() {
        Assert.assertFalse(GestureAndScaleRecognizer.isTerminalScaleStreamEvent(
            MotionEvent.ACTION_POINTER_UP, 3));
        Assert.assertFalse(GestureAndScaleRecognizer.isTerminalScaleStreamEvent(
            MotionEvent.ACTION_MOVE, 2));
    }
}
