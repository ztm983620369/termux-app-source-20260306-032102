package com.termux.app;

import android.content.Context;
import android.os.Process;

import com.termux.shared.logger.Logger;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Makes the native Shadow build Worker a child of the long-lived Termux foreground service.
 *
 * <p>The Worker is deliberately not a terminal task: Codex command cleanup therefore cannot reap
 * it or the Gradle daemon it owns. The native socket protocol still performs same-UID peer checks,
 * request validation, project locking, idempotency and evidence capture.</p>
 */
final class ShadowPluginWorkerSupervisor {

    private static final String LOG_TAG = "ShadowWorkerSupervisor";
    private static final int MAX_STATE_BYTES = 64 * 1024;
    private static final long DEFAULT_IDLE_SECONDS = 3600L;

    private final Context context;
    private final File home;
    private final File prefix;
    private final File shadowHome;
    private final File workerDirectory;
    private final File stateFile;
    private final File socketFile;
    private final File lockFile;
    private final File supervisorReport;

    private java.lang.Process process;
    private int requestedStopPid;

    ShadowPluginWorkerSupervisor(Context context) {
        this.context = context.getApplicationContext();
        File files = this.context.getFilesDir();
        this.home = new File(files, "home");
        this.prefix = new File(files, "usr");
        this.shadowHome = new File(home, ".termux-shadow");
        this.workerDirectory = new File(shadowHome, "worker");
        this.stateFile = new File(workerDirectory, "state.json");
        this.socketFile = new File(workerDirectory, "shadow-plugin.sock");
        this.lockFile = new File(workerDirectory, "worker.lock");
        this.supervisorReport = new File(shadowHome, "reports/worker-supervisor.json");
    }

    synchronized String ensure() {
        if (isAlive(process)) {
            return "READY pid=" + processPid(process);
        }
        process = null;
        WorkerState existing = readState();
        int existingPid = existing == null ? readPidFile(lockFile) : existing.pid;
        if (existingPid > 0 && isVerifiedWorkerProcess(existingPid) && socketFile.exists()) {
            return "READY pid=" + existingPid + " (adopted state)";
        }
        cleanupStaleEndpoint();
        File binary = new File(prefix, "bin/shadow-plugin");
        if (!binary.isFile() || !binary.canExecute()) {
            String message = "native Worker binary is not executable: " + binary;
            writeSupervisorReport("START_FAILED", 0, message);
            throw new IllegalStateException(message);
        }
        ensurePrivateDirectory(workerDirectory);
        File logDirectory = new File(shadowHome, "logs/worker");
        ensurePrivateDirectory(logDirectory);
        File log = new File(logDirectory, "supervisor.log");
        ProcessBuilder builder = new ProcessBuilder(
                binary.getAbsolutePath(),
                "__worker",
                "--idle-timeout-seconds",
                Long.toString(DEFAULT_IDLE_SECONDS)
        );
        builder.directory(home);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("HOME", home.getAbsolutePath());
        environment.put("TERMUX_HOME", home.getAbsolutePath());
        environment.put("PREFIX", prefix.getAbsolutePath());
        environment.put("TERMUX_SHADOW_HOME", shadowHome.getAbsolutePath());
        environment.put(
                "PATH",
                new File(prefix, "bin").getAbsolutePath() + ":/system/bin:/system/xbin"
        );
        environment.put("TMPDIR", new File(prefix, "tmp").getAbsolutePath());
        environment.put("LANG", "C.UTF-8");
        try {
            java.lang.Process started = builder.start();
            process = started;
            int pid = processPid(started);
            writeSupervisorReport("STARTING", pid, "Worker process started");
            monitor(started, pid);
            Logger.logInfo(LOG_TAG, "Started Shadow build Worker pid=" + pid);
            return "STARTING pid=" + pid;
        } catch (Exception error) {
            process = null;
            writeSupervisorReport("START_FAILED", 0, rootMessage(error));
            throw new IllegalStateException("failed to start native Worker", error);
        }
    }

    synchronized String query() {
        WorkerState state = readState();
        if (state == null) {
            return isAlive(process)
                    ? "STARTING pid=" + processPid(process)
                    : "STOPPED";
        }
        boolean alive = state.pid > 0 && isVerifiedWorkerProcess(state.pid);
        return (alive ? state.status : "STALE")
                + " pid=" + state.pid
                + " protocol=" + state.protocolVersion
                + " daemonPid=" + state.gradleDaemonPid;
    }

    synchronized String stop() {
        WorkerState state = readState();
        java.lang.Process current = process;
        requestedStopPid = state != null && state.pid > 0
                ? state.pid
                : processPid(current);
        process = null;
        if (isAlive(current)) {
            current.destroy();
        }
        if (state != null && state.pid > 0 && isVerifiedWorkerProcess(state.pid)) {
            Process.sendSignal(state.pid, 15);
            waitForWorkerExit(state.pid, 1000L);
            if (isVerifiedWorkerProcess(state.pid)) {
                Process.killProcess(state.pid);
            }
        }
        if (state != null && state.gradleDaemonManaged && state.gradleDaemonPid > 0
                && isProcessCommand(state.gradleDaemonPid, "GradleDaemon")) {
            Process.sendSignal(state.gradleDaemonPid, 15);
        }
        cleanupStaleEndpoint();
        writeSupervisorReport("STOPPED", state == null ? 0 : state.pid, "Worker stop requested");
        return "STOPPED";
    }

