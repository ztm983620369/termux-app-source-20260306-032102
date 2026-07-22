package org.fossify.filemanager.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivePathPolicyTest {
    private val trustedPrefixes = listOf(
        "/data/data/com.termux/files/usr",
        "/data/user/0/com.termux/files/usr"
    )

    @Test
    fun acceptsInternalAndTermuxPrefixLinks() {
        val report = ArchivePathPolicy.validate(
            listOf(
                ArchiveEntryMetadata("portable/", ArchiveEntryKind.DIRECTORY),
                ArchiveEntryMetadata("portable/legal/", ArchiveEntryKind.DIRECTORY),
                ArchiveEntryMetadata("portable/legal/java.base/", ArchiveEntryKind.DIRECTORY),
                ArchiveEntryMetadata(
                    "portable/legal/java.sql/LICENSE",
                    ArchiveEntryKind.SYMBOLIC_LINK,
                    linkTarget = "../java.base/LICENSE"
                ),
                ArchiveEntryMetadata(
                    "portable/project/android-sdk/platform-tools/adb",
                    ArchiveEntryKind.SYMBOLIC_LINK,
                    linkTarget = "/data/data/com.termux/files/usr/bin/adb"
                )
            ),
            trustedPrefixes
        )

        assertEquals(2, report.symbolicLinkCount)
        assertEquals(1, report.trustedExternalLinks.size)
        assertTrue(report.trustedExternalLinks.single().endsWith("/usr/bin/adb"))
    }

    @Test(expected = ArchivePolicyException::class)
    fun rejectsLinksIntoTermuxHome() {
        ArchivePathPolicy.validate(
            listOf(
                ArchiveEntryMetadata(
                    "portable/leak",
                    ArchiveEntryKind.SYMBOLIC_LINK,
                    linkTarget = "/data/data/com.termux/files/home/.ssh/id_rsa"
                )
            ),
            trustedPrefixes
        )
    }

    @Test(expected = ArchivePolicyException::class)
    fun rejectsEntryWrittenThroughEarlierSymlink() {
        ArchivePathPolicy.validate(
            listOf(
                ArchiveEntryMetadata("portable/link", ArchiveEntryKind.SYMBOLIC_LINK, linkTarget = "target"),
                ArchiveEntryMetadata("portable/link/payload", ArchiveEntryKind.FILE, size = 4L)
            ),
            trustedPrefixes
        )
    }

    @Test(expected = ArchivePolicyException::class)
    fun rejectsPathTraversal() {
        ArchivePathPolicy.normalizeEntryPath("portable/../../outside")
    }
}
