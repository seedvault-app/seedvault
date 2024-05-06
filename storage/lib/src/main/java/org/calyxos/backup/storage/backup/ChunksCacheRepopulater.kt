/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.backup

import android.util.Log
import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.db.CachedChunk
import org.calyxos.backup.storage.db.Db
import org.calyxos.backup.storage.measure
import org.calyxos.backup.storage.plugin.SnapshotRetriever
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.time.DurationUnit.MILLISECONDS
import kotlin.time.ExperimentalTime
import kotlin.time.toDuration

private const val TAG = "ChunksCacheRepopulater"

internal class ChunksCacheRepopulater(
    private val db: Db,
    private val storagePlugin: () -> StoragePlugin,
    private val snapshotRetriever: SnapshotRetriever,
) {

    suspend fun repopulate(streamKey: ByteArray, availableChunkIds: HashSet<String>) {
        Log.i(TAG, "Starting to repopulate chunks cache")
        try {
            repopulateInternal(streamKey, availableChunkIds)
        } catch (e: Exception) {
            // TODO what can we do now? Just try again next time?
            Log.e(TAG, "Error while repopulating chunks cache", e)
        }
    }

    @Throws(IOException::class)
    @OptIn(ExperimentalTime::class)
    private suspend fun repopulateInternal(
        streamKey: ByteArray,
        availableChunkIds: HashSet<String>,
    ) {
        val start = System.currentTimeMillis()
        val snapshots = storagePlugin().getCurrentBackupSnapshots().mapNotNull { storedSnapshot ->
            try {
                snapshotRetriever.getSnapshot(streamKey, storedSnapshot)
            } catch (e: GeneralSecurityException) {
                Log.w(TAG, "Error fetching snapshot $storedSnapshot", e)
                null
            }
        }
        val snapshotDuration = (System.currentTimeMillis() - start).toDuration(MILLISECONDS)
        Log.i(TAG, "Retrieving and parsing all snapshots took $snapshotDuration")

        val cachedChunks = getCachedChunks(snapshots, availableChunkIds)
        val repopulateDuration = measure {
            db.getChunksCache().clearAndRepopulate(db, cachedChunks)
        }
        Log.i(TAG, "Repopulating chunks cache took $repopulateDuration")

        // delete chunks that are not references by any snapshot anymore
        val chunksToDelete = availableChunkIds.subtract(cachedChunks.map { it.id })
        val deletionDuration = measure {
            storagePlugin().deleteChunks(chunksToDelete.toList())
        }
        Log.i(TAG, "Deleting ${chunksToDelete.size} chunks took $deletionDuration")
    }

    private fun getCachedChunks(
        snapshots: List<BackupSnapshot>,
        availableChunks: HashSet<String>,
    ): Collection<CachedChunk> {
        val chunkMap = HashMap<String, CachedChunk>()
        snapshots.forEach { snapshot ->
            val chunksInSnapshot = HashSet<String>()
            snapshot.mediaFilesList.forEach { file ->
                file.chunkIdsList.forEach { chunkId -> chunksInSnapshot.add(chunkId) }
            }
            snapshot.documentFilesList.forEach { file ->
                file.chunkIdsList.forEach { chunkId -> chunksInSnapshot.add(chunkId) }
            }
            addCachedChunksToMap(snapshot.timeStart, availableChunks, chunkMap, chunksInSnapshot)
        }
        return chunkMap.values
    }

    private fun addCachedChunksToMap(
        snapshotTimeStamp: Long,
        availableChunks: HashSet<String>,
        chunkMap: HashMap<String, CachedChunk>,
        chunksInSnapshot: HashSet<String>,
    ) = chunksInSnapshot.forEach { chunkId ->
        if (!availableChunks.contains(chunkId)) {
            Log.w(TAG, "ChunkId $chunkId referenced in $snapshotTimeStamp, but not in storage.")
            return@forEach
        }
        val cachedChunk = chunkMap.getOrElse(chunkId) {
            // TODO get actual chunk size (isn't used for anything critical, yet)
            val size = 0L
            CachedChunk(chunkId, 0, size)
        }
        chunkMap[chunkId] = cachedChunk.copy(refCount = cachedChunk.refCount + 1)
    }

}
