/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import androidx.annotation.WorkerThread
import com.github.luben.zstd.ZstdOutputStream
import com.google.protobuf.ByteString
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.proto.Snapshot.Blob
import com.stevesoltys.seedvault.proto.SnapshotKt.blob
import okio.Buffer
import okio.buffer
import okio.sink
import org.calyxos.seedvault.chunker.Chunk
import org.calyxos.seedvault.core.backends.AppBackupFileType
import java.io.IOException

/**
 * Creates and uploads new blobs to the current backend.
 */
internal class BlobCreator(
    private val crypto: Crypto,
    private val backendManager: BackendManager,
) {

    private val buffer = Buffer()

    /**
     * Creates and returns a new [Blob] from the given [chunk] and uploads it to the backend.
     */
    @WorkerThread
    @Throws(IOException::class)
    suspend fun createNewBlob(chunk: Chunk): Blob {
        buffer.clear()
        val bufferStream = buffer.outputStream()
        bufferStream.write(VERSION.toInt())
        crypto.newEncryptingStream(bufferStream, crypto.getAdForVersion()).use { cryptoStream ->
            ZstdOutputStream(cryptoStream).use { zstdOutputStream ->
                zstdOutputStream.write(chunk.data)
            }
        }
        val sha256ByteString = buffer.sha256()
        val handle = AppBackupFileType.Blob(crypto.repoId, sha256ByteString.hex())
        // TODO for later: implement a backend wrapper that handles retries for transient errors
        val size = backendManager.backend.save(handle).use { outputStream ->
            val outputBuffer = outputStream.sink().buffer()
            val length = outputBuffer.writeAll(buffer)
            // flushing is important here, otherwise data doesn't get fully written!
            outputBuffer.flush()
            length
        }
        return blob {
            id = ByteString.copyFrom(sha256ByteString.asByteBuffer())
            length = size.toInt()
            uncompressedLength = chunk.length
        }
    }
}
