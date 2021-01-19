package org.calyxos.backup.storage.prune

import android.util.Log
import org.calyxos.backup.storage.api.BackupObserver
import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.crypto.StreamCrypto
import org.calyxos.backup.storage.db.Db
import org.calyxos.backup.storage.measure
import org.calyxos.backup.storage.plugin.SnapshotRetriever
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.time.ExperimentalTime

private val TAG = Pruner::class.java.simpleName

@Suppress("BlockingMethodInNonBlockingContext")
internal class Pruner(
    private val db: Db,
    private val retentionManager: RetentionManager,
    private val storagePlugin: StoragePlugin,
    private val snapshotRetriever: SnapshotRetriever,
    streamCrypto: StreamCrypto = StreamCrypto,
) {

    private val chunksCache = db.getChunksCache()
    private val streamKey = try {
        streamCrypto.deriveStreamKey(storagePlugin.getMasterKey())
    } catch (e: GeneralSecurityException) {
        throw AssertionError(e)
    }

    @OptIn(ExperimentalTime::class)
    @Throws(IOException::class)
    suspend fun prune(backupObserver: BackupObserver?) {
        val duration = measure {
            val timestamps = storagePlugin.getAvailableBackupSnapshots()
            val toDelete = retentionManager.getSnapshotsToDelete(timestamps)
            backupObserver?.onPruneStart(toDelete)
            for (timestamp in toDelete) {
                try {
                    pruneSnapshot(timestamp, backupObserver)
                } catch (e: Exception) {
                    Log.e(TAG, "Error pruning $timestamp", e)
                    backupObserver?.onPruneError(timestamp, e)
                }
            }
        }
        Log.i(TAG, "Pruning took $duration")
        backupObserver?.onPruneComplete(duration.toLongMilliseconds())
    }

    @Throws(IOException::class, SecurityException::class)
    private suspend fun pruneSnapshot(timestamp: Long, backupObserver: BackupObserver?) {
        val snapshot = snapshotRetriever.getSnapshot(streamKey, timestamp)
        val chunks = HashSet<String>()
        snapshot.mediaFilesList.forEach { chunks.addAll(it.chunkIdsList) }
        snapshot.documentFilesList.forEach { chunks.addAll(it.chunkIdsList) }
        storagePlugin.deleteBackupSnapshot(timestamp)
        db.applyInParts(chunks) {
            chunksCache.decrementRefCount(it)
        }
        var size = 0L
        val cachedChunksToDelete = chunksCache.getUnreferencedChunks()
        val chunkIdsToDelete = cachedChunksToDelete.map {
            if (it.refCount < 0) Log.w(TAG, "${it.id} has ref count ${it.refCount}")
            size += it.size
            it.id
        }
        backupObserver?.onPruneSnapshot(timestamp, chunkIdsToDelete.size, size)
        storagePlugin.deleteChunks(chunkIdsToDelete)
        chunksCache.deleteChunks(cachedChunksToDelete)
    }

}
