package org.fossify.filemanager.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveLayoutPolicyTest {
    @Test
    fun collapsesOnlyOneRealDirectoryWithTheRequestedName() {
        val selected = ArchiveLayoutPolicy.redundantSingleRootName(
            archiveCount = 1,
            requestedRootName = "android-minimal-basic-portable",
            children = listOf(
                ExtractedRootEntry(
                    name = "android-minimal-basic-portable",
                    directory = true,
                    symbolicLink = false
                )
            )
        )

        assertEquals("android-minimal-basic-portable", selected)
    }

    @Test
    fun keepsRootWhenArchiveHasAdditionalEntries() {
        val selected = ArchiveLayoutPolicy.redundantSingleRootName(
            archiveCount = 1,
            requestedRootName = "bundle",
            children = listOf(
                ExtractedRootEntry("bundle", directory = true, symbolicLink = false),
                ExtractedRootEntry("README.txt", directory = false, symbolicLink = false)
            )
        )

        assertNull(selected)
    }

    @Test
    fun neverCollapsesSymbolicLinkOrMultiArchiveRoots() {
        assertNull(
            ArchiveLayoutPolicy.redundantSingleRootName(
                archiveCount = 1,
                requestedRootName = "bundle",
                children = listOf(ExtractedRootEntry("bundle", directory = true, symbolicLink = true))
            )
        )
        assertNull(
            ArchiveLayoutPolicy.redundantSingleRootName(
                archiveCount = 2,
                requestedRootName = "bundle",
                children = listOf(ExtractedRootEntry("bundle", directory = true, symbolicLink = false))
            )
        )
    }
}
