/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.restore

import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.RestoreDescription
import android.app.backup.RestoreDescription.NO_MORE_PACKAGES
import android.app.backup.RestoreDescription.TYPE_FULL_STREAM
import android.app.backup.RestoreDescription.TYPE_KEY_VALUE
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.coAssertThrows
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.MetadataReader
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.proto.copy
import com.stevesoltys.seedvault.repo.SnapshotManager
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
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import org.calyxos.seedvault.core.backends.saf.SafProperties
import org.calyxos.seedvault.core.toHexString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random

internal class RestoreCoordinatorTest : TransportTest() {

    private val notificationManager: BackupNotificationManager = mockk()
    private val backendManager: BackendManager = mockk()
    private val backend = mockk<Backend>()
    private val snapshotManager = mockk<SnapshotManager>()
    private val kv = mockk<KVRestore>()
    private val full = mockk<FullRestore>()
    private val metadataReader = mockk<MetadataReader>()

    private val restore = RestoreCoordinator(
        context = context,
        crypto = crypto,
        settingsManager = settingsManager,
        metadataManager = metadataManager,
        notificationManager = notificationManager,
        backendManager = backendManager,
        snapshotManager = snapshotManager,
        kv = kv,
        full = full,
        metadataReader = metadataReader,
    )

    private val restorableBackup = RestorableBackup(metadata, repoId, snapshot)
    private val inputStream = mockk<InputStream>()
    private val safStorage: SafProperties = mockk()
    private val packageInfo2 = PackageInfo().apply { packageName = "org.example2" }
    private val packageInfoArray = arrayOf(packageInfo)
    private val packageInfoArray2 = arrayOf(packageInfo, packageInfo2)
    private val pmPackageInfoArray = arrayOf(
        PackageInfo().apply { packageName = "@pm@" },
        packageInfo
    )
    private val storageName = getRandomString()

    init {
        metadata.packageMetadataMap[packageInfo2.packageName] = PackageMetadata(
            backupType = BackupType.FULL,
            chunkIds = listOf(chunkId2),
        )

        every { backendManager.backend } returns backend
    }

    @Test
    fun `getAvailableRestoreSets() builds set from plugin response`() = runBlocking {
        val handle1 = LegacyAppBackupFile.Metadata(token)
        val handle2 = LegacyAppBackupFile.Metadata(token + 1)

        coEvery { backend.getAvailableBackupFileHandles() } returns listOf(handle1, handle2)
        coEvery { backend.load(handle1) } returns inputStream
        coEvery { backend.load(handle2) } returns inputStream
        every { metadataReader.readMetadata(inputStream, token) } returns metadata
        every { metadataReader.readMetadata(inputStream, token + 1) } returns metadata
        every { inputStream.close() } just Runs

        val sets = restore.getAvailableRestoreSets() ?: fail()
        assertEquals(2, sets.size)
        assertEquals(metadata.deviceName, sets[0].device)
        assertEquals(metadata.deviceName, sets[0].name)
        assertEquals(metadata.token, sets[0].token)

        every { metadataReader.readMetadata(inputStream, token) } returns d2dMetadata
        every { metadataReader.readMetadata(inputStream, token + 1) } returns d2dMetadata

        val d2dSets = restore.getAvailableRestoreSets() ?: fail()
        assertEquals(2, d2dSets.size)
        assertEquals(D2D_DEVICE_NAME, d2dSets[0].device)
        assertEquals(metadata.deviceName, d2dSets[0].name)
        assertEquals(metadata.token, d2dSets[0].token)
    }

    @Test
    fun `getCurrentRestoreSet() delegates to plugin`() {
        every { settingsManager.token } returns token
        assertEquals(token, restore.getCurrentRestoreSet())
    }

    @Test
    fun `startRestore() returns OK`() = runBlocking {
        restore.beforeStartRestore(restorableBackup)
        assertEquals(TRANSPORT_OK, restore.startRestore(token, packageInfoArray))
    }

    @Test
    fun `startRestore() fetches metadata if missing`() = runBlocking {
        val handle1 = LegacyAppBackupFile.Metadata(token)
        val handle2 = LegacyAppBackupFile.Metadata(token + 1)

        coEvery { backend.getAvailableBackupFileHandles() } returns listOf(handle1, handle2)
        coEvery { backend.load(handle1) } returns inputStream
        coEvery { backend.load(handle2) } returns inputStream
        every { metadataReader.readMetadata(inputStream, token) } returns metadata
        every { metadataReader.readMetadata(inputStream, token + 1) } returns metadata
        every { inputStream.close() } just Runs

        assertEquals(TRANSPORT_OK, restore.startRestore(token, packageInfoArray))
    }

