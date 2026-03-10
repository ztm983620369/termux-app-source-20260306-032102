package com.termux.terminalsessionruntime;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminalsessioncore.SshTmuxSessionStateMachine;

public final class SshTmuxCommandFactory {

    public static final String DEFAULT_SSH_TMUX_SESSION = "termux";
    public static final String TMUX_LIST_ITEM_PREFIX = "__TMUX_ITEM__|";
    public static final String TMUX_LIST_DONE = "__TMUX_LIST_DONE__";
    public static final String TMUX_SESSION_CREATED = "__TMUX_CREATED__";
    public static final String TMUX_SESSION_KILLED = "__TMUX_KILLED__";
    public static final String TMUX_SESSION_EXISTS = "__TMUX_EXISTS__";
    public static final String TMUX_SESSION_NOT_FOUND = "__TMUX_NOT_FOUND__";

    @NonNull
    public String normalizeTmuxSessionName(@Nullable String raw) {
        String value = SshTmuxSessionStateMachine.normalizeRemoteSessionName(raw);
        return value.isEmpty() ? DEFAULT_SSH_TMUX_SESSION : value;
    }

    @NonNull
    public String normalizeDisplayName(@Nullable String raw, @Nullable String fallback) {
        return SshTmuxSessionStateMachine.normalizeDisplayName(raw, fallback);
    }

