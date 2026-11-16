package com.fct.gardendless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameLocalStorageBackupStoreTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesAndReadsSnapshot() {
        val store = GameLocalStorageBackupStore(temporaryFolder.root)
        val snapshot = """{"version":1,"entries":{"save":"data"}}"""

        assertTrue(store.write(snapshot))
        assertEquals(snapshot, store.read())
    }

    @Test
    fun oversizedSnapshotDoesNotReplaceExistingBackup() {
        val store = GameLocalStorageBackupStore(temporaryFolder.root, maxBytes = 32)
        val existingSnapshot = """{"version":1}"""

        assertTrue(store.write(existingSnapshot))
        assertFalse(store.write("x".repeat(33)))

        assertEquals(existingSnapshot, store.read())
    }

    @Test
    fun missingSnapshotReturnsNull() {
        val store = GameLocalStorageBackupStore(temporaryFolder.root)

        assertNull(store.read())
    }
}
