package com.termux.terminalsessionruntime;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.sshconnectioncore.OpenSshCommand;
import java.nio.charset.StandardCharsets;

/** Builds the small, in-band protocol used to manage remote Zellij sessions over SSH. */
public final class SshZellijCommandFactory {

    public static final String DEFAULT_SESSION = "termux";
    public static final String LIST_ITEM_PREFIX = "__ZELLIJ_ITEM__|";
    public static final String LIST_DONE = "__ZELLIJ_LIST_DONE__";
    public static final String SESSION_CREATED = "__ZELLIJ_CREATED__";
    public static final String SESSION_EXISTS = "__ZELLIJ_EXISTS__";
    public static final String SESSION_DESTROYED = "__ZELLIJ_DESTROYED__";
    public static final String SESSION_NOT_FOUND = "__ZELLIJ_NOT_FOUND__";
    public static final String ZELLIJ_OK = "__ZELLIJ_OK__";
    public static final String ZELLIJ_MISSING = "__ZELLIJ_MISSING__";
    public static final int REMOTE_SESSION_GONE_EXIT_STATUS = 43;

    private static final String RECONNECT_PROTOCOL_MARKER = "ssh_zellij_protocol=1";
    private static final String SSH_HEX_MARKER = "ssh_base_hex=";
    private static final String SESSION_HEX_MARKER = "zellij_session_hex=";
    private static final String PURE_TERMINAL_LAYOUT = "layout { pane borderless=true; }";
    private static final String PURE_TERMINAL_ATTACH_OPTIONS =
        " options --pane-frames false --show-startup-tips false --show-release-notes false";
    private static final String INVALID_SSH_COMMAND =
        "echo '[ssh-zellij] invalid SSH profile command' >&2; exit 64";

    private final SshTmuxCommandFactory transportFactory = new SshTmuxCommandFactory();

