package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.net.InternetDomainName;
import com.google.common.net.InetAddresses;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic URL recognition for terminal transcripts and coordinate-backed selections. */
public final class TerminalLinkResolver {

    public static final int DEFAULT_MAX_URL_LENGTH = 8192;
    private static final int MAX_SELECTION_RESULTS = 32;
    private static final int MAX_TRANSCRIPT_RESULTS = 512;
    private static final int INITIAL_SELECTION_CONTEXT_ROWS = 2;
    private static final int MAX_SELECTION_CONTEXT_ROWS = 256;
    private static final int FOLD_NONE = 0;
    private static final int FOLD_CERTAIN = 1;
    private static final int FOLD_AMBIGUOUS = 2;

    private static final Set<String> NETWORK_SCHEMES = new HashSet<>(Arrays.asList(
        "dav", "dict", "dns", "finger", "ftp", "ftps", "git", "gemini", "gopher",
        "http", "https", "imap", "imaps", "irc", "irc6", "ircs", "ipfs", "ipns",
        "ldap", "ldaps", "pop3", "pop3s", "redis", "rediss", "rsync", "rtsp",
        "rtsps", "rtspu", "sftp", "smb", "smbs", "smtp", "smtps", "ssh", "svn",
        "svn+ssh", "tcp", "telnet", "tftp", "udp", "vnc", "ws", "wss"
    ));

    private static final Set<String> OPAQUE_SCHEMES = new HashSet<>(Arrays.asList(
        "geo", "magnet", "mailto", "sms", "tel"
    ));

    // These are overwhelmingly filenames in terminal output when no path, port, or www prefix exists.
    private static final Set<String> HOST_ONLY_FILE_SUFFIXES = new HashSet<>(Arrays.asList(
        "7z", "aab", "apk", "bash", "bz2", "c", "cc", "cfg", "class", "conf", "cpp",
        "csv", "db", "dex", "gif", "go", "gradle", "gz", "h", "hpp", "ini", "jar",
        "java", "jpeg", "jpg", "js", "json", "jsx", "kt", "kts", "lock", "log", "md",
        "pdf", "png", "properties", "py", "rs", "sh", "so", "sql", "sqlite", "svg",
        "tar", "toml", "ts", "tsx", "txt", "webp", "xml", "xz", "yaml", "yml", "zip"
    ));

    private TerminalLinkResolver() {
    }

    /** Validates and canonicalizes an exact OSC 8 destination without text-wrapper heuristics. */
    @Nullable
    static String normalizeSemanticUrl(@Nullable String destination) {
        if (destination == null || destination.isEmpty() ||
            destination.length() > DEFAULT_MAX_URL_LENGTH ||
            !isAsciiLetter(destination.charAt(0))) return null;

        int colon = 1;
        while (colon < destination.length() && colon <= 32 &&
            isSchemeCharacter(destination.charAt(colon))) colon++;
        if (colon >= destination.length() || destination.charAt(colon) != ':') return null;

        String scheme = destination.substring(0, colon).toLowerCase(Locale.ROOT);
        boolean network = NETWORK_SCHEMES.contains(scheme);
        if (!network && !OPAQUE_SCHEMES.contains(scheme)) return null;
        if (network && (colon + 2 >= destination.length() ||
            destination.charAt(colon + 1) != '/' || destination.charAt(colon + 2) != '/')) return null;
        return normalizeExplicit(destination, scheme, network);
    }

    /** Returns normalized URLs ordered by their most recent visual occurrence. */
    @NonNull
    public static LinkedHashSet<String> extractUrls(@Nullable CharSequence text, boolean allowWithoutScheme) {
        if (text == null || text.length() == 0) return new LinkedHashSet<>();
        List<Candidate> candidates = resolveCandidates(
            text.toString(), new int[0], -1, -1, allowWithoutScheme, false);
        return toUrlSet(candidates, MAX_TRANSCRIPT_RESULTS);
    }

    /** Uses terminal-provided hard-wrap hints while scanning a full transcript snapshot. */
    @NonNull
    public static LinkedHashSet<String> extractUrls(@Nullable TerminalSelectionContext context,
                                                    boolean allowWithoutScheme) {
        if (context == null || context.getText().isEmpty()) return new LinkedHashSet<>();
        List<Candidate> candidates = resolveCandidates(
            context.getText(), context.getHardWrapHintOffsets(),
            -1, -1, allowWithoutScheme, false);
        return toUrlSet(candidates, MAX_TRANSCRIPT_RESULTS);
    }

