package org.fossify.filemanager.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardPathFormatterTest {
    @Test
    fun remotePathKeepsOnlyAbsoluteLinuxPath() {
        assertEquals(
            "/root/project/file.txt",
            ClipboardPathFormatter.remoteLinuxPath("sftp://10.0.0.8/root//project/./file.txt")
        )
    }

    @Test
    fun remotePathDropsScpStyleAuthority() {
        assertEquals(
            "/root/project",
            ClipboardPathFormatter.remoteLinuxPath("root@10.0.0.8:/root/project/")
        )
    }

    @Test
    fun remotePathAddsMissingAbsolutePrefix() {
        assertEquals("/root/project", ClipboardPathFormatter.remoteLinuxPath("root/project"))
    }

    @Test
    fun localPathKeepsAndroidAbsolutePath() {
        assertEquals(
            "/data/data/com.termux/files/home/project",
            ClipboardPathFormatter.localPath("/data/data/com.termux/files/home//project/")
        )
    }
}
