/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import com.stevesoltys.seedvault.backend.BackendManager
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.random.Random

internal class BlobCreatorTest : TransportTest() {

    private val backendManager: BackendManager = mockk()
    private val backend: Backend = mockk()
    private val blobCreator = BlobCreator(crypto, backendManager)

    private val ad = Random.nextBytes(1)
    private val passThroughOutputStream = slot<OutputStream>()
    private val blobHandle = slot<AppBackupFileType.Blob>()

    @Test
    fun `test re-use for hashing two chunks`() = runBlocking {
        val data1 = Random.nextBytes(1337)
        val data2 = Random.nextBytes(2342)
        val chunk1 = Chunk(0L, data1.size, data1, "doesn't matter here")
        val chunk2 = Chunk(0L, data2.size, data2, "doesn't matter here")
        val outputStream1 = ByteArrayOutputStream()
        val outputStream2 = ByteArrayOutputStream()

        every { crypto.getAdForVersion() } returns ad
        every { crypto.newEncryptingStream(capture(passThroughOutputStream), ad) } answers {
            passThroughOutputStream.captured // not really encrypting here
        }
        every { crypto.repoId } returns repoId
        every { backendManager.backend } returns backend
        coEvery { backend.save(capture(blobHandle)) } returns outputStream1

        blobCreator.createNewBlob(chunk1)
        // check that file content hash matches snapshot hash
        val messageDigest = MessageDigest.getInstance("SHA-256")
        assertEquals(
            messageDigest.digest(outputStream1.toByteArray()).toHexString(),
            blobHandle.captured.name,
        )

        // use same BlobCreator to create another blob, because we re-use a single buffer
        // and need to check clearing that does work as expected
        coEvery { backend.save(capture(blobHandle)) } returns outputStream2
        blobCreator.createNewBlob(chunk2)
        // check that file content hash matches snapshot hash
        assertEquals(
            messageDigest.digest(outputStream2.toByteArray()).toHexString(),
            blobHandle.captured.name,
        )
    }
}
