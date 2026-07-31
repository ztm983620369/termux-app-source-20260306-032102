package com.termux.terminal;

import junit.framework.TestCase;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class TerminalParserDifferentialTest extends TestCase {

    private static final int COLUMNS = 40;
    private static final int ROWS = 12;
    private static final int TRANSCRIPT_ROWS = 120;

    private static final String[] CONTROL_SEQUENCES = {
        "\033[0m", "\033[1m", "\033[2m", "\033[3m", "\033[4m", "\033[7m",
        "\033[22m", "\033[23m", "\033[24m", "\033[27m", "\033[31m", "\033[38;5;117m",
        "\033[38;2;12;34;56m", "\033[48;5;22m", "\033[2J", "\033[K", "\033[1K",
        "\033[2K", "\033[2A", "\033[3B", "\033[2C", "\033[2D", "\033[s", "\033[u",
        "\033[2@", "\033[2P", "\033[2L", "\033[2M", "\033[2S", "\033[2T",
        "\033[?7h", "\033[?7l", "\033[4h", "\033[4l", "\033[?25h", "\033[?25l",
        "\033[?1049h", "\033[?1049l", "\033[?2026h", "\033[?2026l", "\033(0", "\033(B", "\016", "\017",
        "\033[2;10r", "\033[r", "\033[?69h", "\033[3;35s", "\033[?69l"
    };

    private static final String[] TEXT_RUNS = {
        "plain ascii output ",
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
        "tmux|status|pane|command|output",
        "spaces        and        columns",
        "\u00e9\u03bb\u0416",
        "\u4e2d\u6587\u754c",
        "e\u0301o\u0308",
        "\ud83d\ude00\ud83d\ude80"
    };

    public void testBatchParserMatchesByteWiseReferenceAcrossRandomizedStreams() throws Exception {
        for (int seed = 0; seed < 24; seed++) {
            byte[] input = buildInput(seed);
            CapturingOutput optimizedOutput = new CapturingOutput();
            CapturingOutput referenceOutput = new CapturingOutput();
            TerminalEmulator optimized = newTerminal(optimizedOutput);
            TerminalEmulator reference = newTerminal(referenceOutput);
            Random chunks = new Random(0x51A7E000L + seed);

            int offset = 0;
            while (offset < input.length) {
                int count = Math.min(input.length - offset, 1 + chunks.nextInt(47));
                byte[] chunk = Arrays.copyOfRange(input, offset, offset + count);
                try {
                    optimized.append(chunk, chunk.length);
                    reference.appendByteWiseForTesting(chunk, chunk.length);
                } catch (RuntimeException exception) {
                    throw new AssertionError("seed=" + seed + ", offset=" + offset +
                        ", count=" + count + ", chunk=" + Arrays.toString(chunk), exception);
                }
                assertEquivalent("seed=" + seed + ", offset=" + offset, optimized, reference,
                    optimizedOutput, referenceOutput);
                offset += count;
            }
        }
    }

    public void testBatchParserMatchesReferenceAfterComplexRowsAndAutowrapBoundaries() throws Exception {
        byte[] input = ("\u4e2d\u6587\u0301\r\n" +
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" +
            "\033[31mRED\033[0m\r\n" +
            "\033[?1049halternate-screen-data\033[?1049lmain-screen-data")
            .getBytes(StandardCharsets.UTF_8);
        CapturingOutput optimizedOutput = new CapturingOutput();
        CapturingOutput referenceOutput = new CapturingOutput();
        TerminalEmulator optimized = newTerminal(optimizedOutput);
        TerminalEmulator reference = newTerminal(referenceOutput);

        optimized.append(input, input.length);
        reference.appendByteWiseForTesting(input, input.length);

        assertEquivalent("complex rows", optimized, reference, optimizedOutput, referenceOutput);
    }

    public void testWholeChunkAsciiClassifierMatchesByteWiseReference() throws Exception {
        for (int seed = 0; seed < 24; seed++) {
            byte[] input = buildInput(seed);
            CapturingOutput classifiedOutput = new CapturingOutput();
            CapturingOutput referenceOutput = new CapturingOutput();
            TerminalEmulator classified = newTerminal(classifiedOutput);
            TerminalEmulator reference = newTerminal(referenceOutput);

            classified.appendWithScalarAsciiClassifierForTesting(input, input.length);
            reference.appendByteWiseForTesting(input, input.length);

            assertEquivalent("whole-chunk seed=" + seed, classified, reference,
                classifiedOutput, referenceOutput);
        }
    }

    public void testParserDoesNotCrashForEveryByteBoundary() throws Exception {
        byte[] input = buildInput(17);
        CapturingOutput optimizedOutput = new CapturingOutput();
        CapturingOutput referenceOutput = new CapturingOutput();
        TerminalEmulator optimized = newTerminal(optimizedOutput);
        TerminalEmulator reference = newTerminal(referenceOutput);
        for (int offset = 0; offset < input.length; offset++) {
            byte[] oneByte = {input[offset]};
            try {
                optimized.append(oneByte, 1);
                reference.appendByteWiseForTesting(oneByte, 1);
            } catch (RuntimeException exception) {
                throw new AssertionError("offset=" + offset + ", byte=" + (input[offset] & 0xff) +
                    ", cursor=" + optimized.getCursorRow() + ',' + optimized.getCursorCol() +
                    ", margins=" + getPrivateField(optimized, "mTopMargin") + ',' +
                    getPrivateField(optimized, "mBottomMargin") + ',' +
                    getPrivateField(optimized, "mLeftMargin") + ',' +
                    getPrivateField(optimized, "mRightMargin"), exception);
            }
            assertEquivalent("one-byte offset=" + offset, optimized, reference,
                optimizedOutput, referenceOutput);
        }
    }

    private static TerminalEmulator newTerminal(CapturingOutput output) {
        return new TerminalEmulator(output, COLUMNS, ROWS,
            TerminalTestCase.INITIAL_CELL_WIDTH_PIXELS,
            TerminalTestCase.INITIAL_CELL_HEIGHT_PIXELS,
            TRANSCRIPT_ROWS, null);
    }

    private static byte[] buildInput(int seed) {
        Random random = new Random(0x7E57C0DEL + seed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int operation = 0; operation < 420; operation++) {
            switch (random.nextInt(10)) {
                case 0:
                case 1:
                case 2:
                    writeUtf8(output, TEXT_RUNS[random.nextInt(TEXT_RUNS.length)]);
                    break;
                case 3:
                    writeUtf8(output, CONTROL_SEQUENCES[random.nextInt(CONTROL_SEQUENCES.length)]);
                    break;
                case 4:
                    writeUtf8(output, "\033[" + (1 + random.nextInt(ROWS)) + ";" +
                        (1 + random.nextInt(COLUMNS)) + "H");
                    break;
                case 5:
                    writeUtf8(output, "\033]0;title-" + seed + '-' + operation + "\007");
                    break;
                case 6:
                    output.write('\r');
                    output.write('\n');
                    break;
                case 7:
                    output.write(random.nextBoolean() ? '\b' : '\t');
                    break;
                case 8:
                    byte[] malformedUtf8 = {(byte) 0xf0, 0x28, (byte) 0x8c, 0x28};
                    output.write(malformedUtf8, 0, malformedUtf8.length);
                    break;
                default:
                    output.write(0x20 + random.nextInt(0x7f - 0x20));
                    break;
            }
        }
        writeUtf8(output, "\033[?69l\033[r\033[?7h\033[4l\033[?1049l\033[0mREFERENCE-END");
        return output.toByteArray();
    }

    private static void writeUtf8(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(bytes, 0, bytes.length);
    }

    private static void assertEquivalent(String context, TerminalEmulator optimized,
                                         TerminalEmulator reference, CapturingOutput optimizedOutput,
                                         CapturingOutput referenceOutput) throws Exception {
        assertEquals(context, reference.mRows, optimized.mRows);
        assertEquals(context, reference.mColumns, optimized.mColumns);
        assertEquals(context, reference.getCursorRow(), optimized.getCursorRow());
        assertEquals(context, reference.getCursorCol(), optimized.getCursorCol());
        assertEquals(context, reference.getCursorStyle(), optimized.getCursorStyle());
        assertEquals(context, reference.isAlternateBufferActive(), optimized.isAlternateBufferActive());
        assertEquals(context, reference.isReverseVideo(), optimized.isReverseVideo());
        assertEquals(context, reference.isCursorEnabled(), optimized.isCursorEnabled());
        assertEquals(context, reference.shouldCursorBeVisible(), optimized.shouldCursorBeVisible());
        assertEquals(context, reference.isKeypadApplicationMode(), optimized.isKeypadApplicationMode());
        assertEquals(context, reference.isCursorKeysApplicationMode(), optimized.isCursorKeysApplicationMode());
        assertEquals(context, reference.isMouseTrackingActive(), optimized.isMouseTrackingActive());
        assertEquals(context, reference.shouldSendFocusEvents(), optimized.shouldSendFocusEvents());
        assertEquals(context, reference.getScrollCounter(), optimized.getScrollCounter());
        assertEquals(context, reference.isScrollCounterFullScreen(), optimized.isScrollCounterFullScreen());
        assertEquals(context, reference.isFullRedrawRequired(), optimized.isFullRedrawRequired());
        assertEquals(context, reference.isAutoScrollDisabled(), optimized.isAutoScrollDisabled());
        assertEquals(context, reference.getTitle(), optimized.getTitle());
        assertTrue(context, Arrays.equals(reference.mColors.mCurrentColors, optimized.mColors.mCurrentColors));

        compareBuffer(context + " main", getPrivateBuffer(reference, "mMainBuffer"),
            getPrivateBuffer(optimized, "mMainBuffer"));
        compareBuffer(context + " alt", reference.mAltBuffer, optimized.mAltBuffer);
        comparePrivateParserFields(context, optimized, reference);
        compareSavedState(context + " saved-main", getPrivateField(reference, "mSavedStateMain"),
            getPrivateField(optimized, "mSavedStateMain"));
        compareSavedState(context + " saved-alt", getPrivateField(reference, "mSavedStateAlt"),
            getPrivateField(optimized, "mSavedStateAlt"));
        assertEquals(context, referenceOutput, optimizedOutput);
    }

    private static void compareBuffer(String context, TerminalBuffer reference, TerminalBuffer optimized) {
        assertEquals(context, reference.mColumns, optimized.mColumns);
        assertEquals(context, reference.mScreenRows, optimized.mScreenRows);
        assertEquals(context, reference.mTotalRows, optimized.mTotalRows);
        assertEquals(context, reference.getActiveTranscriptRows(), optimized.getActiveTranscriptRows());
        assertEquals(context, reference.hasDirtyRows(), optimized.hasDirtyRows());
        assertEquals(context, reference.getDirtyStartRow(), optimized.getDirtyStartRow());
        assertEquals(context, reference.getDirtyEndRow(), optimized.getDirtyEndRow());

        int firstRow = -reference.getActiveTranscriptRows();
        for (int row = firstRow; row < reference.mScreenRows; row++) {
            TerminalRow expected = reference.allocateFullLineIfNecessary(reference.externalToInternalRow(row));
            TerminalRow actual = optimized.allocateFullLineIfNecessary(optimized.externalToInternalRow(row));
            assertEquals(context + " row=" + row, expected.getSpaceUsed(), actual.getSpaceUsed());
            assertTrue(context + " text row=" + row,
                Arrays.equals(Arrays.copyOf(expected.mText, expected.getSpaceUsed()),
                    Arrays.copyOf(actual.mText, actual.getSpaceUsed())));
            assertTrue(context + " style row=" + row, Arrays.equals(expected.mStyle, actual.mStyle));
            assertEquals(context + " wrap row=" + row, expected.mLineWrap, actual.mLineWrap);
            assertEquals(context + " width-state row=" + row,
                expected.mHasNonOneWidthOrSurrogateChars, actual.mHasNonOneWidthOrSurrogateChars);
        }
    }

    private static void comparePrivateParserFields(String context, TerminalEmulator optimized,
                                                   TerminalEmulator reference) throws Exception {
        String[] fields = {
            "mTitle", "mTitleStack", "mCursorRow", "mCursorCol", "mCellWidthPixels", "mCellHeightPixels",
            "mCursorStyle", "mArgIndex", "mArgs", "mArgsSubParamsBitSet", "mOSCOrDeviceControlArgs",
            "mContinueSequence", "mEscapeState", "mUseLineDrawingG0", "mUseLineDrawingG1",
            "mUseLineDrawingUsesG0", "mCurrentDecSetFlags", "mSavedDecSetFlags", "mInsertMode",
            "mTabStop", "mTopMargin", "mBottomMargin", "mLeftMargin", "mRightMargin", "mAboutToAutoWrap",
            "mCursorBlinkingEnabled", "mCursorBlinkState", "mForeColor", "mBackColor", "mUnderlineColor",
            "mEffect", "mScrollSignal", "mFullRedrawRequired",
            "mAutoScrollDisabled", "mUtf8ToFollow", "mUtf8Index", "mUtf8InputBuffer", "mLastEmittedCodePoint"
        };
        for (String field : fields) {
            Object expected = normalize(getPrivateField(reference, field));
            Object actual = normalize(getPrivateField(optimized, field));
            assertTrue(context + " field=" + field + ", expected=" + valueString(expected) +
                ", actual=" + valueString(actual), Objects.deepEquals(expected, actual));
        }
    }

    private static void compareSavedState(String context, Object reference, Object optimized) throws Exception {
        for (Field field : reference.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object expected = normalize(field.get(reference));
            Object actual = normalize(field.get(optimized));
            assertTrue(context + " field=" + field.getName(), Objects.deepEquals(expected, actual));
        }
    }

    private static TerminalBuffer getPrivateBuffer(TerminalEmulator emulator, String fieldName) throws Exception {
        return (TerminalBuffer) getPrivateField(emulator, fieldName);
    }

    private static Object getPrivateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object normalize(Object value) {
        if (value instanceof StringBuilder) return value.toString();
        if (value instanceof AtomicLong) return ((AtomicLong) value).get();
        if (value instanceof AtomicBoolean) return ((AtomicBoolean) value).get();
        return value;
    }

    private static String valueString(Object value) {
        if (value instanceof int[]) return Arrays.toString((int[]) value);
        if (value instanceof byte[]) return Arrays.toString((byte[]) value);
        if (value instanceof boolean[]) return Arrays.toString((boolean[]) value);
        return String.valueOf(value);
    }

    private static final class CapturingOutput extends TerminalOutput {
        private final ByteArrayOutputStream writes = new ByteArrayOutputStream();
        private final List<String> titles = new ArrayList<>();
        private final List<String> clipboard = new ArrayList<>();
        private final List<String> hostCommands = new ArrayList<>();
        private int pasteRequests;
        private int bells;
        private int colorChanges;

        @Override
        public void write(byte[] data, int offset, int count) {
            writes.write(data, offset, count);
        }

        @Override
        public void titleChanged(String oldTitle, String newTitle) {
            titles.add(String.valueOf(oldTitle) + "\u0000" + newTitle);
        }

        @Override
        public void onCopyTextToClipboard(String text) {
            clipboard.add(text);
        }

        @Override
        public void onPasteTextFromClipboard() {
            pasteRequests++;
        }

        @Override
        public void onBell() {
            bells++;
        }

        @Override
        public void onColorsChanged() {
            colorChanges++;
        }

        @Override
        public void onTerminalHostControlCommand(String command, String argument) {
            hostCommands.add(command + "\u0000" + argument);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof CapturingOutput)) return false;
            CapturingOutput output = (CapturingOutput) other;
            return Arrays.equals(writes.toByteArray(), output.writes.toByteArray())
                && titles.equals(output.titles)
                && clipboard.equals(output.clipboard)
                && hostCommands.equals(output.hostCommands)
                && pasteRequests == output.pasteRequests
                && bells == output.bells
                && colorChanges == output.colorChanges;
        }

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public String toString() {
            return "CapturingOutput{writes=" + writes.size() + ", titles=" + titles.size() +
                ", clipboard=" + clipboard.size() + ", hostCommands=" + hostCommands.size() +
                ", pasteRequests=" + pasteRequests + ", bells=" + bells +
                ", colorChanges=" + colorChanges + '}';
        }
    }
}
