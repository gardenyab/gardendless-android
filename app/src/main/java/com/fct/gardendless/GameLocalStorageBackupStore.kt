package com.fct.gardendless

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class GameLocalStorageBackupStore(
    filesDir: File,
    private val maxBytes: Int = MAX_BACKUP_BYTES
) {
    private val directory = File(filesDir, BACKUP_DIRECTORY)
    private val snapshotFile = File(directory, SNAPSHOT_FILE)

    @Synchronized
    @Throws(IOException::class)
    fun write(snapshot: String): Boolean {
        val bytes = snapshot.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) return false
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create the game backup directory")
        }

        val temporaryFile = File(directory, "$SNAPSHOT_FILE.tmp")
        try {
            FileOutputStream(temporaryFile).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporaryFile.toPath(),
                    snapshotFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporaryFile.toPath(),
                    snapshotFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (e: IOException) {
            if (temporaryFile.exists() && !temporaryFile.delete()) {
                e.addSuppressed(IOException("Could not delete the temporary game backup"))
            }
            throw e
        }
        return true
    }

    @Synchronized
    @Throws(IOException::class)
    fun read(): String? {
        if (!snapshotFile.exists()) return null
        if (!snapshotFile.isFile || snapshotFile.length() > maxBytes) {
            throw IOException("The game backup file is invalid or exceeds the size limit")
        }
        return snapshotFile.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private companion object {
        const val BACKUP_DIRECTORY = "game-backup"
        const val SNAPSHOT_FILE = "local-storage.json"
        const val MAX_BACKUP_BYTES = 8 * 1024 * 1024
    }
}
