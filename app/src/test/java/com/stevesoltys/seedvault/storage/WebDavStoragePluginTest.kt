/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.storage

import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.plugins.webdav.WebDavTestConfig
import com.stevesoltys.seedvault.transport.backup.BackupTest
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.calyxos.backup.storage.api.StoredSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

internal class WebDavStoragePluginTest : BackupTest() {

    private val keyManager: KeyManager = mockk()
    private val plugin = WebDavStoragePlugin(keyManager, "foo", WebDavTestConfig.getConfig())

    private val snapshot = StoredSnapshot("foo.sv", System.currentTimeMillis())

    @Test
    fun `test chunks`() = runBlocking {
        val chunkId1 = getRandomByteArray(32).toHexString()
        val chunkBytes1 = getRandomByteArray()

        // init to create root folder
        plugin.init()

        // first we don't have any chunks
        assertEquals(emptyList<String>(), plugin.getAvailableChunkIds())

        // we write out chunk1
        plugin.getChunkOutputStream(chunkId1).use {
            it.write(chunkBytes1)
        }

        try {
            // now we have the ID of chunk1
            assertEquals(listOf(chunkId1), plugin.getAvailableChunkIds())

            // reading chunk1 matches what we wrote
            assertArrayEquals(
                chunkBytes1,
                plugin.getChunkInputStream(snapshot, chunkId1).readAllBytes(),
            )
        } finally {
            // delete chunk again
            plugin.deleteChunks(listOf(chunkId1))
        }
    }

    @Test
    fun `test snapshots`() = runBlocking {
        val snapshotBytes = getRandomByteArray()

        // init to create root folder
        plugin.init()

        // first we don't have any snapshots
        assertEquals(emptyList<StoredSnapshot>(), plugin.getCurrentBackupSnapshots())
        assertEquals(emptyList<StoredSnapshot>(), plugin.getBackupSnapshotsForRestore())

        // now write one snapshot
        plugin.getBackupSnapshotOutputStream(snapshot.timestamp).use {
            it.write(snapshotBytes)
        }

        try {
            // now we have that one snapshot
            assertEquals(listOf(snapshot), plugin.getCurrentBackupSnapshots())
            assertEquals(listOf(snapshot), plugin.getBackupSnapshotsForRestore())

            // read back written snapshot
            assertArrayEquals(
                snapshotBytes,
                plugin.getBackupSnapshotInputStream(snapshot).readAllBytes(),
            )

            // other device writes another snapshot
            val otherPlugin = WebDavStoragePlugin(keyManager, "bar", WebDavTestConfig.getConfig())
            val otherSnapshot = StoredSnapshot("bar.sv", System.currentTimeMillis())
            val otherSnapshotBytes = getRandomByteArray()
            assertEquals(emptyList<String>(), otherPlugin.getAvailableChunkIds())
            otherPlugin.getBackupSnapshotOutputStream(otherSnapshot.timestamp).use {
                it.write(otherSnapshotBytes)
            }
            try {
                // now that initial one snapshot is still the only current, but restore has both
                assertEquals(listOf(snapshot), plugin.getCurrentBackupSnapshots())
                assertEquals(
                    setOf(snapshot, otherSnapshot),
                    plugin.getBackupSnapshotsForRestore().toSet(), // set to avoid sorting issues
                )
            } finally {
                plugin.deleteBackupSnapshot(otherSnapshot)
            }
        } finally {
            plugin.deleteBackupSnapshot(snapshot)
        }
    }

    @Test
    fun `test missing root dir`() = runBlocking {
        val plugin = WebDavStoragePlugin(
            keyManager = keyManager,
            androidId = "foo",
            webDavConfig = WebDavTestConfig.getConfig(),
            root = getRandomString(),
        )

        assertThrows<IOException> {
            plugin.getCurrentBackupSnapshots()
        }
        assertThrows<IOException> {
            plugin.getBackupSnapshotsForRestore()
        }
        assertThrows<IOException> {
            plugin.getAvailableChunkIds()
        }
        assertThrows<IOException> {
            plugin.deleteChunks(listOf("foo"))
        }
        assertThrows<IOException> {
            plugin.deleteBackupSnapshot(snapshot)
        }
        assertThrows<IOException> {
            plugin.getBackupSnapshotOutputStream(snapshot.timestamp).close()
        }
        assertThrows<IOException> {
            plugin.getBackupSnapshotInputStream(snapshot).use { it.readAllBytes() }
        }
        assertThrows<IOException> {
            plugin.getChunkOutputStream("foo").close()
        }
        assertThrows<IOException> {
            plugin.getChunkInputStream(snapshot, "foo").use { it.readAllBytes() }
        }
        Unit
    }

}

private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
