package com.termux.terminal;

public class ScreenBufferTest extends TerminalTestCase {

	public void testBasics() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		assertEquals("", screen.getTranscriptText());
		screen.setChar(0, 0, 'a', 0);
		assertEquals("a", screen.getTranscriptText());
		screen.setChar(0, 0, 'b', 0);
		assertEquals("b", screen.getTranscriptText());
		screen.setChar(2, 0, 'c', 0);
		assertEquals("b c", screen.getTranscriptText());
		screen.setChar(2, 2, 'f', 0);
		assertEquals("b c\n\n  f", screen.getTranscriptText());
		screen.blockSet(0, 0, 2, 2, 'X', 0);
	}

	public void testBlockSet() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		screen.blockSet(0, 0, 2, 2, 'X', 0);
		assertEquals("XX\nXX", screen.getTranscriptText());
		screen.blockSet(1, 1, 2, 2, 'Y', 0);
		assertEquals("XX\nXYY\n YY", screen.getTranscriptText());
	}

	public void testGetSelectedText() {
		withTerminalSized(5, 3).enterString("ABCDEFGHIJ").assertLinesAre("ABCDE", "FGHIJ", "     ");
		assertEquals("AB", mTerminal.getSelectedText(0, 0, 1, 0));
		assertEquals("BC", mTerminal.getSelectedText(1, 0, 2, 0));
		assertEquals("CDE", mTerminal.getSelectedText(2, 0, 4, 0));
		assertEquals("FG", mTerminal.getSelectedText(0, 1, 1, 1));
		assertEquals("GH", mTerminal.getSelectedText(1, 1, 2, 1));
		assertEquals("HIJ", mTerminal.getSelectedText(2, 1, 4, 1));

		assertEquals("ABCDEFG", mTerminal.getSelectedText(0, 0, 1, 1));
		withTerminalSized(5, 3).enterString("ABCDE\r\nFGHIJ").assertLinesAre("ABCDE", "FGHIJ", "     ");
		assertEquals("ABCDE\nFG", mTerminal.getSelectedText(0, 0, 1, 1));
	}

	public void testGetSelectedTextJoinFullLines() {
		withTerminalSized(5, 3).enterString("ABCDE\r\nFG");
		assertEquals("ABCDEFG", mTerminal.getScreen().getSelectedText(0, 0, 1, 1, true, true));

		withTerminalSized(5, 3).enterString("ABC\r\nFG");
		assertEquals("ABC\nFG", mTerminal.getScreen().getSelectedText(0, 0, 1, 1, true, true));
	}

	public void testGetWordAtLocation() {
		withTerminalSized(5, 3).enterString("ABCDEFGHIJ\r\nKLMNO");
		assertEquals("ABCDEFGHIJKLMNO", mTerminal.getScreen().getWordAtLocation(0, 0));
		assertEquals("ABCDEFGHIJKLMNO", mTerminal.getScreen().getWordAtLocation(4, 1));
		assertEquals("ABCDEFGHIJKLMNO", mTerminal.getScreen().getWordAtLocation(4, 2));

		withTerminalSized(5, 3).enterString("ABC DEF GHI ");
		assertEquals("ABC", mTerminal.getScreen().getWordAtLocation(0, 0));
		assertEquals("", mTerminal.getScreen().getWordAtLocation(3, 0));
		assertEquals("DEF", mTerminal.getScreen().getWordAtLocation(4, 0));
		assertEquals("DEF", mTerminal.getScreen().getWordAtLocation(0, 1));
		assertEquals("DEF", mTerminal.getScreen().getWordAtLocation(1, 1));
		assertEquals("GHI", mTerminal.getScreen().getWordAtLocation(0, 2));
		assertEquals("", mTerminal.getScreen().getWordAtLocation(1, 2));
		assertEquals("", mTerminal.getScreen().getWordAtLocation(2, 2));

		// Transcript row support (negative y).
		withTerminalSized(10, 3).enterString("ONE\r\nTWO\r\nTHREE\r\nFOUR");
		assertEquals("ONE", mTerminal.getScreen().getWordAtLocation(0, -1));
		assertEquals("TWO", mTerminal.getScreen().getWordAtLocation(0, 0));
	}

	public void testBlockSetPartialClearPreservesStyles() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);

		long styleA = 111L;
		long styleB = 222L;
		screen.blockSet(0, 0, 5, 1, 'A', styleA);
		screen.blockSet(2, 0, 2, 1, ' ', styleB);

		assertEquals("AA  A", screen.getTranscriptText());

		assertEquals(styleA, screen.getStyleAt(0, 0));
		assertEquals(styleA, screen.getStyleAt(0, 1));
		assertEquals(styleB, screen.getStyleAt(0, 2));
		assertEquals(styleB, screen.getStyleAt(0, 3));
		assertEquals(styleA, screen.getStyleAt(0, 4));
	}

	public void testIdenticalSimpleWritesDoNotMarkRowsDirty() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		screen.clearDirtyRows();

		screen.setChar(0, 0, ' ', TextStyle.NORMAL);
		assertFalse(screen.hasDirtyRows());

		byte[] unchanged = new byte[]{' ', ' ', ' '};
		assertTrue(screen.setAsciiRunIfSimple(1, 1, unchanged, 0, unchanged.length, TextStyle.NORMAL));
		assertFalse(screen.hasDirtyRows());

		byte[] changed = new byte[]{'a', 'b', 'c'};
		assertTrue(screen.setAsciiRunIfSimple(1, 1, changed, 0, changed.length, TextStyle.NORMAL));
		assertTrue(screen.hasDirtyRows());
		assertEquals(1, screen.getDirtyStartRow());
		assertEquals(2, screen.getDirtyEndRow());
	}

	public void testContentRevisionChangesOnlyWhenTheBufferIsMarkedOrWrapStateChanges() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		long initial = screen.getContentRevision();

		screen.setChar(0, 0, ' ', TextStyle.NORMAL);
		assertEquals(initial, screen.getContentRevision());

		screen.setChar(0, 0, 'x', TextStyle.NORMAL);
		long afterText = screen.getContentRevision();
		assertTrue(afterText > initial);

		screen.setLineWrap(0);
		long afterWrap = screen.getContentRevision();
		assertTrue(afterWrap > afterText);
		screen.setLineWrap(0);
		assertEquals(afterWrap, screen.getContentRevision());

		screen.clearLineWrap(0);
		assertTrue(screen.getContentRevision() > afterWrap);
	}

	public void testHyperlinkOnlyChangesInvalidateAndParticipateInSynchronizedOutput() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		screen.setChar(0, 0, 'x', TextStyle.NORMAL, "https://one.example/path");
		screen.clearDirtyRows();
		long before = screen.getContentRevision();

		screen.setChar(0, 0, 'x', TextStyle.NORMAL, "https://two.example/path");
		assertTrue(screen.hasDirtyRows());
		assertTrue(screen.getContentRevision() > before);
		assertEquals("https://two.example/path", screen.getHyperlinkAt(0, 0));

		screen.clearDirtyRows();
		screen.beginSynchronizedOutput();
		screen.setChar(0, 0, 'x', TextStyle.NORMAL, "https://three.example/path");
		screen.setChar(0, 0, 'x', TextStyle.NORMAL, "https://two.example/path");
		screen.finishSynchronizedOutput();
		assertFalse("Restored hyperlink metadata must not leave a dirty row", screen.hasDirtyRows());
	}

	public void testSynchronizedOutputKeepsOnlyFinalRowDifferences() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		screen.clearDirtyRows();

		screen.beginSynchronizedOutput();
		screen.setChar(2, 1, 'x', TextStyle.NORMAL);
		screen.setChar(2, 1, ' ', TextStyle.NORMAL);
		screen.finishSynchronizedOutput();
		assertFalse("A row restored before synchronized presentation must stay clean", screen.hasDirtyRows());

		screen.beginSynchronizedOutput();
		screen.setChar(3, 2, 'y', TextStyle.NORMAL);
		screen.finishSynchronizedOutput();
		assertTrue(screen.hasDirtyRows());
		assertEquals(2, screen.getDirtyStartRow());
		assertEquals(3, screen.getDirtyEndRow());
	}

	public void testSynchronizedOutputPreservesPreexistingDirtyRows() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		screen.clearDirtyRows();
		screen.setChar(0, 0, 'z', TextStyle.NORMAL);

		screen.beginSynchronizedOutput();
		screen.setChar(1, 2, 'q', TextStyle.NORMAL);
		screen.setChar(1, 2, ' ', TextStyle.NORMAL);
		screen.finishSynchronizedOutput();

		assertTrue(screen.hasDirtyRows());
		assertEquals(0, screen.getDirtyStartRow());
		assertEquals(1, screen.getDirtyEndRow());
	}
}
