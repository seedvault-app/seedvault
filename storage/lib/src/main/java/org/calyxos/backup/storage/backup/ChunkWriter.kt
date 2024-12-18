/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.backup

import android.util.Log
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.Buffer
import okio.buffer
import okio.sink
import org.calyxos.backup.storage.backup.Backup.Companion.VERSION
import org.calyxos.backup.storage.crypto.StreamCrypto
import org.calyxos.backup.storage.db.ChunksCache
import org.calyxos.seedvault.core.backends.BackendSaver
import org.calyxos.seedvault.core.backends.FileBackupFileType
import org.calyxos.seedvault.core.backends.IBackendManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.attribute.FileTime
import java.security.GeneralSecurityException
import java.util.zip.ZipEntry
import kotlin.math.min

internal data class ChunkWriterResult(
    val numChunksWritten: Int,
    val bytesWritten: Long,
)

private const val TAG = "ChunkWriter"

internal class ChunkWriter(
    private val streamCrypto: StreamCrypto,
    private val streamKey: ByteArray,
    private val chunksCache: ChunksCache,
    private val backendManager: IBackendManager,
    private val androidId: String,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {

    private val backend get() = backendManager.backend
    private val semaphore = Semaphore(1)
    private val byteBuffer = ByteArray(bufferSize)
    private val buffer = Buffer()

    @Throws(IOException::class, GeneralSecurityException::class)
    suspend fun writeChunk(
        inputStream: InputStream,
        chunks: List<Chunk>,
        missingChunkIds: List<String>,
    ): ChunkWriterResult {
        var writtenChunks = 0
        var writtenBytes = 0L
        chunks.forEach { chunk ->
            val cachedChunk = chunksCache.get(chunk.id)
            // TODO missing chunks used by several files will get uploaded several times
            val isMissing = chunk.id in missingChunkIds
            val notCached = cachedChunk == null
            if (isMissing) Log.w(TAG, "Chunk ${chunk.id} is missing (cached: ${!notCached})")
            if (notCached || isMissing) { // chunk not in storage
                val size = writeChunkData(chunk.id) { encryptingStream ->
                    copyChunkFromInputStream(inputStream, chunk, encryptingStream)
                }
                if (notCached) chunksCache.insert(chunk.toCachedChunk(size))
                writtenChunks++
                writtenBytes += size
            } else { // chunk already uploaded
                val skipped = inputStream.skip(chunk.plaintextSize)
                check(chunk.plaintextSize == skipped) { "skipping error" }
            }
        }
        val endByte = inputStream.read()
        check(endByte == -1) { "Stream did continue with $endByte" }
        return ChunkWriterResult(writtenChunks, writtenBytes)
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    private suspend fun writeChunkData(chunkId: String, writer: (OutputStream) -> Unit): Long {
        val handle = FileBackupFileType.Blob(androidId, chunkId)
        semaphore.withPermit { // only allow one writer using the buffer at a time
            buffer.clear()
            buffer.writeByte(VERSION.toInt())
            val ad = streamCrypto.getAssociatedDataForChunk(chunkId)
            streamCrypto.newEncryptingStream(streamKey, buffer.outputStream(), ad).use { stream ->
                writer(stream)
            }
            val saver = object : BackendSaver {
                override val size: Long = buffer.size
                override val sha256: String = buffer.sha256().hex()
                override fun save(outputStream: OutputStream): Long {
                    val outputBuffer = outputStream.sink().buffer()
                    val length = outputBuffer.writeAll(buffer)
                    // flushing is important here, otherwise data doesn't get fully written!
                    outputBuffer.flush()
                    return length
                }
            }
            return try {
                backend.save(handle, saver)
            } finally {
                buffer.clear()
            }
        }
    }

    @Throws(IOException::class)
    private fun copyChunkFromInputStream(
        inputStream: InputStream,
        chunk: Chunk,
        outputStream: OutputStream,
    ) {
        var totalBytesRead = 0L
        do {
            val sizeLeft = (chunk.plaintextSize - totalBytesRead).toInt()
            val bytesRead = inputStream.read(byteBuffer, 0, min(bufferSize, sizeLeft))
            if (bytesRead == -1) throw IOException("unexpected end of stream for ${chunk.id}")
            outputStream.write(byteBuffer, 0, bytesRead)
            totalBytesRead += bytesRead
        } while (bytesRead >= 0 && totalBytesRead < chunk.plaintextSize)
        check(totalBytesRead == chunk.plaintextSize) {
            "copyChunkFromInputStream: $totalBytesRead != ${chunk.plaintextSize}"
        }
    }

    /**
     * Writes the zip chunk to backup storage.
     *
     * @return true if the chunk was written or false, if it was present already.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    suspend fun writeZipChunk(
        chunk: ZipChunk,
        zip: ByteArrayOutputStream,
        missingChunkIds: List<String>,
    ): Boolean {
        val cachedChunk = chunksCache.get(chunk.id)
        val isMissing = chunk.id in missingChunkIds
        if (isMissing) Log.w(TAG, "Chunk ${chunk.id} is missing (cached: ${cachedChunk != null})")
        if (cachedChunk != null && !isMissing) return false
        // chunk not yet uploaded
        val size = writeChunkData(chunk.id) { encryptingStream ->
            zip.writeTo(encryptingStream)
        }
        if (cachedChunk == null) chunksCache.insert(chunk.toCachedChunk(size))
        return true
    }

    @Throws(IOException::class)
    fun writeNewZipEntry(
        zipOutputStream: NameZipOutputStream,
        counter: Int,
        inputStream: InputStream,
    ) {
        // If copying below throws an exception, we'd be adding a new entry with the same counter,
        // so we check if we have added an entry for that counter already to prevent duplicates.
        if ((zipOutputStream.lastName?.toIntOrNull() ?: 0) != counter) {
            val entry = createNewZipEntry(counter)
            zipOutputStream.putNextEntry(entry)
        }
        inputStream.copyTo(zipOutputStream)
        zipOutputStream.closeEntry()
    }

    private fun createNewZipEntry(counter: Int) = ZipEntry(counter.toString()).apply {
        // needed to make the ZIP and thus the MAC deterministic
        lastModifiedTime = FileTime.fromMillis(0)
    }

}
