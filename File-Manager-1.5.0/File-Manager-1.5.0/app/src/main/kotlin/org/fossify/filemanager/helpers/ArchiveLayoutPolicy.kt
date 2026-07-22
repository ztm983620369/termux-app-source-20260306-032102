package org.fossify.filemanager.helpers

internal data class ExtractedRootEntry(
    val name: String,
    val directory: Boolean,
    val symbolicLink: Boolean
)

internal object ArchiveLayoutPolicy {
    /**
     * Avoids output/name/name/... when a single archive already owns the requested root.
     * Any ambiguity keeps the original staging root intact.
     */
    fun redundantSingleRootName(
        archiveCount: Int,
        requestedRootName: String,
        children: List<ExtractedRootEntry>
    ): String? {
        if (archiveCount != 1 || children.size != 1) return null
        val child = children.single()
        return child.name.takeIf {
            child.directory && !child.symbolicLink && child.name == requestedRootName
        }
    }
}
