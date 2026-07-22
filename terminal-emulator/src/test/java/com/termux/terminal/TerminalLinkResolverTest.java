package com.termux.terminal;

import org.junit.Assert;
import org.junit.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Random;

public class TerminalLinkResolverTest {

    private void assertUrlsAre(String text, String... urls) {
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        Collections.addAll(expected, urls);
        Assert.assertEquals(expected, TerminalLinkResolver.extractUrls(text, true));
    }

    private void assertSelectedUrlsAre(String text, int start, int end, int[] hardWrapHints,
                                       String... urls) {
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        Collections.addAll(expected, urls);
        TerminalSelectionContext context = new TerminalSelectionContext(text, start, end, hardWrapHints);
        Assert.assertEquals(expected, TerminalLinkResolver.resolveSelection(context, true));
    }

    @Test
    public void extractsAndCanonicalizesExplicitUris() {
        assertUrlsAre(
            "HTTPS://Example.COM/Path ftp://Files.Example.COM/pub ssh://git@Build-Server:22/repo",
            "https://example.com/Path",
            "ftp://files.example.com/pub",
            "ssh://git@build-server:22/repo");
        assertUrlsAre("mailto:user@example.com magnet:?xt=urn:btih:abcdef",
            "mailto:user@example.com", "magnet:?xt=urn:btih:abcdef");
        assertUrlsAre("http://intranet:8080/status", "http://intranet:8080/status");
    }

    @Test
    public void handlesTerminalAndMarkupWrappersWithoutLosingBalancedPathCharacters() {
        String wikipedia = "https://en.wikipedia.org/wiki/Paren_(disambiguation)";
        assertUrlsAre("see <" + wikipedia + ">.", wikipedia);
        assertUrlsAre("[docs](" + wikipedia + ")).", wikipedia);
        assertUrlsAre("{\"url\":\"https://example.com/a?x=1&y=2\"}",
            "https://example.com/a?x=1&y=2");
        assertUrlsAre("中文：https://example.com/path，下一项", "https://example.com/path");
        assertUrlsAre("[https://one.example/a][https://two.example/b]",
            "https://one.example/a", "https://two.example/b");
    }

    @Test
    public void recognizesBareHostsAddressesPortsAndIdn() {
        assertUrlsAre(
            "github.com/termux/termux-app www.google.com localhost:3000/api 127.0.0.1:8080 [::1]:9090/x",
            "https://github.com/termux/termux-app",
            "https://www.google.com",
            "https://localhost:3000/api",
            "https://127.0.0.1:8080",
            "https://[::1]:9090/x");
        assertUrlsAre("example.cloud //example.dev/docs",
            "https://example.cloud", "https://example.dev/docs");
        assertUrlsAre("例子.测试/路径", "https://xn--fsqu00a.xn--0zwm56d/路径");
    }

    @Test
    public void rejectsUnsafeAmbiguousAndMalformedTokens() {
        assertUrlsAre("config.json README.md build.gradle foo.md user@example.com git@github.com:repo");
        assertUrlsAre("example.notarealtld");
        assertUrlsAre("javascript:alert(1) data:text/plain,hello intent://example.com");
        assertUrlsAre("custom:example.com file://example.com");
        assertUrlsAre("https://example.com:70000 https://example.com/%zz http://exa_mple.com");
        assertUrlsAre("http://999.1.1.1 version=1.2.3");
        assertUrlsAre("https://example.com/\u202Eevil");
    }

    @Test
    public void selectionResolutionReturnsOnlyTheOverlappingTarget() {
        String text = "first https://one.example/a middle https://two.example/b last";
        int secondStart = text.indexOf("two.example");
        assertSelectedUrlsAre(text, secondStart, secondStart + 3, new int[0],
            "https://two.example/b");

        int middleStart = text.indexOf("middle");
        assertSelectedUrlsAre(text, middleStart, middleStart + "middle".length(), new int[0]);
    }

    @Test
    public void resolvesSchemeLessSelectionWithoutSelectingNearbyUrls() {
        String text = "left.example/a selected.example/path right.example/b";
        int start = text.indexOf("selected.example");
        assertSelectedUrlsAre(text, start, start + 8, new int[0],
            "https://selected.example/path");
    }

    @Test
    public void reconstructsIndentedHardFoldsWithoutDiscardingTheVisibleTarget() {
        String queryFold = "https://example.com/search?\n    q=terminal&\n    page=2";
        assertUrlsAre(queryFold,
            "https://example.com/search?",
            "https://example.com/search?q=terminal&page=2");

        String pathFold = "https://example.com/docs/\n    installation/android";
        assertUrlsAre(pathFold,
            "https://example.com/docs/",
            "https://example.com/docs/installation/android");
    }

