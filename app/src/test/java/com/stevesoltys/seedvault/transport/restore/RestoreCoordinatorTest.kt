package com.stevesoltys.seedvault.transport.restore

import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.RestoreDescription
import android.app.backup.RestoreDescription.*
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.BackupMetadata
import com.stevesoltys.seedvault.metadata.EncryptedBackupMetadata
import com.stevesoltys.seedvault.metadata.MetadataReader
import com.stevesoltys.seedvault.transport.TransportTest
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random

internal class RestoreCoordinatorTest : TransportTest() {

    private val plugin = mockk<RestorePlugin>()
    private val kv = mockk<KVRestore>()
    private val full = mockk<FullRestore>()
    private val metadataReader = mockk<MetadataReader>()

    private val restore = RestoreCoordinator(settingsManager, plugin, kv, full, metadataReader)

    private val token = Random.nextLong()
    private val inputStream = mockk<InputStream>()
    private val packageInfo2 = PackageInfo().apply { packageName = "org.example2" }
    private val packageInfoArray = arrayOf(packageInfo)
    private val packageInfoArray2 = arrayOf(packageInfo, packageInfo2)

    @Test
    fun `getAvailableRestoreSets() builds set from plugin response`() {
        val encryptedMetadata = EncryptedBackupMetadata(token, inputStream)
        val metadata = BackupMetadata(
                token = token,
                androidVersion = Random.nextInt(),
                deviceName = getRandomString())

        every { plugin.getAvailableBackups() } returns sequenceOf(encryptedMetadata, encryptedMetadata)
        every { metadataReader.readMetadata(inputStream, token) } returns metadata
        every { inputStream.close() } just Runs

        val sets = restore.getAvailableRestoreSets() ?: fail()
        assertEquals(2, sets.size)
        assertEquals(metadata.deviceName, sets[0].device)
        assertEquals(metadata.deviceName, sets[0].name)
        assertEquals(metadata.token, sets[0].token)
    }

    @Test
    fun `getCurrentRestoreSet() delegates to plugin`() {
        every { settingsManager.getBackupToken() } returns token
        assertEquals(token, restore.getCurrentRestoreSet())
    }

    @Test
    fun `startRestore() returns OK`() {
        assertEquals(TRANSPORT_OK, restore.startRestore(token, packageInfoArray))
    }

    @Test
    fun `startRestore() can not be called twice`() {
        assertEquals(TRANSPORT_OK, restore.startRestore(token, packageInfoArray))
        assertThrows(IllegalStateException::class.javaObjectType) {
            restore.startRestore(token, packageInfoArray)
        }
    }

    @Test
    fun `nextRestorePackage() throws without startRestore()`() {
        assertThrows(IllegalStateException::class.javaObjectType) {
            restore.nextRestorePackage()
        }
    }

    @Test
    fun `nextRestorePackage() returns KV description and takes precedence`() {
        restore.startRestore(token, packageInfoArray)

        every { kv.hasDataForPackage(token, packageInfo) } returns true
        every { kv.initializeState(token, packageInfo) } just Runs

        val expected = RestoreDescription(packageInfo.packageName, TYPE_KEY_VALUE)
        assertEquals(expected, restore.nextRestorePackage())
    }

    @Test
    fun `nextRestorePackage() returns full description if no KV data found`() {
        restore.startRestore(token, packageInfoArray)

        every { kv.hasDataForPackage(token, packageInfo) } returns false
        every { full.hasDataForPackage(token, packageInfo) } returns true
        every { full.initializeState(token, packageInfo) } just Runs

        val expected = RestoreDescription(packageInfo.packageName, TYPE_FULL_STREAM)
        assertEquals(expected, restore.nextRestorePackage())
    }

    @Test
    fun `nextRestorePackage() returns NO_MORE_PACKAGES if data found`() {
        restore.startRestore(token, packageInfoArray)

        every { kv.hasDataForPackage(token, packageInfo) } returns false
        every { full.hasDataForPackage(token, packageInfo) } returns false

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    fun `nextRestorePackage() returns all packages from startRestore()`() {
        restore.startRestore(token, packageInfoArray2)

        every { kv.hasDataForPackage(token, packageInfo) } returns true
        every { kv.initializeState(token, packageInfo) } just Runs

        val expected = RestoreDescription(packageInfo.packageName, TYPE_KEY_VALUE)
        assertEquals(expected, restore.nextRestorePackage())

        every { kv.hasDataForPackage(token, packageInfo2) } returns false
        every { full.hasDataForPackage(token, packageInfo2) } returns true
        every { full.initializeState(token, packageInfo2) } just Runs

        val expected2 = RestoreDescription(packageInfo2.packageName, TYPE_FULL_STREAM)
        assertEquals(expected2, restore.nextRestorePackage())

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    fun `when kv#hasDataForPackage() throws return null`() {
        restore.startRestore(token, packageInfoArray)

        every { kv.hasDataForPackage(token, packageInfo) } throws IOException()

        assertNull(restore.nextRestorePackage())
    }

    @Test
    fun `when full#hasDataForPackage() throws return null`() {
        restore.startRestore(token, packageInfoArray)

        every { kv.hasDataForPackage(token, packageInfo) } returns false
        every { full.hasDataForPackage(token, packageInfo) } throws IOException()

        assertNull(restore.nextRestorePackage())
    }

    @Test
    fun `getRestoreData() delegates to KV`() {
        val data = mockk<ParcelFileDescriptor>()
        val result = Random.nextInt()

        every { kv.getRestoreData(data) } returns result

        assertEquals(result, restore.getRestoreData(data))
    }

    @Test
    fun `getNextFullRestoreDataChunk() delegates to Full`() {
        val data = mockk<ParcelFileDescriptor>()
        val result = Random.nextInt()

        every { full.getNextFullRestoreDataChunk(data) } returns result

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

        every { full.hasState() } returns hasState
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
