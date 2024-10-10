/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.proto.copy
import com.stevesoltys.seedvault.transport.TransportTest
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.FileInfo
import org.calyxos.seedvault.core.backends.TopLevelFolder
import org.calyxos.seedvault.core.toHexString
import org.junit.jupiter.api.Test
import java.security.GeneralSecurityException
import java.time.LocalDateTime
import java.time.ZoneOffset.UTC
import java.util.concurrent.TimeUnit
import kotlin.random.Random

internal class PrunerTest : TransportTest() {

    private val backendManager: BackendManager = mockk()
    private val snapshotManager: SnapshotManager = mockk()
    private val backend: Backend = mockk()

    private val pruner = Pruner(crypto, backendManager, snapshotManager)
    private val folder = TopLevelFolder(repoId)

    private val snapshotHandle1 =
        AppBackupFileType.Snapshot(repoId, getRandomByteArray(32).toHexString())
    private val snapshotHandle2 =
        AppBackupFileType.Snapshot(repoId, getRandomByteArray(32).toHexString())
    private val snapshotHandle3 =
        AppBackupFileType.Snapshot(repoId, getRandomByteArray(32).toHexString())
    private val snapshotHandle4 =
        AppBackupFileType.Snapshot(repoId, getRandomByteArray(32).toHexString())
    private val snapshotHandle5 =
        AppBackupFileType.Snapshot(repoId, getRandomByteArray(32).toHexString())

    @Test
    fun `single snapshot gets left alone`() = runBlocking {
        val snapshotMap = mapOf(
            snapshotHandle1 to snapshot.copy { token = System.currentTimeMillis() },
        )
        expectLoadingSnapshots(snapshotMap)

        // we only find blobs that are in snapshots
        expectLoadingBlobs(snapshot.blobsMap.values.map { it.id.hexFromProto() })

        pruner.removeOldSnapshotsAndPruneUnusedBlobs()
    }

    @Test
    fun `corrupted snapshot gets removed`() = runBlocking {
        val snapshot = snapshot.copy { token = System.currentTimeMillis() }
        every { crypto.repoId } returns repoId
        every { backendManager.backend } returns backend
        coEvery {
            backend.list(folder, AppBackupFileType.Snapshot::class, callback = captureLambda())
        } answers {
            val fileInfo = FileInfo(snapshotHandle1, Random.nextLong(Long.MAX_VALUE))
            lambda<(FileInfo) -> Unit>().captured.invoke(fileInfo)
        }
        coEvery { snapshotManager.loadSnapshot(snapshotHandle1) } throws GeneralSecurityException()
        coEvery { snapshotManager.removeSnapshot(snapshotHandle1) } just Runs

        // we only find blobs that are in snapshots
        val blobIds = snapshot.blobsMap.values.map { it.id.hexFromProto() }
        expectLoadingBlobs(blobIds)

        // but since all snapshots got removed, we remove all blobs :(
        blobIds.forEach {
            coEvery { backend.remove(AppBackupFileType.Blob(repoId, it)) } just Runs
        }

        pruner.removeOldSnapshotsAndPruneUnusedBlobs()
    }

    @Test
    fun `three snapshots from same day don't remove blobs`() = runBlocking {
        val snapshotMap = mapOf(
            snapshotHandle1 to snapshot.copy { token = System.currentTimeMillis() },
            snapshotHandle2 to snapshot.copy { token = System.currentTimeMillis() - 1 },
            snapshotHandle3 to snapshot.copy { token = System.currentTimeMillis() - 2 },
        )
        expectLoadingSnapshots(snapshotMap)

        // we only find blobs that are in snapshots
        expectLoadingBlobs(snapshot.blobsMap.values.map { it.id.hexFromProto() })

        pruner.removeOldSnapshotsAndPruneUnusedBlobs()
    }

    @Test
    fun `four snapshots from same day only remove oldest`() = runBlocking {
        val now = System.currentTimeMillis()
        val snapshotMap = mapOf(
            snapshotHandle1 to snapshot.copy { token = now },
            snapshotHandle2 to snapshot.copy { token = now - 1 },
            snapshotHandle3 to snapshot.copy { token = now - 2 },
            snapshotHandle4 to snapshot.copy { token = now - 3 },
        )
        expectLoadingSnapshots(snapshotMap)

        // we only find blobs that are in snapshots
        expectLoadingBlobs(snapshot.blobsMap.values.map { it.id.hexFromProto() })

        coEvery { snapshotManager.removeSnapshot(snapshotHandle4) } just Runs

        pruner.removeOldSnapshotsAndPruneUnusedBlobs()

        coVerify { snapshotManager.removeSnapshot(snapshotHandle4) }
    }

