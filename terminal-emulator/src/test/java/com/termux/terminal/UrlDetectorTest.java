package com.termux.terminal;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashSet;

public class UrlDetectorTest {

    private void assertUrlsAre(String text, boolean allowWithoutScheme, String... urls) {
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        Collections.addAll(expected, urls);
        Assert.assertEquals(expected, UrlDetector.extractUrls(text, allowWithoutScheme));
    }

    @Test
    public void testExtractUrls_knownSchemes() {
        assertUrlsAre("hello http://example.com world", false, "http://example.com");
        assertUrlsAre("http://example.com\nhttp://another.com", false, "http://example.com", "http://another.com");
        assertUrlsAre("hello https://example.com/#bar https://example.com/foo#bar", false,
            "https://example.com/#bar", "https://example.com/foo#bar");
        assertUrlsAre("prefix <https://example.com> suffix", false, "https://example.com");
        assertUrlsAre("prefix (https://example.com). suffix", false, "https://example.com");
        assertUrlsAre("https://example.com,", false, "https://example.com");
    }

    @Test
    public void testExtractUrls_withoutScheme() {
        assertUrlsAre("github.com/termux/termux-app", true, "https://github.com/termux/termux-app");
        assertUrlsAre("www.google.com", true, "https://www.google.com");
        assertUrlsAre("example.com", true, "https://example.com");
        assertUrlsAre("localhost:3000/api", true, "https://localhost:3000/api");
        assertUrlsAre("127.0.0.1:8080", true, "https://127.0.0.1:8080");
        assertUrlsAre("10.0.2.2:8080", true, "https://10.0.2.2:8080");

        // Host-only for uncommon/new gTLDs is intentionally conservative to avoid filename false positives.
        assertUrlsAre("example.cloud", true);
        assertUrlsAre("example.cloud/docs", true, "https://example.cloud/docs");

        // Common terminal filenames should not be detected as URLs.
        assertUrlsAre("config.json", true);
        assertUrlsAre("README.md", true);
        assertUrlsAre("build.gradle", true);

        // Non-allowlisted TLD should still be detected when it has clear URL structure.
        assertUrlsAre("foo.bar/baz", true, "https://foo.bar/baz");
    }

    @Test
    public void testDoesNotMatchEmail() {
        assertUrlsAre("user@example.com", true);
        assertUrlsAre("mailto:user@example.com", true); // we currently don't extract mailto, just ensure no false positives
    }

    @Test
    public void testBalancedParensPreserved() {
        String url = "https://en.wikipedia.org/wiki/Paren_(disambiguation)";
        assertUrlsAre("see " + url, false, url);

        // Extra trailing ')' should be trimmed.
        assertUrlsAre("see " + url + ")", false, url);
        assertUrlsAre("see (" + url + ")).", false, url);
    }
}
