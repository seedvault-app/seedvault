package com.stevesoltys.seedvault.transport

import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.BackupTransport.NO_MORE_DATA
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.RestoreDescription
import android.app.backup.RestoreDescription.TYPE_FULL_STREAM
import android.os.ParcelFileDescriptor
import com.stevesoltys.seedvault.service.crypto.CipherFactoryImpl
import com.stevesoltys.seedvault.service.crypto.CryptoServiceImpl
import com.stevesoltys.seedvault.crypto.KeyManagerTestImpl
import com.stevesoltys.seedvault.service.header.HeaderDecodeServiceImpl
import com.stevesoltys.seedvault.service.header.MAX_SEGMENT_CLEARTEXT_LENGTH
import com.stevesoltys.seedvault.service.metadata.BackupType
import com.stevesoltys.seedvault.service.metadata.MetadataReaderImpl
import com.stevesoltys.seedvault.service.metadata.PackageMetadata
import com.stevesoltys.seedvault.service.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.service.storage.saf.legacy.LegacyStoragePlugin
import com.stevesoltys.seedvault.service.storage.StoragePlugin
import com.stevesoltys.seedvault.service.storage.saf.FILE_BACKUP_METADATA
import com.stevesoltys.seedvault.service.app.backup.apk.ApkBackupService
import com.stevesoltys.seedvault.service.app.backup.coordinator.BackupCoordinatorService
import com.stevesoltys.seedvault.service.app.backup.full.FullBackupService
import com.stevesoltys.seedvault.service.app.backup.InputFactory
import com.stevesoltys.seedvault.service.app.backup.kv.KVBackupService
import com.stevesoltys.seedvault.service.app.PackageService
import com.stevesoltys.seedvault.transport.backup.TestKvDbManager
import com.stevesoltys.seedvault.service.app.restore.full.FullRestore
import com.stevesoltys.seedvault.service.app.restore.kv.KVRestore
import com.stevesoltys.seedvault.service.app.restore.OutputFactory
import com.stevesoltys.seedvault.service.app.restore.coordinator.RestoreCoordinator
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
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

@Suppress("BlockingMethodInNonBlockingContext")
internal class CoordinatorIntegrationTest : TransportTest() {

    private val inputFactory = mockk<InputFactory>()
    private val outputFactory = mockk<OutputFactory>()
    private val keyManager = KeyManagerTestImpl()
    private val cipherFactory = CipherFactoryImpl(keyManager)
    private val headerReader = HeaderDecodeServiceImpl()
    private val cryptoServiceImpl = CryptoServiceImpl(keyManager, cipherFactory, headerReader)
    private val metadataReader = MetadataReaderImpl(cryptoServiceImpl)
    private val notificationManager = mockk<BackupNotificationManager>()
    private val dbManager = TestKvDbManager()

    @Suppress("Deprecation")
    private val legacyPlugin = mockk<LegacyStoragePlugin>()
    private val backupPlugin = mockk<StoragePlugin>()
    private val kvBackupService =
        KVBackupService(backupPlugin, settingsService, inputFactory, cryptoServiceImpl, dbManager)
    private val fullBackupService =
        FullBackupService(backupPlugin, settingsService, inputFactory, cryptoServiceImpl)
    private val apkBackupService = mockk<ApkBackupService>()
    private val packageService: PackageService = mockk()
    private val backup = BackupCoordinatorService(
        context,
        backupPlugin,
        kvBackupService,
        fullBackupService,
        apkBackupService,
        timeSource,
        packageService,
        metadataService,
        settingsService,
        notificationManager
    )

    private val kvRestore = KVRestore(
        backupPlugin,
        legacyPlugin,
        outputFactory,
        headerReader,
        cryptoServiceImpl,
        dbManager
    )
    private val fullRestore =
        FullRestore(backupPlugin, legacyPlugin, outputFactory, headerReader, cryptoServiceImpl)
    private val restore = RestoreCoordinator(
        context,
        cryptoService,
        settingsService,
        metadataService,
        notificationManager,
        backupPlugin,
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
    private val realName = cryptoServiceImpl.getNameForPackage(salt, packageInfo.packageName)

    @Test
    fun `test key-value backup and restore with 2 records`() = runBlocking {
        val value = CapturingSlot<ByteArray>()
        val value2 = CapturingSlot<ByteArray>()
        val bOutputStream = ByteArrayOutputStream()

        every { metadataService.requiresInit } returns false
        every { settingsService.getToken() } returns token
        every { metadataService.salt } returns salt
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
            apkBackupService.backupApkIfNecessary(packageInfo, UNKNOWN_ERROR, any())
        } returns packageMetadata
        coEvery {
            backupPlugin.getOutputStream(token, FILE_BACKUP_METADATA)
        } returns metadataOutputStream
        every {
            metadataService.onApkBackedUp(packageInfo, packageMetadata, metadataOutputStream)
        } just Runs
        every {
            metadataService.onPackageBackedUp(packageInfo, BackupType.KV, metadataOutputStream)
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
        every {
            cryptoService.getNameForPackage(metadata.salt, packageInfo.packageName)
        } returns name
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

        every { metadataService.requiresInit } returns false
        every { settingsService.getToken() } returns token
        every { metadataService.salt } returns salt
        // read one key/value record and write it to output stream
        every { inputFactory.getBackupDataInput(fileDescriptor) } returns backupDataInput
        every { backupDataInput.readNextHeader() } returns true andThen false
        every { backupDataInput.key } returns key
        every { backupDataInput.dataSize } returns appData.size
        every { backupDataInput.readEntityData(capture(value), 0, appData.size) } answers {
            appData.copyInto(value.captured) // write the app data into the passed ByteArray
            appData.size
        }
        coEvery {
            apkBackupService.backupApkIfNecessary(
                packageInfo,
                UNKNOWN_ERROR,
                any()
            )
        } returns null
        every { settingsService.getToken() } returns token
        coEvery {
            backupPlugin.getOutputStream(token, FILE_BACKUP_METADATA)
        } returns metadataOutputStream
        every {
            metadataService.onPackageBackedUp(packageInfo, BackupType.KV, metadataOutputStream)
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
        every {
            cryptoService.getNameForPackage(
                metadata.salt,
                packageInfo.packageName
            )
        } returns name
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
        every { settingsService.isQuotaUnlimited() } returns false
        coEvery {
            apkBackupService.backupApkIfNecessary(
                packageInfo,
                UNKNOWN_ERROR,
                any()
            )
        } returns packageMetadata
        every { settingsService.getToken() } returns token
        every { metadataService.salt } returns salt
        coEvery {
            backupPlugin.getOutputStream(token, FILE_BACKUP_METADATA)
        } returns metadataOutputStream
        every {
            metadataService.onApkBackedUp(
                packageInfo,
                packageMetadata,
                metadataOutputStream
            )
        } just Runs
        every {
            metadataService.onPackageBackedUp(packageInfo, BackupType.FULL, metadataOutputStream)
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
        every { cryptoService.getNameForPackage(salt, packageInfo.packageName) } returns name
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
