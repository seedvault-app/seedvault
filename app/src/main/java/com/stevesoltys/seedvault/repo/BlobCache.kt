/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import android.content.Context
import android.content.Context.MODE_APPEND
import android.content.Context.MODE_PRIVATE
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.google.protobuf.ByteString
import org.calyxos.seedvault.core.MemoryLogger
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.proto.Snapshot.Blob
import io.github.oshai.kotlinlogging.KotlinLogging
import org.calyxos.seedvault.core.backends.FileInfo
import org.calyxos.seedvault.core.toByteArrayFromHex
import org.calyxos.seedvault.core.toHexString
import java.io.FileNotFoundException
import java.io.IOException

@VisibleForTesting
internal const val CACHE_FILE_NAME = "blobsCache"

/**
 * The filename of the file where we store which blobs are known to be corrupt
 * and should not be used anymore.
 * Each [BLOB_ID_SIZE] bytes are appended without separator or line breaks.
 */
@VisibleForTesting
internal const val DO_NOT_USE_FILE_NAME = "doNotUseBlobs"

private const val BLOB_ID_SIZE = 32

/**
 * Responsible for caching blobs during a backup run,
 * so we can know that a blob for the given chunk ID already exists
 * and does not need to be uploaded again.
 *
 * It builds up its cache from snapshots available on the backend
 * and from the persistent cache that includes blobs that could not be added to a snapshot,
 * because the backup was aborted.
 */
