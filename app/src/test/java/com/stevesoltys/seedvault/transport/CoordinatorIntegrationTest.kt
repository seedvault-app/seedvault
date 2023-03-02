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
import com.stevesoltys.seedvault.crypto.CipherFactoryImpl
import com.stevesoltys.seedvault.crypto.CryptoImpl
import com.stevesoltys.seedvault.crypto.KeyManagerTestImpl
import com.stevesoltys.seedvault.header.HeaderReaderImpl
import com.stevesoltys.seedvault.header.MAX_SEGMENT_CLEARTEXT_LENGTH
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.MetadataReaderImpl
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.plugins.LegacyStoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.plugins.saf.FILE_BACKUP_METADATA
import com.stevesoltys.seedvault.transport.backup.BackupCoordinator
import com.stevesoltys.seedvault.transport.backup.FullBackup
import com.stevesoltys.seedvault.transport.backup.InputFactory
import com.stevesoltys.seedvault.transport.backup.KVBackup
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.transport.backup.TestKvDbManager
import com.stevesoltys.seedvault.transport.restore.FullRestore
import com.stevesoltys.seedvault.transport.restore.KVRestore
import com.stevesoltys.seedvault.transport.restore.OutputFactory
import com.stevesoltys.seedvault.transport.restore.RestoreCoordinator
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.worker.ApkBackup
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

internal class CoordinatorIntegrationTest : TransportTest() {

    private val inputFactory = mockk<InputFactory>()
    private val outputFactory = mockk<OutputFactory>()
    private val keyManager = KeyManagerTestImpl()
    private val cipherFactory = CipherFactoryImpl(keyManager)
    private val headerReader = HeaderReaderImpl()
    private val cryptoImpl = CryptoImpl(keyManager, cipherFactory, headerReader)
    private val metadataReader = MetadataReaderImpl(cryptoImpl)
    private val notificationManager = mockk<BackupNotificationManager>()
    private val dbManager = TestKvDbManager()
    private val storagePluginManager: StoragePluginManager = mockk()

    @Suppress("Deprecation")
    private val legacyPlugin = mockk<LegacyStoragePlugin>()
    private val backupPlugin = mockk<StoragePlugin<*>>()
    private val kvBackup = KVBackup(
        pluginManager = storagePluginManager,
        settingsManager = settingsManager,
        nm = notificationManager,
        inputFactory = inputFactory,
        crypto = cryptoImpl,
        dbManager = dbManager,
    )
    private val fullBackup = FullBackup(
        pluginManager = storagePluginManager,
        settingsManager = settingsManager,
        nm = notificationManager,
        inputFactory = inputFactory,
        crypto = cryptoImpl,
    )
    private val apkBackup = mockk<ApkBackup>()
    private val packageService: PackageService = mockk()
    private val backup = BackupCoordinator(
        context,
        storagePluginManager,
        kvBackup,
        fullBackup,
        clock,
        packageService,
        metadataManager,
        settingsManager,
        notificationManager
    )

    private val kvRestore = KVRestore(
        storagePluginManager,
        legacyPlugin,
        outputFactory,
        headerReader,
        cryptoImpl,
        dbManager
    )
    private val fullRestore =
        FullRestore(storagePluginManager, legacyPlugin, outputFactory, headerReader, cryptoImpl)
    private val restore = RestoreCoordinator(
        context,
        crypto,
        settingsManager,
        metadataManager,
        notificationManager,
        storagePluginManager,
        kvRestore,
        fullRestore,
        metadataReader
    )

    private val backupDataInput = mockk<BackupDataInput>()
    private val fileDescriptor = mockk<ParcelFileDescriptor>(relaxed = true)
    private val appData = ByteArray(42).apply { Random.nextBytes(this) }
    private val appData2 = ByteArray(1337).apply { Random.nextBytes(this) }
    private val metadataOutputStream = ByteArrayOutputStream()
    private val packageMetadata = PackageMetadata(time = 0L)
    private val key = "RestoreKey"
    private val key2 = "RestoreKey2"

