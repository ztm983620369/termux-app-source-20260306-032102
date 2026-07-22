package com.termux.terminal;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Random;

public class TerminalSelectionContextExtractorTest extends TerminalTestCase {

    public void testSelectionContextKeepsWrappedLogicalLine() {
        withTerminalSized(5, 3).enterString("ABCDEFGHIJ");

        TerminalSelectionContext context = TerminalSelectionContextExtractor.extractSelectionContext(
            mTerminal.getScreen(), 3, 0, 4, 0, 0);

        assertEquals("ABCDEFGHIJ", context.getText());
        assertEquals("DE", context.getSelectedText());
    }

    public void testSelectionContextPreservesExplicitNewline() {
        withTerminalSized(5, 3).enterString("ABC\r\nFGHIJ");

        TerminalSelectionContext context = TerminalSelectionContextExtractor.extractSelectionContext(
            mTerminal.getScreen(), 1, 0, 1, 1, 0);

        assertEquals("ABC\nFGHIJ", context.getText());
        assertEquals("BC\nFG", context.getSelectedText());
    }

    public void testSelectionContextIncludesPaddingWithoutBlindFullLineJoining() {
        withTerminalSized(5, 4).enterString("AAAAA\r\nBBBBB\r\nCCCCC");

        TerminalSelectionContext context = TerminalSelectionContextExtractor.extractSelectionContext(
            mTerminal.getScreen(), 1, 1, 3, 1, 1);

        assertEquals("AAAAA\nBBBBB\nCCCCC", context.getText());
        assertEquals("BBB", context.getSelectedText());
    }

    public void testSoftWrappedUrlKeepsOneLogicalTokenAndExactSelectionTarget() {
        withTerminalSized(10, 4).enterString("https://example.com/path");
        assertTrue(mTerminal.getScreen().getLineWrap(0));

        TerminalSelectionContext context = TerminalSelectionContextExtractor.extractSelectionContext(
            mTerminal.getScreen(), 2, 1, 2, 1, 1);
        assertFalse(context.getText().contains("https://ex\nample"));
        assertEquals(0, context.getHardWrapHintOffsets().length);

        LinkedHashSet<String> urls = TerminalLinkResolver.resolveSelection(context, true);
        assertEquals(1, urls.size());
        assertEquals("https://example.com/path", urls.iterator().next());
    }

    public void testWideCharacterBeforeWrappedUrlDoesNotShiftTheSelectedTarget() {
        withTerminalSized(12, 4).enterString("\u4e2d https://example.com/path");

        TerminalSelectionContext context = TerminalSelectionContextExtractor.extractSelectionContext(
            mTerminal.getScreen(), 2, 1, 2, 1, 1);
        assertEquals("https://example.com/path",
            TerminalLinkResolver.resolveUniqueSelectionUrl(context, true));
    }

    public void testFullHardLineRemainsSeparatedAndProducesOnlyAHint() {
        withTerminalSized(10, 3).enterString("ABCDEFGHIJ\r\nKLMNO");
        TerminalSelectionContext context = TerminalSelectionContextExtractor.extractTranscriptContext(
            mTerminal.getScreen());

        assertTrue(context.getText().contains("ABCDEFGHIJ\nKLMNO"));
        assertEquals(1, context.getHardWrapHintOffsets().length);
        assertEquals(context.getText().indexOf('\n'), context.getHardWrapHintOffsets()[0]);
    }

    public void testHardWrappedUrlResolvesFromContinuationCellWithoutBlindlyJoiningRows() {
        String firstRow = "https://example.com/very";
        assertEquals(24, firstRow.length());
        withTerminalSized(firstRow.length(), 3).enterString(firstRow + "\r\nlong/path");

        TerminalSelectionContext context = TerminalSelectionContextExtractor.extractSelectionContext(
            mTerminal.getScreen(), 2, 1, 2, 1, 1);
        assertTrue(context.getText().contains(firstRow + "\nlong/path"));
        assertEquals(1, context.getHardWrapHintOffsets().length);
        TerminalLinkResolver.SelectionResult result =
            TerminalLinkResolver.resolveSelectionResult(context, true);
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        expected.add("https://example.com/verylong/path");
        assertEquals(expected, result.getUrls());
        assertTrue(result.requiresConfirmation());
        assertNull(TerminalLinkResolver.resolveUniqueSelectionUrl(context, true));
    }

