package com.fct.gardendless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GameArchiveCatalogTest {

    @Test
    fun buildsValidArchiveEntryAndRejectsInvalidUrls() {
        val valid = GameArchiveCatalog.buildEntry(
            name = " 0.16.24 lite ",
            gpNext = " GP_Next 1.6.1 ",
            url = "https://github.com/gardenyab/gardendless-android/releases/download/launcheru/pvzge-0.16.24-1.6.1.zip"
        )
        assertNotNull(valid)
        assertEquals("0.16.24 lite", valid?.name)
        assertEquals("GP_Next 1.6.1", valid?.gpNext)

        assertNull(GameArchiveCatalog.buildEntry("", "GP_Next 1.6.1", "https://example.com/a.zip"))
        assertNull(GameArchiveCatalog.buildEntry("0.16.24", null, "http://insecure.example/a.zip"))
    }
}
