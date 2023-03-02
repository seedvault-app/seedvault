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
import com.stevesoltys.seedvault.coAssertThrows
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.MetadataReader
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.plugins.EncryptedMetadata
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.plugins.saf.SafStorage
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random

internal class RestoreCoordinatorTest : TransportTest() {

    private val notificationManager: BackupNotificationManager = mockk()
    private val storagePluginManager: StoragePluginManager = mockk()
    private val plugin = mockk<StoragePlugin<*>>()
    private val kv = mockk<KVRestore>()
    private val full = mockk<FullRestore>()
    private val metadataReader = mockk<MetadataReader>()

    private val restore = RestoreCoordinator(
        context = context,
        crypto = crypto,
        settingsManager = settingsManager,
        metadataManager = metadataManager,
        notificationManager = notificationManager,
        pluginManager = storagePluginManager,
        kv = kv,
        full = full,
        metadataReader = metadataReader,
    )

    private val inputStream = mockk<InputStream>()
    private val safStorage: SafStorage = mockk()
    private val packageInfo2 = PackageInfo().apply { packageName = "org.example2" }
    private val packageInfoArray = arrayOf(packageInfo)
    private val packageInfoArray2 = arrayOf(packageInfo, packageInfo2)
    private val pmPackageInfoArray = arrayOf(
        PackageInfo().apply { packageName = "@pm@" },
        packageInfo
    )
    private val packageName = packageInfo.packageName
    private val storageName = getRandomString()

    init {
        metadata.packageMetadataMap[packageInfo2.packageName] =
            PackageMetadata(backupType = BackupType.FULL)

        every { storagePluginManager.appPlugin } returns plugin
    }

    @Test
    fun `getAvailableRestoreSets() builds set from plugin response`() = runBlocking {
        val encryptedMetadata = EncryptedMetadata(token) { inputStream }

        coEvery { plugin.getAvailableBackups() } returns sequenceOf(
            encryptedMetadata,
            EncryptedMetadata(token + 1) { inputStream }
        )
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
        every { settingsManager.getToken() } returns token
        assertEquals(token, restore.getCurrentRestoreSet())
    }

    @Test
    fun `startRestore() returns OK`() = runBlocking {
        restore.beforeStartRestore(metadata)
        assertEquals(TRANSPORT_OK, restore.startRestore(token, packageInfoArray))
    }

    @Test
    fun `startRestore() fetches metadata if missing`() = runBlocking {
        coEvery { plugin.getAvailableBackups() } returns sequenceOf(
            EncryptedMetadata(token) { inputStream },
            EncryptedMetadata(token + 1) { inputStream }
        )
        every { metadataReader.readMetadata(inputStream, token) } returns metadata
        every { metadataReader.readMetadata(inputStream, token + 1) } returns metadata
        every { inputStream.close() } just Runs

        assertEquals(TRANSPORT_OK, restore.startRestore(token, packageInfoArray))
    }

    @Test
    fun `startRestore() errors if metadata is not matching token`() = runBlocking {
        coEvery { plugin.getAvailableBackups() } returns sequenceOf(
            EncryptedMetadata(token + 42) { inputStream }
        )
        every { metadataReader.readMetadata(inputStream, token + 42) } returns metadata
        every { inputStream.close() } just Runs

        assertEquals(TRANSPORT_ERROR, restore.startRestore(token, packageInfoArray))
    }

    @Test
    fun `startRestore() can not be called twice`() = runBlocking {
        restore.beforeStartRestore(metadata)
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
        restore.beforeStartRestore(metadata)
        assertEquals(TRANSPORT_OK, restore.startRestore(token, packageInfoArray))

        every { full.hasState() } returns false
        restore.finishRestore()

        restore.beforeStartRestore(metadata)
        assertEquals(TRANSPORT_OK, restore.startRestore(token, packageInfoArray))
    }

    @Test
    fun `startRestore() optimized auto-restore with removed storage shows notification`() =
        runBlocking {
            every { storagePluginManager.storageProperties } returns safStorage
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
            every { storagePluginManager.storageProperties } returns safStorage
            every { safStorage.isUnavailableUsb(context) } returns false

            restore.beforeStartRestore(metadata)
            assertEquals(TRANSPORT_OK, restore.startRestore(token, pmPackageInfoArray))

            verify(exactly = 0) {
                notificationManager.onRemovableStorageNotAvailableForRestore(
                    packageName,
                    storageName
                )
            }
        }

