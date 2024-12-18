/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import androidx.annotation.WorkerThread
import com.google.protobuf.ByteString
import org.calyxos.seedvault.core.MemoryLogger
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.proto.Snapshot.Blob
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.TopLevelFolder
import org.calyxos.seedvault.core.toHexString
import java.security.DigestInputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal class Checker(
    private val crypto: Crypto,
    private val backendManager: BackendManager,
    private val snapshotManager: SnapshotManager,
    private val loader: Loader,
    private val blobCache: BlobCache,
    private val nm: BackupNotificationManager,
) {
    private val log = KotlinLogging.logger { }

    private var handleSize: Int? = null
    private var snapshots: List<Snapshot>? = null
    private val concurrencyLimit: Int
        get() {
            val maxConcurrent = if (backendManager.requiresNetwork) 3 else 42
            return min(Runtime.getRuntime().availableProcessors(), maxConcurrent)
        }
    var checkerResult: CheckerResult? = null
        private set

    @WorkerThread
    suspend fun getBackupSize(): Long? {
        return try {
            getBackupSizeInt()
        } catch (e: Exception) {
            log.error(e) { "Error loading snapshots: " }
            // we swallow this exception, because an error will be shown in the next step
            null
        }
    }

    private suspend fun getBackupSizeInt(): Long {
        // get all snapshots
        val folder = TopLevelFolder(crypto.repoId)
        val handles = mutableListOf<AppBackupFileType.Snapshot>()
        backendManager.backend.list(folder, AppBackupFileType.Snapshot::class) { fileInfo ->
            handles.add(fileInfo.fileHandle as AppBackupFileType.Snapshot)
        }
        val snapshots = snapshotManager.onSnapshotsLoaded(handles)
        this.snapshots = snapshots // remember loaded snapshots
        this.handleSize = handles.size // remember number of snapshot handles we had

        // get total disk space used by snapshots
        val sizeMap = mutableMapOf<ByteString, Int>() // uses blob.id as key
        snapshots.forEach { snapshot ->
            // add sizes to a map first, so we don't double count
            snapshot.blobsMap.forEach { (_, blob) ->
                sizeMap[blob.id] = blob.length
            }
        }
        return sizeMap.values.sumOf { it.toLong() }
    }

    @WorkerThread
    suspend fun check(percent: Int) {
        check(percent in 0..100) { "Percent $percent out of bounds." }

        if (snapshots == null) try {
            getBackupSizeInt() // just get size again to be sure we get snapshots
        } catch (e: Exception) {
            nm.onCheckFinishedWithError(0, 0)
            checkerResult = CheckerResult.GeneralError(e)
            return
        }
        val snapshots = snapshots ?: error("Snapshots still null")
        val handleSize = handleSize ?: error("Handle size still null")
        check(handleSize >= snapshots.size) {
            "Got $handleSize handles, but ${snapshots.size} snapshots."
        }
        val blobSample = getBlobSample(snapshots, percent)
        val sampleSize = blobSample.sumOf { it.blob.length.toLong() }
        log.info { "Blob sample has ${blobSample.size} blobs worth $sampleSize bytes." }

        // check blobs concurrently
        val semaphore = Semaphore(concurrencyLimit)
        val size = AtomicLong()
        val badChunks = ConcurrentSkipListSet<ChunkIdBlobPair>()
        val lastNotification = AtomicLong()
        val startTime = System.currentTimeMillis()
        coroutineScope {
            blobSample.forEach { (chunkId, blob) ->
                // launch a new co-routine for each blob to check
                launch {
                    // suspend here until we get a permit from the semaphore (there's free workers)
                    semaphore.withPermit {
                        try {
                            checkBlob(chunkId, blob)
                        } catch (e: HashMismatchException) {
                            log.error(e) { "Error loading chunk $chunkId: " }
                            badChunks.add(ChunkIdBlobPair(chunkId, blob))
                            blobCache.doNotUseBlob(blob.id)
                        } catch (e: Exception) {
                            log.error(e) { "Error loading chunk $chunkId: " }
                            // TODO we could try differentiating transient backend issues
                            badChunks.add(ChunkIdBlobPair(chunkId, blob))
                        }
                    }
                    // keep track of how much we checked and for how long
                    val newSize = size.addAndGet(blob.length.toLong())
                    val passedTime = System.currentTimeMillis() - startTime
                    // only log/show notification after some time has passed (throttling)
                    if (passedTime > lastNotification.get() + 500) {
                        lastNotification.set(passedTime)
                        val bandwidth = (newSize / (passedTime.toDouble() / 1000)).roundToLong()
                        val thousandth = ((newSize.toDouble() / sampleSize) * 1000).roundToInt()
                        log.debug { "$thousandth‰ - $bandwidth KB/sec - $newSize bytes" }
                        nm.showCheckNotification(bandwidth, thousandth)
                        MemoryLogger.log()
                    }
                }
            }
        }
        if (sampleSize != size.get()) log.error {
            "Checked ${size.get()} bytes, but expected $sampleSize"
        }
        val passedTime = max(System.currentTimeMillis() - startTime, 1000) // no div by zero
        val bandwidth = size.get() / (passedTime.toDouble() / 1000).roundToLong()
        checkerResult = if (badChunks.isEmpty() && handleSize == snapshots.size && handleSize > 0) {
            nm.onCheckComplete(size.get(), bandwidth)
            CheckerResult.Success(snapshots, percent, size.get())
        } else {
            nm.onCheckFinishedWithError(size.get(), bandwidth)
            CheckerResult.Error(handleSize, snapshots, badChunks)
        }
        this.snapshots = null
    }

    fun clear() {
        log.info { "Clearing..." }
        snapshots = null
        checkerResult = null
    }

    private fun getBlobSample(
        snapshots: List<Snapshot>,
        percent: Int,
    ): List<ChunkIdBlobPair> {
        // split up blobs for app data and for APKs (use blob.id as key to prevent double counting)
        val appBlobs = mutableMapOf<ByteString, ChunkIdBlobPair>()
        val apkBlobs = mutableMapOf<ByteString, ChunkIdBlobPair>()
        snapshots.forEach { snapshot ->
            val appChunkIds = snapshot.appsMap.flatMap { it.value.chunkIdsList.hexFromProto() }
            val apkChunkIds = snapshot.appsMap.flatMap {
                it.value.apk.splitsList.flatMap { split -> split.chunkIdsList.hexFromProto() }
            }
            appChunkIds.forEach { chunkId ->
                val blob = snapshot.blobsMap[chunkId] ?: error("No Blob for chunkId")
                appBlobs[blob.id] = ChunkIdBlobPair(chunkId, blob)
            }
            apkChunkIds.forEach { chunkId ->
                val blob = snapshot.blobsMap[chunkId] ?: error("No Blob for chunkId")
                apkBlobs[blob.id] = ChunkIdBlobPair(chunkId, blob)
            }
        }
        // calculate sizes
        val appSize = appBlobs.values.sumOf { it.blob.length.toLong() }
        val apkSize = apkBlobs.values.sumOf { it.blob.length.toLong() }
        // let's assume it is unlikely that app data and APKs have blobs in common
        val totalSize = appSize + apkSize
        log.info { "Got ${appBlobs.size + apkBlobs.size} blobs worth $totalSize bytes to check." }

        // calculate target sizes (how much do we want to check)
        val targetSize = (totalSize * (percent.toDouble() / 100)).roundToLong()
        val appTargetSize = min((targetSize * 0.75).roundToLong(), appSize) // 75% of targetSize
        log.info { "Sampling $targetSize bytes of which $appTargetSize bytes for apps." }

        val blobSample = mutableListOf<ChunkIdBlobPair>()
        var currentSize = 0L
        // check apps first until we reach their target size
        val appIterator = appBlobs.values.shuffled().iterator() // random app blob iterator
        while (currentSize < appTargetSize && appIterator.hasNext()) {
            val pair = appIterator.next()
            blobSample.add(pair)
            currentSize += pair.blob.length
        }
        // now check APKs until we reach total targetSize
        val apkIterator = apkBlobs.values.shuffled().iterator() // random APK blob iterator
        while (currentSize < targetSize && apkIterator.hasNext()) {
            val pair = apkIterator.next()
            blobSample.add(pair)
            currentSize += pair.blob.length
        }
        return blobSample
    }

    private suspend fun checkBlob(chunkId: String, blob: Blob) {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        val storageId = blob.id.hexFromProto()
        val handle = AppBackupFileType.Blob(crypto.repoId, storageId)
        val readChunkId = loader.loadFile(handle, null).use { inputStream ->
            DigestInputStream(inputStream, messageDigest).use { digestStream ->
                digestStream.readAllBytes()
                digestStream.messageDigest.digest().toHexString()
            }
        }
        if (readChunkId != chunkId) throw GeneralSecurityException("ChunkId doesn't match")
    }
}

data class ChunkIdBlobPair(val chunkId: String, val blob: Blob) : Comparable<ChunkIdBlobPair> {
    override fun compareTo(other: ChunkIdBlobPair): Int {
        return chunkId.compareTo(other.chunkId)
    }
}
