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
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.calyxos.backup.storage.backup.Backup.Companion.VERSION
import org.calyxos.backup.storage.crypto.StreamCrypto
import org.calyxos.backup.storage.db.ChunksCache
import org.calyxos.backup.storage.getRandomString
import org.calyxos.backup.storage.mockLog
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.BackendSaver
import org.calyxos.seedvault.core.backends.FileBackupFileType.Blob
import org.calyxos.seedvault.core.backends.IBackendManager
import org.calyxos.seedvault.core.crypto.CoreCrypto.KEY_SIZE_BYTES
import org.calyxos.seedvault.core.toHexString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.random.Random
import kotlin.test.assertFailsWith

internal class ChunkWriterTest {

    private val streamCrypto: StreamCrypto = mockk()
    private val chunksCache: ChunksCache = mockk()
    private val backendManager: IBackendManager = mockk()
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
        backendManager = backendManager,
        androidId = androidId,
        bufferSize = Random.nextInt(1, 42),
    )

    private val chunkId1 = Random.nextBytes(KEY_SIZE_BYTES).toHexString()
    private val chunkId2 = Random.nextBytes(KEY_SIZE_BYTES).toHexString()
    private val chunkId3 = Random.nextBytes(KEY_SIZE_BYTES).toHexString()

    init {
        mockLog()
        every { backendManager.backend } returns backend
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

        // get AD
        every { streamCrypto.getAssociatedDataForChunk(chunkId1) } returns ad1
        every { streamCrypto.getAssociatedDataForChunk(chunkId2) } returns ad2
        every { streamCrypto.getAssociatedDataForChunk(chunkId3) } returns ad3

        // wrap output stream in crypto stream
        val streamSlot = slot<OutputStream>()
        every { streamCrypto.newEncryptingStream(streamKey, capture(streamSlot), ad1) } answers {
            streamSlot.captured
        }
        every { streamCrypto.newEncryptingStream(streamKey, capture(streamSlot), ad2) } answers {
            streamSlot.captured
        }
        every { streamCrypto.newEncryptingStream(streamKey, capture(streamSlot), ad3) } answers {
            streamSlot.captured
        }

        // save the chunks
        val saverSlot = slot<BackendSaver>()
        coEvery {
            backend.save(Blob(androidId, chunkId1), capture(saverSlot))
        } answers {
            saverSlot.captured.save(chunk1Output)
        }
        coEvery { backend.save(Blob(androidId, chunkId2), capture(saverSlot)) } answers {
            saverSlot.captured.save(chunk2Output)
        }
        coEvery { backend.save(Blob(androidId, chunkId3), capture(saverSlot)) } answers {
            saverSlot.captured.save(chunk3Output)
        }

        // insert chunks into cache after upload
        every { chunksCache.insert(chunks[0].toCachedChunk(3)) } just Runs
        every { chunksCache.insert(chunks[1].toCachedChunk(3)) } just Runs
        every { chunksCache.insert(chunks[2].toCachedChunk(3)) } just Runs

        chunkWriter.writeChunk(inputStream, chunks, emptyList()) { false }

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
        every { chunksCache.get(chunkId1) } returns chunks[0].toCachedChunk(1)
        every { chunksCache.get(chunkId2) } returns chunks[1].toCachedChunk(2)
        every { chunksCache.get(chunkId3) } returns null

        // get and wrap the output stream for chunk that is missing
        val saverSlot = slot<BackendSaver>()
        coEvery { backend.save(Blob(androidId, chunkId1), capture(saverSlot)) } answers {
            saverSlot.captured.save(chunk1Output)
        }
        every { streamCrypto.getAssociatedDataForChunk(chunkId1) } returns ad1
        val streamSlot = slot<OutputStream>()
        every {
            streamCrypto.newEncryptingStream(streamKey, capture(streamSlot), bytes(34))
        } answers {
            streamSlot.captured
        }

        // insert missing cached chunk into cache after upload
        every { chunksCache.insert(chunks[0].toCachedChunk(3)) } just Runs

        // get and wrap the output stream for chunk that isn't cached
        coEvery { backend.save(Blob(androidId, chunkId3), capture(saverSlot)) } answers {
            saverSlot.captured.save(chunk3Output)
        }
        every { streamCrypto.getAssociatedDataForChunk(chunkId3) } returns ad3

        // insert last not cached chunk into cache after upload
        every { chunksCache.insert(chunks[2].toCachedChunk(3)) } just Runs

        chunkWriter.writeChunk(inputStream, chunks, listOf(chunkId1)) { false }

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
        every { chunksCache.get(chunkId2) } returns chunks[1].toCachedChunk(1)
        every { chunksCache.get(chunkId3) } returns null

        // get the output streams for the chunks
        val saverSlot = slot<BackendSaver>()
        coEvery { backend.save(Blob(androidId, chunkId1), capture(saverSlot)) } answers {
            saverSlot.captured.save(chunk1Output)
        }
        coEvery { backend.save(Blob(androidId, chunkId3), capture(saverSlot)) } answers {
            saverSlot.captured.save(chunk3Output)
        }

        // get AD
        every { streamCrypto.getAssociatedDataForChunk(chunkId1) } returns ad1
        every { streamCrypto.getAssociatedDataForChunk(chunkId3) } returns ad3

        // wrap output streams in crypto streams
        val streamSlot = slot<OutputStream>()
        every { streamCrypto.newEncryptingStream(streamKey, capture(streamSlot), ad1) } answers {
            streamSlot.captured
        }
        every { streamCrypto.newEncryptingStream(streamKey, capture(streamSlot), ad3) } answers {
            streamSlot.captured
        }

        // insert chunks into cache after upload
        every {
            chunksCache.insert(chunks[0].toCachedChunk(chunk1Bytes.size.toLong() + 1))
        } just Runs
        every {
            chunksCache.insert(chunks[2].toCachedChunk(chunk3Bytes.size.toLong() + 1))
        } just Runs

        chunkWriter.writeChunk(inputStream, chunks, emptyList()) { false }

        // check that version and wrapped key was written as the first byte
        outputStreams.forEach { outputStream ->
            assertEquals(VERSION, outputStream.toByteArray()[0])
        }

        // check that output matches chunk data
        assertEquals(1 + chunks[0].plaintextSize.toInt(), chunk1Output.size())
        assertArrayEquals(
            chunk1Bytes,
            chunk1Output.toByteArray().copyOfRange(1, 1 + chunks[0].plaintextSize.toInt())
        )
        assertEquals(1 + chunks[2].plaintextSize.toInt(), chunk3Output.size())
        assertArrayEquals(
            chunk3Bytes,
            chunk3Output.toByteArray().copyOfRange(1, 1 + chunks[2].plaintextSize.toInt())
        )
    }

    @Test
    fun testChunkSavingCanBeRetried() = runBlocking {
        val chunkBytes = Random.nextBytes(16 * 1024 * 1024)
        val inputStream = ByteArrayInputStream(chunkBytes)
        val chunks = listOf(
            Chunk(chunkId1, 0, chunkBytes.size.toLong()),
        )
        val chunkOutput1 = ByteArrayOutputStream()
        val chunkOutput2 = ByteArrayOutputStream()
        val chunkOutput3 = ByteArrayOutputStream()

        // chunk is not cached
        every { chunksCache.get(chunkId1) } returns null

        // get the output streams for the chunks
        val saverSlot = slot<BackendSaver>()
        var size1 = -1L
        var size2 = -1L
        coEvery { backend.save(Blob(androidId, chunkId1), capture(saverSlot)) } answers {
            // saver saves 3 times
            size1 = saverSlot.captured.save(chunkOutput1)
            size2 = saverSlot.captured.save(chunkOutput2)
            saverSlot.captured.save(chunkOutput3)
        }

        // wrap output streams in crypto streams
        val streamSlot = slot<OutputStream>()
        every { streamCrypto.getAssociatedDataForChunk(chunkId1) } returns ad1
        every { streamCrypto.newEncryptingStream(streamKey, capture(streamSlot), ad1) } answers {
            streamSlot.captured
        }

        // insert chunks into cache after upload
        every {
            chunksCache.insert(chunks[0].toCachedChunk(chunkBytes.size.toLong() + 1))
        } just Runs

        chunkWriter.writeChunk(inputStream, chunks, emptyList()) { false }

        // check that output matches chunk data
        assertEquals(1 + chunks[0].plaintextSize.toInt(), chunkOutput1.size())
        assertEquals(1 + chunks[0].plaintextSize.toInt(), chunkOutput2.size())
        assertEquals(1 + chunks[0].plaintextSize.toInt(), chunkOutput3.size())
        assertArrayEquals(
            chunkBytes,
            chunkOutput1.toByteArray().copyOfRange(1, 1 + chunks[0].plaintextSize.toInt())
        )
        assertArrayEquals(
            chunkBytes,
            chunkOutput2.toByteArray().copyOfRange(1, 1 + chunks[0].plaintextSize.toInt())
        )
        assertArrayEquals(
            chunkBytes,
            chunkOutput3.toByteArray().copyOfRange(1, 1 + chunks[0].plaintextSize.toInt())
        )
        assertEquals(chunkOutput3.size().toLong(), size1)
        assertEquals(chunkOutput3.size().toLong(), size2)
    }

    @Test
    fun testAbort() = runBlocking {
        val chunkBytes = Random.nextBytes(16 * 1024 * 1024)
        val inputStream = ByteArrayInputStream(chunkBytes)
        val chunks = listOf(
            Chunk(chunkId1, 0, chunkBytes.size.toLong()),
        )

        val e = assertFailsWith<IOException> {
            chunkWriter.writeChunk(inputStream, chunks, emptyList()) { true }
        }
        assertEquals("Metered Network", e.message)
    }

    private fun MockKMatcherScope.bytes(size: Int) = match<ByteArray> {
        it.size == size
    }

}
