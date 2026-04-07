package com.termux.view.links;

import android.content.Context;
import android.os.Build;
import android.os.LocaleList;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationContext;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLinks;
import android.view.textclassifier.TextSelection;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSelectionContext;
import com.termux.terminal.TerminalUrlDetectionResult;
import com.termux.terminal.UrlDetector;

import java.util.Collections;
import java.util.LinkedHashSet;

/**
 * Interactive URL detector for terminal text selection.
 *
 * <p>The detector uses Android's text classification pipeline when available so URL recognition is
 * driven by the same subsystem used by smart text selection and clipboard link detection. It keeps
 * {@link UrlDetector} as a deterministic fallback and for URL normalization.
 */
public final class TerminalSmartUrlDetector {

    private static final int MAX_URLS_TO_RETURN = 32;
    private static final String WIDGET_VERSION = "termux-terminal";

    private final Context mAppContext;

    public TerminalSmartUrlDetector(Context context) {
        Context appContext = context.getApplicationContext();
        mAppContext = appContext != null ? appContext : context;
    }

    public TerminalUrlDetectionResult detectLocally(@Nullable TerminalSelectionContext selectionContext) {
        if (selectionContext == null || selectionContext.isEmpty()) return TerminalUrlDetectionResult.empty();

        LinkedHashSet<String> urls = limitUrls(UrlDetector.extractUrls(selectionContext.getSelectedText(), true));
        return urls.isEmpty() ? TerminalUrlDetectionResult.empty() : new TerminalUrlDetectionResult(urls, false);
    }

    public TerminalUrlDetectionResult detectSmart(@Nullable TerminalSelectionContext selectionContext) {
        TerminalUrlDetectionResult fallback = detectLocally(selectionContext);
        if (selectionContext == null || selectionContext.getText().isEmpty()) return fallback;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return fallback;

        TextClassifier classifier = null;
        try {
            classifier = createTextClassifier();
            if (classifier == null || classifier == TextClassifier.NO_OP) return fallback;

            LinkedHashSet<String> urls = new LinkedHashSet<>();
            boolean usedTextClassifier = false;

            TerminalSelectionContext suggestedContext = suggestUrlSelection(classifier, selectionContext);
            if (suggestedContext != null) {
                urls.addAll(limitUrls(UrlDetector.extractUrls(suggestedContext.getSelectedText(), true)));
                if (!urls.isEmpty()) usedTextClassifier = true;
            }

            urls.addAll(detectOverlappingUrls(classifier, selectionContext, suggestedContext));
            if (!urls.isEmpty()) usedTextClassifier = true;

            if (urls.isEmpty() && classifySelectionAsUrl(classifier, selectionContext)) {
                urls.addAll(limitUrls(UrlDetector.extractUrls(selectionContext.getSelectedText(), true)));
                if (!urls.isEmpty()) usedTextClassifier = true;
            }

            if (urls.isEmpty()) return fallback;

            urls.addAll(fallback.getUrls());
            return new TerminalUrlDetectionResult(limitUrls(urls), usedTextClassifier);
        } catch (Throwable ignored) {
            return fallback;
        } finally {
            if (classifier != null && classifier != TextClassifier.NO_OP) {
                classifier.destroy();
            }
        }
    }

    @Nullable
    private TextClassifier createTextClassifier() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;

        TextClassificationManager manager = mAppContext.getSystemService(TextClassificationManager.class);
        if (manager == null) return null;

