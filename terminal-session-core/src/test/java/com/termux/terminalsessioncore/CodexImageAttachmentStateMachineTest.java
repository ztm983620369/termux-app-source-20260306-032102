package com.termux.terminalsessioncore;

import org.junit.Assert;
import org.junit.Test;

public class CodexImageAttachmentStateMachineTest {

    @Test
    public void localFlowSkipsTransferAndCompletes() {
        Assert.assertEquals(
            CodexImageAttachmentStateMachine.Route.LOCAL_FILESYSTEM,
            CodexImageAttachmentStateMachine.resolveRoute(false));
        Assert.assertTrue(CodexImageAttachmentStateMachine.canTransition(
            CodexImageAttachmentStateMachine.Phase.AWAITING_SELECTION,
            CodexImageAttachmentStateMachine.Phase.MATERIALIZING));
        Assert.assertTrue(CodexImageAttachmentStateMachine.canTransition(
            CodexImageAttachmentStateMachine.Phase.MATERIALIZING,
            CodexImageAttachmentStateMachine.Phase.READY_TO_INJECT));
        Assert.assertTrue(CodexImageAttachmentStateMachine.canTransition(
            CodexImageAttachmentStateMachine.Phase.READY_TO_INJECT,
            CodexImageAttachmentStateMachine.Phase.INJECTING));
        Assert.assertTrue(CodexImageAttachmentStateMachine.canTransition(
            CodexImageAttachmentStateMachine.Phase.INJECTING,
            CodexImageAttachmentStateMachine.Phase.COMPLETED));
    }

    @Test
    public void remoteFlowRequiresTransferBeforeInjection() {
        Assert.assertEquals(
            CodexImageAttachmentStateMachine.Route.REMOTE_SFTP,
            CodexImageAttachmentStateMachine.resolveRoute(true));
        Assert.assertTrue(CodexImageAttachmentStateMachine.canTransition(
            CodexImageAttachmentStateMachine.Phase.MATERIALIZING,
            CodexImageAttachmentStateMachine.Phase.TRANSFERRING));
        Assert.assertTrue(CodexImageAttachmentStateMachine.canTransition(
            CodexImageAttachmentStateMachine.Phase.TRANSFERRING,
            CodexImageAttachmentStateMachine.Phase.READY_TO_INJECT));
        Assert.assertFalse(CodexImageAttachmentStateMachine.canTransition(
            CodexImageAttachmentStateMachine.Phase.TRANSFERRING,
            CodexImageAttachmentStateMachine.Phase.COMPLETED));
    }

    @Test
    public void terminalStateCannotBeReopenedByLateCallback() {
        Assert.assertFalse(CodexImageAttachmentStateMachine.canTransition(
            CodexImageAttachmentStateMachine.Phase.COMPLETED,
            CodexImageAttachmentStateMachine.Phase.INJECTING));
        Assert.assertFalse(CodexImageAttachmentStateMachine.canTransition(
            CodexImageAttachmentStateMachine.Phase.FAILED,
            CodexImageAttachmentStateMachine.Phase.MATERIALIZING));
    }

    @Test
    public void injectionRequiresTheOriginalLiveTargetAndPayload() {
        Assert.assertTrue(CodexImageAttachmentStateMachine.canInject(true, true, true, 1));
        Assert.assertFalse(CodexImageAttachmentStateMachine.canInject(false, true, true, 1));
        Assert.assertFalse(CodexImageAttachmentStateMachine.canInject(true, false, true, 1));
        Assert.assertFalse(CodexImageAttachmentStateMachine.canInject(true, true, false, 1));
        Assert.assertFalse(CodexImageAttachmentStateMachine.canInject(true, true, true, 0));
    }
}
