/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import com.google.protobuf.ByteString
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.proto.SnapshotKt.blob
import com.stevesoltys.seedvault.proto.copy
import com.stevesoltys.seedvault.transport.TransportTest
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.FileInfo
import org.calyxos.seedvault.core.backends.TopLevelFolder
import org.calyxos.seedvault.core.toHexString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

internal class CheckerTest : TransportTest() {

    private val backendManager: BackendManager = mockk()
    private val snapshotManager: SnapshotManager = mockk()
    private val loader: Loader = mockk()
    private val blobCache: BlobCache = mockk()
    private val nm: BackupNotificationManager = mockk()
    private val backend: Backend = mockk()

    private val checker = Checker(crypto, backendManager, snapshotManager, loader, blobCache, nm)
    private val folder = TopLevelFolder(repoId)

    private val snapshotHandle1 =
        AppBackupFileType.Snapshot(repoId, getRandomByteArray(32).toHexString())
    private val snapshotHandle2 =
        AppBackupFileType.Snapshot(repoId, getRandomByteArray(32).toHexString())

    @Test
    fun `getBackupSize returns 0 for no data`() = runBlocking {
        expectLoadingSnapshots(emptyMap())

        assertEquals(0, checker.getBackupSize())
    }

    @Test
    fun `getBackupSize returns null on error`() = runBlocking {
        every { crypto.repoId } returns repoId
        every { backendManager.backend } returns backend
        coEvery {
            backend.list(folder, AppBackupFileType.Snapshot::class, callback = captureLambda())
        } throws IOException()

        assertNull(checker.getBackupSize())
    }

    @Test
    fun `getBackupSize returns size without double counting blobs`() = runBlocking {
        val snapshotMap = mapOf(
            snapshotHandle1 to snapshot.copy { token = 1 },
            snapshotHandle2 to snapshot.copy { token = 2 },
        )
        val expectedSize = blob1.length.toLong() + blob2.length.toLong()
        expectLoadingSnapshots(snapshotMap)

        assertEquals(expectedSize, checker.getBackupSize())
    }

    @Test
    fun `getBackupSize returns size without under-counting blobs with same chunkId`() =
        runBlocking {
            val apk = apk.copy {
                splits.clear()
                splits.add(baseSplit.copy {
                    this.chunkIds.clear()
                    chunkIds.add(ByteString.fromHex(chunkId1))
                })
            }
            val snapshot = snapshot.copy {
                apps[packageName] = app.copy { this.apk = apk }
                blobs.clear()
            }
            val snapshotMap = mapOf(
                snapshotHandle1 to snapshot.copy {
                    token = 1
                    blobs[chunkId1] = blob1
                },
                snapshotHandle2 to snapshot.copy {
                    token = 2
                    blobs[chunkId1] = blob2
                },
            )
            val expectedSize = blob1.length.toLong() + blob2.length.toLong()
            expectLoadingSnapshots(snapshotMap)

            assertEquals(expectedSize, checker.getBackupSize())
        }

    @Test
    fun `check works even with no backup data`() = runBlocking {
        expectLoadingSnapshots(emptyMap())

        every { backendManager.requiresNetwork } returns Random.nextBoolean()
        every { nm.onCheckFinishedWithError(0, 0) } just Runs

        assertNull(checker.checkerResult)
        checker.check(100)
        assertInstanceOf(CheckerResult.Error::class.java, checker.checkerResult)
        val result = checker.checkerResult as CheckerResult.Error
        assertEquals(emptyList<Snapshot>(), result.snapshots)
        assertEquals(0, result.existingSnapshots)
    }

    @Test
    fun `check returns error when loading snapshots fails`() = runBlocking {
        every { crypto.repoId } returns repoId
        every { backendManager.backend } returns backend
        coEvery {
            backend.list(folder, AppBackupFileType.Snapshot::class, callback = captureLambda())
        } throws IOException("foo")
        every { nm.onCheckFinishedWithError(0, 0) } just Runs

        assertNull(checker.checkerResult)
        checker.check(100)
        // assert the right exception gets passed on in error result
        assertInstanceOf(CheckerResult.GeneralError::class.java, checker.checkerResult)
        val result = checker.checkerResult as CheckerResult.GeneralError
        assertInstanceOf(IOException::class.java, result.e)
        assertEquals("foo", result.e.message)
    }