    private void monitor(final java.lang.Process monitored, final int pid) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                int exitCode;
                try {
                    exitCode = monitored.waitFor();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    exitCode = -1;
                }
                synchronized (ShadowPluginWorkerSupervisor.this) {
                    boolean requestedStop = pid > 0 && requestedStopPid == pid;
                    if (process == monitored) {
                        process = null;
                    }
                    if (requestedStop) {
                        requestedStopPid = 0;
                    }
                    WorkerState state = readState();
                    boolean graceful = requestedStop
                            || (state != null && "STOPPED".equals(state.status));
                    if (!graceful && ownsEndpoint(pid)) {
                        socketFile.delete();
                        lockFile.delete();
                        stateFile.delete();
                    }
                    writeSupervisorReport(
                            graceful ? "STOPPED" : "CRASHED",
                            pid,
                            "Worker exited with code " + exitCode
                    );
                }
            }
        }, "shadow-plugin-worker-monitor");
        thread.setDaemon(true);
        thread.start();
    }

    private WorkerState readState() {
        if (!stateFile.isFile()) {
            return null;
        }
        try {
            JSONObject value = new JSONObject(
                    new String(readBounded(stateFile), StandardCharsets.UTF_8)
            );
            WorkerState state = new WorkerState();
            state.status = value.optString("status", "UNKNOWN");
            state.pid = value.optInt("pid", 0);
            state.protocolVersion = value.optInt("protocolVersion", 0);
            state.gradleDaemonPid = value.optInt("gradleDaemonPid", 0);
            state.gradleDaemonManaged = value.optBoolean("gradleDaemonManaged", false);
            return state;
        } catch (Exception error) {
            Logger.logWarn(LOG_TAG, "Ignoring unreadable Worker state: " + rootMessage(error));
            return null;
        }
    }

    private void cleanupStaleEndpoint() {
        socketFile.delete();
        lockFile.delete();
        WorkerState state = readState();
        if (state == null || state.pid <= 0 || !isVerifiedWorkerProcess(state.pid)) {
            stateFile.delete();
        }
    }

    private boolean isVerifiedWorkerProcess(int pid) {
        return isProcessCommand(pid, "shadow-plugin") && isProcessCommand(pid, "__worker");
    }

    private boolean ownsEndpoint(int pid) {
        if (pid <= 0) {
            return false;
        }
        WorkerState state = readState();
        return (state != null && state.pid == pid) || readPidFile(lockFile) == pid;
    }

    private static int readPidFile(File file) {
        if (file == null || !file.isFile()) {
            return 0;
        }
        try {
            return Integer.parseInt(
                    new String(readBounded(file), StandardCharsets.UTF_8).trim()
            );
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void waitForWorkerExit(int pid, long timeoutMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while (isVerifiedWorkerProcess(pid)
                && android.os.SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean isProcessCommand(int pid, String needle) {
        if (pid <= 0) {
            return false;
        }
        File commandLine = new File("/proc/" + pid + "/cmdline");
        if (!commandLine.isFile()) {
            return false;
        }
        try {
            return new String(readBounded(commandLine), StandardCharsets.UTF_8).contains(needle);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isAlive(java.lang.Process process) {
        if (process == null) {
            return false;
        }
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    private static int processPid(java.lang.Process process) {
        if (process == null) {
            return 0;
        }
        try {
            Object value = java.lang.Process.class.getMethod("pid").invoke(process);
            if (value instanceof Long) {
                return ((Long) value).intValue();
            }
        } catch (Throwable ignored) {
            // Android's Process implementation before API 26 exposes only an internal pid field.
        }
        try {
            java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            return field.getInt(process);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void writeSupervisorReport(String status, int pid, String message) {
        try {
            File parent = supervisorReport.getParentFile();
            ensurePrivateDirectory(parent);
            JSONObject value = new JSONObject();
            value.put("schemaVersion", 1);
            value.put("status", status);
            value.put("pid", pid);
            value.put("message", message);
            value.put("updatedAt", System.currentTimeMillis());
            byte[] bytes = (value.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
            File temporary = new File(parent, ".worker-supervisor.tmp");
            FileOutputStream output = new FileOutputStream(temporary, false);
            try {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            } finally {
                output.close();
            }
            if (!temporary.renameTo(supervisorReport)) {
                temporary.delete();
            }
            makePrivateFile(supervisorReport);
        } catch (Exception error) {
            Logger.logWarn(LOG_TAG, "Failed to write Worker supervisor report: " + rootMessage(error));
        }
    }

    private static byte[] readBounded(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_STATE_BYTES) {
                    throw new IllegalStateException("file exceeds Worker state limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static void ensurePrivateDirectory(File directory) {
        if (directory == null || (!directory.mkdirs() && !directory.isDirectory())) {
            throw new IllegalStateException("failed to create private directory: " + directory);
        }
        directory.setReadable(false, false);
        directory.setWritable(false, false);
        directory.setExecutable(false, false);
        directory.setReadable(true, true);
        directory.setWritable(true, true);
        directory.setExecutable(true, true);
    }

    private static void makePrivateFile(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.length() == 0
                ? current.getClass().getSimpleName()
                : message;
    }

    private static final class WorkerState {
        String status;
        int pid;
        int protocolVersion;
        int gradleDaemonPid;
        boolean gradleDaemonManaged;
    }
}
