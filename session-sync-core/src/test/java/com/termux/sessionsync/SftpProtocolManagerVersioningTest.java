package com.termux.sessionsync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SftpProtocolManagerVersioningTest {

    @Test
    public void unspecifiedExpectationAlwaysMatches() {
        assertTrue(SftpProtocolManager.matchesExpectedRemoteVersion(-1L, -1L, 1234L, 99L));
        assertTrue(SftpProtocolManager.matchesExpectedRemoteVersion(-1L, -1L, -1L, -1L));
    }

    @Test
    public void exactVersionMatches() {
        assertTrue(SftpProtocolManager.matchesExpectedRemoteVersion(1000L, 42L, 1000L, 42L));
        assertTrue(SftpProtocolManager.matchesExpectedRemoteVersion(1000L, -1L, 1000L, 99L));
        assertTrue(SftpProtocolManager.matchesExpectedRemoteVersion(-1L, 42L, 999L, 42L));
    }

    @Test
    public void mismatchIsRejected() {
        assertFalse(SftpProtocolManager.matchesExpectedRemoteVersion(1000L, 42L, 2000L, 42L));
        assertFalse(SftpProtocolManager.matchesExpectedRemoteVersion(1000L, 42L, 1000L, 84L));
        assertFalse(SftpProtocolManager.matchesExpectedRemoteVersion(1000L, 42L, -1L, -1L));
    }
}
