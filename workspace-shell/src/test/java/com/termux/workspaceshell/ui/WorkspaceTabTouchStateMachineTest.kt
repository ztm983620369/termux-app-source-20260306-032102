package com.termux.workspaceshell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTabTouchStateMachineTest {
    @Test
    fun clickWithoutMoveReleasesClick() {
        val machine = WorkspaceTabTouchStateMachine(8f)

        machine.onDown(0f, 0f)

        assertFalse(machine.onMove(3f, 0f))
        assertEquals(WorkspaceTabTouchStateMachine.ReleaseAction.CLICK, machine.onUp())
    }

    @Test
    fun movePastThresholdReleasesNoClick() {
        val machine = WorkspaceTabTouchStateMachine(8f)

        machine.onDown(0f, 0f)

        assertTrue(machine.onMove(9f, 0f))

        assertEquals(WorkspaceTabTouchStateMachine.ReleaseAction.NONE, machine.onUp())
    }
}
