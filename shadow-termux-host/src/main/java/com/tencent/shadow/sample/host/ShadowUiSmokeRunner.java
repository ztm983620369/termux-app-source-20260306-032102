package com.tencent.shadow.sample.host;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Bounded, declarative UI smoke runner.  It intentionally exposes no arbitrary code or
 * reflection: every operation is a small, deterministic interaction against the current view
 * tree.  Failures are reported to the normal runtime-health rollback path.
 */
final class ShadowUiSmokeRunner {

    interface Callback {
        void onSuccess(int stepCount, long durationMs);

        void onFailure(String message, int step, long durationMs);
    }

    private static final int MAX_SPEC_BYTES = 32 * 1024;
    private static final long MAX_TOTAL_MS = 15_000L;
    private static final long MAX_TOTAL_WAIT_MS = 10_000L;
    private static final int MAX_STEPS = 32;
    private static final int MAX_TEXT = 2_048;
    private static final Set<String> ACTIONS = new HashSet<>();

    static {
        ACTIONS.add("wait");
        ACTIONS.add("assertDisplayed");
        ACTIONS.add("assertText");
        ACTIONS.add("click");
        ACTIONS.add("focus");
        ACTIONS.add("input");
        ACTIONS.add("scroll");
        ACTIONS.add("assertImeActive");
    }

    private ShadowUiSmokeRunner() {
    }

    static void run(final Activity activity, String specification, final Callback callback) {
        final long started = android.os.SystemClock.elapsedRealtime();
        try {
            validateSpecification(specification);
            JSONObject root = new JSONObject(specification);
            JSONArray steps = root.optJSONArray("steps");
            execute(activity, steps, 0, started, callback);
        } catch (Throwable throwable) {
            callback.onFailure(rootMessage(throwable), 0,
                    android.os.SystemClock.elapsedRealtime() - started);
        }
    }

