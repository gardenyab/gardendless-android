package com.fct.gardendless

import java.io.File
import java.io.IOException

internal class GpNextPathResolver(
    private val filesDir: File,
    private val gpNextDir: File
) {
    fun resolve(rawPath: String): File {
        val appRoot = filesDir.absolutePath.replace('\\', '/').trimEnd('/')
        val normalized = rawPath.trim().replace('\\', '/')
        val relative = when {
            normalized == appRoot -> GP_NEXT_DIR_NAME
            normalized.startsWith("$appRoot/") -> normalized.removePrefix("$appRoot/")
            else -> normalized.trimStart('/')
        }

        val root = gpNextDir.canonicalFile
        val candidate = File(filesDir, relative).canonicalFile
        val rootPrefix = root.path + File.separator
        if (candidate.path != root.path && !candidate.path.startsWith(rootPrefix)) {
            throw IOException("Path is outside the GP-Next data directory")
        }
        return candidate
    }

    private companion object {
        const val GP_NEXT_DIR_NAME = "gp-next"
    }
}