        TextClassificationContext classificationContext =
            new TextClassificationContext.Builder(mAppContext.getPackageName(), TextClassifier.WIDGET_TYPE_CUSTOM_TEXTVIEW)
                .setWidgetVersion(WIDGET_VERSION)
                .build();
        return manager.createTextClassificationSession(classificationContext);
    }

    @Nullable
    private TerminalSelectionContext suggestUrlSelection(TextClassifier classifier,
                                                         TerminalSelectionContext selectionContext) {
        TextSelection.Request request = new TextSelection.Request.Builder(
            selectionContext.getText(),
            selectionContext.getSelectionStart(),
            selectionContext.getSelectionEnd()
        )
            .setDefaultLocales(LocaleList.getDefault())
            .setIncludeTextClassification(true)
            .build();

        TextSelection suggested = classifier.suggestSelection(request);
        if (suggested == null || !hasUrlEntity(suggested)) return null;

        int start = clamp(suggested.getSelectionStartIndex(), 0, selectionContext.getText().length());
        int end = clamp(suggested.getSelectionEndIndex(), start, selectionContext.getText().length());
        if (start == end) return null;
        if (!rangesOverlap(start, end, selectionContext.getSelectionStart(), selectionContext.getSelectionEnd())) {
            return null;
        }

        return selectionContext.withSelection(start, end);
    }

    private LinkedHashSet<String> detectOverlappingUrls(TextClassifier classifier,
                                                        TerminalSelectionContext selectionContext,
                                                        @Nullable TerminalSelectionContext suggestedContext) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();

        TextLinks.Request request = new TextLinks.Request.Builder(selectionContext.getText())
            .setDefaultLocales(LocaleList.getDefault())
            .setEntityConfig(new TextClassifier.EntityConfig.Builder()
                .includeTypesFromTextClassifier(false)
                .setIncludedTypes(Collections.singletonList(TextClassifier.TYPE_URL))
                .build())
            .build();

        TextLinks textLinks = classifier.generateLinks(request);
        if (textLinks == null) return urls;

        int suggestedStart = suggestedContext != null ? suggestedContext.getSelectionStart() : -1;
        int suggestedEnd = suggestedContext != null ? suggestedContext.getSelectionEnd() : -1;

        for (TextLinks.TextLink link : textLinks.getLinks()) {
            if (link == null || !hasUrlEntity(link)) continue;

            boolean overlapsCurrent = rangesOverlap(link.getStart(), link.getEnd(),
                selectionContext.getSelectionStart(), selectionContext.getSelectionEnd());
            boolean overlapsSuggested = suggestedContext != null &&
                rangesOverlap(link.getStart(), link.getEnd(), suggestedStart, suggestedEnd);
            if (!overlapsCurrent && !overlapsSuggested) continue;

            String candidate = selectionContext.getText().substring(link.getStart(), link.getEnd());
            urls.addAll(limitUrls(UrlDetector.extractUrls(candidate, true)));
            if (urls.size() >= MAX_URLS_TO_RETURN) break;
        }

        return urls;
    }

    private boolean classifySelectionAsUrl(TextClassifier classifier, TerminalSelectionContext selectionContext) {
        TextClassification.Request request = new TextClassification.Request.Builder(
            selectionContext.getText(),
            selectionContext.getSelectionStart(),
            selectionContext.getSelectionEnd()
        )
            .setDefaultLocales(LocaleList.getDefault())
            .build();

        TextClassification classification = classifier.classifyText(request);
        return hasUrlEntity(classification);
    }

    private static boolean hasUrlEntity(@Nullable TextSelection selection) {
        if (selection == null) return false;
        if (containsUrlEntity(selection)) return true;
        return hasUrlEntity(selection.getTextClassification());
    }

    private static boolean hasUrlEntity(@Nullable TextClassification classification) {
        return classification != null && containsUrlEntity(classification);
    }

    private static boolean hasUrlEntity(@Nullable TextLinks.TextLink textLink) {
        return textLink != null && containsUrlEntity(textLink);
    }

    private static boolean containsUrlEntity(TextSelection selection) {
        for (int i = 0; i < selection.getEntityCount(); i++) {
            if (TextClassifier.TYPE_URL.equals(selection.getEntity(i))) return true;
        }
        return false;
    }

    private static boolean containsUrlEntity(TextClassification classification) {
        for (int i = 0; i < classification.getEntityCount(); i++) {
            if (TextClassifier.TYPE_URL.equals(classification.getEntity(i))) return true;
        }
        return false;
    }

    private static boolean containsUrlEntity(TextLinks.TextLink textLink) {
        for (int i = 0; i < textLink.getEntityCount(); i++) {
            if (TextClassifier.TYPE_URL.equals(textLink.getEntity(i))) return true;
        }
        return false;
    }

    private static LinkedHashSet<String> limitUrls(LinkedHashSet<String> urls) {
        if (urls.size() <= MAX_URLS_TO_RETURN) return urls;

        LinkedHashSet<String> limited = new LinkedHashSet<>();
        int count = 0;
        for (String url : urls) {
            limited.add(url);
            count++;
            if (count >= MAX_URLS_TO_RETURN) break;
        }
        return limited;
    }

    private static boolean rangesOverlap(int start1, int end1, int start2, int end2) {
        return start1 < end2 && start2 < end1;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
