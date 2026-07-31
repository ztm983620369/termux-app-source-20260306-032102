package com.termux.terminal;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Complete libghostty-vt terminal state and bulk production-render backend.
 *
 * <p>Ghostty is both the production parser and GPU screen authority when this backend is healthy.
 * Effects are delivered synchronously to the owning {@link TerminalEmulator}; terminal state is
 * refreshed in the same JNI call as each write so Java never polls individual cells or modes.</p>
 */
final class GhosttyTerminalBackend implements AutoCloseable {

    private static final String LOG_TAG = "TermuxGhosttyVT";
    private static final int SNAPSHOT_VALUES = 20;
    private static final int STATE_VALUES = 22;
    /**
     * One atomic resize-anchor transaction. Keep this in lockstep with
     * TERMUX_GHOSTTY_RESIZE_ANCHOR_COUNT in ghostty_terminal_backend.c.
     */
    private static final int RESIZE_ANCHOR_VALUES = 20;
    private static final int RENDER_METADATA_VALUES = 18;
    private static final int RENDER_DELTA_METADATA_VALUES = 26;
    private static final int MIN_RENDER_BUFFER_BYTES = 256 * 1024;
    private static final int MAX_RENDER_BUFFER_BYTES = 64 * 1024 * 1024;
    private static final long MAX_SCROLLBACK_BYTES = 512L * 1024L * 1024L;
    private static final byte[] SOFT_RESET = {0x1b, '[', '!', 'p'};

    static final long MODE_CURSOR_KEYS = 1L << 0;
    static final long MODE_REVERSE_VIDEO = 1L << 1;
    static final long MODE_KEYPAD = 1L << 2;
    static final long MODE_MOUSE = 1L << 3;
    static final long MODE_MOUSE_MOTION = 1L << 4;
    static final long MODE_MOUSE_SGR = 1L << 5;
    static final long MODE_FOCUS = 1L << 6;
    static final long MODE_BRACKETED_PASTE = 1L << 7;
    static final long MODE_SYNC_OUTPUT = 1L << 8;
    static final long MODE_WRAPAROUND = 1L << 9;

    private static volatile boolean sProductionEnabled = true;
    private static volatile boolean sValidationEnabled;
    private static volatile boolean sLoadAttempted;
    private static volatile boolean sNativeMethodsAvailable;
    private static volatile boolean sActivationLogged;

    private long mHandle;
    private boolean mFailed;
    private boolean mRenderFailed;
    private String mLastRenderStatus = "not-requested";
    private long mRenderRecoveryAttempts;
    private long mRenderRecoverySuccesses;
    private ByteBuffer mRenderBuffer;
    private final Object mRenderDecodeLock = new Object();
    private final long[] mRenderMetadata = new long[RENDER_METADATA_VALUES];
    private final long[] mRenderDeltaMetadata = new long[RENDER_DELTA_METADATA_VALUES];
    private final long[] mStateValues = new long[STATE_VALUES];
    private final long[] mResizeAnchorValues = new long[RESIZE_ANCHOR_VALUES];
    private final Effects mEffects;
    private final int mLogicalScrollbackRows;
    /** Logical history currently exposed by TerminalEmulator for the resized screen height. */
    private int mVisibleScrollbackRows;
    private State mState;
    private boolean mLastResizeAnchorRequested;
    private boolean mLastResizeAnchorTracked;
    private int mLastResizeAnchorOutcome;
    private int mLastResizeAnchorOldTopRow;
    private int mLastResizeAnchorOldRow = -1;
    private int mLastResizeAnchorTargetRow = -1;
    private int mLastResizeAnchorResolvedRow = -1;
    private long mLastResizeAnchorScrollbarOffset = -1L;
    private long mLastResizeAnchorOldScrollback = -1L;
    private long mLastResizeAnchorOldRequestedOffset = -1L;
    private long mLastResizeAnchorOldActualOffset = -1L;
    private long mLastResizeAnchorOldScreenRow = -1L;
    private boolean mLastResizeAnchorPreconditionExact;
    private long mLastResizeAnchorNewScrollback = -1L;
    private long mLastResizeAnchorNewScreenRow = -1L;
    private long mLastResizeAnchorRequestedOffset = -1L;
    private long mLastResizeAnchorMinimumOffset = -1L;
    private long mLastResizeAnchorMaximumOffset = -1L;
    private int mLastResizeAnchorFinalTopRow;
    private long mLastResizeAnchorReadbackOffset = -1L;

    private GhosttyTerminalBackend(int columns, int rows, int logicalScrollbackRows,
                                   Effects effects) {
        mEffects = effects == null ? Effects.NONE : effects;
        mLogicalScrollbackRows = Math.max(0, logicalScrollbackRows);
        mVisibleScrollbackRows = mLogicalScrollbackRows;
        long initial = Math.max(MIN_RENDER_BUFFER_BYTES,
            (long) columns * rows * (24L + 4L));
        mRenderBuffer = allocateRenderBuffer(nextPowerOfTwo(initial));
    }

