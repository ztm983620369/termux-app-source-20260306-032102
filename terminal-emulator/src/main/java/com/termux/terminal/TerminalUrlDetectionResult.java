package com.termux.terminal;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Immutable URL detection result used by interactive terminal actions.
 */
public final class TerminalUrlDetectionResult {

    private static final TerminalUrlDetectionResult EMPTY =
        new TerminalUrlDetectionResult(new LinkedHashSet<>(), false);

    private final Set<String> mUrls;
    private final boolean mUsedTextClassifier;

    public TerminalUrlDetectionResult(@NonNull LinkedHashSet<String> urls, boolean usedTextClassifier) {
        if (urls == null) throw new IllegalArgumentException("urls == null");
        mUrls = Collections.unmodifiableSet(new LinkedHashSet<>(urls));
        mUsedTextClassifier = usedTextClassifier;
    }

    @NonNull
    public static TerminalUrlDetectionResult empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return mUrls.isEmpty();
    }

    @NonNull
    public LinkedHashSet<String> getUrls() {
        return new LinkedHashSet<>(mUrls);
    }

    public boolean usedTextClassifier() {
        return mUsedTextClassifier;
    }
}
