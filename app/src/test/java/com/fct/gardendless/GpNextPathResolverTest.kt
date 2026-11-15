package com.fct.gardendless

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GpNextPathResolverTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resolvesPathsRelativeToAppDataRoot() {
        val filesDir = temporaryFolder.root
        val resolver = GpNextPathResolver(filesDir, File(filesDir, "gp-next"))

        val resolved = resolver.resolve("gp-next/packs/example/pack.json")

        assertEquals(
            File(filesDir, "gp-next/packs/example/pack.json").canonicalFile,
            resolved
        )
    }

    @Test
    fun resolvesAbsolutePathsReturnedByTauriPathPlugin() {
        val filesDir = temporaryFolder.root
        val resolver = GpNextPathResolver(filesDir, File(filesDir, "gp-next"))
        val absolutePath = File(filesDir, "gp-next/packs/example/pack.json").absolutePath

        val resolved = resolver.resolve(absolutePath)

        assertEquals(File(absolutePath).canonicalFile, resolved)
    }

    @Test
    fun resolvesVirtualGpNextPaths() {
        val filesDir = temporaryFolder.root
        val resolver = GpNextPathResolver(filesDir, File(filesDir, "gp-next"))

        val resolved = resolver.resolve("/gp-next/packs/example/pack.json")

        assertEquals(
            File(filesDir, "gp-next/packs/example/pack.json").canonicalFile,
            resolved
        )
    }

    @Test(expected = IOException::class)
    fun rejectsPathsOutsideTheGpNextDirectory() {
        val filesDir = temporaryFolder.root
        val resolver = GpNextPathResolver(filesDir, File(filesDir, "gp-next"))

        resolver.resolve("gp-next/../../outside.txt")
    }
}