    @Test
    fun `three snapshots from different days remove blobs`() = runBlocking {
        val now = System.currentTimeMillis()
        val snapshotMap = mapOf(
            snapshotHandle1 to snapshot.copy { token = now },
            snapshotHandle2 to snapshot.copy { token = now - TimeUnit.DAYS.toMillis(1) },
            snapshotHandle3 to snapshot.copy { token = now - TimeUnit.DAYS.toMillis(2) },
        )
        expectLoadingSnapshots(snapshotMap)

        // we only find blobs that are in snapshots
        val blob1 = getRandomByteArray(32).toHexString()
        val blob2 = getRandomByteArray(32).toHexString()
        val blobs = snapshot.blobsMap.values.map { it.id.hexFromProto() } + listOf(blob1, blob2)
        expectLoadingBlobs(blobs)

        // now extra blobs will get removed
        coEvery { backend.remove(AppBackupFileType.Blob(repoId, blob1)) } just Runs
        coEvery { backend.remove(AppBackupFileType.Blob(repoId, blob2)) } just Runs

        pruner.removeOldSnapshotsAndPruneUnusedBlobs()

        coVerify {
            backend.remove(AppBackupFileType.Blob(repoId, blob1))
            backend.remove(AppBackupFileType.Blob(repoId, blob2))
        }
    }

    @Test
    fun `three snapshots from last weeks are still kept`() = runBlocking {
        val now = System.currentTimeMillis()
        val snapshotMap = mapOf(
            snapshotHandle1 to snapshot.copy { token = now },
            snapshotHandle2 to snapshot.copy { token = now - TimeUnit.DAYS.toMillis(7) },
            snapshotHandle3 to snapshot.copy { token = now - TimeUnit.DAYS.toMillis(14) },
        )
        expectLoadingSnapshots(snapshotMap)
        expectLoadingBlobs(emptyList())

        pruner.removeOldSnapshotsAndPruneUnusedBlobs()
    }

    @Test
    fun `five snapshots with two from last week, removes only one oldest`() = runBlocking {
        val snapshotMap = mapOf(
            snapshotHandle1 to snapshot.copy { // this week
                token = LocalDateTime.of(2024, 9, 18, 23, 0).toMillis()
            },
            snapshotHandle2 to snapshot.copy { // this week, different day
                token = LocalDateTime.of(2024, 9, 17, 20, 0).toMillis()
            },
            snapshotHandle3 to snapshot.copy { // this week, different day
                token = LocalDateTime.of(2024, 9, 16, 12, 0).toMillis()
            },
            snapshotHandle4 to snapshot.copy { // last week
                token = LocalDateTime.of(2024, 9, 13, 23, 0).toMillis()
            },
            snapshotHandle5 to snapshot.copy { // last week, different day
                token = LocalDateTime.of(2024, 9, 12, 23, 0).toMillis()
            },
        )
        expectLoadingSnapshots(snapshotMap)
        expectLoadingBlobs(emptyList())

        coEvery { snapshotManager.removeSnapshot(snapshotHandle5) } just Runs

        pruner.removeOldSnapshotsAndPruneUnusedBlobs()

        coVerify {
            snapshotManager.removeSnapshot(snapshotHandle5)
        }
    }

    @Test
    fun `five snapshots with one from last week, removes oldest from this week`() = runBlocking {
        val snapshotMap = mapOf(
            snapshotHandle1 to snapshot.copy { // this week
                token = LocalDateTime.of(2024, 9, 19, 23, 0).toMillis()
            },
            snapshotHandle2 to snapshot.copy { // this week, different day
                token = LocalDateTime.of(2024, 9, 18, 20, 0).toMillis()
            },
            snapshotHandle3 to snapshot.copy { // this week, different day
                token = LocalDateTime.of(2024, 9, 17, 12, 0).toMillis()
            },
            snapshotHandle4 to snapshot.copy { // this week, different day
                token = LocalDateTime.of(2024, 9, 16, 23, 0).toMillis()
            },
            snapshotHandle5 to snapshot.copy { // last week
                token = LocalDateTime.of(2024, 9, 12, 23, 0).toMillis()
            },
        )
        expectLoadingSnapshots(snapshotMap)
        expectLoadingBlobs(emptyList())

        coEvery { snapshotManager.removeSnapshot(snapshotHandle4) } just Runs

        pruner.removeOldSnapshotsAndPruneUnusedBlobs()

        coVerify {
            snapshotManager.removeSnapshot(snapshotHandle4)
        }
    }

