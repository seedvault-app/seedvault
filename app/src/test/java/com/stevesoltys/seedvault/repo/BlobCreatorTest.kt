/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.transport.TransportTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.chunker.Chunk
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.toHexString
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.random.Random

internal class BlobCreatorTest : TransportTest() {

    private val backendManager: BackendManager = mockk()
    private val backend: Backend = mockk()
    private val blobCreator = BlobCreator(crypto, backendManager)

    private val ad = Random.nextBytes(1)
    private val passThroughOutputStream = slot<OutputStream>()
    private val passThroughInputStream = slot<InputStream>()
    private val blobHandle = slot<AppBackupFileType.Blob>()

    @Test
    fun `test re-use instance for creating two blobs`() = runBlocking {
        val data1 = Random.nextBytes(1337)
        val data2 = Random.nextBytes(2342)
        val chunk1 = Chunk(0L, data1.size, data1, "doesn't matter here")
        val chunk2 = Chunk(0L, data2.size, data2, "doesn't matter here")
        val outputStream1 = ByteArrayOutputStream()
        val outputStream2 = ByteArrayOutputStream()
        val paddingNum = slot<Int>()

        every { crypto.getAdForVersion() } returns ad
        every { crypto.newEncryptingStream(capture(passThroughOutputStream), ad) } answers {
            passThroughOutputStream.captured // not really encrypting here
        }
        every { crypto.getRandomBytes(capture(paddingNum)) } answers {
            getRandomByteArray(paddingNum.captured)
        }
        every { crypto.repoId } returns repoId
        every { backendManager.backend } returns backend

        // create first blob
        coEvery { backend.save(capture(blobHandle)) } returns outputStream1
        val blob1 = blobCreator.createNewBlob(chunk1)
        // check that file content hash matches snapshot hash
        val messageDigest = MessageDigest.getInstance("SHA-256")
        val hash1 = messageDigest.digest(outputStream1.toByteArray()).toHexString()
        assertEquals(hash1, blobHandle.captured.name)

        // check blob metadata
        assertEquals(hash1, blob1.id.hexFromProto())
        assertEquals(outputStream1.size(), blob1.length)
        assertEquals(data1.size, blob1.uncompressedLength)

        // use same BlobCreator to create another blob, because we re-use a single buffer
        // and need to check clearing that does work as expected
        coEvery { backend.save(capture(blobHandle)) } returns outputStream2
        val blob2 = blobCreator.createNewBlob(chunk2)
        // check that file content hash matches snapshot hash
        val hash2 = messageDigest.digest(outputStream2.toByteArray()).toHexString()
        assertEquals(hash2, blobHandle.captured.name)

        // both hashes are different
        assertNotEquals(hash1, hash2)

        // check blob metadata
        assertEquals(hash2, blob2.id.hexFromProto())
        assertEquals(outputStream2.size(), blob2.length)
        assertEquals(data2.size, blob2.uncompressedLength)
    }

    @Test
    fun `create and load blob`() = runBlocking {
        val data = getRandomByteArray(Random.nextInt(8 * 1024 * 1024))
        val chunk = Chunk(0L, data.size, data, "doesn't matter here")
        val outputStream = ByteArrayOutputStream()
        val paddingNum = slot<Int>()

        every { crypto.getAdForVersion() } returns ad
        every { crypto.newEncryptingStream(capture(passThroughOutputStream), ad) } answers {
            passThroughOutputStream.captured // not really encrypting here
        }
        every { crypto.getRandomBytes(capture(paddingNum)) } answers {
            getRandomByteArray(paddingNum.captured)
        }
        every { crypto.repoId } returns repoId
        every { backendManager.backend } returns backend

        // create blob
        coEvery { backend.save(capture(blobHandle)) } returns outputStream
        val blob = blobCreator.createNewBlob(chunk)
        // check that file content hash matches snapshot hash
        val messageDigest = MessageDigest.getInstance("SHA-256")
        val hash = messageDigest.digest(outputStream.toByteArray()).toHexString()
        assertEquals(hash, blobHandle.captured.name)

        // check blob metadata
        assertEquals(hash, blob.id.hexFromProto())
        assertEquals(outputStream.size(), blob.length)
        assertEquals(data.size, blob.uncompressedLength)

        // prepare blob loading
        val blobHandle = AppBackupFileType.Blob(repoId, hash)
        coEvery {
            backend.load(blobHandle)
        } returns ByteArrayInputStream(outputStream.toByteArray())
        every {
            crypto.sha256(outputStream.toByteArray())
        } returns messageDigest.digest(outputStream.toByteArray()) // same hash came out
        every {
            crypto.newDecryptingStream(capture(passThroughInputStream), ad)
        } answers {
            passThroughInputStream.captured // not really decrypting here
        }

        // load blob
        val loader = Loader(crypto, backendManager) // need a real loader
        loader.loadFile(blobHandle).use { inputStream ->
            // data came back out
            assertArrayEquals(data, inputStream.readAllBytes())
        }
    }
}
