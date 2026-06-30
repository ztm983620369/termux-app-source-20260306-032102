package com.termux.ecjbridge

import java.io.File

object EcjProjectDetector {
    fun isProjectRoot(projectRoot: File): Boolean {
        if (!projectRoot.exists() || !projectRoot.isDirectory) return false
        val nearestRoot = findNearestProjectRootFromDirectory(projectRoot) ?: return false
        return normalizedAbsolutePath(nearestRoot) == normalizedAbsolutePath(projectRoot)
    }

    fun findNearestProjectRoot(path: String?): File? {
        val trimmedPath = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return findNearestProjectRoot(File(trimmedPath))
    }

    fun findNearestProjectRoot(startPath: File?): File? {
        var cursor = startPath ?: return null
        if (cursor.isFile) {
            cursor = cursor.parentFile ?: return null
        }

        return findNearestProjectRootFromDirectory(cursor)
    }

    private fun findNearestProjectRootFromDirectory(startDirectory: File): File? {
        var cursor: File? = startDirectory
        var sourceLayoutCandidate: File? = null
        var directLayoutCandidate: File? = null

        while (true) {
            if (cursor == null) {
                return sourceLayoutCandidate ?: directLayoutCandidate
            }
            if (hasProjectConfig(cursor)) {
                return cursor
            }
            if (sourceLayoutCandidate == null && hasSourceLayoutEntry(cursor)) {
                sourceLayoutCandidate = cursor
            }
            if (directLayoutCandidate == null && hasDirectLayoutEntry(cursor)) {
                directLayoutCandidate = cursor
            }
            cursor = cursor.parentFile
        }
    }

    private fun hasProjectConfig(projectRoot: File): Boolean {
        return File(projectRoot, EcjBridgeContract.PROJECT_CONFIG).isFile
    }

    private fun hasSourceLayoutEntry(projectRoot: File): Boolean {
        return File(projectRoot, "src/com/dynamic/RealAppScript.java").isFile
    }

    private fun hasDirectLayoutEntry(projectRoot: File): Boolean {
        return File(projectRoot, "com/dynamic/RealAppScript.java").isFile
    }

    private fun normalizedAbsolutePath(file: File): String {
        val path = runCatching { file.canonicalFile.absolutePath }
            .getOrElse { file.absoluteFile.absolutePath }
        return if (path.length > 1) path.trimEnd(File.separatorChar) else path
    }
}