    static GhosttyTerminalBackend createIfEnabled(int columns, int rows, int cellWidth, int cellHeight,
                                                  int maxScrollbackRows, int cursorStyle,
                                                  int[] colors, Effects effects) {
        if ((!sProductionEnabled && !sValidationEnabled) || !ensureNativeMethods()) return null;
        long handle = 0L;
        GhosttyTerminalBackend backend = null;
        try {
            backend = new GhosttyTerminalBackend(columns, rows, maxScrollbackRows, effects);
            handle = nativeOpen(columns, rows,
                scrollbackBytesForRows(columns, rows, maxScrollbackRows),
                Math.max(0, cellWidth), Math.max(0, cellHeight), backend, colors);
            if (handle == 0L) return null;
            backend.mHandle = handle;
            if (!backend.setDefaultCursorStyle(cursorStyle)) {
                backend.close();
                return null;
            }
            logActivationOnce();
            return backend;
        } catch (LinkageError | RuntimeException | OutOfMemoryError error) {
            Log.e(LOG_TAG, "Complete libghostty-vt terminal initialization failed", error);
            if (handle != 0L) {
                try {
                    nativeClose(handle);
                } catch (LinkageError | RuntimeException ignored) {
                }
            }
            return null;
        }
    }

    /** Diagnostic overload with the standard Termux palette and no host effects. */
    static GhosttyTerminalBackend createIfEnabled(int columns, int rows, int maxScrollback) {
        return createIfEnabled(columns, rows, 0, 0, maxScrollback, 0,
            new TerminalColors().mCurrentColors, Effects.NONE);
    }

    /**
     * The pinned Ghostty implementation accounts scrollback in page bytes even though the current
     * C header describes this field as lines. Reserve conservatively so Termux's row contract is
     * never silently reduced; memory is still allocated lazily by Ghostty's page list.
     */
    static long scrollbackBytesForRows(int columns, int screenRows, int scrollbackRows) {
        if (scrollbackRows <= 0) return 0L;
        long bytesPerRow = Math.max(4096L, Math.max(1, columns) * 16L + 1024L);
        long rowsWithPageSlack = (long) scrollbackRows + Math.max(1, screenRows) + 256L;
        if (rowsWithPageSlack > MAX_SCROLLBACK_BYTES / bytesPerRow) {
            return MAX_SCROLLBACK_BYTES;
        }
        return Math.min(MAX_SCROLLBACK_BYTES, rowsWithPageSlack * bytesPerRow);
    }

    synchronized boolean write(byte[] input, int length) {
        if (mHandle == 0L || mFailed) return false;
        try {
            long oldPaletteHash = mState == null ? 0L : mState.paletteHash;
            if (nativeWrite(mHandle, input, length, mStateValues)) {
                mState = new State(mStateValues);
                if (oldPaletteHash != 0L && oldPaletteHash != mState.paletteHash) {
                    mEffects.onColorsChanged();
                }
                return true;
            }
        } catch (LinkageError | RuntimeException error) {
            Log.e(LOG_TAG, "Complete libghostty-vt write failed", error);
        }
        mFailed = true;
        return false;
    }

    synchronized boolean resize(int columns, int rows, int cellWidth, int cellHeight) {
        return resize(columns, rows, cellWidth, cellHeight, 0, -1, -1, -1,
            mLogicalScrollbackRows);
    }

    synchronized boolean resize(int columns, int rows, int cellWidth, int cellHeight,
                                int viewportTopRow, int anchorColumn,
                                int anchorViewportRow, int targetViewportRow) {
        return resize(columns, rows, cellWidth, cellHeight, viewportTopRow, anchorColumn,
            anchorViewportRow, targetViewportRow, mLogicalScrollbackRows);
    }

    synchronized boolean resize(int columns, int rows, int cellWidth, int cellHeight,
                                int viewportTopRow, int anchorColumn,
                                int anchorViewportRow, int targetViewportRow,
                                int visibleScrollbackRows) {
        if (mHandle == 0L || mFailed) return false;
        try {
            if (nativeResize(mHandle, columns, rows, cellWidth, cellHeight,
                viewportTopRow, anchorColumn, anchorViewportRow, targetViewportRow,
                Math.max(0, visibleScrollbackRows), mResizeAnchorValues, mStateValues)) {
                mState = new State(mStateValues);
                mVisibleScrollbackRows = Math.max(0, visibleScrollbackRows);
                mLastResizeAnchorRequested = mResizeAnchorValues[0] != 0L;
                mLastResizeAnchorTracked = mResizeAnchorValues[1] != 0L;
                mLastResizeAnchorOutcome = (int) mResizeAnchorValues[2];
                mLastResizeAnchorOldTopRow = (int) mResizeAnchorValues[3];
                mLastResizeAnchorOldRow = (int) mResizeAnchorValues[4];
                mLastResizeAnchorTargetRow = (int) mResizeAnchorValues[5];
                mLastResizeAnchorResolvedRow = (int) mResizeAnchorValues[6];
                mLastResizeAnchorScrollbarOffset = mResizeAnchorValues[7];
                mLastResizeAnchorOldScrollback = mResizeAnchorValues[8];
                mLastResizeAnchorOldRequestedOffset = mResizeAnchorValues[9];
                mLastResizeAnchorOldActualOffset = mResizeAnchorValues[10];
                mLastResizeAnchorOldScreenRow = mResizeAnchorValues[11];
                mLastResizeAnchorPreconditionExact = mResizeAnchorValues[12] != 0L;
                mLastResizeAnchorNewScrollback = mResizeAnchorValues[13];
                mLastResizeAnchorNewScreenRow = mResizeAnchorValues[14];
                mLastResizeAnchorRequestedOffset = mResizeAnchorValues[15];
                mLastResizeAnchorMinimumOffset = mResizeAnchorValues[16];
                mLastResizeAnchorMaximumOffset = mResizeAnchorValues[17];
                mLastResizeAnchorFinalTopRow = (int) mResizeAnchorValues[18];
                mLastResizeAnchorReadbackOffset = mResizeAnchorValues[19];
                return true;
            }
        } catch (LinkageError | RuntimeException error) {
            Log.e(LOG_TAG, "Complete libghostty-vt resize failed", error);
        }
        mFailed = true;
        return false;
    }

