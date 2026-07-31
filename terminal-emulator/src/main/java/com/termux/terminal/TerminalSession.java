package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.Trace;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.view.Choreographer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * {@link #updateSize(int, int, int, int)} terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * Healthy Ghostty PTY bytes are parsed on the dedicated input-reader thread. UI callbacks are
 * marshalled to the main thread, while the Java compatibility parser remains main-thread confined.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_JAVA_INPUT = 1;
    private static final int MSG_NATIVE_SCREEN_UPDATE = 2;
    private static final int MSG_PROCESS_EXITED = 4;
    private static final int MSG_SYNCHRONIZED_OUTPUT_TIMEOUT = 5;
    private static final long SYNCHRONIZED_OUTPUT_TIMEOUT_MS = 250L;

    /**
     * Main-thread processing budget for Java compatibility output.
     *
     * <p>The production Ghostty path never uses this budget because it parses directly on the PTY
     * reader. The bounded main-thread queue exists only for explicit rollback or native failure.
     */
    private static final int PROCESS_INPUT_MAX_BYTES_PER_SLICE = 64 * 1024;
    private static final int PROCESS_INPUT_BUFFER_SIZE = 32 * 1024;
    private static final int PROCESS_TO_TERMINAL_IO_QUEUE_CAPACITY_BYTES = 1024 * 1024;
    private static final int TERMINAL_TO_PROCESS_IO_QUEUE_CAPACITY_BYTES = 1024 * 1024;
    private static final long PTY_TAIL_DRAIN_GRACE_MS = 2000L;
    private static final long PTY_READER_STOP_GRACE_MS = 500L;
    private static final long INPUT_DROP_LOG_INTERVAL_MS = 5000L;
    private static final long MAX_ASYNC_TERMINAL_INPUT_BYTES = 16L * 1024L * 1024L;

    public final String mHandle = UUID.randomUUID().toString();

    volatile TerminalEmulator mEmulator;

    /**
     * Java-only fallback queue. Healthy Ghostty traffic bypasses this queue and is parsed on the
     * dedicated PTY reader thread.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(PROCESS_TO_TERMINAL_IO_QUEUE_CAPACITY_BYTES);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the {@link #mTerminalFileDescriptor}.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(TERMINAL_TO_PROCESS_IO_QUEUE_CAPACITY_BYTES);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    volatile TerminalSessionClient mClient;

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    volatile int mShellPid;

    /** The exit status of the shell process. Only valid if ${@link #mShellPid} is -1. */
    int mShellExitStatus;

    /** Guarded by this session. Disposal may be requested before a killed child reaches EOF. */
    private boolean mDisposeRequested;
    private boolean mEmulatorResourcesReleased;

    /**
     * The file descriptor referencing the master half of a pseudo-terminal pair, resulting from calling
     * {@link JNI#createSubprocess(String, String, String[], String[], int[], int, int, int, int)}.
     */
    private int mTerminalFileDescriptor = -1;
    private ParcelFileDescriptor mPtyInputDescriptor;
    private ParcelFileDescriptor mPtyOutputDescriptor;
    private final Object mPtyDispatchLock = new Object();
    private volatile boolean mStopPtyReader;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final MainThreadHandler mMainThreadHandler = new MainThreadHandler();
    private final AtomicBoolean mJavaInputScheduled = new AtomicBoolean(false);
    private final AtomicBoolean mNativeScreenUpdateScheduled = new AtomicBoolean(false);
    private final AtomicLong mPtyParserOffMainCalls = new AtomicLong();
    private final AtomicLong mPtyParserOffMainBytes = new AtomicLong();
    private final AtomicLong mPtyParserMainThreadCalls = new AtomicLong();
    private final AtomicLong mNativeScreenUpdateRequests = new AtomicLong();
    private final AtomicLong mNativeScreenUpdateCoalesced = new AtomicLong();
    private final AtomicLong mNativeScreenUpdatePublished = new AtomicLong();
    private final AtomicLong mTerminalInputDroppedBytes = new AtomicLong();
    private final AtomicLong mTerminalInputDeferredBytes = new AtomicLong();
    private final AtomicLong mResizeTransactions = new AtomicLong();
    private final AtomicLong mPtyWindowSizeRequests = new AtomicLong();
    private final AtomicLong mRedundantResizeRequestsSuppressed = new AtomicLong();
    private final AtomicLong mLastInputDropLogMs = new AtomicLong();
    private final Object mTerminalInputSubmissionLock = new Object();
    private ExecutorService mTerminalInputOverflowExecutor;
    private int mTerminalInputOverflowTasks;
    private long mTerminalInputOverflowBytes;
    private boolean mTerminalInputClosed;
    private volatile String mLastPtyParserThreadName = "not-started";
    private volatile long mLastPtyParserThreadId = -1L;
    private int mLastPtyColumns = -1;
    private int mLastPtyRows = -1;
    private int mLastPtyCellWidthPixels = -1;
    private int mLastPtyCellHeightPixels = -1;

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final Integer mTranscriptRows;


    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    private void scheduleJavaInput() {
        if (mJavaInputScheduled.compareAndSet(false, true)) {
            mMainThreadHandler.sendEmptyMessage(MSG_JAVA_INPUT);
        }
    }

    private void scheduleNativeScreenUpdate() {
        mNativeScreenUpdateRequests.incrementAndGet();
        if (mNativeScreenUpdateScheduled.compareAndSet(false, true)) {
            mMainThreadHandler.sendEmptyMessage(MSG_NATIVE_SCREEN_UPDATE);
        } else {
            mNativeScreenUpdateCoalesced.incrementAndGet();
        }
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the attached pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        updateSize(columns, rows, cellWidthPixels, cellHeightPixels, 0, -1, -1, -1);
    }

    /** Resize while preserving a specific visible cell across Ghostty's native reflow. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels,
                           int viewportTopRow, int anchorColumn,
                           int anchorViewportRow, int targetViewportRow) {
        updateSizeInternal(columns, rows, cellWidthPixels, cellHeightPixels,
            viewportTopRow, anchorColumn, anchorViewportRow, targetViewportRow);
    }

    private void updateSizeInternal(int columns, int rows, int cellWidthPixels, int cellHeightPixels,
                                    int viewportTopRow, int anchorColumn,
                                    int anchorViewportRow, int targetViewportRow) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            // Keep the parser from mutating the model between SIGWINCH and the anchored reflow.
            synchronized (mEmulator) {
                boolean emulatorGeometryChanged = !mEmulator.hasExactGeometry(
                    columns, rows, cellWidthPixels, cellHeightPixels);
                requestPtyWindowSize(columns, rows, cellWidthPixels, cellHeightPixels);
                if (!emulatorGeometryChanged) {
                    mRedundantResizeRequestsSuppressed.incrementAndGet();
                    return;
                }
                mResizeTransactions.incrementAndGet();
                mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels,
                    viewportTopRow, anchorColumn, anchorViewportRow, targetViewportRow);
            }
        }
    }

    private boolean requestPtyWindowSize(int columns, int rows,
                                         int cellWidthPixels, int cellHeightPixels) {
        synchronized (this) {
            if (mLastPtyColumns == columns && mLastPtyRows == rows &&
                mLastPtyCellWidthPixels == cellWidthPixels &&
                mLastPtyCellHeightPixels == cellHeightPixels) {
                return false;
            }
            if (mTerminalFileDescriptor < 0) return false;
            mPtyWindowSizeRequests.incrementAndGet();
            JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns,
                cellWidthPixels, cellHeightPixels);
            mLastPtyColumns = columns;
            mLastPtyRows = rows;
            mLastPtyCellWidthPixels = cellWidthPixels;
            mLastPtyCellHeightPixels = cellHeightPixels;
            return true;
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);

        int[] processId = new int[1];
        mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns, cellWidthPixels, cellHeightPixels);
        mLastPtyColumns = columns;
        mLastPtyRows = rows;
        mLastPtyCellWidthPixels = cellWidthPixels;
        mLastPtyCellHeightPixels = cellHeightPixels;
        mShellPid = processId[0];
        ParcelFileDescriptor ownedInputDescriptor = null;
        ParcelFileDescriptor ownedOutputDescriptor = null;
        int unownedInputFd = -1;
        int unownedOutputFd = -1;
        try {
            unownedInputFd = JNI.dup(mTerminalFileDescriptor);
            unownedOutputFd = JNI.dup(mTerminalFileDescriptor);
            ownedInputDescriptor = ParcelFileDescriptor.adoptFd(unownedInputFd);
            unownedInputFd = -1;
            ownedOutputDescriptor = ParcelFileDescriptor.adoptFd(unownedOutputFd);
            unownedOutputFd = -1;
            synchronized (this) {
                mPtyInputDescriptor = ownedInputDescriptor;
                mPtyOutputDescriptor = ownedOutputDescriptor;
            }
        } catch (RuntimeException error) {
            if (unownedInputFd >= 0) JNI.close(unownedInputFd);
            if (unownedOutputFd >= 0) JNI.close(unownedOutputFd);
            closeDescriptorQuietly(ownedInputDescriptor);
            closeDescriptorQuietly(ownedOutputDescriptor);
            abortPtyStartup();
            throw error;
        }
        final ParcelFileDescriptor inputDescriptor = ownedInputDescriptor;
        final ParcelFileDescriptor outputDescriptor = ownedOutputDescriptor;
        mClient.setTerminalShellPid(this, mShellPid);

        final Thread inputReaderThread = new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                try (InputStream termIn = new ParcelFileDescriptor.AutoCloseInputStream(inputDescriptor)) {
                    final byte[] buffer = new byte[PROCESS_INPUT_BUFFER_SIZE];
                    while (true) {
                        int read = readProcessOutputBatch(termIn, buffer);
                        if (read == -1) return;
                        if (read > 0) {
                            synchronized (mPtyDispatchLock) {
                                if (mStopPtyReader || !dispatchProcessOutput(buffer, read, true)) return;
                            }
                        }
                    }
                } catch (Exception e) {
                    if (!mStopPtyReader && isRunning()) {
                        Logger.logWarn(mClient, LOG_TAG,
                            "PTY reader stopped unexpectedly: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                } finally {
                    clearPtyInputDescriptor(inputDescriptor);
                }
            }
        };
        inputReaderThread.setDaemon(true);
        inputReaderThread.start();

        Thread outputWriterThread = new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[PROCESS_INPUT_BUFFER_SIZE];
                try (ParcelFileDescriptor.AutoCloseOutputStream termOut =
                         new ParcelFileDescriptor.AutoCloseOutputStream(outputDescriptor)) {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        termOut.write(buffer, 0, bytesToWrite);
                    }
                } catch (IOException e) {
                    if (isRunning()) {
                        Logger.logWarn(mClient, LOG_TAG,
                            "PTY writer stopped unexpectedly: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                } finally {
                    closeTerminalInputQueue();
                    clearPtyOutputDescriptor(outputDescriptor);
                }
            }
        };
        outputWriterThread.setDaemon(true);
        outputWriterThread.start();

        Thread waiterThread = new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                int processExitCode = JNI.waitFor(mShellPid);
                boolean interrupted = joinThread(inputReaderThread, PTY_TAIL_DRAIN_GRACE_MS);
                if (inputReaderThread.isAlive()) {
                    synchronized (mPtyDispatchLock) {
                        mStopPtyReader = true;
                    }
                    closePtyInputDescriptor();
                    interrupted |= joinThread(inputReaderThread, PTY_READER_STOP_GRACE_MS);
                    Logger.logWarn(mClient, LOG_TAG,
                        "PTY tail exceeded " + PTY_TAIL_DRAIN_GRACE_MS +
                            "ms after shell exit; bounded drain was closed");
                }
                synchronized (mPtyDispatchLock) {
                    mStopPtyReader = true;
                    appendProcessExitDescription(processExitCode);
                }
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
                if (interrupted) Thread.currentThread().interrupt();
            }
        };
        waiterThread.setDaemon(true);
        waiterThread.start();

    }

    /**
     * Read one blocking PTY chunk, then drain only bytes that are already available into the same
     * parser batch. Linux PTYs commonly wake readers at roughly 4 KiB even when a producer has much
     * more queued. Draining before parsing releases PTY capacity sooner and amortizes JNI/state
     * publication without waiting for future interactive input.
     */
    static int readProcessOutputBatch(InputStream input, byte[] buffer) throws IOException {
        if (input == null || buffer == null || buffer.length == 0) {
            throw new IllegalArgumentException("Input and non-empty buffer are required");
        }
        int total = input.read(buffer, 0, buffer.length);
        if (total <= 0) return total;
        while (total < buffer.length) {
            int available = input.available();
            if (available <= 0) break;
            int read = input.read(buffer, total, Math.min(available, buffer.length - total));
            if (read <= 0) break;
            total += read;
        }
        return total;
    }

    /** Route one ordered process-output chunk without ever parsing healthy Ghostty bytes on UI. */
    private boolean dispatchProcessOutput(byte[] buffer, int length, boolean countAsPtyTraffic) {
        TerminalEmulator emulator = mEmulator;
        boolean parsedByGhostty = false;
        if (emulator != null) {
            parsedByGhostty = emulator.appendPtyFromWorker(buffer, length);
        }
        if (parsedByGhostty) {
            if (countAsPtyTraffic) {
                Thread parserThread = Thread.currentThread();
                mLastPtyParserThreadName = parserThread.getName();
                mLastPtyParserThreadId = parserThread.getId();
                if (parserThread == mMainThreadHandler.getLooper().getThread()) {
                    mPtyParserMainThreadCalls.incrementAndGet();
                } else {
                    mPtyParserOffMainCalls.incrementAndGet();
                    mPtyParserOffMainBytes.addAndGet(length);
                }
            }
            scheduleNativeScreenUpdate();
            return true;
        }

        // Missing/disabled/failed native authority is deliberately isolated to the historical
        // main-thread Java emulator. The transition replay has already restored its state.
        if (!mProcessToTerminalIOQueue.write(buffer, 0, length)) return false;
        scheduleJavaInput();
        return true;
    }

    private void appendProcessExitDescription(int exitCode) {
        String exitDescription = "\r\n[Process completed";
        if (exitCode > 0) {
            exitDescription += " (code " + exitCode + ")";
        } else if (exitCode < 0) {
            exitDescription += " (signal " + (-exitCode) + ")";
        }
        exitDescription += " - press Enter]";
        byte[] bytes = exitDescription.getBytes(StandardCharsets.UTF_8);
        dispatchProcessOutput(bytes, bytes.length, false);
    }

    /** Write data to the shell process. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mShellPid <= 0 || count <= 0) return;
        synchronized (mTerminalInputSubmissionLock) {
            if (mTerminalInputClosed || mShellPid <= 0) return;
            if (mTerminalInputOverflowTasks == 0 &&
                mTerminalToProcessIOQueue.tryWrite(data, offset, count)) {
                return;
            }

            boolean interrupted = false;
            while (mTerminalInputOverflowTasks > 0 &&
                   (count > MAX_ASYNC_TERMINAL_INPUT_BYTES ||
                    mTerminalInputOverflowBytes > MAX_ASYNC_TERMINAL_INPUT_BYTES - count) &&
                   !mTerminalInputClosed && mShellPid > 0) {
                try {
                    mTerminalInputSubmissionLock.wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (mTerminalInputClosed || mShellPid <= 0) return;

            if (count > MAX_ASYNC_TERMINAL_INPUT_BYTES) {
                if (!mTerminalToProcessIOQueue.write(data, offset, count)) {
                    recordTerminalInputDrop(count);
                }
                return;
            }

            byte[] owned = new byte[count];
            System.arraycopy(data, offset, owned, 0, count);
            mTerminalInputOverflowTasks++;
            mTerminalInputOverflowBytes += count;
            mTerminalInputDeferredBytes.addAndGet(count);
            try {
                getTerminalInputOverflowExecutorLocked().execute(() -> writeDeferredTerminalInput(owned));
            } catch (RejectedExecutionException error) {
                mTerminalInputOverflowTasks--;
                mTerminalInputOverflowBytes -= count;
                mTerminalInputSubmissionLock.notifyAll();
                if (!mTerminalToProcessIOQueue.write(owned, 0, owned.length)) {
                    recordTerminalInputDrop(owned.length);
                }
            }
        }
    }

    private void writeDeferredTerminalInput(byte[] owned) {
        boolean written = false;
        try {
            written = mTerminalToProcessIOQueue.write(owned, 0, owned.length);
        } finally {
            synchronized (mTerminalInputSubmissionLock) {
                mTerminalInputOverflowTasks--;
                mTerminalInputOverflowBytes -= owned.length;
                mTerminalInputSubmissionLock.notifyAll();
            }
        }
        if (!written && mShellPid > 0) recordTerminalInputDrop(owned.length);
    }

    private ExecutorService getTerminalInputOverflowExecutorLocked() {
        if (mTerminalInputOverflowExecutor == null) {
            mTerminalInputOverflowExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable,
                    "TermSessionInputOverflow[" + mHandle.substring(0, 8) + "]");
                thread.setDaemon(true);
                return thread;
            });
        }
        return mTerminalInputOverflowExecutor;
    }

    private void recordTerminalInputDrop(int count) {
        long totalDropped = mTerminalInputDroppedBytes.addAndGet(count);
        long now = SystemClock.uptimeMillis();
        long lastLog = mLastInputDropLogMs.get();
        if (now - lastLog >= INPUT_DROP_LOG_INTERVAL_MS &&
            mLastInputDropLogMs.compareAndSet(lastLog, now)) {
            Logger.logError(mClient, LOG_TAG,
                "PTY input transport closed before " + count +
                    " ordered bytes could be written; total=" + totalDropped);
        }
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    public long getPtyParserOffMainThreadCallsForDiagnostics() {
        return mPtyParserOffMainCalls.get();
    }

    public long getPtyParserOffMainThreadBytesForDiagnostics() {
        return mPtyParserOffMainBytes.get();
    }

    public long getPtyParserMainThreadCallsForDiagnostics() {
        return mPtyParserMainThreadCalls.get();
    }

    public long getNativeScreenUpdateRequestsForDiagnostics() {
        return mNativeScreenUpdateRequests.get();
    }

    public long getNativeScreenUpdateCoalescedForDiagnostics() {
        return mNativeScreenUpdateCoalesced.get();
    }

    public long getNativeScreenUpdatePublishedForDiagnostics() {
        return mNativeScreenUpdatePublished.get();
    }

    public long getTerminalInputDroppedBytesForDiagnostics() {
        return mTerminalInputDroppedBytes.get();
    }

    public long getTerminalInputDeferredBytesForDiagnostics() {
        return mTerminalInputDeferredBytes.get();
    }

    /** Number of post-initialization emulator resize transactions requested by the UI. */
    public long getResizeTransactionsForDiagnostics() {
        return mResizeTransactions.get();
    }

    /** Exact number of process-visible PTY window-size ioctls requested by this session. */
    public long getPtyWindowSizeRequestsForDiagnostics() {
        return mPtyWindowSizeRequests.get();
    }

    public long getRedundantResizeRequestsSuppressedForDiagnostics() {
        return mRedundantResizeRequestsSuppressed.get();
    }

    public String getLastPtyParserThreadNameForDiagnostics() {
        return mLastPtyParserThreadName;
    }

    public long getLastPtyParserThreadIdForDiagnostics() {
        return mLastPtyParserThreadId;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mMainThreadHandler.cancelSynchronizedOutputTimeout();
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by sending SIGKILL to the shell. */
    public void finishIfRunning() {
        final int shellPid;
        synchronized (this) {
            shellPid = mShellPid;
        }
        final int hostPid = android.os.Process.myPid();
        if (!isSafeSignalTarget(shellPid, hostPid)) {
            if (shellPid == hostPid) {
                Logger.logError(mClient, LOG_TAG,
                    "Refusing to send SIGKILL to the Termux host process: pid=" + shellPid);
            }
            return;
        }

        killProcessTree(shellPid, true);
    }

    static boolean isSafeSignalTarget(int targetPid, int hostPid) {
        // kill(0, signal) targets the caller's whole process group.
        return targetPid > 0 && targetPid != hostPid;
    }

    /** Cleanup resources when the process exits. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
        }

        // Stop the reader and writer threads, and close the I/O streams
        closeTerminalInputQueue();
        mProcessToTerminalIOQueue.close();
        closePtyInputDescriptor();
        closePtyOutputDescriptor();
        closeTerminalControlDescriptor();
    }

    /**
     * Permanently dispose a session that has been removed from the service. A running child may
     * still have ordered PTY tail bytes in flight, so native/parser resources are released only
     * after that reader reaches EOF. Completed sessions retained in the UI are deliberately not
     * disposed until the user removes them.
     */
    public void dispose() {
        synchronized (this) {
            mDisposeRequested = true;
        }
        releaseDisposedEmulatorIfStopped();
    }

    private void releaseDisposedEmulatorIfStopped() {
        TerminalEmulator emulatorToRelease;
        synchronized (this) {
            if (!mDisposeRequested || mShellPid > 0 || mEmulatorResourcesReleased ||
                mEmulator == null) return;
            mEmulatorResourcesReleased = true;
            emulatorToRelease = mEmulator;
        }
        mMainThreadHandler.removeCallbacksAndMessages(null);
        emulatorToRelease.releaseNativeResources();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        dispatchClientCallback(() -> mClient.onTitleChanged(this));
    }

    public synchronized boolean isRunning() {
        return mShellPid > 0;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        dispatchClientCallback(() -> mClient.onCopyTextToClipboard(this, text));
    }

    @Override
    public void onPasteTextFromClipboard() {
        dispatchClientCallback(() -> mClient.onPasteTextFromClipboard(this));
    }

    @Override
    public void onBell() {
        dispatchClientCallback(() -> mClient.onBell(this));
    }

    @Override
    public void onTerminalHostControlCommand(String command, String argument) {
        dispatchClientCallback(() ->
            mClient.onTerminalHostControlCommand(this, command, argument));
    }

    @Override
    public void onColorsChanged() {
        dispatchClientCallback(() -> mClient.onColorsChanged(this));
    }

    private void dispatchClientCallback(Runnable callback) {
        if (Thread.currentThread() == mMainThreadHandler.getLooper().getThread()) {
            callback.run();
        } else {
            mMainThreadHandler.post(callback);
        }
    }

    public synchronized int getPid() {
        return mShellPid;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (IOException | SecurityException e) {
            Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    private boolean joinThread(Thread thread, long timeoutMs) {
        boolean interrupted = false;
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (thread.isAlive()) {
            long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0) break;
            try {
                thread.join(remaining);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private void abortPtyStartup() {
        int shellPid = mShellPid;
        closePtyInputDescriptor();
        closePtyOutputDescriptor();
        closeTerminalControlDescriptor();
        if (shellPid > 0) {
            killProcessTree(shellPid, false);
            JNI.waitFor(shellPid);
        }
        synchronized (this) {
            mShellPid = -1;
        }
    }

    private void killProcessTree(int shellPid, boolean logFailure) {
        ErrnoException groupError = null;
        try {
            Os.kill(-shellPid, OsConstants.SIGKILL);
            return;
        } catch (ErrnoException error) {
            groupError = error;
        }

        try {
            Os.kill(shellPid, OsConstants.SIGKILL);
        } catch (ErrnoException processError) {
            if (logFailure && processError.errno != OsConstants.ESRCH) {
                Logger.logWarn(mClient, LOG_TAG,
                    "Failed sending SIGKILL to process group and leader: group=" +
                        groupError.getMessage() + ", process=" + processError.getMessage());
            }
        }
    }

    private void clearPtyInputDescriptor(ParcelFileDescriptor expected) {
        synchronized (this) {
            if (mPtyInputDescriptor == expected) mPtyInputDescriptor = null;
        }
    }

    private void clearPtyOutputDescriptor(ParcelFileDescriptor expected) {
        synchronized (this) {
            if (mPtyOutputDescriptor == expected) mPtyOutputDescriptor = null;
        }
    }

    private void closePtyInputDescriptor() {
        ParcelFileDescriptor descriptor;
        synchronized (this) {
            descriptor = mPtyInputDescriptor;
            mPtyInputDescriptor = null;
        }
        closeDescriptorQuietly(descriptor);
    }

    private void closePtyOutputDescriptor() {
        ParcelFileDescriptor descriptor;
        synchronized (this) {
            descriptor = mPtyOutputDescriptor;
            mPtyOutputDescriptor = null;
        }
        closeDescriptorQuietly(descriptor);
    }

    private void closeTerminalInputQueue() {
        synchronized (mTerminalInputSubmissionLock) {
            if (mTerminalInputClosed) return;
            mTerminalInputClosed = true;
            mTerminalToProcessIOQueue.close();
            if (mTerminalInputOverflowExecutor != null) {
                mTerminalInputOverflowExecutor.shutdownNow();
            }
            mTerminalInputSubmissionLock.notifyAll();
        }
    }

    private void closeTerminalControlDescriptor() {
        int fd;
        synchronized (this) {
            fd = mTerminalFileDescriptor;
            mTerminalFileDescriptor = -1;
        }
        if (fd >= 0) JNI.close(fd);
    }

    private static void closeDescriptorQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (IOException ignored) {
        }
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[PROCESS_INPUT_BUFFER_SIZE];
        private boolean mSynchronizedOutputTimeoutScheduled;
        private boolean mNativeFrameCallbackScheduled;
        private long mNativeFrameScheduledNanos;
        private long mMaxNativeFrameWaitMicros;
        private long mLastNativeFrameLogMs;
        private final Choreographer.FrameCallback mNativeFrameCallback = frameTimeNanos ->
            publishNativeScreenUpdateAtFrame();

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SYNCHRONIZED_OUTPUT_TIMEOUT:
                    handleSynchronizedOutputTimeout();
                    return;
                case MSG_NATIVE_SCREEN_UPDATE:
                    scheduleNativeScreenUpdateAtFrame();
                    return;
                case MSG_JAVA_INPUT:
                    mJavaInputScheduled.set(false);
                    processPendingJavaInput(false);
                    return;
                case MSG_PROCESS_EXITED:
                    // The PTY reader has joined, so no producer can add more bytes. Drain the
                    // complete Java fallback tail before publishing process completion.
                    mJavaInputScheduled.set(false);
                    processPendingJavaInput(true);
                    handleProcessExited((Integer) msg.obj);
                    return;
                default:
                    return;
            }
        }

        private void scheduleNativeScreenUpdateAtFrame() {
            if (mNativeFrameCallbackScheduled) return;
            mNativeFrameCallbackScheduled = true;
            mNativeFrameScheduledNanos = System.nanoTime();
            try {
                Choreographer.getInstance().postFrameCallback(mNativeFrameCallback);
            } catch (RuntimeException error) {
                Logger.logWarn(mClient, LOG_TAG,
                    "VSync terminal publication unavailable; using immediate main-thread fallback: " +
                        error.getClass().getSimpleName());
                publishNativeScreenUpdateAtFrame();
            }
        }

        private void publishNativeScreenUpdateAtFrame() {
            mNativeFrameCallbackScheduled = false;
            long waitMicros = Math.max(0L,
                (System.nanoTime() - mNativeFrameScheduledNanos) / 1000L);
            if (waitMicros > mMaxNativeFrameWaitMicros) mMaxNativeFrameWaitMicros = waitMicros;
            mNativeScreenUpdateScheduled.set(false);
            mNativeScreenUpdatePublished.incrementAndGet();
            handleNativeScreenUpdate();

            long now = SystemClock.uptimeMillis();
            if (now - mLastNativeFrameLogMs >= 3000L) {
                mLastNativeFrameLogMs = now;
                Logger.logInfo(mClient, LOG_TAG, "frame-notify-v4 session=" + mHandle +
                    " requests=" + mNativeScreenUpdateRequests.get() + " coalesced=" +
                    mNativeScreenUpdateCoalesced.get() + " published=" +
                    mNativeScreenUpdatePublished.get() + " waitUs=" + waitMicros +
                    " maxWaitUs=" + mMaxNativeFrameWaitMicros + " authority=" +
                    (mEmulator != null && mEmulator.isGhosttyParserAuthorityActive() ?
                        "ghostty" : "java-fallback"));
            }
        }

        private void processPendingJavaInput(boolean drainAll) {
            int totalBytesRead = 0;
            boolean appended = false;
            boolean ghosttyAuthorityBefore = mEmulator.isGhosttyParserAuthorityActive();
            TerminalBuffer screenBefore = ghosttyAuthorityBefore ? null : mEmulator.getScreen();
            int cursorRowBefore = mEmulator.getCursorRow();
            int cursorColBefore = mEmulator.getCursorCol();
            int cursorStyleBefore = mEmulator.getCursorStyle();
            boolean cursorVisibleBefore = mEmulator.shouldCursorBeVisible();

            while (true) {
                int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
                if (bytesRead <= 0) break;

                Trace.beginSection("TermuxJavaFallbackPtyParse");
                try {
                    mEmulator.append(mReceiveBuffer, bytesRead);
                } finally {
                    Trace.endSection();
                }
                mPtyParserMainThreadCalls.incrementAndGet();
                totalBytesRead += bytesRead;
                appended = true;

                if (!drainAll && totalBytesRead >= PROCESS_INPUT_MAX_BYTES_PER_SLICE) {
                    scheduleJavaInput();
                    break;
                }
            }

            if (!appended) return;

            if (mEmulator.isSynchronizedOutputActive()) {
                scheduleSynchronizedOutputTimeout();
            } else {
                cancelSynchronizedOutputTimeout();
                boolean ghosttyAuthorityAfter = mEmulator.isGhosttyParserAuthorityActive();
                if (ghosttyAuthorityBefore || ghosttyAuthorityAfter) {
                    notifyScreenUpdate();
                    return;
                }
                TerminalBuffer screenAfter = mEmulator.getScreen();
                boolean cursorVisibleAfter = mEmulator.shouldCursorBeVisible();
                boolean cursorChanged = cursorVisibleBefore != cursorVisibleAfter ||
                    (cursorVisibleAfter && (cursorRowBefore != mEmulator.getCursorRow() ||
                        cursorColBefore != mEmulator.getCursorCol() ||
                        cursorStyleBefore != mEmulator.getCursorStyle()));
                boolean screenChanged = screenBefore != screenAfter || screenAfter.hasDirtyRows() ||
                    mEmulator.isFullRedrawRequired() || mEmulator.getScrollCounter() != 0 || cursorChanged;
                if (screenChanged) notifyScreenUpdate();
            }
        }

        private void handleNativeScreenUpdate() {
            if (mEmulator == null) return;
            if (mEmulator.isSynchronizedOutputActive()) {
                scheduleSynchronizedOutputTimeout();
            } else {
                cancelSynchronizedOutputTimeout();
                notifyScreenUpdate();
            }
        }

        private void scheduleSynchronizedOutputTimeout() {
            if (mSynchronizedOutputTimeoutScheduled) return;
            mSynchronizedOutputTimeoutScheduled = true;
            sendEmptyMessageDelayed(MSG_SYNCHRONIZED_OUTPUT_TIMEOUT, SYNCHRONIZED_OUTPUT_TIMEOUT_MS);
        }

        private void cancelSynchronizedOutputTimeout() {
            if (!mSynchronizedOutputTimeoutScheduled) return;
            removeMessages(MSG_SYNCHRONIZED_OUTPUT_TIMEOUT);
            mSynchronizedOutputTimeoutScheduled = false;
        }

        private void handleSynchronizedOutputTimeout() {
            mSynchronizedOutputTimeoutScheduled = false;
            if (mEmulator == null || !mEmulator.isSynchronizedOutputActive()) return;

            // A crashed or blocked child must not leave the terminal permanently frozen.
            mEmulator.forceFinishSynchronizedOutput();
            if (mEmulator.isGhosttyParserAuthorityActive() ||
                mEmulator.getScreen().hasDirtyRows() || mEmulator.isFullRedrawRequired() ||
                mEmulator.getScrollCounter() != 0) {
                notifyScreenUpdate();
            }
        }

        private void handleProcessExited(int exitCode) {
            cancelSynchronizedOutputTimeout();
            if (mEmulator != null) mEmulator.forceFinishSynchronizedOutput();
            cleanupResources(exitCode);
            notifyScreenUpdate();

            try {
                mClient.onSessionFinished(TerminalSession.this);
            } finally {
                // A service removal may have preceded SIGKILL/PTY EOF. Release only after the
                // final screen notification and session-finished callback have observed the tail.
                releaseDisposedEmulatorIfStopped();
            }
        }

    }

}
