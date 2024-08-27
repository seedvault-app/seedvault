/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.backup

import org.calyxos.backup.storage.content.ContentFile
import org.calyxos.backup.storage.db.CachedChunk
import org.calyxos.seedvault.core.toHexString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Mac

private const val ZIP_CHUNK_SIZE_MAX = 7 * 1024 * 1024

internal data class ZipChunk(
    val id: String,
    val files: List<ContentFile>,
    val size: Long,
    var wasUploaded: Boolean = false,
) {
    fun toCachedChunk() = CachedChunk(id, 0, size)
}

@Suppress("BlockingMethodInNonBlockingContext")
internal class ZipChunker(
    private val mac: Mac,
    private val chunkWriter: ChunkWriter,
    private val chunkSizeMax: Int = ZIP_CHUNK_SIZE_MAX,
) {

    private val files = ArrayList<ContentFile>()

    private val outputStream = ByteArrayOutputStream(chunkSizeMax)
    private var zipOutputStream = NameZipOutputStream(outputStream)

    // we start with 1, because 0 is the default value in protobuf 3
    private var counter = 1

    fun fitsFile(file: ContentFile): Boolean {
        return outputStream.size() + file.size <= chunkSizeMax
    }

    @Throws(IOException::class)
    fun addFile(file: ContentFile, inputStream: InputStream) {
        chunkWriter.writeNewZipEntry(zipOutputStream, counter, inputStream)
        files.add(file)
        counter++
    }

    /**
     * Finalizes the operation and returns a [ZipChunk] including the previous files.
     * This zip chunk will be uploaded to the backup storage.
     *
     * This object gets reset for the next operation.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    suspend fun finalizeAndReset(missingChunkIds: List<String>): ZipChunk = try {
        zipOutputStream.finish()
        zipOutputStream.close()

        val chunkId = mac.doFinal(outputStream.toByteArray()).toHexString()
        val zipChunk = ZipChunk(chunkId, files.toList(), outputStream.size().toLong())
        val wasUploaded = chunkWriter.writeZipChunk(zipChunk, outputStream, missingChunkIds)
        zipChunk.wasUploaded = wasUploaded

        zipChunk
    } finally {
        // reset the state of the zip chunker for the next operation even if an exception was thrown
        reset()
    }

    private fun reset() {
        files.clear()
        outputStream.reset()
        zipOutputStream = NameZipOutputStream(outputStream)
        counter = 1
    }

}

/**
 * A wrapper for [ZipOutputStream] that remembers the name of the last [ZipEntry] that was added.
 */
internal class NameZipOutputStream(outputStream: OutputStream) : ZipOutputStream(outputStream) {
    internal var lastName: String? = null
        private set

    override fun putNextEntry(e: ZipEntry) {
        super.putNextEntry(e)
        lastName = e.name
    }
}
