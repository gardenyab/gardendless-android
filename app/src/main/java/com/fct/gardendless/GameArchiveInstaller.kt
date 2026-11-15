package com.fct.gardendless

import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

internal class GameArchiveInstaller(private val filesDir: File) {
    private val gameDir = File(filesDir, GAME_DIR_NAME)

    fun installedSiteDirectory(): File? {
        if (gameDir.isDirectory) {
            val marker = File(gameDir, SITE_MARKER)
            if (marker.isFile) {
                val relativePath = marker.readText(Charsets.UTF_8)
                val siteDir = if (relativePath.isEmpty()) gameDir else File(gameDir, relativePath)
                val gameCanonicalPath = gameDir.canonicalFile.toPath()
                val siteCanonicalPath = siteDir.canonicalFile.toPath()
                if (siteCanonicalPath.startsWith(gameCanonicalPath) && File(siteDir, "index.html").isFile) {
                    return siteDir
                }
            }
        }

        return File(filesDir, LEGACY_SITE_PATH).takeIf { File(it, "index.html").isFile }
    }

    fun install(
        input: InputStream,
        archiveSize: Long = -1,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) {
        val stagingDir = File(filesDir, STAGING_DIR_NAME)
        val backupDir = File(filesDir, BACKUP_DIR_NAME)
        deleteDirectory(stagingDir)
        deleteDirectory(backupDir)
        if (!stagingDir.mkdirs()) throw IOException("Could not create the game staging directory")

        try {
            val indexFiles = mutableListOf<String>()
            var extractedBytes = 0L
            var entryCount = 0
            val progressInput = ProgressInputStream(input, archiveSize, onProgress)
            ZipInputStream(BufferedInputStream(progressInput)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entryCount++
                    if (entryCount > MAX_ENTRIES) throw IOException("ZIP contains too many files")

                    val relativePath = normalizeEntryPath(entry.name)
                    if (relativePath.isNotEmpty()) {
                        val outputFile = File(stagingDir, relativePath)
                        val stagingPath = stagingDir.canonicalFile.toPath()
                        if (!outputFile.canonicalFile.toPath().startsWith(stagingPath)) {
                            throw IOException("ZIP contains an invalid file path")
                        }

                        if (entry.isDirectory) {
                            if (!outputFile.mkdirs() && !outputFile.isDirectory) {
                                throw IOException("Could not create a directory from the ZIP")
                            }
                        } else {
                            outputFile.parentFile?.let {
                                if (!it.mkdirs() && !it.isDirectory) {
                                    throw IOException("Could not create a directory from the ZIP")
                                }
                            }
                            outputFile.outputStream().buffered().use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    extractedBytes += count
                                    if (extractedBytes > MAX_UNCOMPRESSED_BYTES) {
                                        throw IOException("ZIP expands beyond the 4 GiB safety limit")
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                            if (relativePath.substringAfterLast('/') == "index.html") {
                                indexFiles.add(relativePath)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val sitePath = chooseSiteIndex(indexFiles)
                ?: throw IOException("ZIP does not contain a game index.html")
            val siteRelativePath = sitePath.substringBeforeLast('/', "")
            File(stagingDir, SITE_MARKER).writeText(siteRelativePath, Charsets.UTF_8)

            if (gameDir.exists() && !gameDir.renameTo(backupDir)) {
                throw IOException("Could not move the installed game before updating")
            }
            if (!stagingDir.renameTo(gameDir)) {
                if (backupDir.exists() && !backupDir.renameTo(gameDir)) {
                    throw IOException("Could not install the new game or restore the previous one")
                }
                throw IOException("Could not install the new game")
            }
            deleteDirectory(backupDir)
        } catch (e: Exception) {
            try {
                deleteDirectory(stagingDir)
            } catch (cleanupError: Exception) {
                e.addSuppressed(cleanupError)
            }
            if (!gameDir.exists() && backupDir.exists() && !backupDir.renameTo(gameDir)) {
                e.addSuppressed(IOException("Could not restore the previous game after installation failed"))
            }
            throw e
        }
    }

    private fun normalizeEntryPath(entryName: String): String {
        val path = entryName.replace('\\', '/')
        if (path.startsWith('/') || DRIVE_PREFIX.matches(path)) {
            throw IOException("ZIP contains an absolute file path")
        }

        val segments = path.split('/')
        if (segments.any { it == ".." }) throw IOException("ZIP contains an invalid parent path")
        return segments.filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }

    private fun chooseSiteIndex(indexFiles: List<String>): String? =
        indexFiles.minWithOrNull(
            compareBy<String> {
                val parent = it.substringBeforeLast('/', "")
                if (parent.substringAfterLast('/').equals("docs", ignoreCase = true)) 0 else 1
            }.thenBy { it.count { char -> char == '/' } }
        )

    private fun deleteDirectory(directory: File) {
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IOException("Could not clean up the game installation directory")
        }
    }

    private class ProgressInputStream(
        input: InputStream,
        private val totalBytes: Long,
        private val onProgress: (Long, Long) -> Unit
    ) : FilterInputStream(input) {
        private var bytesRead = 0L
        private var lastReportedPercent = -1

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) reportRead(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) reportRead(count)
            return count
        }

        private fun reportRead(count: Int) {
            bytesRead += count
            if (totalBytes > 0) {
                val percent = (bytesRead * 100 / totalBytes).toInt().coerceIn(0, 100)
                if (percent > lastReportedPercent) {
                    lastReportedPercent = percent
                    onProgress(bytesRead.coerceAtMost(totalBytes), totalBytes)
                }
            }
        }
    }

    private companion object {
        const val GAME_DIR_NAME = "gardendless-game"
        const val STAGING_DIR_NAME = "gardendless-game-staging"
        const val BACKUP_DIR_NAME = "gardendless-game-backup"
        const val LEGACY_SITE_PATH = "pvzge_web-master/docs"
        const val SITE_MARKER = ".gardendless-site"
        const val BUFFER_SIZE = 8 * 1024
        const val MAX_ENTRIES = 100_000
        const val MAX_UNCOMPRESSED_BYTES = 4L * 1024 * 1024 * 1024
        val DRIVE_PREFIX = Regex("^[A-Za-z]:")
    }
}
