package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class CodexImageAttachmentStoreTest {

    @Test
    public void detectsCodexNativeImageFormatsByContent() {
        Assert.assertEquals("png", CodexImageAttachmentStore.detectImageExtension(new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a}));
        Assert.assertEquals("jpg", CodexImageAttachmentStore.detectImageExtension(new byte[]{
            (byte) 0xff, (byte) 0xd8, (byte) 0xff}));
        Assert.assertEquals("gif", CodexImageAttachmentStore.detectImageExtension(
            "GIF89a".getBytes(StandardCharsets.US_ASCII)));
        Assert.assertEquals("webp", CodexImageAttachmentStore.detectImageExtension(
            "RIFF0000WEBP".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void unsupportedContainerRequiresAndroidNormalization() {
        Assert.assertEquals("", CodexImageAttachmentStore.detectImageExtension(
            new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'}));
        Assert.assertEquals("", CodexImageAttachmentStore.detectImageExtension(new byte[0]));
        Assert.assertEquals("", CodexImageAttachmentStore.detectImageExtension(null));
    }

    @Test
    public void deduplicatesExactContentInFirstSelectionOrderAndReportsReuse() {
        CodexImageAttachmentStore.MaterializedImage firstA = image("a", 100L, false);
        CodexImageAttachmentStore.MaterializedImage duplicateA = image("a", 100L, true);
        CodexImageAttachmentStore.MaterializedImage existingB = image("b", 200L, true);
        CodexImageAttachmentStore.MaterializedImage anotherA = image("a", 100L, true);

        CodexImageAttachmentStore.DeduplicatedBatch batch =
            CodexImageAttachmentStore.deduplicateByContent(
                Arrays.asList(firstA, duplicateA, existingB, anotherA));

        Assert.assertEquals(2, batch.uniqueImages.size());
        Assert.assertSame(firstA, batch.uniqueImages.get(0));
        Assert.assertSame(existingB, batch.uniqueImages.get(1));
        Assert.assertEquals(2, batch.duplicateCount);
        Assert.assertEquals(200L, batch.duplicateBytes);
        Assert.assertEquals(1, batch.reusedUniqueCount);
        Assert.assertEquals(200L, batch.reusedUniqueBytes);
    }

    @Test
    public void duplicateByteAccountingSaturatesInsteadOfOverflowing() {
        CodexImageAttachmentStore.DeduplicatedBatch batch =
            CodexImageAttachmentStore.deduplicateByContent(Arrays.asList(
                image("a", Long.MAX_VALUE, false),
                image("a", Long.MAX_VALUE, true),
                image("a", Long.MAX_VALUE, true)));

        Assert.assertEquals(2, batch.duplicateCount);
        Assert.assertEquals(Long.MAX_VALUE, batch.duplicateBytes);
    }

    private static CodexImageAttachmentStore.MaterializedImage image(String digest,
                                                                      long size,
                                                                      boolean reused) {
        return new CodexImageAttachmentStore.MaterializedImage(
            new File("/tmp/" + digest + ".png"), digest, "png", size, 1, 1, reused);
    }
}