    // as we use real crypto, we need a real name for packageInfo
    private val realName = cryptoImpl.getNameForPackage(salt, packageInfo.packageName)

    init {
        every { storagePluginManager.appPlugin } returns backupPlugin
    }

    @Test
    fun `test key-value backup and restore with 2 records`() = runBlocking {
        val value = CapturingSlot<ByteArray>()
        val value2 = CapturingSlot<ByteArray>()
        val bOutputStream = ByteArrayOutputStream()

        every { metadataManager.requiresInit } returns false
        every { settingsManager.getToken() } returns token
        every { metadataManager.salt } returns salt
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
        coEvery {
            apkBackup.backupApkIfNecessary(packageInfo, any())
        } returns packageMetadata
        coEvery {
            backupPlugin.getOutputStream(token, FILE_BACKUP_METADATA)
        } returns metadataOutputStream
        every {
            metadataManager.onApkBackedUp(packageInfo, packageMetadata)
        } just Runs
        every {
            metadataManager.onPackageBackedUp(
                packageInfo = packageInfo,
                type = BackupType.KV,
                size = more((appData.size + appData2.size).toLong()), // more because DB overhead
                metadataOutputStream = metadataOutputStream,
            )
        } just Runs

        // start K/V backup
        assertEquals(TRANSPORT_OK, backup.performIncrementalBackup(packageInfo, fileDescriptor, 0))

        // upload DB
        coEvery { backupPlugin.getOutputStream(token, realName) } returns bOutputStream

        // finish K/V backup
        assertEquals(TRANSPORT_OK, backup.finishBackup())

        // start restore
        restore.beforeStartRestore(metadata)
        assertEquals(TRANSPORT_OK, restore.startRestore(token, arrayOf(packageInfo)))

        // find data for K/V backup
        every { crypto.getNameForPackage(metadata.salt, packageInfo.packageName) } returns name
        coEvery { backupPlugin.hasData(token, name) } returns true

        val restoreDescription = restore.nextRestorePackage() ?: fail()
        assertEquals(packageInfo.packageName, restoreDescription.packageName)
        assertEquals(RestoreDescription.TYPE_KEY_VALUE, restoreDescription.dataType)

        // restore finds the backed up key and writes the decrypted value
        val backupDataOutput = mockk<BackupDataOutput>()
        val rInputStream = ByteArrayInputStream(bOutputStream.toByteArray())
        coEvery { backupPlugin.getInputStream(token, name) } returns rInputStream
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
        val size = Random.nextInt(5) * MAX_SEGMENT_CLEARTEXT_LENGTH + Random.nextInt(0, 1337)
        val appData = ByteArray(size).apply { Random.nextBytes(this) }
        val bOutputStream = ByteArrayOutputStream()

        every { metadataManager.requiresInit } returns false
        every { settingsManager.getToken() } returns token
        every { metadataManager.salt } returns salt
        // read one key/value record and write it to output stream
        every { inputFactory.getBackupDataInput(fileDescriptor) } returns backupDataInput
        every { backupDataInput.readNextHeader() } returns true andThen false
        every { backupDataInput.key } returns key
        every { backupDataInput.dataSize } returns appData.size
        every { backupDataInput.readEntityData(capture(value), 0, appData.size) } answers {
            appData.copyInto(value.captured) // write the app data into the passed ByteArray
            appData.size
        }
        coEvery { apkBackup.backupApkIfNecessary(packageInfo, any()) } returns null
        every { settingsManager.getToken() } returns token
        coEvery {
            backupPlugin.getOutputStream(token, FILE_BACKUP_METADATA)
        } returns metadataOutputStream
        every {
            metadataManager.onPackageBackedUp(
                packageInfo = packageInfo,
                type = BackupType.KV,
                size = more(size.toLong()), // more than $size, because DB overhead
                metadataOutputStream = metadataOutputStream,
            )
        } just Runs

        // start K/V backup
        assertEquals(TRANSPORT_OK, backup.performIncrementalBackup(packageInfo, fileDescriptor, 0))

        // upload DB
        coEvery { backupPlugin.getOutputStream(token, realName) } returns bOutputStream

        // finish K/V backup
        assertEquals(TRANSPORT_OK, backup.finishBackup())

        // start restore
        restore.beforeStartRestore(metadata)
        assertEquals(TRANSPORT_OK, restore.startRestore(token, arrayOf(packageInfo)))

        // find data for K/V backup
        every { crypto.getNameForPackage(metadata.salt, packageInfo.packageName) } returns name
        coEvery { backupPlugin.hasData(token, name) } returns true

        val restoreDescription = restore.nextRestorePackage() ?: fail()
        assertEquals(packageInfo.packageName, restoreDescription.packageName)
        assertEquals(RestoreDescription.TYPE_KEY_VALUE, restoreDescription.dataType)

        // restore finds the backed up key and writes the decrypted value
        val backupDataOutput = mockk<BackupDataOutput>()
        val rInputStream = ByteArrayInputStream(bOutputStream.toByteArray())
        coEvery { backupPlugin.getInputStream(token, name) } returns rInputStream
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
        // package is of type FULL
        val packageMetadata = metadata.packageMetadataMap[packageInfo.packageName]!!
        metadata.packageMetadataMap[packageInfo.packageName] =
            packageMetadata.copy(backupType = BackupType.FULL)

        // return streams from plugin and app data
        val bOutputStream = ByteArrayOutputStream()
        val bInputStream = ByteArrayInputStream(appData)
        coEvery { backupPlugin.getOutputStream(token, realName) } returns bOutputStream
        every { inputFactory.getInputStream(fileDescriptor) } returns bInputStream
        every { settingsManager.isQuotaUnlimited() } returns false
        coEvery { apkBackup.backupApkIfNecessary(packageInfo, any()) } returns packageMetadata
        every { settingsManager.getToken() } returns token
        every { metadataManager.salt } returns salt
        coEvery {
            backupPlugin.getOutputStream(token, FILE_BACKUP_METADATA)
        } returns metadataOutputStream
        every { metadataManager.onApkBackedUp(packageInfo, packageMetadata) } just Runs
        every {
            metadataManager.onPackageBackedUp(
                packageInfo = packageInfo,
                type = BackupType.FULL,
                size = appData.size.toLong(),
                metadataOutputStream = metadataOutputStream,
            )
        } just Runs

        // perform backup to output stream
        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, fileDescriptor, 0))
        assertEquals(TRANSPORT_OK, backup.sendBackupData(appData.size / 2))
        assertEquals(TRANSPORT_OK, backup.sendBackupData(appData.size / 2))
        assertEquals(TRANSPORT_OK, backup.finishBackup())

        // start restore
        restore.beforeStartRestore(metadata)
        assertEquals(TRANSPORT_OK, restore.startRestore(token, arrayOf(packageInfo)))

        // finds data for full backup
        every { crypto.getNameForPackage(salt, packageInfo.packageName) } returns name
        coEvery { backupPlugin.hasData(token, name) } returns true

        val restoreDescription = restore.nextRestorePackage() ?: fail()
        assertEquals(packageInfo.packageName, restoreDescription.packageName)
        assertEquals(TYPE_FULL_STREAM, restoreDescription.dataType)

        // reverse the backup streams into restore input
        val rInputStream = ByteArrayInputStream(bOutputStream.toByteArray())
        val rOutputStream = ByteArrayOutputStream()
        coEvery { backupPlugin.getInputStream(token, name) } returns rInputStream
        every { outputFactory.getOutputStream(fileDescriptor) } returns rOutputStream

        // restore data
        assertEquals(appData.size, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertEquals(NO_MORE_DATA, restore.getNextFullRestoreDataChunk(fileDescriptor))
        restore.finishRestore()

        // assert that restored data matches original app data
        assertArrayEquals(appData, rOutputStream.toByteArray())
    }

}
