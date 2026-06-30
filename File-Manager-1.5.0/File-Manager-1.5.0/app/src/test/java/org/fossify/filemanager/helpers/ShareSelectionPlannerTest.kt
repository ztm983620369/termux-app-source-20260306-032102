package org.fossify.filemanager.helpers

import org.fossify.commons.models.FileDirItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareSelectionPlannerTest {
    @Test
    fun emptySelectionIsNotShareable() {
        val plan = ShareSelectionPlanner.build(emptyList())

        assertFalse(plan.hasShareableItems)
        assertFalse(plan.needsPreparationDialog)
    }

    @Test
    fun localFilesCanShareWithoutPreparationDialog() {
        val plan = ShareSelectionPlanner.build(
            listOf(
                FileDirItem("/tmp/a.zip", "a.zip", isDirectory = false),
                FileDirItem("/tmp/b.txt", "b.txt", isDirectory = false)
            )
        )

        assertTrue(plan.hasShareableItems)
        assertFalse(plan.needsPreparationDialog)
        assertEquals(2, plan.localItems.size)
        assertEquals(0, plan.remoteItems.size)
    }

    @Test
    fun localDirectoryUsesPreparationDialogForTreeExpansion() {
        val plan = ShareSelectionPlanner.build(
            listOf(FileDirItem("/tmp/project", "project", isDirectory = true))
        )

        assertTrue(plan.hasShareableItems)
        assertTrue(plan.needsPreparationDialog)
    }

    @Test
    fun remoteSelectionUsesPreparationDialogForMaterialization() {
        val plan = ShareSelectionPlanner.build(
            listOf(
                FileDirItem("/home/.termux/sftp-virtual/server/archive.tar.gz", "archive.tar.gz", isDirectory = false),
                FileDirItem("/tmp/local.apk", "local.apk", isDirectory = false)
            )
        ) { path ->
            path.contains("/.termux/sftp-virtual/")
        }

        assertTrue(plan.hasShareableItems)
        assertTrue(plan.needsPreparationDialog)
        assertEquals(1, plan.localItems.size)
        assertEquals(1, plan.remoteItems.size)
    }
}
