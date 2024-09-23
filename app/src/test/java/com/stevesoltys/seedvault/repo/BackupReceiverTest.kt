/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.transport.TransportTest
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.chunker.Chunk
import org.calyxos.seedvault.chunker.Chunker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

internal class BackupReceiverTest : TransportTest() {

    private val blobCache: BlobCache = mockk()
    private val blobCreator: BlobCreator = mockk()
    private val chunker: Chunker = mockk()

    private val backupReceiver = BackupReceiver(
        blobCache = blobCache,
        blobCreator = blobCreator,
        crypto = crypto,
        replaceableChunker = chunker,
    )

    @Test
    fun `ownership is enforced`() = runBlocking {
        every { chunker.addBytes(ByteArray(0)) } returns emptySequence()

        backupReceiver.addBytes("foo", ByteArray(0))
        assertThrows<IllegalStateException> {
            backupReceiver.addBytes("bar", ByteArray(0))
        }
        every { chunker.finalize() } returns emptySequence()
        assertThrows<IllegalStateException> {
            backupReceiver.readFromStream("bar", ByteArrayInputStream(ByteArray(0)))
        }
        assertThrows<IllegalStateException> {
            backupReceiver.finalize("bar")
        }
        // finalize with proper owner
        backupReceiver.finalize("foo")
        // now "bar" can add bytes
        backupReceiver.addBytes("bar", ByteArray(0))
    }

    @Test
    fun `add bytes and finalize`() = runBlocking {
        val bytes = getRandomByteArray()
        val chunkBytes1 = getRandomByteArray()
        val chunkBytes2 = getRandomByteArray()
        val chunk1 = Chunk(0, chunkBytes1.size, chunkBytes1, "hash1")
        val chunk2 = Chunk(0, chunkBytes2.size, chunkBytes2, "hash2")

        // chunk1 is new, but chunk2 is already cached
        every { chunker.addBytes(bytes) } returns sequenceOf(chunk1)
        every { chunker.finalize() } returns sequenceOf(chunk2)
        every { blobCache["hash1"] } returns null
        every { blobCache["hash2"] } returns blob2
        coEvery { blobCreator.createNewBlob(chunk1) } returns blob1
        coEvery { blobCache.saveNewBlob("hash1", blob1) } just Runs

        // add bytes and finalize
        backupReceiver.addBytes("foo", bytes)
        val backupData = backupReceiver.finalize("foo")

        // assert that backupData includes all chunks and blobs
        assertEquals(listOf("hash1", "hash2"), backupData.chunkIds)
        assertEquals(setOf("hash1", "hash2"), backupData.blobMap.keys)
        assertEquals(blob1, backupData.blobMap["hash1"])
        assertEquals(blob2, backupData.blobMap["hash2"])
    }

    @Test
    fun readFromStream() = runBlocking {
        val bytes = getRandomByteArray()
        val chunkBytes1 = getRandomByteArray()
        val chunkBytes2 = getRandomByteArray()
        val chunk1 = Chunk(0, chunkBytes1.size, chunkBytes1, "hash1")
        val chunk2 = Chunk(0, chunkBytes2.size, chunkBytes2, "hash2")

        // chunk1 is new, but chunk2 is already cached
        every { chunker.addBytes(bytes) } returns sequenceOf(chunk1)
        every { chunker.finalize() } returns sequenceOf(chunk2)
        every { blobCache["hash1"] } returns null
        every { blobCache["hash2"] } returns blob2
        coEvery { blobCreator.createNewBlob(chunk1) } returns blob1
        coEvery { blobCache.saveNewBlob("hash1", blob1) } just Runs

        // add bytes and finalize
        val backupData = backupReceiver.readFromStream("foo", ByteArrayInputStream(bytes))

        // assert that backupData includes all chunks and blobs
        assertEquals(listOf("hash1", "hash2"), backupData.chunkIds)
        assertEquals(setOf("hash1", "hash2"), backupData.blobMap.keys)
        assertEquals(blob1, backupData.blobMap["hash1"])
        assertEquals(blob2, backupData.blobMap["hash2"])

        // data should be all empty when calling finalize again
        every { chunker.finalize() } returns emptySequence()
        val backupDataEnd = backupReceiver.finalize("foo")
        assertEquals(emptyList<String>(), backupDataEnd.chunkIds)
        assertEquals(emptyMap<String, Snapshot.Blob>(), backupDataEnd.blobMap)
    }

    @Test
    fun `readFromStream auto-finalizes when it throws`() = runBlocking {
        val inputStream: InputStream = mockk()

        every { inputStream.read(any<ByteArray>()) } throws IOException()
        every { chunker.finalize() } returns emptySequence()

        assertThrows<IOException> {
            backupReceiver.readFromStream("foo", inputStream)
        }

        verify {
            chunker.finalize()
        }

        // bytes can be added with different owner now
        every { chunker.addBytes(ByteArray(0)) } returns emptySequence()
        backupReceiver.addBytes("bar", ByteArray(0))
    }

    @Test
    fun `finalizing happens even if creating new blob throws`() = runBlocking {
        val bytes = getRandomByteArray()
        val chunkBytes1 = getRandomByteArray()
        val chunkBytes2 = getRandomByteArray()
        val chunk1 = Chunk(0, chunkBytes1.size, chunkBytes1, chunkId1)
        val chunk2 = Chunk(0, chunkBytes2.size, chunkBytes2, chunkId2)

        // chunk1 is new, but chunk2 is already cached
        every { chunker.addBytes(bytes) } returns sequenceOf(chunk1)
        every { chunker.finalize() } returns sequenceOf(chunk2)
        every { blobCache[chunkId1] } returns blob1
        every { blobCache[chunkId2] } returns null
        coEvery { blobCreator.createNewBlob(chunk2) } throws IOException()

        assertThrows<IOException> {
            backupReceiver.finalize("foo")
        }

        // now we can finalize again with different owner
        every { chunker.finalize() } returns emptySequence()
        val backupData = backupReceiver.finalize("foo")

        // data should be all empty, not include blob1
        assertEquals(emptyList<String>(), backupData.chunkIds)
        assertEquals(emptyMap<String, Snapshot.Blob>(), backupData.blobMap)
    }
}