    public void testFullWidthListLineDoesNotConsumeFollowingListItem() {
        String url = "https://example.com/";
        assertEquals(20, url.length());
        withTerminalSized(url.length(), 3).enterString(url + "\r\n- documentation");

        TerminalSelectionContext context = TerminalSelectionContextExtractor.extractSelectionContext(
            mTerminal.getScreen(), 5, 0, 5, 0, 1);
        assertEquals(url, TerminalLinkResolver.resolveUniqueSelectionUrl(context, true));
    }

    public void testStyledColumnLinkIgnoresTextInTheFollowingTableColumn() {
        String expected = writeCodexTableLink();

        TerminalSelectionContext context = TerminalSelectionContextExtractor.extractStyledLinkContext(
            mTerminal.getScreen(), 22, 0, 35, 0);

        assertNotNull(context);
        assertEquals(expected, context.getText());
        assertNull(TerminalLinkResolver.resolveUniqueSelectionUrl(context, true));

        TerminalLinkResolver.SelectionResult result = TerminalLinkResolver.resolveTerminalSelection(
            mTerminal.getScreen(), 22, 0, 35, 0, true);
        assertEquals(1, result.getUrls().size());
        assertEquals(expected, result.getUrls().iterator().next());
        assertTrue(result.requiresConfirmation());
    }

    public void testStyledColumnLinkCanBeRecoveredFromAMiddleContinuationRow() {
        String expected = writeCodexTableLink();

        TerminalSelectionContext context = TerminalSelectionContextExtractor.extractStyledLinkContext(
            mTerminal.getScreen(), 23, 3, 31, 3);

        assertNotNull(context);
        assertEquals(expected, context.getText());
        assertNull(TerminalLinkResolver.resolveUniqueSelectionUrl(context, true));

        TerminalLinkResolver.SelectionResult result = TerminalLinkResolver.resolveTerminalSelection(
            mTerminal.getScreen(), 23, 3, 31, 3, true);
        assertEquals(1, result.getUrls().size());
        assertEquals(expected, result.getUrls().iterator().next());
        assertTrue(result.requiresConfirmation());
    }

    public void testOsc8SemanticTargetWinsOverWrappedTableGeometry() {
        String expected = writeOsc8CodexTableLink();

        TerminalLinkResolver.SelectionResult firstRow = TerminalLinkResolver.resolveTerminalSelection(
            mTerminal.getScreen(), 22, 0, 35, 0, true);
        TerminalLinkResolver.SelectionResult continuation = TerminalLinkResolver.resolveTerminalSelection(
            mTerminal.getScreen(), 23, 5, 28, 5, true);

        assertEquals(1, firstRow.getUrls().size());
        assertEquals(expected, firstRow.getUrls().iterator().next());
        assertFalse(firstRow.requiresConfirmation());
        assertEquals(firstRow.getUrls(), continuation.getUrls());
        assertFalse(continuation.requiresConfirmation());
    }

    public void testOsc8StateSupportsBelAndStringTerminatorWithoutLeaking() {
        withTerminalSized(40, 3);
        String expected = "https://example.com/a?x=1#part";
        enterString("\033]8;id=one;HTTPS://Example.COM/a?x=1#part\007A\033]8;;\007");
        enterString("B");
        enterString("\033]8;;" + expected + "\033\\C\033]8;;\033\\");
        enterString("\033]8;broken\007D");
        enterString("\033]8;;javascript:alert(1)\007E\033]8;;\007");

        TerminalBuffer screen = mTerminal.getScreen();
        assertEquals(expected, screen.getHyperlinkAt(0, 0));
        assertNull(screen.getHyperlinkAt(0, 1));
        assertEquals(expected, screen.getHyperlinkAt(0, 2));
        assertNull(screen.getHyperlinkAt(0, 3));
        assertNull(screen.getHyperlinkAt(0, 4));
    }

    public void testOversizedOsc8IsDiscardedWithoutRenderingOrLeakingState() {
        withTerminalSized(20, 3);
        String original = "https://example.com/original";
        enterString("\033]8;;" + original + "\007A");

        char[] oversizedPath = new char[9000];
        java.util.Arrays.fill(oversizedPath, 'x');
        enterString("\033]8;;https://example.com/" + new String(oversizedPath) +
            "\nTAIL\007Z");

        TerminalBuffer screen = mTerminal.getScreen();
        assertEquals("AZ", screen.getTranscriptText());
        assertEquals(original, screen.getHyperlinkAt(0, 0));
        assertNull(screen.getHyperlinkAt(0, 1));
    }

