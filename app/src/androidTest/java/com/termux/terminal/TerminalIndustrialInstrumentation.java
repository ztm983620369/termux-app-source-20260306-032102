package com.termux.terminal;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.FrameMetrics;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminalsessionsurface.TerminalSessionSurfaceView;
import com.termux.view.GhosttyViewportRenderProbe;
import com.termux.view.TerminalView;
import com.termux.view.TerminalVulkanOrientationProbe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ADB entry point for the real Termux terminal stress gate. This class deliberately
 * does not use the Shadow platform or its lifecycle. It starts an ordinary Termux
 * Activity, creates terminal sessions through TermuxService, and drives the actual
 * terminal page, parser, renderer, and touch handlers.
 */
public final class TerminalIndustrialInstrumentation extends Instrumentation {

    private static final String TAG = "TermuxIndustrialProbe";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    private static final long ACTIVITY_TIMEOUT_MS = 45_000L;
    private static final long SELECTION_TIMEOUT_MS = 3_000L;
    private static final long DEFAULT_TIMEOUT_MS = 240_000L;

    private Bundle arguments = new Bundle();

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : arguments;
        start();
    }

    @Override
    public void onStart() {
        RunResult run = runIndustrial(this, arguments);
        Bundle result = new Bundle();
        Log.i(TAG, run.summary);
        result.putString("stream", run.summary + "\n");
        result.putString("report_path", run.appReportPath);
        if (run.failureStack != null) result.putString("stack", run.failureStack);
        finish(run.success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    /**
     * Allows the repository's existing instrumentation declaration to dispatch this terminal-only
     * action without initializing its unrelated platform path.
     */
    public static RunResult runIndustrial(Instrumentation instrumentation, Bundle arguments) {
        ProbeRun run = new ProbeRun(instrumentation, arguments == null ? new Bundle() : arguments);
        boolean success = true;
        try {
            run.execute();
            success = run.errors.isEmpty();
        } catch (Throwable throwable) {
            run.errors.add(rootMessage(throwable));
            run.failureStack = Log.getStackTraceString(throwable);
            Log.e(TAG, "industrial probe failed", throwable);
            success = false;
        } finally {
            try {
                run.finish();
            } catch (Throwable throwable) {
                run.errors.add("report write failed: " + rootMessage(throwable));
                run.failureStack = Log.getStackTraceString(throwable);
                Log.e(TAG, "industrial report write failed", throwable);
                success = false;
            }
        }
        if (!run.errors.isEmpty()) success = false;
        return new RunResult(success, run.summaryLine(), run.appReportPath, run.failureStack);
    }

    public static final class RunResult {
        public final boolean success;
        public final String summary;
        public final String appReportPath;
        public final String failureStack;

        RunResult(boolean success, String summary, String appReportPath, String failureStack) {
            this.success = success;
            this.summary = summary;
            this.appReportPath = appReportPath;
            this.failureStack = failureStack;
        }
    }

    private static final class ProbeRun {
        final Instrumentation instrumentation;
        final Bundle args;
        final String runId;
        final String labPath;
        final String labReportPath;
        final String appReportPath;
        final int stress;
        final int frames;
        final int burstLines;
        final int baselineTextSize;
        final int imeCycles;
        final long timeoutMs;
        final ArrayList<String> errors = new ArrayList<>();
        final ArrayList<String> warnings = new ArrayList<>();
        final ArrayList<JSONObject> switchSamples = new ArrayList<>();
        final ArrayList<JSONObject> viewportSamples = new ArrayList<>();
        final ArrayList<JSONObject> backgroundSamples = new ArrayList<>();
        final ArrayList<JSONObject> pinchSamples = new ArrayList<>();
        final ArrayList<JSONObject> imeSamples = new ArrayList<>();
        final FrameCollector frameCollector = new FrameCollector();
        final long startedElapsedMs = SystemClock.elapsedRealtime();
        final String startedAt = now();

        String nativeProbeEvidence;
        String viewportProbeEvidence;
        JSONObject vulkanEvidence;
        JSONObject vulkanOrientationEvidence;
        JSONObject cancelledPinchEvidence;
        String failureStack;
        TermuxActivity activity;
        TermuxService service;
        TermuxTerminalSessionActivityClient client;
        TerminalSession labSession;
        TerminalSession backgroundOne;
        TerminalSession backgroundTwo;
        JSONObject labReport;
        boolean labReportVisible;
        boolean inputSent;
        int pinchCycles;
        int userTextSizeBeforeRun;
        int pinchRestoreTextSize;
        boolean deterministicTextBaselineApplied;
        int completedSelections;
        int incompleteSelections;
        boolean testWindowOverrideApplied;
        boolean activityWindowReady;

        ProbeRun(Instrumentation instrumentation, Bundle args) {
            this.instrumentation = instrumentation;
            this.args = args;
            this.runId = safeRunId(args.getString("run_id", "industrial-" + SystemClock.elapsedRealtime()));
            this.labPath = args.getString("lab_path", TERMUX_PREFIX + "/bin/termux-tui-lab");
            this.labReportPath = args.getString("report_path",
                TERMUX_HOME + "/termux-tui-lab/reports/termux-industrial-" + runId + ".json");
            this.appReportPath = args.getString("app_report_path", labReportPath + ".app.json");
            this.stress = boundedInt(args, "stress", 8, 1, 32);
            this.frames = boundedInt(args, "frames", 900, 64, 2400);
            this.burstLines = boundedInt(args, "burst_lines", 50_000, 1_000, 200_000);
            this.baselineTextSize = boundedInt(args, "baseline_text_size", 42, 8, 256);
            this.imeCycles = boundedInt(args, "ime_cycles", 0, 0, 20);
            this.timeoutMs = boundedLong(args, "timeout_ms", DEFAULT_TIMEOUT_MS, 30_000L, 600_000L);
        }

        private <T> T onMain(MainCallable<T> callable) throws Exception {
            return TerminalIndustrialInstrumentation.onMain(instrumentation, callable);
        }

        private <T> T awaitValue(String name, long timeout, MainCallable<T> callable) throws Exception {
            return TerminalIndustrialInstrumentation.awaitValue(instrumentation, name, timeout, callable);
        }

        void execute() throws Exception {
            verifyLabBinary();
            Log.i(TAG, "phase=native-baseline run=" + runId);
            runNativeBaseline();
            Log.i(TAG, "phase=activity-start run=" + runId);
            activity = startTermuxActivity();
            service = awaitValue("TermuxService", ACTIVITY_TIMEOUT_MS, new MainCallable<TermuxService>() {
                @Override public TermuxService call() {
                    return activity.getTermuxService();
                }
            });
            client = awaitValue("Termux terminal session client", ACTIVITY_TIMEOUT_MS,
                new MainCallable<TermuxTerminalSessionActivityClient>() {
                    @Override public TermuxTerminalSessionActivityClient call() {
                        return activity.getTermuxTerminalSessionClient();
                    }
                });
            applyDeterministicTextBaseline();

            Log.i(TAG, "phase=session-create run=" + runId);
            frameCollector.start(activity.getWindow());
            frameCollector.beginWindow("session-setup");
            backgroundOne = createBackgroundSession("industrial-bg-a-" + runId);
            requireSelection(backgroundOne, "initialize-background-a");
            awaitSessionRunning(backgroundOne, "background-a");
            backgroundTwo = createBackgroundSession("industrial-bg-b-" + runId);
            requireSelection(backgroundTwo, "initialize-background-b");
            awaitSessionRunning(backgroundTwo, "background-b");
            labSession = createLabSession();
            captureBackgroundProgress("created");
            requireSelection(labSession, "initial-lab");
            frameCollector.beginWindow("steady-workload");
            Log.i(TAG, "phase=workload-drive run=" + runId);
            driveUntilComplete();
            captureBackgroundProgress("workload-complete");
            requireSelection(labSession, "final-lab");
            captureViewport("final");
            captureBackgroundProgress("final");
            frameCollector.endWindow();
            verifyVulkanRenderer();
            verifyLabReport();
            verifyBackgroundProgress();
        }

        void finish() throws Exception {
            frameCollector.endWindow();
            frameCollector.stop(activity == null ? null : activity.getWindow());
            if (backgroundOne != null) backgroundOne.finishIfRunning();
            if (backgroundTwo != null) backgroundTwo.finishIfRunning();
            try {
                writeJsonAtomically(new File(appReportPath), buildReport());
            } finally {
                // The benchmark owns this tab. Leaving its interactive shell alive makes every
                // later run progressively heavier and invalidates cross-build comparisons.
                if (labSession != null) labSession.finishIfRunning();
                try {
                    restoreUserTextSize();
                } finally {
                    releaseTestWindowOverride();
                }
            }
        }

        private void applyDeterministicTextBaseline() throws Exception {
            onMain(new MainCallable<Object>() {
                @Override public Object call() {
                    TerminalView view = activity.getTerminalView();
                    if (view == null) {
                        throw new IllegalStateException(
                            "TerminalView unavailable while applying deterministic font baseline");
                    }
                    userTextSizeBeforeRun = view.getTextSizeForDiagnostics();
                    view.setTextSize(baselineTextSize);
                    activity.getPreferences().setFontSize(baselineTextSize);
                    activity.applyTerminalSessionSurfaceSettings();
                    deterministicTextBaselineApplied = true;
                    return null;
                }
            });
            instrumentation.waitForIdleSync();
        }

        private void restoreUserTextSize() throws Exception {
            if (!deterministicTextBaselineApplied || activity == null ||
                userTextSizeBeforeRun <= 0) return;
            onMain(new MainCallable<Object>() {
                @Override public Object call() {
                    TerminalView view = activity.getTerminalView();
                    if (view != null) view.setTextSize(userTextSizeBeforeRun);
                    activity.getPreferences().setFontSize(userTextSizeBeforeRun);
                    activity.applyTerminalSessionSurfaceSettings();
                    deterministicTextBaselineApplied = false;
                    return null;
                }
            });
            instrumentation.waitForIdleSync();
        }

        private void verifyLabBinary() {
            File file = new File(labPath);
            if (!file.isFile() || !file.canExecute()) {
                throw new IllegalStateException("Termux lab binary is unavailable or not executable: " + labPath);
            }
        }

        private void runNativeBaseline() {
            try {
                // The full synthetic parser benchmark can run for minutes on some devices and
                // delays the actual PTY workload. This verifies the production native batch
                // contract; the real terminal tab below supplies the parser saturation load.
                nativeProbeEvidence = TerminalNativeDeviceProbe.verifyRenderBatchPacketsForDiagnostics();
            } catch (Throwable throwable) {
                errors.add("native parser baseline: " + rootMessage(throwable));
            }
            try {
                viewportProbeEvidence = GhosttyViewportRenderProbe.run();
            } catch (Throwable throwable) {
                errors.add("retained viewport baseline: " + rootMessage(throwable));
            }
        }

        private TermuxActivity startTermuxActivity() throws Exception {
            final Application targetApplication =
                (Application) instrumentation.getTargetContext().getApplicationContext();
            final Application.ActivityLifecycleCallbacks windowCallbacks =
                new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityPreCreated(Activity candidate,
                                                               Bundle savedInstanceState) {
                        applyTestWindowOverride(candidate);
                    }
                    @Override public void onActivityCreated(Activity candidate, Bundle state) {}
                    @Override public void onActivityStarted(Activity candidate) {}
                    @Override public void onActivityResumed(Activity candidate) {}
                    @Override public void onActivityPaused(Activity candidate) {}
                    @Override public void onActivityStopped(Activity candidate) {}
                    @Override public void onActivitySaveInstanceState(Activity candidate,
                                                                      Bundle state) {}
                    @Override public void onActivityDestroyed(Activity candidate) {}
                };
            onMain(new MainCallable<Object>() {
                @Override public Object call() {
                    targetApplication.registerActivityLifecycleCallbacks(windowCallbacks);
                    return null;
                }
            });
            Intent intent = new Intent(instrumentation.getTargetContext(), TermuxActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            final Activity launched;
            try {
                launched = instrumentation.startActivitySync(intent);
            } finally {
                onMain(new MainCallable<Object>() {
                    @Override public Object call() {
                        targetApplication.unregisterActivityLifecycleCallbacks(windowCallbacks);
                        return null;
                    }
                });
            }
            if (!(launched instanceof TermuxActivity)) {
                throw new IllegalStateException("TermuxActivity did not launch: " + launched);
            }
            final TermuxActivity termuxActivity = (TermuxActivity) launched;
            activity = termuxActivity;
            onMain(new MainCallable<Object>() {
                @Override public Object call() {
                    applyTestWindowOverride(termuxActivity);
                    return null;
                }
            });
            instrumentation.waitForIdleSync();
            awaitValue("interactive Termux window", ACTIVITY_TIMEOUT_MS,
                new MainCallable<Boolean>() {
                    @Override public Boolean call() {
                        View decor = termuxActivity.getWindow().getDecorView();
                        if (!decor.isAttachedToWindow() || decor.getWidth() <= 0 ||
                            decor.getHeight() <= 0 ||
                            decor.getWindowVisibility() != View.VISIBLE ||
                            !termuxActivity.hasWindowFocus()) {
                            return null;
                        }
                        activityWindowReady = true;
                        Log.i(TAG, "phase=activity-window-ready run=" + runId + " decor=" +
                            decor.getWidth() + 'x' + decor.getHeight() + " focus=true");
                        return Boolean.TRUE;
                    }
                });
            return termuxActivity;
        }

        private void applyTestWindowOverride(Activity candidate) {
            if (!(candidate instanceof TermuxActivity)) return;
            // Device labs routinely run while a secure keyguard is showing. Applying these before
            // Activity.onCreate lets WindowManager resume a real composited target over keyguard.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                candidate.setShowWhenLocked(true);
                candidate.setTurnScreenOn(true);
            }
            candidate.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            testWindowOverrideApplied = true;
        }

        private void releaseTestWindowOverride() throws Exception {
            if (!testWindowOverrideApplied || activity == null) return;
            onMain(new MainCallable<Object>() {
                @Override public Object call() {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        activity.setShowWhenLocked(false);
                        activity.setTurnScreenOn(false);
                    }
                    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    testWindowOverrideApplied = false;
                    return null;
                }
            });
        }

        private TerminalSession createBackgroundSession(final String name) throws Exception {
            final String shell = TERMUX_PREFIX + "/bin/bash";
            final String command = "i=0; while :; do " +
                "printf '\\033[38;5;%smINDUSTRIAL_BG " + name + " seq=%06d\\033[0m\\r\\n' " +
                "\"$((16 + i % 216))\" \"$i\"; i=$((i + 1)); sleep 0.025; done";
            return createSession(shell, command, name);
        }

        private TerminalSession createLabSession() throws Exception {
            final String shell = TERMUX_PREFIX + "/bin/bash";
            final String command = "TERM=xterm-256color; export TERM; " +
                shellQuote(labPath) + " -mode industrial -run-id " + shellQuote(runId) +
                " -stress " + stress + " -frames " + frames + " -burst-lines " + burstLines +
                " -report " + shellQuote(labReportPath) +
                "; status=$?; printf '\\r\\nTERMUX_TUI_LAB_PROCESS_EXIT run=" + runId +
                " status=%s\\r\\n' \"$status\"; exec " + shellQuote(shell) + " -i";
            return createSession(shell, command, "industrial-" + runId);
        }

        private TerminalSession createSession(final String shell, final String command, final String name) throws Exception {
            return onMain(new MainCallable<TerminalSession>() {
                @Override public TerminalSession call() {
                    TermuxSession created = service.createTermuxSession(shell,
                        new String[] {"-lc", command}, null, TERMUX_HOME, false, name);
                    if (created == null) throw new IllegalStateException("TermuxService returned null for " + name);
                    return created.getTerminalSession();
                }
            });
        }

        private void awaitSessionRunning(final TerminalSession session, final String name) throws Exception {
            awaitValue(name, ACTIVITY_TIMEOUT_MS, new MainCallable<Boolean>() {
                @Override public Boolean call() {
                    return session != null && session.getEmulator() != null && session.isRunning()
                        ? Boolean.TRUE : null;
                }
            });
        }

        private void driveUntilComplete() throws Exception {
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            int cycle = 0;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (isLabReportReady()) return;
                String title = onMain(new MainCallable<String>() {
                    @Override public String call() {
                        return labSession == null ? null : labSession.getTitle();
                    }
                });
                if (!inputSent && title != null && title.contains(runId) && title.contains("input-ready")) {
                    requireSelection(labSession, "input-ready");
                    sendInputAndPinch();
                    inputSent = true;
                }

                if ((cycle % 3) == 0) {
                    selectAndRecord(backgroundOne, "background-a-" + cycle);
                    selectAndRecord(labSession, "return-lab-a-" + cycle);
                } else if ((cycle % 5) == 0) {
                    selectAndRecord(backgroundTwo, "background-b-" + cycle);
                    selectAndRecord(labSession, "return-lab-b-" + cycle);
                } else {
                    scrollCurrentLabViewport(cycle);
                }
                SystemClock.sleep(70L);
                cycle++;
            }
            throw new IllegalStateException("industrial lab timed out after " + timeoutMs + "ms");
        }

        private boolean isLabReportReady() {
            File file = new File(labReportPath);
            labReportVisible = file.isFile() && file.length() > 32L;
            return labReportVisible;
        }

        private void sendInputAndPinch() throws Exception {
            onMain(new MainCallable<Object>() {
                @Override public Object call() {
                    TerminalView view = activity.getTerminalView();
                    if (view == null || view.getCurrentSession() != labSession) {
                        throw new IllegalStateException("lab TerminalView is unavailable for input handshake");
                    }
                    pinchRestoreTextSize = view.getTextSizeForDiagnostics();
                    // Start from a middle value even when a previous run left the app at
                    // the 256px clamp, so both expansion and contraction can be observed.
                    view.setTextSize(64);
                    sendToken(view, "TUI-LAB-INPUT-" + runId);
                    return null;
                }
            });
            // Each direction starts from a known, direction-appropriate size. The setup reflow is
            // outside the gesture sample; the measured change must still come from TouchEvent.
            final int[] expandBaselines = new int[] {24, 32, 48, 64, 80};
            final int[] shrinkBaselines = new int[] {240, 208, 176, 144, 112};
            final int[] viewportPermille = new int[] {0, 100, 500, 900, 1000};
            final String[] viewportNames = new String[] {
                "bottom", "near-bottom", "middle", "near-oldest", "oldest"
            };
            for (int cycle = 0; cycle < viewportPermille.length; cycle++) {
                final int expandStart = preparePinchBaseline(expandBaselines[cycle],
                    "expand-" + cycle, viewportPermille[cycle]);
                onMain(new MainCallable<Object>() {
                    @Override public Object call() {
                        TerminalView view = activity.getTerminalView();
                        if (view == null || view.getCurrentSession() != labSession) {
                            throw new IllegalStateException("lab TerminalView disappeared during pinch");
                        }
                        dispatchPinch(view, true);
                        return null;
                    }
                });
                instrumentation.waitForIdleSync();
                capturePinchSample("expand", cycle, expandStart,
                    viewportNames[cycle], viewportPermille[cycle]);

                final int shrinkStart = preparePinchBaseline(shrinkBaselines[cycle],
                    "shrink-" + cycle, viewportPermille[cycle]);
                onMain(new MainCallable<Object>() {
                    @Override public Object call() {
                        TerminalView view = activity.getTerminalView();
                        if (view == null || view.getCurrentSession() != labSession) {
                            throw new IllegalStateException("lab TerminalView disappeared during pinch");
                        }
                        dispatchPinch(view, false);
                        return null;
                    }
                });
                instrumentation.waitForIdleSync();
                capturePinchSample("shrink", cycle, shrinkStart,
                    viewportNames[cycle], viewportPermille[cycle]);
                pinchCycles += 2;
            }
            verifyCancelledPinchDoesNotPersist();
            onMain(new MainCallable<Object>() {
                @Override public Object call() {
                    TerminalView view = activity.getTerminalView();
                    if (view != null && pinchRestoreTextSize > 0) {
                        view.setTextSize(pinchRestoreTextSize);
                        activity.getPreferences().setFontSize(pinchRestoreTextSize);
                        activity.applyTerminalSessionSurfaceSettings();
                    }
                    return null;
                }
            });
            instrumentation.waitForIdleSync();
            captureViewport("input-pinch");
            if (imeCycles > 0) verifyImeViewportDoesNotResizePty();
        }

        private void verifyImeViewportDoesNotResizePty() throws Exception {
            awaitValue("stable IME geometry baseline", SELECTION_TIMEOUT_MS,
                new MainCallable<Boolean>() {
                    @Override public Boolean call() {
                        TerminalView view = activity.getTerminalView();
                        return view != null && view.getCurrentSession() == labSession &&
                            view.hasCurrentTerminalGeometryForDiagnostics() &&
                            view.hasCompleteRenderFrame() ? Boolean.TRUE : null;
                    }
                });
            SystemClock.sleep(80L);
            instrumentation.waitForIdleSync();
            final long baselineResizeTransactions =
                labSession.getResizeTransactionsForDiagnostics();
            final long baselinePtyIoctls =
                labSession.getPtyWindowSizeRequestsForDiagnostics();
            final long baselineViewCommits = onMain(new MainCallable<Long>() {
                @Override public Long call() {
                    TerminalView view = activity.getTerminalView();
                    if (view == null || view.getCurrentSession() != labSession) {
                        throw new IllegalStateException("lab TerminalView missing before IME gate");
                    }
                    return view.getPtyGeometryCommitCountForDiagnostics();
                }
            });
            final ImeViewportBaseline viewportBaseline = onMain(new MainCallable<ImeViewportBaseline>() {
                @Override public ImeViewportBaseline call() {
                    TerminalView view = activity.getTerminalView();
                    TerminalSessionSurfaceView surface =
                        activity.findViewById(R.id.terminal_session_surface);
                    if (view == null || surface == null) {
                        throw new IllegalStateException("terminal viewport missing before IME gate");
                    }
                    int[] terminalLocation = new int[2];
                    int[] surfaceLocation = new int[2];
                    int[] bottomNavigationLocation = new int[2];
                    view.getLocationInWindow(terminalLocation);
                    surface.getLocationInWindow(surfaceLocation);
                    View bottomNavigation = activity.findViewById(R.id.bottom_navigation);
                    if (bottomNavigation == null || bottomNavigation.getVisibility() != View.VISIBLE ||
                        bottomNavigation.getHeight() <= 0) {
                        throw new IllegalStateException(
                            "bottom navigation missing before IME gate");
                    }
                    bottomNavigation.getLocationInWindow(bottomNavigationLocation);
                    return new ImeViewportBaseline(terminalLocation[1], surfaceLocation[1],
                        bottomNavigationLocation[1],
                        bottomNavigationLocation[1] + bottomNavigation.getHeight());
                }
            });
            final int baselineColumns = labSession.getEmulator().mColumns;
            final int baselineRows = labSession.getEmulator().mRows;
            final TermuxTerminalViewClient terminalViewClient =
                activity.getTermuxTerminalViewClient();
            if (terminalViewClient == null) {
                throw new IllegalStateException("terminal view client missing before IME gate");
            }

            final boolean canExerciseInputFocusRestore = onMain(new MainCallable<Boolean>() {
                @Override public Boolean call() {
                    TerminalView view = activity.getTerminalView();
                    TerminalEmulator emulator = labSession.getEmulator();
                    if (view == null || emulator == null || view.getCurrentSession() != labSession) {
                        throw new IllegalStateException("terminal ownership changed before input-focus gate");
                    }
                    return emulator.getActiveTranscriptRows() > 0;
                }
            });

            try {
                for (int cycle = 0; cycle < imeCycles; cycle++) {
                    if (cycle == 0 && canExerciseInputFocusRestore) {
                        onMain(new MainCallable<Object>() {
                            @Override public Object call() {
                                TerminalView view = activity.getTerminalView();
                                TerminalEmulator emulator = labSession.getEmulator();
                                if (view == null || emulator == null ||
                                    view.getCurrentSession() != labSession) {
                                    throw new IllegalStateException(
                                        "terminal ownership changed before history-focus exercise");
                                }
                                int historyRow = -Math.min(5,
                                    emulator.getActiveTranscriptRows());
                                view.setTopRow(historyRow);
                                if (view.getTopRow() == 0) {
                                    throw new IllegalStateException(
                                        "failed to establish history viewport before input focus");
                                }
                                return null;
                            }
                        });
                    }
                    onMain(new MainCallable<Object>() {
                        @Override public Object call() {
                            terminalViewClient.showSoftKeyboardForTerminal();
                            TerminalView view = activity.getTerminalView();
                            if (view == null || view.getCurrentSession() != labSession) {
                                throw new IllegalStateException(
                                    "terminal ownership changed during input-focus restore");
                            }
                            if (view.getTopRow() != 0 ||
                                Math.abs(view.getViewportPixelOffset()) > 0.01f) {
                                throw new IllegalStateException(
                                    "explicit input focus did not restore the live viewport: " +
                                        view.getRenderDiagnostics());
                            }
                            return null;
                        }
                    });
                    awaitImeState(true, "show-" + cycle);
                    imeSamples.add(captureImeInvariant("shown", cycle, baselineColumns,
                        baselineRows, baselineResizeTransactions, baselinePtyIoctls,
                        baselineViewCommits, viewportBaseline));

                    onMain(new MainCallable<Object>() {
                        @Override public Object call() {
                            terminalViewClient.hideSoftKeyboardForTerminal();
                            return null;
                        }
                    });
                    awaitImeState(false, "hide-" + cycle);
                    imeSamples.add(captureImeInvariant("hidden", cycle, baselineColumns,
                        baselineRows, baselineResizeTransactions, baselinePtyIoctls,
                        baselineViewCommits, viewportBaseline));
                }
            } finally {
                onMain(new MainCallable<Object>() {
                    @Override public Object call() {
                        terminalViewClient.hideSoftKeyboardForTerminal();
                        return null;
                    }
                });
            }
        }

        private void awaitImeState(final boolean visible, String label) throws Exception {
            awaitValue("IME " + label, 15_000L, new MainCallable<Boolean>() {
                @Override public Boolean call() {
                    TermuxActivityRootView root = activity.getTermuxActivityRootView();
                    if (root == null || root.isImeAnimationRunning()) return null;
                    boolean actualVisible = root.getLastImeBottomInset() > 0;
                    return actualVisible == visible ? Boolean.TRUE : null;
                }
            });
            instrumentation.waitForIdleSync();
        }

        private JSONObject captureImeInvariant(String phase, int cycle, int baselineColumns,
                                               int baselineRows,
                                               long baselineResizeTransactions,
                                               long baselinePtyIoctls,
                                               long baselineViewCommits,
                                               ImeViewportBaseline viewportBaseline) throws Exception {
            JSONObject sample = onMain(new MainCallable<JSONObject>() {
                @Override public JSONObject call() throws Exception {
                    TerminalView view = activity.getTerminalView();
                    TerminalEmulator emulator = labSession.getEmulator();
                    TermuxActivityRootView root = activity.getTermuxActivityRootView();
                    TerminalSessionSurfaceView surface =
                        activity.findViewById(R.id.terminal_session_surface);
                    if (view == null || emulator == null || root == null ||
                        surface == null || view.getCurrentSession() != labSession) {
                        throw new IllegalStateException("terminal ownership changed during IME gate");
                    }
                    long resizeTransactions = labSession.getResizeTransactionsForDiagnostics();
                    long ptyIoctls = labSession.getPtyWindowSizeRequestsForDiagnostics();
                    long viewCommits = view.getPtyGeometryCommitCountForDiagnostics();
                    if (emulator.mColumns != baselineColumns || emulator.mRows != baselineRows ||
                        resizeTransactions != baselineResizeTransactions ||
                        ptyIoctls != baselinePtyIoctls || viewCommits != baselineViewCommits) {
                        throw new IllegalStateException("IME changed terminal geometry: " +
                            view.getRenderDiagnostics());
                    }
                    int[] surfaceLocation = new int[2];
                    int[] terminalLocation = new int[2];
                    int[] windowLocation = new int[2];
                    surface.getLocationInWindow(surfaceLocation);
                    view.getLocationInWindow(terminalLocation);
                    View windowRoot = surface.getRootView();
                    windowRoot.getLocationInWindow(windowLocation);
                    int windowBottom = windowLocation[1] + windowRoot.getHeight();
                    int imeTop = windowBottom - root.getLastImeBottomInset();
                    int presentedSurfaceBottom = surfaceLocation[1] + surface.getHeight();
                    int softInputAdjust = activity.getWindow().getAttributes().softInputMode &
                        WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
                    if (softInputAdjust != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) {
                        throw new IllegalStateException("terminal window left ADJUST_NOTHING: mode=" +
                            activity.getWindow().getAttributes().softInputMode);
                    }
                    if (root.getLastImeBottomInset() > 0 &&
                        Math.abs(surface.getTranslationY()) > 0.01f) {
                        throw new IllegalStateException("IME translated the terminal surface: " +
                            surface.getImeViewportDiagnostics());
                    }
                    float terminalTranslation = view.getTranslationY();
                    float untransformedTerminalTop = terminalLocation[1] - terminalTranslation;
                    TerminalView.ImeCameraSnapshot imeCameraSnapshot =
                        view.getImeCameraSnapshot();
                    if (Math.abs(untransformedTerminalTop -
                        viewportBaseline.terminalTopInWindow) > 1.01f) {
                        throw new IllegalStateException("IME changed terminal layout origin: baseline=" +
                            viewportBaseline.terminalTopInWindow + " untransformed=" +
                            untransformedTerminalTop + " " + surface.getImeViewportDiagnostics());
                    }
                    int chromeBoundary = root.getTerminalChromeBoundaryInWindow();
                    View bottomNavigation = activity.findViewById(R.id.bottom_navigation);
                    if (bottomNavigation == null ||
                        bottomNavigation.getVisibility() != View.VISIBLE ||
                        bottomNavigation.getHeight() <= 0) {
                        throw new IllegalStateException(
                            "bottom navigation presentation changed during IME gate");
                    }
                    int[] navigationLocation = new int[2];
                    bottomNavigation.getLocationInWindow(navigationLocation);
                    int navigationBottom = navigationLocation[1] + bottomNavigation.getHeight();
                    if (Math.abs(bottomNavigation.getTranslationY()) > 0.01f ||
                        Math.abs(navigationLocation[1] - viewportBaseline.bottomNavigationTopInWindow) > 1 ||
                        Math.abs(navigationBottom - viewportBaseline.bottomNavigationBottomInWindow) > 1) {
                        throw new IllegalStateException("IME moved bottom navigation: baseline=" +
                            viewportBaseline.bottomNavigationTopInWindow + ".." +
                            viewportBaseline.bottomNavigationBottomInWindow + " actual=" +
                            navigationLocation[1] + ".." + navigationBottom + " translation=" +
                            bottomNavigation.getTranslationY());
                    }
                    if (Math.abs(chromeBoundary - imeTop) > 1) {
                        throw new IllegalStateException("terminal chrome did not use the real IME top: boundary=" +
                            chromeBoundary + " imeTop=" + imeTop);
                    }
                    if (root.getLastImeBottomInset() > 0 && navigationBottom <= imeTop) {
                        throw new IllegalStateException("bottom navigation escaped IME occlusion: bottom=" +
                            navigationBottom + " imeTop=" + imeTop);
                    }
                    ExtraKeysView extraKeys = surface.getExtraKeysView();
                    int terminalBoundary = chromeBoundary;
                    if (root.getLastImeBottomInset() > 0 && extraKeys != null &&
                        extraKeys.getVisibility() == View.VISIBLE && extraKeys.getHeight() > 0) {
                        int[] extraKeysLocation = new int[2];
                        extraKeys.getLocationInWindow(extraKeysLocation);
                        int extraKeysBottom = extraKeysLocation[1] + extraKeys.getHeight();
                        terminalBoundary = extraKeysLocation[1];
                        if (Math.abs(extraKeysBottom - chromeBoundary) > 1) {
                            throw new IllegalStateException("extra keys IME boundary mismatch: bottom=" +
                                extraKeysBottom + " boundary=" + chromeBoundary + " " +
                                surface.getImeViewportDiagnostics());
                        }
                    } else if (root.getLastImeBottomInset() > 0 &&
                        surface.getToolbarPager() != null &&
                        surface.getToolbarPager().getVisibility() == View.VISIBLE &&
                        surface.getToolbarPager().getHeight() > 0) {
                        int[] toolbarLocation = new int[2];
                        surface.getToolbarPager().getLocationInWindow(toolbarLocation);
                        terminalBoundary = toolbarLocation[1];
                    }
                    if (root.getLastImeBottomInset() > 0) {
                        if (!surface.isImeTerminalPixelTransformSynchronizedForDiagnostics()) {
                            throw new IllegalStateException(
                                "Canvas/Vulkan IME camera transforms diverged: " +
                                    surface.getImeViewportDiagnostics());
                        }
                        if (imeCameraSnapshot.availability ==
                            TerminalView.ImeCameraSnapshot.Availability.READY) {
                            int focusTarget =
                                surface.getImeFocusTargetBottomInWindowForDiagnostics();
                            if (focusTarget < 0) {
                                throw new IllegalStateException(
                                    "ready cursor has no stable IME focus target: " +
                                        surface.getImeViewportDiagnostics());
                            }
                            int expectedTranslation = Math.max(-view.getHeight(), Math.min(0,
                                Math.round(focusTarget - untransformedTerminalTop -
                                    imeCameraSnapshot.protectedBottomPx)));
                            if (Math.abs(terminalTranslation - expectedTranslation) > 1.01f) {
                                throw new IllegalStateException(
                                    "terminal semantic camera mismatch: actual=" +
                                        terminalTranslation + " expected=" + expectedTranslation +
                                        " cursorBottom=" + imeCameraSnapshot.cursorBottomPx +
                                        " protectedBottom=" +
                                            imeCameraSnapshot.protectedBottomPx +
                                        " target=" + focusTarget + " boundary=" + terminalBoundary +
                                        " " + surface.getImeViewportDiagnostics());
                            }
                            float presentedCursorTop = untransformedTerminalTop +
                                imeCameraSnapshot.cursorTopPx + terminalTranslation;
                            float presentedCursorBottom = untransformedTerminalTop +
                                imeCameraSnapshot.cursorBottomPx + terminalTranslation;
                            float presentedProtectedBottom = untransformedTerminalTop +
                                imeCameraSnapshot.protectedBottomPx + terminalTranslation;
                            if (presentedCursorTop < untransformedTerminalTop - 1.01f ||
                                presentedCursorBottom > terminalBoundary + 1.01f ||
                                presentedProtectedBottom > terminalBoundary + 1.01f) {
                                throw new IllegalStateException(
                                    "terminal semantic camera left the usable viewport: top=" +
                                        presentedCursorTop + " bottom=" + presentedCursorBottom +
                                        " protectedBottom=" + presentedProtectedBottom +
                                        " boundary=" + terminalBoundary + " " +
                                        surface.getImeViewportDiagnostics());
                            }
                        } else if (imeCameraSnapshot.availability ==
                            TerminalView.ImeCameraSnapshot.Availability.HISTORY_OWNED) {
                            if (Math.abs(terminalTranslation) > 1.01f) {
                                throw new IllegalStateException(
                                    "history viewport did not own natural presentation: " +
                                        surface.getImeViewportDiagnostics());
                            }
                        } else if (terminalTranslation > 1.01f ||
                            terminalTranslation < -view.getHeight() - 1.01f) {
                            throw new IllegalStateException(
                                "pending frame did not hold a bounded camera transform: " +
                                    surface.getImeViewportDiagnostics());
                        }
                    }
                    if (root.getLastImeBottomInset() == 0 &&
                        (Math.abs(surface.getTranslationY()) > 0.01f ||
                            Math.abs(terminalTranslation) > 0.01f)) {
                        throw new IllegalStateException("terminal IME translation was not restored: " +
                            surface.getImeViewportDiagnostics());
                    }
                    JSONObject value = new JSONObject();
                    value.put("phase", phase);
                    value.put("cycle", cycle);
                    value.put("ime_inset", root.getLastImeBottomInset());
                    value.put("columns", emulator.mColumns);
                    value.put("rows", emulator.mRows);
                    value.put("resize_transactions_delta",
                        resizeTransactions - baselineResizeTransactions);
                    value.put("pty_ioctl_delta", ptyIoctls - baselinePtyIoctls);
                    value.put("view_commit_delta", viewCommits - baselineViewCommits);
                    value.put("suppressed_layouts",
                        view.getImeGeometrySuppressedCountForDiagnostics());
                    value.put("complete", view.hasCompleteRenderFrame());
                    value.put("soft_input_adjust", softInputAdjust);
                    value.put("surface_top", surfaceLocation[1]);
                    value.put("terminal_top", terminalLocation[1]);
                    value.put("terminal_translation", terminalTranslation);
                    value.put("ime_camera_availability",
                        imeCameraSnapshot.availability.name());
                    value.put("ime_camera_content_revision",
                        imeCameraSnapshot.contentRevision);
                    value.put("ime_camera_presented_revision",
                        imeCameraSnapshot.presentedRevision);
                    value.put("ime_camera_protected_bottom",
                        imeCameraSnapshot.protectedBottomPx);
                    value.put("ime_camera_target",
                        surface.getImeFocusTargetBottomInWindowForDiagnostics());
                    value.put("surface_bottom", presentedSurfaceBottom);
                    value.put("ime_top", imeTop);
                    value.put("chrome_boundary", chromeBoundary);
                    value.put("surface", surface.getImeViewportDiagnostics());
                    value.put("diagnostics", view.getRenderDiagnostics());
                    return value;
                }
            });
            return sample;
        }

        private static final class ImeViewportBaseline {
            final int terminalTopInWindow;
            final int surfaceTopInWindow;
            final int bottomNavigationTopInWindow;
            final int bottomNavigationBottomInWindow;

            ImeViewportBaseline(int terminalTopInWindow, int surfaceTopInWindow,
                                int bottomNavigationTopInWindow,
                                int bottomNavigationBottomInWindow) {
                this.terminalTopInWindow = terminalTopInWindow;
                this.surfaceTopInWindow = surfaceTopInWindow;
                this.bottomNavigationTopInWindow = bottomNavigationTopInWindow;
                this.bottomNavigationBottomInWindow = bottomNavigationBottomInWindow;
            }
        }

        private void verifyCancelledPinchDoesNotPersist() throws Exception {
            final int baselineTextSize = 64;
            onMain(new MainCallable<Object>() {
                @Override public Object call() {
                    TerminalView view = activity.getTerminalView();
                    if (view == null || view.getCurrentSession() != labSession) {
                        throw new IllegalStateException(
                            "lab TerminalView disappeared before cancelled pinch");
                    }
                    view.setTextSize(baselineTextSize);
                    activity.getPreferences().setFontSize(baselineTextSize);
                    activity.applyTerminalSessionSurfaceSettings();
                    return null;
                }
            });
            awaitValue("cancelled pinch baseline", SELECTION_TIMEOUT_MS,
                new MainCallable<Boolean>() {
                    @Override public Boolean call() {
                        TerminalView view = activity.getTerminalView();
                        return view != null && view.getCurrentSession() == labSession &&
                            view.getTextSizeForDiagnostics() == baselineTextSize &&
                            view.hasCurrentTerminalGeometryForDiagnostics() &&
                            view.hasCompleteRenderFrame() ? Boolean.TRUE : null;
                    }
                });

            final PinchInProgress pinch = onMain(new MainCallable<PinchInProgress>() {
                @Override public PinchInProgress call() {
                    TerminalView view = activity.getTerminalView();
                    if (view == null || view.getCurrentSession() != labSession) {
                        throw new IllegalStateException(
                            "lab TerminalView disappeared during cancelled pinch");
                    }
                    return dispatchPinchUntilCancel(view, true);
                }
            });
            final int liveTextSize = awaitValue("live cancelled pinch reflow",
                SELECTION_TIMEOUT_MS, new MainCallable<Integer>() {
                    @Override public Integer call() {
                        TerminalView view = activity.getTerminalView();
                        if (view == null || view.getTextSizeForDiagnostics() == baselineTextSize ||
                            !view.hasCurrentTerminalGeometryForDiagnostics() ||
                            !view.hasCompleteRenderFrame()) return null;
                        return view.getTextSizeForDiagnostics();
                    }
                });
            int persistedDuringGesture = onMain(new MainCallable<Integer>() {
                @Override public Integer call() {
                    return activity.getPreferences().getFontSize();
                }
            });
            if (persistedDuringGesture != baselineTextSize) {
                throw new IllegalStateException("live pinch persisted before gesture end: " +
                    persistedDuringGesture);
            }

            onMain(new MainCallable<Object>() {
                @Override public Object call() {
                    TerminalView view = activity.getTerminalView();
                    if (view == null) {
                        throw new IllegalStateException(
                            "lab TerminalView disappeared before pinch cancellation");
                    }
                    dispatchPinchCancel(view, pinch);
                    return null;
                }
            });
            cancelledPinchEvidence = awaitValue("cancelled pinch rollback",
                SELECTION_TIMEOUT_MS, new MainCallable<JSONObject>() {
                    @Override public JSONObject call() throws Exception {
                        TerminalView view = activity.getTerminalView();
                        int persisted = activity.getPreferences().getFontSize();
                        if (view == null || view.getTextSizeForDiagnostics() != baselineTextSize ||
                            persisted != baselineTextSize ||
                            !view.hasCurrentTerminalGeometryForDiagnostics() ||
                            !view.hasCompleteRenderFrame()) return null;
                        JSONObject value = new JSONObject();
                        value.put("passed", true);
                        value.put("baseline_text_size", baselineTextSize);
                        value.put("live_text_size", liveTextSize);
                        value.put("restored_text_size", view.getTextSizeForDiagnostics());
                        value.put("persisted_text_size", persisted);
                        value.put("geometry_current", true);
                        value.put("complete", true);
                        value.put("diagnostics", view.getRenderDiagnostics());
                        return value;
                    }
                });
        }

        private int preparePinchBaseline(final int textSize, String label,
                                         final int viewportPermille) throws Exception {
            onMain(new MainCallable<Object>() {
                @Override public Object call() {
                    TerminalView view = activity.getTerminalView();
                    if (view == null || view.getCurrentSession() != labSession) {
                        throw new IllegalStateException("lab TerminalView disappeared before pinch baseline");
                    }
                    view.setTextSize(textSize);
                    return null;
                }
            });
            instrumentation.waitForIdleSync();
            final int actualTextSize = awaitValue("complete pinch baseline " + label,
                SELECTION_TIMEOUT_MS,
                new MainCallable<Integer>() {
                    @Override public Integer call() {
                        TerminalView view = activity.getTerminalView();
                        if (view == null || view.getCurrentSession() != labSession ||
                            !view.hasCompleteRenderFrame()) return null;
                        return view.getTextSizeForDiagnostics();
                    }
                });
            final int expectedTopRow = onMain(new MainCallable<Integer>() {
                @Override public Integer call() {
                    TerminalView view = activity.getTerminalView();
                    if (view == null || view.getCurrentSession() != labSession) {
                        throw new IllegalStateException(
                            "lab TerminalView disappeared before viewport positioning");
                    }
                    int transcriptRows = view.getTranscriptRowsForDiagnostics();
                    int requestedTopRow = -(int) Math.round(
                        transcriptRows * (viewportPermille / 1000.0));
                    view.setViewportPositionForDiagnostics(requestedTopRow, 0f);
                    return requestedTopRow;
                }
            });
            awaitValue("position pinch viewport " + label, SELECTION_TIMEOUT_MS,
                new MainCallable<Boolean>() {
                    @Override public Boolean call() {
                        TerminalView view = activity.getTerminalView();
                        return view != null && view.getCurrentSession() == labSession &&
                            view.hasCompleteRenderFrame() && view.getTopRow() == expectedTopRow
                            ? Boolean.TRUE : null;
                    }
                });
            return actualTextSize;
        }

        private void capturePinchSample(final String direction, final int cycle,
                                        final int startTextSize, final String viewportName,
                                        final int viewportPermille) throws Exception {
            JSONObject sample = awaitValue("complete pinch " + direction + '-' + cycle,
                SELECTION_TIMEOUT_MS, new MainCallable<JSONObject>() {
                @Override public JSONObject call() throws Exception {
                    JSONObject value = new JSONObject();
                    value.put("direction", direction);
                    value.put("cycle", cycle);
                    value.put("viewport_position", viewportName);
                    value.put("viewport_permille_from_bottom", viewportPermille);
                    value.put("start_text_size", startTextSize);
                    TerminalView view = activity.getTerminalView();
                    if (view == null) {
                        return null;
                    } else {
                        int endTextSize = view.getTextSizeForDiagnostics();
                        boolean directionValid = "expand".equals(direction)
                            ? endTextSize > startTextSize : endTextSize < startTextSize;
                        int anchorOutcome = view.getResizeAnchorOutcomeForDiagnostics();
                        float reportedFocusDrift =
                            view.getScaleReportedFocusDriftForDiagnostics();
                        String anchorStatus = view.getResizeAnchorStatusForDiagnostics();
                        boolean liveEdgePinned =
                            view.isScaleLiveEdgePinnedForDiagnostics();
                        boolean viewportPolicyValid;
                        if (liveEdgePinned) {
                            viewportPolicyValid = viewportPermille == 0 &&
                                view.getTopRow() == 0 &&
                                view.getScaleLiveEdgePinCountForDiagnostics() > 0;
                        } else {
                            viewportPolicyValid = anchorOutcome > 0 &&
                                view.isResizeAnchorCommitValidForDiagnostics() &&
                                anchorStatus.contains("precondition=true") &&
                                anchorStatus.contains("valid=true");
                        }
                        if (!directionValid || !view.hasCompleteRenderFrame() ||
                            !view.isScalePivotLockedForDiagnostics() ||
                            reportedFocusDrift < 8f || !viewportPolicyValid) {
                            return null;
                        }
                        value.put("available", true);
                        value.put("text_size", endTextSize);
                        value.put("delta_text_size", endTextSize - startTextSize);
                        value.put("direction_valid", true);
                        value.put("complete", true);
                        value.put("anchor_outcome", anchorOutcome);
                        value.put("anchor_status", anchorStatus);
                        value.put("pivot_policy", "fixed");
                        value.put("multitouch_exclusive", true);
                        value.put("viewport_policy", liveEdgePinned
                            ? "inline-tui-live-edge" : "tracked-cell-universal");
                        value.put("live_edge_pins",
                            view.getScaleLiveEdgePinCountForDiagnostics());
                        value.put("reported_focus_drift_px", reportedFocusDrift);
                        value.put("top_row", view.getTopRow());
                        value.put("viewport_pixel_offset",
                            view.getViewportPixelOffsetForDiagnostics());
                        value.put("diagnostics", view.getRenderDiagnostics());
                    }
                    return value;
                }
            });
            pinchSamples.add(sample);
        }

        private void scrollCurrentLabViewport(final int cycle) throws Exception {
            onMain(new MainCallable<Object>() {
                @Override public Object call() {
                    TerminalView view = activity.getTerminalView();
                    if (view != null && view.getCurrentSession() == labSession) {
                        dispatchScroll(view, (cycle & 1) == 0);
                    }
                    return null;
                }
            });
            if ((cycle % 7) == 0) captureViewport("scroll-" + cycle);
        }

        private void selectAndRecord(final TerminalSession session, String label) throws Exception {
            if (session == null) return;
            long started = SystemClock.elapsedRealtime();
            onMain(new MainCallable<Object>() {
                @Override public Object call() {
                    client.setCurrentSession(session);
                    return null;
                }
            });
            JSONObject sample = new JSONObject();
            sample.put("label", label);
            sample.put("target_handle", session.mHandle);
            try {
                TerminalView selected = awaitValue("complete terminal page " + label, SELECTION_TIMEOUT_MS,
                    new MainCallable<TerminalView>() {
                        @Override public TerminalView call() {
                            TerminalView view = activity.getTerminalView();
                            if (view == null || view.getCurrentSession() != session || !view.hasCompleteRenderFrame()) {
                                return null;
                            }
                            return view;
                        }
                    });
                completedSelections++;
                sample.put("complete", true);
                sample.put("diagnostics", selected.getRenderDiagnostics());
            } catch (Throwable throwable) {
                incompleteSelections++;
                sample.put("complete", false);
                sample.put("error", rootMessage(throwable));
                warnings.add("incomplete page selection " + label + ": " + rootMessage(throwable));
            }
            sample.put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            switchSamples.add(sample);
        }

        private void requireSelection(TerminalSession session, String label) throws Exception {
            selectAndRecord(session, label);
            if (switchSamples.isEmpty() || !switchSamples.get(switchSamples.size() - 1).optBoolean("complete", false)) {
                throw new IllegalStateException("terminal page was not complete: " + label);
            }
        }

        private void captureViewport(final String label) throws Exception {
            JSONObject sample = onMain(new MainCallable<JSONObject>() {
                @Override public JSONObject call() throws Exception {
                    JSONObject value = new JSONObject();
                    value.put("label", label);
                    value.put("elapsed_ms", SystemClock.elapsedRealtime() - startedElapsedMs);
                    TerminalView view = activity.getTerminalView();
                    if (view == null) {
                        value.put("view", "none");
                    } else {
                        value.put("complete", view.hasCompleteRenderFrame());
                        value.put("diagnostics", view.getRenderDiagnostics());
                        TerminalSession current = view.getCurrentSession();
                        value.put("session", current == null ? "none" : current.mHandle);
                    }
                    return value;
                }
            });
            viewportSamples.add(sample);
        }

        private void captureBackgroundProgress(final String label) throws Exception {
            JSONObject sample = new JSONObject();
            sample.put("label", label);
            sample.put("elapsed_ms", SystemClock.elapsedRealtime() - startedElapsedMs);
            JSONArray sessions = new JSONArray();
            if (backgroundOne != null) sessions.put(sessionProgress(backgroundOne));
            if (backgroundTwo != null) sessions.put(sessionProgress(backgroundTwo));
            sample.put("sessions", sessions);
            backgroundSamples.add(sample);
        }

        private JSONObject sessionProgress(final TerminalSession session) throws Exception {
            return onMain(new MainCallable<JSONObject>() {
                @Override public JSONObject call() throws Exception {
                    return sessionProgressOnMain(session);
                }
            });
        }

        private static JSONObject sessionProgressOnMain(TerminalSession session) throws Exception {
            JSONObject value = new JSONObject();
            value.put("handle", session.mHandle);
            value.put("session_name", session.mSessionName == null ? "" : session.mSessionName);
            int pid = session.getPid();
            boolean running = pid > 0;
            value.put("pid", pid);
            value.put("running", running);
            value.put("process_state", running ? "running" : (pid == 0 ? "not-started" : "exited"));
            if (pid < 0) value.put("exit_status", session.getExitStatus());
            TerminalEmulator emulator = session.getEmulator();
            if (emulator == null) {
                value.put("available", false);
                return value;
            }
            value.put("available", true);
            value.put("content_revision", emulator.getContentRevision());
            value.put("transcript_rows", emulator.getActiveTranscriptRows());
            value.put("active_rows", emulator.getActiveRows());
            value.put("alternate_screen", emulator.isAlternateBufferActive());
            value.put("pty_parser_off_main_calls", session.getPtyParserOffMainThreadCallsForDiagnostics());
            value.put("pty_parser_off_main_bytes", session.getPtyParserOffMainThreadBytesForDiagnostics());
            value.put("pty_parser_main_calls", session.getPtyParserMainThreadCallsForDiagnostics());
            value.put("frame_notify_requests", session.getNativeScreenUpdateRequestsForDiagnostics());
            value.put("frame_notify_coalesced", session.getNativeScreenUpdateCoalescedForDiagnostics());
            value.put("frame_notify_published", session.getNativeScreenUpdatePublishedForDiagnostics());
            value.put("resize_transactions", session.getResizeTransactionsForDiagnostics());
            value.put("pty_window_size_ioctls",
                session.getPtyWindowSizeRequestsForDiagnostics());
            value.put("redundant_resizes_suppressed",
                session.getRedundantResizeRequestsSuppressedForDiagnostics());
            return value;
        }

        private void verifyBackgroundProgress() throws Exception {
            if (backgroundSamples.size() < 2) {
                errors.add("background progress samples are incomplete");
                return;
            }
            JSONObject first = backgroundSamples.get(0).optJSONArray("sessions") == null
                ? null : backgroundSamples.get(0);
            JSONObject last = backgroundSamples.get(backgroundSamples.size() - 1);
            if (first == null) {
                errors.add("background progress baseline is unavailable");
                return;
            }
            JSONArray before = first.optJSONArray("sessions");
            JSONArray after = last.optJSONArray("sessions");
            if (before == null || after == null || before.length() != after.length()) {
                errors.add("background progress session counts do not match");
                return;
            }
            for (int index = 0; index < before.length(); index++) {
                JSONObject start = before.optJSONObject(index);
                JSONObject end = after.optJSONObject(index);
                if (start == null || end == null) {
                    errors.add("background progress sample is malformed at index=" + index);
                    continue;
                }
                long startBytes = start.optLong("pty_parser_off_main_bytes", 0L);
                long endBytes = end.optLong("pty_parser_off_main_bytes", 0L);
                long startRevision = start.optLong("content_revision", 0L);
                long endRevision = end.optLong("content_revision", 0L);
                if (endBytes <= startBytes && endRevision <= startRevision) {
                    errors.add("background session did not advance independently handle=" +
                        end.optString("handle", "unknown"));
                }
            }
        }

        private void verifyLabReport() throws Exception {
            if (!isLabReportReady()) {
                throw new IllegalStateException("lab report was not visible: " + labReportPath);
            }
            labReport = new JSONObject(readFile(new File(labReportPath)));
            JSONObject industrial = labReport.optJSONObject("industrial");
            if (industrial == null) {
                errors.add("lab report lacks industrial section");
                return;
            }
            if (!runId.equals(industrial.optString("run_id"))) {
                errors.add("lab report run ID mismatch: " + industrial.optString("run_id"));
            }
            if (!industrial.optBoolean("tty")) errors.add("lab did not run on a real TTY");
            if (!industrial.optBoolean("input_token_seen")) errors.add("lab did not receive TerminalView input token");
            if (industrial.optString("failure").length() > 0) {
                errors.add("lab failure: " + industrial.optString("failure"));
            }
            JSONObject summary = labReport.optJSONObject("summary");
            if (summary != null && summary.optInt("failed", 0) > 0) {
                errors.add("lab summary failures=" + summary.optInt("failed", 0));
            }
            if (industrial.optInt("sigwinch_count", 0) == 0) {
                warnings.add("no SIGWINCH observed during Android pinch sequence");
            }
        }

        private void verifyVulkanRenderer() throws Exception {
            vulkanEvidence = awaitValue("Vulkan renderer state", SELECTION_TIMEOUT_MS,
                new MainCallable<JSONObject>() {
                    @Override public JSONObject call() throws Exception {
                        TerminalView view = activity.getTerminalView();
                        if (view == null) return null;
                        boolean expected = view.isVulkanRendererExpectedForDiagnostics();
                        boolean ready = view.isVulkanFrameReadyForDiagnostics();
                        boolean failed = view.hasVulkanRendererFailedForDiagnostics();
                        long presented = view.getVulkanPresentedFrameCountForDiagnostics();
                        if (expected && !ready && !failed) return null;
                        JSONObject value = new JSONObject();
                        value.put("expected", expected);
                        value.put("ready", ready);
                        value.put("failed", failed);
                        value.put("presented_frames", presented);
                        value.put("diagnostics", view.getRenderDiagnostics());
                        return value;
                    }
                });
            boolean expected = vulkanEvidence.optBoolean("expected", false);
            if (!expected) {
                warnings.add("independent Vulkan renderer is unavailable on this device");
                return;
            }
            if (vulkanEvidence.optBoolean("failed", false)) {
                errors.add("independent Vulkan renderer entered permanent fallback");
            }
            if (!vulkanEvidence.optBoolean("ready", false)) {
                errors.add("independent Vulkan renderer did not commit a complete frame");
            }
            if (vulkanEvidence.optLong("presented_frames", 0L) <= 0L) {
                errors.add("independent Vulkan renderer presented no frames");
            }
            vulkanOrientationEvidence = TerminalVulkanOrientationProbe.run(instrumentation, activity);
            if (!vulkanOrientationEvidence.optBoolean("passed", false)) {
                errors.add("independent Vulkan renderer pixel orientation mismatch: " +
                    vulkanOrientationEvidence.optString("sampled", "unavailable"));
            }
        }

        private JSONObject buildReport() throws Exception {
            JSONObject root = new JSONObject();
            root.put("schema", 1);
            root.put("kind", "termux-terminal-industrial-device-probe");
            root.put("run_id", runId);
            root.put("started_at", startedAt);
            root.put("finished_at", now());
            root.put("duration_ms", SystemClock.elapsedRealtime() - startedElapsedMs);
            root.put("lab_path", labPath);
            root.put("lab_report_path", labReportPath);
            root.put("lab_report_visible", labReportVisible);
            root.put("stress", stress);
            root.put("frames", frames);
            root.put("burst_lines", burstLines);
            root.put("baseline_text_size", baselineTextSize);
            root.put("user_text_size_before_run", userTextSizeBeforeRun);
            root.put("activity_window_ready", activityWindowReady);
            root.put("test_window_override_applied", testWindowOverrideApplied);
            root.put("input_sent", inputSent);
            root.put("pinch_cycles", pinchCycles);
            root.put("pinch_samples", new JSONArray(pinchSamples));
            root.put("ime_cycles", imeCycles);
            root.put("ime_samples", new JSONArray(imeSamples));
            root.put("cancelled_pinch", cancelledPinchEvidence == null
                ? JSONObject.NULL : cancelledPinchEvidence);
            root.put("completed_selections", completedSelections);
            root.put("incomplete_selections", incompleteSelections);
            root.put("native_probe", nativeProbeEvidence == null ? "" : nativeProbeEvidence);
            root.put("viewport_probe", viewportProbeEvidence == null ? "" : viewportProbeEvidence);
            root.put("vulkan_renderer", vulkanEvidence == null ? JSONObject.NULL : vulkanEvidence);
            root.put("vulkan_orientation", vulkanOrientationEvidence == null
                ? JSONObject.NULL : vulkanOrientationEvidence);
            root.put("frame_metrics", frameCollector.toJson(refreshRate(activity)));
            root.put("switches", new JSONArray(switchSamples));
            root.put("viewport_samples", new JSONArray(viewportSamples));
            root.put("background_progress", new JSONArray(backgroundSamples));
            root.put("errors", new JSONArray(errors));
            root.put("warnings", new JSONArray(warnings));
            if (failureStack != null) root.put("failure_stack", failureStack);
            if (labReport != null) root.put("lab_summary", labReport.optJSONObject("summary"));
            root.put("current_session", sessionSnapshot());
            root.put("device", deviceJson());
            return root;
        }

        private JSONObject sessionSnapshot() throws Exception {
            return onMain(new MainCallable<JSONObject>() {
                @Override public JSONObject call() throws Exception {
                    JSONObject value = new JSONObject();
                    TerminalView view = activity == null ? null : activity.getTerminalView();
                    TerminalSession session = view == null ? null : view.getCurrentSession();
                    if (session == null) {
                        value.put("available", false);
                        return value;
                    }
                    TerminalEmulator emulator = session.getEmulator();
                    value.put("available", true);
                    value.put("handle", session.mHandle);
                    value.put("running", session.isRunning());
                    value.put("title", session.getTitle());
                    value.put("pty_parser_off_main_calls", session.getPtyParserOffMainThreadCallsForDiagnostics());
                    value.put("pty_parser_off_main_bytes", session.getPtyParserOffMainThreadBytesForDiagnostics());
                    value.put("pty_parser_main_calls", session.getPtyParserMainThreadCallsForDiagnostics());
                    value.put("frame_notify_requests", session.getNativeScreenUpdateRequestsForDiagnostics());
                    value.put("frame_notify_coalesced", session.getNativeScreenUpdateCoalescedForDiagnostics());
                    value.put("frame_notify_published", session.getNativeScreenUpdatePublishedForDiagnostics());
                    value.put("resize_transactions", session.getResizeTransactionsForDiagnostics());
                    value.put("pty_window_size_ioctls",
                        session.getPtyWindowSizeRequestsForDiagnostics());
                    value.put("redundant_resizes_suppressed",
                        session.getRedundantResizeRequestsSuppressedForDiagnostics());
                    if (emulator != null) {
                        value.put("ghostty_parser_authority", emulator.isGhosttyParserAuthorityActive());
                        value.put("ghostty_render_authority", emulator.isGhosttyRenderAuthorityActive());
                        value.put("ghostty_render_healthy", emulator.isGhosttyRenderBackendHealthy());
                        value.put("ghostty_render_status", emulator.getGhosttyRenderStatusForDiagnostics());
                        value.put("transcript_rows", emulator.getActiveTranscriptRows());
                        value.put("grid", emulator.mColumns + "x" + emulator.mRows);
                    }
                    if (view != null) {
                        value.put("frame_complete", view.hasCompleteRenderFrame());
                        value.put("render_diagnostics", view.getRenderDiagnostics());
                    }
                    return value;
                }
            });
        }

        String summaryLine() {
            return "TERMUX_INDUSTRIAL_PROBE status=" + (errors.isEmpty() ? "PASS" : "FAIL") +
                " run=" + runId + " report=" + appReportPath +
                " selections=" + completedSelections + "/" + incompleteSelections +
                " ime=" + imeCycles +
                " warnings=" + warnings.size() + " errors=" + errors.size();
        }
    }

    private interface MainCallable<T> extends Callable<T> {
    }

    private static final class FrameCollector {
        final ArrayList<Long> totalNs = new ArrayList<>();
        final ArrayList<Long> drawNs = new ArrayList<>();
        final ArrayList<Long> syncNs = new ArrayList<>();
        final ArrayList<Long> commandNs = new ArrayList<>();
        final ArrayList<JSONObject> windows = new ArrayList<>();
        Window.OnFrameMetricsAvailableListener listener;
        boolean available;
        int droppedCallbacks;
        String activeWindow;
        int activeStartIndex;
        int activeDroppedCallbacks;

        void start(Window window) {
            if (window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
            listener = new Window.OnFrameMetricsAvailableListener() {
                @Override public void onFrameMetricsAvailable(Window ignored, FrameMetrics metrics, int dropCount) {
                    synchronized (FrameCollector.this) {
                        appendMetric(totalNs, metrics.getMetric(FrameMetrics.TOTAL_DURATION));
                        appendMetric(drawNs, metrics.getMetric(FrameMetrics.DRAW_DURATION));
                        appendMetric(syncNs, metrics.getMetric(FrameMetrics.SYNC_DURATION));
                        appendMetric(commandNs, metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION));
                        droppedCallbacks += Math.max(0, dropCount);
                    }
                }
            };
            window.addOnFrameMetricsAvailableListener(listener, new Handler(Looper.getMainLooper()));
            available = true;
        }

        void beginWindow(String name) {
            synchronized (this) {
                endWindowLocked();
                activeWindow = name;
                activeStartIndex = totalNs.size();
                activeDroppedCallbacks = droppedCallbacks;
            }
        }

        void endWindow() {
            synchronized (this) {
                endWindowLocked();
            }
        }

        private void endWindowLocked() {
            if (activeWindow == null) return;
            try {
                windows.add(windowJson(activeWindow, activeStartIndex, totalNs.size(),
                    Math.max(0, droppedCallbacks - activeDroppedCallbacks), 60f));
            } catch (Throwable ignored) {
                // The aggregate metrics remain authoritative if a diagnostic window cannot be encoded.
            }
            activeWindow = null;
        }

        void stop(Window window) {
            if (window != null && listener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                window.removeOnFrameMetricsAvailableListener(listener);
            }
        }

        JSONObject toJson(float refreshRate) throws Exception {
            JSONObject value = new JSONObject();
            value.put("available", available);
            value.put("refresh_rate_hz", refreshRate);
            long budgetNs = (long) (1_000_000_000d / Math.max(1f, refreshRate));
            value.put("frame_budget_ms", nanosToMs(budgetNs));
            synchronized (this) {
                value.put("dropped_callbacks", droppedCallbacks);
                value.put("total", latencyJson(totalNs));
                value.put("draw", latencyJson(drawNs));
                value.put("sync", latencyJson(syncNs));
                value.put("command_issue", latencyJson(commandNs));
                int janky = 0;
                for (Long valueNs : totalNs) {
                    if (valueNs != null && valueNs > budgetNs) janky++;
                }
                value.put("janky_frames", janky);
                value.put("jank_ratio", totalNs.isEmpty() ? 0d : (double) janky / totalNs.size());
                if (activeWindow != null) endWindowLocked();
                JSONArray windowValues = new JSONArray();
                for (JSONObject window : windows) {
                    windowValues.put(windowJson(window.optString("name"),
                        window.optInt("start_index", 0), window.optInt("end_index", 0),
                        window.optInt("dropped_callbacks", 0), refreshRate));
                }
                value.put("windows", windowValues);
            }
            return value;
        }

        private JSONObject windowJson(String name, int start, int end, int dropped, float refreshRate)
            throws Exception {
            int boundedStart = Math.max(0, Math.min(start, totalNs.size()));
            int boundedEnd = Math.max(boundedStart, Math.min(end, totalNs.size()));
            JSONObject value = new JSONObject();
            value.put("name", name);
            value.put("start_index", boundedStart);
            value.put("end_index", boundedEnd);
            value.put("dropped_callbacks", dropped);
            long budgetNs = (long) (1_000_000_000d / Math.max(1f, refreshRate));
            ArrayList<Long> values = new ArrayList<>(totalNs.subList(boundedStart, boundedEnd));
            value.put("total", latencyJson(values));
            int janky = 0;
            for (Long sample : values) {
                if (sample != null && sample > budgetNs) janky++;
            }
            value.put("janky_frames", janky);
            value.put("jank_ratio", values.isEmpty() ? 0d : (double) janky / values.size());
            return value;
        }

        private static void appendMetric(List<Long> values, long value) {
            if (value >= 0L) values.add(value);
        }
    }

    private static <T> T onMain(Instrumentation instrumentation, final MainCallable<T> callable) throws Exception {
        final AtomicReference<T> value = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    value.set(callable.call());
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }
        });
        Throwable throwable = failure.get();
        if (throwable == null) return value.get();
        if (throwable instanceof Exception) throw (Exception) throwable;
        if (throwable instanceof Error) throw (Error) throwable;
        throw new RuntimeException(throwable);
    }

    private static <T> T awaitValue(Instrumentation instrumentation, String name, long timeoutMs,
                                    MainCallable<T> probe) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        Throwable last = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                T value = onMain(instrumentation, probe);
                if (value != null) return value;
            } catch (Throwable throwable) {
                last = throwable;
            }
            SystemClock.sleep(40L);
        }
        String suffix = last == null ? "" : ": " + rootMessage(last);
        throw new IllegalStateException("timed out waiting for " + name + suffix);
    }

    private static void sendToken(TerminalView view, String token) {
        for (int offset = 0; offset < token.length();) {
            int codePoint = token.codePointAt(offset);
            view.inputCodePoint(0, codePoint, false, false);
            offset += Character.charCount(codePoint);
        }
        view.inputCodePoint(0, '\n', false, false);
    }

    private static void dispatchScroll(TerminalView view, boolean upward) {
        int width = Math.max(2, view.getWidth());
        int height = Math.max(2, view.getHeight());
        float x = width * 0.50f;
        float startY = upward ? height * 0.78f : height * 0.22f;
        float endY = upward ? height * 0.22f : height * 0.78f;
        long down = SystemClock.uptimeMillis();
        dispatchTouch(view, down, down, MotionEvent.ACTION_DOWN, new float[] {x}, new float[] {startY});
        for (int step = 1; step <= 8; step++) {
            float y = startY + (endY - startY) * step / 8f;
            dispatchTouch(view, down, down + step * 12L, MotionEvent.ACTION_MOVE, new float[] {x}, new float[] {y});
        }
        dispatchTouch(view, down, down + 120L, MotionEvent.ACTION_UP, new float[] {x}, new float[] {endY});
    }

    private static void dispatchPinch(TerminalView view, boolean expand) {
        int width = Math.max(4, view.getWidth());
        int height = Math.max(4, view.getHeight());
        float centerX = width * 0.5f;
        float minimumDimension = Math.min(width, height);
        // Reproduce the real failure: pointer 0 remains stationary while pointer 1 moves vertically.
        // This changes both span and Android's reported focus. Production must consume only span;
        // focus translation must never be reinterpreted as terminal history scrolling.
        float base = Math.max(48f, minimumDimension * (expand ? 0.12f : 0.38f));
        float target = Math.max(48f, minimumDimension * (expand ? 0.38f : 0.16f));
        float fixedX = centerX - minimumDimension * 0.04f;
        float movingX = centerX + minimumDimension * 0.04f;
        float fixedY = height * 0.72f;
        float movingStartY = fixedY - base;
        float movingEndY = fixedY - target;
        long down = SystemClock.uptimeMillis();
        dispatchTouch(view, down, down, MotionEvent.ACTION_DOWN,
            new float[] {fixedX}, new float[] {fixedY});
        dispatchTouch(view, down, down + 8L, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            new float[] {fixedX, movingX}, new float[] {fixedY, movingStartY});
        for (int step = 1; step <= 10; step++) {
            float movingY = movingStartY + (movingEndY - movingStartY) * step / 10f;
            dispatchTouch(view, down, down + 8L + step * 16L, MotionEvent.ACTION_MOVE,
                new float[] {fixedX, movingX}, new float[] {fixedY, movingY});
        }
        dispatchTouch(view, down, down + 180L, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            new float[] {fixedX, movingX}, new float[] {fixedY, movingEndY});
        dispatchTouch(view, down, down + 190L, MotionEvent.ACTION_UP,
            new float[] {fixedX}, new float[] {fixedY});
    }

    private static PinchInProgress dispatchPinchUntilCancel(TerminalView view, boolean expand) {
        int width = Math.max(4, view.getWidth());
        int height = Math.max(4, view.getHeight());
        float centerX = width * 0.5f;
        float minimumDimension = Math.min(width, height);
        float base = Math.max(48f, minimumDimension * (expand ? 0.12f : 0.38f));
        float target = Math.max(48f, minimumDimension * (expand ? 0.38f : 0.16f));
        float fixedX = centerX - minimumDimension * 0.04f;
        float movingX = centerX + minimumDimension * 0.04f;
        float fixedY = height * 0.72f;
        float movingStartY = fixedY - base;
        float movingEndY = fixedY - target;
        long down = SystemClock.uptimeMillis();
        dispatchTouch(view, down, down, MotionEvent.ACTION_DOWN,
            new float[] {fixedX}, new float[] {fixedY});
        dispatchTouch(view, down, down + 8L,
            MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            new float[] {fixedX, movingX}, new float[] {fixedY, movingStartY});
        long lastEventTime = down + 8L;
        for (int step = 1; step <= 10; step++) {
            float movingY = movingStartY + (movingEndY - movingStartY) * step / 10f;
            lastEventTime = down + 8L + step * 16L;
            dispatchTouch(view, down, lastEventTime, MotionEvent.ACTION_MOVE,
                new float[] {fixedX, movingX}, new float[] {fixedY, movingY});
        }
        return new PinchInProgress(down, lastEventTime, fixedX, movingX, fixedY, movingEndY);
    }

    private static void dispatchPinchCancel(TerminalView view, PinchInProgress pinch) {
        long eventTime = Math.max(SystemClock.uptimeMillis(), pinch.lastEventTime + 16L);
        dispatchTouch(view, pinch.downTime, eventTime, MotionEvent.ACTION_CANCEL,
            new float[] {pinch.fixedX, pinch.movingX},
            new float[] {pinch.fixedY, pinch.movingY});
    }

    private static final class PinchInProgress {
        final long downTime;
        final long lastEventTime;
        final float fixedX;
        final float movingX;
        final float fixedY;
        final float movingY;

        PinchInProgress(long downTime, long lastEventTime, float fixedX, float movingX,
                        float fixedY, float movingY) {
            this.downTime = downTime;
            this.lastEventTime = lastEventTime;
            this.fixedX = fixedX;
            this.movingX = movingX;
            this.fixedY = fixedY;
            this.movingY = movingY;
        }
    }

    private static void dispatchTouch(TerminalView view, long downTime, long eventTime, int action,
                                      float[] xs, float[] ys) {
        int count = xs.length;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[count];
        for (int index = 0; index < count; index++) {
            MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
            property.id = index;
            property.toolType = MotionEvent.TOOL_TYPE_FINGER;
            properties[index] = property;
            MotionEvent.PointerCoords coordinate = new MotionEvent.PointerCoords();
            coordinate.x = clamp(xs[index], 1f, Math.max(1f, view.getWidth() - 1f));
            coordinate.y = clamp(ys[index], 1f, Math.max(1f, view.getHeight() - 1f));
            coordinate.pressure = 1f;
            coordinate.size = 1f;
            coordinates[index] = coordinate;
        }
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, count, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            view.onTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static JSONObject latencyJson(List<Long> nanos) throws Exception {
        ArrayList<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        JSONObject value = new JSONObject();
        value.put("count", sorted.size());
        if (sorted.isEmpty()) return value;
        double sum = 0d;
        for (Long sample : sorted) sum += nanosToMs(sample);
        value.put("min_ms", nanosToMs(sorted.get(0)));
        value.put("p50_ms", nanosToMs(percentile(sorted, 0.50d)));
        value.put("p95_ms", nanosToMs(percentile(sorted, 0.95d)));
        value.put("p99_ms", nanosToMs(percentile(sorted, 0.99d)));
        value.put("max_ms", nanosToMs(sorted.get(sorted.size() - 1)));
        value.put("avg_ms", sum / sorted.size());
        return value;
    }

    private static long percentile(List<Long> sorted, double percent) {
        if (sorted.isEmpty()) return 0L;
        int index = (int) (percent * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static JSONObject deviceJson() throws Exception {
        JSONObject value = new JSONObject();
        value.put("manufacturer", Build.MANUFACTURER);
        value.put("model", Build.MODEL);
        value.put("device", Build.DEVICE);
        value.put("sdk", Build.VERSION.SDK_INT);
        value.put("fingerprint", Build.FINGERPRINT);
        return value;
    }

    private static float refreshRate(TermuxActivity activity) {
        if (activity == null || activity.getWindowManager() == null ||
            activity.getWindowManager().getDefaultDisplay() == null) return 60f;
        float rate = activity.getWindowManager().getDefaultDisplay().getRefreshRate();
        return rate > 0f ? rate : 60f;
    }

    private static void writeJsonAtomically(File destination, JSONObject report) throws Exception {
        File directory = destination.getParentFile();
        if (directory != null && !directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("could not create report directory: " + directory);
        }
        File temporary = new File(destination.getPath() + ".tmp-" + android.os.Process.myPid());
        FileOutputStream output = new FileOutputStream(temporary);
        try {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            output.getFD().sync();
        } finally {
            output.close();
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IllegalStateException("could not atomically rename report: " + destination);
        }
    }

    private static String readFile(File file) throws Exception {
        StringBuilder value = new StringBuilder((int) Math.min(Integer.MAX_VALUE, file.length() + 128L));
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) value.append(buffer, 0, read);
        } finally {
            reader.close();
        }
        return value.toString();
    }

    private static int boundedInt(Bundle args, String key, int fallback, int minimum, int maximum) {
        int value = fallback;
        Object raw = args.get(key);
        if (raw instanceof Number) {
            value = ((Number) raw).intValue();
        } else if (raw != null) {
            try {
                value = Integer.parseInt(String.valueOf(raw));
            } catch (NumberFormatException ignored) {
            }
        }
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long boundedLong(Bundle args, String key, long fallback, long minimum, long maximum) {
        long value = fallback;
        Object raw = args.get(key);
        if (raw instanceof Number) {
            value = ((Number) raw).longValue();
        } else if (raw != null) {
            try {
                value = Long.parseLong(String.valueOf(raw));
            } catch (NumberFormatException ignored) {
            }
        }
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String safeRunId(String value) {
        String normalized = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "-");
        if (normalized.length() == 0) normalized = "industrial-" + SystemClock.elapsedRealtime();
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\\"'\\\"'") + "'";
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000d;
    }

    private static String now() {
        return String.format(Locale.US, "%d", System.currentTimeMillis());
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.length() == 0 ? current.getClass().getSimpleName() : message;
    }
}
