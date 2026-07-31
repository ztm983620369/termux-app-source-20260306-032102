package com.termux.terminalsessionruntime;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SshZellijCommandFactoryTest {

    private final SshZellijCommandFactory factory = new SshZellijCommandFactory();

    @Test
    public void reconnectLoopRoundTripsIdentityAndNeverCreatesSession() {
        String ssh = "ssh -p 22023 root@127.0.0.1";
        String script = factory.buildReconnectLoopCommand(ssh, "mobile");

        assertTrue(factory.isReconnectLoop(script));
        assertEquals("mobile", factory.extractSessionFromReconnectLoop(script));
        assertEquals(ssh, factory.extractSshCommandFromReconnectLoop(script));
        assertTrue(script.contains("zellij attach mobile options --pane-frames false"));
        assertTrue(script.contains("--show-startup-tips false"));
        assertTrue(script.contains("--show-release-notes false"));
        assertFalse(script.contains("--create"));
    }

    @Test
    public void createAndDestroyQuoteSessionAsData() {
        String hostile = "name'; touch /tmp/nope; echo '";
        String create = factory.buildCreateSessionCommand("ssh host", hostile);
        String destroy = factory.buildDestroySessionCommand("ssh host", hostile);

        assertTrue(create.contains(SshZellijCommandFactory.SESSION_CREATED));
        assertTrue(destroy.contains(SshZellijCommandFactory.SESSION_DESTROYED));
        assertTrue(create.contains("layout { pane borderless=true; }"));
        assertFalse(create.contains("tab-bar"));
        assertFalse(create.contains("status-bar"));
        assertFalse(create.contains("compact-bar"));
        assertFalse(create.contains("--create-background -- name';"));
        assertFalse(destroy.contains("--force -- name';"));
        assertFalse(create.contains("grep "));
        assertTrue(create.contains("while IFS= read -r zellij_name"));
    }

    @Test
    public void optionLikeExistingSessionRemainsData() {
        String script = factory.buildReconnectLoopCommand("ssh host", "--forget");

        assertTrue(script.contains("zellij attach -- --forget"));
        assertFalse(script.contains("zellij attach --forget"));
    }

    @Test
    public void listProtocolIsDeterministicWhenThereAreNoSessions() {
        String command = factory.buildListSessionsCommand("ssh host");

        assertTrue(command.contains(SshZellijCommandFactory.LIST_ITEM_PREFIX));
        assertTrue(command.contains(SshZellijCommandFactory.LIST_DONE));
        assertTrue(command.contains(SshZellijCommandFactory.ZELLIJ_MISSING));
    }

    @Test
    public void existingRemoteIdentityKeepsInternalSpacing() {
        assertEquals("team  workspace", factory.normalizeSessionName("  team  workspace  "));
    }
}
