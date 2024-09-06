/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.proto.Snapshot.Blob
import org.calyxos.seedvault.chunker.Chunk
import org.calyxos.seedvault.chunker.Chunker
import org.calyxos.seedvault.chunker.GearTableCreator
import org.calyxos.seedvault.core.toHexString
import java.io.InputStream

data class BackupData(
    val chunks: List<String>,
    val chunkMap: Map<String, Blob>,
) {
    val size get() = chunkMap.values.sumOf { it.uncompressedLength }.toLong()
}

internal class BackupReceiver(
    private val blobsCache: BlobsCache,
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
                crypto.sha256(bytes).toHexString()
            },
        )
    }
    private val chunks = mutableListOf<String>()
    private val chunkMap = mutableMapOf<String, Blob>()
    private var addedBytes = false

    suspend fun addBytes(bytes: ByteArray) {
        addedBytes = true
        chunker.addBytes(bytes).forEach { chunk ->
            onNewChunk(chunk)
        }
    }

    suspend fun readFromStream(inputStream: InputStream) {
        try {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytes = inputStream.read(buffer)
            while (bytes >= 0) {
                if (bytes == buffer.size) {
                    addBytes(buffer)
                } else {
                    addBytes(buffer.copyOfRange(0, bytes))
                }
                bytes = inputStream.read(buffer)
            }
        } catch (e: Exception) {
            finalize()
            throw e
        }
    }

    suspend fun finalize(): BackupData {
        chunker.finalize().forEach { chunk ->
            onNewChunk(chunk)
        }
        // copy chunks and chunkMap before clearing
        val backupData = BackupData(chunks.toList(), chunkMap.toMap())
        chunks.clear()
        chunkMap.clear()
        addedBytes = false
        return backupData
    }

    fun assertFinalized() {
        // TODO maybe even use a userTag and throw also above if that doesn't match
        check(!addedBytes) { "Re-used non-finalized BackupReceiver" }
    }

    private suspend fun onNewChunk(chunk: Chunk) {
        chunks.add(chunk.hash)

        val existingBlob = blobsCache.getBlob(chunk.hash)
        if (existingBlob == null) {
            val blob = blobCreator.createNewBlob(chunk)
            chunkMap[chunk.hash] = blob
            blobsCache.saveNewBlob(chunk.hash, blob)
        } else {
            chunkMap[chunk.hash] = existingBlob
        }
    }

}
