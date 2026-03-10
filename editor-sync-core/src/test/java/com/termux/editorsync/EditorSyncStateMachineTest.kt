package com.termux.editorsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSyncStateMachineTest {

    @Test
    fun `auto save enabled spins while dirty and ends green`() {
        val machine = EditorSyncStateMachine()
        val target = EditorSyncTarget(localPath = "/tmp/demo.txt")

        var snapshot = machine.bindDocument(target, autoSaveEnabled = true, nowMs = 1L)
        assertEquals(EditorSyncIndicatorState.SYNCED, snapshot.indicatorState)
        assertFalse(snapshot.hasUnsavedChanges)

        snapshot = machine.onContentChanged()
        assertEquals(EditorSyncIndicatorState.SPINNING, snapshot.indicatorState)
        assertTrue(snapshot.hasUnsavedChanges)

        snapshot = machine.onSaveStarted(EditorSaveTrigger.AUTO, snapshot.currentRevision)
        assertEquals(EditorSyncIndicatorState.SPINNING, snapshot.indicatorState)

        snapshot = machine.onSaveSucceeded(EditorSaveTrigger.AUTO, snapshot.currentRevision, completedAtMs = 2L)
        assertEquals(EditorSyncIndicatorState.SYNCED, snapshot.indicatorState)
        assertFalse(snapshot.hasUnsavedChanges)
        assertEquals(2L, snapshot.lastSuccessfulSaveAtMs)
    }

    @Test
    fun `manual save stays hidden when autosave disabled`() {
        val machine = EditorSyncStateMachine()
        val target = EditorSyncTarget(localPath = "/tmp/demo.txt")

        var snapshot = machine.bindDocument(target, autoSaveEnabled = false)
        assertEquals(EditorSyncIndicatorState.HIDDEN, snapshot.indicatorState)

        snapshot = machine.onContentChanged()
        assertEquals(EditorSyncIndicatorState.HIDDEN, snapshot.indicatorState)
        assertTrue(snapshot.hasUnsavedChanges)

        snapshot = machine.onSaveStarted(EditorSaveTrigger.MANUAL, snapshot.currentRevision)
        assertEquals(EditorSyncIndicatorState.SPINNING, snapshot.indicatorState)

        snapshot = machine.onSaveSucceeded(EditorSaveTrigger.MANUAL, snapshot.currentRevision)
        assertEquals(EditorSyncIndicatorState.HIDDEN, snapshot.indicatorState)
        assertFalse(snapshot.hasUnsavedChanges)
    }

    @Test
    fun `save failure keeps autosave dirty state spinning`() {
        val machine = EditorSyncStateMachine()
        val target = EditorSyncTarget(
            localPath = "/tmp/remote.txt",
            kind = EditorSyncTargetKind.SFTP_VIRTUAL_FILE,
            originPath = "/virtual/session/remote.txt"
        )

        machine.bindDocument(target, autoSaveEnabled = true)
        var snapshot = machine.onContentChanged()
        snapshot = machine.onSaveStarted(EditorSaveTrigger.AUTO, snapshot.currentRevision)
        snapshot = machine.onSaveFailed(EditorSaveTrigger.AUTO, snapshot.currentRevision, "network down")

        assertEquals(EditorSyncIndicatorState.SPINNING, snapshot.indicatorState)
        assertTrue(snapshot.hasUnsavedChanges)
        assertEquals("network down", snapshot.lastError)
    }
}
