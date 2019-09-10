package com.stevesoltys.backup.transport

import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.BackupTransport.NO_MORE_DATA
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.RestoreDescription
import android.app.backup.RestoreDescription.TYPE_FULL_STREAM
import android.os.ParcelFileDescriptor
import com.stevesoltys.backup.BackupNotificationManager
import com.stevesoltys.backup.crypto.CipherFactoryImpl
import com.stevesoltys.backup.crypto.CryptoImpl
import com.stevesoltys.backup.crypto.KeyManagerTestImpl
import com.stevesoltys.backup.encodeBase64
import com.stevesoltys.backup.header.HeaderReaderImpl
import com.stevesoltys.backup.header.HeaderWriterImpl
import com.stevesoltys.backup.metadata.MetadataWriterImpl
import com.stevesoltys.backup.transport.backup.*
import com.stevesoltys.backup.transport.restore.*
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

internal class CoordinatorIntegrationTest : TransportTest() {

    private val inputFactory = mockk<InputFactory>()
    private val outputFactory = mockk<OutputFactory>()
    private val keyManager = KeyManagerTestImpl()
    private val cipherFactory = CipherFactoryImpl(keyManager)
    private val headerWriter = HeaderWriterImpl()
    private val headerReader = HeaderReaderImpl()
    private val cryptoImpl = CryptoImpl(cipherFactory, headerWriter, headerReader)
    private val metadataWriter = MetadataWriterImpl(cryptoImpl)

    private val backupPlugin = mockk<BackupPlugin>()
    private val kvBackupPlugin = mockk<KVBackupPlugin>()
    private val kvBackup = KVBackup(kvBackupPlugin, inputFactory, headerWriter, cryptoImpl)
    private val fullBackupPlugin = mockk<FullBackupPlugin>()
    private val fullBackup = FullBackup(fullBackupPlugin, inputFactory, headerWriter, cryptoImpl)
    private val notificationManager = mockk<BackupNotificationManager>()
    private val backup = BackupCoordinator(backupPlugin, kvBackup, fullBackup, metadataWriter, notificationManager)

    private val restorePlugin = mockk<RestorePlugin>()
    private val kvRestorePlugin = mockk<KVRestorePlugin>()
    private val kvRestore = KVRestore(kvRestorePlugin, outputFactory, headerReader, cryptoImpl)
    private val fullRestorePlugin = mockk<FullRestorePlugin>()
    private val fullRestore = FullRestore(fullRestorePlugin, outputFactory, headerReader, cryptoImpl)
    private val restore = RestoreCoordinator(restorePlugin, kvRestore, fullRestore)

    private val backupDataInput = mockk<BackupDataInput>()
    private val fileDescriptor = mockk<ParcelFileDescriptor>(relaxed = true)
    private val token = DEFAULT_RESTORE_SET_TOKEN
    private val appData = ByteArray(42).apply { Random.nextBytes(this) }
    private val appData2 = ByteArray(1337).apply { Random.nextBytes(this) }
    private val key = "RestoreKey"
    private val key64 = key.encodeBase64()
    private val key2 = "RestoreKey2"
    private val key264 = key2.encodeBase64()

    init {
        every { backupPlugin.kvBackupPlugin } returns kvBackupPlugin
        every { backupPlugin.fullBackupPlugin } returns fullBackupPlugin
    }

