package com.termux.app.editor;

import org.junit.Assert;
import org.junit.Test;

public class EditorTerminalWorkspaceStateMachineTest {

    @Test
    public void openMovesStateToTerminalWorkspace() {
        EditorTerminalWorkspaceStateMachine machine = new EditorTerminalWorkspaceStateMachine();

        Assert.assertTrue(machine.openTerminalWorkspace());
        Assert.assertEquals(EditorTerminalWorkspaceStateMachine.State.TERMINAL_WORKSPACE, machine.getState());
        Assert.assertTrue(machine.isTerminalWorkspaceVisible());
    }

    @Test
    public void closeReturnsToCode() {
        EditorTerminalWorkspaceStateMachine machine = new EditorTerminalWorkspaceStateMachine();
        machine.openTerminalWorkspace();

        Assert.assertTrue(machine.closeTerminalWorkspace());
        Assert.assertEquals(EditorTerminalWorkspaceStateMachine.State.CODE, machine.getState());
        Assert.assertFalse(machine.isTerminalWorkspaceVisible());
    }

    @Test
    public void backPressClosesVisibleWorkspace() {
        EditorTerminalWorkspaceStateMachine machine = new EditorTerminalWorkspaceStateMachine();
        machine.openTerminalWorkspace();

        Assert.assertTrue(machine.onBackPressed());
        Assert.assertEquals(EditorTerminalWorkspaceStateMachine.State.CODE, machine.getState());
    }
}
