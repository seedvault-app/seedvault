/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import android.content.Context
import android.content.Context.MODE_APPEND
import com.stevesoltys.seedvault.MemoryLogger
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.proto.Snapshot.Blob
import io.github.oshai.kotlinlogging.KotlinLogging
import org.calyxos.seedvault.core.backends.FileInfo
import org.calyxos.seedvault.core.toByteArrayFromHex
import org.calyxos.seedvault.core.toHexString
import java.io.FileNotFoundException
import java.io.IOException

private const val CACHE_FILE_NAME = "blobsCache"

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
        val blobIds = blobs.associate {
            Pair(it.fileHandle.name, it.size.toInt())
        }
        // load local blob cache and include only blobs on backend
        loadPersistentBlobCache(blobIds)
        // build up mapping from chunkId to blob from available snapshots
        snapshots.forEach { snapshot ->
            onSnapshotLoaded(snapshot, blobIds)
        }
        MemoryLogger.log()
    }

    /**
     * Should only be called after [populateCache] has returned.
     */
    operator fun get(chunkId: String): Blob? = blobMap[chunkId]

    /**
     * Should get called for all new blobs as soon as they've been saved to the backend.
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
     * Adds mapping from chunkId to [Blob], if it exists on backend, i.e. part of [blobIds]
     * and its size matches the one on backend, i.e. value of [blobIds].
     */
    private fun onSnapshotLoaded(snapshot: Snapshot, blobIds: Map<String, Int>) {
        snapshot.blobsMap.forEach { (chunkId, blob) ->
            // check if referenced blob still exists on backend
            val blobId = blob.id.hexFromProto()
            val sizeOnBackend = blobIds[blobId]
            if (sizeOnBackend == blob.length) {
                // only add blob to our mapping, if it still exists
                blobMap.putIfAbsent(chunkId, blob)?.let { previous ->
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

}
