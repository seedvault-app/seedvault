/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.prune

import android.util.Log
import org.calyxos.backup.storage.api.BackupObserver
import org.calyxos.backup.storage.api.StoredSnapshot
import org.calyxos.backup.storage.crypto.StreamCrypto
import org.calyxos.backup.storage.db.Db
import org.calyxos.backup.storage.measure
import org.calyxos.backup.storage.SnapshotRetriever
import org.calyxos.backup.storage.getCurrentBackupSnapshots
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.FileBackupFileType
import org.calyxos.seedvault.core.crypto.KeyManager
import java.io.IOException
import java.security.GeneralSecurityException

private val TAG = Pruner::class.java.simpleName

internal class Pruner(
    private val db: Db,
    private val retentionManager: RetentionManager,
    private val storagePluginGetter: () -> Backend,
    private val androidId: String,
    keyManager: KeyManager,
    private val snapshotRetriever: SnapshotRetriever,
    streamCrypto: StreamCrypto = StreamCrypto,
) {

    private val backend get() = storagePluginGetter()
    private val chunksCache = db.getChunksCache()
    private val streamKey = try {
        streamCrypto.deriveStreamKey(keyManager.getMainKey())
    } catch (e: GeneralSecurityException) {
        throw AssertionError(e)
    }

    @Throws(IOException::class)
    suspend fun prune(backupObserver: BackupObserver?) {
        val duration = measure {
            val storedSnapshots = backend.getCurrentBackupSnapshots(androidId)
            val toDelete = retentionManager.getSnapshotsToDelete(storedSnapshots)
            backupObserver?.onPruneStart(toDelete.map { it.timestamp })
            for (snapshot in toDelete) {
                try {
                    pruneSnapshot(snapshot, backupObserver)
                } catch (e: Exception) {
                    Log.e(TAG, "Error pruning $snapshot", e)
                    backupObserver?.onPruneError(snapshot.timestamp, e)
                }
            }
        }
        Log.i(TAG, "Pruning took $duration")
        backupObserver?.onPruneComplete(duration.inWholeMilliseconds)
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    private suspend fun pruneSnapshot(
        storedSnapshot: StoredSnapshot,
        backupObserver: BackupObserver?,
    ) {
        val snapshot = snapshotRetriever.getSnapshot(streamKey, storedSnapshot)
        val chunks = HashSet<String>()
        snapshot.mediaFilesList.forEach { chunks.addAll(it.chunkIdsList) }
        snapshot.documentFilesList.forEach { chunks.addAll(it.chunkIdsList) }
        backend.remove(storedSnapshot.snapshotHandle)
        db.applyInParts(chunks) {
            chunksCache.decrementRefCount(it)
        }
        var size = 0L
        // TODO add integration test for a failed backup that later resumes with unreferenced chunks
        //  and here only deletes those that are still unreferenced afterwards
        val cachedChunksToDelete = chunksCache.getUnreferencedChunks()
        val chunkIdsToDelete = cachedChunksToDelete.map {
            if (it.refCount < 0) Log.w(TAG, "${it.id} has ref count ${it.refCount}")
            size += it.size
            it.id
        }
        backupObserver?.onPruneSnapshot(storedSnapshot.timestamp, chunkIdsToDelete.size, size)
        chunkIdsToDelete.forEach { chunkId ->
            backend.remove(FileBackupFileType.Blob(androidId, chunkId))
        }
        chunksCache.deleteChunks(cachedChunksToDelete)
    }

}
