/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.backup

import org.calyxos.backup.storage.db.CachedChunk
import org.calyxos.backup.storage.toHexString
import java.io.IOException
import java.io.InputStream
import javax.crypto.Mac
import kotlin.math.min

internal data class Chunk(
    val id: String,
    val offset: Long,
    val size: Long,
) {
    fun toCachedChunk() = CachedChunk(id, 0, size)
}

internal class Chunker(
    private val mac: Mac,
    private val chunkSizeMax: Int,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {

    private val buffer = ByteArray(bufferSize)

    /**
     * Returns a list of chunks
     */
    @Throws(IOException::class)
    fun makeChunks(inputStream: InputStream): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        var chunkStart = 0L
        var chunkBytesRead = 0
        var totalBytesRead = 0L

        var bytesRead: Int
        do {
            bytesRead = inputStream.read(buffer, 0, min(bufferSize, chunkSizeMax - chunkBytesRead))
            if (bytesRead > 0) {
                mac.update(buffer, 0, bytesRead)
                chunkBytesRead += bytesRead
                totalBytesRead += bytesRead
            }
            if ((bytesRead < 0 && chunkStart != totalBytesRead) || chunkBytesRead >= chunkSizeMax) {
                val chunkId = mac.doFinal().toHexString()
                val chunkLength = totalBytesRead - chunkStart
                val chunk = Chunk(chunkId, chunkStart, chunkLength)
                chunks.add(chunk)
                chunkStart = totalBytesRead
                chunkBytesRead = 0
            }
        } while (bytesRead >= 0)
        return chunks
    }
}
