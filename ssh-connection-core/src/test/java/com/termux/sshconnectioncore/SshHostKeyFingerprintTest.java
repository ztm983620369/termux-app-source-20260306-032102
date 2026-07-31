package com.termux.sshconnectioncore;

import org.junit.Assert;
import org.junit.Test;

public class SshHostKeyFingerprintTest {

    @Test
    public void computesCanonicalOpenSshFingerprintWithoutPadding() {
        Assert.assertEquals(
            "SHA256:rksygOVuL6+D9BSm49q+nV++GJdlRMBf7RIazLhbU/w",
            SshHostKeyFingerprint.fromPublicKeyBlob(new byte[]{0, 1, 2}));
    }

    @Test
    public void normalizesOnlyPrefixAndPadding() {
        String payload = "rksygOVuL6+D9BSm49q+nV++GJdlRMBf7RIazLhbU/w";
        Assert.assertEquals("SHA256:" + payload,
            SshHostKeyFingerprint.normalizeSha256("sha256:" + payload + "="));
        Assert.assertEquals("", SshHostKeyFingerprint.normalizeSha256("SHA256:too-short"));
    }
}
