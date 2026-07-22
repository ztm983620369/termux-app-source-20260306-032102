package com.termux.app.terminal;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Verifies that a Codex control event belongs to the process tree of its terminal tab. */
final class CodexProcessIdentity {

    private static final int MAX_PROC_FILE_BYTES = 64 * 1024;
    private static final int MAX_ANCESTRY_DEPTH = 256;

    private CodexProcessIdentity() {
    }

    static boolean isLiveCodexProcessForSession(@NonNull TerminalSession session, int processId) {
        int terminalRootPid = session.getPid();
        if (processId <= 0 || terminalRootPid <= 0 || !isCodexProcess(processId)) return false;

        Set<Integer> visited = new HashSet<>();
        int current = processId;
        for (int depth = 0; depth < MAX_ANCESTRY_DEPTH && current > 0; depth++) {
            if (!visited.add(current)) return false;
            if (current == terminalRootPid) return true;
            current = readParentPid(current);
        }
        return false;
    }

    static int findLiveCodexProcessForSession(@Nullable TerminalSession session) {
        if (session == null || session.getPid() <= 0) return -1;

        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        pending.add(session.getPid());
        while (!pending.isEmpty() && visited.size() < MAX_ANCESTRY_DEPTH) {
            Integer processId = pending.pollLast();
            if (processId == null || processId <= 0 || !visited.add(processId)) continue;
            if (isCodexProcess(processId)) return processId;

            byte[] children = readBoundedFile(new File(
                "/proc/" + processId + "/task/" + processId + "/children"));
            if (children == null || children.length == 0) continue;
            String raw = new String(children, StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) continue;
            for (String token : raw.split("\\s+")) {
                try {
                    int child = Integer.parseInt(token);
                    if (child > 0) pending.add(child);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    /**
     * Finds Codex below the terminal process, falling back to a PID supplied by a
     * validated host-ready lease. Some Android kernels do not expose every
     * descendant through /proc/&lt;pid&gt;/task/&lt;pid&gt;/children, so the fallback is
     * accepted only when the process is still Codex and shares this terminal's
     * process ancestry or terminal identity.
     */
    static int findLiveCodexProcessForSession(@Nullable TerminalSession session,
                                              int leasedProcessId) {
        int descendant = findLiveCodexProcessForSession(session);
        boolean leasedProcessAttached = session != null && leasedProcessId > 0 &&
            isLiveCodexProcessAttachedToSession(session, leasedProcessId);
        return selectVerifiedProcessId(descendant, leasedProcessId, leasedProcessAttached);
    }

    static int selectVerifiedProcessId(int descendantProcessId, int leasedProcessId,
                                       boolean leasedProcessAttached) {
        if (descendantProcessId > 0) return descendantProcessId;
        return leasedProcessId > 0 && leasedProcessAttached ? leasedProcessId : -1;
    }

    static boolean isLiveCodexProcessAttachedToSession(@NonNull TerminalSession session,
                                                       int processId) {
        if (isLiveCodexProcessForSession(session, processId)) return true;
        int terminalRootPid = session.getPid();
        if (processId <= 0 || terminalRootPid <= 0 || !isCodexProcess(processId)) return false;

        byte[] terminalStat = readBoundedFile(new File("/proc/" + terminalRootPid + "/stat"));
        byte[] processStat = readBoundedFile(new File("/proc/" + processId + "/stat"));
        if (terminalStat == null || processStat == null) return false;
        return processStatsShareTerminal(
            new String(terminalStat, StandardCharsets.UTF_8),
            new String(processStat, StandardCharsets.UTF_8));
    }

    static boolean processStatsShareTerminal(@Nullable String first, @Nullable String second) {
        long[] firstIdentity = parseTerminalIdentity(first);
        long[] secondIdentity = parseTerminalIdentity(second);
        if (firstIdentity == null || secondIdentity == null) return false;
        long firstSession = firstIdentity[0];
        long firstTty = firstIdentity[1];
        long secondSession = secondIdentity[0];
        long secondTty = secondIdentity[1];
        if (firstTty != 0L && secondTty != 0L) return firstTty == secondTty;
        return firstSession > 0L && firstSession == secondSession;
    }

    private static boolean isCodexProcess(int processId) {
        File processDirectory = new File("/proc/" + processId);
        if (!processDirectory.isDirectory()) return false;

        try {
            String executable = new File(processDirectory, "exe").getCanonicalPath();
            if (isCodexExecutable(executable)) return true;
        } catch (Exception ignored) {
        }

        byte[] cmdline = readBoundedFile(new File(processDirectory, "cmdline"));
        if (cmdline == null || cmdline.length == 0) return false;
        int end = 0;
        while (end < cmdline.length && cmdline[end] != 0) end++;
        return isCodexExecutable(new String(cmdline, 0, end, StandardCharsets.UTF_8));
    }

    private static boolean isCodexExecutable(@Nullable String executable) {
        if (TextUtils.isEmpty(executable)) return false;
        return "codex".equals(new File(executable).getName().toLowerCase(Locale.ROOT));
    }

    static int parseParentPid(@Nullable String stat) {
        if (TextUtils.isEmpty(stat)) return -1;
        int commandEnd = stat.lastIndexOf(')');
        if (commandEnd < 0 || commandEnd + 2 >= stat.length()) return -1;
        String[] fields = stat.substring(commandEnd + 2).trim().split("\\s+");
        if (fields.length < 2) return -1;
        try {
            return Integer.parseInt(fields[1]);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    @Nullable
    private static long[] parseTerminalIdentity(@Nullable String stat) {
        if (TextUtils.isEmpty(stat)) return null;
        int commandEnd = stat.lastIndexOf(')');
        if (commandEnd < 0 || commandEnd + 2 >= stat.length()) return null;
        String[] fields = stat.substring(commandEnd + 2).trim().split("\\s+");
        if (fields.length < 5) return null;
        try {
            return new long[]{Long.parseLong(fields[3]), Long.parseLong(fields[4])};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int readParentPid(int processId) {
        byte[] stat = readBoundedFile(new File("/proc/" + processId + "/stat"));
        return stat == null ? -1 : parseParentPid(new String(stat, StandardCharsets.UTF_8));
    }

    @Nullable
    private static byte[] readBoundedFile(@NonNull File file) {
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read <= 0) continue;
                if (out.size() + read > MAX_PROC_FILE_BYTES) return null;
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }
}
