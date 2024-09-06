/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport

import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.transport.restore.Loader
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.FileInfo
import org.calyxos.seedvault.core.backends.TopLevelFolder
import org.calyxos.seedvault.core.toByteArrayFromHex
import org.calyxos.seedvault.core.toHexString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.random.Random

internal class SnapshotManagerTest : TransportTest() {

    private val backendManager: BackendManager = mockk()
    private val backend: Backend = mockk()

    private val loader = Loader(crypto, backendManager) // need a real loader
    private val snapshotManager = SnapshotManager(crypto, loader, backendManager)

    private val ad = Random.nextBytes(1)
    private val passThroughOutputStream = slot<OutputStream>()
    private val passThroughInputStream = slot<InputStream>()
    private val snapshotHandle = slot<AppBackupFileType.Snapshot>()

    @Test
    fun `test saving and loading`() = runBlocking {
        val outputStream = ByteArrayOutputStream()

        every { crypto.getAdForVersion() } returns ad
        every { crypto.newEncryptingStream(capture(passThroughOutputStream), ad) } answers {
            passThroughOutputStream.captured // not really encrypting here
        }
        every { crypto.repoId } returns repoId
        every { backendManager.backend } returns backend
        coEvery { backend.save(capture(snapshotHandle)) } returns outputStream

        snapshotManager.saveSnapshot(snapshot)

        // check that file content hash matches snapshot hash
        val messageDigest = MessageDigest.getInstance("SHA-256")
        assertEquals(
            messageDigest.digest(outputStream.toByteArray()).toHexString(),
            snapshotHandle.captured.hash,
        )

        val fileInfo = FileInfo(snapshotHandle.captured, Random.nextLong())
        assertTrue(outputStream.size() > 0)
        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        coEvery {
            backend.list(
                topLevelFolder = TopLevelFolder(repoId),
                AppBackupFileType.Snapshot::class,
                callback = captureLambda<(FileInfo) -> Unit>()
            )
        } answers {
            lambda<(FileInfo) -> Unit>().captured.invoke(fileInfo)
        }
        coEvery { backend.load(snapshotHandle.captured) } returns inputStream
        every {
            crypto.sha256(outputStream.toByteArray())
        } returns snapshotHandle.captured.hash.toByteArrayFromHex()
        every { crypto.newDecryptingStream(capture(passThroughInputStream), ad) } answers {
            passThroughInputStream.captured
        }

        var loadedSnapshot: Snapshot? = null
        snapshotManager.loadSnapshots { loadedSnapshot = it }
        assertEquals(snapshot, loadedSnapshot)
    }
}
