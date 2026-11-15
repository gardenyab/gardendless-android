package com.fct.gardendless

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameArchiveInstallerTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installsSiteInsideDocsDirectory() {
        val installer = GameArchiveInstaller(temporaryFolder.root)

        installer.install(zipOf("pvzge_web-master/docs/index.html" to "game"))

        val siteDirectory = installer.installedSiteDirectory()
        assertNotNull(siteDirectory)
        assertEquals("game", File(siteDirectory, "index.html").readText())
    }

    @Test
    fun reportsArchiveReadProgress() {
        val installer = GameArchiveInstaller(temporaryFolder.root)
        val archive = zipOf("index.html" to "game").readBytes()
        val progress = mutableListOf<Pair<Long, Long>>()

        installer.install(ByteArrayInputStream(archive), archive.size.toLong()) { read, total ->
            progress.add(read to total)
        }

        assertTrue(progress.isNotEmpty())
        assertEquals(archive.size.toLong(), progress.last().first)
        assertEquals(archive.size.toLong(), progress.last().second)
    }

    @Test
    fun findsPreviouslyBundledGameFiles() {
        val legacySite = File(temporaryFolder.root, "pvzge_web-master/docs").apply { mkdirs() }
        File(legacySite, "index.html").writeText("legacy game")

        val siteDirectory = GameArchiveInstaller(temporaryFolder.root).installedSiteDirectory()

        assertEquals(legacySite, siteDirectory)
    }

    @Test
    fun failedUpdatePreservesPreviouslyInstalledGame() {
        val installer = GameArchiveInstaller(temporaryFolder.root)
        installer.install(zipOf("index.html" to "working game"))

        try {
            installer.install(zipOf("not-a-game.txt" to "invalid"))
            throw AssertionError("Expected archive without index.html to fail")
        } catch (_: IOException) {
            // Expected: invalid updates must not replace the working installation.
        }

        val siteDirectory = installer.installedSiteDirectory()
        assertNotNull(siteDirectory)
        assertEquals("working game", File(siteDirectory, "index.html").readText())
    }

    @Test
    fun rejectsZipSlipPaths() {
        val installer = GameArchiveInstaller(temporaryFolder.root)

        try {
            installer.install(zipOf("../outside.txt" to "not allowed", "index.html" to "game"))
            throw AssertionError("Expected an unsafe path to fail")
        } catch (_: IOException) {
            // Expected: archive entries must stay inside the app's staging directory.
        }

        assertFalse(File(temporaryFolder.root, "outside.txt").exists())
        assertNull(installer.installedSiteDirectory())
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
}
