package com.termux.sessionsync;

import org.junit.Assert;
import org.junit.Test;

public class SftpProtocolManagerCodexAttachmentTest {

    private static final String DIGEST =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void buildsContentAddressedPathUnderRemoteCodexHome() {
        Assert.assertEquals(
            "/home/alice/.codex/termux-images/" + DIGEST + ".png",
            SftpProtocolManager.buildCodexAttachmentRemotePath("/home/alice", DIGEST, "png"));
    }

    @Test
    public void normalizesChrootHomeAndJpegExtension() {
        Assert.assertEquals(
            "/.codex/termux-images/" + DIGEST + ".jpg",
            SftpProtocolManager.buildCodexAttachmentRemotePath("/", DIGEST, "JPEG"));
    }

    @Test
    public void rejectsPathInjectionAndInvalidDigest() {
        Assert.assertEquals("", SftpProtocolManager.buildCodexAttachmentRemotePath(
            "/home/alice", DIGEST, "png/../../authorized_keys"));
        Assert.assertEquals("", SftpProtocolManager.buildCodexAttachmentRemotePath(
            "/home/alice", "not-a-digest", "png"));
    }

    @Test
    public void reusedRemoteObjectReportsZeroPayloadBytes() {
        Assert.assertEquals(0L,
            SftpProtocolManager.codexAttachmentUploadedBytes(true, 128L * 1024L));
        Assert.assertEquals(128L * 1024L,
            SftpProtocolManager.codexAttachmentUploadedBytes(false, 128L * 1024L));
        Assert.assertEquals(0L,
            SftpProtocolManager.codexAttachmentUploadedBytes(false, -1L));
    }
}
