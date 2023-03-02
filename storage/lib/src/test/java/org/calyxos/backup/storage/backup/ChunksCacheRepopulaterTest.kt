/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.backup

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.api.StoredSnapshot
import org.calyxos.backup.storage.db.CachedChunk
import org.calyxos.backup.storage.db.ChunksCache
import org.calyxos.backup.storage.db.Db
import org.calyxos.backup.storage.getRandomString
import org.calyxos.backup.storage.mockLog
import org.calyxos.backup.storage.plugin.SnapshotRetriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@Suppress("BlockingMethodInNonBlockingContext")
internal class ChunksCacheRepopulaterTest {

    private val db: Db = mockk()
    private val chunksCache: ChunksCache = mockk()
    private val plugin: StoragePlugin = mockk()
    private val snapshotRetriever: SnapshotRetriever = mockk()
    private val streamKey = "This is a backup key for testing".toByteArray()
    private val cacheRepopulater = ChunksCacheRepopulater(db, plugin, snapshotRetriever)

    init {
        mockLog()
        every { db.getChunksCache() } returns chunksCache
    }

    @Test
    fun test(): Unit = runBlocking {
        val chunk1 = getRandomString(6) // in 2 snapshots
        val chunk2 = getRandomString(6) // in 2 snapshots
        val chunk3 = getRandomString(6) // not referenced by any snapshot
        val chunk4 = getRandomString(6) // in 1 snapshot
        val chunk5 = getRandomString(6) // in 1 snapshot, but not available in storage
        val availableChunkIds = hashSetOf(chunk1, chunk2, chunk3, chunk4)
        val snapshot1 = BackupSnapshot.newBuilder()
            .setTimeStart(Random.nextLong())
            .addMediaFiles(BackupMediaFile.newBuilder().addChunkIds(chunk1))
            .addMediaFiles(BackupMediaFile.newBuilder().addChunkIds(chunk2))
            .addDocumentFiles(BackupDocumentFile.newBuilder().addChunkIds(chunk1))
            .build()
        val snapshot2 = BackupSnapshot.newBuilder()
            .setTimeStart(Random.nextLong())
            .addMediaFiles(BackupMediaFile.newBuilder().addChunkIds(chunk1))
            .addMediaFiles(BackupMediaFile.newBuilder().addChunkIds(chunk2))
            .addDocumentFiles(BackupDocumentFile.newBuilder().addChunkIds(chunk4))
            .addDocumentFiles(BackupDocumentFile.newBuilder().addChunkIds(chunk5))
            .build()
        val storedSnapshot1 = StoredSnapshot("foo", snapshot1.timeStart)
        val storedSnapshot2 = StoredSnapshot("bar", snapshot2.timeStart)
        val storedSnapshots = listOf(storedSnapshot1, storedSnapshot2)
        val cachedChunks = listOf(
            CachedChunk(chunk1, 2, 0),
            CachedChunk(chunk2, 2, 0),
            CachedChunk(chunk4, 1, 0),
        ) // chunk3 is not referenced and should get deleted
        val cachedChunksSlot = slot<Collection<CachedChunk>>()

        coEvery { plugin.getCurrentBackupSnapshots() } returns storedSnapshots
        coEvery {
            snapshotRetriever.getSnapshot(streamKey, storedSnapshot1)
        } returns snapshot1
        coEvery {
            snapshotRetriever.getSnapshot(streamKey, storedSnapshot2)
        } returns snapshot2
        every { chunksCache.clearAndRepopulate(db, capture(cachedChunksSlot)) } just Runs
        coEvery { plugin.deleteChunks(listOf(chunk3)) } just Runs

        cacheRepopulater.repopulate(streamKey, availableChunkIds)

        assertTrue(cachedChunksSlot.isCaptured)
        assertEquals(cachedChunks.toSet(), cachedChunksSlot.captured.toSet())

        coVerify { plugin.deleteChunks(listOf(chunk3)) }
    }

}
