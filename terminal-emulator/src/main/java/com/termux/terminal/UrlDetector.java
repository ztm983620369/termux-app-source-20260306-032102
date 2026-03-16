package com.termux.terminal;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URL extraction and normalization helpers designed for terminal transcripts.
 *
 * <p>Goals:
 * <ul>
 *     <li>Work reliably with terminal output which frequently wraps/quotes URLs.</li>
 *     <li>Extract URLs even when surrounded by wrappers like {@code <...>} or {@code (...)}.</li>
 *     <li>Optionally detect URLs without scheme (e.g. {@code github.com/termux/termux-app})
 *     and normalize them to {@code https://...}.</li>
 * </ul>
 */
public final class UrlDetector {

    private UrlDetector() {}

    /** Default max URL length accepted after normalization. */
    public static final int DEFAULT_MAX_URL_LENGTH = 4096;

    // Schemes commonly seen in Termux output. Keep list reasonably tight for tap-to-open safety.
    // Note: "mailto:" and similar are intentionally not included in the "://"-based matcher below.
    private static final String KNOWN_SCHEMES =
        "(?:dav|dict|dns|file|finger|ftp(?:s?)|git|gemini|gopher|http(?:s?)|imap(?:s?)|irc(?:[6s]?)|ip[fn]s|" +
            "ldap(?:s?)|pop3(?:s?)|redis(?:s?)|rsync|rtsp(?:[su]?)|sftp|smb(?:s?)|smtp(?:s?)|ssh|" +
            "svn(?:(?:\\+ssh)?)|tcp|telnet|tftp|udp|vnc|ws(?:s?))";

    private static final Pattern SCHEME_URL_PATTERN = Pattern.compile(
        // Capture the full URL as group(0).
        "(?i)" + KNOWN_SCHEMES + "://\\S+"
    );

    // Domain label: allow unicode letters/digits, keep hyphen rules sane.
    private static final String DOMAIN_LABEL =
        "(?:[a-z0-9\\u00a1-\\uffff](?:[a-z0-9\\u00a1-\\uffff-]{0,61}[a-z0-9\\u00a1-\\uffff])?)";

    // TLD: letters or punycode. Keep max length conservative to reduce false positives.
    private static final String TLD =
        "(?:(?:[a-z\\u00a1-\\uffff]{2,24})|(?:xn--[a-z0-9-]{2,59}))";

    private static final String DOMAIN =
        "(?:" + DOMAIN_LABEL + "\\.)+" + TLD;

    private static final String IPV4 =
        "(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)";

    private static final Pattern IPV4_PATTERN = Pattern.compile("(?i)^" + IPV4 + "$");

    // RFC 3986-ish trailing URL characters (no whitespace).
    private static final String URL_TRAIL_CHARS =
        "[A-Za-z0-9\\u00a1-\\uffff\\-._~!$&'()*+,;=:@%/?#\\[\\]]*";

    private static final Pattern BARE_URL_PATTERN = Pattern.compile(
        // Avoid matching inside emails/usernames (e.g. user@example.com).
        "(?i)(?<![\\w@])" +
            "(" +
            "(?:localhost|" + IPV4 + "|" + DOMAIN + ")" +
            "(?::\\d{1,5})?" +
            "(?:" + URL_TRAIL_CHARS + ")" +
            ")"
    );

    // Accept host-only bare domains only for a small set of common TLDs. This helps avoid false
    // positives for filenames like "config.json" which otherwise look like hostnames.
    private static final String[] HOST_ONLY_TLD_ALLOWLIST = new String[] {
        "com", "net", "org", "edu", "gov", "mil",
        "io", "dev", "app", "me", "co",
        "cn", "jp", "kr", "uk", "de", "fr", "ru", "br", "in", "it", "es", "nl", "au", "ca"
    };

    /**
     * Extract URLs from text.
     *
     * @param text The input text.
     * @param allowWithoutScheme Whether to also detect URLs without scheme, e.g. {@code github.com/...}.
     * @return A set of normalized URLs in first-seen order.
     */
    public static LinkedHashSet<String> extractUrls(@Nullable CharSequence text, boolean allowWithoutScheme) {
        return extractUrls(text, allowWithoutScheme, DEFAULT_MAX_URL_LENGTH);
    }

    public static LinkedHashSet<String> extractUrls(@Nullable CharSequence text, boolean allowWithoutScheme, int maxUrlLength) {
        if (text == null) return new LinkedHashSet<>();
        String input = text.toString();
        if (input.isEmpty()) return new LinkedHashSet<>();

        LinkedHashSet<String> out = new LinkedHashSet<>();
        List<int[]> occupied = new ArrayList<>();

        Matcher schemeMatcher = SCHEME_URL_PATTERN.matcher(input);
        while (schemeMatcher.find()) {
            int start = schemeMatcher.start();
            int end = schemeMatcher.end();
            String raw = input.substring(start, end);
            String normalized = normalizeUrl(raw, /*addDefaultSchemeIfMissing*/ false, maxUrlLength);
            if (normalized != null) out.add(normalized);
            occupied.add(new int[]{start, end});
        }

        if (!allowWithoutScheme) return out;

        if (!occupied.isEmpty()) {
            // Keep overlap checks cheap by sorting by start.
            Collections.sort(occupied, (a, b) -> Integer.compare(a[0], b[0]));
        }

        Matcher bareMatcher = BARE_URL_PATTERN.matcher(input);
        while (bareMatcher.find()) {
            int start = bareMatcher.start(1);
            int end = bareMatcher.end(1);
            if (overlapsAny(start, end, occupied)) continue;
            String raw = bareMatcher.group(1);
            String normalized = normalizeUrl(raw, /*addDefaultSchemeIfMissing*/ true, maxUrlLength);
            if (normalized != null) out.add(normalized);
        }

        return out;
    }

    @Nullable
    public static String extractFirstUrl(@Nullable CharSequence text, boolean allowWithoutScheme) {
        LinkedHashSet<String> urls = extractUrls(text, allowWithoutScheme);
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    private static boolean overlapsAny(int start, int end, List<int[]> occupiedSpans) {
        if (occupiedSpans == null || occupiedSpans.isEmpty()) return false;
        // occupiedSpans sorted by start.
        for (int[] span : occupiedSpans) {
            if (span[1] <= start) continue;
            if (span[0] >= end) return false;
            return true;
        }
        return false;
    }

    @Nullable
    private static String normalizeUrl(@Nullable String raw, boolean addDefaultSchemeIfMissing, int maxUrlLength) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // Strip common wrappers like <...> or "...".
        s = stripEnclosingWrappers(s);
        // Strip trailing punctuation frequently adjacent to URLs in terminal output.
        s = stripTrailingPunctuation(s);
        // Sometimes wrappers are only on one side, re-run to handle "<url)." etc.
        s = stripEnclosingWrappers(s);
        s = s.trim();

        if (s.isEmpty()) return null;
        if (s.length() > maxUrlLength) return null;

        if (s.startsWith("//")) {
            // Protocol-relative URL.
            s = "https:" + s;
        } else if (addDefaultSchemeIfMissing && !hasScheme(s)) {
            if (!isPlausibleBareUrl(s)) return null;
            s = "https://" + s;
        }

        if (s.length() > maxUrlLength) return null;
        return s;
    }

    private static boolean hasScheme(String url) {
        if (url == null) return false;
        int idx = url.indexOf("://");
        if (idx > 0) return true;

        // Handle a few common no-slash schemes. Keep small, we primarily normalize host-like strings.
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith("sms:") || lower.startsWith("geo:");
    }

    private static boolean isPlausibleBareUrl(String s) {
        if (s == null) return false;
        String candidate = s.trim();
        if (candidate.isEmpty()) return false;

        // Extract host[:port] portion.
        int end = indexOfAny(candidate, '/', '?', '#');
        String hostPort = end >= 0 ? candidate.substring(0, end) : candidate;
        if (hostPort.isEmpty()) return false;

        // Strip userinfo if present.
        int at = hostPort.lastIndexOf('@');
        if (at >= 0 && at + 1 < hostPort.length()) hostPort = hostPort.substring(at + 1);

        // Trim a trailing dot in host. "example.com." is valid in DNS but uncommon in terminal URLs.
        while (hostPort.endsWith(".")) hostPort = hostPort.substring(0, hostPort.length() - 1);
        if (hostPort.isEmpty()) return false;

        // Handle host:port.
        boolean hasPort = false;
        int colon = hostPort.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < hostPort.length()) {
            String maybePort = hostPort.substring(colon + 1);
            if (maybePort.matches("\\d{1,5}")) {
                hasPort = true;
                hostPort = hostPort.substring(0, colon);
            }
        }

        String hostLower = hostPort.toLowerCase(Locale.ROOT);
        if ("localhost".equals(hostLower)) return true;
        if (hostLower.startsWith("www.")) return true;
        if (IPV4_PATTERN.matcher(hostPort).matches()) return true;

        boolean hasPathQueryFragment = end >= 0;
        if (hasPort || hasPathQueryFragment) {
            // If it has extra URL-like structure, accept more broadly.
            return hostPort.indexOf('.') >= 0;
        }

        // Host-only (no :port and no /?/#): accept only for common TLDs to avoid filename false positives.
        int lastDot = hostPort.lastIndexOf('.');
        if (lastDot <= 0 || lastDot + 1 >= hostPort.length()) return false;
        String tld = hostLower.substring(lastDot + 1);
        for (String allowed : HOST_ONLY_TLD_ALLOWLIST) {
            if (allowed.equals(tld)) return true;
        }

        return false;
    }

    private static int indexOfAny(String s, char a, char b, char c) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        int ic = s.indexOf(c);
        int min = -1;
        if (ia >= 0) min = ia;
        if (ib >= 0) min = min < 0 ? ib : Math.min(min, ib);
        if (ic >= 0) min = min < 0 ? ic : Math.min(min, ic);
        return min;
    }

    private static String stripEnclosingWrappers(String s) {
        String out = s;
        boolean changed;
        do {
            changed = false;
            if (out.length() < 2) break;
            char first = out.charAt(0);
            char last = out.charAt(out.length() - 1);

            if ((first == '<' && last == '>') ||
                (first == '(' && last == ')') ||
                (first == '[' && last == ']') ||
                (first == '{' && last == '}') ||
                (first == '"' && last == '"') ||
                (first == '\'' && last == '\'') ||
                (first == '`' && last == '`')) {
                out = out.substring(1, out.length() - 1).trim();
                changed = true;
            }
        } while (changed);
        return out;
    }

    private static String stripTrailingPunctuation(String s) {
        String out = s;

        while (!out.isEmpty()) {
            char c = out.charAt(out.length() - 1);

            // Always trim these punctuation marks.
            if (c == '.' || c == ',' || c == ';' || c == '!' ) {
                out = out.substring(0, out.length() - 1);
                continue;
            }

            // Trim unbalanced closers which often appear due to wrappers like "(url)" or "[url]".
            if (c == ')' && hasMoreClosingsThanOpenings(out, '(', ')')) {
                out = out.substring(0, out.length() - 1);
                continue;
            }
            if (c == ']' && hasMoreClosingsThanOpenings(out, '[', ']')) {
                out = out.substring(0, out.length() - 1);
                continue;
            }
            if (c == '}' && hasMoreClosingsThanOpenings(out, '{', '}')) {
                out = out.substring(0, out.length() - 1);
                continue;
            }
            if (c == '>' && out.indexOf('<') == -1) {
                out = out.substring(0, out.length() - 1);
                continue;
            }

            // Common quote wrappers. Only trim if there is no matching opener inside.
            if (c == '"' && out.indexOf('"') == out.length() - 1) {
                out = out.substring(0, out.length() - 1);
                continue;
            }
            if (c == '\'' && out.indexOf('\'') == out.length() - 1) {
                out = out.substring(0, out.length() - 1);
                continue;
            }
            if (c == '`' && out.indexOf('`') == out.length() - 1) {
                out = out.substring(0, out.length() - 1);
                continue;
            }

            break;
        }

        return out.trim();
    }

    private static boolean hasMoreClosingsThanOpenings(String s, char open, char close) {
        int opens = 0;
        int closes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == open) opens++;
            else if (c == close) closes++;
        }
        return closes > opens;
    }
}