    @Test
    fun `test key-value backup and restore with 2 records`() {
        val value = CapturingSlot<ByteArray>()
        val value2 = CapturingSlot<ByteArray>()
        val bOutputStream = ByteArrayOutputStream()
        val bOutputStream2 = ByteArrayOutputStream()

        // read one key/value record and write it to output stream
        every { kvBackupPlugin.hasDataForPackage(packageInfo) } returns false
        every { kvBackupPlugin.ensureRecordStorageForPackage(packageInfo) } just Runs
        every { inputFactory.getBackupDataInput(fileDescriptor) } returns backupDataInput
        every { backupDataInput.readNextHeader() } returns true andThen true andThen false
        every { backupDataInput.key } returns key andThen key2
        every { backupDataInput.dataSize } returns appData.size andThen appData2.size
        every { backupDataInput.readEntityData(capture(value), 0, appData.size) } answers {
            appData.copyInto(value.captured) // write the app data into the passed ByteArray
            appData.size
        }
        every { kvBackupPlugin.getOutputStreamForRecord(packageInfo, key64) } returns bOutputStream
        every { backupDataInput.readEntityData(capture(value2), 0, appData2.size) } answers {
            appData2.copyInto(value2.captured) // write the app data into the passed ByteArray
            appData2.size
        }
        every { kvBackupPlugin.getOutputStreamForRecord(packageInfo, key264) } returns bOutputStream2

        // start and finish K/V backup
        assertEquals(TRANSPORT_OK, backup.performIncrementalBackup(packageInfo, fileDescriptor, 0))
        assertEquals(TRANSPORT_OK, backup.finishBackup())

        // start restore
        assertEquals(TRANSPORT_OK, restore.startRestore(token, arrayOf(packageInfo)))

        // find data for K/V backup
        every { kvRestorePlugin.hasDataForPackage(token, packageInfo) } returns true

        val restoreDescription = restore.nextRestorePackage() ?: fail()
        assertEquals(packageInfo.packageName, restoreDescription.packageName)
        assertEquals(RestoreDescription.TYPE_KEY_VALUE, restoreDescription.dataType)

        // restore finds the backed up key and writes the decrypted value
        val backupDataOutput = mockk<BackupDataOutput>()
        val rInputStream = ByteArrayInputStream(bOutputStream.toByteArray())
        val rInputStream2 = ByteArrayInputStream(bOutputStream2.toByteArray())
        every { kvRestorePlugin.listRecords(token, packageInfo) } returns listOf(key64, key264)
        every { outputFactory.getBackupDataOutput(fileDescriptor) } returns backupDataOutput
        every { kvRestorePlugin.getInputStreamForRecord(token, packageInfo, key64) } returns rInputStream
        every { backupDataOutput.writeEntityHeader(key, appData.size) } returns 1137
        every { backupDataOutput.writeEntityData(appData, appData.size) } returns appData.size
        every { kvRestorePlugin.getInputStreamForRecord(token, packageInfo, key264) } returns rInputStream2
        every { backupDataOutput.writeEntityHeader(key2, appData2.size) } returns 1137
        every { backupDataOutput.writeEntityData(appData2, appData2.size) } returns appData2.size

        assertEquals(TRANSPORT_OK, restore.getRestoreData(fileDescriptor))
    }

    @Test
    fun `test full backup and restore with two chunks`() {
        // return streams from plugin and app data
        val bOutputStream = ByteArrayOutputStream()
        val bInputStream = ByteArrayInputStream(appData)
        every { fullBackupPlugin.getOutputStream(packageInfo) } returns bOutputStream
        every { inputFactory.getInputStream(fileDescriptor) } returns bInputStream
        every { fullBackupPlugin.getQuota() } returns DEFAULT_QUOTA_FULL_BACKUP

        // perform backup to output stream
        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, fileDescriptor, 0))
        assertEquals(TRANSPORT_OK, backup.sendBackupData(appData.size / 2))
        assertEquals(TRANSPORT_OK, backup.sendBackupData(appData.size / 2))
        assertEquals(TRANSPORT_OK, backup.finishBackup())

        // start restore
        assertEquals(TRANSPORT_OK, restore.startRestore(token, arrayOf(packageInfo)))

        // find data only for full backup
        every { kvRestorePlugin.hasDataForPackage(token, packageInfo) } returns false
        every { fullRestorePlugin.hasDataForPackage(token, packageInfo) } returns true

        val restoreDescription = restore.nextRestorePackage() ?: fail()
        assertEquals(packageInfo.packageName, restoreDescription.packageName)
        assertEquals(TYPE_FULL_STREAM, restoreDescription.dataType)

        // reverse the backup streams into restore input
        val rInputStream = ByteArrayInputStream(bOutputStream.toByteArray())
        val rOutputStream = ByteArrayOutputStream()
        every { fullRestorePlugin.getInputStreamForPackage(token, packageInfo) } returns rInputStream
        every { outputFactory.getOutputStream(fileDescriptor) } returns rOutputStream

        // restore data
        assertEquals(appData.size / 2, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertEquals(appData.size / 2, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertEquals(NO_MORE_DATA, restore.getNextFullRestoreDataChunk(fileDescriptor))
        restore.finishRestore()

        // assert that restored data matches original app data
        assertArrayEquals(appData, rOutputStream.toByteArray())
    }

}
