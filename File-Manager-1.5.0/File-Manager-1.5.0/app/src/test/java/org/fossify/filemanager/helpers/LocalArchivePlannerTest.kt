package org.fossify.filemanager.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalArchivePlannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun tarGzipUsesTermuxTarForTestAndExtraction() {
        val bin = temporaryFolder.newFolder("bin")
        val tar = bin.resolve("tar").apply {
            writeText("tool")
            setExecutable(true)
        }
        val archive = temporaryFolder.newFile("portable.tar.gz")
        val destination = temporaryFolder.newFolder("destination")

        val plan = LocalArchivePlanner.buildExtractPlan(archive, destination, bin, "-aou")

        assertEquals(LocalArchiveBackend.GNU_TAR, plan.backend)
        assertEquals(tar.absolutePath, plan.nativeTool.absolutePath)
        assertEquals("--list", plan.preflightCommand[1])
        assertTrue(plan.preflightCommand.contains("--gzip"))
        assertEquals("--extract", plan.command[1])
        assertTrue(plan.command.containsAll(listOf("--no-same-owner", "--no-same-permissions")))
    }

    @Test
    fun nonTarArchiveFallsBackToTermuxSevenZip() {
        val bin = temporaryFolder.newFolder("bin")
        val sevenZip = bin.resolve("7zz").apply {
            writeText("tool")
            setExecutable(true)
        }
        val archive = temporaryFolder.newFile("portable.7z")
        val destination = temporaryFolder.newFolder("destination")

        val plan = LocalArchivePlanner.buildExtractPlan(archive, destination, bin, "-aos")

        assertEquals(LocalArchiveBackend.SEVEN_ZIP, plan.backend)
        assertEquals(sevenZip.absolutePath, plan.nativeTool.absolutePath)
        assertEquals("t", plan.preflightCommand[1])
        assertEquals("x", plan.command[1])
        assertTrue(plan.command.contains("-aos"))
    }

    @Test
    fun recognizesAllTermuxTarCompressionModes() {
        assertEquals("", LocalArchivePlanner.tarCompressionOption("bundle.tar"))
        assertEquals("--gzip", LocalArchivePlanner.tarCompressionOption("bundle.TGZ"))
        assertEquals("--bzip2", LocalArchivePlanner.tarCompressionOption("bundle.tbz2"))
        assertEquals("--xz", LocalArchivePlanner.tarCompressionOption("bundle.txz"))
        assertEquals("--zstd", LocalArchivePlanner.tarCompressionOption("bundle.tar.zst"))
        assertEquals(null, LocalArchivePlanner.tarCompressionOption("bundle.zip"))
    }
}
