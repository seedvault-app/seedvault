/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.storage

import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.plugins.webdav.WebDavTestConfig
import com.stevesoltys.seedvault.transport.backup.BackupTest
import kotlinx.coroutines.runBlocking
import org.calyxos.backup.storage.api.StoredSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

internal class WebDavStoragePluginTest : BackupTest() {

    private val androidId = "abcdef0123456789"
    private val plugin = WebDavStoragePlugin(androidId, WebDavTestConfig.getConfig())

    private val snapshot = StoredSnapshot("$androidId.sv", System.currentTimeMillis())

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
            val androidId2 = "0123456789abcdef"
            val otherPlugin = WebDavStoragePlugin(androidId2, WebDavTestConfig.getConfig())
            val otherSnapshot = StoredSnapshot("$androidId2.sv", System.currentTimeMillis())
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

}

private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
