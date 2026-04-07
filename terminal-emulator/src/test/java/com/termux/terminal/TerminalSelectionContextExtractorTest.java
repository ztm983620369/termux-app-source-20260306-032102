package com.termux.terminal;

public class TerminalSelectionContextExtractorTest extends TerminalTestCase {

    public void testSelectionContextKeepsWrappedLogicalLine() throws Exception {
        withTerminalSized(5, 3).enterString("ABCDEFGHIJ");

        TerminalSelectionContext context = TerminalSelectionContextExtractor.extractSelectionContext(
            mTerminal.getScreen(), 3, 0, 4, 0, 0
        );

        assertEquals("ABCDEFGHIJ", context.getText());
        assertEquals("DE", context.getSelectedText());
    }

    public void testSelectionContextPreservesExplicitNewline() throws Exception {
        withTerminalSized(5, 3).enterString("ABC\r\nFGHIJ");

        TerminalSelectionContext context = TerminalSelectionContextExtractor.extractSelectionContext(
            mTerminal.getScreen(), 1, 0, 1, 1, 0
        );

        assertEquals("ABC\nFGHIJ", context.getText());
        assertEquals("BC\nFG", context.getSelectedText());
    }

    public void testSelectionContextIncludesPaddingRows() throws Exception {
        withTerminalSized(5, 4).enterString("AAAAA\r\nBBBBB\r\nCCCCC");

        TerminalSelectionContext context = TerminalSelectionContextExtractor.extractSelectionContext(
            mTerminal.getScreen(), 1, 1, 3, 1, 1
        );

        assertEquals("AAAAABBBBBCCCCC", context.getText());
        assertEquals("BBB", context.getSelectedText());
    }
}