    @Test
    fun `startRestore() errors if metadata is not matching token`() = runBlocking {
        val otherToken = token + 42
        val info = FileInfo(LegacyAppBackupFile.Metadata(otherToken), 23)
        coEvery {
            backend.list(
                topLevelFolder = null,
                AppBackupFileType.Snapshot::class, LegacyAppBackupFile.Metadata::class,
                callback = captureLambda<(FileInfo) -> Unit>()
            )
        } answers {
            val callback = lambda<(FileInfo) -> Unit>().captured
            callback(info)
        }
        coEvery { backend.load(info.fileHandle) } returns inputStream
        every { metadataReader.readMetadata(inputStream, otherToken) } returns metadata
        every { inputStream.close() } just Runs

        assertEquals(TRANSPORT_ERROR, restore.startRestore(otherToken, packageInfoArray))
    }

    @Test
    fun `startRestore() can not be called twice`() = runBlocking {
        restore.beforeStartRestore(restorableBackup)
        assertEquals(TRANSPORT_OK, restore.startRestore(token, packageInfoArray))
        assertThrows(IllegalStateException::class.javaObjectType) {
            runBlocking {
                restore.startRestore(token, packageInfoArray)
            }
        }
        Unit
    }

    @Test
    fun `startRestore() can be be called again after restore finished`() = runBlocking {
        restore.beforeStartRestore(restorableBackup)
        assertEquals(TRANSPORT_OK, restore.startRestore(token, packageInfoArray))

        every { full.hasState } returns false
        restore.finishRestore()

        restore.beforeStartRestore(restorableBackup)
        assertEquals(TRANSPORT_OK, restore.startRestore(token, packageInfoArray))
    }

    @Test
    fun `startRestore() optimized auto-restore with removed storage shows notification`() =
        runBlocking {
            every { backendManager.backendProperties } returns safStorage
            every { safStorage.isUnavailableUsb(context) } returns true
            every { metadataManager.getPackageMetadata(packageName) } returns PackageMetadata(42L)
            every { safStorage.name } returns storageName
            every {
                notificationManager.onRemovableStorageNotAvailableForRestore(
                    packageName,
                    storageName
                )
            } just Runs

            assertEquals(TRANSPORT_ERROR, restore.startRestore(token, pmPackageInfoArray))

            verify(exactly = 1) {
                notificationManager.onRemovableStorageNotAvailableForRestore(
                    packageName,
                    storageName
                )
            }
        }

    @Test
    fun `startRestore() optimized auto-restore with available storage shows no notification`() =
        runBlocking {
            every { backendManager.backendProperties } returns safStorage
            every { safStorage.isUnavailableUsb(context) } returns false

            restore.beforeStartRestore(restorableBackup)
            assertEquals(TRANSPORT_OK, restore.startRestore(token, pmPackageInfoArray))

            verify(exactly = 0) {
                notificationManager.onRemovableStorageNotAvailableForRestore(
                    packageName,
                    storageName
                )
            }
        }

    @Test
    fun `startRestore() loads snapshots for auto-restore from local cache`() = runBlocking {
        every { backendManager.backendProperties } returns safStorage
        every { safStorage.isUnavailableUsb(context) } returns false

        every { crypto.repoId } returns repoId
        every { snapshotManager.loadCachedSnapshots() } returns listOf(snapshot)

        assertEquals(TRANSPORT_OK, restore.startRestore(token, pmPackageInfoArray))

        verify {
            snapshotManager.loadCachedSnapshots()
        }
    }

    @Test
    fun `startRestore() errors when it can't find snapshots`() = runBlocking {
        val handle = AppBackupFileType.Snapshot(repoId, getRandomByteArray(32).toHexString())

        every { backendManager.backendProperties } returns safStorage
        every { safStorage.isUnavailableUsb(context) } returns false
        coEvery { backend.getAvailableBackupFileHandles() } returns listOf(handle)
        coEvery { snapshotManager.loadSnapshot(handle) } returns snapshot.copy {
            token = this@RestoreCoordinatorTest.token - 1 // unexpected token
        }

        assertEquals(TRANSPORT_ERROR, restore.startRestore(token, pmPackageInfoArray))

        coVerify {
            snapshotManager.loadSnapshot(handle) // really loaded snapshot
        }
    }

