/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport

import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.BackupTransport.NO_MORE_DATA
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.RestoreDescription
import android.app.backup.RestoreDescription.TYPE_FULL_STREAM
import android.os.ParcelFileDescriptor
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.backend.LegacyStoragePlugin
import com.stevesoltys.seedvault.crypto.CipherFactoryImpl
import com.stevesoltys.seedvault.crypto.CryptoImpl
import com.stevesoltys.seedvault.crypto.KeyManagerTestImpl
import com.stevesoltys.seedvault.header.HeaderReaderImpl
import com.stevesoltys.seedvault.header.MAX_SEGMENT_CLEARTEXT_LENGTH
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.MetadataReaderImpl
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.repo.AppBackupManager
import com.stevesoltys.seedvault.repo.BackupReceiver
import com.stevesoltys.seedvault.repo.Loader
import com.stevesoltys.seedvault.repo.SnapshotCreator
import com.stevesoltys.seedvault.repo.SnapshotManager
import com.stevesoltys.seedvault.transport.backup.BackupCoordinator
import com.stevesoltys.seedvault.transport.backup.FullBackup
import com.stevesoltys.seedvault.transport.backup.InputFactory
import com.stevesoltys.seedvault.transport.backup.KVBackup
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.transport.backup.TestKvDbManager
import com.stevesoltys.seedvault.transport.restore.FullRestore
import com.stevesoltys.seedvault.transport.restore.KVRestore
import com.stevesoltys.seedvault.transport.restore.OutputFactory
import com.stevesoltys.seedvault.transport.restore.RestorableBackup
import com.stevesoltys.seedvault.transport.restore.RestoreCoordinator
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.Backend
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.random.Random

internal class CoordinatorIntegrationTest : TransportTest() {

    private val inputFactory = mockk<InputFactory>()
    private val outputFactory = mockk<OutputFactory>()
    private val keyManager = KeyManagerTestImpl()
    private val cipherFactory = CipherFactoryImpl(keyManager)
    private val headerReader = HeaderReaderImpl()
    private val cryptoImpl =
        CryptoImpl(context, keyManager, cipherFactory, headerReader, "androidId")
    private val metadataReader = MetadataReaderImpl(cryptoImpl)
    private val notificationManager = mockk<BackupNotificationManager>()
    private val dbManager = TestKvDbManager()
    private val backendManager: BackendManager = mockk()
    private val appBackupManager: AppBackupManager = mockk()
    private val snapshotCreator: SnapshotCreator = mockk()

    @Suppress("Deprecation")
    private val legacyPlugin = mockk<LegacyStoragePlugin>()
    private val backend = mockk<Backend>()
    private val loader = mockk<Loader>()
    private val snapshotManager = mockk<SnapshotManager>()
    private val backupReceiver = mockk<BackupReceiver>()
    private val kvBackup = KVBackup(
        backupReceiver = backupReceiver,
        inputFactory = inputFactory,
        dbManager = dbManager,
    )
    private val fullBackup = FullBackup(
        settingsManager = settingsManager,
        nm = notificationManager,
        backupReceiver = backupReceiver,
        inputFactory = inputFactory,
    )
    private val packageService: PackageService = mockk()
    private val backup = BackupCoordinator(
        context = context,
        backendManager = backendManager,
        appBackupManager = appBackupManager,
        kv = kvBackup,
        full = fullBackup,
        packageService = packageService,
        metadataManager = metadataManager,
        settingsManager = settingsManager,
        nm = notificationManager,
    )

    private val kvRestore = KVRestore(
        backendManager,
        loader,
        legacyPlugin,
        outputFactory,
        headerReader,
        cryptoImpl,
        dbManager
    )
    private val fullRestore =
        FullRestore(backendManager, loader, legacyPlugin, outputFactory, headerReader, cryptoImpl)
    private val restore = RestoreCoordinator(
        context,
        crypto,
        settingsManager,
        metadataManager,
        notificationManager,
        backendManager,
        snapshotManager,
        kvRestore,
        fullRestore,
        metadataReader
    )

    private val restorableBackup = RestorableBackup(metadata, repoId, snapshot)
    private val backupDataInput = mockk<BackupDataInput>()
    private val fileDescriptor = mockk<ParcelFileDescriptor>(relaxed = true)
    private val appData = ByteArray(42).apply { Random.nextBytes(this) }
    private val appData2 = ByteArray(1337).apply { Random.nextBytes(this) }
    private val key = "RestoreKey"
    private val key2 = "RestoreKey2"

