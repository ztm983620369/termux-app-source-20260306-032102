package com.termux.sessionsync;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Central trace sink for session-sync-core.
 * All cross-module state transitions should be recorded here for diagnosis.
 */
public final class SessionSyncTracer {

    private static final SessionSyncTracer INSTANCE = new SessionSyncTracer();
    private static final String LOG_TAG = "SessionSyncTracer";
    private static final int MAX_EVENTS = 500;
    private static final long MAX_TRACE_FILE_BYTES = 1_024_000L;

    private final Object lock = new Object();
    private final ArrayDeque<SessionSyncTraceEvent> events = new ArrayDeque<>();

    @Nullable
    private File traceFile;

    private SessionSyncTracer() {
    }

    @NonNull
    public static SessionSyncTracer getInstance() {
        return INSTANCE;
    }

    public void initialize(@NonNull Context context) {
        synchronized (lock) {
            if (traceFile != null) return;
            try {
                File root = new File(context.getApplicationContext().getFilesDir(), ".termux/session-sync");
                if (!root.exists()) root.mkdirs();
                traceFile = new File(root, "trace.log");
            } catch (Throwable ignored) {
                traceFile = null;
            }
        }
    }

    public void debug(@Nullable Context context, @NonNull String source, @NonNull String action,
                      @Nullable String sessionKey, @NonNull String message, @Nullable String detail) {
        trace(context, SessionSyncTraceLevel.DEBUG, source, action, sessionKey, message, detail);
    }

    public void info(@Nullable Context context, @NonNull String source, @NonNull String action,
                     @Nullable String sessionKey, @NonNull String message, @Nullable String detail) {
        trace(context, SessionSyncTraceLevel.INFO, source, action, sessionKey, message, detail);
    }

    public void warn(@Nullable Context context, @NonNull String source, @NonNull String action,
                     @Nullable String sessionKey, @NonNull String message, @Nullable String detail) {
        trace(context, SessionSyncTraceLevel.WARN, source, action, sessionKey, message, detail);
    }

    public void error(@Nullable Context context, @NonNull String source, @NonNull String action,
                      @Nullable String sessionKey, @NonNull String message, @Nullable String detail) {
        trace(context, SessionSyncTraceLevel.ERROR, source, action, sessionKey, message, detail);
    }

    public void trace(@Nullable Context context, @NonNull SessionSyncTraceLevel level,
                      @NonNull String source, @NonNull String action, @Nullable String sessionKey,
                      @NonNull String message, @Nullable String detail) {
        if (context != null) initialize(context);
        String normalizedDetail = detail == null ? "" : detail.trim();
        SessionSyncTraceEvent event = new SessionSyncTraceEvent(
            System.currentTimeMillis(),
            level,
            source.trim(),
            action.trim(),
            trimToNull(sessionKey),
            message.trim(),
            normalizedDetail
        );

        synchronized (lock) {
            events.addLast(event);
            while (events.size() > MAX_EVENTS) {
                events.removeFirst();
            }
            appendToFileLocked(event);
        }

        StringBuilder sb = new StringBuilder();
        sb.append('[').append(level.name()).append("] ")
            .append(source).append('/').append(action)
            .append(" key=").append(event.sessionKey == null ? "-" : event.sessionKey)
            .append(" msg=").append(message);
        if (!TextUtils.isEmpty(normalizedDetail)) {
            sb.append(" detail=").append(normalizedDetail);
        }
        switch (level) {
            case ERROR:
                Log.e(LOG_TAG, sb.toString());
                break;
            case WARN:
                Log.w(LOG_TAG, sb.toString());
                break;
            case INFO:
                Log.i(LOG_TAG, sb.toString());
                break;
            default:
                Log.d(LOG_TAG, sb.toString());
                break;
        }
    }

    @NonNull
    public List<SessionSyncTraceEvent> snapshot() {
        synchronized (lock) {
            return new ArrayList<>(events);
        }
    }

    @NonNull
    public String dumpRecent(int maxCount) {
        int want = maxCount <= 0 ? 100 : Math.min(maxCount, MAX_EVENTS);
        ArrayList<SessionSyncTraceEvent> copy;
        synchronized (lock) {
            copy = new ArrayList<>(events);
        }
        if (copy.isEmpty()) return "";

        int from = Math.max(0, copy.size() - want);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < copy.size(); i++) {
            SessionSyncTraceEvent e = copy.get(i);
            sb.append(e.toJson().toString()).append('\n');
        }
        return sb.toString();
    }

    private void appendToFileLocked(@NonNull SessionSyncTraceEvent event) {
        if (traceFile == null) return;
        try (FileOutputStream out = new FileOutputStream(traceFile, true)) {
            byte[] data = (event.toJson().toString() + "\n").getBytes(StandardCharsets.UTF_8);
            out.write(data);
        } catch (Throwable ignored) {
            return;
        }

        try {
            if (traceFile.length() > MAX_TRACE_FILE_BYTES) {
                rewriteFileFromMemoryLocked();
            }
        } catch (Throwable ignored) {
        }
    }

    private void rewriteFileFromMemoryLocked() {
        if (traceFile == null) return;
        try (FileOutputStream out = new FileOutputStream(traceFile, false)) {
            for (SessionSyncTraceEvent event : events) {
                out.write((event.toJson().toString() + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) return null;
        String out = value.trim();
        return out.isEmpty() ? null : out;
    }
}