class BlobCache(
    private val context: Context,
) {

    private val log = KotlinLogging.logger {}
    private val blobMap = mutableMapOf<String, Blob>()

    /**
     * This must be called before saving files to the backend to avoid uploading duplicate blobs.
     */
    @Throws(IOException::class)
    fun populateCache(blobs: List<FileInfo>, snapshots: List<Snapshot>) {
        log.info { "Getting all blobs from backend..." }
        blobMap.clear()
        MemoryLogger.log()
        // create map of blobId to size of blob on backend
        val allowedBlobIds = blobs.associate {
            Pair(it.fileHandle.name, it.size.toInt())
        }.toMutableMap()
        // remove known bad blob IDs from allowedBlobIds
        getDoNotUseBlobIds().forEach { knownBadId ->
            if (allowedBlobIds.remove(knownBadId) != null) {
                log.info { "Removed known bad blob: $knownBadId" }
            }
        }
        // load local blob cache and include only blobs on backend
        loadPersistentBlobCache(allowedBlobIds)
        // build up mapping from chunkId to blob from available snapshots
        snapshots.forEach { snapshot ->
            onSnapshotLoaded(snapshot, allowedBlobIds)
        }
        MemoryLogger.log()
    }

    /**
     * Should only be called after [populateCache] has returned.
     */
    operator fun get(chunkId: String): Blob? = blobMap[chunkId]

    /**
     * Should only be called after [populateCache] has returned.
     *
     * @return true if all [chunkIds] are in cache, or false if one or more is missing.
     */
    fun containsAll(chunkIds: List<String>): Boolean = chunkIds.all { chunkId ->
        blobMap.containsKey(chunkId)
    }

    /**
     * Should get called for all new blobs as soon as they've been saved to the backend.
     *
     * We shouldn't need to worry about [Pruner] removing blobs that get cached here locally,
     * because we do run [Pruner.removeOldSnapshotsAndPruneUnusedBlobs] only after
     * a successful backup which is when we also clear cache in [clearLocalCache].
     */
    fun saveNewBlob(chunkId: String, blob: Blob) {
        val previous = blobMap.put(chunkId, blob)
        if (previous == null) {
            // persist this new blob locally in case backup gets interrupted
            context.openFileOutput(CACHE_FILE_NAME, MODE_APPEND).use { outputStream ->
                outputStream.write(chunkId.toByteArrayFromHex())
                blob.writeDelimitedTo(outputStream)
            }
        }
    }

    /**
     * Clears the cached blob mapping.
     * Should be called after a backup run to free up memory.
     */
    fun clear() {
        log.info { "Clearing cache..." }
        blobMap.clear()
    }

    /**
     * Clears the local cache.
     * Should get called after
     * * changing to a different backup to prevent usage of blobs that don't exist there
     * * uploading a new snapshot to prevent the persistent cache from growing indefinitely
     */
    @WorkerThread
    fun clearLocalCache() {
        log.info { "Clearing local cache..." }
        context.deleteFile(CACHE_FILE_NAME)
    }

    /**
     * Loads persistent cache from disk and adds blobs to [blobMap]
     * if available in [allowedBlobIds] with the right size.
     */
    private fun loadPersistentBlobCache(allowedBlobIds: Map<String, Int>) {
        try {
            context.openFileInput(CACHE_FILE_NAME).use { inputStream ->
                val chunkIdBytes = ByteArray(32)
                while (true) {
                    val bytesRead = inputStream.read(chunkIdBytes)
                    if (bytesRead != 32) break
                    val chunkId = chunkIdBytes.toHexString()
                    // parse blob
                    val blob = Blob.parseDelimitedFrom(inputStream)
                    val blobId = blob.id.hexFromProto()
                    // include blob only if size is equal to size on backend
                    val sizeOnBackend = allowedBlobIds[blobId]
                    if (sizeOnBackend == blob.length) {
                        blobMap[chunkId] = blob
                    } else log.warn {
                        if (sizeOnBackend == null) {
                            "Cached blob $blobId is missing from backend."
                        } else {
                            "Cached blob $blobId had different size on backend: $sizeOnBackend"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is FileNotFoundException) log.info { "No local blob cache found." }
            else {
                // If the local cache is corrupted, that's not the end of the world.
                // We can still continue normally,
                // but may be writing out duplicated blobs we can't re-use.
                // Those will get deleted again when pruning.
                // So swallow the exception.
                log.error(e) { "Error loading blobs cache: " }
            }
        }
    }

    /**
     * Used for populating local [blobMap] cache.
     * Adds mapping from chunkId to [Blob], if it exists on backend, i.e. part of [allowedBlobIds]
     * and its size matches the one on backend, i.e. value of [allowedBlobIds].
     */
    private fun onSnapshotLoaded(snapshot: Snapshot, allowedBlobIds: Map<String, Int>) {
        snapshot.blobsMap.forEach { (chunkId, blob) ->
            // check if referenced blob still exists on backend
            val blobId = blob.id.hexFromProto()
            val sizeOnBackend = allowedBlobIds[blobId]
            if (sizeOnBackend == blob.length) {
                // only add blob to our mapping, if it still exists
                blobMap.putIfAbsent(chunkId, blob)?.let { previous ->
                    // If there's more than one blob for the same chunk ID, it shouldn't matter
                    // which one we keep on using provided both are still ok.
                    // When we are here, the blob exists on storage and has the same size.
                    // There may still be other corruption such as bit flips in one of the blobs.
                    if (previous.id != blob.id) log.warn {
                        "Chunk ID ${chunkId.substring(0..5)} had more than one blob."
                    }
                }
            } else log.warn {
                if (sizeOnBackend == null) {
                    "Blob $blobId in snapshot ${snapshot.token} is missing."
                } else {
                    "Blob $blobId has unexpected size: $sizeOnBackend"
                }
            }
        }
    }

    /**
     * This is expected to get called by the [Checker] when it finds a blob
     * that has the expected file size, but its content hash doesn't match what we expect.
     *
     * It appends the given [blobId] to our [DO_NOT_USE_FILE_NAME] file.
     */
    fun doNotUseBlob(blobId: ByteString) {
        try {
            context.openFileOutput(DO_NOT_USE_FILE_NAME, MODE_APPEND).use { outputStream ->
                val bytes = blobId.toByteArray()
                check(bytes.size == 32) { "Blob ID $blobId has unexpected size of ${bytes.size}" }
                outputStream.write(bytes)
            }
        } catch (e: Exception) {
            log.error(e) { "Error adding blob to do-not-use list, may be corrupted: " }
        }
    }

    @VisibleForTesting
    fun getDoNotUseBlobIds(): Set<String> {
        val blobsIds = mutableSetOf<String>()
        try {
            context.openFileInput(DO_NOT_USE_FILE_NAME).use { inputStream ->
                val bytes = ByteArray(BLOB_ID_SIZE)
                while (inputStream.read(bytes) == 32) {
                    val blobId = bytes.toHexString()
                    blobsIds.add(blobId)
                }
            }
        } catch (e: FileNotFoundException) {
            log.info { "No do-not-use list found" }
        } catch (e: Exception) {
            log.error(e) { "Our internal do-not-use list is corrupted, deleting it..." }
            context.deleteFile(DO_NOT_USE_FILE_NAME)
        }
        return blobsIds
    }

    /**
     * Call this after deleting blobs from the backend,
     * so we can remove those from our do-not-use list.
     */
    fun onBlobsRemoved(blobIds: Set<String>) {
        log.info { "${blobIds.size} blobs were removed." }

        val blobsIdsToKeep = mutableSetOf<String>()

        try {
            context.openFileInput(DO_NOT_USE_FILE_NAME).use { inputStream ->
                val bytes = ByteArray(BLOB_ID_SIZE)
                while (inputStream.read(bytes) == 32) {
                    val blobId = bytes.toHexString()
                    if (blobId !in blobIds) blobsIdsToKeep.add(blobId)
                }
            }
        } catch (e: FileNotFoundException) {
            log.info { "No do-not-use list found, no need to remove blobs from it." }
            return
        } // if something else goes wrong here, we'll delete the file before next backup
        context.openFileOutput(DO_NOT_USE_FILE_NAME, MODE_PRIVATE).use { outputStream ->
            blobsIdsToKeep.forEach { blobId ->
                val bytes = blobId.toByteArrayFromHex()
                outputStream.write(bytes)
            }
        }
        log.info { "${blobsIdsToKeep.size} blobs remain on do-not-use list." }
    }

}