    @Test
    fun `five snapshots with two on the same day, removes oldest from same day`() = runBlocking {
        val snapshotMap = mapOf(
            snapshotHandle1 to snapshot.copy { // this week
                token = LocalDateTime.of(2024, 9, 19, 23, 0).toMillis()
            },
            snapshotHandle2 to snapshot.copy { // this week, same day
                token = LocalDateTime.of(2024, 9, 19, 20, 0).toMillis()
            },
            snapshotHandle3 to snapshot.copy { // this week, different day
                token = LocalDateTime.of(2024, 9, 17, 12, 0).toMillis()
            },
            snapshotHandle4 to snapshot.copy { // this week, different day
                token = LocalDateTime.of(2024, 9, 16, 23, 0).toMillis()
            },
            snapshotHandle5 to snapshot.copy { // this week, same day
                token = LocalDateTime.of(2024, 9, 16, 22, 0).toMillis()
            },
        )
        expectLoadingSnapshots(snapshotMap)
        expectLoadingBlobs(emptyList())

        coEvery { snapshotManager.removeSnapshot(snapshotHandle2) } just Runs
        coEvery { snapshotManager.removeSnapshot(snapshotHandle5) } just Runs

        pruner.removeOldSnapshotsAndPruneUnusedBlobs()

        coVerify {
            snapshotManager.removeSnapshot(snapshotHandle2)
            snapshotManager.removeSnapshot(snapshotHandle5)
        }
    }

    @Test
    fun `five snapshots from past weeks, removes two oldest weeks`() = runBlocking {
        val snapshotMap = mapOf(
            snapshotHandle1 to snapshot.copy {
                token = LocalDateTime.of(2024, 9, 3, 23, 0).toMillis()
            },
            snapshotHandle2 to snapshot.copy {
                token = LocalDateTime.of(2024, 9, 10, 20, 0).toMillis()
            },
            snapshotHandle3 to snapshot.copy {
                token = LocalDateTime.of(2024, 9, 17, 12, 0).toMillis()
            },
            snapshotHandle4 to snapshot.copy {
                token = LocalDateTime.of(2024, 9, 24, 23, 0).toMillis()
            },
            snapshotHandle5 to snapshot.copy {
                token = LocalDateTime.of(2024, 10, 1, 22, 0).toMillis()
            },
        )
        expectLoadingSnapshots(snapshotMap)
        expectLoadingBlobs(emptyList())

        coEvery { snapshotManager.removeSnapshot(snapshotHandle1) } just Runs
        coEvery { snapshotManager.removeSnapshot(snapshotHandle2) } just Runs

        pruner.removeOldSnapshotsAndPruneUnusedBlobs()

        coVerify {
            snapshotManager.removeSnapshot(snapshotHandle1)
            snapshotManager.removeSnapshot(snapshotHandle2)
        }
    }

    private suspend fun expectLoadingSnapshots(
        snapshots: Map<AppBackupFileType.Snapshot, Snapshot>,
    ) {
        every { crypto.repoId } returns repoId
        every { backendManager.backend } returns backend
        coEvery {
            backend.list(folder, AppBackupFileType.Snapshot::class, callback = captureLambda())
        } answers {
            snapshots.keys.forEach {
                val fileInfo = FileInfo(it, Random.nextLong(Long.MAX_VALUE))
                lambda<(FileInfo) -> Unit>().captured.invoke(fileInfo)
            }
        }
        snapshots.forEach { (handle, snapshot) ->
            coEvery { snapshotManager.loadSnapshot(handle) } returns snapshot
        }
    }

    private suspend fun expectLoadingBlobs(blobIds: List<String>) {
        coEvery {
            backend.list(folder, AppBackupFileType.Blob::class, callback = captureLambda())
        } answers {
            blobIds.forEach {
                val fileInfo = FileInfo(
                    fileHandle = AppBackupFileType.Blob(repoId, it),
                    size = Random.nextLong(Long.MAX_VALUE),
                )
                lambda<(FileInfo) -> Unit>().captured.invoke(fileInfo)
            }
        }
    }

    private fun LocalDateTime.toMillis(): Long {
        return toInstant(UTC).toEpochMilli()
    }

}
