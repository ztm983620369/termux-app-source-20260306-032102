package com.termux.terminal;

import android.os.Trace;
import android.os.Process;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Renders text into a screen. Contains all the terminal-specific knowledge and state. Emulates a subset of the X Window
 * System xterm terminal, which in turn is an emulator for a subset of the Digital Equipment Corporation vt100 terminal.
 * <p>
 * References:
 * <ul>
 * <li>http://invisible-island.net/xterm/ctlseqs/ctlseqs.html</li>
 * <li>http://en.wikipedia.org/wiki/ANSI_escape_code</li>
 * <li>http://man.he.net/man4/console_codes</li>
 * <li>http://bazaar.launchpad.net/~leonerd/libvterm/trunk/view/head:/src/state.c</li>
 * <li>http://www.columbia.edu/~kermit/k95manual/iso2022.html</li>
 * <li>http://www.vt100.net/docs/vt510-rm/chapter4</li>
 * <li>http://en.wikipedia.org/wiki/ISO/IEC_2022 - for 7-bit and 8-bit GL GR explanation</li>
 * <li>http://bjh21.me.uk/all-escapes/all-escapes.txt - extensive!</li>
 * <li>http://woldlab.caltech.edu/~diane/kde4.10/workingdir/kubuntu/konsole/doc/developer/old-documents/VT100/techref.
 * html - document for konsole - accessible!</li>
 * </ul>
 */
public final class TerminalEmulator {

    /** Log unknown or unimplemented escape sequences received from the shell process. */
    private static final boolean LOG_ESCAPE_SEQUENCES = false;

    public static final int MOUSE_LEFT_BUTTON = 0;

    /** Mouse moving while having left mouse button pressed. */
    public static final int MOUSE_LEFT_BUTTON_MOVED = 32;
    public static final int MOUSE_WHEELUP_BUTTON = 64;
    public static final int MOUSE_WHEELDOWN_BUTTON = 65;

    /** Used for invalid data - http://en.wikipedia.org/wiki/Replacement_character#Replacement_character */
    public static final int UNICODE_REPLACEMENT_CHAR = 0xFFFD;

    /** Escape processing: Not currently in an escape sequence. */
    private static final int ESC_NONE = 0;
    /** Escape processing: Have seen an ESC character - proceed to {@link #doEsc(int)} */
    private static final int ESC = 1;
    /** Escape processing: Have seen ESC POUND */
    private static final int ESC_POUND = 2;
    /** Escape processing: Have seen ESC and a character-set-select ( char */
    private static final int ESC_SELECT_LEFT_PAREN = 3;
    /** Escape processing: Have seen ESC and a character-set-select ) char */
    private static final int ESC_SELECT_RIGHT_PAREN = 4;
    /** Escape processing: "ESC [" or CSI (Control Sequence Introducer). */
    private static final int ESC_CSI = 6;
    /** Escape processing: ESC [ ? */
    private static final int ESC_CSI_QUESTIONMARK = 7;
    /** Escape processing: ESC [ $ */
    private static final int ESC_CSI_DOLLAR = 8;
    /** Escape processing: ESC % */
    private static final int ESC_PERCENT = 9;
    /** Escape processing: ESC ] (AKA OSC - Operating System Controls) */
    private static final int ESC_OSC = 10;
    /** Escape processing: ESC ] (AKA OSC - Operating System Controls) ESC */
    private static final int ESC_OSC_ESC = 11;
    /** Escape processing: ESC [ > */
    private static final int ESC_CSI_BIGGERTHAN = 12;
    /** Escape procession: "ESC P" or Device Control String (DCS) */
    private static final int ESC_P = 13;
    /** Escape processing: CSI > */
    private static final int ESC_CSI_QUESTIONMARK_ARG_DOLLAR = 14;
    /** Escape processing: CSI $ARGS ' ' */
    private static final int ESC_CSI_ARGS_SPACE = 15;
    /** Escape processing: CSI $ARGS '*' */
    private static final int ESC_CSI_ARGS_ASTERIX = 16;
    /** Escape processing: CSI " */
    private static final int ESC_CSI_DOUBLE_QUOTE = 17;
    /** Escape processing: CSI ' */
    private static final int ESC_CSI_SINGLE_QUOTE = 18;
    /** Escape processing: CSI ! */
    private static final int ESC_CSI_EXCLAMATION = 19;
    /** Escape processing: "ESC _" or Application Program Command (APC). */
    private static final int ESC_APC = 20;
    /** Escape processing: "ESC _" or Application Program Command (APC), followed by Escape. */
    private static final int ESC_APC_ESCAPE = 21;
    /** Escape processing: ESC [ <parameter bytes> */
    private static final int ESC_CSI_UNSUPPORTED_PARAMETER_BYTE = 22;
    /** Escape processing: ESC [ <parameter bytes> <intermediate bytes> */
    private static final int ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE = 23;
    /** Discard an oversized OSC payload until BEL or ST without rendering its tail. */
    private static final int ESC_OSC_DISCARD = 24;
    /** ESC received while discarding an oversized OSC payload. */
    private static final int ESC_OSC_DISCARD_ESC = 25;

    /** The number of parameter arguments including colon separated sub-parameters. */
    private static final int MAX_ESCAPE_PARAMETERS = 32;

    /** Needs to be large enough to contain reasonable OSC 52 pastes. */
    private static final int MAX_OSC_STRING_LENGTH = 8192;
    private static final int MAX_HYPERLINK_CACHE_ENTRIES = 256;
    private static final int OSC_TERMUX_HOST_CONTROL = 8900;
    private static final String HOST_CONTROL_TERMINAL_BACKEND = "terminal-backend";
    /**
     * Maximum foreground journal batch. Reaching it atomically hands the immutable batch to the
     * compatibility checkpoint worker; the PTY parser never replays Java state inline.
     */
    private static final long MAX_COMPATIBILITY_REPLAY_BYTES = 256L * 1024L * 1024L;
    /** Bound one journal record and the replay worker's reusable input buffer. */
    private static final int COMPATIBILITY_REPLAY_BLOCK_BYTES = 64 * 1024;
    private static final int COMPATIBILITY_JOURNAL_BUFFER_BYTES = 256 * 1024;
    private static final int COMPATIBILITY_JOURNAL_MAGIC = 0x54564a31; // TVJ1
    private static final String COMPATIBILITY_JOURNAL_PREFIX = "termux-ghostty-";
    private static final String COMPATIBILITY_JOURNAL_SUFFIX = ".vtj";
    private static final Object COMPATIBILITY_JOURNAL_REGISTRY_LOCK = new Object();
    /** Absolute paths owned by any live terminal in this app process. */
    private static final Set<String> ACTIVE_COMPATIBILITY_JOURNALS = new HashSet<>();
    private static final int COMPATIBILITY_EVENT_BYTES = 1;
    private static final int COMPATIBILITY_EVENT_RESIZE = 2;
    private static final int COMPATIBILITY_EVENT_RESET = 3;
    private static final int COMPATIBILITY_EVENT_COLORS = 4;
    private static final long COMPATIBILITY_METRICS_LOG_INTERVAL_NANOS = 3_000_000_000L;
    /**
     * A compatibility checkpoint is cold recovery state. Do not let it compete with the live
     * Ghostty parser while PTY bytes are arriving. Once output has been quiet for this interval,
     * the background worker may catch up. A synchronous fallback request always bypasses it.
     */
    private static final long COMPATIBILITY_REPLAY_QUIET_NANOS = 1_000_000_000L;
    /** Bound deferred journals even for a terminal producing output without an idle gap. */
    private static final long COMPATIBILITY_REPLAY_PRESSURE_BYTES =
        MAX_COMPATIBILITY_REPLAY_BYTES * 4L;

    /** DECSET 1 - application cursor keys. */
    private static final int DECSET_BIT_APPLICATION_CURSOR_KEYS = 1;
    private static final int DECSET_BIT_REVERSE_VIDEO = 1 << 1;
    /**
     * http://www.vt100.net/docs/vt510-rm/DECOM: "When DECOM is set, the home cursor position is at the upper-left
     * corner of the screen, within the margins. The starting point for line numbers depends on the current top margin
     * setting. The cursor cannot move outside of the margins. When DECOM is reset, the home cursor position is at the
     * upper-left corner of the screen. The starting point for line numbers is independent of the margins. The cursor
     * can move outside of the margins."
     */
    private static final int DECSET_BIT_ORIGIN_MODE = 1 << 2;
    /**
     * http://www.vt100.net/docs/vt510-rm/DECAWM: "If the DECAWM function is set, then graphic characters received when
     * the cursor is at the right border of the page appear at the beginning of the next line. Any text on the page
     * scrolls up if the cursor is at the end of the scrolling region. If the DECAWM function is reset, then graphic
     * characters received when the cursor is at the right border of the page replace characters already on the page."
     */
    private static final int DECSET_BIT_AUTOWRAP = 1 << 3;
    /** DECSET 25 - if the cursor should be enabled, {@link #isCursorEnabled()}. */
    private static final int DECSET_BIT_CURSOR_ENABLED = 1 << 4;
    private static final int DECSET_BIT_APPLICATION_KEYPAD = 1 << 5;
    /** DECSET 1000 - if to report mouse press&release events. */
    private static final int DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE = 1 << 6;
    /** DECSET 1002 - like 1000, but report moving mouse while pressed. */
    private static final int DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT = 1 << 7;
    /** DECSET 1004 - NOT implemented. */
    private static final int DECSET_BIT_SEND_FOCUS_EVENTS = 1 << 8;
    /** DECSET 1006 - SGR-like mouse protocol (the modern sane choice). */
    private static final int DECSET_BIT_MOUSE_PROTOCOL_SGR = 1 << 9;
    /** DECSET 2004 - see {@link #paste(String)} */
    private static final int DECSET_BIT_BRACKETED_PASTE_MODE = 1 << 10;
    /** Toggled with DECLRMM - http://www.vt100.net/docs/vt510-rm/DECLRMM */
    private static final int DECSET_BIT_LEFTRIGHT_MARGIN_MODE = 1 << 11;
    /** Not really DECSET bit... - http://www.vt100.net/docs/vt510-rm/DECSACE */
    private static final int DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE = 1 << 12;
    /** DECSET 2026 - defer presenting screen updates until the mode is reset. */
    private static final int DECSET_BIT_SYNCHRONIZED_OUTPUT = 1 << 13;


    private volatile String mTitle;
    private final Stack<String> mTitleStack = new Stack<>();

    /** The cursor position. Between (0,0) and (mRows-1, mColumns-1). */
    private int mCursorRow, mCursorCol;

    /** The number of character rows and columns in the terminal screen. */
    public int mRows, mColumns;

    /** Size of a terminal cell in pixels. */
    private int mCellWidthPixels, mCellHeightPixels;

    /** The number of terminal transcript rows that can be scrolled back to. */
    public static final int TERMINAL_TRANSCRIPT_ROWS_MIN = 100;
    public static final int TERMINAL_TRANSCRIPT_ROWS_MAX = 50000;
    public static final int DEFAULT_TERMINAL_TRANSCRIPT_ROWS = 2000;


    /* The supported terminal cursor styles. */

    public static final int TERMINAL_CURSOR_STYLE_BLOCK = 0;
    public static final int TERMINAL_CURSOR_STYLE_UNDERLINE = 1;
    public static final int TERMINAL_CURSOR_STYLE_BAR = 2;
    public static final int DEFAULT_TERMINAL_CURSOR_STYLE = TERMINAL_CURSOR_STYLE_BLOCK;
    public static final Integer[] TERMINAL_CURSOR_STYLES_LIST = new Integer[]{TERMINAL_CURSOR_STYLE_BLOCK, TERMINAL_CURSOR_STYLE_UNDERLINE, TERMINAL_CURSOR_STYLE_BAR};

    /** The terminal cursor styles. */
    private int mCursorStyle = DEFAULT_TERMINAL_CURSOR_STYLE;


    /** The normal screen buffer. Stores the characters that appear on the screen of the emulated terminal. */
    private final TerminalBuffer mMainBuffer;
    /**
     * The alternate screen buffer, exactly as large as the display and contains no additional saved lines (so that when
     * the alternate screen buffer is active, you cannot scroll back to view saved lines).
     * <p>
     * See http://www.xfree86.org/current/ctlseqs.html#The%20Alternate%20Screen%20Buffer
     */
    final TerminalBuffer mAltBuffer;
    /** The current screen buffer, pointing at either {@link #mMainBuffer} or {@link #mAltBuffer}. */
    private TerminalBuffer mScreen;

    /** The terminal session this emulator is bound to. */
    private final TerminalOutput mSession;

    volatile TerminalSessionClient mClient;

    /** Keeps track of the current argument of the current escape sequence. Ranges from 0 to MAX_ESCAPE_PARAMETERS-1. */
    private int mArgIndex;
    /** Holds the arguments of the current escape sequence. */
    private final int[] mArgs = new int[MAX_ESCAPE_PARAMETERS];
    /** Holds the bit flags which arguments are sub parameters (after a colon) - bit N is set if <code>mArgs[N]</code> is a sub parameter. */
    private int mArgsSubParamsBitSet = 0;

