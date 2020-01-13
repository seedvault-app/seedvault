package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupTransport.*
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.seedvault.BackupNotificationManager
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageState.*
import com.stevesoltys.seedvault.settings.Storage
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.OutputStream
import kotlin.random.Random

internal class BackupCoordinatorTest : BackupTest() {

    private val plugin = mockk<BackupPlugin>()
    private val kv = mockk<KVBackup>()
    private val full = mockk<FullBackup>()
    private val apkBackup = mockk<ApkBackup>()
    private val packageService: PackageService = mockk()
    private val notificationManager = mockk<BackupNotificationManager>()

    private val backup = BackupCoordinator(context, plugin, kv, full, apkBackup, clock, packageService, metadataManager, settingsManager, notificationManager)

    private val metadataOutputStream = mockk<OutputStream>()
    private val fileDescriptor: ParcelFileDescriptor = mockk()
    private val packageMetadata: PackageMetadata = mockk()
    private val storage = Storage(Uri.EMPTY, getRandomString(), false)

    @Test
    fun `device initialization succeeds and delegates to plugin`() {
        every { clock.time() } returns token
        every { plugin.initializeDevice(token) } returns true // TODO test when false
        every { plugin.getMetadataOutputStream() } returns metadataOutputStream
        every { metadataManager.onDeviceInitialization(token, metadataOutputStream) } just Runs
        every { kv.hasState() } returns false
        every { full.hasState() } returns false

        assertEquals(TRANSPORT_OK, backup.initializeDevice())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
    }

    @Test
    fun `device initialization does no-op when already initialized`() {
        every { clock.time() } returns token
        every { plugin.initializeDevice(token) } returns false
        every { kv.hasState() } returns false
        every { full.hasState() } returns false

        assertEquals(TRANSPORT_OK, backup.initializeDevice())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
    }

    @Test
    fun `error notification when device initialization fails`() {
        every { clock.time() } returns token
        every { plugin.initializeDevice(token) } throws IOException()
        every { settingsManager.getStorage() } returns storage
        every { notificationManager.onBackupError() } just Runs

        assertEquals(TRANSPORT_ERROR, backup.initializeDevice())

        // finish will only be called when TRANSPORT_OK is returned, so it should throw
        every { kv.hasState() } returns false
        every { full.hasState() } returns false
        assertThrows(IllegalStateException::class.java) {
            backup.finishBackup()
        }
    }

    @Test
    fun `no error notification when device initialization fails on unplugged USB storage`() {
        val storage = mockk<Storage>()
        val documentFile = mockk<DocumentFile>()

        every { clock.time() } returns token
        every { plugin.initializeDevice(token) } throws IOException()
        every { settingsManager.getStorage() } returns storage
        every { storage.isUsb } returns true
        every { storage.getDocumentFile(context) } returns documentFile
        every { documentFile.isDirectory } returns false

        assertEquals(TRANSPORT_ERROR, backup.initializeDevice())

        // finish will only be called when TRANSPORT_OK is returned, so it should throw
        every { kv.hasState() } returns false
        every { full.hasState() } returns false
        assertThrows(IllegalStateException::class.java) {
            backup.finishBackup()
        }
    }

    @Test
    fun `getBackupQuota() delegates to right plugin`() {
        val isFullBackup = Random.nextBoolean()
        val quota = Random.nextLong()

        if (isFullBackup) {
            every { full.getQuota() } returns quota
        } else {
            every { kv.getQuota() } returns quota
        }
        assertEquals(quota, backup.getBackupQuota(packageInfo.packageName, isFullBackup))
    }

