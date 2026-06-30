package com.termux.ecjbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EcjTemplateScaffoldTest {
    @Test
    fun createProjectWritesRunnableEcjLayout() {
        val parent = createTempDir(prefix = "ecj-template-test-")
        try {
            val result = EcjTemplateScaffold.createProject(
                EcjTemplateCreateRequest(
                    parentDirectory = parent,
                    projectName = "Hello ECJ",
                    sourceAppPackage = "com.termux"
                )
            )

            assertEquals("Hello ECJ", result.projectRoot.name)
            assertTrue(File(result.projectRoot, EcjBridgeContract.PROJECT_CONFIG).isFile)
            assertTrue(File(result.projectRoot, EcjBridgeContract.TERMUX_LINK_CONFIG).isFile)
            assertTrue(File(result.projectRoot, "src/com/dynamic/RealAppScript.java").isFile)
            assertTrue(File(result.projectRoot, "assets/text/hello.txt").isFile)
            assertTrue(result.entryFile.readText().contains("class RealAppScript"))
            assertTrue(EcjProjectDetector.isProjectRoot(result.projectRoot))

            val nestedDirectory = File(result.projectRoot, "src/com/dynamic").apply { mkdirs() }
            assertEquals(result.projectRoot, EcjProjectDetector.findNearestProjectRoot(nestedDirectory))
            assertEquals(result.projectRoot, EcjProjectDetector.findNearestProjectRoot(result.entryFile))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun sanitizeProjectNameRemovesUnsafePathCharacters() {
        assertEquals("bad_name", EcjTemplateScaffold.sanitizeProjectName("bad/name"))
        assertEquals("ecj-template", EcjTemplateScaffold.sanitizeProjectName("///"))
    }
}
