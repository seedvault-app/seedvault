/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import com.github.luben.zstd.ZstdOutputStream
import com.google.protobuf.ByteString
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.proto.Snapshot.Blob
import okio.Buffer
import okio.buffer
import okio.sink
import org.calyxos.seedvault.chunker.Chunk
import org.calyxos.seedvault.core.backends.AppBackupFileType

internal class BlobCreator(
    private val crypto: Crypto,
    private val backendManager: BackendManager,
) {

    private val buffer = Buffer()

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
        // TODO exception handling and retries
        val size = backendManager.backend.save(handle).use { outputStream ->
            val outputBuffer = outputStream.sink().buffer()
            val length = outputBuffer.writeAll(buffer)
            // flushing is important here, otherwise data doesn't get fully written!
            outputBuffer.flush()
            length
        }
        return Blob.newBuilder()
            .setId(ByteString.copyFrom(sha256ByteString.asByteBuffer()))
            .setLength(size.toInt())
            .setUncompressedLength(chunk.length)
            .build()
    }
}