    @Test
    fun `check raises error for wrong chunkIDs`() = runBlocking {
        val snapshotMap = mapOf(
            snapshotHandle1 to snapshot.copy { token = 1 },
            snapshotHandle2 to snapshot.copy { token = 2 },
        )
        expectLoadingSnapshots(snapshotMap)
        every { backendManager.requiresNetwork } returns Random.nextBoolean()

        val data = ByteArray(0)
        coEvery { loader.loadFile(blobHandle1, null) } returns ByteArrayInputStream(data)
        coEvery { loader.loadFile(blobHandle2, null) } returns ByteArrayInputStream(data)

        every { nm.onCheckFinishedWithError(any(), any()) } just Runs

        assertNull(checker.checkerResult)
        checker.check(100)
        assertInstanceOf(CheckerResult.Error::class.java, checker.checkerResult)
        val result = checker.checkerResult as CheckerResult.Error
        assertEquals(snapshotMap.values.toSet(), result.snapshots.toSet())
        assertEquals(snapshotMap.values.toSet(), result.badSnapshots.toSet())
        assertEquals(emptyList<Snapshot>(), result.goodSnapshots)
        assertEquals(snapshotMap.size, result.existingSnapshots)
        val errorPairs = setOf(ChunkIdBlobPair(chunkId1, blob1), ChunkIdBlobPair(chunkId2, blob2))
        assertEquals(errorPairs, result.errorChunkIdBlobPairs)
    }

    @Test
    fun `check records hash error from loader`() = runBlocking {
        // chunkId is "real"
        val data1 = getRandomByteArray()
        val chunkId1 = MessageDigest.getInstance("SHA-256").digest(data1).toHexString()
        // each snapshot gets a different blob
        val apk1 = apk.copy {
            splits.clear()
            splits.add(baseSplit.copy {
                this.chunkIds.clear()
                chunkIds.add(ByteString.fromHex(chunkId1))
            })
        }
        val apk2 = apk.copy {
            splits.clear()
            splits.add(baseSplit.copy {
                this.chunkIds.clear()
                chunkIds.add(ByteString.fromHex(chunkId2))
            })
        }
        val snapshotMap = mapOf(
            snapshotHandle1 to snapshot.copy {
                token = 1
                apps[packageName] = app.copy { this.apk = apk1 }
                blobs.clear()
                blobs[chunkId1] = blob1
            },
            snapshotHandle2 to snapshot.copy {
                token = 2
                apps[packageName] = app.copy { this.apk = apk2 }
            },
        )
        expectLoadingSnapshots(snapshotMap)
        every { backendManager.requiresNetwork } returns Random.nextBoolean()

        coEvery { loader.loadFile(blobHandle1, null) } returns ByteArrayInputStream(data1)
        coEvery { loader.loadFile(blobHandle2, null) } throws HashMismatchException()

        every { blobCache.doNotUseBlob(ByteString.fromHex(blobHandle2.name)) } just Runs
        every { nm.onCheckFinishedWithError(any(), any()) } just Runs

        assertNull(checker.checkerResult)
        checker.check(100)
        assertInstanceOf(CheckerResult.Error::class.java, checker.checkerResult)
        val result = checker.checkerResult as CheckerResult.Error
        assertEquals(snapshotMap.values.toSet(), result.snapshots.toSet())
        assertEquals(listOf(snapshotMap[snapshotHandle1]), result.goodSnapshots)
        assertEquals(listOf(snapshotMap[snapshotHandle2]), result.badSnapshots)
        assertEquals(snapshotMap.size, result.existingSnapshots)
        val errorPairs = setOf(ChunkIdBlobPair(chunkId2, blob2))
        assertEquals(errorPairs, result.errorChunkIdBlobPairs)
    }

    @Test
    fun `check with 100 percent works`() = runBlocking {
        // get "real" data for blobs
        val messageDigest = MessageDigest.getInstance("SHA-256")
        val data1 = getRandomByteArray()
        val data2 = getRandomByteArray()
        val chunkId1 = messageDigest.digest(data1).toHexString()
        val chunkId2 = messageDigest.digest(data2).toHexString()
        val apk = apk.copy {
            splits.clear()
            splits.add(baseSplit.copy {
                this.chunkIds.clear()
                chunkIds.add(ByteString.fromHex(chunkId1))
            })
            splits.add(apkSplit.copy {
                this.chunkIds.clear()
                chunkIds.add(ByteString.fromHex(chunkId2))
            })
        }
        val snapshot = snapshot.copy {
            apps[packageName] = app.copy { this.apk = apk }
            blobs[chunkId1] = blob1
            blobs[chunkId2] = blob2
        }
        val snapshotMap = mapOf(
            snapshotHandle1 to snapshot.copy { token = 1 },
            snapshotHandle2 to snapshot.copy { token = 2 },
        )
        val expectedSize = blob1.length.toLong() + blob2.length.toLong()

        expectLoadingSnapshots(snapshotMap)
        every { backendManager.requiresNetwork } returns Random.nextBoolean()

        coEvery { loader.loadFile(blobHandle1, null) } returns ByteArrayInputStream(data1)
        coEvery { loader.loadFile(blobHandle2, null) } returns ByteArrayInputStream(data2)

        every { nm.onCheckComplete(expectedSize, any()) } just Runs

        assertNull(checker.checkerResult)
        checker.check(100)
        assertInstanceOf(CheckerResult.Success::class.java, checker.checkerResult)
        val result = checker.checkerResult as CheckerResult.Success
        assertEquals(snapshotMap.values.toSet(), result.snapshots.toSet())
        assertEquals(100, result.percent)
        assertEquals(expectedSize, result.size)

        verify {
            nm.onCheckComplete(any(), any())
        }
    }

