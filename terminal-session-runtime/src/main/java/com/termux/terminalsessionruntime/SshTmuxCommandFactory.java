package com.termux.terminalsessionruntime;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;
import com.termux.sshconnectioncore.OpenSshCommand;
import com.termux.terminalsessioncore.SshTmuxSessionStateMachine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

public final class SshTmuxCommandFactory {

    public static final String DEFAULT_SSH_TMUX_SESSION = "termux";
    public static final String TMUX_LIST_ITEM_PREFIX = "__TMUX_ITEM__|";
    public static final String TMUX_LIST_DONE = "__TMUX_LIST_DONE__";
    public static final String TMUX_SESSION_CREATED = "__TMUX_CREATED__";
    public static final String TMUX_SESSION_KILLED = "__TMUX_KILLED__";
    public static final String TMUX_SESSION_EXISTS = "__TMUX_EXISTS__";
    public static final String TMUX_SESSION_NOT_FOUND = "__TMUX_NOT_FOUND__";
    private static final String INVALID_SSH_COMMAND =
        "echo '[ssh-persist] invalid SSH profile command' >&2; exit 64";
    private static final String RECONNECT_PROTOCOL_MARKER = "ssh_loop_protocol=8";
    private static final String TRANSPORT_SCOPE_MARKER = "transport_scope_hash=";
    // OpenSSH appends a temporary ".<16 random chars>" suffix before binding a mux socket.
    // Keep the configured path well below Android's 108-byte sockaddr_un.sun_path limit.
    static final int MAX_CONTROL_PATH_BYTES = 80;
    private static final int OPENSSH_TEMPORARY_CONTROL_PATH_SUFFIX_BYTES = 17;
    private static final int UNIX_DOMAIN_SOCKET_PATH_BYTES = 108;
    private static final int TRANSPORT_IDENTIFIER_DIGEST_BYTES = 16;

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
        return OpenSshCommand.quoteShellToken(value);
    }

    public boolean isSshpassCommand(@NonNull String sshCommand) {
        OpenSshCommand parsed = OpenSshCommand.tryParse(sshCommand);
        return parsed != null && parsed.usesSshpass();
    }

    public boolean isCurrentReconnectProtocol(@Nullable String script) {
        if (isEmpty(script)) return false;
        String value = script.trim();
        return value.equals(RECONNECT_PROTOCOL_MARKER) || value.startsWith(RECONNECT_PROTOCOL_MARKER + ";");
    }

    public boolean isReconnectTransportScope(@Nullable String script, @Nullable String transportScope) {
        if (isEmpty(script)) return false;
        String expectedPrefix = RECONNECT_PROTOCOL_MARKER + "; " + TRANSPORT_SCOPE_MARKER +
            buildTransportScopeHash(transportScope) + ";";
        return script.trim().startsWith(expectedPrefix);
    }

    public boolean isReconnectTransportIdentity(@Nullable String script, @Nullable String sshCommand,
                                                @Nullable String transportScope) {
        if (!isCurrentReconnectProtocol(script) || !isReconnectTransportScope(script, transportScope)) {
            return false;
        }
        String expectedCommand = sanitizeSshBootstrapCommand(sshCommand);
        return !expectedCommand.isEmpty() && expectedCommand.equals(extractSshCommandFromReconnectLoop(script));
    }

    @NonNull
    public String buildSshRemoteExecCommand(@NonNull String sshCommand, @NonNull String remoteCommand) {
        return buildSshRemoteExecCommand(sshCommand, remoteCommand, false);
    }

    /**
     * Build a managed SSH command for another terminal multiplexer while retaining the exact
     * transport hardening and ControlMaster scoping used by the tmux runtime.
     */
    @NonNull
    public String buildManagedSshRemoteExecCommand(@NonNull String sshCommand,
                                                    @NonNull String remoteCommand,
                                                    boolean forceTty,
                                                    @Nullable String transportScope) {
        return buildSshRemoteExecCommand(sshCommand, remoteCommand, forceTty, transportScope);
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
        // Some deployed tmux builds accept '=name' for lookup but reject it in set-option and
        // set-window-option. Callers first prove the resolved session name is exact, then use the
        // portable literal target for the guarded operation and attach.
        return quoteArg(normalizeTmuxSessionName(tmuxSession));
    }

    @NonNull
    public String buildTmuxSessionNameArg(@Nullable String tmuxSession) {
        return quoteArg(normalizeTmuxSessionName(tmuxSession));
    }

    @NonNull
    public String buildTmuxExactSessionCheck(@NonNull String tmuxSession) {
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String target = buildTmuxTargetArg(safeTmuxSession);
        // A portable target may resolve by prefix or glob. Compare the resolved name before any
        // create, attach, option update, or destroy operation is allowed to use it.
        return "[ \"$(tmux display-message -p -t " + target +
            " '#{session_name}' 2>/dev/null)\" = " + quoteArg(safeTmuxSession) + " ]";
    }

    @NonNull
    public String buildTmuxDisplaySyncCommand(@NonNull String tmuxSession, @Nullable String displayName) {
        String encoded = SshTmuxSessionStateMachine.encodeDisplayNameHex(normalizeDisplayName(displayName, tmuxSession));
        return "tmux set-option -q -t " + buildTmuxTargetArg(tmuxSession) + " " +
            SshTmuxSessionStateMachine.TMUX_DISPLAY_NAME_OPTION + " " + quoteArg(encoded) + " >/dev/null 2>&1";
    }

    /** Enable tmux's in-band mouse protocol for a previously verified session target. */
    @NonNull
    public String buildTmuxMouseEnableCommand(@NonNull String tmuxSession) {
        return "tmux set-option -t " + buildTmuxTargetArg(tmuxSession) +
            " mouse on >/dev/null 2>&1";
    }

    @NonNull
    public String buildTmuxDisplaySyncRemoteExecCommand(@NonNull String sshCommand, @NonNull String tmuxSession,
                                                        @Nullable String displayName) {
        String remoteSync =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if " + buildTmuxExactSessionCheck(tmuxSession) + "; then " +
                    buildTmuxDisplaySyncCommand(tmuxSession, displayName) + "; " +
                "else echo __TMUX_NOT_FOUND__; exit 3; fi; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        return buildSshRemoteExecCommand(sshCommand, remoteSync);
    }

    @NonNull
    public String buildTmuxCreateSessionCommand(@NonNull String sshCommand, @NonNull String tmuxSession,
                                                @NonNull String displayName) {
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String sessionName = buildTmuxSessionNameArg(safeTmuxSession);
        String remoteCreate =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if " + buildTmuxExactSessionCheck(safeTmuxSession) + "; then echo __TMUX_EXISTS__; exit 5; fi; " +
                "if tmux new-session -d -s " + sessionName + "; then " +
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
                "if " + buildTmuxExactSessionCheck(safeTmuxSession) + "; then " +
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
        return buildSshRemoteExecCommand(sshCommand, remoteInstall, true);
    }

    @NonNull
    public String buildReconnectLoopCommand(@NonNull String sshCommand, @NonNull String tmuxSession,
                                            @NonNull String displayName, int preloadLines) {
        return buildReconnectLoopCommand(sshCommand, tmuxSession, displayName, preloadLines, null, true);
    }

    @NonNull
    public String buildReconnectLoopCommand(@NonNull String sshCommand, @NonNull String tmuxSession,
                                            @NonNull String displayName, int preloadLines,
                                            @Nullable String transportScope) {
        return buildReconnectLoopCommand(
            sshCommand, tmuxSession, displayName, preloadLines, transportScope, true);
    }

    /**
     * Build the managed SSH/tmux client loop.
     *
     * <p>Only an explicit user creation flow may set {@code createIfMissing}. Restored records
     * must attach to the original remote session or stop: treating a missing session as a reason
     * to create one would resurrect a session the user deleted while Termux was offline.</p>
     */
    @NonNull
    public String buildReconnectLoopCommand(@NonNull String sshCommand, @NonNull String tmuxSession,
                                            @NonNull String displayName, int preloadLines,
                                            @Nullable String transportScope, boolean createIfMissing) {
        sshCommand = sanitizeSshBootstrapCommand(sshCommand);
        if (sshCommand.isEmpty()) return INVALID_SSH_COMMAND;
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String sessionName = buildTmuxSessionNameArg(safeTmuxSession);
        String remoteBootstrap = createIfMissing
            ?
            "if command -v tmux >/dev/null 2>&1; then " +
                "if ! " + buildTmuxExactSessionCheck(safeTmuxSession) + "; then tmux new-session -d -s " + sessionName + " || exit $?; fi; " +
                buildTmuxDisplaySyncCommand(safeTmuxSession, displayName) + "; " +
                "echo __TMUX_READY__; " +
            "else echo __TMUX_MISSING__; exit 42; fi"
            :
            "if command -v tmux >/dev/null 2>&1; then " +
                "if " + buildTmuxExactSessionCheck(safeTmuxSession) + "; then echo __TMUX_READY__; " +
                "else echo __TMUX_GONE__; exit 43; fi; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        String remoteInitialAttach =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if " + buildTmuxExactSessionCheck(safeTmuxSession) + "; then " +
                    buildTmuxAttachClientCommand(safeTmuxSession, preloadLines, true) + "; " +
                "else echo __TMUX_GONE__; exit 43; fi; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        String remoteReconnectAttach =
            "if command -v tmux >/dev/null 2>&1; then " +
                "if " + buildTmuxExactSessionCheck(safeTmuxSession) + "; then " +
                    buildTmuxAttachClientCommand(safeTmuxSession, preloadLines, false) + "; " +
                "else echo __TMUX_GONE__; exit 43; fi; " +
            "else echo __TMUX_MISSING__; exit 42; fi";
        String bootstrapCommand = buildSshRemoteExecCommand(sshCommand, remoteBootstrap, false, transportScope);
        String initialAttachCommand = buildSshRemoteExecCommand(
            sshCommand, remoteInitialAttach, true, transportScope);
        String reconnectAttachCommand = buildSshRemoteExecCommand(
            sshCommand, remoteReconnectAttach, true, transportScope);
        return RECONNECT_PROTOCOL_MARKER + "; " + TRANSPORT_SCOPE_MARKER +
            buildTransportScopeHash(transportScope) + "; ssh_base_hex=" + encodeHex(sshCommand) + "; " +
            "bootstrap_create=" + (createIfMissing ? "1" : "0") + "; init=0; preload=1; failures=0; " +
            "retry_delay() { case \"$failures\" in 0) delay=0;; 1) delay=0.25;; 2) delay=0.5;; " +
            "3) delay=1;; 4) delay=2;; 5) delay=5;; *) delay=10;; esac; failures=$((failures + 1)); }; " +
            "while true; do " +
            "if [ \"$init\" -eq 0 ]; then " +
            bootstrapCommand + "; " +
            "ready=$?; " +
            "if [ \"$ready\" -eq 42 ]; then echo \"[ssh-persist] tmux missing on server\"; sleep 8; continue; fi; " +
            "if [ \"$ready\" -eq 43 ]; then echo \"[ssh-persist] remote tmux session removed, stop reconnect loop\"; exit 43; fi; " +
            "if [ \"$ready\" -ne 0 ]; then retry_delay; echo \"[ssh-persist] bootstrap failed ($ready), retrying in ${delay}s...\"; " +
            "[ \"$delay\" = 0 ] || sleep \"$delay\"; continue; fi; " +
            "init=1; failures=0; fi; " +
            "started=$SECONDS; if [ \"$preload\" -eq 1 ]; then " + initialAttachCommand + "; " +
            "code=$?; preload=0; else " + reconnectAttachCommand + "; code=$?; fi; " +
            "if [ \"$code\" -eq 42 ]; then echo \"[ssh-persist] tmux missing on server\"; sleep 8; " +
            "elif [ \"$code\" -eq 43 ]; then echo \"[ssh-persist] remote tmux session removed, stop reconnect loop\"; exit 43; " +
            "elif [ \"$code\" -eq 126 ] || [ \"$code\" -eq 127 ]; then echo \"[ssh-persist] local SSH command unavailable ($code)\"; break; " +
            "else lived=$((SECONDS - started)); [ \"$lived\" -ge 10 ] && failures=0; retry_delay; " +
            "echo \"[ssh-persist] disconnected ($code), reconnecting in ${delay}s...\"; " +
            "[ \"$delay\" = 0 ] || sleep \"$delay\"; fi; " +
            "done";
    }

    @NonNull
    public String buildTmuxAttachOnlyCommand(@NonNull String tmuxSession, @NonNull String displayName, int preloadLines) {
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        return buildTmuxDisplaySyncCommand(safeTmuxSession, displayName) + "; " +
            buildTmuxAttachClientCommand(safeTmuxSession, preloadLines, true);
    }

    @NonNull
    private String buildTmuxAttachClientCommand(@NonNull String tmuxSession, int preloadLines,
                                                 boolean preloadHistory) {
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String target = buildTmuxTargetArg(safeTmuxSession);
        int historyLimit = Math.max(1000, Math.min(200000, preloadLines));
        String historySnapshot = preloadHistory
            ? "pane=$(tmux display-message -p -t " + target +
                " '#{session_name}:#{window_index}.#{pane_index}' 2>/dev/null); " +
                "[ -n \"$pane\" ] && tmux capture-pane -p -t \"$pane\" -S -" + historyLimit +
                " 2>/dev/null || true; "
            : "";
        return buildTmuxMouseEnableCommand(safeTmuxSession) + " || exit $?; " +
            "tmux set-window-option -t " + target + " alternate-screen on >/dev/null 2>&1 || exit $?; " +
            "tmux set-window-option -t " + target + " history-limit " + historyLimit + " >/dev/null 2>&1 || exit $?; " +
            historySnapshot +
            "if tmux -T sync display-message -p -t " + target + " '#{version}' >/dev/null 2>&1; then " +
                "exec tmux -T sync attach-session -t " + target + "; " +
            "else exec tmux attach-session -t " + target + "; fi";
    }

    @NonNull
    public String buildTmuxEnsureAndAttachCommand(@NonNull String tmuxSession, @NonNull String displayName,
                                                  int preloadLines) {
        String safeTmuxSession = normalizeTmuxSessionName(tmuxSession);
        String sessionName = buildTmuxSessionNameArg(safeTmuxSession);
        return buildTmuxExactSessionCheck(safeTmuxSession) + " || tmux new-session -d -s " + sessionName +
            "; " + buildTmuxAttachOnlyCommand(safeTmuxSession, displayName, preloadLines);
    }

    @Nullable
    public String extractSshCommandFromReconnectLoop(@Nullable String script) {
        if (isEmpty(script)) return null;
        String s = script.trim();
        if (!s.contains("while true; do") || !s.contains("[ssh-persist]")) return null;
        int hexStart = s.indexOf("ssh_base_hex=");
        if (hexStart >= 0) {
            hexStart += "ssh_base_hex=".length();
            int hexEnd = s.indexOf(';', hexStart);
            if (hexEnd > hexStart) {
                String decoded = decodeHex(s.substring(hexStart, hexEnd).trim());
                OpenSshCommand parsed = OpenSshCommand.tryParse(decoded);
                if (parsed != null) return parsed.renderBaseCommand();
            }
        }
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
        OpenSshCommand parsed = OpenSshCommand.tryParse(isEmpty(extracted) ? value : extracted);
        return parsed == null ? "" : parsed.renderBaseCommand();
    }

    @NonNull
    public String unquoteShellToken(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return normalizeTmuxSessionName(null);
        if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
            value = value.replace("'\"'\"'", "'");
        }
        // Reconnect scripts persist exact tmux targets. Remove only the target marker so a real
        // session name beginning with '=' remains representable as '==name' in the script.
        if (value.startsWith("=")) value = value.substring(1);
        return normalizeTmuxSessionName(value);
    }

    @NonNull
    private String buildSshRemoteExecCommand(@NonNull String sshCommand, @NonNull String remoteCommand,
                                             boolean forceTty) {
        return buildSshRemoteExecCommand(sshCommand, remoteCommand, forceTty, null);
    }

    @NonNull
    private String buildSshRemoteExecCommand(@NonNull String sshCommand, @NonNull String remoteCommand,
                                             boolean forceTty, @Nullable String transportScope) {
        OpenSshCommand command = OpenSshCommand.tryParse(sshCommand);
        if (command == null) return INVALID_SSH_COMMAND;

        ArrayList<String> options = new ArrayList<>();
        addOptionIfMissing(command, options, "BatchMode", "yes", !command.usesSshpass());
        addOptionIfMissing(command, options, "ConnectTimeout", "5", true);
        addOptionIfMissing(command, options, "ConnectionAttempts", "1", true);
        addOptionIfMissing(command, options, "ServerAliveInterval", "3", true);
        addOptionIfMissing(command, options, "ServerAliveCountMax", "2", true);
        addOptionIfMissing(command, options, "TCPKeepAlive", "yes", true);
        addOptionIfMissing(command, options, "StrictHostKeyChecking", "yes", true);

        boolean customControlConfiguration = command.hasOption("ControlMaster") ||
            command.hasOption("ControlPersist") || command.hasOption("ControlPath");
        if (!customControlConfiguration) {
            String controlPath = buildDefaultControlPath(command, transportScope);
            int controlPathBytes = controlPath.getBytes(StandardCharsets.UTF_8).length;
            if (controlPathBytes <= MAX_CONTROL_PATH_BYTES &&
                controlPathBytes + OPENSSH_TEMPORARY_CONTROL_PATH_SUFFIX_BYTES < UNIX_DOMAIN_SOCKET_PATH_BYTES) {
                options.add("-o");
                options.add("ControlMaster=auto");
                options.add("-o");
                options.add("ControlPersist=300");
                options.add("-o");
                options.add("ControlPath=" + controlPath);
            }
        }
        return command.renderRemoteCommand(options, forceTty, remoteCommand);
    }

    @NonNull
    String buildDefaultControlPath(@NonNull OpenSshCommand command) {
        return buildDefaultControlPath(command, null);
    }

    @NonNull
    String buildDefaultControlPath(@NonNull OpenSshCommand command, @Nullable String transportScope) {
        // stableId hashes the complete canonical base command, so it isolates different users,
        // endpoints, credentials, identities, proxy settings, and other profile options.
        if (transportScope == null || transportScope.isEmpty()) {
            return TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH + "/tmx-" + command.stableId();
        }
        return TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH + "/tmx-" +
            buildScopedControlPathId(command, transportScope);
    }

    @NonNull
    private String buildScopedControlPathId(@NonNull OpenSshCommand command,
                                            @NonNull String transportScope) {
        MessageDigest digest = newSha256Digest();
        updateLengthPrefixed(digest, "termux:ssh-control-path:v1");
        updateLengthPrefixed(digest, command.renderBaseCommand());
        updateLengthPrefixed(digest, transportScope);
        return encodeDigestPrefix(digest.digest());
    }

    @NonNull
    private String buildTransportScopeHash(@Nullable String transportScope) {
        MessageDigest digest = newSha256Digest();
        updateLengthPrefixed(digest, "termux:ssh-transport-scope:v1");
        updateLengthPrefixed(digest, transportScope == null ? "" : transportScope);
        return encodeDigestPrefix(digest.digest());
    }

    @NonNull
    private MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void updateLengthPrefixed(@NonNull MessageDigest digest, @NonNull String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    @NonNull
    private String encodeDigestPrefix(@NonNull byte[] value) {
        StringBuilder out = new StringBuilder(TRANSPORT_IDENTIFIER_DIGEST_BYTES * 2);
        for (int i = 0; i < TRANSPORT_IDENTIFIER_DIGEST_BYTES; i++) {
            out.append(Character.forDigit((value[i] >>> 4) & 0x0f, 16));
            out.append(Character.forDigit(value[i] & 0x0f, 16));
        }
        return out.toString();
    }

    private void addOptionIfMissing(@NonNull OpenSshCommand command, @NonNull ArrayList<String> options,
                                    @NonNull String name, @NonNull String value, boolean enabled) {
        if (!enabled || command.hasOption(name)) return;
        options.add("-o");
        options.add(name + "=" + value);
    }

    @NonNull
    private String encodeHex(@NonNull String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >>> 4) & 0x0f, 16));
            out.append(Character.forDigit(b & 0x0f, 16));
        }
        return out.toString();
    }

    @Nullable
    private String decodeHex(@NonNull String value) {
        if ((value.length() & 1) != 0 || !value.matches("[0-9A-Fa-f]+")) return null;
        byte[] bytes = new byte[value.length() / 2];
        try {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isEmpty(@Nullable CharSequence value) {
        return value == null || value.length() == 0;
    }
}