    @NonNull
    public String normalizeSessionName(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.isEmpty()) {
            StringBuilder exactName = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == 0 || ch == '\r' || ch == '\n' || Character.isISOControl(ch)) continue;
                exactName.append(ch);
            }
            value = exactName.toString().trim();
        }
        return value.isEmpty() ? DEFAULT_SESSION : value;
    }

    @NonNull
    public String normalizeDisplayName(@Nullable String raw, @Nullable String fallback) {
        String value = raw == null ? "" : raw.trim();
        if (!value.isEmpty()) return value;
        value = fallback == null ? "" : fallback.trim();
        return value.isEmpty() ? "Zellij" : value;
    }

    @NonNull
    public String sanitizeSshBootstrapCommand(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        String extracted = extractSshCommandFromReconnectLoop(value);
        OpenSshCommand parsed = OpenSshCommand.tryParse(extracted == null ? value : extracted);
        return parsed == null ? "" : parsed.renderBaseCommand();
    }

    @NonNull
    public String buildCheckCommand(@NonNull String sshCommand) {
        return transportFactory.buildSshRemoteExecCommand(sshCommand,
            "command -v zellij >/dev/null 2>&1 && echo " + ZELLIJ_OK +
                " || { echo " + ZELLIJ_MISSING + "; exit 42; }");
    }

    @NonNull
    public String buildListSessionsCommand(@NonNull String sshCommand) {
        String remote =
            "if command -v zellij >/dev/null 2>&1; then " +
                "zellij list-sessions --short --no-formatting 2>/dev/null | " +
                    "while IFS= read -r zellij_name; do " +
                        "[ -n \"$zellij_name\" ] && printf '" + LIST_ITEM_PREFIX + "%s\\n' \"$zellij_name\"; " +
                    "done; " +
                "echo " + LIST_DONE + "; " +
            "else echo " + ZELLIJ_MISSING + "; exit 42; fi";
        return transportFactory.buildSshRemoteExecCommand(sshCommand, remote);
    }

    @NonNull
    public String buildCreateSessionCommand(@NonNull String sshCommand, @NonNull String sessionName) {
        String safeSession = normalizeSessionName(sessionName);
        String remote =
            "if command -v zellij >/dev/null 2>&1; then " +
                "if " + buildExactSessionCheck(safeSession) + "; then " +
                    "echo " + SESSION_EXISTS + "; " +
                "elif zellij --layout-string " + quoteArg(PURE_TERMINAL_LAYOUT) +
                    " attach --create-background -- " + quoteArg(safeSession) + "; then " +
                    "echo " + SESSION_CREATED + "; " +
                "else exit $?; fi; " +
            "else echo " + ZELLIJ_MISSING + "; exit 42; fi";
        return transportFactory.buildSshRemoteExecCommand(sshCommand, remote);
    }

    @NonNull
    public String buildDestroySessionCommand(@NonNull String sshCommand, @NonNull String sessionName) {
        String safeSession = normalizeSessionName(sessionName);
        String remote =
            "if command -v zellij >/dev/null 2>&1; then " +
                "if " + buildExactSessionCheck(safeSession) + "; then " +
                    "zellij delete-session --force -- " + quoteArg(safeSession) +
                        " && echo " + SESSION_DESTROYED + "; " +
                "else echo " + SESSION_NOT_FOUND + "; fi; " +
            "else echo " + ZELLIJ_MISSING + "; exit 42; fi";
        return transportFactory.buildSshRemoteExecCommand(sshCommand, remote);
    }

    /**
     * Build a reconnecting client loop. It never recreates a missing remote session: only the
     * explicit create action is allowed to do that, matching the tmux recovery contract.
     */
    @NonNull
    public String buildReconnectLoopCommand(@NonNull String sshCommand, @NonNull String sessionName) {
        String safeSsh = sanitizeSshBootstrapCommand(sshCommand);
        if (safeSsh.isEmpty()) return INVALID_SSH_COMMAND;
        String safeSession = normalizeSessionName(sessionName);
        String remoteAttach =
            "if command -v zellij >/dev/null 2>&1; then " +
                "if " + buildExactSessionCheck(safeSession) + "; then " +
                    "exec " + buildPureTerminalAttachCommand(safeSession) + "; " +
                "else echo __ZELLIJ_GONE__; exit " + REMOTE_SESSION_GONE_EXIT_STATUS + "; fi; " +
            "else echo " + ZELLIJ_MISSING + "; exit 42; fi";
        String attach = transportFactory.buildManagedSshRemoteExecCommand(
            safeSsh, remoteAttach, true, "zellij:" + safeSession);
        return RECONNECT_PROTOCOL_MARKER + "; " +
            SSH_HEX_MARKER + encodeHex(safeSsh) + "; " +
            SESSION_HEX_MARKER + encodeHex(safeSession) + "; failures=0; " +
            "retry_delay() { case \"$failures\" in 0) delay=0;; 1) delay=0.25;; 2) delay=0.5;; " +
            "3) delay=1;; 4) delay=2;; 5) delay=5;; *) delay=10;; esac; failures=$((failures + 1)); }; " +
            "while true; do started=$SECONDS; " + attach + "; code=$?; " +
            "if [ \"$code\" -eq 42 ]; then echo \"[ssh-zellij] Zellij is missing on server\"; sleep 8; " +
            "elif [ \"$code\" -eq " + REMOTE_SESSION_GONE_EXIT_STATUS +
                " ]; then echo \"[ssh-zellij] remote session removed; stopping\"; exit " +
                REMOTE_SESSION_GONE_EXIT_STATUS + "; " +
            "elif [ \"$code\" -eq 126 ] || [ \"$code\" -eq 127 ]; then " +
                "echo \"[ssh-zellij] local SSH command unavailable ($code)\"; break; " +
            "else lived=$((SECONDS - started)); [ \"$lived\" -ge 10 ] && failures=0; retry_delay; " +
                "echo \"[ssh-zellij] disconnected ($code), reconnecting in ${delay}s...\"; " +
                "[ \"$delay\" = 0 ] || sleep \"$delay\"; fi; done";
    }

    public boolean isReconnectLoop(@Nullable String script) {
        return script != null && script.trim().startsWith(RECONNECT_PROTOCOL_MARKER + ";");
    }

    @Nullable
    public String extractSessionFromReconnectLoop(@Nullable String script) {
        return extractHexField(script, SESSION_HEX_MARKER, true);
    }

    @Nullable
    public String extractSshCommandFromReconnectLoop(@Nullable String script) {
        return extractHexField(script, SSH_HEX_MARKER, false);
    }

    @NonNull
    private String buildExactSessionCheck(@NonNull String sessionName) {
        return "zellij list-sessions --short --no-formatting 2>/dev/null | " +
            "{ while IFS= read -r zellij_name; do " +
                "[ \"$zellij_name\" = " + quoteArg(normalizeSessionName(sessionName)) + " ] && exit 0; " +
            "done; exit 1; }";
    }

    @NonNull
    private String buildPureTerminalAttachCommand(@NonNull String sessionName) {
        String safeSession = normalizeSessionName(sessionName);
        // `options` is an attach subcommand, so the usual `-- SESSION` form cannot carry it.
        // Keep unusual option-like/reserved remote names attachable without treating them as CLI
        // switches; ordinary and all app-created names get the idempotent chrome-off options.
        if (safeSession.startsWith("-") || "options".equals(safeSession) || "help".equals(safeSession)) {
            return "zellij attach -- " + quoteArg(safeSession);
        }
        return "zellij attach " + quoteArg(safeSession) + PURE_TERMINAL_ATTACH_OPTIONS;
    }

    @NonNull
    private String quoteArg(@NonNull String value) {
        return OpenSshCommand.quoteShellToken(value);
    }

    @NonNull
    private String encodeHex(@NonNull String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte valueByte : bytes) {
            out.append(Character.forDigit((valueByte >>> 4) & 0x0f, 16));
            out.append(Character.forDigit(valueByte & 0x0f, 16));
        }
        return out.toString();
    }

    @Nullable
    private String extractHexField(@Nullable String script, @NonNull String marker, boolean session) {
        if (!isReconnectLoop(script)) return null;
        int start = script.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = script.indexOf(';', start);
        if (end <= start) return null;
        String decoded = decodeHex(script.substring(start, end).trim());
        if (decoded == null) return null;
        if (session) return normalizeSessionName(decoded);
        OpenSshCommand parsed = OpenSshCommand.tryParse(decoded);
        return parsed == null ? null : parsed.renderBaseCommand();
    }

    @Nullable
    private String decodeHex(@NonNull String value) {
        if (value.isEmpty() || (value.length() & 1) != 0 || !value.matches("[0-9A-Fa-f]+")) return null;
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
}
