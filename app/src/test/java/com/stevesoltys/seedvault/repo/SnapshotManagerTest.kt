/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.proto.snapshot
import com.stevesoltys.seedvault.transport.TransportTest
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.toByteArrayFromHex
import org.calyxos.seedvault.core.toHexString
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.MessageDigest
import kotlin.random.Random

internal class SnapshotManagerTest : TransportTest() {

    private val backendManager: BackendManager = mockk()
    private val backend: Backend = mockk()
    private val loader: Loader = mockk()

    private val messageDigest = MessageDigest.getInstance("SHA-256")
    private val ad = Random.nextBytes(1)
    private val passThroughOutputStream = slot<OutputStream>()
    private val passThroughInputStream = slot<InputStream>()
    private val snapshotHandle = slot<AppBackupFileType.Snapshot>()

    init {
        every { backendManager.backend } returns backend
    }

    private fun getSnapshotFolder(tmpDir: Path, hash: String): File {
        val repoFolder = File(tmpDir.toString(), repoId)
        return File(repoFolder, hash)
    }

    @Test
    fun `test onSnapshotsLoaded sets latestSnapshot`(@TempDir tmpDir: Path) = runBlocking {
        val snapshotManager = getSnapshotManager(File(tmpDir.toString()))
        val snapshotData1 = snapshot { token = 20 }.toByteArray()
        val snapshotData2 = snapshot { token = 10 }.toByteArray()
        val inputStream1 = ByteArrayInputStream(snapshotData1)
        val inputStream2 = ByteArrayInputStream(snapshotData2)

        val snapshotHandle1 = AppBackupFileType.Snapshot(repoId, chunkId1)
        val snapshotHandle2 = AppBackupFileType.Snapshot(repoId, chunkId2)

        every { crypto.repoId } returns repoId
        coEvery { loader.loadFile(snapshotHandle1, any()) } returns inputStream1
        coEvery { loader.loadFile(snapshotHandle2, any()) } returns inputStream2
        snapshotManager.onSnapshotsLoaded(listOf(snapshotHandle1, snapshotHandle2))

        // snapshot with largest token is latest
        assertEquals(20, snapshotManager.latestSnapshot?.token)

        // when switching storage and now not having any snapshots, we must clear latestSnapshot
        snapshotManager.onSnapshotsLoaded(emptyList())
        assertNull(snapshotManager.latestSnapshot)
    }

    @Test
    fun `saveSnapshot saves to local cache`(@TempDir tmpDir: Path) = runBlocking {
        val snapshotManager = getSnapshotManager(File(tmpDir.toString()))
        val snapshotHandle = AppBackupFileType.Snapshot(repoId, chunkId1)
        val outputStream = ByteArrayOutputStream()

        every { crypto.getAdForVersion() } returns ad
        every { crypto.newEncryptingStream(capture(passThroughOutputStream), ad) } answers {
            passThroughOutputStream.captured // not really encrypting here
        }
        every { crypto.sha256(any()) } returns chunkId1.toByteArrayFromHex()
        every { crypto.repoId } returns repoId
        coEvery { backend.save(snapshotHandle) } returns outputStream

        snapshotManager.saveSnapshot(snapshot)

        val snapshotFile = getSnapshotFolder(tmpDir, snapshotHandle.name)
        assertTrue(snapshotFile.isFile)
        assertTrue(outputStream.size() > 0)
        val cachedBytes = snapshotFile.inputStream().use { it.readAllBytes() }
        assertArrayEquals(outputStream.toByteArray(), cachedBytes)
    }

    @Test
    fun `snapshot loads from cache without backend`(@TempDir tmpDir: Path) = runBlocking {
        val snapshotManager = getSnapshotManager(File(tmpDir.toString()))
        val snapshot = snapshot { token = 1337 }
        val snapshotData = snapshot.toByteArray()
        val inputStream = ByteArrayInputStream(snapshotData)
        val snapshotHandle = AppBackupFileType.Snapshot(repoId, chunkId1)

        // create cached file
        val file = getSnapshotFolder(tmpDir, snapshotHandle.name)
        file.parentFile?.mkdirs()
        file.outputStream().use { it.write(snapshotData) }

        every { crypto.repoId } returns repoId
        coEvery { loader.loadFile(file, snapshotHandle.hash) } returns inputStream

        assertEquals(listOf(snapshot), snapshotManager.onSnapshotsLoaded(listOf(snapshotHandle)))

        coVerify(exactly = 0) { // did not load from backend
            loader.loadFile(snapshotHandle, any())
        }

        // now load all snapshots from cache
        inputStream.reset()
        assertEquals(listOf(snapshot), snapshotManager.loadCachedSnapshots())
    }