    @Test
    fun `startRestore() with removed storage shows no notification`() = runBlocking {
        every { backendManager.backendProperties } returns safStorage
        every { safStorage.isUnavailableUsb(context) } returns true
        every { metadataManager.getPackageMetadata(packageName) } returns null

        assertEquals(TRANSPORT_ERROR, restore.startRestore(token, pmPackageInfoArray))

        verify(exactly = 0) {
            notificationManager.onRemovableStorageNotAvailableForRestore(
                packageName,
                storageName
            )
        }
    }

    @Test
    fun `nextRestorePackage() throws without startRestore()`() {
        coAssertThrows(IllegalStateException::class.javaObjectType) {
            restore.nextRestorePackage()
        }
    }

    @Test
    fun `nextRestorePackageV1() returns KV description`() = runBlocking {
        restore.beforeStartRestore(restorableBackup.copy(metadata.copy(version = 1)))
        restore.startRestore(token, packageInfoArray)

        every { crypto.getNameForPackage(metadata.salt, packageName) } returns name
        every { kv.initializeStateV1(token, name, packageInfo) } just Runs

        val expected = RestoreDescription(packageName, TYPE_KEY_VALUE)
        assertEquals(expected, restore.nextRestorePackage())
    }

    @Test
    @Suppress("Deprecation")
    fun `v0 nextRestorePackage() returns KV description and takes precedence`() = runBlocking {
        val backup = restorableBackup.copy(backupMetadata = metadata.copy(version = 0x00))
        restore.beforeStartRestore(backup)
        restore.startRestore(token, packageInfoArray)

        coEvery { kv.hasDataForPackage(token, packageInfo) } returns true
        every { kv.initializeStateV0(token, packageInfo) } just Runs

        val expected = RestoreDescription(packageInfo.packageName, TYPE_KEY_VALUE)
        assertEquals(expected, restore.nextRestorePackage())
    }

    @Test
    @Suppress("deprecation")
    fun `v0 nextRestorePackage() returns full description if no KV data found`() = runBlocking {
        val backup = restorableBackup.copy(backupMetadata = metadata.copy(version = 0x00))
        restore.beforeStartRestore(backup)
        restore.startRestore(token, packageInfoArray)

        coEvery { kv.hasDataForPackage(token, packageInfo) } returns false
        coEvery { full.hasDataForPackage(token, packageInfo) } returns true
        every { full.initializeStateV0(token, packageInfo) } just Runs

        val expected = RestoreDescription(packageInfo.packageName, TYPE_FULL_STREAM)
        assertEquals(expected, restore.nextRestorePackage())
    }