    @Test
    fun `clearing KV backup data throws`() {
        every { kv.clearBackupData(packageInfo) } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.clearBackupData(packageInfo))
    }

    @Test
    fun `clearing full backup data throws`() {
        every { kv.clearBackupData(packageInfo) } just Runs
        every { full.clearBackupData(packageInfo) } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.clearBackupData(packageInfo))
    }

    @Test
    fun `clearing backup data succeeds`() {
        every { kv.clearBackupData(packageInfo) } just Runs
        every { full.clearBackupData(packageInfo) } just Runs

        assertEquals(TRANSPORT_OK, backup.clearBackupData(packageInfo))

        every { kv.hasState() } returns false
        every { full.hasState() } returns false

        assertEquals(TRANSPORT_OK, backup.finishBackup())
    }

    @Test
    fun `finish backup delegates to KV plugin if it has state`() {
        val result = Random.nextInt()

        every { kv.hasState() } returns true
        every { full.hasState() } returns false
        every { kv.getCurrentPackage() } returns packageInfo
        every { plugin.getMetadataOutputStream() } returns metadataOutputStream
        every { metadataManager.onPackageBackedUp(packageInfo.packageName, metadataOutputStream) } just Runs
        every { kv.finishBackup() } returns result

        assertEquals(result, backup.finishBackup())
    }

    @Test
    fun `finish backup delegates to full plugin if it has state`() {
        val result = Random.nextInt()

        every { kv.hasState() } returns false
        every { full.hasState() } returns true
        every { full.getCurrentPackage() } returns packageInfo
        every { plugin.getMetadataOutputStream() } returns metadataOutputStream
        every { metadataManager.onPackageBackedUp(packageInfo.packageName, metadataOutputStream) } just Runs
        every { full.finishBackup() } returns result

        assertEquals(result, backup.finishBackup())
    }

    @Test
    fun `metadata does not get updated when no APK was backed up`() {
        every { full.performFullBackup(packageInfo, fileDescriptor, 0) } returns TRANSPORT_OK
        every { apkBackup.backupApkIfNecessary(packageInfo, UNKNOWN_ERROR, any()) } returns null

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, fileDescriptor, 0))
    }

    @Test
    fun `app exceeding quota gets cancelled and reason written to metadata`() {
        every { full.performFullBackup(packageInfo, fileDescriptor, 0) } returns TRANSPORT_OK
        expectApkBackupAndMetadataWrite()
        every { full.getQuota() } returns DEFAULT_QUOTA_FULL_BACKUP
        every { full.checkFullBackupSize(DEFAULT_QUOTA_FULL_BACKUP + 1) } returns TRANSPORT_QUOTA_EXCEEDED
        every { full.getCurrentPackage() } returns packageInfo
        every { metadataManager.onPackageBackupError(packageInfo.packageName, QUOTA_EXCEEDED, metadataOutputStream) } just Runs
        every { full.cancelFullBackup() } just Runs
        every { settingsManager.getStorage() } returns storage

        assertEquals(TRANSPORT_OK,
                backup.performFullBackup(packageInfo, fileDescriptor, 0))
        assertEquals(DEFAULT_QUOTA_FULL_BACKUP,
                backup.getBackupQuota(packageInfo.packageName, true))
        assertEquals(TRANSPORT_QUOTA_EXCEEDED,
                backup.checkFullBackupSize(DEFAULT_QUOTA_FULL_BACKUP + 1))
        backup.cancelFullBackup()
        assertEquals(0L, backup.requestFullBackupTime())

        verify(exactly = 1) {
            metadataManager.onPackageBackupError(packageInfo.packageName, QUOTA_EXCEEDED, metadataOutputStream)
        }
    }

    @Test
    fun `app with no data gets cancelled and reason written to metadata`() {
        every { full.performFullBackup(packageInfo, fileDescriptor, 0) } returns TRANSPORT_OK
        expectApkBackupAndMetadataWrite()
        every { full.getQuota() } returns DEFAULT_QUOTA_FULL_BACKUP
        every { full.checkFullBackupSize(0) } returns TRANSPORT_PACKAGE_REJECTED
        every { full.getCurrentPackage() } returns packageInfo
        every { metadataManager.onPackageBackupError(packageInfo.packageName, NO_DATA, metadataOutputStream) } just Runs
        every { full.cancelFullBackup() } just Runs
        every { settingsManager.getStorage() } returns storage

        assertEquals(TRANSPORT_OK,
                backup.performFullBackup(packageInfo, fileDescriptor, 0))
        assertEquals(DEFAULT_QUOTA_FULL_BACKUP,
                backup.getBackupQuota(packageInfo.packageName, true))
        assertEquals(TRANSPORT_PACKAGE_REJECTED, backup.checkFullBackupSize(0))
        backup.cancelFullBackup()
        assertEquals(0L, backup.requestFullBackupTime())

        verify(exactly = 1) {
            metadataManager.onPackageBackupError(packageInfo.packageName, NO_DATA, metadataOutputStream)
        }
    }

    @Test
    fun `not allowed apps get their APKs backed up during @pm@ backup`() {
        val packageInfo = PackageInfo().apply { packageName = MAGIC_PACKAGE_MANAGER }
        val packageName1 = "org.example.1"
        val packageName2 = "org.example.2"
        val notAllowedPackages = listOf(
                PackageInfo().apply { packageName = packageName1 },
                PackageInfo().apply { packageName = packageName2 }
        )
        val packageMetadata: PackageMetadata = mockk()

        every { settingsManager.getStorage() } returns storage  // to check for removable storage
        every { packageService.notAllowedPackages } returns notAllowedPackages
        // no backup needed
        every { apkBackup.backupApkIfNecessary(notAllowedPackages[0], NOT_ALLOWED, any()) } returns null
        // was backed up, get new packageMetadata
        every { apkBackup.backupApkIfNecessary(notAllowedPackages[1], NOT_ALLOWED, any()) } returns packageMetadata
        every { plugin.getMetadataOutputStream() } returns metadataOutputStream
        every { metadataManager.onApkBackedUp(packageName2, packageMetadata, metadataOutputStream) } just Runs
        // do actual @pm@ backup
        every { kv.performBackup(packageInfo, fileDescriptor, 0) } returns TRANSPORT_OK

        assertEquals(TRANSPORT_OK,
                backup.performIncrementalBackup(packageInfo, fileDescriptor, 0))

        verify {
            apkBackup.backupApkIfNecessary(notAllowedPackages[0], NOT_ALLOWED, any())
            apkBackup.backupApkIfNecessary(notAllowedPackages[1], NOT_ALLOWED, any())
        }
    }

    private fun expectApkBackupAndMetadataWrite() {
        every { apkBackup.backupApkIfNecessary(packageInfo, UNKNOWN_ERROR, any()) } returns packageMetadata
        every { plugin.getMetadataOutputStream() } returns metadataOutputStream
        every { metadataManager.onApkBackedUp(packageInfo.packageName, packageMetadata, metadataOutputStream) } just Runs
    }

}