    init {
        every { backendManager.backend } returns backend
        every { appBackupManager.snapshotCreator } returns snapshotCreator
    }

    @Test
    fun `test key-value backup and restore with 2 records`() = runBlocking {
        val value = CapturingSlot<ByteArray>()
        val value2 = CapturingSlot<ByteArray>()
        val inputStream = CapturingSlot<InputStream>()
        val bOutputStream = ByteArrayOutputStream()

        // read one key/value record and write it to output stream
        every { inputFactory.getBackupDataInput(fileDescriptor) } returns backupDataInput
        every { backupDataInput.readNextHeader() } returns true andThen true andThen false
        every { backupDataInput.key } returns key andThen key2
        every { backupDataInput.dataSize } returns appData.size andThen appData2.size
        every { backupDataInput.readEntityData(capture(value), 0, appData.size) } answers {
            appData.copyInto(value.captured) // write the app data into the passed ByteArray
            appData.size
        }
        every { backupDataInput.readEntityData(capture(value2), 0, appData2.size) } answers {
            appData2.copyInto(value2.captured) // write the app data into the passed ByteArray
            appData2.size
        }

        // start K/V backup
        assertEquals(TRANSPORT_OK, backup.performIncrementalBackup(packageInfo, fileDescriptor, 0))

        // upload DB
        coEvery { backupReceiver.readFromStream(any(), capture(inputStream)) } answers {
            inputStream.captured.copyTo(bOutputStream)
            apkBackupData
        }
        every {
            snapshotCreator.onPackageBackedUp(packageInfo, BackupType.KV, apkBackupData)
        } just Runs
        every {
            metadataManager.onPackageBackedUp(packageInfo, BackupType.KV, apkBackupData.size)
        } just Runs

        // finish K/V backup
        assertEquals(TRANSPORT_OK, backup.finishBackup())

        // start restore
        restore.beforeStartRestore(restorableBackup)
        assertEquals(TRANSPORT_OK, restore.startRestore(token, arrayOf(packageInfo)))

        val restoreDescription = restore.nextRestorePackage() ?: fail()
        assertEquals(packageInfo.packageName, restoreDescription.packageName)
        assertEquals(RestoreDescription.TYPE_KEY_VALUE, restoreDescription.dataType)

        // restore finds the backed up key and writes the decrypted value
        val backupDataOutput = mockk<BackupDataOutput>()
        val rInputStream = ByteArrayInputStream(bOutputStream.toByteArray())
        coEvery { loader.loadFiles(listOf(blobHandle1)) } returns rInputStream
        every { outputFactory.getBackupDataOutput(fileDescriptor) } returns backupDataOutput
        every { backupDataOutput.writeEntityHeader(key, appData.size) } returns 1137
        every { backupDataOutput.writeEntityData(appData, appData.size) } returns appData.size
        every { backupDataOutput.writeEntityHeader(key2, appData2.size) } returns 1137
        every { backupDataOutput.writeEntityData(appData2, appData2.size) } returns appData2.size

        assertEquals(TRANSPORT_OK, restore.getRestoreData(fileDescriptor))

        verify {
            backupDataOutput.writeEntityHeader(key, appData.size)
            backupDataOutput.writeEntityData(appData, appData.size)
            backupDataOutput.writeEntityHeader(key2, appData2.size)
            backupDataOutput.writeEntityData(appData2, appData2.size)
        }
    }

