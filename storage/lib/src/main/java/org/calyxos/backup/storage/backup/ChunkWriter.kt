/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.backup

import android.util.Log
import org.calyxos.backup.storage.backup.Backup.Companion.VERSION
import org.calyxos.backup.storage.crypto.StreamCrypto
import org.calyxos.backup.storage.db.ChunksCache
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.FileBackupFileType
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
    private val backendGetter: () -> Backend,
    private val androidId: String,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {

    private val backend get() = backendGetter()
    private val buffer = ByteArray(bufferSize)

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
            val isMissing = chunk.id in missingChunkIds
            val notCached = cachedChunk == null
            if (isMissing) Log.w(TAG, "Chunk ${chunk.id} is missing (cached: ${!notCached})")
            if (notCached || isMissing) { // chunk not in storage
                writeChunkData(chunk.id) { encryptingStream ->
                    copyChunkFromInputStream(inputStream, chunk, encryptingStream)
                }
                if (notCached) chunksCache.insert(chunk.toCachedChunk())
                writtenChunks++
                writtenBytes += chunk.size
            } else { // chunk already uploaded
                val skipped = inputStream.skip(chunk.size)
                check(chunk.size == skipped) { "skipping error" }
            }
        }
        val endByte = inputStream.read()
        check(endByte == -1) { "Stream did continue with $endByte" }
        return ChunkWriterResult(writtenChunks, writtenBytes)
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    private suspend fun writeChunkData(chunkId: String, writer: (OutputStream) -> Unit) {
        val handle = FileBackupFileType.Blob(androidId, chunkId)
        backend.save(handle).use { chunkStream ->
            chunkStream.write(VERSION.toInt())
            val ad = streamCrypto.getAssociatedDataForChunk(chunkId)
            streamCrypto.newEncryptingStream(streamKey, chunkStream, ad).use { encryptingStream ->
                writer(encryptingStream)
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
            val sizeLeft = (chunk.size - totalBytesRead).toInt()
            val bytesRead = inputStream.read(buffer, 0, min(bufferSize, sizeLeft))
            if (bytesRead == -1) throw IOException("unexpected end of stream for ${chunk.id}")
            outputStream.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead
        } while (bytesRead >= 0 && totalBytesRead < chunk.size)
        check(totalBytesRead == chunk.size) {
            "copyChunkFromInputStream: $totalBytesRead != ${chunk.size}"
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
        writeChunkData(chunk.id) { encryptingStream ->
            zip.writeTo(encryptingStream)
        }
        if (cachedChunk == null) chunksCache.insert(chunk.toCachedChunk())
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
