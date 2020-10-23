package com.stevesoltys.seedvault.transport.restore

import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.RestoreDescription
import android.app.backup.RestoreDescription.NO_MORE_PACKAGES
import android.app.backup.RestoreDescription.TYPE_FULL_STREAM
import android.app.backup.RestoreDescription.TYPE_KEY_VALUE
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import com.stevesoltys.seedvault.coAssertThrows
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.BackupMetadata
import com.stevesoltys.seedvault.metadata.EncryptedBackupMetadata
import com.stevesoltys.seedvault.metadata.MetadataReader
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.settings.Storage
import com.stevesoltys.seedvault.transport.TransportTest
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random

@Suppress("BlockingMethodInNonBlockingContext")
internal class RestoreCoordinatorTest : TransportTest() {

    private val notificationManager: BackupNotificationManager = mockk()
    private val plugin = mockk<RestorePlugin>()
    private val kv = mockk<KVRestore>()
    private val full = mockk<FullRestore>()
    private val metadataReader = mockk<MetadataReader>()

    private val restore = RestoreCoordinator(
        context,
        settingsManager,
        metadataManager,
        notificationManager,
        plugin,
        kv,
        full,
        metadataReader
    )

    private val inputStream = mockk<InputStream>()
    private val storage: Storage = mockk()
    private val packageInfo2 = PackageInfo().apply { packageName = "org.example2" }
    private val packageInfoArray = arrayOf(packageInfo)
    private val packageInfoArray2 = arrayOf(packageInfo, packageInfo2)
    private val pmPackageInfoArray = arrayOf(
        PackageInfo().apply { packageName = "@pm@" },
        packageInfo
    )
    private val packageName = packageInfo.packageName
    private val storageName = getRandomString()

    @Test
    fun `getAvailableRestoreSets() builds set from plugin response`() = runBlocking {
        val encryptedMetadata = EncryptedBackupMetadata(token, inputStream)
        val metadata = BackupMetadata(
            token = token,
            androidVersion = Random.nextInt(),
            androidIncremental = getRandomString(),
            deviceName = getRandomString()
        )

        coEvery { plugin.getAvailableBackups() } returns sequenceOf(
            encryptedMetadata,
            encryptedMetadata
        )
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
        every { settingsManager.getToken() } returns token
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
    fun `startRestore() can be be called again after restore finished`() {
        assertEquals(TRANSPORT_OK, restore.startRestore(token, packageInfoArray))

        every { full.hasState() } returns false
        restore.finishRestore()

        assertEquals(TRANSPORT_OK, restore.startRestore(token, packageInfoArray))
    }

    @Test
    fun `startRestore() optimized auto-restore with removed storage shows notification`() {
        every { settingsManager.getStorage() } returns storage
        every { storage.isUnavailableUsb(context) } returns true
        every { metadataManager.getPackageMetadata(packageName) } returns PackageMetadata(42L)
        every { storage.name } returns storageName
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
    fun `startRestore() optimized auto-restore with available storage shows no notification`() {
        every { settingsManager.getStorage() } returns storage
        every { storage.isUnavailableUsb(context) } returns false

        assertEquals(TRANSPORT_OK, restore.startRestore(token, pmPackageInfoArray))

        verify(exactly = 0) {
            notificationManager.onRemovableStorageNotAvailableForRestore(
                packageName,
                storageName
            )
        }
    }

    @Test
    fun `startRestore() with removed storage shows no notification`() {
        every { settingsManager.getStorage() } returns storage
        every { storage.isUnavailableUsb(context) } returns true
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
    fun `nextRestorePackage() returns KV description and takes precedence`() = runBlocking {
        restore.startRestore(token, packageInfoArray)

        coEvery { kv.hasDataForPackage(token, packageInfo) } returns true
        every { kv.initializeState(token, packageInfo) } just Runs

        val expected = RestoreDescription(packageInfo.packageName, TYPE_KEY_VALUE)
        assertEquals(expected, restore.nextRestorePackage())
    }

    @Test
    fun `nextRestorePackage() returns full description if no KV data found`() = runBlocking {
        restore.startRestore(token, packageInfoArray)

        coEvery { kv.hasDataForPackage(token, packageInfo) } returns false
        coEvery { full.hasDataForPackage(token, packageInfo) } returns true
        every { full.initializeState(token, packageInfo) } just Runs

        val expected = RestoreDescription(packageInfo.packageName, TYPE_FULL_STREAM)
        assertEquals(expected, restore.nextRestorePackage())
    }

    @Test
    fun `nextRestorePackage() returns NO_MORE_PACKAGES if data found`() = runBlocking {
        restore.startRestore(token, packageInfoArray)

        coEvery { kv.hasDataForPackage(token, packageInfo) } returns false
        coEvery { full.hasDataForPackage(token, packageInfo) } returns false

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    fun `nextRestorePackage() returns all packages from startRestore()`() = runBlocking {
        restore.startRestore(token, packageInfoArray2)

        coEvery { kv.hasDataForPackage(token, packageInfo) } returns true
        every { kv.initializeState(token, packageInfo) } just Runs

        val expected = RestoreDescription(packageInfo.packageName, TYPE_KEY_VALUE)
        assertEquals(expected, restore.nextRestorePackage())

        coEvery { kv.hasDataForPackage(token, packageInfo2) } returns false
        coEvery { full.hasDataForPackage(token, packageInfo2) } returns true
        every { full.initializeState(token, packageInfo2) } just Runs

        val expected2 = RestoreDescription(packageInfo2.packageName, TYPE_FULL_STREAM)
        assertEquals(expected2, restore.nextRestorePackage())

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    fun `when kv#hasDataForPackage() throws return null`() = runBlocking {
        restore.startRestore(token, packageInfoArray)

        coEvery { kv.hasDataForPackage(token, packageInfo) } throws IOException()

        assertNull(restore.nextRestorePackage())
    }

    @Test
    fun `when full#hasDataForPackage() throws return null`() = runBlocking {
        restore.startRestore(token, packageInfoArray)

        coEvery { kv.hasDataForPackage(token, packageInfo) } returns false
        coEvery { full.hasDataForPackage(token, packageInfo) } throws IOException()

        assertNull(restore.nextRestorePackage())
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
