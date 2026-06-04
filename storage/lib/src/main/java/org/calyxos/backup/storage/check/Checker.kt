/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.check

import android.util.Log
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.calyxos.backup.storage.SnapshotRetriever
import org.calyxos.backup.storage.api.CheckObserver
import org.calyxos.backup.storage.api.CheckResult
import org.calyxos.backup.storage.api.StoredSnapshot
import org.calyxos.backup.storage.backup.Backup.Companion.VERSION
import org.calyxos.backup.storage.backup.BackupSnapshot
import org.calyxos.backup.storage.backup.ChunksCacheRepopulater
import org.calyxos.backup.storage.crypto.ChunkCrypto
import org.calyxos.backup.storage.crypto.StreamCrypto
import org.calyxos.backup.storage.db.Db
import org.calyxos.backup.storage.restore.readVersion
import org.calyxos.seedvault.core.backends.FileBackupFileType
import org.calyxos.seedvault.core.backends.IBackendManager
import org.calyxos.seedvault.core.backends.TopLevelFolder
import org.calyxos.seedvault.core.crypto.KeyManager
import org.calyxos.seedvault.core.toHexString
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val TAG = Checker::class.simpleName

internal class Checker(
    private val db: Db,
    private val backendManager: IBackendManager,
    private val snapshotRetriever: SnapshotRetriever,
    private val keyManager: KeyManager,
    private val cacheRepopulater: ChunksCacheRepopulater,
    private val androidId: String,
    private val streamCrypto: StreamCrypto = StreamCrypto,
    private val chunkCrypto: ChunkCrypto = ChunkCrypto,
) {

    private val backend get() = backendManager.backend
    private val concurrencyLimit: Int
        get() {
            val maxConcurrent = if (backendManager.requiresNetwork) 3 else 42
            return min(Runtime.getRuntime().availableProcessors(), maxConcurrent)
        }

    private val streamKey
        get() = try {
            streamCrypto.deriveStreamKey(keyManager.getMainKey())
        } catch (e: GeneralSecurityException) {
            throw AssertionError(e)
        }
    private val chunkIdKey by lazy {
        ChunkCrypto.deriveChunkIdKey(keyManager.getMainKey())
    }
    private val mac
        get() = try {
            // each request gets a fresh MAC to avoid concurrent usage of same MAC
            chunkCrypto.getMac(chunkIdKey)
        } catch (e: GeneralSecurityException) {
            throw AssertionError(e)
        }

    fun getBackupSize(): Long {
        return db.getChunksCache().getSizeOfCachedChunks()
    }

    @Throws(
        IOException::class,
        GeneralSecurityException::class,
        InvalidProtocolBufferException::class,
    )
    suspend fun check(percent: Int, checkObserver: CheckObserver?): CheckResult {
        check(percent in 0..100) { "Invalid percentage: $percent" }

        // get all snapshots and blobs on storage
        val snapshotInfo = try {
            getSnapshotsAndAvailableChunks()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting snapshots and blobs: ", e)
            return CheckResult.GeneralError(e)
        }
        // get all referenced chunkIds
        val referencedChunkIds = mutableSetOf<String>()
        snapshotInfo.snapshots.forEach { snapshot ->
            snapshot.mediaFilesList.forEach { referencedChunkIds.addAll(it.chunkIdsList) }
            snapshot.documentFilesList.forEach { referencedChunkIds.addAll(it.chunkIdsList) }
        }
        // calculate chunks that are missing
        val missingChunkIds = referencedChunkIds - snapshotInfo.availableChunkIds
        Log.i(
            TAG, "Found ${referencedChunkIds.size} referenced chunks, " +
                "${missingChunkIds.size} missing."
        )
        // get sample of referenced blobs/chunks
        val (blobs, sampleSize) =
            checkBlobSample(referencedChunkIds, snapshotInfo.suspiciousChunkIds, percent)

        // check chunks concurrently
        val semaphore = Semaphore(concurrencyLimit)
        val size = AtomicLong()
        val badChunks = ConcurrentSkipListSet<String>()
        val lastNotification = AtomicLong()
        val startTime = System.currentTimeMillis()
        coroutineScope {
            blobs.forEach { chunkId ->
                // launch a new co-routine for each blob to check
                launch {
                    // suspend here until we get a permit from the semaphore (there's free workers)
                    val chunkSize = semaphore.withPermit {
                        try {
                            val (readId, chunkSize) = checkChunk(chunkId)
                            if (readId != chunkId) {
                                Log.w(TAG, "Wrong chunkId $readId for $chunkId")
                                // we could read the chunk,
                                // but its HMAC is wrong, so mark it as corrupted
                                db.getChunksCache().markCorrupted(chunkId)
                                badChunks.add(chunkId)
                            } else {
                                Log.i(TAG, "Checked chunkId $chunkId")
                            }
                            chunkSize.toLong()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error checking chunk $chunkId: ", e)
                            // TODO markCorrupted(chunkId) only for permanent exceptions
                            //  to prevent unnecessary re-upload
                            db.getChunksCache().markCorrupted(chunkId)
                            badChunks.add(chunkId)
                            db.getChunksCache().getEvenIfCorrupted(chunkId)?.size ?: 0L
                        }
                    }
                    // keep track of how much we checked and for how long
                    val newSize = size.addAndGet(chunkSize)
                    val passedTime = System.currentTimeMillis() - startTime
                    // only log/show notification after some time has passed (throttling)
                    if (passedTime > lastNotification.get() + 500) {
                        lastNotification.set(passedTime)
                        val bandwidth = (newSize / (passedTime.toDouble() / 1000)).roundToLong()
                        val thousandth = ((newSize.toDouble() / sampleSize) * 1000).roundToInt()
                        Log.d(TAG, "$thousandth‰ - $bandwidth KB/sec - $newSize bytes")
                        checkObserver?.onCheckUpdate(bandwidth, thousandth)
                    }
                }
            }
        }
        val s = size.get()
        if (sampleSize != s) Log.e(TAG, "Checked ${size.get()} bytes, but expected $sampleSize")
        val passedTime = max(System.currentTimeMillis() - startTime, 1000) // no div by zero
        val bandwidth = size.get() / (passedTime.toDouble() / 1000).roundToLong()
        val storedSnapshotSize = snapshotInfo.storedSnapshotSize
        return if (missingChunkIds.isEmpty() &&
            badChunks.isEmpty() &&
            snapshotInfo.snapshots.size == storedSnapshotSize &&
            storedSnapshotSize > 0
        ) {
            checkObserver?.onCheckSuccess(s, bandwidth)
            CheckResult.Success(snapshotInfo.snapshots, percent, s)
        } else {
            checkObserver?.onCheckFoundErrors(s, bandwidth)
            CheckResult.Error(
                existingSnapshots = storedSnapshotSize,
                snapshots = snapshotInfo.snapshots,
                missingChunkIds = missingChunkIds,
                malformedChunkIds = badChunks,
            )
        }
    }

    private class SnapshotInfo(
        val storedSnapshotSize: Int,
        val snapshots: List<BackupSnapshot>,
        val availableChunkIds: Set<String>,
        /**
         * Chunk IDs that we should check with priority,
         * because we noticed something off about them, e.g. their file size isn't as expected.
         */
        val suspiciousChunkIds: Set<String>,
    )

    private suspend fun getSnapshotsAndAvailableChunks(): SnapshotInfo {
        val topLevelFolder = TopLevelFolder.fromAndroidId(androidId)
        val storedSnapshots = mutableListOf<StoredSnapshot>()
        val availableChunkIds = mutableMapOf<String, Long>()
        backend.list(
            topLevelFolder,
            FileBackupFileType.Snapshot::class,
            FileBackupFileType.Blob::class,
        ) { fileInfo ->
            when (fileInfo.fileHandle) {
                is FileBackupFileType.Snapshot -> {
                    val handle = fileInfo.fileHandle as FileBackupFileType.Snapshot
                    val storedSnapshot = StoredSnapshot(handle.topLevelFolder.name, handle.time)
                    storedSnapshots.add(storedSnapshot)
                }
                is FileBackupFileType.Blob ->
                    availableChunkIds[fileInfo.fileHandle.name] = fileInfo.size
                else -> error("Unexpected FileHandle: $fileInfo")
            }
        }
        // ensure our local ChunksCache is up to date
        val chunksCache = db.getChunksCache()
        val suspiciousChunkIds = mutableSetOf<String>()
        if (chunksCache.areAllAvailableChunksCached(availableChunkIds.keys)) {
            // cache is OK, so we can verify that file sizes are still as expected
            availableChunkIds.forEach { (chunkId, size) ->
                val chunk = chunksCache.getEvenIfCorrupted(chunkId)
                if (chunk?.corrupted == true) {
                    Log.i(TAG, "Chunk ${chunk.id} known to be corrupted.")
                    return@forEach
                }
                if (size != chunk?.size) {
                    Log.i(TAG, "Expected size ${chunk?.size}, but chunk had $size: $chunkId")
                    suspiciousChunkIds.add(chunkId)
                }
            }
        } else {
            Log.i(TAG, "Not all available chunks cached, rebuild local cache...")
            cacheRepopulater.repopulate(streamKey, availableChunkIds)
        }
        // parse snapshots
        val snapshots = storedSnapshots.mapNotNull {
            try {
                snapshotRetriever.getSnapshot(streamKey, it)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting snapshot for $it: ", e)
                null
            }
        }
        Log.i(TAG, "Found ${storedSnapshots.size} snapshots, ${snapshots.size} readable.")
        return SnapshotInfo(
            storedSnapshotSize = storedSnapshots.size,
            snapshots = snapshots,
            availableChunkIds = availableChunkIds.keys,
            suspiciousChunkIds = suspiciousChunkIds,
        )
    }

    private fun checkBlobSample(
        referencedChunkIds: Set<String>,
        suspiciousChunkIds: Set<String>,
        percent: Int,
    ): Pair<List<String>, Long> {
        val size = getBackupSize()
        val targetSize = (size * (percent.toDouble() / 100)).roundToLong()
        val blobSample = mutableListOf<String>()
        val priorityChunksIds = referencedChunkIds.intersect(suspiciousChunkIds).shuffled()
        val iterator = (priorityChunksIds + referencedChunkIds.shuffled()).iterator()
        var currentSize = 0L
        while (currentSize < targetSize && iterator.hasNext()) {
            val chunkId = iterator.next()
            blobSample.add(chunkId)
            // we ensure cache consistency above, so chunks not in cache don't exist anymore
            currentSize += db.getChunksCache().getEvenIfCorrupted(chunkId)?.size ?: 0L
        }
        return Pair(blobSample, currentSize)
    }

    private suspend fun checkChunk(chunkId: String): Pair<String, Int> {
        val handle = FileBackupFileType.Blob(androidId, chunkId)
        val cachedChunk = db.getChunksCache().getEvenIfCorrupted(chunkId)
        // if chunk is not in DB, it isn't available on backend, so missing version doesn't matter
        val version = cachedChunk?.version ?: VERSION
        return backend.load(handle).use { inputStream ->
            inputStream.readVersion(version.toInt())
            val ad = streamCrypto.getAssociatedDataForChunk(chunkId, version)
            streamCrypto.newDecryptingStream(streamKey, inputStream, ad).use { decryptedStream ->
                val bytes = decryptedStream.readAllBytes()
                Pair(mac.doFinal(bytes).toHexString(), bytes.size)
            }
        }
    }
}
