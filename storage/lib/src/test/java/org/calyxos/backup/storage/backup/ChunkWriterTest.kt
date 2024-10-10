/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.backup

import io.mockk.MockKMatcherScope
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.calyxos.backup.storage.backup.Backup.Companion.VERSION
import org.calyxos.backup.storage.crypto.StreamCrypto
import org.calyxos.backup.storage.db.ChunksCache
import org.calyxos.backup.storage.getRandomString
import org.calyxos.backup.storage.mockLog
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.FileBackupFileType.Blob
import org.calyxos.seedvault.core.crypto.CoreCrypto.KEY_SIZE_BYTES
import org.calyxos.seedvault.core.toHexString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

internal class ChunkWriterTest {

    private val streamCrypto: StreamCrypto = mockk()
    private val chunksCache: ChunksCache = mockk()
    private val backendGetter: () -> Backend = mockk()
    private val backend: Backend = mockk()
    private val androidId: String = getRandomString()
    private val streamKey: ByteArray = Random.nextBytes(KEY_SIZE_BYTES)
    private val ad1: ByteArray = Random.nextBytes(34)
    private val ad2: ByteArray = Random.nextBytes(34)
    private val ad3: ByteArray = Random.nextBytes(34)
    private val chunkWriter = ChunkWriter(
        streamCrypto = streamCrypto,
        streamKey = streamKey,
        chunksCache = chunksCache,
        backendGetter = backendGetter,
        androidId = androidId,
        bufferSize = Random.nextInt(1, 42),
    )

    private val chunkId1 = Random.nextBytes(KEY_SIZE_BYTES).toHexString()
    private val chunkId2 = Random.nextBytes(KEY_SIZE_BYTES).toHexString()
    private val chunkId3 = Random.nextBytes(KEY_SIZE_BYTES).toHexString()

    init {
        mockLog()
        every { backendGetter() } returns backend
    }

    @Test
    fun testTwoByteChunksNotCached() = runBlocking {
        val inputBytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)
        val inputStream = ByteArrayInputStream(inputBytes)
        val chunks = listOf(
            Chunk(chunkId1, 0, 2),
            Chunk(chunkId2, 2, 2),
            Chunk(chunkId3, 4, 2),
        )
        val chunk1Output = ByteArrayOutputStream()
        val chunk2Output = ByteArrayOutputStream()
        val chunk3Output = ByteArrayOutputStream()
        val outputStreams = listOf(chunk1Output, chunk2Output, chunk3Output)

        // chunks are not cached
        every { chunksCache.get(chunkId1) } returns null
        every { chunksCache.get(chunkId2) } returns null
        every { chunksCache.get(chunkId3) } returns null

        // get the output streams for the chunks
        coEvery { backend.save(Blob(androidId, chunkId1)) } returns chunk1Output
        coEvery { backend.save(Blob(androidId, chunkId2)) } returns chunk2Output
        coEvery { backend.save(Blob(androidId, chunkId3)) } returns chunk3Output

        // get AD
        every { streamCrypto.getAssociatedDataForChunk(chunkId1) } returns ad1
        every { streamCrypto.getAssociatedDataForChunk(chunkId2) } returns ad2
        every { streamCrypto.getAssociatedDataForChunk(chunkId3) } returns ad3

        // wrap output stream in crypto stream
        every {
            streamCrypto.newEncryptingStream(streamKey, chunk1Output, ad1)
        } returns chunk1Output
        every {
            streamCrypto.newEncryptingStream(streamKey, chunk2Output, ad2)
        } returns chunk2Output
        every {
            streamCrypto.newEncryptingStream(streamKey, chunk3Output, ad3)
        } returns chunk3Output

        // insert chunks into cache after upload
        every { chunksCache.insert(chunks[0].toCachedChunk()) } just Runs
        every { chunksCache.insert(chunks[1].toCachedChunk()) } just Runs
        every { chunksCache.insert(chunks[2].toCachedChunk()) } just Runs

        chunkWriter.writeChunk(inputStream, chunks, emptyList())

        // check that version was written as the first byte
        outputStreams.forEach { outputStream ->
            assertEquals(VERSION, outputStream.toByteArray()[0])
        }