    @NonNull
    private static LinkedHashSet<String> toUrlSet(List<Candidate> candidates, int limit) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            // A repeated URL belongs at its latest position, not where it first appeared.
            urls.remove(candidate.url);
            urls.add(candidate.url);
            if (urls.size() > limit) {
                Iterator<String> oldest = urls.iterator();
                oldest.next();
                oldest.remove();
            }
        }
        return urls;
    }

    /** Returns only URLs which overlap the current terminal selection, best target first. */
    @NonNull
    public static LinkedHashSet<String> resolveSelection(@Nullable TerminalSelectionContext context,
                                                         boolean allowWithoutScheme) {
        return resolveSelectionResult(context, allowWithoutScheme).getUrls();
    }

    /**
     * Single terminal-selection entry point. Semantic OSC 8 targets are authoritative; styled 2D
     * runs, conservative column geometry, and adaptive linear context are bounded fallbacks that
     * all use the same URI scanner.
     */
    @NonNull
    public static SelectionResult resolveTerminalSelection(@Nullable TerminalBuffer screen,
                                                           int selX1, int selY1,
                                                           int selX2, int selY2,
                                                           boolean allowWithoutScheme) {
        if (screen == null) return SelectionResult.empty();

        LinkedHashSet<String> semanticUrls =
            TerminalSelectionContextExtractor.extractSemanticLinkTargets(
                screen, selX1, selY1, selX2, selY2);
        if (!semanticUrls.isEmpty()) {
            return new SelectionResult(semanticUrls, false, false, false);
        }

        TerminalSelectionContext styledContext =
            TerminalSelectionContextExtractor.extractStyledLinkContext(
                screen, selX1, selY1, selX2, selY2);
        SelectionResult styled = resolveSelectionResult(styledContext, allowWithoutScheme);

        TerminalSelectionContext columnContext =
            TerminalSelectionContextExtractor.extractColumnLinkContext(
                screen, selX1, selY1, selX2, selY2);
        SelectionResult column = resolveSelectionResult(columnContext, allowWithoutScheme);
        SelectionResult linear = resolveAdaptiveLinearSelection(
            screen, selX1, selY1, selX2, selY2, allowWithoutScheme);

        LinkedHashSet<String> styledUrls = styled.getUrls();
        LinkedHashSet<String> columnUrls = column.getUrls();
        LinkedHashSet<String> linearUrls = linear.getUrls();
        LinkedHashSet<String> structuredUrls = new LinkedHashSet<>(styledUrls);
        boolean columnAdded = false;
        for (String url : columnUrls) {
            columnAdded |= addPreferredUrl(structuredUrls, url);
            if (structuredUrls.size() >= MAX_SELECTION_RESULTS) break;
        }

        boolean linearAdded = false;
        for (String url : linearUrls) {
            linearAdded |= addPreferredUrl(structuredUrls, url);
            if (structuredUrls.size() >= MAX_SELECTION_RESULTS) break;
        }

        boolean requiresConfirmation =
            (!styledUrls.isEmpty() && styled.requiresConfirmation()) ||
            (columnAdded && column.requiresConfirmation()) ||
            (linearAdded && linear.requiresConfirmation());
        return new SelectionResult(structuredUrls, requiresConfirmation, false, false);
    }

    /** Includes whether terminal hard-fold ambiguity requires an explicit confirmation dialog. */
    @NonNull
    public static SelectionResult resolveSelectionResult(@Nullable TerminalSelectionContext context,
                                                         boolean allowWithoutScheme) {
        if (context == null || context.isEmpty()) {
            return SelectionResult.empty();
        }
        List<Candidate> candidates = resolveCandidates(
            context.getText(), context.getHardWrapHintOffsets(),
            context.getSelectionStart(), context.getSelectionEnd(),
            allowWithoutScheme, true);
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        boolean requiresConfirmation = context.requiresConfirmation();
        boolean touchesContextStart = false;
        boolean touchesContextEnd = false;
        for (Candidate candidate : candidates) {
            urls.add(candidate.url);
            requiresConfirmation |= candidate.ambiguousFold;
            touchesContextStart |= candidate.sourceStart == 0;
            touchesContextEnd |= candidate.sourceEnd == context.getText().length();
            if (urls.size() >= MAX_SELECTION_RESULTS) break;
        }
        return new SelectionResult(
            urls, requiresConfirmation, touchesContextStart, touchesContextEnd);
    }

    @Nullable
    public static String resolveUniqueSelectionUrl(@Nullable TerminalSelectionContext context,
                                                    boolean allowWithoutScheme) {
        SelectionResult result = resolveSelectionResult(context, allowWithoutScheme);
        LinkedHashSet<String> urls = result.getUrls();
        return !result.requiresConfirmation() && urls.size() == 1 ? urls.iterator().next() : null;
    }

    @NonNull
    private static SelectionResult resolveAdaptiveLinearSelection(TerminalBuffer screen,
                                                                  int selX1, int selY1,
                                                                  int selX2, int selY2,
                                                                  boolean allowWithoutScheme) {
        int paddingRows = INITIAL_SELECTION_CONTEXT_ROWS;
        String previousText = null;
        SelectionResult latest = SelectionResult.empty();
        while (true) {
            TerminalSelectionContext context = TerminalSelectionContextExtractor.extractSelectionContext(
                screen, selX1, selY1, selX2, selY2, paddingRows);
            latest = resolveSelectionResult(context, allowWithoutScheme);
            if (latest.getUrls().isEmpty() ||
                (!latest.touchesContextStart() && !latest.touchesContextEnd()) ||
                context.getText().equals(previousText) ||
                paddingRows >= MAX_SELECTION_CONTEXT_ROWS) {
                return latest;
            }
            previousText = context.getText();
            paddingRows = Math.min(MAX_SELECTION_CONTEXT_ROWS, paddingRows * 2);
        }
    }

    private static boolean addPreferredUrl(LinkedHashSet<String> preferred, String candidate) {
        for (String url : preferred) {
            if (url.length() >= candidate.length() && url.startsWith(candidate)) return false;
        }
        Iterator<String> iterator = preferred.iterator();
        while (iterator.hasNext()) {
            String url = iterator.next();
            if (candidate.length() > url.length() && candidate.startsWith(url)) iterator.remove();
        }
        return preferred.add(candidate);
    }

    @NonNull
    private static List<Candidate> resolveCandidates(@NonNull String input,
                                                     @NonNull int[] hardWrapHints,
                                                     int selectionStart,
                                                     int selectionEnd,
                                                     boolean allowWithoutScheme,
                                                     boolean selectionOnly) {
        if (input.isEmpty()) return Collections.emptyList();

        int safeSelectionStart = selectionOnly ? clamp(selectionStart, 0, input.length()) : -1;
        int safeSelectionEnd = selectionOnly
            ? clamp(selectionEnd, safeSelectionStart, input.length())
            : -1;

        ArrayList<MappedText> views = new ArrayList<>(2);
        views.add(MappedText.identity(input));
        MappedText folded = foldContinuations(
            input, hardWrapHints, safeSelectionStart, safeSelectionEnd);
        if (!folded.text.equals(input)) views.add(folded);

        LinkedHashMap<String, Candidate> distinct = new LinkedHashMap<>();
        for (MappedText view : views) {
            for (Candidate candidate : scan(view, allowWithoutScheme)) {
                if (selectionOnly && !overlapsSelection(candidate, safeSelectionStart, safeSelectionEnd)) {
                    continue;
                }
                String key = candidate.url + '\u0000' + candidate.sourceStart + '\u0000' + candidate.sourceEnd;
                Candidate existing = distinct.get(key);
                if (existing == null || (existing.folded && !candidate.folded)) {
                    distinct.put(key, candidate);
                }
            }
        }

        ArrayList<Candidate> result = new ArrayList<>(distinct.values());
        result = removeSupersededPrefixes(result);
        if (selectionOnly) {
            Collections.sort(result, selectionComparator(safeSelectionStart, safeSelectionEnd));
        } else {
            Collections.sort(result, (first, second) -> {
                int start = Integer.compare(first.sourceStart, second.sourceStart);
                if (start != 0) return start;
                int explicit = Boolean.compare(second.explicitScheme, first.explicitScheme);
                if (explicit != 0) return explicit;
                return Integer.compare(second.sourceEnd, first.sourceEnd);
            });
        }
        return result;
    }

    @NonNull
    private static ArrayList<Candidate> removeSupersededPrefixes(ArrayList<Candidate> candidates) {
        HashMap<Long, ArrayList<Candidate>> candidatesByStart = new HashMap<>();
        for (Candidate candidate : candidates) {
            long key = ((long) candidate.sourceStart << 1) | (candidate.explicitScheme ? 1L : 0L);
            candidatesByStart.computeIfAbsent(key, ignored -> new ArrayList<>(2)).add(candidate);
        }

        HashSet<Candidate> supersededCandidates = new HashSet<>();
        for (ArrayList<Candidate> group : candidatesByStart.values()) {
            for (Candidate candidate : group) {
                for (Candidate other : group) {
                    if (candidate == other || candidate.sourceEnd >= other.sourceEnd) continue;
                    // A full-width hard newline can be either visual folding or a real line break.
                    // Retain both targets so callers can require a choice instead of guessing.
                    if (other.url.startsWith(candidate.url) && !other.ambiguousFold) {
                        supersededCandidates.add(candidate);
                        break;
                    }
                }
            }
        }

        ArrayList<Candidate> result = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            if (!supersededCandidates.contains(candidate)) result.add(candidate);
        }
        return result;
    }

    @NonNull
    private static Comparator<Candidate> selectionComparator(int selectionStart, int selectionEnd) {
        return (first, second) -> {
            int firstOverlap = overlapLength(first, selectionStart, selectionEnd);
            int secondOverlap = overlapLength(second, selectionStart, selectionEnd);
            int overlap = Integer.compare(secondOverlap, firstOverlap);
            if (overlap != 0) return overlap;

            boolean firstContains = first.sourceStart <= selectionStart && first.sourceEnd >= selectionEnd;
            boolean secondContains = second.sourceStart <= selectionStart && second.sourceEnd >= selectionEnd;
            int contains = Boolean.compare(secondContains, firstContains);
            if (contains != 0) return contains;

            int explicit = Boolean.compare(second.explicitScheme, first.explicitScheme);
            if (explicit != 0) return explicit;

            int firstDistance = distanceFromSelection(first, selectionStart, selectionEnd);
            int secondDistance = distanceFromSelection(second, selectionStart, selectionEnd);
            int distance = Integer.compare(firstDistance, secondDistance);
            if (distance != 0) return distance;

            int length = Integer.compare(
                second.sourceEnd - second.sourceStart,
                first.sourceEnd - first.sourceStart);
            if (length != 0) return length;
            return Integer.compare(first.sourceStart, second.sourceStart);
        };
    }

    @NonNull
    private static List<Candidate> scan(@NonNull MappedText view, boolean allowWithoutScheme) {
        ArrayList<Candidate> candidates = new ArrayList<>();
        int length = view.text.length();
        int cursor = 0;
        while (cursor < length) {
            Candidate explicit = scanExplicitAt(view, cursor);
            if (explicit != null) {
                candidates.add(explicit);
                cursor = Math.max(cursor + 1, explicit.viewEnd);
                continue;
            }
            if (allowWithoutScheme) {
                Candidate bare = scanBareAt(view, cursor);
                if (bare != null) {
                    candidates.add(bare);
                    cursor = Math.max(cursor + 1, bare.viewEnd);
                    continue;
                }
            }
            cursor++;
        }
        return candidates;
    }

    @Nullable
    private static Candidate scanExplicitAt(@NonNull MappedText view, int start) {
        String text = view.text;
        if (!isSchemeStartBoundary(text, start) || !isAsciiLetter(text.charAt(start))) return null;

        int colon = start + 1;
        while (colon < text.length() && colon - start <= 32 && isSchemeCharacter(text.charAt(colon))) {
            colon++;
        }
        if (colon >= text.length() || text.charAt(colon) != ':') return null;

        String scheme = text.substring(start, colon).toLowerCase(Locale.ROOT);
        boolean network = NETWORK_SCHEMES.contains(scheme);
        boolean opaque = OPAQUE_SCHEMES.contains(scheme);
        if (!network && !opaque) return null;
        if (network && (colon + 2 >= text.length() || text.charAt(colon + 1) != '/' ||
            text.charAt(colon + 2) != '/')) return null;

        int end = scanTokenEnd(text, start, colon + 1);
        end = trimCandidateEnd(text, start, end);
        if (end <= colon + 1 || end - start > DEFAULT_MAX_URL_LENGTH) return null;

        String normalized = normalizeExplicit(text.substring(start, end), scheme, network);
        if (normalized == null) return null;
        return Candidate.fromView(view, normalized, start, end, true);
    }

    @Nullable
    private static Candidate scanBareAt(@NonNull MappedText view, int start) {
        String text = view.text;
        if (!isBareStartBoundary(text, start)) return null;
        char first = text.charAt(start);
        if (!(isHostCharacter(first) || first == '[' || first == '/')) return null;

        int end = scanTokenEnd(text, start, start);
        end = trimCandidateEnd(text, start, end);
        if (end <= start || end - start > DEFAULT_MAX_URL_LENGTH) return null;

        String raw = text.substring(start, end);
        if (raw.startsWith("//") && start > 0 && text.charAt(start - 1) == ':') return null;
        String normalized = normalizeBare(raw);
        if (normalized == null) return null;
        return Candidate.fromView(view, normalized, start, end, false);
    }

    private static int scanTokenEnd(String text, int start, int minimum) {
        int cursor = Math.max(start, minimum);
        while (cursor < text.length()) {
            char ch = text.charAt(cursor);
            if (isHardBoundary(ch)) break;
            if (cursor > start && isNestedSchemeBoundary(text, cursor)) break;
            cursor++;
        }
        return cursor;
    }

    private static boolean isNestedSchemeBoundary(String text, int index) {
        char previous = text.charAt(index - 1);
        if (!(previous == ',' || previous == ';' || previous == '[' || previous == '(' || previous == '{')) {
            return false;
        }
        int cursor = index;
        if (cursor >= text.length() || !isAsciiLetter(text.charAt(cursor))) return false;
        cursor++;
        while (cursor < text.length() && cursor - index <= 32 && isSchemeCharacter(text.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= text.length() || text.charAt(cursor) != ':') return false;
        String scheme = text.substring(index, cursor).toLowerCase(Locale.ROOT);
        return NETWORK_SCHEMES.contains(scheme) || OPAQUE_SCHEMES.contains(scheme);
    }

    @Nullable
    private static String normalizeExplicit(String candidate, String scheme, boolean network) {
        if (containsUnsafeCodePoint(candidate)) return null;
        if (network) return normalizeNetwork(candidate, scheme);

        String remainder = candidate.substring(candidate.indexOf(':') + 1);
        if (remainder.isEmpty()) return null;
        try {
            URI uri = new URI(scheme + ':' + remainder);
            if (uri.getScheme() == null || uri.getRawSchemeSpecificPart() == null ||
                uri.getRawSchemeSpecificPart().isEmpty()) return null;
        } catch (Exception e) {
            return null;
        }
        if ("mailto".equals(scheme) && !isPlausibleMailto(remainder)) return null;
        return scheme + ':' + remainder;
    }

    @Nullable
    private static String normalizeBare(String raw) {
        if (containsUnsafeCodePoint(raw)) return null;
        String candidate = raw;
        if (candidate.startsWith("//")) candidate = candidate.substring(2);
        if (candidate.isEmpty() || candidate.indexOf("://") >= 0) return null;

        int authorityEnd = indexOfFirst(candidate, 0, '/', '?', '#');
        if (authorityEnd < 0) authorityEnd = candidate.length();
        String rawAuthority = candidate.substring(0, authorityEnd);
        Authority authority = normalizeAuthority(rawAuthority, false, false);
        if (authority == null) return null;

        boolean hasPathQueryOrFragment = authorityEnd < candidate.length();
        boolean hasPort = authority.port >= 0;
        boolean hasWww = authority.hostAscii.startsWith("www.");
        boolean localOrAddress = authority.localhost || authority.ipAddress;
        if (!localOrAddress && !hasWww && !hasPort && !hasPathQueryOrFragment) {
            int dot = authority.hostAscii.lastIndexOf('.');
            if (dot <= 0 || dot + 1 >= authority.hostAscii.length()) return null;
            String suffix = authority.hostAscii.substring(dot + 1);
            if (HOST_ONLY_FILE_SUFFIXES.contains(suffix)) return null;
            if (!hasRecognizedPublicSuffix(authority.hostAscii)) return null;
        }

        String normalized = "https://" + authority.normalized + candidate.substring(authorityEnd);
        return validateUriSyntax(normalized) ? normalized : null;
    }

    @Nullable
    private static String normalizeNetwork(String candidate, String scheme) {
        int schemeEnd = candidate.indexOf(':');
        int authorityStart = schemeEnd + 3;
        if (schemeEnd <= 0 || authorityStart > candidate.length()) return null;
        int authorityEnd = indexOfFirst(candidate, authorityStart, '/', '?', '#');
        if (authorityEnd < 0) authorityEnd = candidate.length();
        if (authorityEnd <= authorityStart) return null;

        Authority authority = normalizeAuthority(
            candidate.substring(authorityStart, authorityEnd), true, true);
        if (authority == null) return null;
        String normalized = scheme + "://" + authority.normalized + candidate.substring(authorityEnd);
        return validateUriSyntax(normalized) ? normalized : null;
    }

    @Nullable
    private static Authority normalizeAuthority(String rawAuthority,
                                                boolean allowUserInfo,
                                                boolean allowSingleLabelHost) {
        if (rawAuthority.isEmpty() || containsUnsafeCodePoint(rawAuthority)) return null;
        String userInfo = "";
        String hostPort = rawAuthority;
        int at = rawAuthority.lastIndexOf('@');
        if (at >= 0) {
            if (!allowUserInfo || at == 0 || at + 1 >= rawAuthority.length()) return null;
            userInfo = rawAuthority.substring(0, at + 1);
            hostPort = rawAuthority.substring(at + 1);
        }

        String host;
        int port = -1;
        if (hostPort.startsWith("[")) {
            int closing = hostPort.indexOf(']');
            if (closing <= 1) return null;
            host = hostPort.substring(0, closing + 1).toLowerCase(Locale.ROOT);
            String remainder = hostPort.substring(closing + 1);
            if (!remainder.isEmpty()) {
                if (!remainder.startsWith(":") || remainder.length() == 1) return null;
                port = parsePort(remainder.substring(1));
                if (port < 0) return null;
            }
            if (!isValidIpv6Literal(host)) return null;
            return new Authority(userInfo + host + formatPort(port), host, port, false, true);
        }

        int colon = hostPort.lastIndexOf(':');
        if (colon >= 0) {
            if (hostPort.indexOf(':') != colon || colon == 0 || colon + 1 >= hostPort.length()) return null;
            port = parsePort(hostPort.substring(colon + 1));
            if (port < 0) return null;
            host = hostPort.substring(0, colon);
        } else {
            host = hostPort;
        }

        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) return null;
        String lowerHost = host.toLowerCase(Locale.ROOT);
        boolean localhost = "localhost".equals(lowerHost);
        boolean ipv4 = isValidIpv4(lowerHost);
        if (!ipv4 && isDottedNumericAddress(lowerHost)) return null;
        String asciiHost;
        if (localhost || ipv4) {
            asciiHost = lowerHost;
        } else {
            try {
                asciiHost = IDN.toASCII(lowerHost, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            } catch (Exception e) {
                return null;
            }
            if (!isValidDomain(asciiHost) &&
                !(allowSingleLabelHost && isValidSingleLabelHost(asciiHost))) {
                return null;
            }
        }
        return new Authority(
            userInfo + asciiHost + formatPort(port), asciiHost, port, localhost, ipv4);
    }

    private static boolean validateUriSyntax(String value) {
        if (value.length() > DEFAULT_MAX_URL_LENGTH || containsUnsafeCodePoint(value)) return false;
        try {
            URI uri = new URI(value);
            return uri.getScheme() != null && uri.getRawSchemeSpecificPart() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPlausibleMailto(String remainder) {
        int query = remainder.indexOf('?');
        String addresses = query >= 0 ? remainder.substring(0, query) : remainder;
        if (addresses.isEmpty()) return false;
        for (String address : addresses.split(",")) {
            int at = address.lastIndexOf('@');
            if (at <= 0 || at + 1 >= address.length()) return false;
            Authority domain = normalizeAuthority(address.substring(at + 1), false, false);
            if (domain == null || domain.localhost || domain.ipAddress) return false;
        }
        return true;
    }

    private static boolean isValidDomain(String host) {
        if (host.isEmpty() || host.length() > 253 || host.indexOf('.') <= 0) return false;
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
            for (int index = 0; index < label.length(); index++) {
                char ch = label.charAt(index);
                if (!(isAsciiLetter(ch) || Character.isDigit(ch) || ch == '-')) return false;
            }
        }
        return true;
    }

    private static boolean isValidSingleLabelHost(String host) {
        if (host.isEmpty() || host.length() > 63 || host.startsWith("-") || host.endsWith("-")) return false;
        boolean hasLetter = false;
        for (int index = 0; index < host.length(); index++) {
            char ch = host.charAt(index);
            if (isAsciiLetter(ch)) hasLetter = true;
            else if (!(Character.isDigit(ch) || ch == '-')) return false;
        }
        return hasLetter;
    }

    private static boolean isValidIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            int value = 0;
            for (int index = 0; index < part.length(); index++) {
                char ch = part.charAt(index);
                if (!Character.isDigit(ch)) return false;
                value = value * 10 + (ch - '0');
            }
            if (value > 255) return false;
        }
        return true;
    }

    private static boolean isDottedNumericAddress(String host) {
        boolean hasDot = false;
        for (int index = 0; index < host.length(); index++) {
            char ch = host.charAt(index);
            if (ch == '.') hasDot = true;
            else if (!Character.isDigit(ch)) return false;
        }
        return hasDot;
    }

    private static boolean isValidIpv6Literal(String host) {
        if (host.length() < 4 || host.charAt(0) != '[' || host.charAt(host.length() - 1) != ']') return false;
        String body = host.substring(1, host.length() - 1);
        String address = body;
        int zoneStart = body.indexOf("%25");
        if (zoneStart >= 0) {
            if (zoneStart == 0 || zoneStart + 3 >= body.length() ||
                body.indexOf('%', zoneStart + 1) >= 0) return false;
            address = body.substring(0, zoneStart);
            String zone = body.substring(zoneStart + 3);
            for (int index = 0; index < zone.length(); index++) {
                char ch = zone.charAt(index);
                if (!(isAsciiLetter(ch) || Character.isDigit(ch) || ch == '.' ||
                    ch == '_' || ch == '~' || ch == '-')) return false;
            }
        }
        if (address.indexOf('%') >= 0) return false;
        try {
            InetAddress parsed = InetAddresses.forString(address);
            return parsed instanceof Inet6Address;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean hasRecognizedPublicSuffix(String host) {
        try {
            InternetDomainName domain = InternetDomainName.from(host);
            return domain.hasPublicSuffix() && !domain.isPublicSuffix();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int parsePort(String raw) {
        if (raw.isEmpty() || raw.length() > 5) return -1;
        int port = 0;
        for (int index = 0; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            if (!Character.isDigit(ch)) return -1;
            port = port * 10 + (ch - '0');
        }
        return port <= 65535 ? port : -1;
    }

    private static String formatPort(int port) {
        return port >= 0 ? ":" + port : "";
    }

    private static int trimCandidateEnd(String text, int start, int end) {
        int result = end;
        while (result > start) {
            char ch = text.charAt(result - 1);
            if (ch == '.' || ch == ',' || ch == ';' || ch == '!' || ch == '\'' || ch == '"' ||
                ch == '`' || ch == '\u2026' || ch == '\u3002' || ch == '\uff0c' || ch == '\uff1b' ||
                ch == '\uff01' || ch == '\uff1f' || ch == '\u3001') {
                result--;
                continue;
            }
            if (ch == ')' && hasExcessClosing(text, start, result, '(', ')')) {
                result--;
                continue;
            }
            if (ch == ']' && hasExcessClosing(text, start, result, '[', ']')) {
                result--;
                continue;
            }
            if (ch == '}' && hasExcessClosing(text, start, result, '{', '}')) {
                result--;
                continue;
            }
            if (ch == '(' || ch == '[' || ch == '{') {
                result--;
                continue;
            }
            break;
        }
        return result;
    }

    private static boolean hasExcessClosing(String text, int start, int end, char opening, char closing) {
        int balance = 0;
        for (int index = start; index < end; index++) {
            char ch = text.charAt(index);
            if (ch == opening) balance++;
            else if (ch == closing) balance--;
        }
        return balance < 0;
    }

    @NonNull
    private static MappedText foldContinuations(String input,
                                                int[] hardWrapHints,
                                                int selectionStart,
                                                int selectionEnd) {
        StringBuilder output = new StringBuilder(input.length());
        int[] offsets = new int[input.length()];
        ArrayList<Integer> ambiguousFoldOffsets = new ArrayList<>();
        int outputLength = 0;
        int cursor = 0;
        while (cursor < input.length()) {
            char ch = input.charAt(cursor);
            if (ch != '\n' && ch != '\r') {
                output.append(ch);
                offsets[outputLength++] = cursor++;
                continue;
            }

            int boundaryStart = cursor;
            if (ch == '\r' && cursor + 1 < input.length() && input.charAt(cursor + 1) == '\n') cursor++;
            int next = cursor + 1;
            while (next < input.length() && (input.charAt(next) == ' ' || input.charAt(next) == '\t')) next++;

            int foldType = classifyFoldBoundary(
                input, output, boundaryStart, next,
                Arrays.binarySearch(hardWrapHints, boundaryStart) >= 0,
                selectionStart, selectionEnd);
            if (foldType != FOLD_NONE) {
                if (foldType == FOLD_AMBIGUOUS) ambiguousFoldOffsets.add(boundaryStart);
                cursor = next;
                continue;
            }

            output.append('\n');
            offsets[outputLength++] = boundaryStart;
            cursor++;
        }
        return new MappedText(
            output.toString(), Arrays.copyOf(offsets, outputLength), true,
            toIntArray(ambiguousFoldOffsets));
    }

    private static int classifyFoldBoundary(String input,
                                            StringBuilder output,
                                            int boundary,
                                            int next,
                                            boolean hardWrapHint,
                                            int selectionStart,
                                            int selectionEnd) {
        if (output.length() == 0 || next >= input.length() || isObviousListPrefix(input, next) ||
            startsWithAllowedScheme(input, next)) return FOLD_NONE;
        char previous = output.charAt(output.length() - 1);
        char following = input.charAt(next);
        if (!isUrlTokenCharacter(previous) || !isUrlTokenCharacter(following)) return FOLD_NONE;

        String token = trailingToken(output);
        if (!looksLikeUrlPrefix(token)) return FOLD_NONE;
        boolean selectionCrosses = selectionStart >= 0 && selectionStart < boundary && selectionEnd > next;
        if (isContinuationDelimiter(previous) || isContinuationPrefix(following)) {
            return selectionCrosses ? FOLD_CERTAIN : FOLD_AMBIGUOUS;
        }
        if (!hasPathQueryOrFragment(token)) return FOLD_NONE;
        if (selectionCrosses) return FOLD_CERTAIN;

        // A physically full row is evidence of display folding, but an explicit newline at
        // exactly that position is indistinguishable. Keep it as a confirm-before-open target.
        return hardWrapHint ? FOLD_AMBIGUOUS : FOLD_NONE;
    }

    private static boolean startsWithAllowedScheme(String input, int start) {
        if (start >= input.length() || !isAsciiLetter(input.charAt(start))) return false;
        int cursor = start + 1;
        while (cursor < input.length() && cursor - start <= 32 && isSchemeCharacter(input.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= input.length() || input.charAt(cursor) != ':') return false;
        String scheme = input.substring(start, cursor).toLowerCase(Locale.ROOT);
        return NETWORK_SCHEMES.contains(scheme) || OPAQUE_SCHEMES.contains(scheme);
    }

    private static String trailingToken(StringBuilder output) {
        int start = output.length();
        while (start > 0 && !Character.isWhitespace(output.charAt(start - 1))) start--;
        return output.substring(start);
    }

    private static boolean looksLikeUrlPrefix(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        int scheme = lower.indexOf("://");
        if (scheme > 0 && NETWORK_SCHEMES.contains(lower.substring(0, scheme))) return true;
        if (lower.startsWith("www.") || lower.startsWith("localhost")) return true;
        int authorityEnd = indexOfFirst(lower, 0, '/', '?', '#');
        if (authorityEnd < 0) authorityEnd = lower.length();
        return lower.substring(0, authorityEnd).indexOf('.') > 0;
    }

    private static boolean hasPathQueryOrFragment(String token) {
        int authorityStart = token.indexOf("://");
        authorityStart = authorityStart >= 0 ? authorityStart + 3 : 0;
        return indexOfFirst(token, authorityStart, '/', '?', '#') >= 0;
    }

    private static boolean isObviousListPrefix(String input, int start) {
        char first = input.charAt(start);
        if ((first == '-' || first == '*' || first == '+' || first == '\u2022') &&
            start + 1 < input.length() && Character.isWhitespace(input.charAt(start + 1))) return true;
        if ((first >= '\u2500' && first <= '\u257f') || first == '\u203a' || first == '\u00bb' ||
            first == '\u25aa' || first == '\u25e6' || first == '\u2192') return true;
        if (first == '[') {
            int closing = input.indexOf(']', start + 1);
            if (closing > start && closing - start <= 3 && closing + 1 < input.length() &&
                Character.isWhitespace(input.charAt(closing + 1))) return true;
        }
        int cursor = start;
        while (cursor < input.length() && Character.isDigit(input.charAt(cursor)) && cursor - start < 6) cursor++;
        return cursor > start && cursor + 1 < input.length() &&
            (input.charAt(cursor) == '.' || input.charAt(cursor) == ')') &&
            Character.isWhitespace(input.charAt(cursor + 1));
    }

    private static boolean isContinuationDelimiter(char ch) {
        return ch == '/' || ch == '?' || ch == '#' || ch == '&' || ch == '=' || ch == '%' || ch == ':';
    }

    private static boolean isContinuationPrefix(char ch) {
        return ch == '/' || ch == '?' || ch == '#' || ch == '&' || ch == '=' || ch == '%';
    }

    private static boolean isUrlTokenCharacter(char ch) {
        return !isHardBoundary(ch);
    }

    private static boolean isHardBoundary(char ch) {
        return Character.isWhitespace(ch) || Character.isISOControl(ch) || ch == '"' || ch == '`' ||
            ch == '<' || ch == '>' || ch == '|' || ch == '\\' || ch == '\u3002' || ch == '\uff0c' ||
            ch == '\uff1b' || ch == '\uff01' || ch == '\uff1f' || ch == '\u3001' || ch == '\uff09' ||
            ch == '\u3011' || ch == '\u300b' || ch == '\u300d' || ch == '\u300f';
    }

    private static boolean containsUnsafeCodePoint(String value) {
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) return true;
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean isSchemeStartBoundary(String text, int start) {
        if (start <= 0) return true;
        char previous = text.charAt(start - 1);
        return !(isAsciiLetter(previous) || Character.isDigit(previous) || previous == '+' ||
            previous == '-' || previous == '.');
    }

    private static boolean isBareStartBoundary(String text, int start) {
        if (start <= 0) return true;
        char previous = text.charAt(start - 1);
        return !(Character.isLetterOrDigit(previous) || previous == '_' || previous == '@' ||
            previous == '.' || previous == '-' || previous == '/' || previous == ':');
    }

    private static boolean isSchemeCharacter(char ch) {
        return isAsciiLetter(ch) || Character.isDigit(ch) || ch == '+' || ch == '-' || ch == '.';
    }

    private static boolean isHostCharacter(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '-';
    }

    private static boolean isAsciiLetter(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }

    private static int indexOfFirst(String value, int start, char first, char second, char third) {
        int result = -1;
        for (char target : new char[]{first, second, third}) {
            int index = value.indexOf(target, Math.max(0, start));
            if (index >= 0 && (result < 0 || index < result)) result = index;
        }
        return result;
    }

    private static boolean overlapsSelection(Candidate candidate, int selectionStart, int selectionEnd) {
        if (selectionStart == selectionEnd) {
            return candidate.sourceStart <= selectionStart && selectionStart < candidate.sourceEnd;
        }
        return candidate.sourceStart < selectionEnd && selectionStart < candidate.sourceEnd;
    }

    private static int overlapLength(Candidate candidate, int selectionStart, int selectionEnd) {
        if (selectionStart == selectionEnd) return overlapsSelection(candidate, selectionStart, selectionEnd) ? 1 : 0;
        return Math.max(0, Math.min(candidate.sourceEnd, selectionEnd) -
            Math.max(candidate.sourceStart, selectionStart));
    }

    private static int distanceFromSelection(Candidate candidate, int selectionStart, int selectionEnd) {
        long candidateCenter = (long) candidate.sourceStart + candidate.sourceEnd;
        long selectionCenter = (long) selectionStart + selectionEnd;
        long distance = Math.abs(candidateCenter - selectionCenter);
        return distance > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) distance;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int index = 0; index < values.size(); index++) result[index] = values.get(index);
        return result;
    }

    public static final class SelectionResult {
        private final LinkedHashSet<String> urls;
        private final boolean requiresConfirmation;
        private final boolean touchesContextStart;
        private final boolean touchesContextEnd;

        private SelectionResult(LinkedHashSet<String> urls,
                                boolean requiresConfirmation,
                                boolean touchesContextStart,
                                boolean touchesContextEnd) {
            this.urls = new LinkedHashSet<>(urls);
            this.requiresConfirmation = requiresConfirmation;
            this.touchesContextStart = touchesContextStart;
            this.touchesContextEnd = touchesContextEnd;
        }

        private static SelectionResult empty() {
            return new SelectionResult(new LinkedHashSet<>(), false, false, false);
        }

        @NonNull
        public LinkedHashSet<String> getUrls() {
            return new LinkedHashSet<>(urls);
        }

        public boolean requiresConfirmation() {
            return requiresConfirmation;
        }

        public boolean touchesContextStart() {
            return touchesContextStart;
        }

        public boolean touchesContextEnd() {
            return touchesContextEnd;
        }
    }

    private static final class Authority {
        final String normalized;
        final String hostAscii;
        final int port;
        final boolean localhost;
        final boolean ipAddress;

        Authority(String normalized, String hostAscii, int port, boolean localhost, boolean ipAddress) {
            this.normalized = normalized;
            this.hostAscii = hostAscii;
            this.port = port;
            this.localhost = localhost;
            this.ipAddress = ipAddress;
        }
    }

    private static final class Candidate {
        final String url;
        final int viewEnd;
        final int sourceStart;
        final int sourceEnd;
        final boolean explicitScheme;
        final boolean folded;
        final boolean ambiguousFold;

        Candidate(String url, int viewEnd, int sourceStart, int sourceEnd,
                  boolean explicitScheme, boolean folded, boolean ambiguousFold) {
            this.url = url;
            this.viewEnd = viewEnd;
            this.sourceStart = sourceStart;
            this.sourceEnd = sourceEnd;
            this.explicitScheme = explicitScheme;
            this.folded = folded;
            this.ambiguousFold = ambiguousFold;
        }

        static Candidate fromView(MappedText view, String url, int start, int end, boolean explicitScheme) {
            int sourceStart = view.sourceOffsets == null ? start : view.sourceOffsets[start];
            int sourceEnd = view.sourceOffsets == null ? end : view.sourceOffsets[end - 1] + 1;
            boolean ambiguousFold = false;
            for (int boundary : view.ambiguousFoldOffsets) {
                if (sourceStart < boundary && boundary < sourceEnd) {
                    ambiguousFold = true;
                    break;
                }
            }
            return new Candidate(
                url, end, sourceStart, sourceEnd, explicitScheme, view.folded, ambiguousFold);
        }
    }

    private static final class MappedText {
        final String text;
        final int[] sourceOffsets;
        final boolean folded;
        final int[] ambiguousFoldOffsets;

        MappedText(String text, int[] sourceOffsets, boolean folded, int[] ambiguousFoldOffsets) {
            this.text = text;
            this.sourceOffsets = sourceOffsets;
            this.folded = folded;
            this.ambiguousFoldOffsets = ambiguousFoldOffsets;
        }

        static MappedText identity(String text) {
            return new MappedText(text, null, false, new int[0]);
        }
    }
}
