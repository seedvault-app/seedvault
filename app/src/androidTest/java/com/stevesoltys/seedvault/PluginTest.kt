package com.stevesoltys.seedvault

import androidx.test.core.content.pm.PackageInfoBuilder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.plugins.saf.DocumentsProviderBackupPlugin
import com.stevesoltys.seedvault.plugins.saf.DocumentsProviderRestorePlugin
import com.stevesoltys.seedvault.plugins.saf.DocumentsStorage
import com.stevesoltys.seedvault.plugins.saf.deleteContents
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.backup.BackupPlugin
import com.stevesoltys.seedvault.transport.restore.RestorePlugin
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.KoinComponent
import org.koin.core.inject
import java.io.InputStream
import java.io.OutputStream
import kotlin.random.Random


@RunWith(AndroidJUnit4::class)
class PluginTest : KoinComponent {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val metadataManager: MetadataManager by inject()
    private val settingsManager: SettingsManager by inject()
    private val mockedSettingsManager: SettingsManager = mockk()
    private val storage = DocumentsStorage(context, metadataManager, mockedSettingsManager)
    private val backupPlugin: BackupPlugin = DocumentsProviderBackupPlugin(storage, context.packageManager)
    private val restorePlugin: RestorePlugin = DocumentsProviderRestorePlugin(context, storage)

    private val token = Random.nextLong()
    private val packageInfo = PackageInfoBuilder.newBuilder().setPackageName("org.example").build()
    private val packageInfo2 = PackageInfoBuilder.newBuilder().setPackageName("net.example").build()

    @Before
    fun setup() {
        every { mockedSettingsManager.getStorage() } returns settingsManager.getStorage()
        storage.rootBackupDir?.deleteContents()
                ?: error("Select a storage location in the app first!")
    }

    @After
    fun tearDown() {
        storage.rootBackupDir?.deleteContents()
    }

    @Test
    fun testProviderPackageName() {
        assertNotNull(backupPlugin.providerPackageName)
    }

    @Test
    fun testMetadataWriteRead() {
        every { mockedSettingsManager.getAndResetIsStorageChanging() } returns true andThen false
        assertTrue(backupPlugin.initializeDevice(newToken = token))

        // write metadata
        val metadata = getRandomByteArray()
        backupPlugin.getMetadataOutputStream().writeAndClose(metadata)

        // get available backups, expect only one with our token and no error
        var availableBackups = restorePlugin.getAvailableBackups()?.toList()
        check(availableBackups != null)
        assertEquals(1, availableBackups.size)
        assertEquals(token, availableBackups[0].token)
        assertFalse(availableBackups[0].error)

        // read metadata matches what was written earlier
        assertEquals(metadata, availableBackups[0].inputStream)

        // initializing again (without changing storage) keeps restore set with same token
        assertFalse(backupPlugin.initializeDevice(newToken = token + 1))
        availableBackups = restorePlugin.getAvailableBackups()?.toList()
        check(availableBackups != null)
        assertEquals(1, availableBackups.size)
        assertEquals(token, availableBackups[0].token)
        assertFalse(availableBackups[0].error)

        // metadata hasn't changed
        assertEquals(metadata, availableBackups[0].inputStream)
    }

    @Test
    fun testApkWriteRead() {
        // initialize storage with given token
        initStorage(token)

        // write random bytes as APK
        val apk = getRandomByteArray(1337)
        backupPlugin.getApkOutputStream(packageInfo).writeAndClose(apk)

        // assert that read APK bytes match what was written
        assertEquals(apk, restorePlugin.getApkInputStream(token, packageInfo.packageName))
    }