    @Test
    public void explicitNewlineContinuationNeverAutoOpensWithoutConfirmation() {
        String text = "https://example.com/\ndocumentation";
        int selected = text.indexOf("documentation");
        TerminalSelectionContext context =
            new TerminalSelectionContext(text, selected, selected + 3, new int[0]);
        TerminalLinkResolver.SelectionResult result =
            TerminalLinkResolver.resolveSelectionResult(context, true);

        Assert.assertEquals(Collections.singleton("https://example.com/documentation"), result.getUrls());
        Assert.assertTrue(result.requiresConfirmation());
        Assert.assertNull(TerminalLinkResolver.resolveUniqueSelectionUrl(context, true));
    }

    @Test
    public void hardWrapHintCanResolveAnAlphanumericContinuationSelectedOnTheNextRow() {
        String text = "https://example.com/very\nlong/path";
        int boundary = text.indexOf('\n');
        int selected = text.indexOf("long");
        assertSelectedUrlsAre(text, selected, selected + 2, new int[]{boundary},
            "https://example.com/verylong/path");
        TerminalLinkResolver.SelectionResult ambiguous = TerminalLinkResolver.resolveSelectionResult(
            new TerminalSelectionContext(text, selected, selected + 2, new int[]{boundary}), true);
        Assert.assertTrue(ambiguous.requiresConfirmation());
        Assert.assertNull(TerminalLinkResolver.resolveUniqueSelectionUrl(
            new TerminalSelectionContext(text, selected, selected + 2, new int[]{boundary}), true));

        assertSelectedUrlsAre(text, selected, selected + 2, new int[0]);
    }

    @Test
    public void hardWrapAmbiguityRetainsTheVisiblePrefixAndRequiresConfirmation() {
        String text = "https://example.com/very\nlong/path";
        int boundary = text.indexOf('\n');
        int selected = text.indexOf("very");
        TerminalLinkResolver.SelectionResult result = TerminalLinkResolver.resolveSelectionResult(
            new TerminalSelectionContext(text, selected, selected + 2, new int[]{boundary}), true);
        Assert.assertEquals(new LinkedHashSet<>(Arrays.asList(
            "https://example.com/very",
            "https://example.com/verylong/path")), result.getUrls());
        Assert.assertTrue(result.requiresConfirmation());
    }

    @Test
    public void neverFoldsListsOrASecondUrlIntoTheFirstTarget() {
        assertUrlsAre("https://example.com/\n  - documentation", "https://example.com/");
        assertUrlsAre("https://example.com/\n  \u251c\u2500 documentation", "https://example.com/");
        assertUrlsAre("https://example.com/\n  [x] documentation", "https://example.com/");
        assertUrlsAre("https://one.example/path\nhttps://two.example/path",
            "https://one.example/path", "https://two.example/path");
    }

    @Test
    public void rejectsCandidatesBeyondTheBoundedUrlLength() {
        char[] path = new char[TerminalLinkResolver.DEFAULT_MAX_URL_LENGTH];
        Arrays.fill(path, 'a');
        assertUrlsAre("https://example.com/" + new String(path));
    }

    @Test
    public void transcriptResultsKeepTheMostRecentUniqueOccurrences() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 520; index++) {
            text.append("https://host.example/path/").append(index).append('\n');
        }
        text.append("https://host.example/path/8");

        String[] urls = TerminalLinkResolver.extractUrls(text, true).toArray(new String[0]);
        Assert.assertEquals(512, urls.length);
        Assert.assertEquals("https://host.example/path/9", urls[0]);
        Assert.assertEquals("https://host.example/path/8", urls[urls.length - 1]);
    }

    @Test
    public void randomizedInputsAreDeterministicAndNeverProduceInvalidUriSyntax() throws Exception {
        Random random = new Random(0x5eed5eedL);
        char[] alphabet = (
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" +
                ":/?#[]@!$&'()*+,;=.%_- <>\\\n\t").toCharArray();
        for (int sample = 0; sample < 1000; sample++) {
            int length = random.nextInt(320);
            StringBuilder text = new StringBuilder(length + 32);
            for (int index = 0; index < length; index++) {
                text.append(alphabet[random.nextInt(alphabet.length)]);
            }
            if (sample % 7 == 0) text.append(" https://example.com/a?x=1&y=2");

            LinkedHashSet<String> first = TerminalLinkResolver.extractUrls(text, true);
            LinkedHashSet<String> second = TerminalLinkResolver.extractUrls(text, true);
            Assert.assertEquals(first, second);
            Assert.assertTrue(first.size() <= 512);
            for (String url : first) {
                URI uri = new URI(url);
                Assert.assertNotNull(uri.getScheme());
                Assert.assertFalse(url.contains("\n"));
                Assert.assertFalse(url.contains("\t"));
            }
        }
    }
}