    synchronized int viewportTopRow() {
        if (mState == null) return 0;
        long relative = mState.scrollbarOffset - mState.scrollbackRows;
        long oldest = -Math.min((long) mVisibleScrollbackRows, (long) mState.scrollbackRows);
        relative = Math.max(oldest, Math.min(0L, relative));
        return relative <= Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) relative;
    }

    synchronized int lastResizeAnchorOutcome() {
        return mLastResizeAnchorOutcome;
    }

    /** Recompute the native transaction invariant instead of trusting its outcome label alone. */
    synchronized boolean lastResizeAnchorCommitValid() {
        return resizeAnchorCommitValid(mResizeAnchorValues);
    }

    /** Pure verifier for the JNI resize-anchor evidence packet. */
    static boolean resizeAnchorCommitValid(long[] values) {
        if (values == null || values.length < RESIZE_ANCHOR_VALUES || values[0] == 0L ||
            values[1] == 0L || values[12] == 0L || values[2] <= 0L ||
            values[13] < 0L || values[14] < 0L || values[16] < 0L ||
            values[17] < values[16]) {
            return false;
        }
        long expectedOffset = Math.max(values[16], Math.min(values[17], values[15]));
        if (values[7] != expectedOffset || values[19] != expectedOffset ||
            values[18] != expectedOffset - values[13] ||
            values[6] != values[14] - expectedOffset) {
            return false;
        }
        boolean requestedInsideBounds = values[15] >= values[16] && values[15] <= values[17];
        return values[2] == 1L
            ? requestedInsideBounds && values[6] == values[5]
            : values[2] == 2L && !requestedInsideBounds;
    }

    synchronized String resizeAnchorStatus() {
        return "requested=" + mLastResizeAnchorRequested +
            " tracked=" + mLastResizeAnchorTracked +
            " outcome=" + mLastResizeAnchorOutcome +
            " oldTop=" + mLastResizeAnchorOldTopRow +
            " row=" + mLastResizeAnchorOldRow + "->" + mLastResizeAnchorTargetRow +
            " resolved=" + mLastResizeAnchorResolvedRow +
            " precondition=" + mLastResizeAnchorPreconditionExact +
            " old={scrollback=" + mLastResizeAnchorOldScrollback +
                " requested=" + mLastResizeAnchorOldRequestedOffset +
                " actual=" + mLastResizeAnchorOldActualOffset +
                " screen=" + mLastResizeAnchorOldScreenRow + "}" +
            " new={scrollback=" + mLastResizeAnchorNewScrollback +
                " screen=" + mLastResizeAnchorNewScreenRow +
                " requested=" + mLastResizeAnchorRequestedOffset +
                " bounds=" + mLastResizeAnchorMinimumOffset + ".." +
                    mLastResizeAnchorMaximumOffset +
                " committed=" + mLastResizeAnchorScrollbarOffset +
                " readback=" + mLastResizeAnchorReadbackOffset +
                " top=" + mLastResizeAnchorFinalTopRow + "}" +
            " valid=" + lastResizeAnchorCommitValid() +
            " viewportTop=" + viewportTopRow();
    }

    synchronized boolean softReset() {
        return write(SOFT_RESET, SOFT_RESET.length);
    }

    synchronized boolean setMode(int mode, boolean value) {
        if (mHandle == 0L || mFailed) return false;
        try {
            if (nativeSetMode(mHandle, mode, value, mStateValues)) {
                mState = new State(mStateValues);
                return true;
            }
        } catch (LinkageError | RuntimeException error) {
            Log.e(LOG_TAG, "Complete libghostty-vt mode update failed", error);
        }
        mFailed = true;
        return false;
    }

    synchronized boolean setColors(int[] colors) {
        if (mHandle == 0L || mFailed || colors == null || colors.length < 259) return false;
        try {
            if (nativeSetColors(mHandle, colors, mStateValues)) {
                mState = new State(mStateValues);
                return true;
            }
        } catch (LinkageError | RuntimeException error) {
            Log.e(LOG_TAG, "Complete libghostty-vt palette update failed", error);
        }
        mFailed = true;
        return false;
    }

    synchronized boolean setDefaultCursorStyle(int cursorStyle) {
        if (mHandle == 0L || mFailed || cursorStyle < 0 || cursorStyle > 2) return false;
        try {
            if (nativeSetDefaultCursorStyle(mHandle, cursorStyle, mStateValues)) {
                mState = new State(mStateValues);
                return true;
            }
        } catch (LinkageError | RuntimeException error) {
            Log.e(LOG_TAG, "Complete libghostty-vt cursor style update failed", error);
        }
        mFailed = true;
        return false;
    }

    synchronized State state() {
        return mState;
    }

    synchronized String formatRange(int x1, int y1, int x2, int y2, boolean unwrap) {
        if (mHandle == 0L || mFailed) return null;
        try {
            byte[] value = nativeFormatRange(mHandle, x1, y1, x2, y2, unwrap, true);
            return value == null ? null : new String(value, StandardCharsets.UTF_8);
        } catch (LinkageError | RuntimeException | OutOfMemoryError error) {
            Log.e(LOG_TAG, "Complete libghostty-vt selection format failed", error);
            return null;
        }
    }

    synchronized String formatRangeRaw(int x1, int y1, int x2, int y2, boolean unwrap) {
        if (mHandle == 0L || mFailed) return null;
        try {
            byte[] value = nativeFormatRange(mHandle, x1, y1, x2, y2, unwrap, false);
            return value == null ? null : new String(value, StandardCharsets.UTF_8);
        } catch (LinkageError | RuntimeException | OutOfMemoryError error) {
            Log.e(LOG_TAG, "Complete libghostty-vt raw selection format failed", error);
            return null;
        }
    }

    synchronized int[] selectWord(int column, int row) {
        if (mHandle == 0L || mFailed) return null;
        try {
            int[] bounds = nativeSelectWord(mHandle, column, row);
            return bounds != null && bounds.length == 4 ? bounds : null;
        } catch (LinkageError | RuntimeException error) {
            Log.e(LOG_TAG, "Complete libghostty-vt word selection failed", error);
            return null;
        }
    }

    synchronized int cellWide(int column, int row) {
        if (mHandle == 0L || mFailed) return -1;
        try {
            return nativeCellWide(mHandle, column, row);
        } catch (LinkageError | RuntimeException error) {
            Log.e(LOG_TAG, "Complete libghostty-vt cell width lookup failed", error);
            return -1;
        }
    }

    synchronized List<String> selectionHyperlinks(int x1, int y1, int x2, int y2) {
        if (mHandle == 0L || mFailed) return null;
        try {
            byte[] packed = nativeSelectionHyperlinks(mHandle, x1, y1, x2, y2);
            if (packed == null) return null;
            ArrayList<String> links = new ArrayList<>();
            int start = 0;
            for (int index = 0; index <= packed.length; index++) {
                if (index < packed.length && packed[index] != 0) continue;
                if (index > start) {
                    links.add(new String(packed, start, index - start, StandardCharsets.UTF_8));
                }
                start = index + 1;
            }
            return links;
        } catch (LinkageError | RuntimeException | OutOfMemoryError error) {
            Log.e(LOG_TAG, "Complete libghostty-vt semantic hyperlink lookup failed", error);
            return null;
        }
    }

    synchronized String formatAll(boolean unwrap) {
        if (mHandle == 0L || mFailed) return null;
        try {
            State state = mState;
            if (state == null || state.columns <= 0 || state.rows <= 0) return null;
            byte[] value = nativeFormatRange(mHandle, 0, -mLogicalScrollbackRows,
                state.columns - 1, state.rows - 1, unwrap, true);
            return value == null ? null : new String(value, StandardCharsets.UTF_8);
        } catch (LinkageError | RuntimeException | OutOfMemoryError error) {
            Log.e(LOG_TAG, "Complete libghostty-vt transcript format failed", error);
            return null;
        }
    }

    synchronized byte[] encodeMouse(int button, int column, int row, boolean pressed) {
        if (mHandle == 0L || mFailed) return null;
        try {
            return nativeEncodeMouse(mHandle, button, column, row, pressed);
        } catch (LinkageError | RuntimeException error) {
            Log.e(LOG_TAG, "Complete libghostty-vt mouse encode failed", error);
            return null;
        }
    }

    synchronized byte[] encodeKey(int androidKeyCode, int keyModifiers, int action) {
        if (mHandle == 0L || mFailed) return null;
        try {
            return nativeEncodeKey(mHandle, androidKeyCode, keyModifiers, action);
        } catch (LinkageError | RuntimeException error) {
            Log.e(LOG_TAG, "Complete libghostty-vt key encode failed", error);
            return null;
        }
    }

    synchronized byte[] encodeText(String text, int androidKeyCode, int action,
                                   int unshiftedCodePoint, int keyModifiers) {
        if (mHandle == 0L || mFailed || text == null) return null;
        try {
            return nativeEncodeText(mHandle, text.getBytes(StandardCharsets.UTF_8),
                androidKeyCode, action, unshiftedCodePoint, keyModifiers);
        } catch (LinkageError | RuntimeException | OutOfMemoryError error) {
            Log.e(LOG_TAG, "Complete libghostty-vt text key encode failed", error);
            return null;
        }
    }

    synchronized byte[] encodePaste(String text) {
        if (mHandle == 0L || mFailed || text == null) return null;
        try {
            return nativeEncodePaste(mHandle, text.getBytes(StandardCharsets.UTF_8));
        } catch (LinkageError | RuntimeException | OutOfMemoryError error) {
            Log.e(LOG_TAG, "Complete libghostty-vt paste encode failed", error);
            return null;
        }
    }

    synchronized byte[] encodeFocus(boolean focused) {
        if (mHandle == 0L || mFailed) return null;
        try {
            return nativeEncodeFocus(mHandle, focused);
        } catch (LinkageError | RuntimeException error) {
            Log.e(LOG_TAG, "Complete libghostty-vt focus encode failed", error);
            return null;
        }
    }

    Snapshot snapshot() {
        synchronized (mRenderDecodeLock) {
            synchronized (this) {
                if (mHandle == 0L || mFailed) return null;
                long[] values = new long[SNAPSHOT_VALUES];
                try {
                    return nativeSnapshot(mHandle, values) ? new Snapshot(values) : null;
                } catch (LinkageError | RuntimeException error) {
                    Log.e(LOG_TAG, "Complete libghostty-vt render-state snapshot failed", error);
                    mFailed = true;
                    return null;
                }
            }
        }
    }

    GhosttyRenderSnapshot renderSnapshot(int topRow) {
        synchronized (mRenderDecodeLock) {
            return renderSnapshotLocked(topRow);
        }
    }

    private GhosttyRenderSnapshot renderSnapshotLocked(int topRow) {
        if (mRenderFailed || mRenderBuffer == null) return null;
        final long handle;
        synchronized (this) {
            if (mHandle == 0L || mFailed) return null;
            handle = mHandle;
        }
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                int result = nativeRenderSnapshot(
                    handle, mRenderBuffer, mRenderMetadata, Math.min(0, topRow));
                if (result == 1) {
                    GhosttyRenderSnapshot snapshot =
                        new GhosttyRenderSnapshot(mRenderBuffer, mRenderMetadata);
                    mLastRenderStatus = "success grid=" + snapshot.columns + 'x' + snapshot.rows +
                        " bytes=" + snapshot.bytesUsed + " capacity=" + mRenderBuffer.capacity();
                    return snapshot;
                }
                if (result != 2) {
                    mLastRenderStatus = "native-failure result=" + result;
                    mRenderFailed = true;
                    return null;
                }

                long required = mRenderMetadata[1];
                mLastRenderStatus = "resize-required bytes=" + required +
                    " capacity=" + mRenderBuffer.capacity();
                if (required <= mRenderBuffer.capacity()) continue;
                int capacity = nextPowerOfTwo(required);
                if (capacity <= 0 || capacity > MAX_RENDER_BUFFER_BYTES) {
                    Log.e(LOG_TAG, "Complete libghostty-vt render packet exceeds safety limit: " +
                        required);
                    mLastRenderStatus = "safety-limit bytes=" + required;
                    mRenderFailed = true;
                    return null;
                }
                mRenderBuffer = allocateRenderBuffer(capacity);
            }
        } catch (LinkageError | RuntimeException | OutOfMemoryError error) {
            Log.e(LOG_TAG, "Complete libghostty-vt bulk render snapshot failed", error);
            mLastRenderStatus = "exception=" + error.getClass().getSimpleName() +
                " message=" + error.getMessage();
            mRenderFailed = true;
        }
        return null;
    }

    <T> T decodeRenderSnapshot(int topRow, RenderDecoder<T> decoder) {
        if (decoder == null) return null;
        // Writes have their own native-state mutex and never mutate mRenderBuffer. Serialize only
        // competing render requests through decode so a worker can consume the zero-copy packet
        // without blocking PTY parsing for the entire Java row-conversion phase.
        synchronized (mRenderDecodeLock) {
            GhosttyRenderSnapshot snapshot = renderSnapshotLocked(topRow);
            return snapshot == null ? null : decoder.decode(snapshot);
        }
    }

    GhosttyRenderSnapshot renderSnapshotCopy(int topRow) {
        synchronized (mRenderDecodeLock) {
            GhosttyRenderSnapshot snapshot = renderSnapshotLocked(topRow);
            return snapshot == null ? null : snapshot.immutableCopy();
        }
    }

    GhosttyRenderDelta renderDelta(int topRow, boolean forceFull) {
        synchronized (mRenderDecodeLock) {
            return renderDeltaLocked(topRow, forceFull);
        }
    }

    private GhosttyRenderDelta renderDeltaLocked(int topRow, boolean forceFull) {
        if (mRenderFailed || mRenderBuffer == null) return null;
        final long handle;
        synchronized (this) {
            if (mHandle == 0L || mFailed) return null;
            handle = mHandle;
        }
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                int result = nativeRenderDelta(
                    handle, mRenderBuffer, mRenderDeltaMetadata, Math.min(0, topRow), forceFull);
                if (result == 1) {
                    GhosttyRenderDelta delta =
                        new GhosttyRenderDelta(mRenderBuffer, mRenderDeltaMetadata);
                    mLastRenderStatus = "delta-success grid=" + delta.columns + 'x' + delta.rows +
                        " changed=" + delta.changedRowCount + " full=" + delta.fullFrame +
                        " semantic=" + delta.semanticCandidateRows + '/' +
                        delta.semanticSuppressedRows + " semanticTotal=" +
                        delta.semanticSuppressedRowsTotal + '/' + delta.semanticPacketsTotal +
                        " bytes=" + delta.bytesUsed + " capacity=" + mRenderBuffer.capacity();
                    return delta;
                }
                if (result != 2) {
                    mLastRenderStatus = "delta-native-failure result=" + result;
                    mRenderFailed = true;
                    return null;
                }

                long required = mRenderDeltaMetadata[1];
                mLastRenderStatus = "delta-resize-required bytes=" + required +
                    " capacity=" + mRenderBuffer.capacity();
                if (required <= mRenderBuffer.capacity()) continue;
                int capacity = nextPowerOfTwo(required);
                if (capacity <= 0 || capacity > MAX_RENDER_BUFFER_BYTES) {
                    Log.e(LOG_TAG, "Complete libghostty-vt render delta exceeds safety limit: " +
                        required);
                    mLastRenderStatus = "delta-safety-limit bytes=" + required;
                    mRenderFailed = true;
                    return null;
                }
                mRenderBuffer = allocateRenderBuffer(capacity);
            }
        } catch (LinkageError | RuntimeException | OutOfMemoryError error) {
            Log.e(LOG_TAG, "Complete libghostty-vt render delta failed", error);
            mLastRenderStatus = "delta-exception=" + error.getClass().getSimpleName() +
                " message=" + error.getMessage();
            mRenderFailed = true;
        }
        return null;
    }

    <T> T decodeRenderDelta(int topRow, boolean forceFull, RenderDeltaDecoder<T> decoder) {
        if (decoder == null) return null;
        synchronized (mRenderDecodeLock) {
            GhosttyRenderDelta delta = renderDeltaLocked(topRow, forceFull);
            return delta == null ? null : decoder.decode(delta);
        }
    }

    String renderStatus() {
        synchronized (mRenderDecodeLock) {
            return mLastRenderStatus;
        }
    }

    boolean isRenderHealthy() {
        synchronized (mRenderDecodeLock) {
            synchronized (this) {
                return mHandle != 0L && !mFailed && !mRenderFailed && mRenderBuffer != null;
            }
        }
    }

    /** Debug-only fault injection used to prove that render recovery preserves terminal state. */
    void forceRenderFailureForTesting() {
        synchronized (mRenderDecodeLock) {
            synchronized (this) {
                if (mHandle == 0L || mFailed) {
                    throw new IllegalStateException(
                        "Cannot inject a render failure into a closed backend");
                }
            }
            mRenderFailed = true;
            mLastRenderStatus = "forced-render-failure-for-testing";
        }
    }

    boolean recoverRender() {
        synchronized (mRenderDecodeLock) {
            final long handle;
            synchronized (this) {
                if (mHandle == 0L || mFailed) return false;
                handle = mHandle;
            }
            mRenderRecoveryAttempts++;
            try {
                if (!nativeRecoverRender(handle)) {
                    mLastRenderStatus = "recovery-native-failure attempts=" +
                        mRenderRecoveryAttempts;
                    return false;
                }
                if (mRenderBuffer == null) {
                    mRenderBuffer = allocateRenderBuffer(MIN_RENDER_BUFFER_BYTES);
                }
                mRenderFailed = false;
                mRenderRecoverySuccesses++;
                mLastRenderStatus = "recovered attempts=" + mRenderRecoveryAttempts +
                    " successes=" + mRenderRecoverySuccesses;
                Log.w(LOG_TAG, "render-recovery-v1 " + mLastRenderStatus);
                return true;
            } catch (LinkageError | RuntimeException | OutOfMemoryError error) {
                mRenderFailed = true;
                mLastRenderStatus = "recovery-exception=" +
                    error.getClass().getSimpleName();
                Log.e(LOG_TAG, "Complete libghostty-vt render recovery failed", error);
                return false;
            }
        }
    }

    @Override
    public void close() {
        synchronized (mRenderDecodeLock) {
            final long handle;
            synchronized (this) {
                handle = mHandle;
                mHandle = 0L;
            }
            if (handle == 0L || !sNativeMethodsAvailable) return;
            try {
                nativeClose(handle);
            } catch (LinkageError | RuntimeException error) {
                Log.w(LOG_TAG, "Complete libghostty-vt terminal cleanup failed", error);
            }
        }
    }

    /** Last-resort native cleanup for sessions discarded without an explicit disposal callback. */
    @Override
    @SuppressWarnings("deprecation")
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    static void setValidationEnabledForDiagnostics(boolean enabled) {
        sValidationEnabled = enabled;
    }

    static void setProductionEnabled(boolean enabled) {
        sProductionEnabled = enabled;
    }

    static boolean isProductionEnabled() {
        return sProductionEnabled;
    }

    static boolean isLibraryAvailableForDiagnostics() {
        if (!ensureNativeMethods()) return false;
        try {
            return nativeIsAvailable();
        } catch (LinkageError | RuntimeException error) {
            return false;
        }
    }

    static String libraryInfoForDiagnostics() {
        if (!ensureNativeMethods()) return "libghostty-vt unavailable";
        try {
            return nativeLibraryInfo();
        } catch (LinkageError | RuntimeException error) {
            return "libghostty-vt error=" + error.getClass().getSimpleName();
        }
    }

    private static boolean ensureNativeMethods() {
        if (sLoadAttempted) return sNativeMethodsAvailable;
        synchronized (GhosttyTerminalBackend.class) {
            if (sLoadAttempted) return sNativeMethodsAvailable;
            try {
                System.loadLibrary("termux");
                sNativeMethodsAvailable = true;
            } catch (LinkageError | SecurityException ignored) {
                sNativeMethodsAvailable = false;
            } finally {
                sLoadAttempted = true;
            }
            return sNativeMethodsAvailable;
        }
    }

    private static void logActivationOnce() {
        if (sActivationLogged) return;
        synchronized (GhosttyTerminalBackend.class) {
            if (sActivationLogged) return;
            sActivationLogged = true;
            Log.i(LOG_TAG, "Complete Ghostty terminal + batch render-state authority active: " +
                libraryInfoForDiagnostics());
        }
    }

    private static ByteBuffer allocateRenderBuffer(int capacity) {
        if (capacity <= 0 || capacity > MAX_RENDER_BUFFER_BYTES) {
            throw new IllegalArgumentException("Invalid Ghostty render buffer capacity=" + capacity);
        }
        return ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
    }

    private static int nextPowerOfTwo(long value) {
        if (value <= 1L) return 1;
        if (value > MAX_RENDER_BUFFER_BYTES) return -1;
        int result = 1;
        while (result < value) result <<= 1;
        return result;
    }

    interface Effects {
        Effects NONE = new Effects() {
            @Override public void writePty(byte[] data) { }
            @Override public void bell() { }
            @Override public void titleChanged(String title) { }
            @Override public boolean clipboardWrite(String text) { return false; }
            @Override public void onColorsChanged() { }
            @Override public void hostControl(String payload) { }
        };

        void writePty(byte[] data);
        void bell();
        void titleChanged(String title);
        boolean clipboardWrite(String text);
        void onColorsChanged();
        void hostControl(String payload);
    }

    interface RenderDecoder<T> {
        T decode(GhosttyRenderSnapshot snapshot);
    }

    interface RenderDeltaDecoder<T> {
        T decode(GhosttyRenderDelta delta);
    }

    static final class State {
        final int columns;
        final int rows;
        final int cursorColumn;
        final int cursorRow;
        final boolean cursorVisible;
        final int cursorStyle;
        final boolean alternateScreen;
        final int scrollbackRows;
        final int totalRows;
        final long scrollbarTotal;
        final long scrollbarOffset;
        final long scrollbarLength;
        final long modes;
        final int foregroundColor;
        final int backgroundColor;
        final int cursorColor;
        final boolean vtProcessingError;
        final long paletteHash;
        final long generation;
        final long bytes;
        final long writes;

        State(long[] values) {
            if (values == null || values.length < STATE_VALUES || values[0] != 1L) {
                throw new IllegalArgumentException("Incomplete libghostty-vt state packet");
            }
            columns = (int) values[1];
            rows = (int) values[2];
            cursorColumn = (int) values[3];
            cursorRow = (int) values[4];
            cursorVisible = values[5] != 0L;
            int nativeCursorStyle = (int) values[6];
            cursorStyle = nativeCursorStyle == 0
                ? TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
                : nativeCursorStyle == 2
                    ? TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
                    : TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
            alternateScreen = values[7] == 1L;
            scrollbackRows = saturatingInt(values[8]);
            totalRows = saturatingInt(values[9]);
            scrollbarTotal = values[10];
            scrollbarOffset = values[11];
            scrollbarLength = values[12];
            modes = values[13];
            foregroundColor = (int) values[14];
            backgroundColor = (int) values[15];
            cursorColor = (int) values[16];
            vtProcessingError = values[17] != 0L;
            paletteHash = values[18];
            generation = values[19];
            bytes = values[20];
            writes = values[21];
        }

        boolean mode(long mode) {
            return (modes & mode) != 0L;
        }

        private static int saturatingInt(long value) {
            return value <= 0L ? 0 : value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
        }
    }

    @SuppressWarnings("unused") // Invoked synchronously by the JNI effect bridge.
    private void onNativeWritePty(byte[] data) {
        if (data != null && data.length > 0) mEffects.writePty(data);
    }

    @SuppressWarnings("unused") // Invoked synchronously by the JNI effect bridge.
    private void onNativeBell() {
        mEffects.bell();
    }

    @SuppressWarnings("unused") // Invoked synchronously by the JNI effect bridge.
    private void onNativeTitleChanged(byte[] title) {
        mEffects.titleChanged(title == null ? "" : new String(title, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unused") // Invoked synchronously by the JNI effect bridge.
    private boolean onNativeClipboardWrite(byte[] text) {
        return mEffects.clipboardWrite(
            text == null ? "" : new String(text, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unused") // Invoked synchronously by the JNI Termux OSC bridge.
    private void onNativeHostControl(byte[] payload) {
        mEffects.hostControl(
            payload == null ? "" : new String(payload, StandardCharsets.UTF_8));
    }

    static final class Snapshot {
        final long writes;
        final long bytes;
        final int columns;
        final int rows;
        final int cursorColumn;
        final int cursorRow;
        final boolean cursorVisible;
        final int dirtyState;
        final long rowCount;
        final long cellCount;
        final long textCellCount;
        final long graphemeCodepoints;
        final long contentHash;
        final long styledCellCount;
        final int activeScreen;
        final long scrollbackRows;
        final boolean vtProcessingError;
        final long renderUpdates;
        final boolean simd;
        final int optimizeMode;

        Snapshot(long[] values) {
            if (values == null || values.length < SNAPSHOT_VALUES) {
                throw new IllegalArgumentException("Incomplete libghostty-vt snapshot");
            }
            writes = values[0];
            bytes = values[1];
            columns = (int) values[2];
            rows = (int) values[3];
            cursorColumn = (int) values[4];
            cursorRow = (int) values[5];
            cursorVisible = values[6] != 0L;
            dirtyState = (int) values[7];
            rowCount = values[8];
            cellCount = values[9];
            textCellCount = values[10];
            graphemeCodepoints = values[11];
            contentHash = values[12];
            styledCellCount = values[13];
            activeScreen = (int) values[14];
            scrollbackRows = values[15];
            vtProcessingError = values[16] != 0L;
            renderUpdates = values[17];
            simd = values[18] != 0L;
            optimizeMode = (int) values[19];
        }

        String toEvidenceString() {
            return String.format(Locale.US,
                "ghostty_full_writes=%d ghostty_full_bytes=%d ghostty_full_grid=%dx%d " +
                    "ghostty_full_cursor=%d,%d ghostty_full_rows=%d ghostty_full_cells=%d " +
                    "ghostty_full_text_cells=%d ghostty_full_graphemes=%d ghostty_full_styled_cells=%d " +
                    "ghostty_full_scrollback=%d ghostty_full_screen_hash=%016x ghostty_full_vt_error=%s " +
                    "ghostty_full_render_updates=%d ghostty_full_simd=%s ghostty_full_optimize=%d",
                writes, bytes, columns, rows, cursorColumn, cursorRow, rowCount, cellCount,
                textCellCount, graphemeCodepoints, styledCellCount, scrollbackRows, contentHash,
                vtProcessingError, renderUpdates, simd, optimizeMode);
        }
    }

    private static native boolean nativeIsAvailable();
    private static native String nativeLibraryInfo();
    private static native long nativeOpen(int columns, int rows, long maxScrollback,
                                          int cellWidth, int cellHeight,
                                          GhosttyTerminalBackend callbackTarget, int[] colors);
    private static native boolean nativeWrite(long handle, byte[] input, int length, long[] state);
    private static native boolean nativeResize(long handle, int columns, int rows,
                                               int cellWidth, int cellHeight,
                                               int viewportTopRow, int anchorColumn,
                                               int anchorViewportRow, int targetViewportRow,
                                               int visibleScrollbackRows,
                                               long[] anchor, long[] state);
    private static native boolean nativeState(long handle, long[] state);
    private static native boolean nativeReset(long handle, long[] state);
    private static native boolean nativeSetColors(long handle, int[] colors, long[] state);
    private static native boolean nativeSetDefaultCursorStyle(long handle, int cursorStyle,
                                                              long[] state);
    private static native boolean nativeSetMode(long handle, int mode, boolean value, long[] state);
    private static native byte[] nativeFormatRange(long handle, int x1, int y1, int x2, int y2,
                                                   boolean unwrap, boolean trim);
    private static native byte[] nativeFormatAll(long handle, boolean unwrap);
    private static native int[] nativeSelectWord(long handle, int column, int row);
    private static native int nativeCellWide(long handle, int column, int row);
    private static native byte[] nativeSelectionHyperlinks(long handle,
                                                           int x1, int y1, int x2, int y2);
    private static native byte[] nativeEncodeMouse(long handle, int button, int column, int row,
                                                   boolean pressed);
    private static native byte[] nativeEncodeKey(long handle, int androidKeyCode, int keyModifiers,
                                                 int action);
    private static native byte[] nativeEncodeText(long handle, byte[] utf8, int androidKeyCode,
                                                  int action, int unshiftedCodePoint,
                                                  int keyModifiers);
    private static native byte[] nativeEncodePaste(long handle, byte[] input);
    private static native byte[] nativeEncodeFocus(long handle, boolean focused);
    private static native boolean nativeSnapshot(long handle, long[] output);
    private static native int nativeRenderSnapshot(long handle, ByteBuffer output,
                                                   long[] metadata, int topRow);
    private static native int nativeRenderDelta(long handle, ByteBuffer output,
                                                long[] metadata, int topRow, boolean forceFull);
    private static native boolean nativeRecoverRender(long handle);
    private static native void nativeClose(long handle);
}