    @Test
    fun `check prefers app data over APKs`() = runBlocking {
        val appDataBlob = blob {
            id = ByteString.copyFrom(Random.nextBytes(32))
            length = Random.nextInt(1, Int.MAX_VALUE)
            uncompressedLength = Random.nextInt(1, Int.MAX_VALUE)
        }
        val appDataBlobHandle1 = AppBackupFileType.Blob(repoId, appDataBlob.id.hexFromProto())
        val appDataChunkId = Random.nextBytes(32).toHexString()

        val snapshotMap = mapOf(
            snapshotHandle1 to snapshot.copy {
                token = 1
                apps[packageName] = app.copy { chunkIds.add(ByteString.fromHex(appDataChunkId)) }
                blobs[appDataChunkId] = appDataBlob
            },
        )
        expectLoadingSnapshots(snapshotMap)
        every { backendManager.requiresNetwork } returns Random.nextBoolean()

        // only loading app data, not other blobs
        coEvery { loader.loadFile(appDataBlobHandle1, null) } throws SecurityException()

        println("appDataBlob.length = $appDataBlob.length")
        every { nm.onCheckFinishedWithError(appDataBlob.length.toLong(), any()) } just Runs

        assertNull(checker.checkerResult)
        checker.check(1) // 1% to minimize chance of selecting a non-app random blob
        assertInstanceOf(CheckerResult.Error::class.java, checker.checkerResult)
        val result = checker.checkerResult as CheckerResult.Error
        assertEquals(snapshotMap.values.toSet(), result.snapshots.toSet())
        assertEquals(snapshotMap.values.toSet(), result.badSnapshots.toSet())
        assertEquals(snapshotMap.size, result.existingSnapshots)
        val errorPairs = setOf(ChunkIdBlobPair(appDataChunkId, appDataBlob))
        assertEquals(errorPairs, result.errorChunkIdBlobPairs)

        coVerify(exactly = 0) {
            loader.loadFile(blobHandle1, null)
            loader.loadFile(blobHandle2, null)
        }
    }

    @Test
    fun `check doesn't skip broken blobs that have a fix with same chunkID`() = runBlocking {
        // get "real" data for blob2
        val messageDigest = MessageDigest.getInstance("SHA-256")
        val data1 = getRandomByteArray() // broken blob
        val data2 = getRandomByteArray() // data2 matches chunkId
        val chunkId = messageDigest.digest(data2).toHexString()
        val apk = apk.copy {
            splits.clear()
            splits.add(baseSplit.copy {
                this.chunkIds.clear()
                chunkIds.add(ByteString.fromHex(chunkId))
            })
        }
        val snapshot = snapshot.copy {
            apps[packageName] = app.copy { this.apk = apk }
            blobs.clear()
        }
        val snapshotMap = mapOf(
            snapshotHandle1 to snapshot.copy {
                token = 1
                blobs[chunkId] = blob1 // snapshot1 has broken blob for chunkId
            },
            snapshotHandle2 to snapshot.copy {
                token = 2
                blobs[chunkId] = blob2 // snapshot2 has fixed blob for chunkId
            },
        )

        expectLoadingSnapshots(snapshotMap)
        every { backendManager.requiresNetwork } returns Random.nextBoolean()

        coEvery { loader.loadFile(blobHandle1, null) } returns ByteArrayInputStream(data1)
        coEvery { loader.loadFile(blobHandle2, null) } returns ByteArrayInputStream(data2)

        every { nm.onCheckFinishedWithError(any(), any()) } just Runs

        assertNull(checker.checkerResult)
        checker.check(100)
        assertInstanceOf(CheckerResult.Error::class.java, checker.checkerResult)
        val result = checker.checkerResult as CheckerResult.Error
        assertEquals(snapshotMap.values.toSet(), result.snapshots.toSet())
        assertEquals(setOf(snapshotMap[snapshotHandle2]), result.goodSnapshots.toSet())
        assertEquals(setOf(snapshotMap[snapshotHandle1]), result.badSnapshots.toSet())
        assertEquals(snapshotMap.size, result.existingSnapshots)
        val errorPairs = setOf(ChunkIdBlobPair(chunkId, blob1))
        assertEquals(errorPairs, result.errorChunkIdBlobPairs)
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
        coEvery {
            snapshotManager.onSnapshotsLoaded(snapshots.keys.toList())
        } returns snapshots.values.toList()
    }
}
