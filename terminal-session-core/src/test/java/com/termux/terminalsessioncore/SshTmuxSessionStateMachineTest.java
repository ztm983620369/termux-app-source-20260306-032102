package com.termux.terminalsessioncore;

import org.junit.Assert;
import org.junit.Test;

public class SshTmuxSessionStateMachineTest {

    @Test
    public void planNewManagedSessionKeepsUnicodeDisplayName() {
        SshTmuxSessionStateMachine.Snapshot snapshot =
            SshTmuxSessionStateMachine.planNewManagedSession("中文会话", "fallback", "ssh root@example.com", "h1");

        Assert.assertEquals("中文会话", snapshot.displayName);
        Assert.assertEquals(SshTmuxSessionStateMachine.DisplayState.EXPLICIT_INPUT, snapshot.displayState);
        Assert.assertEquals(SshTmuxSessionStateMachine.RemoteState.GENERATED_MANAGED_ID, snapshot.remoteState);
        Assert.assertTrue(snapshot.remoteSessionName.startsWith(SshTmuxSessionStateMachine.MANAGED_REMOTE_SESSION_PREFIX));
    }

    @Test
    public void planNewManagedSessionUsesDirectRemoteNameWhenSafe() {
        SshTmuxSessionStateMachine.Snapshot snapshot =
            SshTmuxSessionStateMachine.planNewManagedSession("prod-api", "fallback", "ssh root@example.com", "h1");

        Assert.assertEquals("prod-api", snapshot.displayName);
        Assert.assertEquals("prod-api", snapshot.remoteSessionName);
        Assert.assertEquals(SshTmuxSessionStateMachine.RemoteState.DIRECT_USER_NAME, snapshot.remoteState);
    }

    @Test
    public void displayNameHexRoundTripSupportsUnicode() {
        String encoded = SshTmuxSessionStateMachine.encodeDisplayNameHex("中文 Name 01");
        Assert.assertFalse(encoded.isEmpty());
        Assert.assertEquals("中文 Name 01", SshTmuxSessionStateMachine.decodeDisplayNameHex(encoded));
    }

    @Test
    public void resolveExistingRemotePrefersRemoteMetadata() {
        String encoded = SshTmuxSessionStateMachine.encodeDisplayNameHex("服务器标签");
        SshTmuxSessionStateMachine.Snapshot snapshot =
            SshTmuxSessionStateMachine.resolveExistingRemote("termux-persist-v2-abc", encoded, "本地标签", "ssh-persistent-1", null);

        Assert.assertEquals("服务器标签", snapshot.displayName);
        Assert.assertEquals(SshTmuxSessionStateMachine.DisplayState.REMOTE_METADATA, snapshot.displayState);
    }

    @Test
    public void resolveExistingRemoteFallsBackToRemoteSessionName() {
        SshTmuxSessionStateMachine.Snapshot snapshot =
            SshTmuxSessionStateMachine.resolveExistingRemote("legacy-session", "", "", "ssh-persistent-1", "");

        Assert.assertEquals("legacy-session", snapshot.displayName);
        Assert.assertEquals(SshTmuxSessionStateMachine.DisplayState.REMOTE_SESSION_NAME, snapshot.displayState);
    }
}