    @NonNull
    public String quoteArg(@NonNull String value) {
        if (value.isEmpty()) return "''";
        if (value.matches("[A-Za-z0-9_./:@%+=,-]+")) return value;
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public boolean isSshpassCommand(@NonNull String sshCommand) {
        return sshCommand.trim().startsWith("sshpass ");
    }

    @NonNull
    public String buildSshRemoteExecCommand(@NonNull String sshCommand, @NonNull String remoteCommand) {
        StringBuilder cmd = new StringBuilder(sshCommand);
        if (!isSshpassCommand(sshCommand)) {
            cmd.append(" -o BatchMode=yes");
        }
        cmd.append(" -o ConnectTimeout=8");
        cmd.append(" -o ServerAliveInterval=8 -o ServerAliveCountMax=1");
        cmd.append(" -o StrictHostKeyChecking=accept-new");
        cmd.append(" ").append(quoteArg(remoteCommand));
        return cmd.toString();
    }

    @NonNull
    public String buildTmuxCheckCommand(@NonNull String sshCommand) {
        return buildSshRemoteExecCommand(sshCommand,
            "command -v tmux >/dev/null 2>&1 && echo __TMUX_OK__ || echo __TMUX_MISSING__");
    }

    @NonNull
    public String buildTmuxListSessionsCommand(@NonNull String sshCommand) {
        String remoteList =
            "if command -v tmux >/dev/null 2>&1; then " +
                "tmux list-sessions -F '__TMUX_ITEM__|#{session_name}|#{session_windows}|#{session_attached}|#{" +
                SshTmuxSessionStateMachine.TMUX_DISPLAY_NAME_OPTION + "}' 2>/dev/null || true; " +
                "echo __TMUX_LIST_DONE__; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        return buildSshRemoteExecCommand(sshCommand, remoteList);
    }

    @NonNull
    public String buildTmuxTargetArg(@Nullable String tmuxSession) {
        return quoteArg(normalizeTmuxSessionName(tmuxSession));
    }

    @NonNull
    public String buildTmuxDisplaySyncCommand(@NonNull String tmuxSession, @Nullable String displayName) {
        String encoded = SshTmuxSessionStateMachine.encodeDisplayNameHex(normalizeDisplayName(displayName, tmuxSession));
        return "tmux set-option -q -t " + buildTmuxTargetArg(tmuxSession) + " " +
            SshTmuxSessionStateMachine.TMUX_DISPLAY_NAME_OPTION + " " + quoteArg(encoded) + " >/dev/null 2>&1";
    }

    @NonNull
    public String buildTmuxDisplaySyncRemoteExecCommand(@NonNull String sshCommand, @NonNull String tmuxSession,
                                                        @Nullable String displayName) {
        String target = buildTmuxTargetArg(tmuxSession);
        String remoteSync =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if tmux has-session -t " + target + " 2>/dev/null; then " +
                    buildTmuxDisplaySyncCommand(tmuxSession, displayName) + "; " +
                "else echo __TMUX_NOT_FOUND__; exit 3; fi; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        return buildSshRemoteExecCommand(sshCommand, remoteSync);
    }

    @NonNull
    public String buildTmuxCreateSessionCommand(@NonNull String sshCommand, @NonNull String tmuxSession,
                                                @NonNull String displayName) {
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String target = buildTmuxTargetArg(safeTmuxSession);
        String remoteCreate =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if tmux has-session -t " + target + " 2>/dev/null; then echo __TMUX_EXISTS__; exit 5; fi; " +
                "if tmux new-session -d -s " + target + "; then " +
                    buildTmuxDisplaySyncCommand(safeTmuxSession, displayName) + "; " +
                    "echo __TMUX_CREATED__; " +
                "else exit $?; fi; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        return buildSshRemoteExecCommand(sshCommand, remoteCreate);
    }

    @NonNull
    public String buildTmuxKillSessionCommand(@NonNull String sshCommand, @NonNull String tmuxSession) {
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String target = buildTmuxTargetArg(safeTmuxSession);
        String remoteDestroy =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if tmux has-session -t " + target + " 2>/dev/null; then " +
                    "tmux kill-session -t " + target + " && echo __TMUX_KILLED__; " +
                "else echo __TMUX_NOT_FOUND__; exit 3; fi; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        return buildSshRemoteExecCommand(sshCommand, remoteDestroy);
    }

    @NonNull
    public String buildTmuxInstallCommand(@NonNull String sshCommand) {
        String remoteInstall =
            "if command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y tmux; " +
            "elif command -v dnf >/dev/null 2>&1; then sudo dnf install -y tmux; " +
            "elif command -v yum >/dev/null 2>&1; then sudo yum install -y tmux; " +
            "elif command -v pacman >/dev/null 2>&1; then sudo pacman -Sy --noconfirm tmux; " +
            "elif command -v apk >/dev/null 2>&1; then sudo apk add tmux; " +
            "else echo __NO_PKG_MANAGER__; exit 127; fi";
        return sshCommand + " -tt \"" + remoteInstall.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @NonNull
    public String buildReconnectLoopCommand(@NonNull String sshCommand, @NonNull String tmuxSession,
                                            @NonNull String displayName, int preloadLines) {
        sshCommand = sanitizeSshBootstrapCommand(sshCommand);
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String target = buildTmuxTargetArg(safeTmuxSession);
        String remoteEnsure =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if ! tmux has-session -t " + target + " 2>/dev/null; then tmux new-session -d -s " + target + " || exit $?; fi; " +
                buildTmuxDisplaySyncCommand(safeTmuxSession, displayName) + "; " +
                "echo __TMUX_READY__; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        String remoteAttach =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if tmux has-session -t " + target + " 2>/dev/null; then " +
                    buildTmuxAttachOnlyCommand(safeTmuxSession, displayName, preloadLines) + "; " +
                "else echo __TMUX_GONE__; exit 43; fi; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        return "init=0; while true; do " +
            "if [ \"$init\" -eq 0 ]; then " +
            sshCommand + " -tt " + quoteArg(remoteEnsure) + "; " +
            "ready=$?; " +
            "if [ \"$ready\" -eq 42 ]; then echo \"[ssh-persist] tmux missing on server\"; sleep 8; continue; fi; " +
            "if [ \"$ready\" -ne 0 ]; then echo \"[ssh-persist] bootstrap failed ($ready), retrying in 2s...\"; sleep 2; continue; fi; " +
            "init=1; fi; " +
            sshCommand + " -tt " + quoteArg(remoteAttach) + "; " +
            "code=$?; " +
            "if [ \"$code\" -eq 42 ]; then echo \"[ssh-persist] tmux missing on server\"; sleep 8; " +
            "elif [ \"$code\" -eq 43 ]; then echo \"[ssh-persist] remote tmux session removed, stop reconnect loop\"; break; " +
            "else echo \"[ssh-persist] disconnected ($code), reconnecting in 2s...\"; sleep 2; fi; " +
            "done";
    }

    @NonNull
    public String buildTmuxAttachOnlyCommand(@NonNull String tmuxSession, @NonNull String displayName, int preloadLines) {
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String target = buildTmuxTargetArg(safeTmuxSession);
        return buildTmuxDisplaySyncCommand(safeTmuxSession, displayName) + "; " +
            "tmux set-option -t " + target + " mouse on >/dev/null 2>&1; " +
            "tmux set-window-option -t " + target + " alternate-screen off >/dev/null 2>&1; " +
            "tmux set-option -t " + target + " history-limit " + preloadLines + " >/dev/null 2>&1; " +
            "pane=$(tmux display-message -p -t " + target + " '#{session_name}:#{window_index}.#{pane_index}' 2>/dev/null); " +
            "[ -n \"$pane\" ] && tmux capture-pane -p -t \"$pane\" -S -" + preloadLines + " 2>/dev/null || true; " +
            "tmux attach-session -t " + target;
    }

    @NonNull
    public String buildTmuxEnsureAndAttachCommand(@NonNull String tmuxSession, @NonNull String displayName,
                                                  int preloadLines) {
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String target = buildTmuxTargetArg(safeTmuxSession);
        return "tmux has-session -t " + target + " 2>/dev/null || tmux new-session -d -s " + target +
            "; " + buildTmuxAttachOnlyCommand(safeTmuxSession, displayName, preloadLines);
    }

    @Nullable
    public String extractSshCommandFromReconnectLoop(@Nullable String script) {
        if (TextUtils.isEmpty(script)) return null;
        String s = script.trim();
        if (!s.contains("while true; do") || !s.contains("[ssh-persist]")) return null;
        int loopStart = s.indexOf("while true; do");
        if (loopStart < 0) return null;

        int sshStart = s.indexOf("sshpass ", loopStart);
        int plainSshStart = s.indexOf("ssh ", loopStart);
        if (sshStart < 0 || (plainSshStart >= 0 && plainSshStart < sshStart)) sshStart = plainSshStart;
        if (sshStart < 0) return null;

        int end = s.indexOf(" -tt ", sshStart);
        if (end <= sshStart) return null;
        String command = s.substring(sshStart, end).trim();
        return command.isEmpty() ? null : command;
    }

    @NonNull
    public String sanitizeSshBootstrapCommand(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        String extracted = extractSshCommandFromReconnectLoop(value);
        return TextUtils.isEmpty(extracted) ? value : extracted;
    }

    @NonNull
    public String unquoteShellToken(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return normalizeTmuxSessionName(null);
        if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
            value = value.replace("'\"'\"'", "'");
        }
        return normalizeTmuxSessionName(value);
    }
}