    public void testOsc8MetadataSurvivesWideCellsCopyAndErase() {
        withTerminalSized(20, 4);
        String expected = "https://example.com/wide";
        enterString("\033]8;;" + expected + "\007\u4e2dA\033]8;;\007");

        TerminalBuffer screen = mTerminal.getScreen();
        assertEquals(expected, screen.getHyperlinkAt(0, 0));
        assertEquals(expected, screen.getHyperlinkAt(0, 1));
        assertEquals(expected, screen.getHyperlinkAt(0, 2));

        screen.blockCopy(0, 0, 3, 1, 5, 1);
        assertEquals(expected, screen.getHyperlinkAt(1, 5));
        assertEquals(expected, screen.getHyperlinkAt(1, 6));
        assertEquals(expected, screen.getHyperlinkAt(1, 7));

        screen.blockSet(5, 1, 3, 1, ' ', TextStyle.NORMAL);
        assertNull(screen.getHyperlinkAt(1, 5));
        assertNull(screen.getHyperlinkAt(1, 6));
        assertNull(screen.getHyperlinkAt(1, 7));
    }

    public void testOsc8MetadataSurvivesTerminalReflow() {
        withTerminalSized(8, 6);
        String expected = "https://example.com/reflow";
        enterString("\033]8;;" + expected + "\007abcdefghijk\033]8;;\007");

        resize(5, 6);
        TerminalBuffer screen = mTerminal.getScreen();
        int linkedCells = 0;
        for (int row = -screen.getActiveTranscriptRows(); row < screen.mScreenRows; row++) {
            for (int column = 0; column < screen.mColumns; column++) {
                if (expected.equals(screen.getHyperlinkAt(row, column))) linkedCells++;
            }
        }
        assertEquals(11, linkedCells);

        TerminalLinkResolver.SelectionResult result = TerminalLinkResolver.resolveTerminalSelection(
            screen, 2, 1, 2, 1, true);
        assertEquals(java.util.Collections.singleton(expected), result.getUrls());
        assertFalse(result.requiresConfirmation());
    }

    public void testSemanticSelectionReturnsDistinctTargetsInVisualOrder() {
        withTerminalSized(40, 3);
        String first = "https://one.example/path";
        String second = "https://two.example/path";
        enterString("\033]8;;" + first + "\007one\033]8;;\007");
        enterString("\033[2;1H\033]8;;" + second + "\007two\033]8;;\007");

        TerminalLinkResolver.SelectionResult result = TerminalLinkResolver.resolveTerminalSelection(
            mTerminal.getScreen(), 0, 0, 2, 1, true);

        LinkedHashSet<String> expected = new LinkedHashSet<>();
        expected.add(first);
        expected.add(second);
        assertEquals(expected, result.getUrls());
        assertFalse(result.requiresConfirmation());
    }

    public void testUnstyledColumnContinuationIsRecoveredButRequiresConfirmation() {
        String expected = writeUnstyledColumnLink();

        TerminalLinkResolver.SelectionResult result = TerminalLinkResolver.resolveTerminalSelection(
            mTerminal.getScreen(), 23, 3, 31, 3, true);

        assertEquals(1, result.getUrls().size());
        assertEquals(expected, result.getUrls().iterator().next());
        assertTrue(result.requiresConfirmation());
    }

    public void testASecondUrlAtTheSameColumnIsNeverConsumedAsContinuation() {
        String expected = writeUnstyledColumnLink();
        enterString("\033[7;21Hhttps://other.example/path");

        TerminalLinkResolver.SelectionResult result = TerminalLinkResolver.resolveTerminalSelection(
            mTerminal.getScreen(), 22, 0, 35, 0, true);

        assertEquals(1, result.getUrls().size());
        assertEquals(expected, result.getUrls().iterator().next());
        assertFalse(result.getUrls().contains("https://other.example/path"));
        assertTrue(result.requiresConfirmation());
    }

    public void testStyledSecondUrlAtTheSameColumnStartsANewTarget() {
        withTerminalSized(70, 5);
        enterString("\033[1;21H\033[4;36mhttps://one.example/path\033[0m");
        enterString("\033[2;21H\033[4;36mhttps://two.example/path\033[0m");

        TerminalLinkResolver.SelectionResult result = TerminalLinkResolver.resolveTerminalSelection(
            mTerminal.getScreen(), 24, 1, 30, 1, true);

        assertEquals(1, result.getUrls().size());
        assertEquals("https://two.example/path", result.getUrls().iterator().next());
        assertFalse(result.requiresConfirmation());
    }

