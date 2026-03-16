package com.termux.shared.termux.data;

import androidx.annotation.Nullable;

import com.termux.terminal.UrlDetector;

import java.util.LinkedHashSet;

/**
 * Termux URL utilities.
 *
 * <p>This is a thin wrapper around {@link UrlDetector} so URL detection behavior is shared between
 * terminal-view and app-level features (tap-to-open, select-url dialog, etc).
 */
public final class TermuxUrlUtils {

    private TermuxUrlUtils() {}

    /**
     * Extract URLs from {@code text}.
     *
     * <p>URLs are normalized for terminal UX:
     * <ul>
     *     <li>Wrappers like {@code <...>} and {@code (...)} are stripped.</li>
     *     <li>Common trailing punctuation like {@code . , ; !} is stripped.</li>
     *     <li>Scheme-less hosts like {@code github.com/...} are normalized to {@code https://...}.</li>
     * </ul>
     */
    public static LinkedHashSet<CharSequence> extractUrls(@Nullable String text) {
        LinkedHashSet<String> urls = UrlDetector.extractUrls(text, /*allowWithoutScheme*/ true);
        LinkedHashSet<CharSequence> out = new LinkedHashSet<>(urls.size());
        out.addAll(urls);
        return out;
    }
}

