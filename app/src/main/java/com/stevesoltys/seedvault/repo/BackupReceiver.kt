/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.proto.Snapshot.Blob
import org.calyxos.seedvault.chunker.Chunk
import org.calyxos.seedvault.chunker.Chunker
import org.calyxos.seedvault.chunker.GearTableCreator
import org.calyxos.seedvault.core.toHexString
import java.io.IOException
import java.io.InputStream

/**
 * The single point for receiving data for backup.
 * Data received will get split into smaller chunks, if needed.
 * [Chunk]s that don't have a corresponding [Blob] in the [blobCache]
 * will be passed to the [blobCreator] and have the new blob saved to the backend.
 *
 * Data can be received either via [addBytes] (requires matching call to [finalize])
 * or via [readFromStream].
 * This call is *not* thread-safe.
 */
internal class BackupReceiver(
    private val blobCache: BlobCache,
    private val blobCreator: BlobCreator,
    private val crypto: Crypto,
    private val replaceableChunker: Chunker? = null,
) {

    private val chunker: Chunker by lazy {
        // crypto.gearTableKey is not available at creation time, so use lazy instantiation
        replaceableChunker ?: Chunker(
            minSize = 1536 * 1024, // 1.5 MB
            avgSize = 3 * 1024 * 1024, // 3.0 MB
            maxSize = 7680 * 1024, // 7.5 MB
            normalization = 1,
            gearTable = GearTableCreator.create(crypto.gearTableKey),
            hashFunction = { bytes ->
                // this calculates the chunkId
                crypto.sha256(bytes).toHexString()
            },
        )
    }
    private val chunks = mutableListOf<String>()
    private val blobMap = mutableMapOf<String, Blob>()
    private var owner: String? = null

    /**
     * Adds more [bytes] to be chunked and saved.
     * Must call [finalize] when done, even when an exception was thrown
     * to free up this re-usable instance of [BackupReceiver].
     */
    @WorkerThread
    @Throws(IOException::class)
    suspend fun addBytes(owner: String, bytes: ByteArray) {
        checkOwner(owner)
        chunker.addBytes(bytes).forEach { chunk ->
            onNewChunk(chunk)
        }
    }

    /**
     * Reads backup data from the given [inputStream] and returns [BackupData],
     * so a call to [finalize] isn't required.
     * The caller must close the [inputStream] when done.
     */
    @WorkerThread
    @Throws(IOException::class)
    suspend fun readFromStream(owner: String, inputStream: InputStream): BackupData {
        checkOwner(owner)
        try {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytes = inputStream.read(buffer)
            while (bytes >= 0) {
                if (bytes == buffer.size) {
                    addBytes(owner, buffer)
                } else {
                    addBytes(owner, buffer.copyOfRange(0, bytes))
                }
                bytes = inputStream.read(buffer)
            }
            return finalize(owner)
        } catch (e: Exception) {
            finalize(owner)
            throw e
        }
    }

    /**
     * Must be called after one or more calls to [addBytes] to finalize usage of this instance
     * and receive the [BackupData] for snapshotting.
     */
    @WorkerThread
    @Throws(IOException::class)
    suspend fun finalize(owner: String): BackupData {
        checkOwner(owner)
        try {
            chunker.finalize().forEach { chunk ->
                onNewChunk(chunk)
            }
            return BackupData(chunks.toList(), blobMap.toMap())
        } finally {
            chunks.clear()
            blobMap.clear()
            this.owner = null
        }
    }

    private suspend fun onNewChunk(chunk: Chunk) {
        chunks.add(chunk.hash)

        val existingBlob = blobCache[chunk.hash]
        if (existingBlob == null) {
            val blob = blobCreator.createNewBlob(chunk)
            blobMap[chunk.hash] = blob
            blobCache.saveNewBlob(chunk.hash, blob)
        } else {
            blobMap[chunk.hash] = existingBlob
        }
    }

    private fun checkOwner(owner: String) {
        if (this.owner == null) this.owner = owner
        else check(this.owner == owner) { "Owned by ${this.owner}, but called from $owner" }
    }

}