    @Test
    fun `test key-value backup with huge value`() = runBlocking {
        val value = CapturingSlot<ByteArray>()
        val inputStream = CapturingSlot<InputStream>()
        val size = Random.nextInt(5) * MAX_SEGMENT_CLEARTEXT_LENGTH + Random.nextInt(0, 1337)
        val appData = ByteArray(size).apply { Random.nextBytes(this) }
        val bOutputStream = ByteArrayOutputStream()

        // read one key/value record and write it to output stream
        every { inputFactory.getBackupDataInput(fileDescriptor) } returns backupDataInput
        every { backupDataInput.readNextHeader() } returns true andThen false
        every { backupDataInput.key } returns key
        every { backupDataInput.dataSize } returns appData.size
        every { backupDataInput.readEntityData(capture(value), 0, appData.size) } answers {
            appData.copyInto(value.captured) // write the app data into the passed ByteArray
            appData.size
        }

        // start K/V backup
        assertEquals(TRANSPORT_OK, backup.performIncrementalBackup(packageInfo, fileDescriptor, 0))

        // upload DB
        coEvery { backupReceiver.readFromStream(any(), capture(inputStream)) } answers {
            inputStream.captured.copyTo(bOutputStream)
            apkBackupData
        }
        every {
            snapshotCreator.onPackageBackedUp(packageInfo, BackupType.KV, apkBackupData)
        } just Runs
        every {
            metadataManager.onPackageBackedUp(packageInfo, BackupType.KV, apkBackupData.size)
        } just Runs

        // finish K/V backup
        assertEquals(TRANSPORT_OK, backup.finishBackup())

        // start restore
        restore.beforeStartRestore(restorableBackup)
        assertEquals(TRANSPORT_OK, restore.startRestore(token, arrayOf(packageInfo)))

        val restoreDescription = restore.nextRestorePackage() ?: fail()
        assertEquals(packageInfo.packageName, restoreDescription.packageName)
        assertEquals(RestoreDescription.TYPE_KEY_VALUE, restoreDescription.dataType)

        // restore finds the backed up key and writes the decrypted value
        val backupDataOutput = mockk<BackupDataOutput>()
        val rInputStream = ByteArrayInputStream(bOutputStream.toByteArray())
        coEvery { loader.loadFiles(listOf(blobHandle1)) } returns rInputStream
        every { outputFactory.getBackupDataOutput(fileDescriptor) } returns backupDataOutput
        every { backupDataOutput.writeEntityHeader(key, appData.size) } returns 1137
        every { backupDataOutput.writeEntityData(appData, appData.size) } returns appData.size

        assertEquals(TRANSPORT_OK, restore.getRestoreData(fileDescriptor))

        verify {
            backupDataOutput.writeEntityHeader(key, appData.size)
            backupDataOutput.writeEntityData(appData, appData.size)
        }
    }

    @Test
    fun `test full backup and restore with two chunks`() = runBlocking {
        metadata.packageMetadataMap[packageName] = PackageMetadata(
            backupType = BackupType.FULL,
            chunkIds = listOf(chunkId1),
        )

        // package is of type FULL
        val packageMetadata = metadata.packageMetadataMap[packageInfo.packageName]!!
        metadata.packageMetadataMap[packageInfo.packageName] =
            packageMetadata.copy(backupType = BackupType.FULL)

        // return streams from plugin and app data
        val byteSlot = slot<ByteArray>()
        val bOutputStream = ByteArrayOutputStream()
        val bInputStream = ByteArrayInputStream(appData)

        every { inputFactory.getInputStream(fileDescriptor) } returns bInputStream
        every { settingsManager.quota } returns quota
        coEvery { backupReceiver.addBytes(any(), capture(byteSlot)) } answers {
            bOutputStream.writeBytes(byteSlot.captured)
        }
        every {
            snapshotCreator.onPackageBackedUp(packageInfo, BackupType.FULL, apkBackupData)
        } just Runs
        every {
            metadataManager.onPackageBackedUp(
                packageInfo = packageInfo,
                type = BackupType.FULL,
                size = apkBackupData.size,
            )
        } just Runs
        coEvery { backupReceiver.finalize(any()) } returns apkBackupData // just some backupData

        // perform backup to output stream
        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, fileDescriptor, 0))
        assertEquals(TRANSPORT_OK, backup.sendBackupData(appData.size / 2))
        assertEquals(TRANSPORT_OK, backup.sendBackupData(appData.size / 2))
        assertEquals(TRANSPORT_OK, backup.finishBackup())

        // start restore
        restore.beforeStartRestore(restorableBackup)
        assertEquals(TRANSPORT_OK, restore.startRestore(token, arrayOf(packageInfo)))

        val restoreDescription = restore.nextRestorePackage() ?: fail()
        assertEquals(packageInfo.packageName, restoreDescription.packageName)
        assertEquals(TYPE_FULL_STREAM, restoreDescription.dataType)

        // reverse the backup streams into restore input
        val rInputStream = ByteArrayInputStream(bOutputStream.toByteArray())
        val rOutputStream = ByteArrayOutputStream()
        coEvery { loader.loadFiles(listOf(blobHandle1)) } returns rInputStream
        every { outputFactory.getOutputStream(fileDescriptor) } returns rOutputStream

        // restore data
        assertEquals(appData.size, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertEquals(NO_MORE_DATA, restore.getNextFullRestoreDataChunk(fileDescriptor))
        restore.finishRestore()

        // assert that restored data matches original app data
        assertArrayEquals(appData, rOutputStream.toByteArray())
    }

}
