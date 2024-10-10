/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.transport.TransportTest
import io.mockk.Runs
import io.mockk.andThenJust
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.AppBackupFileType.Blob
import org.calyxos.seedvault.core.backends.AppBackupFileType.Snapshot
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.FileInfo
import org.calyxos.seedvault.core.backends.TopLevelFolder
import org.calyxos.seedvault.core.toHexString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import kotlin.random.Random

internal class AppBackupManagerTest : TransportTest() {

    private val blobCache: BlobCache = mockk()
    private val backendManager: BackendManager = mockk()
    private val backend: Backend = mockk()
    private val snapshotManager: SnapshotManager = mockk()
    private val snapshotCreatorFactory: SnapshotCreatorFactory = mockk()
    private val appBackupManager = AppBackupManager(
        crypto = crypto,
        blobCache = blobCache,
        backendManager = backendManager,
        settingsManager = settingsManager,
        snapshotManager = snapshotManager,
        snapshotCreatorFactory = snapshotCreatorFactory,
    )

    private val snapshotCreator: SnapshotCreator = mockk()

    @Test
    fun `beforeBackup passes exception on`() = runBlocking {
        every { backendManager.backend } returns backend
        every { crypto.repoId } returns repoId
        coEvery {
            backend.list(TopLevelFolder(repoId), Blob::class, Snapshot::class, callback = any())
        } throws IOException()

        assertThrows<IOException> {
            appBackupManager.beforeBackup()
        }
        Unit
    }

    @Test
    fun `beforeBackup passes on blobs, snapshots and initializes SnapshotCreator`() = runBlocking {
        val snapshotHandle = Snapshot(repoId, "foo bar")
        val snapshotInfo = FileInfo(snapshotHandle, Random.nextLong())
        val top = TopLevelFolder(repoId)
        every { backendManager.backend } returns backend
        every { crypto.repoId } returns repoId
        coEvery {
            backend.list(top, Blob::class, Snapshot::class, callback = captureLambda())
        } answers {
            lambda<(FileInfo) -> Unit>().captured.invoke(fileInfo1)
            lambda<(FileInfo) -> Unit>().captured.invoke(fileInfo2)
            lambda<(FileInfo) -> Unit>().captured.invoke(snapshotInfo)
        }
        coEvery {
            snapshotManager.onSnapshotsLoaded(listOf(snapshotHandle))
        } returns listOf(snapshot)
        every { blobCache.populateCache(listOf(fileInfo1, fileInfo2), listOf(snapshot)) } just Runs
        every { snapshotCreatorFactory.createSnapshotCreator() } returns mockk()

        appBackupManager.beforeBackup()

        coVerify {
            snapshotManager.onSnapshotsLoaded(listOf(snapshotHandle))
            blobCache.populateCache(listOf(fileInfo1, fileInfo2), listOf(snapshot))
            snapshotCreatorFactory.createSnapshotCreator()
        }
    }

    @Test
    fun `afterBackupFinished doesn't save snapshot on failure`() = runBlocking {
        every { blobCache.clear() } just Runs

        assertNull(appBackupManager.afterBackupFinished(false))
    }

    @Test
    fun `afterBackupFinished doesn't throw exception`() = runBlocking {
        // need to run beforeBackup to get a snapshotCreator
        minimalBeforeBackup()

        every { blobCache.clear() } just Runs
        every { snapshotCreator.finalizeSnapshot() } returns snapshot
        coEvery { snapshotManager.saveSnapshot(snapshot) } throws IOException()

        assertNull(appBackupManager.afterBackupFinished(true))
    }

    @Test
    fun `afterBackupFinished retries saving snapshot`() = runBlocking {
        // need to run beforeBackup to get a snapshotCreator
        minimalBeforeBackup()

        every { blobCache.clear() } just Runs
        every { snapshotCreator.finalizeSnapshot() } returns snapshot
        coEvery {
            snapshotManager.saveSnapshot(snapshot) // works only at third attempt
        } throws IOException() andThenThrows IOException() andThenJust Runs
        every { settingsManager.onSuccessfulBackupCompleted(snapshot.token) } just Runs
        every { blobCache.clearLocalCache() } just Runs

        assertEquals(snapshot, appBackupManager.afterBackupFinished(true))
    }

    @Test
    fun `recycleBackupRepo doesn't do anything if repoId is current`() = runBlocking {
        every { crypto.repoId } returns repoId
        appBackupManager.recycleBackupRepo(repoId)
    }

    @Test
    fun `recycleBackupRepo renames different repo`() = runBlocking {
        val oldRepoId = getRandomByteArray(32).toHexString()

        every { crypto.repoId } returns repoId
        every { backendManager.backend } returns backend
        coEvery { backend.rename(TopLevelFolder(oldRepoId), TopLevelFolder(repoId)) } just Runs

        appBackupManager.recycleBackupRepo(oldRepoId)

        coVerify {
            backend.rename(TopLevelFolder(oldRepoId), TopLevelFolder(repoId))
        }
    }

    @Test
    fun `removeBackupRepo deletes repo and local cache`() = runBlocking {
        every { blobCache.clearLocalCache() } just Runs
        every { crypto.repoId } returns repoId
        coEvery { backendManager.backend.remove(TopLevelFolder(repoId)) } just Runs

        appBackupManager.removeBackupRepo()

        coVerify {
            blobCache.clearLocalCache()
            backendManager.backend.remove(TopLevelFolder(repoId))
        }
    }

    private suspend fun minimalBeforeBackup() {
        every { backendManager.backend } returns backend
        every { crypto.repoId } returns repoId
        coEvery {
            backend.list(any(), Blob::class, Snapshot::class, callback = any())
        } just Runs
        coEvery {
            snapshotManager.onSnapshotsLoaded(emptyList())
        } returns emptyList()
        every { blobCache.populateCache(emptyList(), emptyList()) } just Runs
        every { snapshotCreatorFactory.createSnapshotCreator() } returns snapshotCreator

        appBackupManager.beforeBackup()
    }
}