    @Test
    fun `snapshot corrupted on cache falls back to backend`(@TempDir tmpDir: Path) = runBlocking {
        val snapshotManager = getSnapshotManager(File(tmpDir.toString()))
        val snapshot = snapshot { token = 1337 }
        val snapshotData = snapshot.toByteArray()
        val inputStream = ByteArrayInputStream(snapshotData)
        val snapshotHandle = AppBackupFileType.Snapshot(repoId, chunkId1)
        val file = getSnapshotFolder(tmpDir, snapshotHandle.name)

        every { crypto.repoId } returns repoId
        coEvery { loader.loadFile(file, snapshotHandle.hash) } throws GeneralSecurityException()
        coEvery { loader.loadFile(snapshotHandle, file) } returns inputStream

        assertEquals(listOf(snapshot), snapshotManager.onSnapshotsLoaded(listOf(snapshotHandle)))

        coVerify { // did load from backend
            loader.loadFile(snapshotHandle, file)
        }
    }

    @Test
    fun `failing to load a snapshot isn't fatal`(@TempDir tmpDir: Path) = runBlocking {
        val snapshotManager = getSnapshotManager(File(tmpDir.toString()))

        val snapshotData = snapshot { token = 42 }.toByteArray()
        val inputStream = ByteArrayInputStream(snapshotData)

        val snapshotHandle1 = AppBackupFileType.Snapshot(repoId, chunkId1)
        val snapshotHandle2 = AppBackupFileType.Snapshot(repoId, chunkId2)

        every { crypto.repoId } returns repoId
        coEvery { loader.loadFile(snapshotHandle1, any()) } returns inputStream
        coEvery { loader.loadFile(snapshotHandle2, any()) } throws IOException()
        snapshotManager.onSnapshotsLoaded(listOf(snapshotHandle1, snapshotHandle2))

        // still one snapshot survived and we didn't crash
        assertEquals(42, snapshotManager.latestSnapshot?.token)
    }

    @Test
    fun `test saving and loading`(@TempDir tmpDir: Path) = runBlocking {
        val loader = Loader(crypto, backendManager) // need a real loader
        val snapshotManager = getSnapshotManager(File(tmpDir.toString()), loader)

        val bytes = slot<ByteArray>()
        val outputStream = ByteArrayOutputStream()

        every { crypto.getAdForVersion() } returns ad
        every { crypto.newEncryptingStream(capture(passThroughOutputStream), ad) } answers {
            passThroughOutputStream.captured // not really encrypting here
        }
        every { crypto.repoId } returns repoId
        every { crypto.sha256(capture(bytes)) } answers {
            messageDigest.digest(bytes.captured)
        }
        coEvery { backend.save(capture(snapshotHandle)) } returns outputStream

        snapshotManager.saveSnapshot(snapshot)

        // check that file content hash matches snapshot hash
        assertEquals(
            messageDigest.digest(outputStream.toByteArray()).toHexString(),
            snapshotHandle.captured.hash,
        )

        assertTrue(outputStream.size() > 0)
        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        coEvery { backend.load(snapshotHandle.captured) } returns inputStream
        every {
            crypto.sha256(outputStream.toByteArray())
        } returns snapshotHandle.captured.hash.toByteArrayFromHex()
        every { crypto.newDecryptingStream(capture(passThroughInputStream), ad) } answers {
            passThroughInputStream.captured
        }

        snapshotManager.onSnapshotsLoaded(listOf(snapshotHandle.captured)).let { snapshots ->
            assertEquals(1, snapshots.size)
            assertEquals(snapshot, snapshots[0])
        }
    }

    @Test
    fun `remove snapshot removes from backend and cache`(@TempDir tmpDir: Path) = runBlocking {
        val snapshotManager = getSnapshotManager(File(tmpDir.toString()))

        val snapshotHandle = AppBackupFileType.Snapshot(repoId, chunkId1)
        val file = getSnapshotFolder(tmpDir, snapshotHandle.name)
        file.parentFile?.mkdirs()
        file.createNewFile()
        assertTrue(file.isFile)

        every { crypto.repoId } returns repoId
        coEvery { backend.remove(snapshotHandle) } just Runs

        snapshotManager.removeSnapshot(snapshotHandle)

        assertFalse(file.exists())
        coVerify { backend.remove(snapshotHandle) }
    }

    private fun getSnapshotManager(tmpFolder: File, loader: Loader = this.loader): SnapshotManager {
        return SnapshotManager(tmpFolder, crypto, loader, backendManager)
    }
}