    @Test
    fun `nextRestorePackage() tries next package if one has no backup type()`() = runBlocking {
        metadata.packageMetadataMap[packageName] =
            metadata.packageMetadataMap[packageName]!!.copy(backupType = null)
        restore.beforeStartRestore(restorableBackup)
        restore.startRestore(token, packageInfoArray2)

        every { full.initializeState(VERSION, packageInfo2, listOf(blobHandle2)) } just Runs

        val expected = RestoreDescription(packageInfo2.packageName, TYPE_FULL_STREAM)
        assertEquals(expected, restore.nextRestorePackage())

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    fun `nextRestorePackageV1() tries next package if one has no backup type()`() = runBlocking {
        metadata.packageMetadataMap[packageName] =
            metadata.packageMetadataMap[packageName]!!.copy(backupType = null)
        restore.beforeStartRestore(restorableBackup.copy(metadata.copy(version = 1)))
        restore.startRestore(token, packageInfoArray2)

        every { crypto.getNameForPackage(metadata.salt, packageInfo2.packageName) } returns name2
        every { full.initializeStateV1(token, name2, packageInfo2) } just Runs

        val expected = RestoreDescription(packageInfo2.packageName, TYPE_FULL_STREAM)
        assertEquals(expected, restore.nextRestorePackage())

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    fun `nextRestorePackage() returns all packages from startRestore()`() = runBlocking {
        restore.beforeStartRestore(restorableBackup)
        restore.startRestore(token, packageInfoArray2)

        every { kv.initializeState(VERSION, packageInfo, listOf(blobHandle1)) } just Runs

        val expected = RestoreDescription(packageInfo.packageName, TYPE_KEY_VALUE)
        assertEquals(expected, restore.nextRestorePackage())

        every { full.initializeState(VERSION, packageInfo2, listOf(blobHandle2)) } just Runs

        val expected2 =
            RestoreDescription(packageInfo2.packageName, TYPE_FULL_STREAM)
        assertEquals(expected2, restore.nextRestorePackage())

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    fun `nextRestorePackageV1() returns all packages from startRestore()`() = runBlocking {
        restore.beforeStartRestore(restorableBackup.copy(metadata.copy(version = 1)))
        restore.startRestore(token, packageInfoArray2)

        every { crypto.getNameForPackage(metadata.salt, packageName) } returns name
        every { kv.initializeStateV1(token, name, packageInfo) } just Runs

        val expected = RestoreDescription(packageInfo.packageName, TYPE_KEY_VALUE)
        assertEquals(expected, restore.nextRestorePackage())

        every { crypto.getNameForPackage(metadata.salt, packageInfo2.packageName) } returns name2
        every { full.initializeStateV1(token, name2, packageInfo2) } just Runs

        val expected2 =
            RestoreDescription(packageInfo2.packageName, TYPE_FULL_STREAM)
        assertEquals(expected2, restore.nextRestorePackage())

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    @Suppress("deprecation")
    fun `v0 nextRestorePackage() returns all packages from startRestore()`() = runBlocking {
        val backup = restorableBackup.copy(backupMetadata = metadata.copy(version = 0x00))
        restore.beforeStartRestore(backup)
        restore.startRestore(token, packageInfoArray2)

        coEvery { kv.hasDataForPackage(token, packageInfo) } returns true
        every { kv.initializeStateV0(token, packageInfo) } just Runs

        val expected = RestoreDescription(packageInfo.packageName, TYPE_KEY_VALUE)
        assertEquals(expected, restore.nextRestorePackage())

        coEvery { kv.hasDataForPackage(token, packageInfo2) } returns false
        coEvery { full.hasDataForPackage(token, packageInfo2) } returns true
        every { full.initializeStateV0(token, packageInfo2) } just Runs

        val expected2 = RestoreDescription(packageInfo2.packageName, TYPE_FULL_STREAM)
        assertEquals(expected2, restore.nextRestorePackage())

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    @Suppress("Deprecation")
    fun `v0 when kv#hasDataForPackage() throws, it tries next package`() = runBlocking {
        val backup = restorableBackup.copy(backupMetadata = metadata.copy(version = 0x00))
        restore.beforeStartRestore(backup)
        restore.startRestore(token, packageInfoArray)

        coEvery { kv.hasDataForPackage(token, packageInfo) } throws IOException()

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    @Suppress("deprecation")
    fun `v0 when full#hasDataForPackage() throws, it tries next package`() = runBlocking {
        val backup = restorableBackup.copy(backupMetadata = metadata.copy(version = 0x00))
        restore.beforeStartRestore(backup)
        restore.startRestore(token, packageInfoArray)

        coEvery { kv.hasDataForPackage(token, packageInfo) } returns false
        coEvery { full.hasDataForPackage(token, packageInfo) } throws IOException()

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    fun `getRestoreData() delegates to KV`() = runBlocking {
        val data = mockk<ParcelFileDescriptor>()
        val result = Random.nextInt()

        coEvery { kv.getRestoreData(data) } returns result

        assertEquals(result, restore.getRestoreData(data))
    }

    @Test
    fun `getNextFullRestoreDataChunk() delegates to Full`() = runBlocking {
        val data = mockk<ParcelFileDescriptor>()
        val result = Random.nextInt()

        coEvery { full.getNextFullRestoreDataChunk(data) } returns result

        assertEquals(result, restore.getNextFullRestoreDataChunk(data))
    }

    @Test
    fun `abortFullRestore() delegates to Full`() {
        val result = Random.nextInt()

        every { full.abortFullRestore() } returns result

        assertEquals(result, restore.abortFullRestore())
    }

    @Test
    fun `finishRestore() delegates to Full if it has state`() {
        val hasState = Random.nextBoolean()

        every { full.hasState } returns hasState
        if (hasState) {
            every { full.finishRestore() } just Runs
        }

        restore.finishRestore()
    }

    private fun assertEquals(expected: RestoreDescription, actual: RestoreDescription?) {
        assertNotNull(actual)
        assertEquals(expected.packageName, actual?.packageName)
        assertEquals(expected.dataType, actual?.dataType)
    }

}