    @Test
    fun `startRestore() with removed storage shows no notification`() = runBlocking {
        every { storagePluginManager.storageProperties } returns safStorage
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
    fun `nextRestorePackage() returns KV description`() = runBlocking {
        restore.beforeStartRestore(metadata)
        restore.startRestore(token, packageInfoArray)

        every { crypto.getNameForPackage(metadata.salt, packageName) } returns name
        coEvery { plugin.hasData(token, name) } returns true
        every { kv.initializeState(VERSION, token, name, packageInfo) } just Runs

        val expected = RestoreDescription(packageName, TYPE_KEY_VALUE)
        assertEquals(expected, restore.nextRestorePackage())
    }

    @Test
    @Suppress("Deprecation")
    fun `v0 nextRestorePackage() returns KV description and takes precedence`() = runBlocking {
        restore.beforeStartRestore(metadata.copy(version = 0x00))
        restore.startRestore(token, packageInfoArray)

        coEvery { kv.hasDataForPackage(token, packageInfo) } returns true
        every { kv.initializeState(0x00, token, "", packageInfo) } just Runs

        val expected = RestoreDescription(packageInfo.packageName, TYPE_KEY_VALUE)
        assertEquals(expected, restore.nextRestorePackage())
    }

    @Test
    @Suppress("deprecation")
    fun `v0 nextRestorePackage() returns full description if no KV data found`() = runBlocking {
        restore.beforeStartRestore(metadata.copy(version = 0x00))
        restore.startRestore(token, packageInfoArray)

        coEvery { kv.hasDataForPackage(token, packageInfo) } returns false
        coEvery { full.hasDataForPackage(token, packageInfo) } returns true
        every { full.initializeState(0x00, token, "", packageInfo) } just Runs

        val expected = RestoreDescription(packageInfo.packageName, TYPE_FULL_STREAM)
        assertEquals(expected, restore.nextRestorePackage())
    }

    @Test
    fun `nextRestorePackage() returns NO_MORE_PACKAGES if data not found`() = runBlocking {
        restore.beforeStartRestore(metadata)
        restore.startRestore(token, packageInfoArray2)

        every { crypto.getNameForPackage(metadata.salt, packageName) } returns name
        coEvery { plugin.hasData(token, name) } returns false
        every { crypto.getNameForPackage(metadata.salt, packageInfo2.packageName) } returns name2
        coEvery { plugin.hasData(token, name2) } returns false

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    fun `nextRestorePackage() tries next package if one has no backup type()`() = runBlocking {
        metadata.packageMetadataMap[packageName] =
            metadata.packageMetadataMap[packageName]!!.copy(backupType = null)
        restore.beforeStartRestore(metadata)
        restore.startRestore(token, packageInfoArray2)

        every { crypto.getNameForPackage(metadata.salt, packageInfo2.packageName) } returns name2
        coEvery { plugin.hasData(token, name2) } returns true
        every { full.initializeState(VERSION, token, name2, packageInfo2) } just Runs

        val expected = RestoreDescription(packageInfo2.packageName, TYPE_FULL_STREAM)
        assertEquals(expected, restore.nextRestorePackage())

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    fun `nextRestorePackage() returns all packages from startRestore()`() = runBlocking {
        restore.beforeStartRestore(metadata)
        restore.startRestore(token, packageInfoArray2)

        every { crypto.getNameForPackage(metadata.salt, packageName) } returns name
        coEvery { plugin.hasData(token, name) } returns true
        every { kv.initializeState(VERSION, token, name, packageInfo) } just Runs

        val expected = RestoreDescription(packageInfo.packageName, TYPE_KEY_VALUE)
        assertEquals(expected, restore.nextRestorePackage())

        every { crypto.getNameForPackage(metadata.salt, packageInfo2.packageName) } returns name2
        coEvery { plugin.hasData(token, name2) } returns true
        every { full.initializeState(VERSION, token, name2, packageInfo2) } just Runs

        val expected2 =
            RestoreDescription(packageInfo2.packageName, TYPE_FULL_STREAM)
        assertEquals(expected2, restore.nextRestorePackage())

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    @Suppress("deprecation")
    fun `v0 nextRestorePackage() returns all packages from startRestore()`() = runBlocking {
        restore.beforeStartRestore(metadata.copy(version = 0x00))
        restore.startRestore(token, packageInfoArray2)

        coEvery { kv.hasDataForPackage(token, packageInfo) } returns true
        every { kv.initializeState(0.toByte(), token, "", packageInfo) } just Runs

        val expected = RestoreDescription(packageInfo.packageName, TYPE_KEY_VALUE)
        assertEquals(expected, restore.nextRestorePackage())

        coEvery { kv.hasDataForPackage(token, packageInfo2) } returns false
        coEvery { full.hasDataForPackage(token, packageInfo2) } returns true
        every { full.initializeState(0.toByte(), token, "", packageInfo2) } just Runs

        val expected2 = RestoreDescription(packageInfo2.packageName, TYPE_FULL_STREAM)
        assertEquals(expected2, restore.nextRestorePackage())

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    @Suppress("Deprecation")
    fun `v0 when kv#hasDataForPackage() throws, it tries next package`() = runBlocking {
        restore.beforeStartRestore(metadata.copy(version = 0x00))
        restore.startRestore(token, packageInfoArray)

        coEvery { kv.hasDataForPackage(token, packageInfo) } throws IOException()

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    fun `when plugin#hasData() throws, it tries next package`() = runBlocking {
        restore.beforeStartRestore(metadata)
        restore.startRestore(token, packageInfoArray2)

        every { crypto.getNameForPackage(metadata.salt, packageName) } returns name
        coEvery { plugin.hasData(token, name) } returns false
        every { crypto.getNameForPackage(metadata.salt, packageInfo2.packageName) } returns name2
        coEvery { plugin.hasData(token, name2) } throws IOException()

        assertEquals(NO_MORE_PACKAGES, restore.nextRestorePackage())
    }

    @Test
    @Suppress("deprecation")
    fun `v0 when full#hasDataForPackage() throws, it tries next package`() = runBlocking {
        restore.beforeStartRestore(metadata.copy(version = 0x00))
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
