package com.termux.app.terminal;

import com.termux.terminalsessioncore.CodexRestoreStateMachine;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class TermuxSessionRestoreStoreTest {

    private static final String THREAD_A = "018f47d2-36a6-7b31-bc42-0e91d6a0b35f";
    private static final String THREAD_B = "018f47d2-36a6-7b31-bc42-0e91d6a0b360";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void protocolParsesVersionedReadyAndClosedEvents() throws Exception {
        File rollout = temporaryFolder.newFile("rollout.jsonl");
        writeRolloutHeader(rollout, THREAD_A);
        JSONObject ready = new JSONObject()
            .put("version", 1)
            .put("event", "ready")
            .put("thread_id", THREAD_A)
            .put("pid", 4242)
            .put("cwd", "/data/data/com.termux/files/home/project")
            .put("rollout_path", rollout.getAbsolutePath())
            .put("title", "Codex project");

        CodexSessionHostProtocol.Event readyEvent = CodexSessionHostProtocol.parse(ready.toString());
        Assert.assertNotNull(readyEvent);
        Assert.assertEquals(CodexRestoreStateMachine.HostEvent.READY, readyEvent.type);
        Assert.assertEquals(THREAD_A, readyEvent.threadId);
        Assert.assertEquals(4242, readyEvent.processId);
        Assert.assertTrue(readyEvent.hasDurableRollout());

        JSONObject closed = new JSONObject()
            .put("version", 1)
            .put("event", "closed")
            .put("thread_id", THREAD_A);
        CodexSessionHostProtocol.Event closedEvent = CodexSessionHostProtocol.parse(closed.toString());
        Assert.assertNotNull(closedEvent);
        Assert.assertEquals(CodexRestoreStateMachine.HostEvent.CLOSED, closedEvent.type);
    }

    @Test
    public void protocolRejectsInvalidIdentityVersionAndRelativeRollout() throws Exception {
        Assert.assertNull(CodexSessionHostProtocol.parse(new JSONObject()
            .put("version", 2)
            .put("event", "closed")
            .put("thread_id", THREAD_A)
            .toString()));
        Assert.assertNull(CodexSessionHostProtocol.parse(new JSONObject()
            .put("version", 1)
            .put("event", "closed")
            .put("thread_id", "not-a-uuid")
            .toString()));
        Assert.assertNull(CodexSessionHostProtocol.parse(new JSONObject()
            .put("version", 1)
            .put("event", "ready")
            .put("thread_id", THREAD_A)
            .put("pid", 4242)
            .put("rollout_path", "relative.jsonl")
            .toString()));
        Assert.assertNull(CodexSessionHostProtocol.parse(new JSONObject()
            .put("version", 1)
            .put("event", "ready")
            .put("thread_id", THREAD_A)
            .put("rollout_path", "/tmp/rollout.jsonl")
            .toString()));
    }

    @Test
    public void durableRolloutMustBelongToPublishedThread() throws Exception {
        File rollout = temporaryFolder.newFile("wrong-thread.jsonl");
        writeRolloutHeader(rollout, THREAD_B);
        CodexSessionHostProtocol.Event event = CodexSessionHostProtocol.parse(new JSONObject()
            .put("version", 1)
            .put("event", "ready")
            .put("thread_id", THREAD_A)
            .put("pid", 4242)
            .put("rollout_path", rollout.getAbsolutePath())
            .toString());

        Assert.assertNotNull(event);
        Assert.assertFalse(event.hasDurableRollout());
    }

    @Test
    public void processStatParserHandlesSpacesAndParenthesesInCommand() {
        Assert.assertEquals(1234,
            CodexProcessIdentity.parseParentPid("99 (codex worker (1)) S 1234 10 10 0"));
        Assert.assertEquals(-1, CodexProcessIdentity.parseParentPid("invalid"));
    }

    @Test
    public void processIdentityFallsBackToControllingTerminal() {
        Assert.assertTrue(CodexProcessIdentity.processStatsShareTerminal(
            "99 (bash) S 1 99 99 34817",
            "100 (codex worker) S 1 100 99 34817"));
        Assert.assertFalse(CodexProcessIdentity.processStatsShareTerminal(
            "99 (bash) S 1 99 99 34817",
            "100 (codex) S 1 100 99 34818"));
    }

    @Test
    public void verifiedProcessSelectionPrefersDescendantAndRequiresAttachedLease() {
        Assert.assertEquals(101,
            CodexProcessIdentity.selectVerifiedProcessId(101, 202, true));
        Assert.assertEquals(202,
            CodexProcessIdentity.selectVerifiedProcessId(-1, 202, true));
        Assert.assertEquals(-1,
            CodexProcessIdentity.selectVerifiedProcessId(-1, 202, false));
        Assert.assertEquals(-1,
            CodexProcessIdentity.selectVerifiedProcessId(-1, -1, true));
    }

    @Test
    public void restartBackoffIsBoundedAndDeterministic() {
        Assert.assertEquals(250L, CodexSessionRecoveryController.computeRestartDelayMs(0));
        Assert.assertEquals(500L, CodexSessionRecoveryController.computeRestartDelayMs(1));
        Assert.assertEquals(30_000L, CodexSessionRecoveryController.computeRestartDelayMs(30));
        Assert.assertFalse(CodexSessionRecoveryController.shouldSuspendRestart(3));
        Assert.assertTrue(CodexSessionRecoveryController.shouldSuspendRestart(4));
        Assert.assertFalse(CodexSessionRecoveryController.shouldResetRestartAttempts(119_999L));
        Assert.assertTrue(CodexSessionRecoveryController.shouldResetRestartAttempts(120_000L));
        Assert.assertTrue(CodexSessionRecoveryController.isIntentionalExitStatus(0));
        Assert.assertTrue(CodexSessionRecoveryController.isIntentionalExitStatus(130));
        Assert.assertFalse(CodexSessionRecoveryController.isIntentionalExitStatus(1));
        Assert.assertFalse(CodexSessionRecoveryController.isIntentionalExitStatus(137));
        Assert.assertTrue(CodexSessionRecoveryController.isTerminalStartupPending(0));
        Assert.assertFalse(CodexSessionRecoveryController.isTerminalStartupPending(-1));
        Assert.assertFalse(CodexSessionRecoveryController.isTerminalStartupPending(42));
    }

    @Test
    public void readyReplacesSameTabProjectionAndPreservesOtherSessionTypes() throws Exception {
        File rollout = temporaryFolder.newFile("ready.jsonl");
        writeRolloutHeader(rollout, THREAD_A);
        JSONObject root = new JSONObject()
            .put("foreground_key", "shell:0:handle-a")
            .put("foreground_handle", "handle-a")
            .put("foreground_order", 0)
            .put("sessions", new JSONArray()
                .put(shellRecord("handle-a", 0))
                .put(sshRecord("handle-ssh", 1)));
        CodexSessionHostProtocol.Event event = new CodexSessionHostProtocol.Event(
            CodexRestoreStateMachine.HostEvent.READY,
            THREAD_A,
            4242,
            "/data/data/com.termux/files/home/project",
            rollout.getAbsolutePath(),
            "Codex project");

        TermuxSessionRestoreStore.UpdateResult result =
            TermuxSessionRestoreStore.updateCodexEventInMemory(root, "handle-a", 0, event);

        Assert.assertEquals(TermuxSessionRestoreStore.UpdateResult.APPLIED, result);
        Assert.assertEquals(TermuxSessionRestoreStore.SCHEMA_VERSION, root.getInt("version"));
        Assert.assertEquals("codex:" + THREAD_A, root.getString("foreground_key"));
        Assert.assertEquals(2, root.getJSONArray("sessions").length());
        JSONObject codex = findByType(root.getJSONArray("sessions"), "codex");
        Assert.assertNotNull(codex);
        Assert.assertEquals("handle-a", codex.getString("handle"));
        Assert.assertEquals(THREAD_A, codex.getString("codex_thread_id"));
        Assert.assertNotNull(findByType(root.getJSONArray("sessions"), "ssh"));
    }

    @Test
    public void newThreadOnSameTabDisplacesButDoesNotRevokePreviousLease() throws Exception {
        File rollout = temporaryFolder.newFile("replacement-ready.jsonl");
        writeRolloutHeader(rollout, THREAD_B);
        JSONObject root = new JSONObject()
            .put("foreground_key", "codex:" + THREAD_A)
            .put("foreground_handle", "handle-a")
            .put("foreground_order", 0)
            .put("sessions", new JSONArray().put(codexRecord("handle-a", THREAD_A, 0)));
        CodexSessionHostProtocol.Event replacement = new CodexSessionHostProtocol.Event(
            CodexRestoreStateMachine.HostEvent.READY,
            THREAD_B,
            5252,
            "/data/data/com.termux/files/home/project",
            rollout.getAbsolutePath(),
            "Codex replacement");

        TermuxSessionRestoreStore.UpdateResult result =
            TermuxSessionRestoreStore.updateCodexEventInMemory(root, "handle-a", 0, replacement);

        Assert.assertEquals(TermuxSessionRestoreStore.UpdateResult.APPLIED, result);
        Assert.assertEquals(2, root.getJSONArray("sessions").length());
        JSONObject previous = findCodexByThread(root.getJSONArray("sessions"), THREAD_A);
        JSONObject current = findCodexByThread(root.getJSONArray("sessions"), THREAD_B);
        Assert.assertNotNull(previous);
        Assert.assertNotNull(current);
        Assert.assertEquals("", previous.getString("handle"));
        Assert.assertEquals("handle-a", current.getString("handle"));
        Assert.assertEquals("codex:" + THREAD_B, root.getString("foreground_key"));
    }

    @Test
    public void staleClosedCannotDeleteAReplacementThread() throws Exception {
        JSONObject root = new JSONObject().put("sessions", new JSONArray()
            .put(codexRecord("handle-a", THREAD_B, 0)));
        CodexSessionHostProtocol.Event stale = new CodexSessionHostProtocol.Event(
            CodexRestoreStateMachine.HostEvent.CLOSED, THREAD_A, -1, "", "", "");

        TermuxSessionRestoreStore.UpdateResult result =
            TermuxSessionRestoreStore.updateCodexEventInMemory(root, "handle-a", 0, stale);

        Assert.assertEquals(TermuxSessionRestoreStore.UpdateResult.IGNORED, result);
        Assert.assertEquals(THREAD_B,
            root.getJSONArray("sessions").getJSONObject(0).getString("codex_thread_id"));
    }

    @Test
    public void matchingClosedRemovesOnlyMatchingCodexIdentity() throws Exception {
        JSONObject root = new JSONObject()
            .put("foreground_key", "codex:" + THREAD_A)
            .put("foreground_handle", "handle-a")
            .put("foreground_order", 0)
            .put("sessions", new JSONArray()
                .put(codexRecord("handle-a", THREAD_A, 0))
                .put(sshRecord("handle-ssh", 1)));
        CodexSessionHostProtocol.Event closed = new CodexSessionHostProtocol.Event(
            CodexRestoreStateMachine.HostEvent.CLOSED, THREAD_A, -1, "", "", "");

        TermuxSessionRestoreStore.UpdateResult result =
            TermuxSessionRestoreStore.updateCodexEventInMemory(root, "handle-a", 0, closed);

        Assert.assertEquals(TermuxSessionRestoreStore.UpdateResult.APPLIED, result);
        Assert.assertEquals("", root.getString("foreground_key"));
        Assert.assertEquals("", root.getString("foreground_handle"));
        Assert.assertEquals(1, root.getJSONArray("sessions").length());
        Assert.assertNull(findByType(root.getJSONArray("sessions"), "codex"));
        Assert.assertNotNull(findByType(root.getJSONArray("sessions"), "ssh"));
    }

    @Test
    public void genericSnapshotCannotDropMissingCodexLease() throws Exception {
        JSONObject durable = new JSONObject()
            .put("foreground_key", "codex:" + THREAD_A)
            .put("foreground_handle", "handle-a")
            .put("foreground_order", 0)
            .put("sessions", new JSONArray()
                .put(codexRecord("handle-a", THREAD_A, 0))
                .put(sshRecord("handle-ssh", 1)));
        JSONObject candidate = new JSONObject()
            .put("sessions", new JSONArray().put(sshRecord("handle-ssh", 0)));

        int retained = TermuxSessionRestoreStore.retainMissingCodexLeasesInMemory(candidate, durable);

        Assert.assertEquals(1, retained);
        Assert.assertEquals(2, candidate.getJSONArray("sessions").length());
        Assert.assertEquals("codex:" + THREAD_A, candidate.getString("foreground_key"));
        Assert.assertEquals("handle-a", candidate.getString("foreground_handle"));
        Assert.assertEquals(4242,
            findByType(candidate.getJSONArray("sessions"), "codex").getInt("codex_pid"));
    }

    @Test
    public void genericSnapshotDoesNotReplaceFreshCodexPidWithStaleDurablePid() throws Exception {
        JSONObject durableCodex = codexRecord("old-handle", THREAD_A, 0).put("codex_pid", 4242);
        JSONObject candidateCodex = codexRecord("new-handle", THREAD_A, 0).put("codex_pid", 5252);
        JSONObject durable = new JSONObject().put("sessions", new JSONArray().put(durableCodex));
        JSONObject candidate = new JSONObject().put("sessions", new JSONArray().put(candidateCodex));

        int retained = TermuxSessionRestoreStore.retainMissingCodexLeasesInMemory(candidate, durable);

        Assert.assertEquals(0, retained);
        Assert.assertEquals(5252,
            findByType(candidate.getJSONArray("sessions"), "codex").getInt("codex_pid"));
    }

    @Test
    public void restoreCommandUsesCodexHomeOwningTheDurableRollout() throws Exception {
        File codexHome = temporaryFolder.newFolder("isolated-codex-home");
        File sessions = new File(codexHome, "sessions/2026/07/23");
        Assert.assertTrue(sessions.mkdirs());
        File rollout = new File(sessions, "rollout-" + THREAD_A + ".jsonl");
        writeRolloutHeader(rollout, THREAD_A);

        Assert.assertEquals(codexHome.getCanonicalPath(),
            CodexSessionRecoveryController.resolveCodexHomeFromRolloutPath(rollout.getAbsolutePath()));
        String command = CodexSessionRecoveryController.buildRestoreCommand(
            THREAD_A, "/data/data/com.termux/files/home/project", rollout.getAbsolutePath());

        Assert.assertTrue(command.contains("rollout_path='" + rollout.getAbsolutePath() + "'"));
        Assert.assertTrue(command.contains("codex_home='" + codexHome.getCanonicalPath() + "'"));
        Assert.assertTrue(command.contains("export CODEX_HOME=\"$codex_home\""));
        Assert.assertTrue(command.endsWith("resume '" + THREAD_A + "'"));
        Assert.assertEquals("",
            CodexSessionRecoveryController.resolveCodexHomeFromRolloutPath(
                "/data/data/com.termux/files/home/not-a-session/rollout.jsonl"));
    }

    @Test
    public void codexCtlClaudeRestoreRestartsTheOwningBridgeInstance() {
        String controlHome = "/data/data/com.termux/files/home/.local/share/codexctl";
        String codexHome = controlHome + "/claude/instances/main/codex";
        String rollout = codexHome + "/sessions/2026/07/23/rollout-" + THREAD_A + ".jsonl";

        Assert.assertEquals(controlHome,
            CodexSessionRecoveryController.resolveCodexCtlControlHome(codexHome));
        Assert.assertEquals("main",
            CodexSessionRecoveryController.resolveCodexCtlClaudeInstance(codexHome));
        String command = CodexSessionRecoveryController.buildRestoreCommand(
            THREAD_A, "/data/data/com.termux/files/home", rollout);

        Assert.assertTrue(command.contains(
            "codexctl_cmd='/data/data/com.termux/files/usr/bin/codexctl'"));
        Assert.assertTrue(command.contains("--control-home '" + controlHome + "'"));
        Assert.assertTrue(command.contains("--codex-bin '/data/data/com.termux/files/usr/bin/codex'"));
        Assert.assertTrue(command.endsWith("claude run 'main' -- resume '" + THREAD_A + "'"));
        Assert.assertFalse(command.contains("export CODEX_HOME"));
    }

    @Test
    public void rebindAndExplicitRemovalAreAtomicStateTransitions() throws Exception {
        JSONObject root = new JSONObject()
            .put("foreground_key", "codex:" + THREAD_A)
            .put("foreground_handle", "old-handle")
            .put("foreground_order", 0)
            .put("sessions", new JSONArray()
                .put(codexRecord("old-handle", THREAD_A, 0))
                .put(sshRecord("handle-ssh", 1)));

        TermuxSessionRestoreStore.UpdateResult rebound =
            TermuxSessionRestoreStore.rebindCodexLeaseInMemory(root, THREAD_A, "new-handle", 1);

        Assert.assertEquals(TermuxSessionRestoreStore.UpdateResult.APPLIED, rebound);
        Assert.assertEquals("new-handle", root.getString("foreground_handle"));
        JSONObject codex = findByType(root.getJSONArray("sessions"), "codex");
        Assert.assertNotNull(codex);
        Assert.assertEquals("new-handle", codex.getString("handle"));
        Assert.assertEquals(1, codex.getInt("order"));

        TermuxSessionRestoreStore.UpdateResult removed =
            TermuxSessionRestoreStore.removeCodexLeaseInMemory(root, THREAD_A, "new-handle");

        Assert.assertEquals(TermuxSessionRestoreStore.UpdateResult.APPLIED, removed);
        Assert.assertEquals("", root.getString("foreground_key"));
        Assert.assertEquals("", root.getString("foreground_handle"));
        Assert.assertEquals(1, root.getJSONArray("sessions").length());
        Assert.assertEquals("ssh", root.getJSONArray("sessions").getJSONObject(0).getString("type"));
    }

    private static JSONObject shellRecord(String handle, int order) throws Exception {
        return new JSONObject()
            .put("key", "shell:" + order + ":" + handle)
            .put("type", "shell")
            .put("handle", handle)
            .put("order", order);
    }

    private static JSONObject sshRecord(String handle, int order) throws Exception {
        return new JSONObject()
            .put("key", "ssh:test:" + order)
            .put("type", "ssh")
            .put("handle", handle)
            .put("order", order);
    }

    private static JSONObject codexRecord(String handle, String threadId, int order) throws Exception {
        return new JSONObject()
            .put("key", "codex:" + threadId)
            .put("type", "codex")
            .put("handle", handle)
            .put("codex_thread_id", threadId)
            .put("codex_pid", 4242)
            .put("order", order);
    }

    private static JSONObject findByType(JSONArray sessions, String type) {
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject item = sessions.optJSONObject(i);
            if (item != null && type.equals(item.optString("type"))) return item;
        }
        return null;
    }

    private static JSONObject findCodexByThread(JSONArray sessions, String threadId) {
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject item = sessions.optJSONObject(i);
            if (item != null && "codex".equals(item.optString("type")) &&
                threadId.equals(item.optString("codex_thread_id"))) {
                return item;
            }
        }
        return null;
    }

    private static void writeRolloutHeader(File rollout, String threadId) throws Exception {
        JSONObject record = new JSONObject()
            .put("timestamp", "2026-07-19T00:00:00Z")
            .put("type", "session_meta")
            .put("payload", new JSONObject().put("id", threadId));
        try (FileOutputStream out = new FileOutputStream(rollout)) {
            out.write((record.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }
}
