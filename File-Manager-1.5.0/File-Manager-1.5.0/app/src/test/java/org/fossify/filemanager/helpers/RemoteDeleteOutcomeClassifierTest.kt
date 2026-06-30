package org.fossify.filemanager.helpers

import com.termux.sessionsync.SftpProtocolManager
import org.fossify.commons.models.FileDirItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDeleteOutcomeClassifierTest {
    @Test
    fun successResultMapsToSuccess() {
        val outcome = RemoteDeleteOutcomeClassifier.fromResult(
            result(success = true, deletedItems = 2, failedItems = 0, message = "删除完成"),
            cancelRequested = false
        )

        assertEquals(ActiveTransferStatus.SUCCESS, outcome.status)
    }

    @Test
    fun cancelledResultMapsToCancelled() {
        val outcome = RemoteDeleteOutcomeClassifier.fromResult(
            result(success = false, cancelled = true, deletedItems = 1, failedItems = 0, message = "删除已取消"),
            cancelRequested = true
        )

        assertEquals(ActiveTransferStatus.CANCELLED, outcome.status)
    }

    @Test
    fun partialResultMapsToPartial() {
        val outcome = RemoteDeleteOutcomeClassifier.fromResult(
            result(success = false, deletedItems = 1, failedItems = 1, message = "删除部分完成"),
            cancelRequested = false
        )

        assertEquals(ActiveTransferStatus.PARTIAL, outcome.status)
    }

    @Test
    fun failedResultMapsToFailed() {
        val outcome = RemoteDeleteOutcomeClassifier.fromResult(
            result(success = false, deletedItems = 0, failedItems = 2, message = "删除失败"),
            cancelRequested = false
        )

        assertEquals(ActiveTransferStatus.FAILED, outcome.status)
    }

    @Test
    fun shouldShowProgressWindowUsesCountOrDirectoryThreshold() {
        assertFalse(RemoteDeleteCoordinator.shouldShowProgressWindow(List(5) { FileDirItem("/v/$it", isDirectory = false) }))
        assertTrue(RemoteDeleteCoordinator.shouldShowProgressWindow(List(6) { FileDirItem("/v/$it", isDirectory = false) }))
        assertTrue(RemoteDeleteCoordinator.shouldShowProgressWindow(listOf(FileDirItem("/v/dir", isDirectory = true))))
    }

    private fun result(
        success: Boolean,
        cancelled: Boolean = false,
        deletedItems: Int,
        failedItems: Int,
        message: String
    ): SftpProtocolManager.RemoteDeleteResult {
        return SftpProtocolManager.RemoteDeleteResult(
            success,
            cancelled,
            deletedItems + failedItems,
            deletedItems + failedItems,
            deletedItems,
            failedItems,
            0,
            1000L,
            arrayListOf<String>(),
            arrayListOf<SftpProtocolManager.RemoteDeleteItemResult>(),
            message
        )
    }
}