    public void testStyledContinuationSupportsBoundedIndentationDrift() {
        withTerminalSized(90, 10);
        String[] segments = {
            "https://example.com/",
            "alpha/",
            "beta?",
            "query=1&",
            "mode=full#",
            "section"
        };
        int[] columns = {21, 23, 19, 25, 20, 22};
        StringBuilder expected = new StringBuilder();
        for (int row = 0; row < segments.length; row++) {
            enterString("\033[" + (row + 1) + ";" + columns[row] + "H\033[4;36m" +
                segments[row] + "\033[0m");
            expected.append(segments[row]);
        }
        enterString("\033[1;61Hsibling column");

        TerminalLinkResolver.SelectionResult result = TerminalLinkResolver.resolveTerminalSelection(
            mTerminal.getScreen(), 25, 3, 30, 3, true);

        assertEquals(1, result.getUrls().size());
        assertEquals(expected.toString(), result.getUrls().iterator().next());
        assertTrue(result.requiresConfirmation());
    }

    public void testColumnDriftBeyondTheBoundNeverCrossesIntoASiblingCell() {
        withTerminalSized(90, 6);
        enterString("\033[1;21H\033[4;36mhttps://example.com/\033[0m");
        enterString("\033[1;61Hsibling column");
        enterString("\033[2;31H\033[4;36mnot-the-same-cell\033[0m");

        TerminalLinkResolver.SelectionResult result = TerminalLinkResolver.resolveTerminalSelection(
            mTerminal.getScreen(), 23, 0, 35, 0, true);

        assertEquals(1, result.getUrls().size());
        assertEquals("https://example.com/", result.getUrls().iterator().next());
        assertFalse(result.requiresConfirmation());
    }

    public void testStyleChangeFallsBackToConfirmedColumnGeometry() {
        withTerminalSized(80, 5);
        enterString("\033[1;21H\033[4;36mhttps://example.com/path/\033[0m");
        enterString("\033[1;61Hsibling");
        enterString("\033[2;21H\033[1;4;36mcontinued\033[0m");

        TerminalLinkResolver.SelectionResult result = TerminalLinkResolver.resolveTerminalSelection(
            mTerminal.getScreen(), 22, 0, 35, 0, true);

        assertEquals(1, result.getUrls().size());
        assertEquals("https://example.com/path/continued", result.getUrls().iterator().next());
        assertTrue(result.requiresConfirmation());
    }

    public void testStyleChangeCanRecoverFromPlainContinuationSelection() {
        withTerminalSized(80, 5);
        enterString("\033[1;21H\033[4;36mhttps://example.com/path/\033[0m");
        enterString("\033[1;61Hsibling");
        enterString("\033[2;21H\033[1;4;36mcontinued\033[0m");

        TerminalLinkResolver.SelectionResult result = TerminalLinkResolver.resolveTerminalSelection(
            mTerminal.getScreen(), 23, 1, 29, 1, true);

        assertEquals(1, result.getUrls().size());
        assertEquals("https://example.com/path/continued", result.getUrls().iterator().next());
        assertTrue(result.requiresConfirmation());
    }

    public void testStructuredSelectionCoordinatesMayBeReversed() {
        String expected = writeCodexTableLink();

        TerminalLinkResolver.SelectionResult result = TerminalLinkResolver.resolveTerminalSelection(
            mTerminal.getScreen(), 31, 3, 22, 0, true);

        assertEquals(1, result.getUrls().size());
        assertEquals(expected, result.getUrls().iterator().next());
        assertTrue(result.requiresConfirmation());
    }

    public void testOrdinaryAlignedWordsDoNotActivateColumnRecovery() {
        withTerminalSized(60, 5);
        enterString("\033[1;21Hdocumentation");
        enterString("\033[2;21Hcontinued");
        enterString("\033[3;21Hnormally");

        assertNull(TerminalSelectionContextExtractor.extractColumnLinkContext(
            mTerminal.getScreen(), 22, 1, 28, 1));
        assertTrue(TerminalLinkResolver.resolveTerminalSelection(
            mTerminal.getScreen(), 22, 1, 28, 1, true).getUrls().isEmpty());
    }

