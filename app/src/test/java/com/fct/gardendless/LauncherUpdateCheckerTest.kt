package com.fct.gardendless

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherUpdateCheckerTest {

    @Test
    fun newerMinorOrPatchVersionIsDetected() {
        assertTrue(LauncherUpdateChecker.isNewerVersion("1.1", "1.0"))
        assertTrue(LauncherUpdateChecker.isNewerVersion("v1.0.1", "1.0"))
        assertTrue(LauncherUpdateChecker.isNewerVersion("v1.10", "1.2"))
        assertTrue(LauncherUpdateChecker.isNewerVersion("2.0", "1.9.9"))
        assertTrue(LauncherUpdateChecker.isNewerVersion("1.0", "0.15.0"))
    }

    @Test
    fun sameOrOlderVersionIsNotNewer() {
        assertFalse(LauncherUpdateChecker.isNewerVersion("1.0", "1.0"))
        assertFalse(LauncherUpdateChecker.isNewerVersion("v1.0.0", "1.0"))
        assertFalse(LauncherUpdateChecker.isNewerVersion("0.15.0", "1.0"))
        assertFalse(LauncherUpdateChecker.isNewerVersion("1.2", "1.10"))
    }
}