    static void validateSpecification(String specification) throws Exception {
        if (specification == null) {
            throw new IllegalArgumentException("smoke specification is missing");
        }
        if (specification.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_SPEC_BYTES) {
            throw new IllegalArgumentException("smoke specification exceeds 32 KiB");
        }
        JSONObject root = new JSONObject(specification);
        if (root.optInt("schemaVersion", 0) != 1) {
            throw new IllegalArgumentException("smoke schemaVersion must be 1");
        }
        for (Iterator<String> keys = root.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!"schemaVersion".equals(key) && !"steps".equals(key)) {
                throw new IllegalArgumentException("unsupported smoke root field: " + key);
            }
        }
        JSONArray steps = root.optJSONArray("steps");
        if (steps == null || steps.length() == 0 || steps.length() > MAX_STEPS) {
            throw new IllegalArgumentException("smoke steps must contain 1..32 entries");
        }
        long totalWaitMs = 0L;
        for (int index = 0; index < steps.length(); index++) {
            JSONObject step = steps.getJSONObject(index);
            String action = step.optString("action", "");
            if (!ACTIONS.contains(action)) {
                throw new IllegalArgumentException("unsupported smoke action: " + action);
            }
            Set<String> allowed = allowedFields(action);
            for (Iterator<String> keys = step.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (!allowed.contains(key)) {
                    throw new IllegalArgumentException(
                            "field " + key + " is not valid for " + action
                    );
                }
            }
            if (!"wait".equals(action) && !step.has("view")) {
                throw new IllegalArgumentException(action + " requires view");
            }
            if (step.has("view")) {
                Object rawView = step.get("view");
                if (!(rawView instanceof String)) {
                    throw new IllegalArgumentException("view must be a string");
                }
                String view = (String) rawView;
                String normalized = view.startsWith("@id/") ? view.substring(4)
                        : view.startsWith("id/") ? view.substring(3) : view;
                if (normalized.length() == 0 || view.length() > 160
                        || !normalized.matches("[A-Za-z0-9_.:]+")) {
                    throw new IllegalArgumentException("unsafe smoke view name");
                }
            }
            if (("assertText".equals(action) || "input".equals(action))
                    && !step.has("text")) {
                throw new IllegalArgumentException(action + " requires text");
            }
            if (step.has("text")) {
                Object rawText = step.get("text");
                if (!(rawText instanceof String)) {
                    throw new IllegalArgumentException("text must be a string");
                }
                if (((String) rawText).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > MAX_TEXT) {
                    throw new IllegalArgumentException("text exceeds 2048 bytes");
                }
            }
            if (step.has("contains") && !(step.get("contains") instanceof Boolean)) {
                throw new IllegalArgumentException("contains must be a boolean");
            }
            long waitMs = integerField(step, "waitMs", 0L);
            boundedWait(waitMs);
            if ("wait".equals(action) && waitMs == 0L) {
                throw new IllegalArgumentException("wait requires a positive waitMs");
            }
            totalWaitMs += waitMs;
            long dx = integerField(step, "dx", 0L);
            long dy = integerField(step, "dy", 0L);
            boundedDelta(dx);
            boundedDelta(dy);
            if ("scroll".equals(action) && dx == 0L && dy == 0L) {
                throw new IllegalArgumentException("scroll requires a non-zero dx or dy");
            }
        }
        if (totalWaitMs > MAX_TOTAL_WAIT_MS) {
            throw new IllegalArgumentException("smoke total waitMs exceeds 10000");
        }
    }

    private static Set<String> allowedFields(String action) {
        Set<String> allowed = new HashSet<>();
        allowed.add("action");
        allowed.add("waitMs");
        if (!"wait".equals(action)) {
            allowed.add("view");
        }
        if ("assertText".equals(action) || "input".equals(action)) {
            allowed.add("text");
        }
        if ("assertText".equals(action)) {
            allowed.add("contains");
        }
        if ("scroll".equals(action)) {
            allowed.add("dx");
            allowed.add("dy");
        }
        return allowed;
    }

    private static long integerField(JSONObject step, String key, long fallback) throws Exception {
        if (!step.has(key)) {
            return fallback;
        }
        Object value = step.get(key);
        if (!(value instanceof Number)
                || value instanceof Float
                || value instanceof Double) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return ((Number) value).longValue();
    }

    private static void execute(
            final Activity activity,
            final JSONArray steps,
            final int index,
            final long started,
            final Callback callback
    ) {
        if (android.os.SystemClock.elapsedRealtime() - started > MAX_TOTAL_MS) {
            callback.onFailure("smoke test exceeded 15000ms", index,
                    android.os.SystemClock.elapsedRealtime() - started);
            return;
        }
        if (index >= steps.length()) {
            callback.onSuccess(steps.length(),
                    android.os.SystemClock.elapsedRealtime() - started);
            return;
        }
        try {
            JSONObject step = steps.getJSONObject(index);
            String action = step.optString("action", "");
            if (!ACTIONS.contains(action)) {
                throw new IllegalArgumentException("unsupported smoke action: " + action);
            }
            long waitMs = boundedWait(step.optLong("waitMs", 0L));
            perform(activity, step, action);
            if (waitMs > 0L) {
                activity.getWindow().getDecorView().postDelayed(
                        () -> execute(activity, steps, index + 1, started, callback),
                        waitMs
                );
            } else {
                execute(activity, steps, index + 1, started, callback);
            }
        } catch (Throwable throwable) {
            callback.onFailure(
                    rootMessage(throwable),
                    index,
                    android.os.SystemClock.elapsedRealtime() - started
            );
        }
    }

    private static void perform(Activity activity, JSONObject step, String action) {
        String viewName = step.optString("view", "");
        View view = viewName.length() == 0 ? activity.getWindow().getDecorView()
                : findView(activity.getWindow().getDecorView(), viewName);
        if (!"wait".equals(action) && view == null) {
            throw new IllegalStateException("view not found: " + viewName);
        }
        if ("wait".equals(action)) {
            return;
        }
        if ("assertDisplayed".equals(action)) {
            if (!view.isShown() || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f
                    || view.getWidth() <= 0 || view.getHeight() <= 0) {
                throw new IllegalStateException("view is not displayed: " + viewName);
            }
            return;
        }
        if ("assertText".equals(action)) {
            if (!(view instanceof TextView)) {
                throw new IllegalStateException("view is not a TextView: " + viewName);
            }
            String expected = step.optString("text", "");
            String actual = ((TextView) view).getText() == null
                    ? "" : ((TextView) view).getText().toString();
            boolean contains = step.optBoolean("contains", true);
            if (contains ? !actual.contains(expected) : !actual.equals(expected)) {
                throw new IllegalStateException(
                        "text mismatch for " + viewName + ": expected="
                                + expected + " actual=" + actual
                );
            }
            return;
        }
        if ("click".equals(action)) {
            if (!view.isEnabled() || !view.performClick()) {
                throw new IllegalStateException("click was rejected: " + viewName);
            }
            return;
        }
        if ("focus".equals(action)) {
            if (!view.requestFocus()) {
                throw new IllegalStateException("focus was rejected: " + viewName);
            }
            return;
        }
        if ("input".equals(action)) {
            if (!(view instanceof EditText)) {
                throw new IllegalStateException("view is not an EditText: " + viewName);
            }
            String text = step.optString("text", "");
            if (text.length() > MAX_TEXT) {
                throw new IllegalArgumentException("input text exceeds 2048 characters");
            }
            ((EditText) view).setText(text);
            return;
        }
        if ("scroll".equals(action)) {
            view.scrollBy(
                    boundedDelta(step.optInt("dx", 0)),
                    boundedDelta(step.optInt("dy", 0))
            );
            return;
        }
        if ("assertImeActive".equals(action)) {
            InputMethodManager manager = (InputMethodManager) activity.getSystemService(
                    Context.INPUT_METHOD_SERVICE
            );
            if (manager == null || !manager.isActive(view)) {
                throw new IllegalStateException("IME is not active for " + viewName);
            }
            return;
        }
        throw new IllegalArgumentException("unsupported smoke action: " + action);
    }

    private static View findView(View root, String requested) {
        String name = requested;
        if (name.startsWith("@id/")) {
            name = name.substring(4);
        } else if (name.startsWith("id/")) {
            name = name.substring(3);
        }
        if (root.getId() != View.NO_ID) {
            try {
                if (name.equals(root.getResources().getResourceEntryName(root.getId()))
                        || requested.equals(root.getResources().getResourceName(root.getId()))) {
                    return root;
                }
            } catch (Throwable ignored) {
                // Resource IDs from an isolated plugin may not resolve through the host table.
            }
        }
        if (root.getContentDescription() != null
                && name.contentEquals(root.getContentDescription())) {
            return root;
        }
        if (root instanceof TextView
                && name.contentEquals(((TextView) root).getText())) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findView(group.getChildAt(index), requested);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static long boundedWait(long value) {
        if (value < 0L || value > 5_000L) {
            throw new IllegalArgumentException("waitMs must be between 0 and 5000");
        }
        return value;
    }

    private static int boundedDelta(long value) {
        if (value < -10_000 || value > 10_000) {
            throw new IllegalArgumentException("scroll delta is out of bounds");
        }
        return (int) value;
    }

    private static String rootMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.trim().length() == 0) {
            message = throwable.getClass().getSimpleName();
        }
        return message.length() > 2_048 ? message.substring(0, 2_048) : message;
    }
}