    /** Holds OSC and device control arguments, which can be strings. */
    private final StringBuilder mOSCOrDeviceControlArgs = new StringBuilder();
    /** Current validated OSC 8 target; null outside a semantic hyperlink. */
    private String mCurrentHyperlink;
    /** Reuses destinations emitted once per TUI cell without creating one String per cell. */
    private final LinkedHashMap<String, String> mHyperlinkCache =
        new LinkedHashMap<String, String>(MAX_HYPERLINK_CACHE_ENTRIES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > MAX_HYPERLINK_CACHE_ENTRIES;
            }
        };

    /**
     * True if the current escape sequence should continue, false if the current escape sequence should be terminated.
     * Used when parsing a single character.
     */
    private boolean mContinueSequence;

    /** The current state of the escape sequence state machine. One of the ESC_* constants. */
    private int mEscapeState;

    private final SavedScreenState mSavedStateMain = new SavedScreenState();
    private final SavedScreenState mSavedStateAlt = new SavedScreenState();

    /** http://www.vt100.net/docs/vt102-ug/table5-15.html */
    private boolean mUseLineDrawingG0, mUseLineDrawingG1, mUseLineDrawingUsesG0 = true;

    /**
     * @see TerminalEmulator#mapDecSetBitToInternalBit(int)
     */
    private int mCurrentDecSetFlags, mSavedDecSetFlags;

    /**
     * If insert mode (as opposed to replace mode) is active. In insert mode new characters are inserted, pushing
     * existing text to the right. Characters moved past the right margin are lost.
     */
    private boolean mInsertMode;

    /** An array of tab stops. mTabStop[i] is true if there is a tab stop set for column i. */
    private boolean[] mTabStop;

    /**
     * Top margin of screen for scrolling ranges from 0 to mRows-2. Bottom margin ranges from mTopMargin + 2 to mRows
     * (Defines the first row after the scrolling region). Left/right margin in [0, mColumns].
     */
    private int mTopMargin, mBottomMargin, mLeftMargin, mRightMargin;

    /**
     * If the next character to be emitted will be automatically wrapped to the next line. Used to disambiguate the case
     * where the cursor is positioned on the last column (mColumns-1). When standing there, a written character will be
     * output in the last column, the cursor not moving but this flag will be set. When outputting another character
     * this will move to the next line.
     */
    private boolean mAboutToAutoWrap;

    /**
     * If the cursor blinking is enabled. It requires cursor itself to be enabled, which is controlled
     * byt whether {@link #DECSET_BIT_CURSOR_ENABLED} bit is set or not.
     */
    private boolean mCursorBlinkingEnabled;

    /**
     * If currently cursor should be in a visible state or not if {@link #mCursorBlinkingEnabled}
     * is {@code true}.
     */
    private boolean mCursorBlinkState;

    /**
     * Current foreground, background and underline colors. Can either be a color index in [0,259] or a truecolor (24-bit) value.
     * For a 24-bit value the top byte (0xff000000) is set.
     *
     * <p>Note that the underline color is currently parsed but not yet used during rendering.
     *
     * @see TextStyle
     */
    int mForeColor, mBackColor, mUnderlineColor;

    /** Current {@link TextStyle} effect. */
    int mEffect;

    /**
     * The number of scrolled lines since last calling {@link #clearScrollCounter()}. Used for moving selection up along
     * with the scrolling text.
     */
    /** Packed as {@code count << 1 | fullScreen}; consumed atomically by TerminalView. */
    private final AtomicLong mScrollSignal = new AtomicLong(1L);
    /** Set when a state change requires a full viewport redraw rather than dirty-row repaint. */
    private final AtomicBoolean mFullRedrawRequired = new AtomicBoolean(true);
    private TerminalBuffer mSynchronizedOutputBuffer;
    private int mSynchronizedOutputCursorRow;
    private int mSynchronizedOutputCursorCol;
    private int mSynchronizedOutputCursorStyle;
    private boolean mSynchronizedOutputCursorVisible;

    /** If automatic scrolling of terminal is disabled */
    private boolean mAutoScrollDisabled;

    private byte mUtf8ToFollow, mUtf8Index;
    private final byte[] mUtf8InputBuffer = new byte[4];
    private int mLastEmittedCodePoint = -1;
    /** One scanned-until value plus up to 1024 [start, end) printable ASCII spans. */
    private final ByteBuffer mAsciiRunStorage = ByteBuffer.allocateDirect(2049 * Integer.BYTES)
        .order(ByteOrder.nativeOrder());
    private final IntBuffer mAsciiRunScratch = mAsciiRunStorage.asIntBuffer();
    /** Per-emulator evidence that real PTY chunks traversed and benefited from the native path. */
    long mNativeAsciiScanCalls;
    long mNativeAsciiScanBytes;
    long mNativeAsciiRangeCount;
    long mNativeAsciiEmittedBytes;
    /** Complete Ghostty parser/render authority and its cached mutation state. */
    private volatile GhosttyTerminalBackend mGhosttyBackend;
    private volatile GhosttyTerminalBackend.State mGhosttyState;
    private File mCompatibilityJournalFile;
    private DataOutputStream mCompatibilityJournalOutput;
    private boolean mCompatibilityJournalHasEvents;
    private long mCompatibilityReplayBytes;
    private final Object mCompatibilityReplayLock = new Object();
    private final ArrayDeque<CompatibilityReplayBatch> mCompatibilityReplayQueue =
        new ArrayDeque<>();
    private volatile Thread mCompatibilityReplayThread;
    private boolean mCompatibilityReplayStop;
    private long mCompatibilityReplayEnqueuedGeneration;
    private long mCompatibilityReplayAppliedGeneration;
    private long mCompatibilityReplayQueuedBytes;
    private long mCompatibilityReplayHighWaterBytes;
    private long mCompatibilityReplayAppliedBytes;
    private long mCompatibilityReplayAppliedBatches;
    private long mCompatibilityReplayMaxBatchNanos;
    private long mCompatibilityReplayLastLogNanos;
    private long mCompatibilityReplayLastThreadId = -1L;
    private String mCompatibilityReplayLastThreadName = "none";
    private volatile long mCompatibilityReplayLastForegroundNanos = System.nanoTime();
    private boolean mCompatibilityReplayUrgent;
    private long mCompatibilityReplayDeferredWaits;
    private long mCompatibilityReplayDeferredNanos;
    private volatile Throwable mCompatibilityReplayFailure;
    private volatile TerminalEmulator mCompatibilityEmulator;
    private volatile boolean mCompatibilityFallback;
    private volatile boolean mCompatibilityReplayInProgress = true;
    private final boolean mReplayOnly;
    /** Grid changes handled by Ghostty without redundantly reflowing the dormant owner Java buffer. */
    private long mGhosttyDormantJavaResizeSkips;
    private final int mInitialColumns;
    private final int mInitialRows;
    private final int mInitialCellWidthPixels;
    private final int mInitialCellHeightPixels;
    private final int mConfiguredTranscriptRows;
    private final int[] mInitialColors;

    public final TerminalColors mColors = new TerminalColors();

    private static final String LOG_TAG = "TerminalEmulator";

    private boolean isDecsetInternalBitSet(int bit) {
        return (mCurrentDecSetFlags & bit) != 0;
    }

    private void setDecsetinternalBit(int internalBit, boolean set) {
        if (set) {
            // The mouse modes are mutually exclusive.
            if (internalBit == DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE) {
                setDecsetinternalBit(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT, false);
            } else if (internalBit == DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT) {
                setDecsetinternalBit(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE, false);
            }
        }
        if (set) {
            mCurrentDecSetFlags |= internalBit;
        } else {
            mCurrentDecSetFlags &= ~internalBit;
        }
    }

    static int mapDecSetBitToInternalBit(int decsetBit) {
        switch (decsetBit) {
            case 1:
                return DECSET_BIT_APPLICATION_CURSOR_KEYS;
            case 5:
                return DECSET_BIT_REVERSE_VIDEO;
            case 6:
                return DECSET_BIT_ORIGIN_MODE;
            case 7:
                return DECSET_BIT_AUTOWRAP;
            case 25:
                return DECSET_BIT_CURSOR_ENABLED;
            case 66:
                return DECSET_BIT_APPLICATION_KEYPAD;
            case 69:
                return DECSET_BIT_LEFTRIGHT_MARGIN_MODE;
            case 1000:
                return DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE;
            case 1002:
                return DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT;
            case 1004:
                return DECSET_BIT_SEND_FOCUS_EVENTS;
            case 1006:
                return DECSET_BIT_MOUSE_PROTOCOL_SGR;
            case 2004:
                return DECSET_BIT_BRACKETED_PASTE_MODE;
            case 2026:
                return DECSET_BIT_SYNCHRONIZED_OUTPUT;
            default:
                return -1;
            // throw new IllegalArgumentException("Unsupported decset: " + decsetBit);
        }
    }

    public TerminalEmulator(TerminalOutput session, int columns, int rows, int cellWidthPixels, int cellHeightPixels, Integer transcriptRows, TerminalSessionClient client) {
        this(session, columns, rows, cellWidthPixels, cellHeightPixels, transcriptRows, client,
            true, false);
    }

    private TerminalEmulator(TerminalOutput session, int columns, int rows,
                             int cellWidthPixels, int cellHeightPixels, Integer transcriptRows,
                             TerminalSessionClient client, boolean allowGhostty,
                             boolean replayOnly) {
        mSession = session;
        int totalRows = getTerminalTranscriptRows(transcriptRows);
        mConfiguredTranscriptRows = totalRows;
        mInitialColumns = columns;
        mInitialRows = rows;
        mInitialCellWidthPixels = cellWidthPixels;
        mInitialCellHeightPixels = cellHeightPixels;
        mReplayOnly = replayOnly;
        mScreen = mMainBuffer = new TerminalBuffer(columns, totalRows, rows);
        mAltBuffer = new TerminalBuffer(columns, rows, rows);
        mClient = client;
        mRows = rows;
        mColumns = columns;
        mCellWidthPixels = cellWidthPixels;
        mCellHeightPixels = cellHeightPixels;
        mTabStop = new boolean[mColumns];
        resetJavaState();
        mInitialColors = mColors.mCurrentColors.clone();
        if (allowGhostty) {
            mGhosttyBackend = GhosttyTerminalBackend.createIfEnabled(
                columns, rows, cellWidthPixels, cellHeightPixels,
                Math.max(0, totalRows - rows), mCursorStyle, mColors.mCurrentColors,
                new GhosttyEffects());
            if (mGhosttyBackend != null) mGhosttyState = mGhosttyBackend.state();
        }
    }

    private final class GhosttyEffects implements GhosttyTerminalBackend.Effects {
        @Override
        public void writePty(byte[] data) {
            if (data != null && data.length > 0) mSession.write(data, 0, data.length);
        }

        @Override
        public void bell() {
            mSession.onBell();
        }

        @Override
        public void titleChanged(String title) {
            setTitle(title);
        }

        @Override
        public boolean clipboardWrite(String text) {
            mSession.onCopyTextToClipboard(text);
            return true;
        }

        @Override
        public void onColorsChanged() {
            mSession.onColorsChanged();
        }

        @Override
        public void hostControl(String payload) {
            handleTermuxHostControl(payload);
        }
    }

    private static final class CompatibilityReplayBatch {
        final File journal;
        final long bytes;
        final long generation;

        CompatibilityReplayBatch(File journal, long bytes,
                                 long generation) {
            this.journal = journal;
            this.bytes = bytes;
            this.generation = generation;
        }
    }

    private final class CompatibilityOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) {
            if (!mCompatibilityReplayInProgress) mSession.write(data, offset, count);
        }

        @Override public void titleChanged(String oldTitle, String newTitle) {
            if (!mCompatibilityReplayInProgress) setTitle(newTitle);
        }

        @Override public void onCopyTextToClipboard(String text) {
            if (!mCompatibilityReplayInProgress) mSession.onCopyTextToClipboard(text);
        }

        @Override public void onPasteTextFromClipboard() {
            if (!mCompatibilityReplayInProgress) mSession.onPasteTextFromClipboard();
        }

        @Override public void onBell() {
            if (!mCompatibilityReplayInProgress) mSession.onBell();
        }

        @Override public void onColorsChanged() {
            if (mCompatibilityReplayInProgress || mCompatibilityEmulator == null) return;
            System.arraycopy(mCompatibilityEmulator.mColors.mCurrentColors, 0,
                mColors.mCurrentColors, 0, mColors.mCurrentColors.length);
            mSession.onColorsChanged();
        }

        @Override public void onTerminalHostControlCommand(String command, String argument) {
            if (!mCompatibilityReplayInProgress) {
                mSession.onTerminalHostControlCommand(command, argument);
            }
        }
    }

    private boolean hasGhosttyAuthority() {
        GhosttyTerminalBackend backend = mGhosttyBackend;
        if (backend == null || mCompatibilityFallback) return false;
        if (GhosttyTerminalBackend.isProductionEnabled()) return true;
        if (mReplayOnly) return false;

        // The healthy read is entirely lock-free so UI getters never wait for a PTY parse. Only
        // the exceptional process kill-switch transition acquires the mutation monitor.
        synchronized (this) {
            if (mGhosttyBackend != null && !GhosttyTerminalBackend.isProductionEnabled() &&
                !mReplayOnly && !mCompatibilityFallback) {
                activateCompatibilityFallback("process kill switch");
            }
            return mGhosttyBackend != null && GhosttyTerminalBackend.isProductionEnabled() &&
                !mCompatibilityFallback;
        }
    }

    private void recordBytesForCompatibility(byte[] buffer, int length) {
        if (length <= 0 || mCompatibilityReplayFailure != null) return;
        markCompatibilityForegroundActivity();
        int offset = 0;
        while (offset < length) {
            if (mCompatibilityReplayBytes == MAX_COMPATIBILITY_REPLAY_BYTES) {
                enqueueCompatibilityReplayBatch(false);
            }
            int journalSpace = (int) Math.min(Integer.MAX_VALUE,
                MAX_COMPATIBILITY_REPLAY_BYTES - mCompatibilityReplayBytes);
            int copied = Math.min(length - offset,
                Math.min(COMPATIBILITY_REPLAY_BLOCK_BYTES, journalSpace));
            try {
                DataOutputStream output = compatibilityJournalOutput();
                output.writeByte(COMPATIBILITY_EVENT_BYTES);
                output.writeInt(copied);
                output.write(buffer, offset, copied);
                mCompatibilityJournalHasEvents = true;
            } catch (IOException error) {
                failCompatibilityJournal(error);
                return;
            }
            offset += copied;
            mCompatibilityReplayBytes += copied;
        }
    }

    private DataOutputStream compatibilityJournalOutput() throws IOException {
        if (mCompatibilityReplayFailure != null) {
            throw new IOException("Compatibility journal is unavailable",
                mCompatibilityReplayFailure);
        }
        if (mCompatibilityJournalOutput != null) return mCompatibilityJournalOutput;
        File directory = new File(System.getProperty("java.io.tmpdir", "."));
        synchronized (COMPATIBILITY_JOURNAL_REGISTRY_LOCK) {
            mCompatibilityJournalFile = File.createTempFile(
                COMPATIBILITY_JOURNAL_PREFIX, COMPATIBILITY_JOURNAL_SUFFIX, directory);
            ACTIVE_COMPATIBILITY_JOURNALS.add(mCompatibilityJournalFile.getAbsolutePath());
        }
        try {
            mCompatibilityJournalOutput = new DataOutputStream(new BufferedOutputStream(
                new FileOutputStream(mCompatibilityJournalFile),
                COMPATIBILITY_JOURNAL_BUFFER_BYTES));
            mCompatibilityJournalOutput.writeInt(COMPATIBILITY_JOURNAL_MAGIC);
            return mCompatibilityJournalOutput;
        } catch (IOException error) {
            deleteCompatibilityJournal(mCompatibilityJournalFile);
            mCompatibilityJournalFile = null;
            throw error;
        }
    }

    private void failCompatibilityJournal(IOException error) {
        closeCompatibilityJournalOutput();
        deleteCompatibilityJournal(mCompatibilityJournalFile);
        mCompatibilityJournalFile = null;
        mCompatibilityJournalHasEvents = false;
        mCompatibilityReplayBytes = 0L;
        mCompatibilityReplayFailure = error;
        Logger.logStackTraceWithMessage(mClient, LOG_TAG,
            "Compatibility journal failed; Ghostty remains authoritative", error);
    }

    private void closeCompatibilityJournalOutput() {
        DataOutputStream output = mCompatibilityJournalOutput;
        mCompatibilityJournalOutput = null;
        if (output == null) return;
        try {
            output.close();
        } catch (IOException error) {
            if (mCompatibilityReplayFailure == null) mCompatibilityReplayFailure = error;
        }
    }

    private static void deleteCompatibilityJournal(File journal) {
        if (journal == null) return;
        synchronized (COMPATIBILITY_JOURNAL_REGISTRY_LOCK) {
            ACTIVE_COMPATIBILITY_JOURNALS.remove(journal.getAbsolutePath());
            if (journal.exists()) journal.delete();
        }
    }

    /** Result of deleting crash-orphaned compatibility journals from the app cache. */
    public static final class CompatibilityJournalCleanupResult {
        public final int deletedFiles;
        public final long deletedBytes;

        CompatibilityJournalCleanupResult(int deletedFiles, long deletedBytes) {
            this.deletedFiles = deletedFiles;
            this.deletedBytes = deletedBytes;
        }
    }

    /**
     * Delete only this pipeline's private temporary journals that are not owned by a live terminal
     * in the current process. The registry lock closes the create-vs-clean race when a new session
     * starts while cleanup is scanning after a prior process crash.
     */
    public static CompatibilityJournalCleanupResult cleanupStaleCompatibilityJournals(
        File directory) {
        if (directory == null || !directory.isDirectory()) {
            return new CompatibilityJournalCleanupResult(0, 0L);
        }
        File[] candidates = directory.listFiles((parent, name) ->
            name.startsWith(COMPATIBILITY_JOURNAL_PREFIX) &&
                name.endsWith(COMPATIBILITY_JOURNAL_SUFFIX));
        if (candidates == null || candidates.length == 0) {
            return new CompatibilityJournalCleanupResult(0, 0L);
        }

        int deletedFiles = 0;
        long deletedBytes = 0L;
        for (File candidate : candidates) {
            if (candidate == null || !candidate.isFile()) continue;
            synchronized (COMPATIBILITY_JOURNAL_REGISTRY_LOCK) {
                if (ACTIVE_COMPATIBILITY_JOURNALS.contains(candidate.getAbsolutePath())) continue;
                long bytes = Math.max(0L, candidate.length());
                if (candidate.delete()) {
                    deletedFiles++;
                    deletedBytes += bytes;
                }
            }
        }
        return new CompatibilityJournalCleanupResult(deletedFiles, deletedBytes);
    }

    private void markCompatibilityForegroundActivity() {
        mCompatibilityReplayLastForegroundNanos = System.nanoTime();
    }

    private void recordResizeForCompatibility(int columns, int rows,
                                              int cellWidth, int cellHeight) {
        if (mCompatibilityReplayFailure != null) return;
        markCompatibilityForegroundActivity();
        try {
            DataOutputStream output = compatibilityJournalOutput();
            output.writeByte(COMPATIBILITY_EVENT_RESIZE);
            output.writeInt(columns);
            output.writeInt(rows);
            output.writeInt(cellWidth);
            output.writeInt(cellHeight);
            mCompatibilityJournalHasEvents = true;
        } catch (IOException error) {
            failCompatibilityJournal(error);
        }
    }

    private void recordResetForCompatibility() {
        if (mCompatibilityReplayFailure != null) return;
        markCompatibilityForegroundActivity();
        try {
            compatibilityJournalOutput().writeByte(COMPATIBILITY_EVENT_RESET);
            mCompatibilityJournalHasEvents = true;
        } catch (IOException error) {
            failCompatibilityJournal(error);
        }
    }

    private void recordColorsForCompatibility(int[] colors) {
        if (mCompatibilityReplayFailure != null) return;
        markCompatibilityForegroundActivity();
        try {
            DataOutputStream output = compatibilityJournalOutput();
            output.writeByte(COMPATIBILITY_EVENT_COLORS);
            output.writeInt(colors.length);
            for (int color : colors) output.writeInt(color);
            mCompatibilityJournalHasEvents = true;
        } catch (IOException error) {
            failCompatibilityJournal(error);
        }
    }

    /** Caller owns this emulator's mutation monitor, making the sealed file immutable. */
    private long enqueueCompatibilityReplayBatch(boolean force) {
        if (!mCompatibilityJournalHasEvents && !force && mCompatibilityEmulator != null) {
            synchronized (mCompatibilityReplayLock) {
                return mCompatibilityReplayEnqueuedGeneration;
            }
        }

        closeCompatibilityJournalOutput();
        if (mCompatibilityReplayFailure != null) {
            throw new IllegalStateException(
                "Compatibility journal is unavailable", mCompatibilityReplayFailure);
        }
        File journal = mCompatibilityJournalFile;
        long bytes = mCompatibilityReplayBytes;
        mCompatibilityJournalFile = null;
        mCompatibilityJournalHasEvents = false;
        mCompatibilityReplayBytes = 0L;
        synchronized (mCompatibilityReplayLock) {
            if (mCompatibilityReplayStop && !mCompatibilityFallback) {
                throw new IllegalStateException("Compatibility checkpoint worker is stopped");
            }
            long generation = ++mCompatibilityReplayEnqueuedGeneration;
            mCompatibilityReplayQueue.addLast(
                new CompatibilityReplayBatch(journal, bytes, generation));
            mCompatibilityReplayQueuedBytes += bytes;
            mCompatibilityReplayHighWaterBytes = Math.max(
                mCompatibilityReplayHighWaterBytes, mCompatibilityReplayQueuedBytes);
            startCompatibilityReplayWorkerLocked();
            mCompatibilityReplayLock.notifyAll();
            return generation;
        }
    }

    private void startCompatibilityReplayWorkerLocked() {
        if (mCompatibilityReplayThread != null) return;
        mCompatibilityReplayStop = false;
        Thread worker = new Thread(this::runCompatibilityReplayWorker,
            "TermuxCompatibilityCheckpoint");
        worker.setDaemon(true);
        mCompatibilityReplayThread = worker;
        worker.start();
    }

    /**
     * Wait until replay cannot steal foreground parser/render time. The pressure escape hatch
     * bounds disk use; an urgent caller is waiting for exact Java recovery state.
     */
    private boolean awaitCompatibilityReplayPermit() {
        boolean interrupted = false;
        boolean permitted;
        synchronized (mCompatibilityReplayLock) {
            while (true) {
                if (mCompatibilityReplayStop) {
                    permitted = false;
                    break;
                }
                if (mCompatibilityReplayUrgent ||
                    mCompatibilityReplayQueuedBytes >= COMPATIBILITY_REPLAY_PRESSURE_BYTES) {
                    permitted = true;
                    break;
                }

                long remaining = COMPATIBILITY_REPLAY_QUIET_NANOS -
                    (System.nanoTime() - mCompatibilityReplayLastForegroundNanos);
                if (remaining <= 0L) {
                    permitted = true;
                    break;
                }

                long waitStarted = System.nanoTime();
                mCompatibilityReplayDeferredWaits++;
                try {
                    long millis = remaining / 1_000_000L;
                    int nanos = (int) (remaining % 1_000_000L);
                    mCompatibilityReplayLock.wait(millis, nanos);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                } finally {
                    mCompatibilityReplayDeferredNanos += Math.max(0L,
                        System.nanoTime() - waitStarted);
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return permitted;
    }

    private boolean replayCompatibilityJournal(File journal, TerminalEmulator compatibility) {
        if (journal == null) return true;
        byte[] bytes = new byte[COMPATIBILITY_REPLAY_BLOCK_BYTES];
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
            new FileInputStream(journal), COMPATIBILITY_JOURNAL_BUFFER_BYTES))) {
            int magic = input.readInt();
            if (magic != COMPATIBILITY_JOURNAL_MAGIC) {
                throw new IOException("Invalid compatibility journal magic");
            }
            while (true) {
                int type;
                try {
                    type = input.readUnsignedByte();
                } catch (EOFException end) {
                    return true;
                }
                // Re-check between bounded records so resumed PTY traffic preempts cold replay
                // within at most 64 KiB of Java parsing.
                if (!awaitCompatibilityReplayPermit()) return false;
                switch (type) {
                    case COMPATIBILITY_EVENT_BYTES: {
                        int length = input.readInt();
                        if (length <= 0 || length > bytes.length) {
                            throw new IOException("Invalid PTY journal record length=" + length);
                        }
                        input.readFully(bytes, 0, length);
                        compatibility.appendJavaOnly(bytes, length);
                        break;
                    }
                    case COMPATIBILITY_EVENT_RESIZE:
                        compatibility.resize(input.readInt(), input.readInt(),
                            input.readInt(), input.readInt());
                        break;
                    case COMPATIBILITY_EVENT_RESET:
                        compatibility.resetJavaState();
                        break;
                    case COMPATIBILITY_EVENT_COLORS: {
                        int count = input.readInt();
                        if (count < 0 || count > compatibility.mColors.mCurrentColors.length) {
                            throw new IOException("Invalid color journal count=" + count);
                        }
                        for (int index = 0; index < count; index++) {
                            compatibility.mColors.mCurrentColors[index] = input.readInt();
                        }
                        break;
                    }
                    default:
                        throw new IOException("Unknown compatibility journal event=" + type);
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Failed to replay compatibility journal", error);
        } finally {
            deleteCompatibilityJournal(journal);
        }
    }

    private void runCompatibilityReplayWorker() {
        try {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (RuntimeException ignored) {
                // Host JVM tests and restrictive devices may reject Android scheduling hints.
            }

            TerminalEmulator compatibility = new TerminalEmulator(
                new CompatibilityOutput(), mInitialColumns, mInitialRows,
                mInitialCellWidthPixels, mInitialCellHeightPixels,
                mConfiguredTranscriptRows, mClient, false, true);
            compatibility.mCursorStyle = mCursorStyle;
            System.arraycopy(mInitialColors, 0, compatibility.mColors.mCurrentColors,
                0, mInitialColors.length);
            synchronized (mCompatibilityReplayLock) {
                mCompatibilityEmulator = compatibility;
                mCompatibilityReplayLock.notifyAll();
            }

            while (true) {
                CompatibilityReplayBatch batch;
                long queuedBytes;
                synchronized (mCompatibilityReplayLock) {
                    while (mCompatibilityReplayQueue.isEmpty() && !mCompatibilityReplayStop) {
                        try {
                            mCompatibilityReplayLock.wait();
                        } catch (InterruptedException ignored) {
                            // Stop state is checked under the same monitor.
                        }
                    }
                    if (mCompatibilityReplayStop && mCompatibilityReplayQueue.isEmpty()) return;
                    batch = mCompatibilityReplayQueue.removeFirst();
                    queuedBytes = mCompatibilityReplayQueuedBytes;
                }

                try {
                    Process.setThreadPriority(queuedBytes > MAX_COMPATIBILITY_REPLAY_BYTES * 2L
                        ? Process.THREAD_PRIORITY_DEFAULT : Process.THREAD_PRIORITY_BACKGROUND);
                } catch (RuntimeException ignored) {
                }

                long started = System.nanoTime();
                long workerThreadId = Thread.currentThread().getId();
                String workerThreadName = Thread.currentThread().getName();
                Trace.beginSection("TermuxJavaCheckpointParse");
                boolean completed;
                try {
                    completed = replayCompatibilityJournal(batch.journal, compatibility);
                    // Checkpoint construction is not a visible presentation. Only scroll edges
                    // accepted by the live authority may move TerminalView's viewport.
                    if (completed) {
                        compatibility.clearScrollCounter();
                        compatibility.clearFullRedrawRequired();
                    }
                } finally {
                    Trace.endSection();
                }
                if (!completed) return;
                long elapsed = Math.max(0L, System.nanoTime() - started);
                String metrics = null;
                synchronized (mCompatibilityReplayLock) {
                    mCompatibilityReplayAppliedGeneration = batch.generation;
                    mCompatibilityReplayQueuedBytes = Math.max(0L,
                        mCompatibilityReplayQueuedBytes - batch.bytes);
                    mCompatibilityReplayAppliedBytes += batch.bytes;
                    mCompatibilityReplayAppliedBatches++;
                    mCompatibilityReplayLastThreadId = workerThreadId;
                    mCompatibilityReplayLastThreadName = workerThreadName;
                    mCompatibilityReplayMaxBatchNanos = Math.max(
                        mCompatibilityReplayMaxBatchNanos, elapsed);
                    long now = System.nanoTime();
                    if (now - mCompatibilityReplayLastLogNanos >=
                        COMPATIBILITY_METRICS_LOG_INTERVAL_NANOS) {
                        mCompatibilityReplayLastLogNanos = now;
                        metrics = compatibilityCheckpointStatusLocked();
                    }
                    mCompatibilityReplayLock.notifyAll();
                }
                if (metrics != null) {
                    Logger.logInfo(mClient, LOG_TAG, "compat-checkpoint-v1 " + metrics);
                }
            }
        } catch (RuntimeException | LinkageError | OutOfMemoryError error) {
            synchronized (mCompatibilityReplayLock) {
                mCompatibilityReplayFailure = error;
                mCompatibilityReplayStop = true;
                mCompatibilityReplayLock.notifyAll();
            }
            Logger.logStackTraceWithMessage(mClient, LOG_TAG,
                "Compatibility checkpoint worker failed; stale Java state will not be shown",
                error);
        } finally {
            synchronized (mCompatibilityReplayLock) {
                if (mCompatibilityReplayThread == Thread.currentThread()) {
                    mCompatibilityReplayThread = null;
                }
                mCompatibilityReplayLock.notifyAll();
            }
        }
    }

    private String compatibilityCheckpointStatusLocked() {
        return "mode=disk-foreground-idle" +
            " thresholdBytes=" + MAX_COMPATIBILITY_REPLAY_BYTES +
            " pressureBytes=" + COMPATIBILITY_REPLAY_PRESSURE_BYTES +
            " queued=" + mCompatibilityReplayQueuedBytes +
            " highWater=" + mCompatibilityReplayHighWaterBytes +
            " appliedBytes=" + mCompatibilityReplayAppliedBytes +
            " batches=" + mCompatibilityReplayAppliedBatches +
            " generation=" + mCompatibilityReplayAppliedGeneration + '/' +
            mCompatibilityReplayEnqueuedGeneration +
            " maxBatchUs=" + (mCompatibilityReplayMaxBatchNanos / 1000L) +
            " deferredWaits=" + mCompatibilityReplayDeferredWaits +
            " deferredUs=" + (mCompatibilityReplayDeferredNanos / 1000L) +
            " urgent=" + mCompatibilityReplayUrgent +
            " thread=" + mCompatibilityReplayLastThreadName + '#' +
            mCompatibilityReplayLastThreadId;
    }

    public synchronized String getCompatibilityCheckpointStatusForDiagnostics() {
        synchronized (mCompatibilityReplayLock) {
            return compatibilityCheckpointStatusLocked() +
                " activeBytes=" + mCompatibilityReplayBytes +
                " activeJournal=" + (mCompatibilityJournalFile != null) +
                " failed=" + (mCompatibilityReplayFailure != null);
        }
    }

    private synchronized TerminalEmulator ensureCompatibilityCurrent() {
        long targetGeneration = enqueueCompatibilityReplayBatch(
            mCompatibilityEmulator == null && !mCompatibilityJournalHasEvents);
        boolean interrupted = false;
        TerminalEmulator compatibility;
        synchronized (mCompatibilityReplayLock) {
            // This API promises an exact, current Java terminal. It is the exceptional recovery
            // path, so finish queued checkpoints immediately instead of observing the idle gate.
            mCompatibilityReplayUrgent = true;
            mCompatibilityReplayLock.notifyAll();
            try {
                while ((mCompatibilityEmulator == null ||
                    mCompatibilityReplayAppliedGeneration < targetGeneration) &&
                    mCompatibilityReplayFailure == null) {
                    try {
                        mCompatibilityReplayLock.wait();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (mCompatibilityReplayFailure != null) {
                    throw new IllegalStateException(
                        "Compatibility checkpoint is unavailable", mCompatibilityReplayFailure);
                }
                compatibility = mCompatibilityEmulator;
            } finally {
                mCompatibilityReplayUrgent = false;
                mCompatibilityReplayLock.notifyAll();
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return compatibility;
    }

    synchronized void recordCompatibilityBytesForTesting(byte[] bytes) {
        recordBytesForCompatibility(bytes, bytes.length);
    }

    synchronized void recordCompatibilityResizeForTesting(int columns, int rows,
                                                          int cellWidth, int cellHeight) {
        recordResizeForCompatibility(columns, rows, cellWidth, cellHeight);
    }

    synchronized void sealCompatibilityCheckpointForTesting() {
        enqueueCompatibilityReplayBatch(false);
    }

    synchronized TerminalEmulator awaitCompatibilityCheckpointForTesting() {
        return ensureCompatibilityCurrent();
    }

    private void stopCompatibilityReplayWorker(boolean discardPending) {
        if (discardPending) {
            closeCompatibilityJournalOutput();
            deleteCompatibilityJournal(mCompatibilityJournalFile);
            mCompatibilityJournalFile = null;
            mCompatibilityJournalHasEvents = false;
            mCompatibilityReplayBytes = 0L;
        }
        Thread worker;
        synchronized (mCompatibilityReplayLock) {
            mCompatibilityReplayStop = true;
            if (discardPending) {
                for (CompatibilityReplayBatch batch : mCompatibilityReplayQueue) {
                    deleteCompatibilityJournal(batch.journal);
                }
                mCompatibilityReplayQueue.clear();
                mCompatibilityReplayQueuedBytes = 0L;
            }
            worker = mCompatibilityReplayThread;
            mCompatibilityReplayLock.notifyAll();
        }
        if (worker != null && worker != Thread.currentThread()) worker.interrupt();
    }

    private synchronized void activateCompatibilityFallback(String reason) {
        TerminalEmulator compatibility = ensureCompatibilityCurrent();
        compatibility.mScrollSignal.set(mScrollSignal.getAndSet(1L));
        mCompatibilityReplayInProgress = false;
        mCompatibilityFallback = true;
        stopCompatibilityReplayWorker(false);
        // Keep the closed wrapper reachable until final disposal. A UI thread may have obtained
        // this Java reference immediately before the authority transition; all backend methods
        // safely return failure after close(), avoiding a null-dereference race.
        GhosttyTerminalBackend retiredBackend = mGhosttyBackend;
        if (retiredBackend != null) retiredBackend.close();
        mGhosttyState = null;
        mColumns = compatibility.mColumns;
        mRows = compatibility.mRows;
        mCellWidthPixels = compatibility.mCellWidthPixels;
        mCellHeightPixels = compatibility.mCellHeightPixels;
        System.arraycopy(compatibility.mColors.mCurrentColors, 0, mColors.mCurrentColors, 0,
            mColors.mCurrentColors.length);
        mFullRedrawRequired.set(true);
        Logger.logWarn(mClient, LOG_TAG,
            "Ghostty authority disabled; Java state restored from PTY journal: " + reason);
    }

    public synchronized void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        setCursorStyle();
        setCursorBlinkState(true);
    }

    public TerminalBuffer getScreen() {
        if (hasGhosttyAuthority()) return ensureCompatibilityCurrent().getScreen();
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.getScreen();
        }
        return mScreen;
    }

    public boolean isAlternateBufferActive() {
        if (hasGhosttyAuthority() && mGhosttyState != null) return mGhosttyState.alternateScreen;
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.isAlternateBufferActive();
        }
        return mScreen == mAltBuffer;
    }

    public int getActiveTranscriptRows() {
        if (hasGhosttyAuthority() && mGhosttyState != null) {
            int configuredHistory = Math.max(0, mConfiguredTranscriptRows - mRows);
            return Math.min(mGhosttyState.scrollbackRows, configuredHistory);
        }
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.getActiveTranscriptRows();
        }
        return mScreen.getActiveTranscriptRows();
    }

    public int getActiveRows() {
        if (hasGhosttyAuthority() && mGhosttyState != null) {
            return mRows + getActiveTranscriptRows();
        }
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.getActiveRows();
        }
        return mScreen.getActiveRows();
    }

    /** Viewport row selected atomically by libghostty-vt's tracked resize anchor. */
    public synchronized int getGhosttyViewportTopRow() {
        return hasGhosttyAuthority() && mGhosttyBackend != null
            ? mGhosttyBackend.viewportTopRow() : 0;
    }

    /** 0=unavailable, 1=exact, 2=clamped at the history/live boundary. */
    public synchronized int getGhosttyResizeAnchorOutcome() {
        return hasGhosttyAuthority() && mGhosttyBackend != null
            ? mGhosttyBackend.lastResizeAnchorOutcome() : 0;
    }

    public synchronized String getGhosttyResizeAnchorStatusForDiagnostics() {
        return hasGhosttyAuthority() && mGhosttyBackend != null
            ? mGhosttyBackend.resizeAnchorStatus() : "authority=java outcome=0";
    }

    public synchronized boolean isGhosttyResizeAnchorCommitValidForDiagnostics() {
        return hasGhosttyAuthority() && mGhosttyBackend != null &&
            mGhosttyBackend.lastResizeAnchorCommitValid();
    }

    public long getContentRevision() {
        if (hasGhosttyAuthority() && mGhosttyState != null) return mGhosttyState.generation;
        return getScreen().getContentRevision();
    }

    public int getCurrentForegroundColor() {
        if (hasGhosttyAuthority() && mGhosttyState != null) return mGhosttyState.foregroundColor;
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND];
        }
        return mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND];
    }

    public int getCurrentBackgroundColor() {
        if (hasGhosttyAuthority() && mGhosttyState != null) return mGhosttyState.backgroundColor;
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND];
        }
        return mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND];
    }

    public synchronized void onDefaultColorsChanged() {
        if (hasGhosttyAuthority()) {
            int[] colors = mColors.mCurrentColors.clone();
            recordColorsForCompatibility(colors);
            if (!mGhosttyBackend.setColors(colors)) {
                activateCompatibilityFallback("native palette update failure");
                return;
            }
            mGhosttyState = mGhosttyBackend.state();
            mFullRedrawRequired.set(true);
        } else if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            System.arraycopy(mColors.mCurrentColors, 0,
                mCompatibilityEmulator.mColors.mCurrentColors, 0,
                mColors.mCurrentColors.length);
        }
    }

    private int getTerminalTranscriptRows(Integer transcriptRows) {
        if (transcriptRows == null || transcriptRows < TERMINAL_TRANSCRIPT_ROWS_MIN || transcriptRows > TERMINAL_TRANSCRIPT_ROWS_MAX)
            return DEFAULT_TERMINAL_TRANSCRIPT_ROWS;
        else
            return transcriptRows;
    }

    /**
     * @param mouseButton one of the MOUSE_* constants of this class.
     */
    public byte[] encodeKey(int androidKeyCode, int keyModifiers, int action) {
        return hasGhosttyAuthority()
            ? mGhosttyBackend.encodeKey(androidKeyCode, keyModifiers, action)
            : null;
    }

    public byte[] encodeCodePoint(int codePoint, boolean controlDown, boolean altDown) {
        int modifiers = 0;
        if (controlDown) modifiers |= KeyHandler.KEYMOD_CTRL;
        if (altDown) modifiers |= KeyHandler.KEYMOD_ALT;
        int unshifted = codePoint >= 'A' && codePoint <= 'Z' ? codePoint - 'A' + 'a' : codePoint;
        return encodeCodePoint(0, 1, codePoint, unshifted, modifiers);
    }

    public byte[] encodeCodePoint(int androidKeyCode, int action, int codePoint,
                                  int unshiftedCodePoint, int keyModifiers) {
        if (!hasGhosttyAuthority() || action < 0 || action > 2 ||
            !Character.isValidCodePoint(codePoint) ||
            (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
            return null;
        }
        int unshifted = unshiftedCodePoint;
        if (!Character.isValidCodePoint(unshifted) ||
            (unshifted >= Character.MIN_SURROGATE && unshifted <= Character.MAX_SURROGATE)) {
            unshifted = codePoint >= 'A' && codePoint <= 'Z'
                ? codePoint - 'A' + 'a' : codePoint;
        }
        return mGhosttyBackend.encodeText(
            new String(Character.toChars(codePoint)), androidKeyCode, action,
            unshifted, keyModifiers);
    }

    public void sendMouseEvent(int mouseButton, int column, int row, boolean pressed) {
        if (column < 1) column = 1;
        if (column > mColumns) column = mColumns;
        if (row < 1) row = 1;
        if (row > mRows) row = mRows;

        if (hasGhosttyAuthority()) {
            byte[] encoded = mGhosttyBackend.encodeMouse(mouseButton, column, row, pressed);
            if (encoded != null) {
                if (encoded.length > 0) mSession.write(encoded, 0, encoded.length);
                return;
            }
        }

        if (mouseButton == MOUSE_LEFT_BUTTON_MOVED && !isMouseMotionTrackingActive()) {
            // Do not send tracking.
        } else if (isMouseSgrProtocolActive()) {
            mSession.write(String.format("\033[<%d;%d;%d" + (pressed ? 'M' : 'm'), mouseButton, column, row));
        } else {
            mouseButton = pressed ? mouseButton : 3; // 3 for release of all buttons.
            // Clip to screen, and clip to the limits of 8-bit data.
            boolean out_of_bounds = column > 255 - 32 || row > 255 - 32;
            if (!out_of_bounds) {
                byte[] data = {'\033', '[', 'M', (byte) (32 + mouseButton), (byte) (32 + column), (byte) (32 + row)};
                mSession.write(data, 0, data.length);
            }
        }
    }

    public synchronized void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        resize(columns, rows, cellWidthPixels, cellHeightPixels, 0, -1, -1, -1);
    }

    public synchronized void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels,
                                    int viewportTopRow, int anchorColumn,
                                    int anchorViewportRow, int targetViewportRow) {
        if (mGhosttyBackend != null && !GhosttyTerminalBackend.isProductionEnabled() &&
            !mReplayOnly && !mCompatibilityFallback) {
            activateCompatibilityFallback("process kill switch");
        }
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            mCompatibilityEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            mColumns = mCompatibilityEmulator.mColumns;
            mRows = mCompatibilityEmulator.mRows;
            mCellWidthPixels = cellWidthPixels;
            mCellHeightPixels = cellHeightPixels;
            mFullRedrawRequired.set(true);
            return;
        }

        this.mCellWidthPixels = cellWidthPixels;
        this.mCellHeightPixels = cellHeightPixels;
        final boolean ghosttyAuthority = hasGhosttyAuthority();
        final int visibleScrollbackRows = Math.max(0, mConfiguredTranscriptRows - rows);
        if (ghosttyAuthority) {
            recordResizeForCompatibility(columns, rows, cellWidthPixels, cellHeightPixels);
            if (!mGhosttyBackend.resize(columns, rows, cellWidthPixels, cellHeightPixels,
                viewportTopRow, anchorColumn, anchorViewportRow, targetViewportRow,
                visibleScrollbackRows)) {
                activateCompatibilityFallback("native resize failure");
                return;
            }
            mGhosttyState = mGhosttyBackend.state();
        } else if (mGhosttyBackend != null) {
            mGhosttyBackend.resize(columns, rows, cellWidthPixels, cellHeightPixels,
                0, -1, -1, -1, visibleScrollbackRows);
            mGhosttyState = mGhosttyBackend.state();
        }

        if (mRows == rows && mColumns == columns) {
            return;
        } else if (columns < 2 || rows < 2) {
            throw new IllegalArgumentException("rows=" + rows + ", columns=" + columns);
        }

        if (mRows != rows) {
            mRows = rows;
            mTopMargin = 0;
            mBottomMargin = mRows;
        }
        if (mColumns != columns) {
            int oldColumns = mColumns;
            mColumns = columns;
            boolean[] oldTabStop = mTabStop;
            mTabStop = new boolean[mColumns];
            setDefaultTabStops();
            int toTransfer = Math.min(oldColumns, columns);
            System.arraycopy(oldTabStop, 0, mTabStop, 0, toTransfer);
            mLeftMargin = 0;
            mRightMargin = mColumns;
        }

        if (shouldReflowDormantJavaScreen(ghosttyAuthority)) {
            resizeScreen();
        } else {
            // Ghostty has already performed the authoritative reflow above. The owner Java parser
            // is dormant in production mode; an independently journaled compatibility emulator is
            // rebuilt off-thread and becomes the only Java screen if fallback is activated.
            // Reflowing this stale owner buffer as well doubled grid-resize work during live pinch.
            mGhosttyDormantJavaResizeSkips++;
        }
        mFullRedrawRequired.set(true);
    }

    static boolean shouldReflowDormantJavaScreen(boolean ghosttyAuthority) {
        return !ghosttyAuthority;
    }

    public synchronized String getGhosttyResizeStatusForDiagnostics() {
        return "authority=" + (hasGhosttyAuthority() ? "ghostty" : "java") +
            " dormantJavaResizeSkips=" + mGhosttyDormantJavaResizeSkips +
            " grid=" + mColumns + 'x' + mRows +
            " cell=" + mCellWidthPixels + 'x' + mCellHeightPixels;
    }

    public synchronized long getGhosttyDormantJavaResizeSkipsForDiagnostics() {
        return mGhosttyDormantJavaResizeSkips;
    }

    public synchronized int getCellWidthPixelsForDiagnostics() {
        return mCellWidthPixels;
    }

    public synchronized int getCellHeightPixelsForDiagnostics() {
        return mCellHeightPixels;
    }

    synchronized boolean hasExactGeometry(int columns, int rows,
                                          int cellWidthPixels, int cellHeightPixels) {
        return mColumns == columns && mRows == rows &&
            mCellWidthPixels == cellWidthPixels && mCellHeightPixels == cellHeightPixels;
    }

    private void resizeScreen() {
        final int[] cursor = {mCursorCol, mCursorRow};
        int newTotalRows = (mScreen == mAltBuffer) ? mRows : mMainBuffer.mTotalRows;
        mScreen.resize(mColumns, mRows, newTotalRows, cursor, getStyle(), isAlternateBufferActive());
        mCursorCol = cursor[0];
        mCursorRow = cursor[1];
    }

    public int getCursorRow() {
        if (hasGhosttyAuthority() && mGhosttyState != null) return mGhosttyState.cursorRow;
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.getCursorRow();
        }
        return mCursorRow;
    }

    public int getCursorCol() {
        if (hasGhosttyAuthority() && mGhosttyState != null) return mGhosttyState.cursorColumn;
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.getCursorCol();
        }
        return mCursorCol;
    }

    /** Get the terminal cursor style. It will be one of {@link #TERMINAL_CURSOR_STYLES_LIST} */
    public int getCursorStyle() {
        if (hasGhosttyAuthority() && mGhosttyState != null) return mGhosttyState.cursorStyle;
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.getCursorStyle();
        }
        return mCursorStyle;
    }

    /** Set the terminal cursor style. */
    public synchronized void setCursorStyle() {
        Integer cursorStyle = null;

        // A compatibility checkpoint may be built on the dedicated PTY parser after native
        // failure. Never invoke application preference/UI clients from that worker; the owning
        // emulator copies its already-resolved cursor style into the replay emulator.
        if (mReplayOnly) {
            cursorStyle = mCursorStyle;
        } else if (mClient != null) {
            cursorStyle = mClient.getTerminalCursorStyle();
        }

        if (cursorStyle == null || !Arrays.asList(TERMINAL_CURSOR_STYLES_LIST).contains(cursorStyle))
            mCursorStyle = DEFAULT_TERMINAL_CURSOR_STYLE;
        else
            mCursorStyle = cursorStyle;

        if (mCompatibilityEmulator != null) {
            mCompatibilityEmulator.mCursorStyle = mCursorStyle;
        }
        if (hasGhosttyAuthority()) {
            if (!mGhosttyBackend.setDefaultCursorStyle(mCursorStyle)) {
                activateCompatibilityFallback("native cursor style update failure");
            } else {
                mGhosttyState = mGhosttyBackend.state();
                mFullRedrawRequired.set(true);
            }
        }
    }

    public boolean isReverseVideo() {
        if (hasGhosttyAuthority() && mGhosttyState != null) {
            return mGhosttyState.mode(GhosttyTerminalBackend.MODE_REVERSE_VIDEO);
        }
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.isReverseVideo();
        }
        return isDecsetInternalBitSet(DECSET_BIT_REVERSE_VIDEO);
    }



    public boolean isCursorEnabled() {
        if (hasGhosttyAuthority() && mGhosttyState != null) return mGhosttyState.cursorVisible;
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.isCursorEnabled();
        }
        return isDecsetInternalBitSet(DECSET_BIT_CURSOR_ENABLED);
    }
    public boolean shouldCursorBeVisible() {
        if (!isCursorEnabled())
            return false;
        else
            return mCursorBlinkingEnabled ? mCursorBlinkState : true;
    }

    public void setCursorBlinkingEnabled(boolean cursorBlinkingEnabled) {
        this.mCursorBlinkingEnabled = cursorBlinkingEnabled;
    }

    public void setCursorBlinkState(boolean cursorBlinkState) {
        this.mCursorBlinkState = cursorBlinkState;
    }



    public boolean isKeypadApplicationMode() {
        if (hasGhosttyAuthority() && mGhosttyState != null) {
            return mGhosttyState.mode(GhosttyTerminalBackend.MODE_KEYPAD);
        }
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.isKeypadApplicationMode();
        }
        return isDecsetInternalBitSet(DECSET_BIT_APPLICATION_KEYPAD);
    }

    public boolean isCursorKeysApplicationMode() {
        if (hasGhosttyAuthority() && mGhosttyState != null) {
            return mGhosttyState.mode(GhosttyTerminalBackend.MODE_CURSOR_KEYS);
        }
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.isCursorKeysApplicationMode();
        }
        return isDecsetInternalBitSet(DECSET_BIT_APPLICATION_CURSOR_KEYS);
    }

    /** If mouse events are being sent as escape codes to the terminal. */
    public boolean isMouseTrackingActive() {
        if (hasGhosttyAuthority() && mGhosttyState != null) {
            return mGhosttyState.mode(GhosttyTerminalBackend.MODE_MOUSE);
        }
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.isMouseTrackingActive();
        }
        return isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE) || isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT);
    }

    private boolean isMouseMotionTrackingActive() {
        if (hasGhosttyAuthority() && mGhosttyState != null) {
            return mGhosttyState.mode(GhosttyTerminalBackend.MODE_MOUSE_MOTION);
        }
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.isMouseMotionTrackingActive();
        }
        return isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT);
    }

    private boolean isMouseSgrProtocolActive() {
        if (hasGhosttyAuthority() && mGhosttyState != null) {
            return mGhosttyState.mode(GhosttyTerminalBackend.MODE_MOUSE_SGR);
        }
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.isMouseSgrProtocolActive();
        }
        return isDecsetInternalBitSet(DECSET_BIT_MOUSE_PROTOCOL_SGR);
    }

    public boolean shouldSendFocusEvents() {
        if (hasGhosttyAuthority() && mGhosttyState != null) {
            return mGhosttyState.mode(GhosttyTerminalBackend.MODE_FOCUS);
        }
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.shouldSendFocusEvents();
        }
        return isDecsetInternalBitSet(DECSET_BIT_SEND_FOCUS_EVENTS);
    }

    /** Whether the child process has suspended visible updates with DECSET 2026. */
    public boolean isSynchronizedOutputActive() {
        if (hasGhosttyAuthority() && mGhosttyState != null) {
            return mGhosttyState.mode(GhosttyTerminalBackend.MODE_SYNC_OUTPUT);
        }
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.isSynchronizedOutputActive();
        }
        return isDecsetInternalBitSet(DECSET_BIT_SYNCHRONIZED_OUTPUT);
    }

    /** Release a synchronized update that exceeded the presentation timeout. */
    synchronized void forceFinishSynchronizedOutput() {
        if (hasGhosttyAuthority()) {
            byte[] syncOff = {0x1b, '[', '?', '2', '0', '2', '6', 'l'};
            recordBytesForCompatibility(syncOff, syncOff.length);
            if (!mGhosttyBackend.setMode(2026, false)) {
                activateCompatibilityFallback("synchronized-output release failure");
                return;
            }
            mGhosttyState = mGhosttyBackend.state();
            mFullRedrawRequired.set(true);
            return;
        }
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            mCompatibilityEmulator.forceFinishSynchronizedOutput();
            return;
        }
        finishSynchronizedOutput();
        setDecsetinternalBit(DECSET_BIT_SYNCHRONIZED_OUTPUT, false);
    }

    private void beginSynchronizedOutput() {
        mSynchronizedOutputBuffer = mScreen;
        mSynchronizedOutputBuffer.beginSynchronizedOutput();
        mSynchronizedOutputCursorRow = mCursorRow;
        mSynchronizedOutputCursorCol = mCursorCol;
        mSynchronizedOutputCursorStyle = mCursorStyle;
        mSynchronizedOutputCursorVisible = shouldCursorBeVisible();
    }

    private void finishSynchronizedOutput() {
        TerminalBuffer synchronizedBuffer = mSynchronizedOutputBuffer;
        if (synchronizedBuffer == null) return;
        mSynchronizedOutputBuffer = null;

        if (synchronizedBuffer == mScreen) {
            synchronizedBuffer.finishSynchronizedOutput();
        } else {
            synchronizedBuffer.cancelSynchronizedOutputSnapshot();
            mScreen.markAllScreenRowsDirty();
        }

        boolean cursorVisible = shouldCursorBeVisible();
        boolean cursorChanged = mSynchronizedOutputCursorVisible != cursorVisible ||
            (cursorVisible && (mSynchronizedOutputCursorRow != mCursorRow ||
                mSynchronizedOutputCursorCol != mCursorCol ||
                mSynchronizedOutputCursorStyle != mCursorStyle));
        if (cursorChanged && synchronizedBuffer == mScreen) {
            if (mSynchronizedOutputCursorVisible) {
                mScreen.markDirtyRows(mSynchronizedOutputCursorRow, mSynchronizedOutputCursorRow + 1);
            }
            if (cursorVisible) mScreen.markDirtyRows(mCursorRow, mCursorRow + 1);
        }
    }

    public void onHostWindowFocusChanged(boolean hasFocus) {
        if (!shouldSendFocusEvents()) return;
        if (hasGhosttyAuthority()) {
            byte[] encoded = mGhosttyBackend.encodeFocus(hasFocus);
            if (encoded != null) {
                if (encoded.length > 0) mSession.write(encoded, 0, encoded.length);
                return;
            }
        }
        mSession.write(hasFocus ? "\033[I" : "\033[O");
    }

    private void setDefaultTabStops() {
        for (int i = 0; i < mColumns; i++)
            mTabStop[i] = (i & 7) == 0 && i != 0;
    }

    /**
     * Atomically accept one PTY chunk for the off-main Ghostty parser.
     *
     * @param buffer a byte array containing the bytes to be processed
     * @param length the number of bytes in the array to process
     * @return true when this chunk was consumed by Ghostty (including a fallback triggered during
     *         the write), false when the caller must enqueue it for the main-thread Java parser.
     */
    synchronized boolean appendPtyFromWorker(byte[] buffer, int length) {
        if (buffer == null || length < 0 || length > buffer.length) {
            throw new IllegalArgumentException("Invalid terminal input length=" + length);
        }
        if (length == 0) return true;
        if (!hasGhosttyAuthority()) return false;
        // Keep the trace boundary inside the authority check. This makes the marker evidentiary:
        // a Java-fallback chunk can never be reported as a Ghostty parse merely because it passed
        // through the session router.
        Trace.beginSection("TermuxGhosttyPtyParse");
        try {
            append(buffer, length);
            return true;
        } finally {
            Trace.endSection();
        }
    }

    public synchronized void append(byte[] buffer, int length) {
        if (buffer == null || length < 0 || length > buffer.length) {
            throw new IllegalArgumentException("Invalid terminal input length=" + length);
        }
        if (length == 0) return;

        if (mGhosttyBackend != null && !GhosttyTerminalBackend.isProductionEnabled() &&
            !mReplayOnly && !mCompatibilityFallback) {
            activateCompatibilityFallback("process kill switch");
        }
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            mCompatibilityEmulator.appendJavaOnly(buffer, length);
            return;
        }
        if (hasGhosttyAuthority()) {
            int oldScrollback = mGhosttyState == null ? 0 : mGhosttyState.scrollbackRows;
            recordBytesForCompatibility(buffer, length);
            if (!mGhosttyBackend.write(buffer, length)) {
                activateCompatibilityFallback("native write failure");
                return;
            }
            if (!GhosttyTerminalBackend.isProductionEnabled() && !mReplayOnly) {
                activateCompatibilityFallback("OSC 8900 process kill switch");
                return;
            }
            GhosttyTerminalBackend.State nextState = mGhosttyBackend.state();
            if (nextState != null) {
                int scrolled = Math.max(0, nextState.scrollbackRows - oldScrollback);
                if (scrolled > 0) {
                    recordScroll(scrolled, true);
                }
                mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] =
                    nextState.foregroundColor;
                mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] =
                    nextState.backgroundColor;
                mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] =
                    nextState.cursorColor;
            }
            // Volatile publication comes after all related counters and palette writes.
            mGhosttyState = nextState;
            return;
        }
        if (mGhosttyBackend != null) {
            mGhosttyBackend.write(buffer, length);
            mGhosttyState = mGhosttyBackend.state();
        }
        appendJavaOnly(buffer, length);
    }

    private void appendJavaOnly(byte[] buffer, int length) {
        int rangeCount = TerminalNativeAccelerator.scanAsciiRuns(
            buffer, length, mAsciiRunStorage, mAsciiRunScratch);
        boolean nativeClassified = mAsciiRunScratch.get(0) >= 0;
        int nativeEmittedBytes = appendWithAsciiRuns(buffer, length, rangeCount, nativeClassified);
        if (nativeClassified) {
            mNativeAsciiScanCalls++;
            mNativeAsciiScanBytes += length;
            mNativeAsciiRangeCount += rangeCount;
            mNativeAsciiEmittedBytes += nativeEmittedBytes;
        }
    }

    /** JVM-testable oracle for the NDK whole-chunk classifier integration. */
    void appendWithScalarAsciiClassifierForTesting(byte[] buffer, int length) {
        disableGhosttyForJavaReferenceTesting();
        int rangeCount = TerminalNativeAccelerator.scanAsciiRunsScalar(buffer, length, mAsciiRunScratch);
        appendWithAsciiRuns(buffer, length, rangeCount, true);
    }

    private int appendWithAsciiRuns(byte[] buffer, int length, int rangeCount, boolean classified) {
        int rangeIndex = 0;
        int classifiedRunBytes = 0;
        int classifiedUntil = classified ? mAsciiRunScratch.get(0) : 0;
        for (int i = 0; i < length; ) {
            while (rangeIndex < rangeCount && i >= mAsciiRunScratch.get(2 + rangeIndex * 2)) {
                rangeIndex++;
            }

            int asciiRunLength = 0;
            boolean fromClassifiedRange = rangeIndex < rangeCount &&
                i >= mAsciiRunScratch.get(1 + rangeIndex * 2) &&
                i < mAsciiRunScratch.get(2 + rangeIndex * 2);
            if (fromClassifiedRange && canEmitSafeAsciiRun()) {
                asciiRunLength = Math.min(
                    mAsciiRunScratch.get(2 + rangeIndex * 2) - i,
                    mRightMargin - 1 - mCursorCol
                );
            } else if (!classified || i >= classifiedUntil) {
                // Native scratch capacity can be exhausted by deliberately alternating bytes.
                // Resume the existing semantic fast path for the unclassified suffix.
                asciiRunLength = findSafeAsciiRun(buffer, i, length);
            }
            if (asciiRunLength > 0 && emitSafeAsciiRun(buffer, i, asciiRunLength)) {
                if (fromClassifiedRange) classifiedRunBytes += asciiRunLength;
                i += asciiRunLength;
                continue;
            }
            processByte(buffer[i]);
            i++;
        }
        return classifiedRunBytes;
    }

    /** Scalar parser used by differential tests as the semantic reference for batch fast paths. */
    void appendByteWiseForTesting(byte[] buffer, int length) {
        disableGhosttyForJavaReferenceTesting();
        for (int i = 0; i < length; i++) {
            processByte(buffer[i]);
        }
    }

    private void disableGhosttyForJavaReferenceTesting() {
        if (mGhosttyBackend != null) {
            mGhosttyBackend.close();
            mGhosttyBackend = null;
            mGhosttyState = null;
        }
        stopCompatibilityReplayWorker(true);
    }

    private int findSafeAsciiRun(byte[] buffer, int offset, int length) {
        if (!canEmitSafeAsciiRun()) return 0;

        int maxRunLength = Math.min(length - offset, mRightMargin - 1 - mCursorCol);
        int runLength = 0;
        while (runLength < maxRunLength) {
            int b = buffer[offset + runLength] & 0xff;
            if (b < 0x20 || b >= 0x7f) break;
            runLength++;
        }
        return runLength;
    }

    private boolean canEmitSafeAsciiRun() {
        return mEscapeState == ESC_NONE
            && mUtf8ToFollow == 0
            && !mInsertMode
            && !mAboutToAutoWrap
            && mCursorCol < mRightMargin - 1
            && mLeftMargin == 0
            && mRightMargin == mColumns
            && isDecsetInternalBitSet(DECSET_BIT_AUTOWRAP)
            && !(mUseLineDrawingUsesG0 ? mUseLineDrawingG0 : mUseLineDrawingG1);
    }

    private boolean emitSafeAsciiRun(byte[] buffer, int offset, int length) {
        if (length <= 0) return false;
        if (!mScreen.setAsciiRunIfSimple(
            mCursorCol, mCursorRow, buffer, offset, length, getStyle(), mCurrentHyperlink)) {
            return false;
        }
        // processCodePoint() clears this before every printable scalar character. Keep the batch
        // path bit-for-bit equivalent so a preceding escape parser continuation cannot leak.
        mContinueSequence = false;
        mLastEmittedCodePoint = buffer[offset + length - 1] & 0x7f;
        mCursorCol += length;
        mAboutToAutoWrap = false;
        return true;
    }

    private void processByte(byte byteToProcess) {
        if (mUtf8ToFollow > 0) {
            if ((byteToProcess & 0b11000000) == 0b10000000) {
                // 10xxxxxx, a continuation byte.
                mUtf8InputBuffer[mUtf8Index++] = byteToProcess;
                if (--mUtf8ToFollow == 0) {
                    byte firstByteMask = (byte) (mUtf8Index == 2 ? 0b00011111 : (mUtf8Index == 3 ? 0b00001111 : 0b00000111));
                    int codePoint = (mUtf8InputBuffer[0] & firstByteMask);
                    for (int i = 1; i < mUtf8Index; i++)
                        codePoint = ((codePoint << 6) | (mUtf8InputBuffer[i] & 0b00111111));
                    if (((codePoint <= 0b1111111) && mUtf8Index > 1) || (codePoint < 0b11111111111 && mUtf8Index > 2)
                        || (codePoint < 0b1111111111111111 && mUtf8Index > 3)) {
                        // Overlong encoding.
                        codePoint = UNICODE_REPLACEMENT_CHAR;
                    }

                    mUtf8Index = mUtf8ToFollow = 0;

                    if (codePoint >= 0x80 && codePoint <= 0x9F) {
                        // Sequence decoded to a C1 control character which we ignore. They are
                        // not used nowadays and increases the risk of messing up the terminal state
                        // on binary input. XTerm does not allow them in utf-8:
                        // "It is not possible to use a C1 control obtained from decoding the
                        // UTF-8 text" - http://invisible-island.net/xterm/ctlseqs/ctlseqs.html
                    } else {
                        switch (Character.getType(codePoint)) {
                            case Character.UNASSIGNED:
                            case Character.SURROGATE:
                                codePoint = UNICODE_REPLACEMENT_CHAR;
                        }
                        processCodePoint(codePoint);
                    }
                }
            } else {
                // Not a UTF-8 continuation byte so replace the entire sequence up to now with the replacement char:
                mUtf8Index = mUtf8ToFollow = 0;
                emitCodePoint(UNICODE_REPLACEMENT_CHAR);
                // The Unicode Standard Version 6.2 – Core Specification
                // (http://www.unicode.org/versions/Unicode6.2.0/ch03.pdf):
                // "If the converter encounters an ill-formed UTF-8 code unit sequence which starts with a valid first
                // byte, but which does not continue with valid successor bytes (see Table 3-7), it must not consume the
                // successor bytes as part of the ill-formed subsequence
                // whenever those successor bytes themselves constitute part of a well-formed UTF-8 code unit
                // subsequence."
                processByte(byteToProcess);
            }
        } else {
            if ((byteToProcess & 0b10000000) == 0) { // The leading bit is not set so it is a 7-bit ASCII character.
                processCodePoint(byteToProcess);
                return;
            } else if ((byteToProcess & 0b11100000) == 0b11000000) { // 110xxxxx, a two-byte sequence.
                mUtf8ToFollow = 1;
            } else if ((byteToProcess & 0b11110000) == 0b11100000) { // 1110xxxx, a three-byte sequence.
                mUtf8ToFollow = 2;
            } else if ((byteToProcess & 0b11111000) == 0b11110000) { // 11110xxx, a four-byte sequence.
                mUtf8ToFollow = 3;
            } else {
                // Not a valid UTF-8 sequence start, signal invalid data:
                processCodePoint(UNICODE_REPLACEMENT_CHAR);
                return;
            }
            mUtf8InputBuffer[mUtf8Index++] = byteToProcess;
        }
    }

    public void processCodePoint(int b) {
        // The Application Program-Control (APC) string might be arbitrary non-printable characters, so handle that early.
        if (mEscapeState == ESC_APC) {
            doApc(b);
            return;
        } else if (mEscapeState == ESC_APC_ESCAPE) {
            doApcEscape(b);
            return;
        } else if (mEscapeState == ESC_OSC_DISCARD || mEscapeState == ESC_OSC_DISCARD_ESC) {
            doOscDiscard(b);
            return;
        }

        switch (b) {
            case 0: // Null character (NUL, ^@). Do nothing.
                break;
            case 7: // Bell (BEL, ^G, \a). If in an OSC sequence, BEL may terminate a string; otherwise signal bell.
                if (mEscapeState == ESC_OSC)
                    doOsc(b);
                else
                    mSession.onBell();
                break;
            case 8: // Backspace (BS, ^H).
                if (mLeftMargin == mCursorCol) {
                    // Jump to previous line if it was auto-wrapped.
                    int previousRow = mCursorRow - 1;
                    if (previousRow >= 0 && mScreen.getLineWrap(previousRow)) {
                        mScreen.clearLineWrap(previousRow);
                        setCursorRowCol(previousRow, mRightMargin - 1);
                    }
                } else if (mCursorCol > 0) {
                    setCursorCol(mCursorCol - 1);
                }
                break;
            case 9: // Horizontal tab (HT, \t) - move to next tab stop, but not past edge of screen
                // XXX: Should perhaps use color if writing to new cells. Try with
                //       printf "\033[41m\tXX\033[0m\n"
                // The OSX Terminal.app colors the spaces from the tab red, but xterm does not.
                // Note that Terminal.app only colors on new cells, in e.g.
                //       printf "\033[41m\t\r\033[42m\tXX\033[0m\n"
                // the first cells are created with a red background, but when tabbing over
                // them again with a green background they are not overwritten.
                mCursorCol = nextTabStop(1);
                break;
            case 10: // Line feed (LF, \n).
            case 11: // Vertical tab (VT, \v).
            case 12: // Form feed (FF, \f).
                doLinefeed();
                break;
            case 13: // Carriage return (CR, \r).
                setCursorCol(mLeftMargin);
                break;
            case 14: // Shift Out (Ctrl-N, SO) → Switch to Alternate Character Set. This invokes the G1 character set.
                mUseLineDrawingUsesG0 = false;
                break;
            case 15: // Shift In (Ctrl-O, SI) → Switch to Standard Character Set. This invokes the G0 character set.
                mUseLineDrawingUsesG0 = true;
                break;
            case 24: // CAN.
            case 26: // SUB.
                if (mEscapeState != ESC_NONE) {
                    // FIXME: What is this??
                    mEscapeState = ESC_NONE;
                    emitCodePoint(127);
                }
                break;
            case 27: // ESC
                // Starts an escape sequence unless we're parsing a string
                if (mEscapeState == ESC_P) {
                    // XXX: Ignore escape when reading device control sequence, since it may be part of string terminator.
                    return;
                } else if (mEscapeState != ESC_OSC) {
                    startEscapeSequence();
                } else {
                    doOsc(b);
                }
                break;
            default:
                mContinueSequence = false;
                switch (mEscapeState) {
                    case ESC_NONE:
                        if (b >= 32) emitCodePoint(b);
                        break;
                    case ESC:
                        doEsc(b);
                        break;
                    case ESC_POUND:
                        doEscPound(b);
                        break;
                    case ESC_SELECT_LEFT_PAREN: // Designate G0 Character Set (ISO 2022, VT100).
                        mUseLineDrawingG0 = (b == '0');
                        break;
                    case ESC_SELECT_RIGHT_PAREN: // Designate G1 Character Set (ISO 2022, VT100).
                        mUseLineDrawingG1 = (b == '0');
                        break;
                    case ESC_CSI:
                        doCsi(b);
                        break;
                    case ESC_CSI_UNSUPPORTED_PARAMETER_BYTE:
                    case ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE:
                        doCsiUnsupportedParameterOrIntermediateByte(b);
                        break;
                    case ESC_CSI_EXCLAMATION:
                        if (b == 'p') { // Soft terminal reset (DECSTR, http://vt100.net/docs/vt510-rm/DECSTR).
                            resetJavaState();
                        } else {
                            unknownSequence(b);
                        }
                        break;
                    case ESC_CSI_QUESTIONMARK:
                        doCsiQuestionMark(b);
                        break;
                    case ESC_CSI_BIGGERTHAN:
                        doCsiBiggerThan(b);
                        break;
                    case ESC_CSI_DOLLAR:
                        boolean originMode = isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE);
                        int effectiveTopMargin = originMode ? mTopMargin : 0;
                        int effectiveBottomMargin = originMode ? mBottomMargin : mRows;
                        int effectiveLeftMargin = originMode ? mLeftMargin : 0;
                        int effectiveRightMargin = originMode ? mRightMargin : mColumns;
                        switch (b) {
                            case 'v': // ${CSI}${SRC_TOP}${SRC_LEFT}${SRC_BOTTOM}${SRC_RIGHT}${SRC_PAGE}${DST_TOP}${DST_LEFT}${DST_PAGE}$v"
                                // Copy rectangular area (DECCRA - http://vt100.net/docs/vt510-rm/DECCRA):
                                // "If Pbs is greater than Pts, or Pls is greater than Prs, the terminal ignores DECCRA.
                                // The coordinates of the rectangular area are affected by the setting of origin mode (DECOM).
                                // DECCRA is not affected by the page margins.
                                // The copied text takes on the line attributes of the destination area.
                                // If the value of Pt, Pl, Pb, or Pr exceeds the width or height of the active page, then the value
                                // is treated as the width or height of that page.
                                // If the destination area is partially off the page, then DECCRA clips the off-page data.
                                // DECCRA does not change the active cursor position."
                                int topSource = Math.min(getArg(0, 1, true) - 1 + effectiveTopMargin, mRows);
                                int leftSource = Math.min(getArg(1, 1, true) - 1 + effectiveLeftMargin, mColumns);
                                // Inclusive, so do not subtract one:
                                int bottomSource = Math.min(Math.max(getArg(2, mRows, true) + effectiveTopMargin, topSource), mRows);
                                int rightSource = Math.min(Math.max(getArg(3, mColumns, true) + effectiveLeftMargin, leftSource), mColumns);
                                // int sourcePage = getArg(4, 1, true);
                                int destionationTop = Math.min(getArg(5, 1, true) - 1 + effectiveTopMargin, mRows);
                                int destinationLeft = Math.min(getArg(6, 1, true) - 1 + effectiveLeftMargin, mColumns);
                                // int destinationPage = getArg(7, 1, true);
                                int heightToCopy = Math.min(mRows - destionationTop, bottomSource - topSource);
                                int widthToCopy = Math.min(mColumns - destinationLeft, rightSource - leftSource);
                                mScreen.blockCopy(leftSource, topSource, widthToCopy, heightToCopy, destinationLeft, destionationTop);
                                break;
                            case '{': // ${CSI}${TOP}${LEFT}${BOTTOM}${RIGHT}${"
                                // Selective erase rectangular area (DECSERA - http://www.vt100.net/docs/vt510-rm/DECSERA).
                            case 'x': // ${CSI}${CHAR};${TOP}${LEFT}${BOTTOM}${RIGHT}$x"
                                // Fill rectangular area (DECFRA - http://www.vt100.net/docs/vt510-rm/DECFRA).
                            case 'z': // ${CSI}$${TOP}${LEFT}${BOTTOM}${RIGHT}$z"
                                // Erase rectangular area (DECERA - http://www.vt100.net/docs/vt510-rm/DECERA).
                                boolean erase = b != 'x';
                                boolean selective = b == '{';
                                // Only DECSERA keeps visual attributes, DECERA does not:
                                boolean keepVisualAttributes = erase && selective;
                                int argIndex = 0;
                                int fillChar = erase ? ' ' : getArg(argIndex++, -1, true);
                                // "Pch can be any value from 32 to 126 or from 160 to 255. If Pch is not in this range, then the
                                // terminal ignores the DECFRA command":
                                if ((fillChar >= 32 && fillChar <= 126) || (fillChar >= 160 && fillChar <= 255)) {
                                    // "If the value of Pt, Pl, Pb, or Pr exceeds the width or height of the active page, the value
                                    // is treated as the width or height of that page."
                                    int top = Math.min(getArg(argIndex++, 1, true) + effectiveTopMargin, effectiveBottomMargin + 1);
                                    int left = Math.min(getArg(argIndex++, 1, true) + effectiveLeftMargin, effectiveRightMargin + 1);
                                    int bottom = Math.min(getArg(argIndex++, mRows, true) + effectiveTopMargin, effectiveBottomMargin);
                                    int right = Math.min(getArg(argIndex, mColumns, true) + effectiveLeftMargin, effectiveRightMargin);
                                    long style = getStyle();
                                    for (int row = top - 1; row < bottom; row++)
                                        for (int col = left - 1; col < right; col++)
                                            if (!selective || (TextStyle.decodeEffect(mScreen.getStyleAt(row, col)) & TextStyle.CHARACTER_ATTRIBUTE_PROTECTED) == 0)
                                                mScreen.setChar(col, row, fillChar, keepVisualAttributes ? mScreen.getStyleAt(row, col) : style);
                                }
                                break;
                            case 'r': // "${CSI}${TOP}${LEFT}${BOTTOM}${RIGHT}${ATTRIBUTES}$r"
                                // Change attributes in rectangular area (DECCARA - http://vt100.net/docs/vt510-rm/DECCARA).
                            case 't': // "${CSI}${TOP}${LEFT}${BOTTOM}${RIGHT}${ATTRIBUTES}$t"
                                // Reverse attributes in rectangular area (DECRARA - http://www.vt100.net/docs/vt510-rm/DECRARA).
                                boolean reverse = b == 't';
                                // FIXME: "coordinates of the rectangular area are affected by the setting of origin mode (DECOM)".
                                int top = Math.min(getArg(0, 1, true) - 1, effectiveBottomMargin) + effectiveTopMargin;
                                int left = Math.min(getArg(1, 1, true) - 1, effectiveRightMargin) + effectiveLeftMargin;
                                int bottom = Math.min(getArg(2, mRows, true) + 1, effectiveBottomMargin - 1) + effectiveTopMargin;
                                int right = Math.min(getArg(3, mColumns, true) + 1, effectiveRightMargin - 1) + effectiveLeftMargin;
                                if (mArgIndex >= 4) {
                                    if (mArgIndex >= mArgs.length) mArgIndex = mArgs.length - 1;
                                    for (int i = 4; i <= mArgIndex; i++) {
                                        int bits = 0;
                                        boolean setOrClear = true; // True if setting, false if clearing.
                                        switch (getArg(i, 0, false)) {
                                            case 0: // Attributes off (no bold, no underline, no blink, positive image).
                                                bits = (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE | TextStyle.CHARACTER_ATTRIBUTE_BLINK
                                                    | TextStyle.CHARACTER_ATTRIBUTE_INVERSE);
                                                if (!reverse) setOrClear = false;
                                                break;
                                            case 1: // Bold.
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_BOLD;
                                                break;
                                            case 4: // Underline.
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE;
                                                break;
                                            case 5: // Blink.
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_BLINK;
                                                break;
                                            case 7: // Negative image.
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_INVERSE;
                                                break;
                                            case 22: // No bold.
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_BOLD;
                                                setOrClear = false;
                                                break;
                                            case 24: // No underline.
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE;
                                                setOrClear = false;
                                                break;
                                            case 25: // No blink.
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_BLINK;
                                                setOrClear = false;
                                                break;
                                            case 27: // Positive image.
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_INVERSE;
                                                setOrClear = false;
                                                break;
                                        }
                                        if (reverse && !setOrClear) {
                                            // Reverse attributes in rectangular area ignores non-(1,4,5,7) bits.
                                        } else {
                                            mScreen.setOrClearEffect(bits, setOrClear, reverse, isDecsetInternalBitSet(DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE),
                                                effectiveLeftMargin, effectiveRightMargin, top, left, bottom, right);
                                        }
                                    }
                                } else {
                                    // Do nothing.
                                }
                                break;
                            default:
                                unknownSequence(b);
                        }
                        break;
                    case ESC_CSI_DOUBLE_QUOTE:
                        if (b == 'q') {
                            // http://www.vt100.net/docs/vt510-rm/DECSCA
                            int arg = getArg0(0);
                            if (arg == 0 || arg == 2) {
                                // DECSED and DECSEL can erase characters.
                                mEffect &= ~TextStyle.CHARACTER_ATTRIBUTE_PROTECTED;
                            } else if (arg == 1) {
                                // DECSED and DECSEL cannot erase characters.
                                mEffect |= TextStyle.CHARACTER_ATTRIBUTE_PROTECTED;
                            } else {
                                unknownSequence(b);
                            }
                        } else {
                            unknownSequence(b);
                        }
                        break;
                    case ESC_CSI_SINGLE_QUOTE:
                        if (b == '}') { // Insert Ps Column(s) (default = 1) (DECIC), VT420 and up.
                            int columnsAfterCursor = mRightMargin - mCursorCol;
                            int columnsToInsert = Math.min(getArg0(1), columnsAfterCursor);
                            int columnsToMove = columnsAfterCursor - columnsToInsert;
                            mScreen.blockCopy(mCursorCol, 0, columnsToMove, mRows, mCursorCol + columnsToInsert, 0);
                            blockClear(mCursorCol, 0, columnsToInsert, mRows);
                        } else if (b == '~') { // Delete Ps Column(s) (default = 1) (DECDC), VT420 and up.
                            int columnsAfterCursor = mRightMargin - mCursorCol;
                            int columnsToDelete = Math.min(getArg0(1), columnsAfterCursor);
                            int columnsToMove = columnsAfterCursor - columnsToDelete;
                            mScreen.blockCopy(mCursorCol + columnsToDelete, 0, columnsToMove, mRows, mCursorCol, 0);
                        } else {
                            unknownSequence(b);
                        }
                        break;
                    case ESC_PERCENT:
                        break;
                    case ESC_OSC:
                        doOsc(b);
                        break;
                    case ESC_OSC_ESC:
                        doOscEsc(b);
                        break;
                    case ESC_P:
                        doDeviceControl(b);
                        break;
                    case ESC_CSI_QUESTIONMARK_ARG_DOLLAR:
                        if (b == 'p') {
                            // Request DEC private mode (DECRQM).
                            int mode = getArg0(0);
                            int value;
                            if (mode == 47 || mode == 1047 || mode == 1049) {
                                // This state is carried by mScreen pointer.
                                value = (mScreen == mAltBuffer) ? 1 : 2;
                            } else {
                                int internalBit = mapDecSetBitToInternalBit(mode);
                                if (internalBit != -1) {
                                    value = isDecsetInternalBitSet(internalBit) ? 1 : 2; // 1=set, 2=reset.
                                } else {
                                    Logger.logError(mClient, LOG_TAG, "Got DECRQM for unrecognized private DEC mode=" + mode);
                                    value = 0; // 0=not recognized, 3=permanently set, 4=permanently reset
                                }
                            }
                            mSession.write(String.format(Locale.US, "\033[?%d;%d$y", mode, value));
                        } else {
                            unknownSequence(b);
                        }
                        break;
                    case ESC_CSI_ARGS_SPACE:
                        int arg = getArg0(0);
                        switch (b) {
                            case 'q': // "${CSI}${STYLE} q" - set cursor style (http://www.vt100.net/docs/vt510-rm/DECSCUSR).
                                switch (arg) {
                                    case 0: // Blinking block.
                                    case 1: // Blinking block.
                                    case 2: // Steady block.
                                        mCursorStyle = TERMINAL_CURSOR_STYLE_BLOCK;
                                        break;
                                    case 3: // Blinking underline.
                                    case 4: // Steady underline.
                                        mCursorStyle = TERMINAL_CURSOR_STYLE_UNDERLINE;
                                        break;
                                    case 5: // Blinking bar (xterm addition).
                                    case 6: // Steady bar (xterm addition).
                                        mCursorStyle = TERMINAL_CURSOR_STYLE_BAR;
                                        break;
                                }
                                break;
                            case 't':
                            case 'u':
                                // Set margin-bell volume - ignore.
                                break;
                            default:
                                unknownSequence(b);
                        }
                        break;
                    case ESC_CSI_ARGS_ASTERIX:
                        int attributeChangeExtent = getArg0(0);
                        if (b == 'x' && (attributeChangeExtent >= 0 && attributeChangeExtent <= 2)) {
                            // Select attribute change extent (DECSACE - http://www.vt100.net/docs/vt510-rm/DECSACE).
                            setDecsetinternalBit(DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE, attributeChangeExtent == 2);
                        } else {
                            unknownSequence(b);
                        }
                        break;
                    default:
                        unknownSequence(b);
                        break;
                }
                if (!mContinueSequence) mEscapeState = ESC_NONE;
                break;
        }
    }

    /** When in {@link #ESC_P} ("device control") sequence. */
    private void doDeviceControl(int b) {
        switch (b) {
            case (byte) '\\': // End of ESC \ string Terminator
            {
                String dcs = mOSCOrDeviceControlArgs.toString();
                // DCS $ q P t ST. Request Status String (DECRQSS)
                if (dcs.startsWith("$q")) {
                    if (dcs.equals("$q\"p")) {
                        // DECSCL, conformance level, http://www.vt100.net/docs/vt510-rm/DECSCL:
                        String csiString = "64;1\"p";
                        mSession.write("\033P1$r" + csiString + "\033\\");
                    } else {
                        finishSequenceAndLogError("Unrecognized DECRQSS string: '" + dcs + "'");
                    }
                } else if (dcs.startsWith("+q")) {
                    // Request Termcap/Terminfo String. The string following the "q" is a list of names encoded in
                    // hexadecimal (2 digits per character) separated by ; which correspond to termcap or terminfo key
                    // names.
                    // Two special features are also recognized, which are not key names: Co for termcap colors (or colors
                    // for terminfo colors), and TN for termcap name (or name for terminfo name).
                    // xterm responds with DCS 1 + r P t ST for valid requests, adding to P t an = , and the value of the
                    // corresponding string that xterm would send, or DCS 0 + r P t ST for invalid requests. The strings are
                    // encoded in hexadecimal (2 digits per character).
                    // Example:
                    // :kr=\EOC: ks=\E[?1h\E=: ku=\EOA: le=^H:mb=\E[5m:md=\E[1m:\
                    // where
                    // kd=down-arrow key
                    // kl=left-arrow key
                    // kr=right-arrow key
                    // ku=up-arrow key
                    // #2=key_shome, "shifted home"
                    // #4=key_sleft, "shift arrow left"
                    // %i=key_sright, "shift arrow right"
                    // *7=key_send, "shifted end"
                    // k1=F1 function key

                    // Example: Request for ku is "ESC P + q 6 b 7 5 ESC \", where 6b7d=ku in hexadecimal.
                    // Xterm response in normal cursor mode:
                    // "<27> P 1 + r 6 b 7 5 = 1 B 5 B 4 1" where 0x1B 0x5B 0x41 = 27 91 65 = ESC [ A
                    // Xterm response in application cursor mode:
                    // "<27> P 1 + r 6 b 7 5 = 1 B 5 B 4 1" where 0x1B 0x4F 0x41 = 27 91 65 = ESC 0 A

                    // #4 is "shift arrow left":
                    // *** Device Control (DCS) for '#4'- 'ESC P + q 23 34 ESC \'
                    // Response: <27> P 1 + r 2 3 3 4 = 1 B 5 B 3 1 3 B 3 2 4 4 <27> \
                    // where 0x1B 0x5B 0x31 0x3B 0x32 0x44 = ESC [ 1 ; 2 D
                    // which we find in: TermKeyListener.java: KEY_MAP.put(KEYMOD_SHIFT | KEYCODE_DPAD_LEFT, "\033[1;2D");

                    // See http://h30097.www3.hp.com/docs/base_doc/DOCUMENTATION/V40G_HTML/MAN/MAN4/0178____.HTM for what to
                    // respond, as well as http://www.freebsd.org/cgi/man.cgi?query=termcap&sektion=5#CAPABILITIES for
                    // the meaning of e.g. "ku", "kd", "kr", "kl"

                    for (String part : dcs.substring(2).split(";")) {
                        if (part.length() % 2 == 0) {
                            StringBuilder transBuffer = new StringBuilder();
                            char c;
                            for (int i = 0; i < part.length(); i += 2) {
                                try {
                                    c = (char) Long.decode("0x" + part.charAt(i) + "" + part.charAt(i + 1)).longValue();
                                } catch (NumberFormatException e) {
                                    Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Invalid device termcap/terminfo encoded name \"" + part + "\"", e);
                                    continue;
                                }
                                transBuffer.append(c);
                            }

                            String trans = transBuffer.toString();
                            String responseValue;
                            switch (trans) {
                                case "Co":
                                case "colors":
                                    responseValue = "256"; // Number of colors.
                                    break;
                                case "TN":
                                case "name":
                                    responseValue = "xterm";
                                    break;
                                default:
                                    responseValue = KeyHandler.getCodeFromTermcap(trans, isDecsetInternalBitSet(DECSET_BIT_APPLICATION_CURSOR_KEYS),
                                        isDecsetInternalBitSet(DECSET_BIT_APPLICATION_KEYPAD));
                                    break;
                            }
                            if (responseValue == null) {
                                switch (trans) {
                                    case "%1": // Help key - ignore
                                    case "&8": // Undo key - ignore.
                                        break;
                                    default:
                                        Logger.logWarn(mClient, LOG_TAG, "Unhandled termcap/terminfo name: '" + trans + "'");
                                }
                                // Respond with invalid request:
                                mSession.write("\033P0+r" + part + "\033\\");
                            } else {
                                StringBuilder hexEncoded = new StringBuilder();
                                for (int j = 0; j < responseValue.length(); j++) {
                                    hexEncoded.append(String.format("%02X", (int) responseValue.charAt(j)));
                                }
                                mSession.write("\033P1+r" + part + "=" + hexEncoded + "\033\\");
                            }
                        } else {
                            Logger.logError(mClient, LOG_TAG, "Invalid device termcap/terminfo name of odd length: " + part);
                        }
                    }
                } else {
                    if (LOG_ESCAPE_SEQUENCES)
                        Logger.logError(mClient, LOG_TAG, "Unrecognized device control string: " + dcs);
                }
                finishSequence();
            }
            break;
            default:
                if (mOSCOrDeviceControlArgs.length() > MAX_OSC_STRING_LENGTH) {
                    // Too long.
                    mOSCOrDeviceControlArgs.setLength(0);
                    finishSequence();
                } else {
                    mOSCOrDeviceControlArgs.appendCodePoint(b);
                    continueSequence(mEscapeState);
                }
        }
    }

    /**
     * When in {@link #ESC_APC} (APC, Application Program Command) sequence.
     */
    private void doApc(int b) {
        if (b == 27) {
            continueSequence(ESC_APC_ESCAPE);
        }
        // Eat APC sequences silently for now.
    }

    /**
     * When in {@link #ESC_APC} (APC, Application Program Command) sequence.
     */
    private void doApcEscape(int b) {
        if (b == '\\') {
            // A String Terminator (ST), ending the APC escape sequence.
            finishSequence();
        } else {
            // The Escape character was not the start of a String Terminator (ST),
            // but instead just data inside of the APC escape sequence.
            continueSequence(ESC_APC);
        }
    }

    private int nextTabStop(int numTabs) {
        for (int i = mCursorCol + 1; i < mColumns; i++)
            if (mTabStop[i] && --numTabs == 0) return Math.min(i, mRightMargin);
        return mRightMargin - 1;
    }

    /**
     * Process byte while in the {@link #ESC_CSI_UNSUPPORTED_PARAMETER_BYTE} or
     * {@link #ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE} escape state.
     *
     * Parse unsupported parameter, intermediate and final bytes but ignore them.
     *
     * > For Control Sequence Introducer, ... the ESC [ is followed by
     * > - any number (including none) of "parameter bytes" in the range 0x30–0x3F (ASCII 0–9:;<=>?),
     * > - then by any number of "intermediate bytes" in the range 0x20–0x2F (ASCII space and !"#$%&'()*+,-./),
     * > - then finally by a single "final byte" in the range 0x40–0x7E (ASCII @A–Z[\]^_`a–z{|}~).
     *
     * - https://en.wikipedia.org/wiki/ANSI_escape_code#Control_Sequence_Introducer_commands
     * - https://invisible-island.net/xterm/ecma-48-parameter-format.html#section5.4
     */
    private void doCsiUnsupportedParameterOrIntermediateByte(int b) {
        if (mEscapeState == ESC_CSI_UNSUPPORTED_PARAMETER_BYTE && b >= 0x30 && b <= 0x3F) {
            // Supported `0–9:;>?` or unsupported `<=` parameter byte after an
            // initial unsupported parameter byte in `doCsi()`, or a sequential parameter byte.
            continueSequence(ESC_CSI_UNSUPPORTED_PARAMETER_BYTE);
        } else if (b >= 0x20 && b <= 0x2F) {
            // Optional intermediate byte `!"#$%&'()*+,-./` after parameter or intermediate byte.
            continueSequence(ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE);
        } else if (b >= 0x40 && b <= 0x7E) {
            // Final byte `@A–Z[\]^_`a–z{|}~` after parameter or intermediate byte.
            // Calling `unknownSequence()` would log an error with only a final byte, so ignore it for now.
            finishSequence();
        } else {
            unknownSequence(b);
        }
    }

    /** Process byte while in the {@link #ESC_CSI_QUESTIONMARK} escape state. */
    private void doCsiQuestionMark(int b) {
        switch (b) {
            case 'J': // Selective erase in display (DECSED) - http://www.vt100.net/docs/vt510-rm/DECSED.
            case 'K': // Selective erase in line (DECSEL) - http://vt100.net/docs/vt510-rm/DECSEL.
                mAboutToAutoWrap = false;
                int fillChar = ' ';
                int startCol = -1;
                int startRow = -1;
                int endCol = -1;
                int endRow = -1;
                boolean justRow = (b == 'K');
                switch (getArg0(0)) {
                    case 0: // Erase from the active position to the end, inclusive (default).
                        startCol = mCursorCol;
                        startRow = mCursorRow;
                        endCol = mColumns;
                        endRow = justRow ? (mCursorRow + 1) : mRows;
                        break;
                    case 1: // Erase from start to the active position, inclusive.
                        startCol = 0;
                        startRow = justRow ? mCursorRow : 0;
                        endCol = mCursorCol + 1;
                        endRow = mCursorRow + 1;
                        break;
                    case 2: // Erase all of the display/line.
                        startCol = 0;
                        startRow = justRow ? mCursorRow : 0;
                        endCol = mColumns;
                        endRow = justRow ? (mCursorRow + 1) : mRows;
                        break;
                    default:
                        unknownSequence(b);
                        break;
                }
                long style = getStyle();
                for (int row = startRow; row < endRow; row++) {
                    for (int col = startCol; col < endCol; col++) {
                        if ((TextStyle.decodeEffect(mScreen.getStyleAt(row, col)) & TextStyle.CHARACTER_ATTRIBUTE_PROTECTED) == 0)
                            mScreen.setChar(col, row, fillChar, style);
                    }
                }
                break;
            case 'h':
            case 'l':
                if (mArgIndex >= mArgs.length) mArgIndex = mArgs.length - 1;
                for (int i = 0; i <= mArgIndex; i++)
                    doDecSetOrReset(b == 'h', mArgs[i]);
                break;
            case 'n': // Device Status Report (DSR, DEC-specific).
                switch (getArg0(-1)) {
                    case 6:
                        // Extended Cursor Position (DECXCPR - http://www.vt100.net/docs/vt510-rm/DECXCPR). Page=1.
                        mSession.write(String.format(Locale.US, "\033[?%d;%d;1R", mCursorRow + 1, mCursorCol + 1));
                        break;
                    default:
                        finishSequence();
                        return;
                }
                break;
            case 'r':
            case 's':
                if (mArgIndex >= mArgs.length) mArgIndex = mArgs.length - 1;
                for (int i = 0; i <= mArgIndex; i++) {
                    int externalBit = mArgs[i];
                    int internalBit = mapDecSetBitToInternalBit(externalBit);
                    if (internalBit == -1) {
                        Logger.logWarn(mClient, LOG_TAG, "Ignoring request to save/recall decset bit=" + externalBit);
                    } else {
                        if (b == 's') {
                            mSavedDecSetFlags |= internalBit;
                        } else {
                            doDecSetOrReset((mSavedDecSetFlags & internalBit) != 0, externalBit);
                        }
                    }
                }
                break;
            case '$':
                continueSequence(ESC_CSI_QUESTIONMARK_ARG_DOLLAR);
                return;
            default:
                parseArg(b);
        }
    }

    public void doDecSetOrReset(boolean setting, int externalBit) {
        int internalBit = mapDecSetBitToInternalBit(externalBit);
        if (externalBit == 2026) {
            boolean wasActive = isSynchronizedOutputActive();
            if (setting && !wasActive) beginSynchronizedOutput();
            else if (!setting && wasActive) finishSynchronizedOutput();
        }
        if (internalBit != -1) {
            setDecsetinternalBit(internalBit, setting);
        }
        switch (externalBit) {
            case 1: // Application Cursor Keys (DECCKM).
                break;
            case 3: // Set: 132 column mode (. Reset: 80 column mode. ANSI name: DECCOLM.
                // We don't actually set/reset 132 cols, but we do want the side effects
                // (FIXME: Should only do this if the 95 DECSET bit (DECNCSM) is set, and if changing value?):
                // Sets the left, right, top and bottom scrolling margins to their default positions, which is important for
                // the "reset" utility to really reset the terminal:
                mLeftMargin = mTopMargin = 0;
                mBottomMargin = mRows;
                mRightMargin = mColumns;
                // "DECCOLM resets vertical split screen mode (DECLRMM) to unavailable":
                setDecsetinternalBit(DECSET_BIT_LEFTRIGHT_MARGIN_MODE, false);
                // "Erases all data in page memory":
                blockClear(0, 0, mColumns, mRows);
                setCursorRowCol(0, 0);
                break;
            case 4: // DECSCLM-Scrolling Mode. Ignore.
                break;
            case 5: // Reverse video. Colors of the entire viewport change.
                mFullRedrawRequired.set(true);
                break;
            case 6: // Set: Origin Mode. Reset: Normal Cursor Mode. Ansi name: DECOM.
                if (setting) setCursorPosition(0, 0);
                break;
            case 7: // Wrap-around bit, not specific action.
            case 8: // Auto-repeat Keys (DECARM). Do not implement.
            case 9: // X10 mouse reporting - outdated. Do not implement.
            case 12: // Control cursor blinking - ignore.
            case 25: // Hide/show cursor - no action needed, renderer will check with shouldCursorBeVisible().
                if (mClient != null)
                    if (!mReplayOnly && mClient != null) {
                        mClient.onTerminalCursorStateChange(setting);
                    }
                break;
            case 40: // Allow 80 => 132 Mode, ignore.
            case 45: // TODO: Reverse wrap-around. Implement???
            case 66: // Application keypad (DECNKM).
                break;
            case 69: // Left and right margin mode (DECLRMM).
                if (!setting) {
                    mLeftMargin = 0;
                    mRightMargin = mColumns;
                }
                break;
            case 1000:
            case 1001:
            case 1002:
            case 1003:
            case 1004:
            case 1005: // UTF-8 mouse mode, ignore.
            case 1006: // SGR Mouse Mode
            case 1015:
            case 1034: // Interpret "meta" key, sets eighth bit.
                break;
            case 1048: // Set: Save cursor as in DECSC. Reset: Restore cursor as in DECRC.
                if (setting)
                    saveCursor();
                else
                    restoreCursor();
                break;
            case 47:
            case 1047:
            case 1049: {
                // Set: Save cursor as in DECSC and use Alternate Screen Buffer, clearing it first.
                // Reset: Use Normal Screen Buffer and restore cursor as in DECRC.
                TerminalBuffer newScreen = setting ? mAltBuffer : mMainBuffer;
                if (newScreen != mScreen) {
                    boolean resized = !(newScreen.mColumns == mColumns && newScreen.mScreenRows == mRows);
                    if (setting) saveCursor();
                    mScreen = newScreen;
                    if (!setting) {
                        int col = mSavedStateMain.mSavedCursorCol;
                        int row = mSavedStateMain.mSavedCursorRow;
                        restoreCursor();
                        if (resized) {
                            // Restore cursor position _not_ clipped to current screen (let resizeScreen() handle that):
                            mCursorCol = col;
                            mCursorRow = row;
                        }
                    }
                    // Check if buffer size needs to be updated:
                    if (resized) resizeScreen();
                    // Clear new screen if alt buffer:
                    if (newScreen == mAltBuffer)
                        newScreen.blockSet(0, 0, mColumns, mRows, ' ', getStyle());
                    mFullRedrawRequired.set(true);
                }
                break;
            }
            case 2004:
                // Bracketed paste mode - setting bit is enough.
                break;
            case 2026:
                // Synchronized output - TerminalSession defers screen notifications while set.
                break;
            default:
                unknownParameter(externalBit);
                break;
        }
    }

    private void doCsiBiggerThan(int b) {
        switch (b) {
            case 'c': // "${CSI}>c" or "${CSI}>c". Secondary Device Attributes (DA2).
                // Originally this was used for the terminal to respond with "identification code, firmware version level,
                // and hardware options" (http://vt100.net/docs/vt510-rm/DA2), with the first "41" meaning the VT420
                // terminal type. This is not used anymore, but the second version level field has been changed by xterm
                // to mean it's release number ("patch numbers" listed at http://invisible-island.net/xterm/xterm.log.html),
                // and some applications use it as a feature check:
                // * tmux used to have a "xterm won't reach version 500 for a while so set that as the upper limit" check,
                // and then check "xterm_version > 270" if rectangular area operations such as DECCRA could be used.
                // * vim checks xterm version number >140 for "Request termcap/terminfo string" functionality >276 for SGR
                // mouse report.
                // The third number is a keyboard identifier not used nowadays.
                mSession.write("\033[>41;320;0c");
                break;
            case 'm':
                // https://bugs.launchpad.net/gnome-terminal/+bug/96676/comments/25
                // Depending on the first number parameter, this can set one of the xterm resources
                // modifyKeyboard, modifyCursorKeys, modifyFunctionKeys and modifyOtherKeys.
                // http://invisible-island.net/xterm/manpage/xterm.html#RESOURCES

                // * modifyKeyboard (parameter=1):
                // Normally xterm makes a special case regarding modifiers (shift, control, etc.) to handle special keyboard
                // layouts (legacy and vt220). This is done to provide compatible keyboards for DEC VT220 and related
                // terminals that implement user-defined keys (UDK).
                // The bits of the resource value selectively enable modification of the given category when these keyboards
                // are selected. The default is "0":
                // (0) The legacy/vt220 keyboards interpret only the Control-modifier when constructing numbered
                // function-keys. Other special keys are not modified.
                // (1) allows modification of the numeric keypad
                // (2) allows modification of the editing keypad
                // (4) allows modification of function-keys, overrides use of Shift-modifier for UDK.
                // (8) allows modification of other special keys

                // * modifyCursorKeys (parameter=2):
                // Tells how to handle the special case where Control-, Shift-, Alt- or Meta-modifiers are used to add a
                // parameter to the escape sequence returned by a cursor-key. The default is "2".
                // - Set it to -1 to disable it.
                // - Set it to 0 to use the old/obsolete behavior.
                // - Set it to 1 to prefix modified sequences with CSI.
                // - Set it to 2 to force the modifier to be the second parameter if it would otherwise be the first.
                // - Set it to 3 to mark the sequence with a ">" to hint that it is private.

                // * modifyFunctionKeys (parameter=3):
                // Tells how to handle the special case where Control-, Shift-, Alt- or Meta-modifiers are used to add a
                // parameter to the escape sequence returned by a (numbered) function-
                // key. The default is "2". The resource values are similar to modifyCursorKeys:
                // Set it to -1 to permit the user to use shift- and control-modifiers to construct function-key strings
                // using the normal encoding scheme.
                // - Set it to 0 to use the old/obsolete behavior.
                // - Set it to 1 to prefix modified sequences with CSI.
                // - Set it to 2 to force the modifier to be the second parameter if it would otherwise be the first.
                // - Set it to 3 to mark the sequence with a ">" to hint that it is private.
                // If modifyFunctionKeys is zero, xterm uses Control- and Shift-modifiers to allow the user to construct
                // numbered function-keys beyond the set provided by the keyboard:
                // (Control) adds the value given by the ctrlFKeys resource.
                // (Shift) adds twice the value given by the ctrlFKeys resource.
                // (Control/Shift) adds three times the value given by the ctrlFKeys resource.
                //
                // As a special case, legacy (when oldFunctionKeys is true) or vt220 (when sunKeyboard is true)
                // keyboards interpret only the Control-modifier when constructing numbered function-keys.
                // This is done to provide compatible keyboards for DEC VT220 and related terminals that
                // implement user-defined keys (UDK).

                // * modifyOtherKeys (parameter=4):
                // Like modifyCursorKeys, tells xterm to construct an escape sequence for other keys (such as "2") when
                // modified by Control-, Alt- or Meta-modifiers. This feature does not apply to function keys and
                // well-defined keys such as ESC or the control keys. The default is "0".
                // (0) disables this feature.
                // (1) enables this feature for keys except for those with well-known behavior, e.g., Tab, Backarrow and
                // some special control character cases, e.g., Control-Space to make a NUL.
                // (2) enables this feature for keys including the exceptions listed.
                Logger.logError(mClient, LOG_TAG, "(ignored) CSI > MODIFY RESOURCE: " + getArg0(-1) + " to " + getArg1(-1));
                break;
            default:
                parseArg(b);
                break;
        }
    }

    private void startEscapeSequence() {
        mEscapeState = ESC;
        mArgIndex = 0;
        Arrays.fill(mArgs, -1);
        mArgsSubParamsBitSet = 0;
    }

    private void doLinefeed() {
        boolean belowScrollingRegion = mCursorRow >= mBottomMargin;
        int newCursorRow = mCursorRow + 1;
        if (belowScrollingRegion) {
            // Move down (but not scroll) as long as we are above the last row.
            if (mCursorRow != mRows - 1) {
                setCursorRow(newCursorRow);
            }
        } else {
            if (newCursorRow == mBottomMargin) {
                scrollDownOneLine();
                newCursorRow = mBottomMargin - 1;
            }
            setCursorRow(newCursorRow);
        }
    }

    private void continueSequence(int state) {
        mEscapeState = state;
        mContinueSequence = true;
    }

    private void doEscPound(int b) {
        switch (b) {
            case '8': // Esc # 8 - DEC screen alignment test - fill screen with E's.
                mScreen.blockSet(0, 0, mColumns, mRows, 'E', getStyle());
                break;
            default:
                unknownSequence(b);
                break;
        }
    }

    /** Encountering a character in the {@link #ESC} state. */
    private void doEsc(int b) {
        switch (b) {
            case '#':
                continueSequence(ESC_POUND);
                break;
            case '(':
                continueSequence(ESC_SELECT_LEFT_PAREN);
                break;
            case ')':
                continueSequence(ESC_SELECT_RIGHT_PAREN);
                break;
            case '6': // Back index (http://www.vt100.net/docs/vt510-rm/DECBI). Move left, insert blank column if start.
                if (mCursorCol > mLeftMargin) {
                    mCursorCol--;
                } else {
                    int rows = mBottomMargin - mTopMargin;
                    mScreen.blockCopy(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin - 1, rows, mLeftMargin + 1, mTopMargin);
                    mScreen.blockSet(mLeftMargin, mTopMargin, 1, rows, ' ', TextStyle.encode(mForeColor, mBackColor, 0));
                }
                break;
            case '7': // DECSC save cursor - http://www.vt100.net/docs/vt510-rm/DECSC
                saveCursor();
                break;
            case '8': // DECRC restore cursor - http://www.vt100.net/docs/vt510-rm/DECRC
                restoreCursor();
                break;
            case '9': // Forward Index (http://www.vt100.net/docs/vt510-rm/DECFI). Move right, insert blank column if end.
                if (mCursorCol < mRightMargin - 1) {
                    mCursorCol++;
                } else {
                    int rows = mBottomMargin - mTopMargin;
                    mScreen.blockCopy(mLeftMargin + 1, mTopMargin, mRightMargin - mLeftMargin - 1, rows, mLeftMargin, mTopMargin);
                    mScreen.blockSet(mRightMargin - 1, mTopMargin, 1, rows, ' ', TextStyle.encode(mForeColor, mBackColor, 0));
                }
                break;
            case 'c': // RIS - Reset to Initial State (http://vt100.net/docs/vt510-rm/RIS).
                resetJavaState();
                mMainBuffer.clearTranscript();
                blockClear(0, 0, mColumns, mRows);
                setCursorPosition(0, 0);
                break;
            case 'D': // INDEX
                doLinefeed();
                break;
            case 'E': // Next line (http://www.vt100.net/docs/vt510-rm/NEL).
                setCursorCol(isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE) ? mLeftMargin : 0);
                doLinefeed();
                break;
            case 'F': // Cursor to lower-left corner of screen
                setCursorRowCol(0, mBottomMargin - 1);
                break;
            case 'H': // Tab set
                mTabStop[mCursorCol] = true;
                break;
            case 'M': // "${ESC}M" - reverse index (RI).
                // http://www.vt100.net/docs/vt100-ug/chapter3.html: "Move the active position to the same horizontal
                // position on the preceding line. If the active position is at the top margin, a scroll down is performed".
                if (mCursorRow <= mTopMargin) {
                    mScreen.blockCopy(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin, mBottomMargin - (mTopMargin + 1), mLeftMargin, mTopMargin + 1);
                    blockClear(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin);
                } else {
                    mCursorRow--;
                }
                break;
            case 'N': // SS2, ignore.
            case '0': // SS3, ignore.
                break;
            case 'P': // Device control string
                mOSCOrDeviceControlArgs.setLength(0);
                continueSequence(ESC_P);
                break;
            case '[':
                continueSequence(ESC_CSI);
                break;
            case '=': // DECKPAM
                setDecsetinternalBit(DECSET_BIT_APPLICATION_KEYPAD, true);
                break;
            case ']': // OSC
                mOSCOrDeviceControlArgs.setLength(0);
                continueSequence(ESC_OSC);
                break;
            case '>': // DECKPNM
                setDecsetinternalBit(DECSET_BIT_APPLICATION_KEYPAD, false);
                break;
            case '_': // APC - Application Program Command.
                continueSequence(ESC_APC);
                break;
            default:
                unknownSequence(b);
                break;
        }
    }

    /** DECSC save cursor - http://www.vt100.net/docs/vt510-rm/DECSC . See {@link #restoreCursor()}. */
    private void saveCursor() {
        SavedScreenState state = (mScreen == mMainBuffer) ? mSavedStateMain : mSavedStateAlt;
        state.mSavedCursorRow = mCursorRow;
        state.mSavedCursorCol = mCursorCol;
        state.mSavedEffect = mEffect;
        state.mSavedForeColor = mForeColor;
        state.mSavedBackColor = mBackColor;
        state.mSavedDecFlags = mCurrentDecSetFlags;
        state.mUseLineDrawingG0 = mUseLineDrawingG0;
        state.mUseLineDrawingG1 = mUseLineDrawingG1;
        state.mUseLineDrawingUsesG0 = mUseLineDrawingUsesG0;
    }

    /** DECRS restore cursor - http://www.vt100.net/docs/vt510-rm/DECRC. See {@link #saveCursor()}. */
    private void restoreCursor() {
        SavedScreenState state = (mScreen == mMainBuffer) ? mSavedStateMain : mSavedStateAlt;
        setCursorRowCol(state.mSavedCursorRow, state.mSavedCursorCol);
        mEffect = state.mSavedEffect;
        mForeColor = state.mSavedForeColor;
        mBackColor = state.mSavedBackColor;
        int mask = (DECSET_BIT_AUTOWRAP | DECSET_BIT_ORIGIN_MODE);
        mCurrentDecSetFlags = (mCurrentDecSetFlags & ~mask) | (state.mSavedDecFlags & mask);
        mUseLineDrawingG0 = state.mUseLineDrawingG0;
        mUseLineDrawingG1 = state.mUseLineDrawingG1;
        mUseLineDrawingUsesG0 = state.mUseLineDrawingUsesG0;
    }

    /** Following a CSI - Control Sequence Introducer, "\033[". {@link #ESC_CSI}. */
    private void doCsi(int b) {
        switch (b) {
            case '!':
                continueSequence(ESC_CSI_EXCLAMATION);
                break;
            case '"':
                continueSequence(ESC_CSI_DOUBLE_QUOTE);
                break;
            case '\'':
                continueSequence(ESC_CSI_SINGLE_QUOTE);
                break;
            case '$':
                continueSequence(ESC_CSI_DOLLAR);
                break;
            case '*':
                continueSequence(ESC_CSI_ARGS_ASTERIX);
                break;
            case '@': {
                // "CSI{n}@" - Insert ${n} space characters (ICH) - http://www.vt100.net/docs/vt510-rm/ICH.
                mAboutToAutoWrap = false;
                int columnsAfterCursor = mColumns - mCursorCol;
                int spacesToInsert = Math.min(getArg0(1), columnsAfterCursor);
                int charsToMove = columnsAfterCursor - spacesToInsert;
                mScreen.blockCopy(mCursorCol, mCursorRow, charsToMove, 1, mCursorCol + spacesToInsert, mCursorRow);
                blockClear(mCursorCol, mCursorRow, spacesToInsert);
            }
            break;
            case 'A': // "CSI${n}A" - Cursor up (CUU) ${n} rows.
                setCursorRow(Math.max(0, mCursorRow - getArg0(1)));
                break;
            case 'B': // "CSI${n}B" - Cursor down (CUD) ${n} rows.
                setCursorRow(Math.min(mRows - 1, mCursorRow + getArg0(1)));
                break;
            case 'C': // "CSI${n}C" - Cursor forward (CUF).
            case 'a': // "CSI${n}a" - Horizontal position relative (HPR). From ISO-6428/ECMA-48.
                setCursorCol(Math.min(mRightMargin - 1, mCursorCol + getArg0(1)));
                break;
            case 'D': // "CSI${n}D" - Cursor backward (CUB) ${n} columns.
                setCursorCol(Math.max(mLeftMargin, mCursorCol - getArg0(1)));
                break;
            case 'E': // "CSI{n}E - Cursor Next Line (CNL). From ISO-6428/ECMA-48.
                setCursorPosition(0, mCursorRow + getArg0(1));
                break;
            case 'F': // "CSI{n}F - Cursor Previous Line (CPL). From ISO-6428/ECMA-48.
                setCursorPosition(0, mCursorRow - getArg0(1));
                break;
            case 'G': // "CSI${n}G" - Cursor horizontal absolute (CHA) to column ${n}.
                setCursorCol(Math.min(Math.max(1, getArg0(1)), mColumns) - 1);
                break;
            case 'H': // "${CSI}${ROW};${COLUMN}H" - Cursor position (CUP).
            case 'f': // "${CSI}${ROW};${COLUMN}f" - Horizontal and Vertical Position (HVP).
                setCursorPosition(getArg1(1) - 1, getArg0(1) - 1);
                break;
            case 'I': // Cursor Horizontal Forward Tabulation (CHT). Move the active position n tabs forward.
                setCursorCol(nextTabStop(getArg0(1)));
                break;
            case 'J': // "${CSI}${0,1,2,3}J" - Erase in Display (ED)
                // ED ignores the scrolling margins.
                switch (getArg0(0)) {
                    case 0: // Erase from the active position to the end of the screen, inclusive (default).
                        blockClear(mCursorCol, mCursorRow, mColumns - mCursorCol);
                        blockClear(0, mCursorRow + 1, mColumns, mRows - (mCursorRow + 1));
                        break;
                    case 1: // Erase from start of the screen to the active position, inclusive.
                        blockClear(0, 0, mColumns, mCursorRow);
                        blockClear(0, mCursorRow, mCursorCol + 1);
                        break;
                    case 2: // Erase all of the display - all lines are erased, changed to single-width, and the cursor does not
                        // move..
                        blockClear(0, 0, mColumns, mRows);
                        break;
                    case 3: // Delete all lines saved in the scrollback buffer (xterm etc)
                        mMainBuffer.clearTranscript();
                        break;
                    default:
                        unknownSequence(b);
                        return;
                }
                mAboutToAutoWrap = false;
                break;
            case 'K': // "CSI{n}K" - Erase in line (EL).
                switch (getArg0(0)) {
                    case 0: // Erase from the cursor to the end of the line, inclusive (default)
                        blockClear(mCursorCol, mCursorRow, mColumns - mCursorCol);
                        break;
                    case 1: // Erase from the start of the screen to the cursor, inclusive.
                        blockClear(0, mCursorRow, mCursorCol + 1);
                        break;
                    case 2: // Erase all of the line.
                        blockClear(0, mCursorRow, mColumns);
                        break;
                    default:
                        unknownSequence(b);
                        return;
                }
                mAboutToAutoWrap = false;
                break;
            case 'L': // "${CSI}{N}L" - insert ${N} lines (IL).
            {
                int linesAfterCursor = mBottomMargin - mCursorRow;
                int linesToInsert = Math.min(getArg0(1), linesAfterCursor);
                int linesToMove = linesAfterCursor - linesToInsert;
                if (linesToMove > 0 && linesToInsert > 0) {
                    // Fast path: rotate full rows (no per-cell copy). Vacated lines are cleared below.
                    mScreen.rotateScreenRows(mCursorRow, linesAfterCursor, linesToInsert);
                } else if (linesToMove > 0) {
                    mScreen.blockCopy(0, mCursorRow, mColumns, linesToMove, 0, mCursorRow + linesToInsert);
                }
                blockClear(0, mCursorRow, mColumns, linesToInsert);
            }
            break;
            case 'M': // "${CSI}${N}M" - delete N lines (DL).
            {
                mAboutToAutoWrap = false;
                int linesAfterCursor = mBottomMargin - mCursorRow;
                int linesToDelete = Math.min(getArg0(1), linesAfterCursor);
                int linesToMove = linesAfterCursor - linesToDelete;
                if (linesToMove > 0 && linesToDelete > 0) {
                    // Fast path: rotate full rows (no per-cell copy). Vacated lines are cleared below.
                    mScreen.rotateScreenRows(mCursorRow, linesAfterCursor, -linesToDelete);
                } else if (linesToMove > 0) {
                    mScreen.blockCopy(0, mCursorRow + linesToDelete, mColumns, linesToMove, 0, mCursorRow);
                }
                blockClear(0, mCursorRow + linesToMove, mColumns, linesToDelete);
            }
            break;
            case 'P': // "${CSI}{N}P" - delete ${N} characters (DCH).
            {
                // http://www.vt100.net/docs/vt510-rm/DCH: "If ${N} is greater than the number of characters between the
                // cursor and the right margin, then DCH only deletes the remaining characters.
                // As characters are deleted, the remaining characters between the cursor and right margin move to the left.
                // Character attributes move with the characters. The terminal adds blank spaces with no visual character
                // attributes at the right margin. DCH has no effect outside the scrolling margins."
                mAboutToAutoWrap = false;
                int cellsAfterCursor = mColumns - mCursorCol;
                int cellsToDelete = Math.min(getArg0(1), cellsAfterCursor);
                int cellsToMove = cellsAfterCursor - cellsToDelete;
                mScreen.blockCopy(mCursorCol + cellsToDelete, mCursorRow, cellsToMove, 1, mCursorCol, mCursorRow);
                blockClear(mCursorCol + cellsToMove, mCursorRow, cellsToDelete);
            }
            break;
            case 'S': { // "${CSI}${N}S" - scroll up ${N} lines (default = 1) (SU).
                final int linesToScroll = getArg0(1);
                for (int i = 0; i < linesToScroll; i++)
                    scrollDownOneLine();
                break;
            }
            case 'T':
                if (mArgIndex == 0) {
                    // "${CSI}${N}T" - Scroll down N lines (default = 1) (SD).
                    // http://vt100.net/docs/vt510-rm/SD: "N is the number of lines to move the user window up in page
                    // memory. N new lines appear at the top of the display. N old lines disappear at the bottom of the
                    // display. You cannot pan past the top margin of the current page".
                    final int linesToScrollArg = getArg0(1);
                    final int linesBetweenTopAndBottomMargins = mBottomMargin - mTopMargin;
                    final int linesToScroll = Math.min(linesBetweenTopAndBottomMargins, linesToScrollArg);
                    mScreen.blockCopy(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin, linesBetweenTopAndBottomMargins - linesToScroll, mLeftMargin, mTopMargin + linesToScroll);
                    blockClear(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin, linesToScroll);
                } else {
                    // "${CSI}${func};${startx};${starty};${firstrow};${lastrow}T" - initiate highlight mouse tracking.
                    unimplementedSequence(b);
                }
                break;
            case 'X': // "${CSI}${N}X" - Erase ${N:=1} character(s) (ECH). FIXME: Clears character attributes?
                mAboutToAutoWrap = false;
                mScreen.blockSet(mCursorCol, mCursorRow, Math.min(getArg0(1), mColumns - mCursorCol), 1, ' ', getStyle());
                break;
            case 'Z': // Cursor Backward Tabulation (CBT). Move the active position n tabs backward.
                int numberOfTabs = getArg0(1);
                int newCol = mLeftMargin;
                for (int i = mCursorCol - 1; i >= 0; i--)
                    if (mTabStop[i]) {
                        if (--numberOfTabs == 0) {
                            newCol = Math.max(i, mLeftMargin);
                            break;
                        }
                    }
                mCursorCol = newCol;
                break;
            case '?': // Esc [ ? -- start of a private parameter byte
                continueSequence(ESC_CSI_QUESTIONMARK);
                break;
            case '>': // "Esc [ >" -- start of a private parameter byte
                continueSequence(ESC_CSI_BIGGERTHAN);
                break;
            case '<': // "Esc [ <" -- start of a private parameter byte
            case '=': // "Esc [ =" -- start of a private parameter byte
                continueSequence(ESC_CSI_UNSUPPORTED_PARAMETER_BYTE);
                break;
            case '`': // Horizontal position absolute (HPA - http://www.vt100.net/docs/vt510-rm/HPA).
                setCursorColRespectingOriginMode(getArg0(1) - 1);
                break;
            case 'b': // Repeat the preceding graphic character Ps times (REP).
                if (mLastEmittedCodePoint == -1) break;
                final int numRepeat = getArg0(1);
                for (int i = 0; i < numRepeat; i++) emitCodePoint(mLastEmittedCodePoint);
                break;
            case 'c': // Primary Device Attributes (http://www.vt100.net/docs/vt510-rm/DA1) if argument is missing or zero.
                // The important part that may still be used by some (tmux stores this value but does not currently use it)
                // is the first response parameter identifying the terminal service class, where we send 64 for "vt420".
                // This is followed by a list of attributes which is probably unused by applications. Send like xterm.
                if (getArg0(0) == 0) mSession.write("\033[?64;1;2;6;9;15;18;21;22c");
                break;
            case 'd': // ESC [ Pn d - Vert Position Absolute
                setCursorRow(Math.min(Math.max(1, getArg0(1)), mRows) - 1);
                break;
            case 'e': // Vertical Position Relative (VPR). From ISO-6429 (ECMA-48).
                setCursorPosition(mCursorCol, mCursorRow + getArg0(1));
                break;
            // case 'f': "${CSI}${ROW};${COLUMN}f" - Horizontal and Vertical Position (HVP). Grouped with case 'H'.
            case 'g': // Clear tab stop
                switch (getArg0(0)) {
                    case 0:
                        mTabStop[mCursorCol] = false;
                        break;
                    case 3:
                        for (int i = 0; i < mColumns; i++) {
                            mTabStop[i] = false;
                        }
                        break;
                    default:
                        // Specified to have no effect.
                        break;
                }
                break;
            case 'h': // Set Mode
                doSetMode(true);
                break;
            case 'l': // Reset Mode
                doSetMode(false);
                break;
            case 'm': // Esc [ Pn m - character attributes. (can have up to 16 numerical arguments)
                selectGraphicRendition();
                break;
            case 'n': // Esc [ Pn n - ECMA-48 Status Report Commands
                // sendDeviceAttributes()
                switch (getArg0(0)) {
                    case 5: // Device status report (DSR):
                        // Answer is ESC [ 0 n (Terminal OK).
                        byte[] dsr = {(byte) 27, (byte) '[', (byte) '0', (byte) 'n'};
                        mSession.write(dsr, 0, dsr.length);
                        break;
                    case 6: // Cursor position report (CPR):
                        // Answer is ESC [ y ; x R, where x,y is
                        // the cursor location.
                        mSession.write(String.format(Locale.US, "\033[%d;%dR", mCursorRow + 1, mCursorCol + 1));
                        break;
                    default:
                        break;
                }
                break;
            case 'r': // "CSI${top};${bottom}r" - set top and bottom Margins (DECSTBM).
            {
                // https://vt100.net/docs/vt510-rm/DECSTBM.html
                // The top margin defaults to 1, the bottom margin defaults to mRows.
                // The escape sequence numbers top 1..23, but we number top 0..22.
                // The escape sequence numbers bottom 2..24, and so do we (because we use a zero based numbering
                // scheme, but we store the first line below the bottom-most scrolling line.
                // As a result, we adjust the top line by -1, but we leave the bottom line alone.
                // Also require that top + 2 <= bottom.
                mTopMargin = Math.max(0, Math.min(getArg0(1) - 1, mRows - 2));
                mBottomMargin = Math.max(mTopMargin + 2, Math.min(getArg1(mRows), mRows));

                // DECSTBM moves the cursor to column 1, line 1 of the page respecting origin mode.
                setCursorPosition(0, 0);
            }
            break;
            case 's':
                if (isDecsetInternalBitSet(DECSET_BIT_LEFTRIGHT_MARGIN_MODE)) {
                    // Set left and right margins (DECSLRM - http://www.vt100.net/docs/vt510-rm/DECSLRM).
                    mLeftMargin = Math.min(getArg0(1) - 1, mColumns - 2);
                    mRightMargin = Math.max(mLeftMargin + 1, Math.min(getArg1(mColumns), mColumns));
                    // DECSLRM moves the cursor to column 1, line 1 of the page.
                    setCursorPosition(0, 0);
                } else {
                    // Save cursor (ANSI.SYS), available only when DECLRMM is disabled.
                    saveCursor();
                }
                break;
            case 't': // Window manipulation (from dtterm, as well as extensions)
                switch (getArg0(0)) {
                    case 11: // Report xterm window state. If the xterm window is open (non-iconified), it returns CSI 1 t .
                        mSession.write("\033[1t");
                        break;
                    case 13: // Report xterm window position. Result is CSI 3 ; x ; y t
                        mSession.write("\033[3;0;0t");
                        break;
                    case 14: // Report xterm window in pixels. Result is CSI 4 ; height ; width t
                        mSession.write(String.format(Locale.US, "\033[4;%d;%dt", mRows * mCellHeightPixels, mColumns * mCellWidthPixels));
                        break;
                    case 16: // Report xterm character cell size in pixels. Result is CSI 6 ; height ; width t
                        mSession.write(String.format(Locale.US, "\033[6;%d;%dt", mCellHeightPixels, mCellWidthPixels));
                        break;
                    case 18: // Report the size of the text area in characters. Result is CSI 8 ; height ; width t
                        mSession.write(String.format(Locale.US, "\033[8;%d;%dt", mRows, mColumns));
                        break;
                    case 19: // Report the size of the screen in characters. Result is CSI 9 ; height ; width t
                        // We report the same size as the view, since it's the view really isn't resizable from the shell.
                        mSession.write(String.format(Locale.US, "\033[9;%d;%dt", mRows, mColumns));
                        break;
                    case 20: // Report xterm windows icon label. Result is OSC L label ST. Disabled due to security concerns:
                        mSession.write("\033]LIconLabel\033\\");
                        break;
                    case 21: // Report xterm windows title. Result is OSC l label ST. Disabled due to security concerns:
                        mSession.write("\033]l\033\\");
                        break;
                    case 22:
                        // 22;0 -> Save xterm icon and window title on stack.
                        // 22;1 -> Save xterm icon title on stack.
                        // 22;2 -> Save xterm window title on stack.
                        mTitleStack.push(mTitle);
                        if (mTitleStack.size() > 20) {
                            // Limit size
                            mTitleStack.remove(0);
                        }
                        break;
                    case 23: // Like 22 above but restore from stack.
                        if (!mTitleStack.isEmpty()) setTitle(mTitleStack.pop());
                        break;
                    default:
                        // Ignore window manipulation.
                        break;
                }
                break;
            case 'u': // Restore cursor (ANSI.SYS).
                restoreCursor();
                break;
            case ' ':
                continueSequence(ESC_CSI_ARGS_SPACE);
                break;
            default:
                parseArg(b);
                break;
        }
    }

    /** Select Graphic Rendition (SGR) - see http://en.wikipedia.org/wiki/ANSI_escape_code#graphics. */
    private void selectGraphicRendition() {
        if (mArgIndex >= mArgs.length) mArgIndex = mArgs.length - 1;
        for (int i = 0; i <= mArgIndex; i++) {
            // Skip leading sub parameters:
            if ((mArgsSubParamsBitSet & (1 << i)) != 0) {
                continue;
            }

            int code = getArg(i, 0, false);
            if (code < 0) {
                if (mArgIndex > 0) {
                    continue;
                } else {
                    code = 0;
                }
            }
            if (code == 0) { // reset
                mForeColor = TextStyle.COLOR_INDEX_FOREGROUND;
                mBackColor = TextStyle.COLOR_INDEX_BACKGROUND;
                mEffect = 0;
            } else if (code == 1) {
                mEffect |= TextStyle.CHARACTER_ATTRIBUTE_BOLD;
            } else if (code == 2) {
                mEffect |= TextStyle.CHARACTER_ATTRIBUTE_DIM;
            } else if (code == 3) {
                mEffect |= TextStyle.CHARACTER_ATTRIBUTE_ITALIC;
            } else if (code == 4) {
                if (i + 1 <= mArgIndex && ((mArgsSubParamsBitSet & (1 << (i + 1))) != 0)) {
                    // Sub parameter, see https://sw.kovidgoyal.net/kitty/underlines/
                    i++;
                    if (mArgs[i] == 0) {
                        // No underline.
                        mEffect &= ~TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE;
                    } else {
                        // Different variations of underlines: https://sw.kovidgoyal.net/kitty/underlines/
                        mEffect |= TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE;
                    }
                } else {
                    mEffect |= TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE;
                }
            } else if (code == 5) {
                mEffect |= TextStyle.CHARACTER_ATTRIBUTE_BLINK;
            } else if (code == 7) {
                mEffect |= TextStyle.CHARACTER_ATTRIBUTE_INVERSE;
            } else if (code == 8) {
                mEffect |= TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE;
            } else if (code == 9) {
                mEffect |= TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH;
            } else if (code == 10) {
                // Exit alt charset (TERM=linux) - ignore.
            } else if (code == 11) {
                // Enter alt charset (TERM=linux) - ignore.
            } else if (code == 22) { // Normal color or intensity, neither bright, bold nor faint.
                mEffect &= ~(TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_DIM);
            } else if (code == 23) { // not italic, but rarely used as such; clears standout with TERM=screen
                mEffect &= ~TextStyle.CHARACTER_ATTRIBUTE_ITALIC;
            } else if (code == 24) { // underline: none
                mEffect &= ~TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE;
            } else if (code == 25) { // blink: none
                mEffect &= ~TextStyle.CHARACTER_ATTRIBUTE_BLINK;
            } else if (code == 27) { // image: positive
                mEffect &= ~TextStyle.CHARACTER_ATTRIBUTE_INVERSE;
            } else if (code == 28) {
                mEffect &= ~TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE;
            } else if (code == 29) {
                mEffect &= ~TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH;
            } else if (code >= 30 && code <= 37) {
                mForeColor = code - 30;
            } else if (code == 38 || code == 48 || code == 58) {
                // Extended set foreground(38)/background(48)/underline(58) color.
                // This is followed by either "2;$R;$G;$B" to set a 24-bit color or
                // "5;$INDEX" to set an indexed color.
                if (i + 2 > mArgIndex) continue;
                int firstArg = mArgs[i + 1];
                if (firstArg == 2) {
                    if (i + 4 > mArgIndex) {
                        Logger.logWarn(mClient, LOG_TAG, "Too few CSI" + code + ";2 RGB arguments");
                    } else {
                        int red = getArg(i + 2, 0, false);
                        int green = getArg(i + 3, 0, false);
                        int blue = getArg(i + 4, 0, false);

                        if (red < 0 || green < 0 || blue < 0 || red > 255 || green > 255 || blue > 255) {
                            finishSequenceAndLogError("Invalid RGB: " + red + "," + green + "," + blue);
                        } else {
                            int argbColor = 0xff_00_00_00 | (red << 16) | (green << 8) | blue;
                            switch (code) {
                                case 38: mForeColor = argbColor; break;
                                case 48: mBackColor = argbColor; break;
                                case 58: mUnderlineColor = argbColor; break;
                            }
                        }
                        i += 4; // "2;P_r;P_g;P_r"
                    }
                } else if (firstArg == 5) {
                    int color = getArg(i + 2, 0, false);
                    i += 2; // "5;P_s"
                    if (color >= 0 && color < TextStyle.NUM_INDEXED_COLORS) {
                        switch (code) {
                            case 38: mForeColor = color; break;
                            case 48: mBackColor = color; break;
                            case 58: mUnderlineColor = color; break;
                        }
                    } else {
                        if (LOG_ESCAPE_SEQUENCES) Logger.logWarn(mClient, LOG_TAG, "Invalid color index: " + color);
                    }
                } else {
                    finishSequenceAndLogError("Invalid ISO-8613-3 SGR first argument: " + firstArg);
                }
            } else if (code == 39) { // Set default foreground color.
                mForeColor = TextStyle.COLOR_INDEX_FOREGROUND;
            } else if (code >= 40 && code <= 47) { // Set background color.
                mBackColor = code - 40;
            } else if (code == 49) { // Set default background color.
                mBackColor = TextStyle.COLOR_INDEX_BACKGROUND;
            } else if (code == 59) { // Set default underline color.
                mUnderlineColor = TextStyle.COLOR_INDEX_FOREGROUND;
            } else if (code >= 90 && code <= 97) { // Bright foreground colors (aixterm codes).
                mForeColor = code - 90 + 8;
            } else if (code >= 100 && code <= 107) { // Bright background color (aixterm codes).
                mBackColor = code - 100 + 8;
            } else {
                if (LOG_ESCAPE_SEQUENCES)
                    Logger.logWarn(mClient, LOG_TAG, String.format("SGR unknown code %d", code));
            }
        }
    }

    private void doOsc(int b) {
        switch (b) {
            case 7: // Bell.
                doOscSetTextParameters("\007");
                break;
            case 27: // Escape.
                continueSequence(ESC_OSC_ESC);
                break;
            default:
                collectOSCArgs(b);
                break;
        }
    }

    private void doOscDiscard(int b) {
        if (b == 7 || (mEscapeState == ESC_OSC_DISCARD_ESC && b == '\\')) {
            finishSequence();
        } else if (b == 27) {
            continueSequence(ESC_OSC_DISCARD_ESC);
        } else {
            continueSequence(ESC_OSC_DISCARD);
        }
    }

    private void doOscEsc(int b) {
        switch (b) {
            case '\\':
                doOscSetTextParameters("\033\\");
                break;
            default:
                // The ESC character was not followed by a \, so insert the ESC and
                // the current character in arg buffer.
                collectOSCArgs(27);
                collectOSCArgs(b);
                continueSequence(ESC_OSC);
                break;
        }
    }

    /** An Operating System Controls (OSC) Set Text Parameters. May come here from BEL or ST. */
    private void doOscSetTextParameters(String bellOrStringTerminator) {
        int value = -1;
        String textParameter = "";
        // Extract initial $value from initial "$value;..." string.
        for (int mOSCArgTokenizerIndex = 0; mOSCArgTokenizerIndex < mOSCOrDeviceControlArgs.length(); mOSCArgTokenizerIndex++) {
            char b = mOSCOrDeviceControlArgs.charAt(mOSCArgTokenizerIndex);
            if (b == ';') {
                textParameter = mOSCOrDeviceControlArgs.substring(mOSCArgTokenizerIndex + 1);
                break;
            } else if (b >= '0' && b <= '9') {
                value = ((value < 0) ? 0 : value * 10) + (b - '0');
            } else {
                unknownSequence(b);
                return;
            }
        }

        switch (value) {
            case 0: // Change icon name and window title to T.
            case 1: // Change icon name to T.
            case 2: // Change window title to T.
                setTitle(textParameter);
                break;
            case 4:
                // P s = 4 ; c ; spec → Change Color Number c to the color specified by spec. This can be a name or RGB
                // specification as per XParseColor. Any number of c name pairs may be given. The color numbers correspond
                // to the ANSI colors 0-7, their bright versions 8-15, and if supported, the remainder of the 88-color or
                // 256-color table.
                // If a "?" is given rather than a name or RGB specification, xterm replies with a control sequence of the
                // same form which can be used to set the corresponding color. Because more than one pair of color number
                // and specification can be given in one control sequence, xterm can make more than one reply.
                int colorIndex = -1;
                int parsingPairStart = -1;
                for (int i = 0; ; i++) {
                    boolean endOfInput = i == textParameter.length();
                    char b = endOfInput ? ';' : textParameter.charAt(i);
                    if (b == ';') {
                        if (parsingPairStart < 0) {
                            parsingPairStart = i + 1;
                        } else {
                            if (colorIndex < 0 || colorIndex > 255) {
                                unknownSequence(b);
                                return;
                            } else {
                                mColors.tryParseColor(colorIndex, textParameter.substring(parsingPairStart, i));
                                mSession.onColorsChanged();
                                colorIndex = -1;
                                parsingPairStart = -1;
                            }
                        }
                    } else if (parsingPairStart >= 0) {
                        // We have passed a color index and are now going through color spec.
                    } else if (parsingPairStart < 0 && (b >= '0' && b <= '9')) {
                        colorIndex = ((colorIndex < 0) ? 0 : colorIndex * 10) + (b - '0');
                    } else {
                        unknownSequence(b);
                        return;
                    }
                    if (endOfInput) break;
                }
                break;
            case 8: // OSC 8 ; params ; URI ST - semantic terminal hyperlink.
                setOsc8Hyperlink(textParameter);
                break;
            case 10: // Set foreground color.
            case 11: // Set background color.
            case 12: // Set cursor color.
                int specialIndex = TextStyle.COLOR_INDEX_FOREGROUND + (value - 10);
                int lastSemiIndex = 0;
                for (int charIndex = 0; ; charIndex++) {
                    boolean endOfInput = charIndex == textParameter.length();
                    if (endOfInput || textParameter.charAt(charIndex) == ';') {
                        try {
                            String colorSpec = textParameter.substring(lastSemiIndex, charIndex);
                            if ("?".equals(colorSpec)) {
                                // Report current color in the same format xterm and gnome-terminal does.
                                int rgb = mColors.mCurrentColors[specialIndex];
                                int r = (65535 * ((rgb & 0x00FF0000) >> 16)) / 255;
                                int g = (65535 * ((rgb & 0x0000FF00) >> 8)) / 255;
                                int b = (65535 * ((rgb & 0x000000FF))) / 255;
                                mSession.write("\033]" + value + ";rgb:" + String.format(Locale.US, "%04x", r) + "/" + String.format(Locale.US, "%04x", g) + "/"
                                    + String.format(Locale.US, "%04x", b) + bellOrStringTerminator);
                            } else {
                                mColors.tryParseColor(specialIndex, colorSpec);
                                mSession.onColorsChanged();
                            }
                            specialIndex++;
                            if (endOfInput || (specialIndex > TextStyle.COLOR_INDEX_CURSOR) || ++charIndex >= textParameter.length())
                                break;
                            lastSemiIndex = charIndex;
                        } catch (NumberFormatException e) {
                            // Ignore.
                        }
                    }
                }
                break;
            case 52: // Manipulate Selection Data. Skip the optional first selection parameter(s).
                int startIndex = textParameter.indexOf(";") + 1;
                try {
                    String clipboardText = new String(Base64.decode(textParameter.substring(startIndex), 0), StandardCharsets.UTF_8);
                    mSession.onCopyTextToClipboard(clipboardText);
                } catch (Exception e) {
                    Logger.logError(mClient, LOG_TAG, "OSC Manipulate selection, invalid string '" + textParameter + "");
                }
                break;
            case OSC_TERMUX_HOST_CONTROL:
                handleTermuxHostControl(textParameter);
                break;
            case 104:
                // "104;$c" → Reset Color Number $c. It is reset to the color specified by the corresponding X
                // resource. Any number of c parameters may be given. These parameters correspond to the ANSI colors 0-7,
                // their bright versions 8-15, and if supported, the remainder of the 88-color or 256-color table. If no
                // parameters are given, the entire table will be reset.
                if (textParameter.isEmpty()) {
                    mColors.reset();
                    mSession.onColorsChanged();
                } else {
                    int lastIndex = 0;
                    for (int charIndex = 0; ; charIndex++) {
                        boolean endOfInput = charIndex == textParameter.length();
                        if (endOfInput || textParameter.charAt(charIndex) == ';') {
                            try {
                                int colorToReset = Integer.parseInt(textParameter.substring(lastIndex, charIndex));
                                mColors.reset(colorToReset);
                                mSession.onColorsChanged();
                                if (endOfInput) break;
                                charIndex++;
                                lastIndex = charIndex;
                            } catch (NumberFormatException e) {
                                // Ignore.
                            }
                        }
                    }
                }
                break;
            case 110: // Reset foreground color.
            case 111: // Reset background color.
            case 112: // Reset cursor color.
                mColors.reset(TextStyle.COLOR_INDEX_FOREGROUND + (value - 110));
                mSession.onColorsChanged();
                break;
            case 119: // Reset highlight color.
                break;
            default:
                unknownParameter(value);
                break;
        }
        finishSequence();
    }

    private void setOsc8Hyperlink(String textParameter) {
        // Every OSC 8 command replaces the previous state. Malformed or unsafe targets close it,
        // preventing a stale destination from leaking onto later terminal output.
        mCurrentHyperlink = null;
        int separator = textParameter.indexOf(';');
        if (separator < 0 || separator + 1 >= textParameter.length()) return;

        String normalized = TerminalLinkResolver.normalizeSemanticUrl(
            textParameter.substring(separator + 1));
        if (normalized == null) return;

        String cached = mHyperlinkCache.get(normalized);
        if (cached == null) {
            mHyperlinkCache.put(normalized, normalized);
            cached = normalized;
        }
        mCurrentHyperlink = cached;
    }

    private void blockClear(int sx, int sy, int w) {
        blockClear(sx, sy, w, 1);
    }

    private void blockClear(int sx, int sy, int w, int h) {
        mScreen.blockSet(sx, sy, w, h, ' ', getStyle());
    }

    private long getStyle() {
        return TextStyle.encode(mForeColor, mBackColor, mEffect);
    }

    /** "CSI P_m h" for set or "CSI P_m l" for reset ANSI mode. */
    private void doSetMode(boolean newValue) {
        int modeBit = getArg0(0);
        switch (modeBit) {
            case 4: // Set="Insert Mode". Reset="Replace Mode". (IRM).
                mInsertMode = newValue;
                break;
            case 20: // Normal Linefeed (LNM).
                unknownParameter(modeBit);
                // http://www.vt100.net/docs/vt510-rm/LNM
                break;
            case 34:
                // Normal cursor visibility - when using TERM=screen, see
                // http://www.gnu.org/software/screen/manual/html_node/Control-Sequences.html
                break;
            default:
                unknownParameter(modeBit);
                break;
        }
    }

    private void handleTermuxHostControl(String textParameter) {
        if (textParameter.isEmpty()) return;

        int separatorIndex = textParameter.indexOf(';');
        String command;
        String argument;
        if (separatorIndex < 0) {
            command = textParameter;
            argument = "";
        } else {
            command = textParameter.substring(0, separatorIndex);
            argument = textParameter.substring(separatorIndex + 1);
        }

        if (command.isEmpty()) return;
        if (HOST_CONTROL_TERMINAL_BACKEND.equals(command) &&
            "java".equalsIgnoreCase(argument)) {
            // The callback can run while libghostty-vt owns its mutex. Flip only the process-wide
            // gate here; the next backend access performs the journal replay and closes native
            // resources after the native write has returned.
            GhosttyTerminalBackend.setProductionEnabled(false);
            Logger.logWarn(mClient, LOG_TAG,
                "One-click terminal rollback requested through OSC 8900; switching to Java");
            return;
        }
        mSession.onTerminalHostControlCommand(command, argument.isEmpty() ? null : argument);
    }

    /**
     * NOTE: The parameters of this function respect the {@link #DECSET_BIT_ORIGIN_MODE}. Use
     * {@link #setCursorRowCol(int, int)} for absolute pos.
     */
    private void setCursorPosition(int x, int y) {
        boolean originMode = isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE);
        int effectiveTopMargin = originMode ? mTopMargin : 0;
        int effectiveBottomMargin = originMode ? mBottomMargin : mRows;
        int effectiveLeftMargin = originMode ? mLeftMargin : 0;
        int effectiveRightMargin = originMode ? mRightMargin : mColumns;
        int newRow = Math.max(effectiveTopMargin, Math.min(effectiveTopMargin + y, effectiveBottomMargin - 1));
        int newCol = Math.max(effectiveLeftMargin, Math.min(effectiveLeftMargin + x, effectiveRightMargin - 1));
        setCursorRowCol(newRow, newCol);
    }

    private void scrollDownOneLine() {
        boolean fullScreenScroll = mTopMargin == 0 && mBottomMargin == mRows && mLeftMargin == 0 && mRightMargin == mColumns;
        recordScroll(1, fullScreenScroll);
        long currentStyle = getStyle();
        if (mLeftMargin != 0 || mRightMargin != mColumns) {
            // Horizontal margin: Do not put anything into scroll history, just non-margin part of screen up.
            mScreen.blockCopy(mLeftMargin, mTopMargin + 1, mRightMargin - mLeftMargin, mBottomMargin - mTopMargin - 1, mLeftMargin, mTopMargin);
            // .. and blank bottom row between margins:
            mScreen.blockSet(mLeftMargin, mBottomMargin - 1, mRightMargin - mLeftMargin, 1, ' ', currentStyle);
        } else {
            mScreen.scrollDownOneLine(mTopMargin, mBottomMargin, currentStyle);
        }
    }

    /**
     * Process the next ASCII character of a parameter.
     *
     * <p>You must use the ; character to separate parameters and : to separate sub-parameters.
     *
     * <p>Parameter characters modify the action or interpretation of the sequence. Originally
     * you can use up to 16 parameters per sequence, but following at least xterm and alacritty
     * we use a common space for parameters and sub-parameters, allowing 32 in total.
     *
     * <p>All parameters are unsigned, positive decimal integers, with the most significant
     * digit sent first. Any parameter greater than 9999 (decimal) is set to 9999
     * (decimal). If you do not specify a value, a 0 value is assumed. A 0 value
     * or omitted parameter indicates a default value for the sequence. For most
     * sequences, the default value is 1.
     *
     * <p>References:
     * <a href="https://vt100.net/docs/vt510-rm/chapter4.html#S4.3.3">VT510 Video Terminal Programmer Information: Control Sequences</a>
     * <a href="https://github.com/alacritty/vte/issues/22">alacritty/vte: Implement colon separated CSI parameters</a>
     * */
    private void parseArg(int b) {
        if (b >= '0' && b <= '9') {
            if (mArgIndex < mArgs.length) {
                int oldValue = mArgs[mArgIndex];
                int thisDigit = b - '0';
                int value;
                if (oldValue >= 0) {
                    value = oldValue * 10 + thisDigit;
                } else {
                    value = thisDigit;
                }
                if (value > 9999)
                    value = 9999;
                mArgs[mArgIndex] = value;
            }
            continueSequence(mEscapeState);
        } else if (b == ';' || b == ':') {
            if (mArgIndex + 1 < mArgs.length) {
                mArgIndex++;
                if (b == ':') {
                    mArgsSubParamsBitSet |= 1 << mArgIndex;
                }
            } else {
                logError("Too many parameters when in state: " + mEscapeState);
            }
            continueSequence(mEscapeState);
        } else {
            unknownSequence(b);
        }
    }

    private int getArg0(int defaultValue) {
        return getArg(0, defaultValue, true);
    }

    private int getArg1(int defaultValue) {
        return getArg(1, defaultValue, true);
    }

    private int getArg(int index, int defaultValue, boolean treatZeroAsDefault) {
        int result = mArgs[index];
        if (result < 0 || (result == 0 && treatZeroAsDefault)) {
            result = defaultValue;
        }
        return result;
    }

    private void collectOSCArgs(int b) {
        if (mOSCOrDeviceControlArgs.length() + Character.charCount(b) <= MAX_OSC_STRING_LENGTH) {
            mOSCOrDeviceControlArgs.appendCodePoint(b);
            continueSequence(mEscapeState);
        } else {
            if (mOSCOrDeviceControlArgs.indexOf("8;") == 0) mCurrentHyperlink = null;
            mOSCOrDeviceControlArgs.setLength(0);
            continueSequence(ESC_OSC_DISCARD);
        }
    }

    private void unimplementedSequence(int b) {
        logError("Unimplemented sequence char '" + (char) b + "' (U+" + String.format("%04x", b) + ")");
        finishSequence();
    }

    private void unknownSequence(int b) {
        logError("Unknown sequence char '" + (char) b + "' (numeric value=" + b + ")");
        finishSequence();
    }

    private void unknownParameter(int parameter) {
        logError("Unknown parameter: " + parameter);
        finishSequence();
    }

    private void logError(String errorType) {
        if (LOG_ESCAPE_SEQUENCES) {
            StringBuilder buf = new StringBuilder();
            buf.append(errorType);
            buf.append(", escapeState=");
            buf.append(mEscapeState);
            boolean firstArg = true;
            if (mArgIndex >= mArgs.length) mArgIndex = mArgs.length - 1;
            for (int i = 0; i <= mArgIndex; i++) {
                int value = mArgs[i];
                if (value >= 0) {
                    if (firstArg) {
                        firstArg = false;
                        buf.append(", args={");
                    } else {
                        buf.append(',');
                    }
                    buf.append(value);
                }
            }
            if (!firstArg) buf.append('}');
            finishSequenceAndLogError(buf.toString());
        }
    }

    private void finishSequenceAndLogError(String error) {
        if (LOG_ESCAPE_SEQUENCES) Logger.logWarn(mClient, LOG_TAG, error);
        finishSequence();
    }

    private void finishSequence() {
        mEscapeState = ESC_NONE;
    }

    /**
     * Send a Unicode code point to the screen.
     *
     * @param codePoint The code point of the character to display
     */
    private void emitCodePoint(int codePoint) {
        mLastEmittedCodePoint = codePoint;
        if (mUseLineDrawingUsesG0 ? mUseLineDrawingG0 : mUseLineDrawingG1) {
            // http://www.vt100.net/docs/vt102-ug/table5-15.html.
            switch (codePoint) {
                case '_':
                    codePoint = ' '; // Blank.
                    break;
                case '`':
                    codePoint = '◆'; // Diamond.
                    break;
                case '0':
                    codePoint = '█'; // Solid block;
                    break;
                case 'a':
                    codePoint = '▒'; // Checker board.
                    break;
                case 'b':
                    codePoint = '␉'; // Horizontal tab.
                    break;
                case 'c':
                    codePoint = '␌'; // Form feed.
                    break;
                case 'd':
                    codePoint = '\r'; // Carriage return.
                    break;
                case 'e':
                    codePoint = '␊'; // Linefeed.
                    break;
                case 'f':
                    codePoint = '°'; // Degree.
                    break;
                case 'g':
                    codePoint = '±'; // Plus-minus.
                    break;
                case 'h':
                    codePoint = '\n'; // Newline.
                    break;
                case 'i':
                    codePoint = '␋'; // Vertical tab.
                    break;
                case 'j':
                    codePoint = '┘'; // Lower right corner.
                    break;
                case 'k':
                    codePoint = '┐'; // Upper right corner.
                    break;
                case 'l':
                    codePoint = '┌'; // Upper left corner.
                    break;
                case 'm':
                    codePoint = '└'; // Left left corner.
                    break;
                case 'n':
                    codePoint = '┼'; // Crossing lines.
                    break;
                case 'o':
                    codePoint = '⎺'; // Horizontal line - scan 1.
                    break;
                case 'p':
                    codePoint = '⎻'; // Horizontal line - scan 3.
                    break;
                case 'q':
                    codePoint = '─'; // Horizontal line - scan 5.
                    break;
                case 'r':
                    codePoint = '⎼'; // Horizontal line - scan 7.
                    break;
                case 's':
                    codePoint = '⎽'; // Horizontal line - scan 9.
                    break;
                case 't':
                    codePoint = '├'; // T facing rightwards.
                    break;
                case 'u':
                    codePoint = '┤'; // T facing leftwards.
                    break;
                case 'v':
                    codePoint = '┴'; // T facing upwards.
                    break;
                case 'w':
                    codePoint = '┬'; // T facing downwards.
                    break;
                case 'x':
                    codePoint = '│'; // Vertical line.
                    break;
                case 'y':
                    codePoint = '≤'; // Less than or equal to.
                    break;
                case 'z':
                    codePoint = '≥'; // Greater than or equal to.
                    break;
                case '{':
                    codePoint = 'π'; // Pi.
                    break;
                case '|':
                    codePoint = '≠'; // Not equal to.
                    break;
                case '}':
                    codePoint = '£'; // UK pound.
                    break;
                case '~':
                    codePoint = '·'; // Centered dot.
                    break;
            }
        }

        final boolean autoWrap = isDecsetInternalBitSet(DECSET_BIT_AUTOWRAP);
        final int displayWidth = WcWidth.width(codePoint);
        final boolean cursorInLastColumn = mCursorCol == mRightMargin - 1;

        if (autoWrap) {
            if (cursorInLastColumn && ((mAboutToAutoWrap && displayWidth == 1) || displayWidth == 2)) {
                mScreen.setLineWrap(mCursorRow);
                mCursorCol = mLeftMargin;
                if (mCursorRow + 1 < mBottomMargin) {
                    mCursorRow++;
                } else {
                    scrollDownOneLine();
                }
            }
        } else if (cursorInLastColumn && displayWidth == 2) {
            // The behaviour when a wide character is output with cursor in the last column when
            // autowrap is disabled is not obvious - it's ignored here.
            return;
        }

        if (mInsertMode && displayWidth > 0) {
            // Move character to right one space.
            int destCol = mCursorCol + displayWidth;
            if (destCol < mRightMargin)
                mScreen.blockCopy(mCursorCol, mCursorRow, mRightMargin - destCol, 1, destCol, mCursorRow);
        }

        int offsetDueToCombiningChar = ((displayWidth <= 0 && mCursorCol > 0 && !mAboutToAutoWrap) ? 1 : 0);
        int column = mCursorCol - offsetDueToCombiningChar;

        // Fix TerminalRow.setChar() ArrayIndexOutOfBoundsException index=-1 exception reported
        // The offsetDueToCombiningChar would never be 1 if mCursorCol was 0 to get column/index=-1,
        // so was mCursorCol changed after the offsetDueToCombiningChar conditional by another thread?
        // TODO: Check if there are thread synchronization issues with mCursorCol and mCursorRow, possibly causing others bugs too.
        if (column < 0) column = 0;
        mScreen.setChar(column, mCursorRow, codePoint, getStyle(), mCurrentHyperlink);

        if (autoWrap && displayWidth > 0)
            mAboutToAutoWrap = (mCursorCol == mRightMargin - displayWidth);

        mCursorCol = Math.min(mCursorCol + displayWidth, mRightMargin - 1);
    }

    private void setCursorRow(int row) {
        mCursorRow = row;
        mAboutToAutoWrap = false;
    }

    private void setCursorCol(int col) {
        mCursorCol = Math.max(0, Math.min(col, mColumns - 1));
        mAboutToAutoWrap = false;
    }

    /** Set the cursor mode, but limit it to margins if {@link #DECSET_BIT_ORIGIN_MODE} is enabled. */
    private void setCursorColRespectingOriginMode(int col) {
        setCursorPosition(col, mCursorRow);
    }

    /** TODO: Better name, distinguished from {@link #setCursorPosition(int, int)} by not regarding origin mode. */
    private void setCursorRowCol(int row, int col) {
        mCursorRow = Math.max(0, Math.min(row, mRows - 1));
        mCursorCol = Math.max(0, Math.min(col, mColumns - 1));
        mAboutToAutoWrap = false;
    }

    private void recordScroll(int count, boolean fullScreen) {
        if (count <= 0) return;
        while (true) {
            long current = mScrollSignal.get();
            int currentCount = (int) Math.min(Integer.MAX_VALUE, current >>> 1);
            int nextCount = (int) Math.min(Integer.MAX_VALUE, (long) currentCount + count);
            boolean currentFullScreen = (current & 1L) != 0L;
            boolean nextFullScreen = currentCount == 0
                ? fullScreen : currentFullScreen && fullScreen;
            long next = ((long) nextCount << 1) | (nextFullScreen ? 1L : 0L);
            if (mScrollSignal.compareAndSet(current, next)) return;
        }
    }

    public int getScrollCounter() {
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.getScrollCounter();
        }
        return (int) Math.min(Integer.MAX_VALUE, mScrollSignal.get() >>> 1);
    }

    public boolean isScrollCounterFullScreen() {
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.isScrollCounterFullScreen();
        }
        return (mScrollSignal.get() & 1L) != 0L;
    }

    /** Consume every scroll accepted before this atomic exchange without losing a concurrent one. */
    public int consumeScrollCounter() {
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.consumeScrollCounter();
        }
        return (int) Math.min(Integer.MAX_VALUE, mScrollSignal.getAndSet(1L) >>> 1);
    }

    public void clearScrollCounter() {
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            mCompatibilityEmulator.clearScrollCounter();
        }
        mScrollSignal.set(1L);
    }

    public boolean isFullRedrawRequired() {
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mFullRedrawRequired.get() ||
                mCompatibilityEmulator.isFullRedrawRequired();
        }
        return mFullRedrawRequired.get();
    }

    /** Consume the redraw edge atomically so a simultaneous PTY publication survives. */
    public boolean consumeFullRedrawRequired() {
        boolean required = mFullRedrawRequired.getAndSet(false);
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            required |= mCompatibilityEmulator.consumeFullRedrawRequired();
        }
        return required;
    }

    public void clearFullRedrawRequired() {
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            mCompatibilityEmulator.clearFullRedrawRequired();
        }
        mFullRedrawRequired.set(false);
    }

    public boolean isAutoScrollDisabled() {
        return mAutoScrollDisabled;
    }

    public void toggleAutoScrollDisabled() {
        mAutoScrollDisabled = !mAutoScrollDisabled;
    }


    /** Reset terminal state so user can interact with it regardless of present state. */
    public synchronized void reset() {
        if (mGhosttyBackend != null && !GhosttyTerminalBackend.isProductionEnabled() &&
            !mReplayOnly && !mCompatibilityFallback) {
            activateCompatibilityFallback("process kill switch");
        }
        if (hasGhosttyAuthority()) {
            recordResetForCompatibility();
            if (!mGhosttyBackend.softReset()) {
                activateCompatibilityFallback("native soft reset failure");
                return;
            }
            mGhosttyState = mGhosttyBackend.state();
            setCursorStyle();
            mFullRedrawRequired.set(true);
            return;
        }
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            mCompatibilityEmulator.resetJavaState();
            return;
        }
        if (mGhosttyBackend != null) mGhosttyBackend.softReset();
        resetJavaState();
    }

    private void resetJavaState() {
        if (isSynchronizedOutputActive()) forceFinishSynchronizedOutput();
        setCursorStyle();
        mArgIndex = 0;
        mContinueSequence = false;
        mEscapeState = ESC_NONE;
        mCurrentHyperlink = null;
        mHyperlinkCache.clear();
        mInsertMode = false;
        mTopMargin = mLeftMargin = 0;
        mBottomMargin = mRows;
        mRightMargin = mColumns;
        mAboutToAutoWrap = false;
        mForeColor = mSavedStateMain.mSavedForeColor = mSavedStateAlt.mSavedForeColor = TextStyle.COLOR_INDEX_FOREGROUND;
        mBackColor = mSavedStateMain.mSavedBackColor = mSavedStateAlt.mSavedBackColor = TextStyle.COLOR_INDEX_BACKGROUND;
        setDefaultTabStops();

        mUseLineDrawingG0 = mUseLineDrawingG1 = false;
        mUseLineDrawingUsesG0 = true;

        mSavedStateMain.mSavedCursorRow = mSavedStateMain.mSavedCursorCol = mSavedStateMain.mSavedEffect = mSavedStateMain.mSavedDecFlags = 0;
        mSavedStateAlt.mSavedCursorRow = mSavedStateAlt.mSavedCursorCol = mSavedStateAlt.mSavedEffect = mSavedStateAlt.mSavedDecFlags = 0;
        mCurrentDecSetFlags = 0;
        // Initial wrap-around is not accurate but makes terminal more useful, especially on a small screen:
        setDecsetinternalBit(DECSET_BIT_AUTOWRAP, true);
        setDecsetinternalBit(DECSET_BIT_CURSOR_ENABLED, true);
        mSavedDecSetFlags = mSavedStateMain.mSavedDecFlags = mSavedStateAlt.mSavedDecFlags = mCurrentDecSetFlags;

        // XXX: Should we set terminal driver back to IUTF8 with termios?
        mUtf8Index = mUtf8ToFollow = 0;

        mColors.reset();
        mSession.onColorsChanged();
    }

    GhosttyTerminalBackend.Snapshot getGhosttySnapshotForDiagnostics() {
        return mGhosttyBackend == null ? null : mGhosttyBackend.snapshot();
    }

    /** Return an owned Ghostty viewport packet for diagnostics or external synchronous consumers. */
    public GhosttyRenderSnapshot getGhosttyRenderSnapshot(int topRow) {
        if (!hasGhosttyAuthority()) return null;
        GhosttyTerminalBackend backend = mGhosttyBackend;
        return backend == null ? null : backend.renderSnapshotCopy(topRow);
    }

    /** Decode the reusable native render packet while excluding only competing render requests. */
    public <T> T decodeGhosttyRenderSnapshot(int topRow, GhosttyRenderDecoder<T> decoder) {
        if (!hasGhosttyAuthority() || decoder == null) return null;
        GhosttyTerminalBackend backend = mGhosttyBackend;
        if (backend == null) return null;
        return backend.decodeRenderSnapshot(topRow, decoder::decode);
    }

    /** Decode only rows changed since the prior successful native render packet. */
    public <T> T decodeGhosttyRenderDelta(int topRow, boolean forceFull,
                                          GhosttyRenderDeltaDecoder<T> decoder) {
        if (!hasGhosttyAuthority() || decoder == null) return null;
        GhosttyTerminalBackend backend = mGhosttyBackend;
        if (backend == null) return null;
        return backend.decodeRenderDelta(topRow, forceFull, decoder::decode);
    }

    public interface GhosttyRenderDecoder<T> {
        T decode(GhosttyRenderSnapshot snapshot);
    }

    public interface GhosttyRenderDeltaDecoder<T> {
        T decode(GhosttyRenderDelta delta);
    }

    public boolean isGhosttyRenderAuthorityActive() {
        return hasGhosttyAuthority();
    }

    /**
     * Return whether the native authority can still produce bulk render packets.
     *
     * <p>Parsing and rendering deliberately share one authority. A terminal whose parser keeps
     * advancing while rendering is permanently unavailable cannot safely display the dormant Java
     * screen, so callers must transition through {@link #activateGhosttyRenderFallback(String)}
     * instead of silently drawing stale compatibility state.</p>
     */
    public boolean isGhosttyRenderBackendHealthy() {
        if (!hasGhosttyAuthority()) return false;
        GhosttyTerminalBackend backend = mGhosttyBackend;
        return backend != null && backend.isRenderHealthy();
    }

    /** Recreate only Ghostty's derived render state; parser, grids, and scrollback remain live. */
    public boolean recoverGhosttyRenderBackend(String reason) {
        if (!hasGhosttyAuthority()) return false;
        GhosttyTerminalBackend backend = mGhosttyBackend;
        if (backend == null || !backend.recoverRender()) return false;
        mFullRedrawRequired.set(true);
        Logger.logWarn(mClient, LOG_TAG, "Ghostty render state recovered in place: " +
            (reason == null ? "unknown" : reason));
        return true;
    }

    /**
     * Atomically restore the journaled Java terminal after a production render failure.
     *
     * @return {@code true} only when this call performed the authority transition.
     */
    public synchronized boolean activateGhosttyRenderFallback(String reason) {
        if (!hasGhosttyAuthority()) return false;
        activateCompatibilityFallback("render pipeline failure: " +
            (reason == null || reason.isEmpty() ? "unknown" : reason));
        return true;
    }

    public boolean isGhosttyParserAuthorityActive() {
        return hasGhosttyAuthority();
    }

    public String getGhosttyRenderStatusForDiagnostics() {
        return mGhosttyBackend == null ? "backend-unavailable" : mGhosttyBackend.renderStatus();
    }

    /** Process-wide kill switch. Existing views fall back immediately; new sessions skip Ghostty. */
    public static void setGhosttyProductionEnabled(boolean enabled) {
        GhosttyTerminalBackend.setProductionEnabled(enabled);
    }

    synchronized void releaseNativeResources() {
        stopCompatibilityReplayWorker(true);
        if (mGhosttyBackend != null) {
            mGhosttyBackend.close();
            mGhosttyBackend = null;
            mGhosttyState = null;
        }
        if (mCompatibilityEmulator != null) {
            mCompatibilityEmulator.releaseNativeResources();
        }
    }

    public String getSelectedText(int x1, int y1, int x2, int y2) {
        if (hasGhosttyAuthority()) {
            String value = mGhosttyBackend.formatRange(x1, y1, x2, y2, true);
            if (value != null) return value;
        }
        return getScreen().getSelectedText(x1, y1, x2, y2);
    }

    /** Derive the initial long-press word range without materializing the Java replay buffer. */
    public int[] getWordBounds(int column, int row) {
        int safeColumn = clampSelection(column, 0, Math.max(0, mColumns - 1));
        int safeRow = clampSelection(row, -getActiveTranscriptRows(), Math.max(0, mRows - 1));
        if (hasGhosttyAuthority()) {
            int[] bounds = mGhosttyBackend.selectWord(safeColumn, safeRow);
            if (bounds != null && bounds.length == 4) {
                bounds[0] = clampSelection(bounds[0], 0, Math.max(0, mColumns - 1));
                bounds[2] = clampSelection(bounds[2], 0, Math.max(0, mColumns - 1));
                bounds[1] = clampSelection(bounds[1], -getActiveTranscriptRows(), mRows - 1);
                bounds[3] = clampSelection(bounds[3], -getActiveTranscriptRows(), mRows - 1);
                return bounds;
            }
            return new int[] {safeColumn, safeRow, safeColumn, safeRow};
        }

        TerminalBuffer screen = getScreen();
        int start = safeColumn;
        int end = safeColumn;
        if (!" ".equals(screen.getSelectedText(start, safeRow, end, safeRow))) {
            while (start > 0 &&
                !"".equals(screen.getSelectedText(start - 1, safeRow, start - 1, safeRow))) {
                start--;
            }
            while (end < mColumns - 1 &&
                !"".equals(screen.getSelectedText(end + 1, safeRow, end + 1, safeRow))) {
                end++;
            }
        }
        return new int[] {start, safeRow, end, safeRow};
    }

    /** Keep selection endpoints aligned to a complete wide grapheme. */
    public int snapSelectionColumn(int column, int row, boolean startEndpoint) {
        int safeColumn = clampSelection(column, 0, Math.max(0, mColumns - 1));
        int safeRow = clampSelection(row, -getActiveTranscriptRows(), Math.max(0, mRows - 1));
        if (hasGhosttyAuthority()) {
            int wide = mGhosttyBackend.cellWide(safeColumn, safeRow);
            if (startEndpoint && wide == 2) return Math.max(0, safeColumn - 1);
            if (!startEndpoint && wide == 1) return Math.min(mColumns - 1, safeColumn + 1);
            return safeColumn;
        }

        TerminalBuffer screen = getScreen();
        TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(safeRow));
        if (startEndpoint && safeColumn > 0 &&
            line.findStartOfColumn(safeColumn) == line.findStartOfColumn(safeColumn - 1)) {
            return safeColumn - 1;
        }
        if (!startEndpoint && safeColumn + 1 < mColumns &&
            line.findStartOfColumn(safeColumn + 1) == line.findStartOfColumn(safeColumn)) {
            return safeColumn + 1;
        }
        return safeColumn;
    }

    /** Resolve OSC 8 and textual URLs against the authoritative backend selection snapshot. */
    public synchronized TerminalLinkResolver.SelectionResult resolveSelectionLinks(
        int x1, int y1, int x2, int y2, boolean allowWithoutScheme) {
        if (hasGhosttyAuthority()) {
            List<String> semantic = mGhosttyBackend.selectionHyperlinks(x1, y1, x2, y2);
            if (semantic != null) {
                TerminalLinkResolver.SelectionResult semanticResult =
                    TerminalLinkResolver.resolveSemanticTargets(semantic);
                if (!semanticResult.getUrls().isEmpty()) return semanticResult;
                TerminalLinkResolver.SelectionResult textResult =
                    resolveGhosttyTextSelectionLinks(x1, y1, x2, y2, allowWithoutScheme);
                if (textResult != null) return textResult;
            }
        }
        return TerminalLinkResolver.resolveTerminalSelection(
            getScreen(), x1, y1, x2, y2, allowWithoutScheme);
    }

    private TerminalLinkResolver.SelectionResult resolveGhosttyTextSelectionLinks(
        int x1, int y1, int x2, int y2, boolean allowWithoutScheme) {
        int startX = x1;
        int startY = y1;
        int endX = x2;
        int endY = y2;
        if (startY > endY || (startY == endY && startX > endX)) {
            int swapX = startX;
            int swapY = startY;
            startX = endX;
            startY = endY;
            endX = swapX;
            endY = swapY;
        }
        int minRow = -getActiveTranscriptRows();
        int maxRow = Math.max(0, mRows - 1);
        startX = clampSelection(startX, 0, Math.max(0, mColumns - 1));
        endX = clampSelection(endX, 0, Math.max(0, mColumns - 1));
        startY = clampSelection(startY, minRow, maxRow);
        endY = clampSelection(endY, minRow, maxRow);

        int paddingRows = 2;
        while (true) {
            int contextStartRow = Math.max(minRow, startY - paddingRows);
            int contextEndRow = Math.min(maxRow, endY + paddingRows);
            String context = mGhosttyBackend.formatRangeRaw(
                0, contextStartRow, mColumns - 1, contextEndRow, true);
            if (context == null) return null;

            String prefix;
            if (startX == 0 && startY == contextStartRow) {
                prefix = "";
            } else if (startX > 0) {
                prefix = mGhosttyBackend.formatRangeRaw(
                    0, contextStartRow, startX - 1, startY, true);
            } else {
                prefix = mGhosttyBackend.formatRangeRaw(
                    0, contextStartRow, mColumns - 1, startY - 1, true);
            }
            String throughSelection = mGhosttyBackend.formatRangeRaw(
                0, contextStartRow, endX, endY, true);
            if (prefix == null || throughSelection == null ||
                !context.startsWith(prefix) || !context.startsWith(throughSelection) ||
                throughSelection.length() < prefix.length()) return null;

            TerminalSelectionContext selectionContext;
            try {
                selectionContext = new TerminalSelectionContext(
                    context, prefix.length(), throughSelection.length());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
            TerminalLinkResolver.SelectionResult result =
                TerminalLinkResolver.resolveSelectionResult(
                    selectionContext, allowWithoutScheme);
            boolean atStart = contextStartRow == minRow;
            boolean atEnd = contextEndRow == maxRow;
            if ((!result.touchesContextStart() || atStart) &&
                (!result.touchesContextEnd() || atEnd)) return result;
            if (paddingRows >= 256) return result;
            paddingRows = Math.min(256, paddingRows * 2);
        }
    }

    private static int clampSelection(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    public String getTranscriptText() {
        return getTranscriptTextInternal(true);
    }

    public String getTranscriptTextWithoutJoinedLines() {
        return getTranscriptTextInternal(false);
    }

    public String getTranscriptTextWithFullLinesJoined() {
        return getTranscriptTextInternal(true);
    }

    private String getTranscriptTextInternal(boolean unwrap) {
        if (hasGhosttyAuthority()) {
            String value = mGhosttyBackend.formatAll(unwrap);
            if (value != null) return value.trim();
        }
        TerminalBuffer screen = getScreen();
        return unwrap ? screen.getTranscriptTextWithFullLinesJoined()
                      : screen.getTranscriptTextWithoutJoinedLines();
    }

    /** Get the terminal session's title (null if not set). */
    public String getTitle() {
        if (mCompatibilityFallback && mCompatibilityEmulator != null) {
            return mCompatibilityEmulator.getTitle();
        }
        return mTitle;
    }

    /** Change the terminal session's title. */
    private void setTitle(String newTitle) {
        String oldTitle = mTitle;
        mTitle = newTitle;
        if (!Objects.equals(oldTitle, newTitle)) {
            mSession.titleChanged(oldTitle, newTitle);
        }
    }

    /** If DECSET 2004 is set, prefix paste with "\033[200~" and suffix with "\033[201~". */
    public void paste(String text) {
        if (text == null) return;
        if (hasGhosttyAuthority()) {
            byte[] encoded = mGhosttyBackend.encodePaste(text);
            if (encoded != null) {
                if (encoded.length > 0) mSession.write(encoded, 0, encoded.length);
                return;
            }
        }
        // First: Always remove escape key and C1 control characters [0x80,0x9F]:
        text = text.replaceAll("(\u001B|[\u0080-\u009F])", "");
        // Second: Replace all newlines (\n) or CRLF (\r\n) with carriage returns (\r).
        text = text.replaceAll("\r?\n", "\r");

        // Then: Implement bracketed paste mode if enabled:
        boolean bracketed = hasGhosttyAuthority() && mGhosttyState != null
            ? mGhosttyState.mode(GhosttyTerminalBackend.MODE_BRACKETED_PASTE)
            : mCompatibilityFallback && mCompatibilityEmulator != null
                ? mCompatibilityEmulator.isDecsetInternalBitSet(DECSET_BIT_BRACKETED_PASTE_MODE)
                : isDecsetInternalBitSet(DECSET_BIT_BRACKETED_PASTE_MODE);
        if (bracketed) mSession.write("\033[200~");
        mSession.write(text);
        if (bracketed) mSession.write("\033[201~");
    }

    /** http://www.vt100.net/docs/vt510-rm/DECSC */
    static final class SavedScreenState {
        /** Saved state of the cursor position, Used to implement the save/restore cursor position escape sequences. */
        int mSavedCursorRow, mSavedCursorCol;
        int mSavedEffect, mSavedForeColor, mSavedBackColor;
        int mSavedDecFlags;
        boolean mUseLineDrawingG0, mUseLineDrawingG1, mUseLineDrawingUsesG0 = true;
    }

    @Override
    public String toString() {
        return "TerminalEmulator[size=" + mScreen.mColumns + "x" + mScreen.mScreenRows + ", margins={" + mTopMargin + "," + mRightMargin + "," + mBottomMargin
            + "," + mLeftMargin + "}]";
    }

}
