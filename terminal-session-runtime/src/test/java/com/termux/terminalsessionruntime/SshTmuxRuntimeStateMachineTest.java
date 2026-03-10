package com.termux.terminalsessionruntime;

import org.junit.Assert;
import org.junit.Test;

public class SshTmuxRuntimeStateMachineTest {

    @Test
    public void nextProducesExpectedSnapshot() {
        SshTmuxRuntimeStateMachine machine = new SshTmuxRuntimeStateMachine();
        SshTmuxRuntimeStateMachine.Snapshot snapshot =
            machine.next(SshTmuxRuntimeStateMachine.Phase.CREATING_REMOTE, "tmux-a", "中文", 2,
                "ok", "op-1", "session-1");

        Assert.assertEquals(SshTmuxRuntimeStateMachine.Phase.CREATING_REMOTE, snapshot.phase);
        Assert.assertEquals("tmux-a", snapshot.tmuxSession);
        Assert.assertEquals("中文", snapshot.displayName);
        Assert.assertEquals(2, snapshot.attempt);
        Assert.assertEquals("ok", snapshot.detail);
        Assert.assertEquals("op-1", snapshot.operationId);
        Assert.assertEquals("session-1", snapshot.sessionHandle);
    }
}
