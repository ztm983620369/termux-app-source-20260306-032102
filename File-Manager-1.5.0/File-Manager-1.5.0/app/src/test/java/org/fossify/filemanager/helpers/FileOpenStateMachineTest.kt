package org.fossify.filemanager.helpers

import com.termux.bridge.FileOpenRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOpenStateMachineTest {
    @Test
    fun imageMimeTypeOpensWithSystemViewer() {
        val decision = FileOpenStateMachine.decide(
            requestInput(
                path = "/data/data/com.termux/files/home/photo.bin",
                mimeType = "image/png"
            )
        )

        val action = decision.action
        assertEquals(FileOpenStateMachine.ContentKind.IMAGE, decision.contentKind)
        assertTrue(action is FileOpenStateMachine.Action.OpenWithSystemViewer)
        assertEquals(OPEN_AS_IMAGE, (action as FileOpenStateMachine.Action.OpenWithSystemViewer).openAsType)
    }

    @Test
    fun imageExtensionOpensWithSystemViewerWhenMimeTypeIsMissing() {
        val decision = FileOpenStateMachine.decide(
            requestInput(
                path = "/data/data/com.termux/files/home/DCIM/IMG_001.HEIC",
                mimeType = null
            )
        )

        assertEquals(FileOpenStateMachine.ContentKind.IMAGE, decision.contentKind)
        assertTrue(decision.action is FileOpenStateMachine.Action.OpenWithSystemViewer)
    }

    @Test
    fun nonImageDefaultOpenUsesEditorRequest() {
        val request = FileOpenRequest(
            path = "/data/data/com.termux/files/home/notes.txt",
            displayName = "notes.txt",
            extension = "txt",
            mimeType = "text/plain"
        )

        val decision = FileOpenStateMachine.decide(
            FileOpenStateMachine.Input(
                path = request.path,
                displayName = request.displayName,
                extension = request.extension,
                mimeType = request.mimeType,
                request = request
            )
        )

        val action = decision.action
        assertEquals(FileOpenStateMachine.ContentKind.OTHER, decision.contentKind)
        assertTrue(action is FileOpenStateMachine.Action.OpenInEditor)
        assertEquals(request, (action as FileOpenStateMachine.Action.OpenInEditor).request)
    }

    @Test
    fun openWithChooserOverridesDefaultEditorPath() {
        val decision = FileOpenStateMachine.decide(
            requestInput(
                path = "/data/data/com.termux/files/home/notes.txt",
                mimeType = "text/plain",
                trigger = FileOpenStateMachine.Trigger.OPEN_WITH_CHOOSER
            )
        )

        val action = decision.action
        assertTrue(action is FileOpenStateMachine.Action.OpenWithSystemViewer)
        assertTrue((action as FileOpenStateMachine.Action.OpenWithSystemViewer).forceChooser)
        assertEquals(OPEN_AS_DEFAULT, action.openAsType)
    }

    @Test
    fun openAsTextOverridesImageDefaultViewer() {
        val decision = FileOpenStateMachine.decide(
            requestInput(
                path = "/data/data/com.termux/files/home/photo.jpg",
                mimeType = "image/jpeg",
                trigger = FileOpenStateMachine.Trigger.OPEN_AS,
                openAsType = OPEN_AS_TEXT
            )
        )

        assertEquals(FileOpenStateMachine.ContentKind.IMAGE, decision.contentKind)
        assertTrue(decision.action is FileOpenStateMachine.Action.OpenInEditor)
    }

    private fun requestInput(
        path: String,
        mimeType: String?,
        trigger: FileOpenStateMachine.Trigger = FileOpenStateMachine.Trigger.DEFAULT_OPEN,
        openAsType: Int = OPEN_AS_DEFAULT
    ): FileOpenStateMachine.Input {
        val displayName = path.substringAfterLast('/')
        val extension = displayName.substringAfterLast('.', "").ifBlank { null }
        val request = FileOpenRequest(
            path = path,
            displayName = displayName,
            extension = extension,
            mimeType = mimeType
        )
        return FileOpenStateMachine.Input(
            path = path,
            displayName = displayName,
            extension = extension,
            mimeType = mimeType,
            request = request,
            trigger = trigger,
            openAsType = openAsType
        )
    }
}
