package com.termux.view;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.Collections;

public final class TerminalGpuFrameTest extends TestCase {

    public void testFrameDeepCopiesCommandListsBeforeCrossThreadPublication() {
        ArrayList<TerminalGpuFrame.Rect> backgrounds = new ArrayList<>();
        backgrounds.add(new TerminalGpuFrame.Rect(0f, 0f, 20f, 10f, 0xff102030));
        ArrayList<TerminalGpuFrame.TextRun> text = new ArrayList<>();
        text.add(new TerminalGpuFrame.TextRun("abc", 0, 3, 0f, 24f, 24f,
            0xfff0f0f0, false, false));
        ArrayList<TerminalGpuFrame.Row> rows = new ArrayList<>();
        rows.add(new TerminalGpuFrame.Row(-3, backgrounds, text, Collections.emptyList()));

        TerminalGpuFrame frame = new TerminalGpuFrame(7L, 11L, 13L, 1080, 1920,
            18, null, 9f, 20, -15, 0xff000000, 1, -3, 0f,
            true, true, rows);

        rows.clear();
        backgrounds.clear();
        text.clear();
        assertEquals(1, frame.rows.size());
        assertEquals(1, frame.rows.get(0).backgrounds.size());
        assertEquals(1, frame.rows.get(0).text.size());
        try {
            frame.rows.clear();
            fail("GPU frame rows must be immutable");
        } catch (UnsupportedOperationException expected) {
        }
        try {
            frame.rows.get(0).text.clear();
            fail("GPU text commands must be immutable");
        } catch (UnsupportedOperationException expected) {
        }
    }

    public void testIncompleteFrameCannotPublishTerminalContent() {
        TerminalGpuFrame frame = TerminalGpuFrame.incomplete(9L, 0, 0, -5);
        assertFalse(frame.contentReady);
        assertTrue(frame.fullFrame);
        assertEquals(1, frame.viewWidth);
        assertEquals(1, frame.viewHeight);
        assertTrue(frame.rows.isEmpty());
    }

    public void testCursorMetadataIsPartOfTheImmutablePresentedFrame() {
        TerminalGpuFrame frame = new TerminalGpuFrame(17L, 19L, 23L, 1080, 1920,
            18, null, 9f, 20, -15, 0xff000000, 40, 0, 0f,
            true, true, Collections.emptyList(), 31, 7, 5, true, false, 38);

        assertEquals(31, frame.cursorRow);
        assertEquals(7, frame.cursorColumn);
        assertEquals(5, frame.cursorStyle);
        assertTrue(frame.cursorEnabled);
        assertFalse(frame.cursorVisible);
        assertEquals(38, frame.imeProtectedBottomScreenRow);
    }

    public void testRowContentEqualityUsesExactCommandsAfterHashPrefilter() {
        TerminalGpuFrame.Row first = new TerminalGpuFrame.Row(-4,
            Collections.singletonList(new TerminalGpuFrame.Rect(0f, 1f, 10f, 11f,
                0xff102030)),
            Collections.singletonList(new TerminalGpuFrame.TextRun("prefix-hello-suffix", 7, 12,
                2f, 40f, 39.5f, 0xffabcdef, true, false)),
            Collections.singletonList(new TerminalGpuFrame.Rect(2f, 9f, 42f, 10f,
                0xff556677)));
        TerminalGpuFrame.Row sameVisibleCommands = new TerminalGpuFrame.Row(-4,
            Collections.singletonList(new TerminalGpuFrame.Rect(0f, 1f, 10f, 11f,
                0xff102030)),
            Collections.singletonList(new TerminalGpuFrame.TextRun("hello", 0, 5,
                2f, 40f, 39.5f, 0xffabcdef, true, false)),
            Collections.singletonList(new TerminalGpuFrame.Rect(2f, 9f, 42f, 10f,
                0xff556677)));
        TerminalGpuFrame.Row changedColor = new TerminalGpuFrame.Row(-4,
            sameVisibleCommands.backgrounds,
            Collections.singletonList(new TerminalGpuFrame.TextRun("hello", 0, 5,
                2f, 40f, 39.5f, 0xffabcdee, true, false)),
            sameVisibleCommands.decorations);

        assertTrue(first.hasSameContent(sameVisibleCommands));
        assertTrue(sameVisibleCommands.hasSameContent(first));
        assertFalse(first.hasSameContent(changedColor));
    }
}
