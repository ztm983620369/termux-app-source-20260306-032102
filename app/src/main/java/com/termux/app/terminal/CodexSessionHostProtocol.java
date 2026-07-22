package com.termux.app.terminal;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminalsessioncore.CodexRestoreStateMachine;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Versioned native protocol emitted by Codex over the Termux host-control OSC channel. */
final class CodexSessionHostProtocol {

    static final String COMMAND = "codex-session";
    static final int VERSION = 1;

    private static final int MAX_PATH_LENGTH = 4096;
    private static final int MAX_TITLE_LENGTH = 256;
    private static final int MAX_ROLLOUT_HEADER_BYTES = 1024 * 1024;

    static final class Event {
        @NonNull final CodexRestoreStateMachine.HostEvent type;
        @NonNull final String threadId;
        final int processId;
        @NonNull final String workingDirectory;
        @NonNull final String rolloutPath;
        @NonNull final String title;

        Event(@NonNull CodexRestoreStateMachine.HostEvent type,
              @NonNull String threadId,
              int processId,
              @NonNull String workingDirectory,
              @NonNull String rolloutPath,
              @NonNull String title) {
            this.type = type;
            this.threadId = threadId;
            this.processId = processId;
            this.workingDirectory = workingDirectory;
            this.rolloutPath = rolloutPath;
            this.title = title;
        }

        boolean hasDurableRollout() {
            return type != CodexRestoreStateMachine.HostEvent.READY ||
                rolloutMatchesThread(rolloutPath, threadId);
        }
    }

    private CodexSessionHostProtocol() {
    }

    @Nullable
    static Event parse(@Nullable String argument) {
        if (TextUtils.isEmpty(argument)) return null;

        try {
            JSONObject json = new JSONObject(argument);
            if (json.optInt("version", -1) != VERSION) return null;

            CodexRestoreStateMachine.HostEvent type;
            String event = boundedText(json.optString("event", ""), 16).toLowerCase(Locale.ROOT);
            if ("ready".equals(event)) {
                type = CodexRestoreStateMachine.HostEvent.READY;
            } else if ("closed".equals(event)) {
                type = CodexRestoreStateMachine.HostEvent.CLOSED;
            } else {
                return null;
            }

            String threadId = normalizeThreadId(json.optString("thread_id", ""));
            if (TextUtils.isEmpty(threadId)) return null;

            if (type == CodexRestoreStateMachine.HostEvent.CLOSED) {
                return new Event(type, threadId, -1, "", "", "");
            }

            int processId = json.optInt("pid", -1);
            if (processId <= 0) return null;
            String cwd = normalizeAbsolutePath(json.optString("cwd", ""));
            String rolloutPath = normalizeAbsolutePath(json.optString("rollout_path", ""));
            if (TextUtils.isEmpty(rolloutPath)) return null;
            String title = boundedText(json.optString("title", ""), MAX_TITLE_LENGTH);
            return new Event(type, threadId, processId, cwd, rolloutPath, title);
        } catch (Exception ignored) {
            return null;
        }
    }

    @NonNull
    private static String normalizeThreadId(@Nullable String raw) {
        String value = boundedText(raw, 64);
        if (TextUtils.isEmpty(value)) return "";
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    @NonNull
    private static String normalizeAbsolutePath(@Nullable String raw) {
        String value = boundedText(raw, MAX_PATH_LENGTH);
        if (TextUtils.isEmpty(value) || value.indexOf('\0') >= 0) return "";
        File file = new File(value);
        return file.isAbsolute() ? file.getAbsolutePath() : "";
    }

    @NonNull
    private static String boundedText(@Nullable String raw, int maxLength) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > maxLength) value = value.substring(0, maxLength);
        return value;
    }

    static boolean rolloutMatchesThread(@Nullable String rolloutPath, @Nullable String threadId) {
        String normalizedPath = normalizeAbsolutePath(rolloutPath);
        String normalizedThreadId = normalizeThreadId(threadId);
        if (TextUtils.isEmpty(normalizedPath) || TextUtils.isEmpty(normalizedThreadId)) return false;
        return rolloutHeaderMatchesThread(new File(normalizedPath), normalizedThreadId);
    }

    private static boolean rolloutHeaderMatchesThread(@NonNull File rollout, @NonNull String threadId) {
        if (!rollout.isFile() || rollout.length() <= 0) return false;

        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(rollout));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            int value;
            while ((value = in.read()) != -1) {
                if (value == '\n') break;
                if (out.size() >= MAX_ROLLOUT_HEADER_BYTES) return false;
                out.write(value);
            }
            if (out.size() == 0) return false;

            JSONObject record = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            if (!"session_meta".equals(record.optString("type", ""))) return false;
            JSONObject payload = record.optJSONObject("payload");
            if (payload == null) return false;
            return TextUtils.equals(threadId, normalizeThreadId(payload.optString("id", "")));
        } catch (Exception ignored) {
            return false;
        }
    }
}