        // check that output matches chunk data
        assertArrayEquals(byteArrayOf(0x00, 0x01), chunk1Output.toByteArray().copyOfRange(1, 3))
        assertArrayEquals(byteArrayOf(0x02, 0x03), chunk2Output.toByteArray().copyOfRange(1, 3))
        assertArrayEquals(byteArrayOf(0x04, 0x05), chunk3Output.toByteArray().copyOfRange(1, 3))
    }

    @Test
    fun testCachedChunksSkippedIfNotMissing() = runBlocking {
        val inputBytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)
        val inputStream = ByteArrayInputStream(inputBytes)
        val chunks = listOf(
            Chunk(chunkId1, 0, 2), // cached and missing
            Chunk(chunkId2, 2, 2), // cached and available
            Chunk(chunkId3, 4, 2), // not cached
        )
        val chunk1Output = ByteArrayOutputStream()
        val chunk3Output = ByteArrayOutputStream()

        // only first two chunks are cached (first chunk is missing from storage)
        every { chunksCache.get(chunkId1) } returns chunks[0].toCachedChunk()
        every { chunksCache.get(chunkId2) } returns chunks[1].toCachedChunk()
        every { chunksCache.get(chunkId3) } returns null

        // get and wrap the output stream for chunk that is missing
        coEvery { backend.save(Blob(androidId, chunkId1)) } returns chunk1Output
        every { streamCrypto.getAssociatedDataForChunk(chunkId1) } returns ad1
        every {
            streamCrypto.newEncryptingStream(streamKey, chunk1Output, bytes(34))
        } returns chunk1Output

        // insert missing cached chunk into cache after upload
        every { chunksCache.insert(chunks[0].toCachedChunk()) } just Runs

        // get and wrap the output stream for chunk that isn't cached
        coEvery { backend.save(Blob(androidId, chunkId3)) } returns chunk3Output
        every { streamCrypto.getAssociatedDataForChunk(chunkId3) } returns ad3
        every {
            streamCrypto.newEncryptingStream(streamKey, chunk3Output, bytes(34))
        } returns chunk3Output

        // insert last not cached chunk into cache after upload
        every { chunksCache.insert(chunks[2].toCachedChunk()) } just Runs

        chunkWriter.writeChunk(inputStream, chunks, listOf(chunkId1))

        // check that output matches chunk data
        assertEquals(VERSION, chunk1Output.toByteArray()[0])
        assertArrayEquals(byteArrayOf(0x00, 0x01), chunk1Output.toByteArray().copyOfRange(1, 3))
        assertEquals(VERSION, chunk3Output.toByteArray()[0])
        assertArrayEquals(byteArrayOf(0x04, 0x05), chunk3Output.toByteArray().copyOfRange(1, 3))
    }

    @Test
    fun testLargerRandomChunks() = runBlocking {
        val chunk1Bytes = Random.nextBytes(Random.nextInt(1, 1024 * 1024))
        val chunk2Bytes = Random.nextBytes(Random.nextInt(1, 1024 * 1024))
        val chunk3Bytes = Random.nextBytes(Random.nextInt(1, 1024 * 1024))
        val inputStream = ByteArrayInputStream(chunk1Bytes + chunk2Bytes + chunk3Bytes)
        val chunks = listOf(
            Chunk(chunkId1, 0, chunk1Bytes.size.toLong()),
            Chunk(chunkId2, chunk1Bytes.size.toLong(), chunk2Bytes.size.toLong()),
            Chunk(
                chunkId3,
                (chunk1Bytes.size + chunk2Bytes.size).toLong(),
                chunk3Bytes.size.toLong()
            ),
        )
        val chunk1Output = ByteArrayOutputStream()
        val chunk3Output = ByteArrayOutputStream()
        val outputStreams = listOf(chunk1Output, chunk3Output)

        // first and last chunk are not cached
        every { chunksCache.get(chunkId1) } returns null
        every { chunksCache.get(chunkId2) } returns chunks[1].toCachedChunk()
        every { chunksCache.get(chunkId3) } returns null

        // get the output streams for the chunks
        coEvery { backend.save(Blob(androidId, chunkId1)) } returns chunk1Output
        coEvery { backend.save(Blob(androidId, chunkId3)) } returns chunk3Output

        // get AD
        every { streamCrypto.getAssociatedDataForChunk(chunkId1) } returns ad1
        every { streamCrypto.getAssociatedDataForChunk(chunkId3) } returns ad3

        // wrap output streams in crypto streams
        every {
            streamCrypto.newEncryptingStream(streamKey, chunk1Output, ad1)
        } returns chunk1Output
        every {
            streamCrypto.newEncryptingStream(streamKey, chunk3Output, ad3)
        } returns chunk3Output

        // insert chunks into cache after upload
        every { chunksCache.insert(chunks[0].toCachedChunk()) } just Runs
        every { chunksCache.insert(chunks[2].toCachedChunk()) } just Runs

        chunkWriter.writeChunk(inputStream, chunks, emptyList())

        // check that version and wrapped key was written as the first byte
        outputStreams.forEach { outputStream ->
            assertEquals(VERSION, outputStream.toByteArray()[0])
        }

        // check that output matches chunk data
        assertEquals(1 + chunks[0].size.toInt(), chunk1Output.size())
        assertArrayEquals(
            chunk1Bytes,
            chunk1Output.toByteArray().copyOfRange(1, 1 + chunks[0].size.toInt())
        )
        assertEquals(1 + chunks[2].size.toInt(), chunk3Output.size())
        assertArrayEquals(
            chunk3Bytes,
            chunk3Output.toByteArray().copyOfRange(1, 1 + chunks[2].size.toInt())
        )
    }

    private fun MockKMatcherScope.bytes(size: Int) = match<ByteArray> {
        it.size == size
    }

}