    public void testStyledColumnRecoveryPreservesWideIdnAndPathCharacters() {
        withTerminalSized(60, 5);
        enterString("\033[1;11H\033[4;36mhttps://\u4f8b\u5b50.\033[0m");
        enterString("\033[2;11H\033[4;36m\u6d4b\u8bd5/\u8def\u5f84\033[0m");

        TerminalLinkResolver.SelectionResult result = TerminalLinkResolver.resolveTerminalSelection(
            mTerminal.getScreen(), 13, 0, 20, 0, true);

        assertEquals(1, result.getUrls().size());
        assertEquals("https://xn--fsqu00a.xn--0zwm56d/\u8def\u5f84",
            result.getUrls().iterator().next());
        assertTrue(result.requiresConfirmation());
    }

    public void testStructuredResolutionIsDeterministicAcrossRandomizedScreens() throws Exception {
        Random random = new Random(0x2d5eedL);
        char[] alphabet = " abcdefghijklmnopqrstuvwxyz0123456789:/?#&=.%_-()[]".toCharArray();
        for (int sample = 0; sample < 100; sample++) {
            withTerminalSized(40, 12);
            TerminalBuffer screen = mTerminal.getScreen();
            for (int row = 0; row < 12; row++) {
                for (int column = 0; column < 40; column++) {
                    char ch = alphabet[random.nextInt(alphabet.length)];
                    int effect = random.nextInt(12) == 0
                        ? TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
                        : 0;
                    long style = TextStyle.encode(
                        effect == 0 ? TextStyle.COLOR_INDEX_FOREGROUND : 6,
                        TextStyle.COLOR_INDEX_BACKGROUND,
                        effect);
                    screen.setChar(column, row, ch, style);
                }
            }

            int row = random.nextInt(12);
            int start = random.nextInt(40);
            int end = Math.min(39, start + random.nextInt(8));
            TerminalLinkResolver.SelectionResult first =
                TerminalLinkResolver.resolveTerminalSelection(screen, start, row, end, row, true);
            TerminalLinkResolver.SelectionResult second =
                TerminalLinkResolver.resolveTerminalSelection(screen, start, row, end, row, true);

            assertEquals(first.getUrls(), second.getUrls());
            assertEquals(first.requiresConfirmation(), second.requiresConfirmation());
            assertTrue(first.getUrls().size() <= 32);
            for (String url : first.getUrls()) {
                assertNotNull(new URI(url).getScheme());
            }
        }
    }

    private String writeCodexTableLink() {
        withTerminalSized(90, 10);
        String[] segments = {
            "https://github.com/",
            "openai/codex/releases/",
            "tag/rust-v0.144.1?",
            "tab=readme-ov-",
            "file#installing-and-",
            "building"
        };
        enterString("\033[1;20H(");
        for (int row = 0; row < segments.length; row++) {
            enterString("\033[" + (row + 1) + ";21H\033[4;36m" + segments[row] + "\033[0m");
        }
        enterString("\033[6;29H)");
        enterString("\033[1;61Hquery + fragment");

        StringBuilder expected = new StringBuilder();
        for (String segment : segments) expected.append(segment);
        return expected.toString();
    }

    private String writeUnstyledColumnLink() {
        withTerminalSized(90, 10);
        String[] segments = {
            "https://github.com/",
            "openai/codex/releases/",
            "tag/rust-v0.144.1?",
            "tab=readme-ov-",
            "file#installing-and-",
            "building"
        };
        enterString("\033[1;20H(");
        for (int row = 0; row < segments.length; row++) {
            enterString("\033[" + (row + 1) + ";21H" + segments[row]);
        }
        enterString("\033[6;29H)");
        enterString("\033[1;61Hquery + fragment");

        StringBuilder expected = new StringBuilder();
        for (String segment : segments) expected.append(segment);
        return expected.toString();
    }

    private String writeOsc8CodexTableLink() {
        withTerminalSized(90, 10);
        String[] segments = {
            "https://github.com/",
            "openai/codex/releases/",
            "tag/rust-v0.144.1?",
            "tab=readme-ov-",
            "file#installing-and-",
            "building"
        };
        String target = "https://github.com/openai/codex/releases/" +
            "tag/rust-v0.144.1?tab=readme-ov-file#installing-and-building";
        enterString("\033[1;20H(");
        for (int row = 0; row < segments.length; row++) {
            enterString("\033[" + (row + 1) + ";21H\033]8;id=codex;" + target + "\007" +
                "\033[4;36m" + segments[row] + "\033[0m\033]8;;\007");
        }
        enterString("\033[6;29H)");
        enterString("\033[1;61Hquery + fragment");
        return target;
    }
}