    @Test
    fun testKvBackupRestore() {
        // define shortcuts
        val kvBackup = backupPlugin.kvBackupPlugin
        val kvRestore = restorePlugin.kvRestorePlugin

        // initialize storage with given token
        initStorage(token)

        // no data available for given package
        assertFalse(kvBackup.hasDataForPackage(packageInfo))
        assertFalse(kvRestore.hasDataForPackage(token, packageInfo))

        // define key/value pair records
        val record1 = Pair(getRandomBase64(23), getRandomByteArray(1337))
        val record2 = Pair(getRandomBase64(42), getRandomByteArray(42 * 1024))
        val record3 = Pair(getRandomBase64(255), getRandomByteArray(5 * 1024 * 1024))

        // write first record
        kvBackup.ensureRecordStorageForPackage(packageInfo)
        kvBackup.getOutputStreamForRecord(packageInfo, record1.first).writeAndClose(record1.second)

        // data is now available for current token and given package, but not for different token
        assertTrue(kvBackup.hasDataForPackage(packageInfo))
        assertTrue(kvRestore.hasDataForPackage(token, packageInfo))
        assertFalse(kvRestore.hasDataForPackage(token + 1, packageInfo))

        // record for package is found and returned properly
        var records = kvRestore.listRecords(token, packageInfo)
        assertEquals(1, records.size)
        assertEquals(record1.first, records[0])
        assertEquals(record1.second, kvRestore.getInputStreamForRecord(token, packageInfo, record1.first))

        // write second and third record
        kvBackup.ensureRecordStorageForPackage(packageInfo)
        kvBackup.getOutputStreamForRecord(packageInfo, record2.first).writeAndClose(record2.second)
        kvBackup.getOutputStreamForRecord(packageInfo, record3.first).writeAndClose(record3.second)

        // all records for package are found and returned properly
        records = kvRestore.listRecords(token, packageInfo)
        assertEquals(listOf(record1.first, record2.first, record3.first).sorted(), records.sorted())
        assertEquals(record1.second, kvRestore.getInputStreamForRecord(token, packageInfo, record1.first))
        assertEquals(record2.second, kvRestore.getInputStreamForRecord(token, packageInfo, record2.first))
        assertEquals(record3.second, kvRestore.getInputStreamForRecord(token, packageInfo, record3.first))

        // delete record3 and ensure that the other two are still found
        kvBackup.deleteRecord(packageInfo, record3.first)
        records = kvRestore.listRecords(token, packageInfo)
        assertEquals(listOf(record1.first, record2.first).sorted(), records.sorted())

        // remove all data of package and ensure that it is gone
        kvBackup.removeDataOfPackage(packageInfo)
        assertFalse(kvBackup.hasDataForPackage(packageInfo))
        assertFalse(kvRestore.hasDataForPackage(token, packageInfo))
    }

    @Test
    fun testMaxKvKeyLength() {
        // define shortcuts
        val kvBackup = backupPlugin.kvBackupPlugin
        val kvRestore = restorePlugin.kvRestorePlugin

        // initialize storage with given token
        initStorage(token)

        // define record with maximum key length and one above the maximum
        val recordMax = Pair(getRandomBase64(255), getRandomByteArray(1024))
        val recordOver = Pair(getRandomBase64(256), getRandomByteArray(1024))

        // write max record
        kvBackup.ensureRecordStorageForPackage(packageInfo)
        kvBackup.getOutputStreamForRecord(packageInfo, recordMax.first).writeAndClose(recordMax.second)

        // max record is found correctly
        assertTrue(kvRestore.hasDataForPackage(token, packageInfo))
        var records = kvRestore.listRecords(token, packageInfo)
        assertEquals(listOf(recordMax.first), records)

        // write exceeding key length record
        kvBackup.ensureRecordStorageForPackage(packageInfo)
        kvBackup.getOutputStreamForRecord(packageInfo, recordOver.first).writeAndClose(recordOver.second)

        // exceeding record gets truncated
        assertTrue(kvRestore.hasDataForPackage(token, packageInfo))
        records = kvRestore.listRecords(token, packageInfo)
        assertNotEquals(listOf(recordMax.first, recordOver.first).sorted(), records.sorted())
    }

    @Test
    fun testFullBackupRestore() {
        // define shortcuts
        val fullBackup = backupPlugin.fullBackupPlugin
        val fullRestore = restorePlugin.fullRestorePlugin

        // initialize storage with given token
        initStorage(token)

        // no data available initially
        assertFalse(fullRestore.hasDataForPackage(token, packageInfo))
        assertFalse(fullRestore.hasDataForPackage(token, packageInfo2))

        // write full backup data
        val data = getRandomByteArray(5 * 1024 * 1024)
        fullBackup.getOutputStream(packageInfo).writeAndClose(data)

        // data is available now, but only this token
        assertTrue(fullRestore.hasDataForPackage(token, packageInfo))
        assertFalse(fullRestore.hasDataForPackage(token + 1, packageInfo))

        // restore data matches backed up data
        assertEquals(data, fullRestore.getInputStreamForPackage(token, packageInfo))

        // write and check data for second package
        val data2 = getRandomByteArray(5 * 1024 * 1024)
        fullBackup.getOutputStream(packageInfo2).writeAndClose(data2)
        assertTrue(fullRestore.hasDataForPackage(token, packageInfo2))
        assertEquals(data2, fullRestore.getInputStreamForPackage(token, packageInfo2))

        // remove data of first package again and ensure that no more data is found
        fullBackup.removeDataOfPackage(packageInfo)
        assertFalse(fullRestore.hasDataForPackage(token, packageInfo))

        // second package is still there
        assertTrue(fullRestore.hasDataForPackage(token, packageInfo2))

        // ensure that it gets deleted as well
        fullBackup.removeDataOfPackage(packageInfo2)
        assertFalse(fullRestore.hasDataForPackage(token, packageInfo2))
    }

    private fun initStorage(token: Long) {
        every { mockedSettingsManager.getAndResetIsStorageChanging() } returns true
        assertTrue(backupPlugin.initializeDevice(newToken = token))
    }

    private fun OutputStream.writeAndClose(data: ByteArray) = use {
        it.write(data)
    }

    private fun assertEquals(data: ByteArray, inputStream: InputStream?) = inputStream?.use {
        assertArrayEquals(data, it.readBytes())
    } ?: error("no input stream")

}
