package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_NOT_INITIALIZED
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import android.app.backup.BackupTransport.TRANSPORT_QUOTA_EXCEEDED
import android.content.pm.ApplicationInfo.FLAG_STOPPED
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.coAssertThrows
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageState.NOT_ALLOWED
import com.stevesoltys.seedvault.metadata.PackageState.NO_DATA
import com.stevesoltys.seedvault.metadata.PackageState.QUOTA_EXCEEDED
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.metadata.PackageState.WAS_STOPPED
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.saf.FILE_BACKUP_METADATA
import com.stevesoltys.seedvault.settings.Storage
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.OutputStream
import kotlin.random.Random

@Suppress("BlockingMethodInNonBlockingContext")
internal class BackupCoordinatorTest : BackupTest() {

    private val plugin = mockk<StoragePlugin>()
    private val kv = mockk<KVBackup>()
    private val full = mockk<FullBackup>()
    private val apkBackup = mockk<ApkBackup>()
    private val packageService: PackageService = mockk()
    private val notificationManager = mockk<BackupNotificationManager>()

    private val backup = BackupCoordinator(
        context,
        plugin,
        kv,
        full,
        apkBackup,
        clock,
        packageService,
        metadataManager,
        settingsManager,
        notificationManager
    )

    private val metadataOutputStream = mockk<OutputStream>()
    private val fileDescriptor: ParcelFileDescriptor = mockk()
    private val packageMetadata: PackageMetadata = mockk()
    private val storage = Storage(
        uri = Uri.EMPTY,
        name = getRandomString(),
        isUsb = false,
        requiresNetwork = false
    )

    @Test
    fun `device initialization succeeds and delegates to plugin`() = runBlocking {
        expectStartNewRestoreSet()
        coEvery { plugin.initializeDevice() } just Runs
        coEvery { plugin.getOutputStream(token, FILE_BACKUP_METADATA) } returns metadataOutputStream
        every { metadataManager.onDeviceInitialization(token, metadataOutputStream) } just Runs
        every { kv.hasState() } returns false
        every { full.hasState() } returns false
        every { metadataOutputStream.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.initializeDevice())
        assertEquals(TRANSPORT_OK, backup.finishBackup())

        verify { metadataOutputStream.close() }
    }

    private suspend fun expectStartNewRestoreSet() {
        every { clock.time() } returns token
        every { settingsManager.setNewToken(token) } just Runs
        coEvery { plugin.startNewRestoreSet(token) } just Runs
    }

    @Test
    fun `error notification when device initialization fails`() = runBlocking {
        val maybeTrue = Random.nextBoolean()

        expectStartNewRestoreSet()
        coEvery { plugin.initializeDevice() } throws IOException()
        every { metadataManager.requiresInit } returns maybeTrue
        every { settingsManager.canDoBackupNow() } returns !maybeTrue
        every { notificationManager.onBackupError() } just Runs

        assertEquals(TRANSPORT_ERROR, backup.initializeDevice())

        // finish will only be called when TRANSPORT_OK is returned, so it should throw
        every { kv.hasState() } returns false
        every { full.hasState() } returns false
        coAssertThrows(IllegalStateException::class.java) {
            backup.finishBackup()
        }
    }

    @Test
    fun `no error notification when device initialization fails when no backup possible`() =
        runBlocking {
            expectStartNewRestoreSet()
            coEvery { plugin.initializeDevice() } throws IOException()
            every { metadataManager.requiresInit } returns false
            every { settingsManager.canDoBackupNow() } returns false

            assertEquals(TRANSPORT_ERROR, backup.initializeDevice())

            // finish will only be called when TRANSPORT_OK is returned, so it should throw
            every { kv.hasState() } returns false
            every { full.hasState() } returns false
            coAssertThrows(IllegalStateException::class.java) {
                backup.finishBackup()
            }
        }

    @Test
    fun `performIncrementalBackup of @pm@ causes re-init when legacy format`() = runBlocking {
        val packageInfo = PackageInfo().apply { packageName = MAGIC_PACKAGE_MANAGER }

        every { settingsManager.canDoBackupNow() } returns true
        every { metadataManager.requiresInit } returns true

        // start new restore set
        every { clock.time() } returns token + 1
        every { settingsManager.setNewToken(token + 1) } just Runs
        coEvery { plugin.startNewRestoreSet(token + 1) } just Runs

        every { data.close() } just Runs

        // returns TRANSPORT_NOT_INITIALIZED to re-init next time
        assertEquals(
            TRANSPORT_NOT_INITIALIZED,
            backup.performIncrementalBackup(packageInfo, data, 0)
        )
    }

    @Test
    fun `getBackupQuota() delegates to right plugin`() = runBlocking {
        val isFullBackup = Random.nextBoolean()
        val quota = Random.nextLong()

        expectApkBackupAndMetadataWrite()
        if (isFullBackup) {
            every { full.getQuota() } returns quota
        } else {
            every { kv.getQuota() } returns quota
        }
        every { metadataOutputStream.close() } just Runs
        assertEquals(quota, backup.getBackupQuota(packageInfo.packageName, isFullBackup))

        verify { metadataOutputStream.close() }
    }

    @Test
    fun `isAppEligibleForBackup() exempts plugin provider and blacklisted apps`() {
        every {
            settingsManager.isBackupEnabled(packageInfo.packageName)
        } returns true andThen false andThen true
        every {
            plugin.providerPackageName
        } returns packageInfo.packageName andThen "new.package" andThen "new.package"

        assertFalse(backup.isAppEligibleForBackup(packageInfo, true))
        assertFalse(backup.isAppEligibleForBackup(packageInfo, true))
        assertTrue(backup.isAppEligibleForBackup(packageInfo, true))
    }

    @Test
    fun `clearing KV backup data throws`() = runBlocking {
        every { settingsManager.getToken() } returns token
        every { metadataManager.salt } returns salt
        coEvery { kv.clearBackupData(packageInfo, token, salt) } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.clearBackupData(packageInfo))
    }

    @Test
    fun `clearing full backup data throws`() = runBlocking {
        every { settingsManager.getToken() } returns token
        every { metadataManager.salt } returns salt
        coEvery { kv.clearBackupData(packageInfo, token, salt) } just Runs
        coEvery { full.clearBackupData(packageInfo, token, salt) } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.clearBackupData(packageInfo))
    }

    @Test
    fun `clearing backup data succeeds`() = runBlocking {
        every { settingsManager.getToken() } returns token
        every { metadataManager.salt } returns salt
        coEvery { kv.clearBackupData(packageInfo, token, salt) } just Runs
        coEvery { full.clearBackupData(packageInfo, token, salt) } just Runs

        assertEquals(TRANSPORT_OK, backup.clearBackupData(packageInfo))

        every { kv.hasState() } returns false
        every { full.hasState() } returns false

        assertEquals(TRANSPORT_OK, backup.finishBackup())
    }

    @Test
    fun `finish backup delegates to KV plugin if it has state`() = runBlocking {
        every { kv.hasState() } returns true
        every { full.hasState() } returns false
        every { kv.getCurrentPackage() } returns packageInfo
        coEvery { kv.finishBackup() } returns TRANSPORT_OK
        every { settingsManager.getToken() } returns token
        coEvery { plugin.getOutputStream(token, FILE_BACKUP_METADATA) } returns metadataOutputStream
        every {
            metadataManager.onPackageBackedUp(packageInfo, BackupType.KV, metadataOutputStream)
        } just Runs
        every { metadataOutputStream.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.finishBackup())

        verify { metadataOutputStream.close() }
    }

    @Test
    fun `finish backup does not upload @pm@ metadata, if it can't do backups`() = runBlocking {
        every { kv.hasState() } returns true
        every { full.hasState() } returns false
        every { kv.getCurrentPackage() } returns pmPackageInfo

        coEvery { kv.finishBackup() } returns TRANSPORT_OK
        every { settingsManager.canDoBackupNow() } returns false

        assertEquals(TRANSPORT_OK, backup.finishBackup())
    }

    @Test
    fun `finish backup delegates to full plugin if it has state`() = runBlocking {
        val result = Random.nextInt()

        every { kv.hasState() } returns false
        every { full.hasState() } returns true
        every { full.getCurrentPackage() } returns packageInfo
        every { full.finishBackup() } returns result
        every { settingsManager.getToken() } returns token
        coEvery { plugin.getOutputStream(token, FILE_BACKUP_METADATA) } returns metadataOutputStream
        every {
            metadataManager.onPackageBackedUp(packageInfo, BackupType.FULL, metadataOutputStream)
        } just Runs
        every { metadataOutputStream.close() } just Runs

        assertEquals(result, backup.finishBackup())

        verify { metadataOutputStream.close() }
    }

    @Test
    fun `metadata does not get updated when no APK was backed up`() = runBlocking {
        every { settingsManager.getToken() } returns token
        every { metadataManager.salt } returns salt
        coEvery {
            full.performFullBackup(packageInfo, fileDescriptor, 0, token, salt)
        } returns TRANSPORT_OK
        coEvery { apkBackup.backupApkIfNecessary(packageInfo, UNKNOWN_ERROR, any()) } returns null

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, fileDescriptor, 0))
    }

    @Test
    fun `app exceeding quota gets cancelled and reason written to metadata`() = runBlocking {
        every { settingsManager.getToken() } returns token
        every { metadataManager.salt } returns salt
        coEvery {
            full.performFullBackup(packageInfo, fileDescriptor, 0, token, salt)
        } returns TRANSPORT_OK
        expectApkBackupAndMetadataWrite()
        every { full.getQuota() } returns DEFAULT_QUOTA_FULL_BACKUP
        every {
            full.checkFullBackupSize(DEFAULT_QUOTA_FULL_BACKUP + 1)
        } returns TRANSPORT_QUOTA_EXCEEDED
        every { full.getCurrentPackage() } returns packageInfo
        every {
            metadataManager.onPackageBackupError(
                packageInfo,
                QUOTA_EXCEEDED,
                metadataOutputStream,
                BackupType.FULL
            )
        } just Runs
        coEvery { full.cancelFullBackup(token, metadata.salt, false) } just Runs
        every { settingsManager.getStorage() } returns storage
        every { metadataOutputStream.close() } just Runs

        assertEquals(
            TRANSPORT_OK,
            backup.performFullBackup(packageInfo, fileDescriptor, 0)
        )
        assertEquals(
            DEFAULT_QUOTA_FULL_BACKUP,
            backup.getBackupQuota(packageInfo.packageName, true)
        )
        assertEquals(
            TRANSPORT_QUOTA_EXCEEDED,
            backup.checkFullBackupSize(DEFAULT_QUOTA_FULL_BACKUP + 1)
        )
        backup.cancelFullBackup()
        assertEquals(0L, backup.requestFullBackupTime())

        verify(exactly = 1) {
            metadataManager.onPackageBackupError(
                packageInfo,
                QUOTA_EXCEEDED,
                metadataOutputStream,
                BackupType.FULL
            )
        }
        verify { metadataOutputStream.close() }
    }

    @Test
    fun `app with no data gets cancelled and reason written to metadata`() = runBlocking {
        every { settingsManager.getToken() } returns token
        every { metadataManager.salt } returns salt
        coEvery {
            full.performFullBackup(packageInfo, fileDescriptor, 0, token, salt)
        } returns TRANSPORT_OK
        expectApkBackupAndMetadataWrite()
        every { full.getQuota() } returns DEFAULT_QUOTA_FULL_BACKUP
        every { full.checkFullBackupSize(0) } returns TRANSPORT_PACKAGE_REJECTED
        every { full.getCurrentPackage() } returns packageInfo
        every {
            metadataManager.onPackageBackupError(
                packageInfo,
                NO_DATA,
                metadataOutputStream,
                BackupType.FULL
            )
        } just Runs
        coEvery { full.cancelFullBackup(token, metadata.salt, false) } just Runs
        every { settingsManager.getStorage() } returns storage
        every { metadataOutputStream.close() } just Runs

        assertEquals(
            TRANSPORT_OK,
            backup.performFullBackup(packageInfo, fileDescriptor, 0)
        )
        assertEquals(
            DEFAULT_QUOTA_FULL_BACKUP,
            backup.getBackupQuota(packageInfo.packageName, true)
        )
        assertEquals(TRANSPORT_PACKAGE_REJECTED, backup.checkFullBackupSize(0))
        backup.cancelFullBackup()
        assertEquals(0L, backup.requestFullBackupTime())

        verify(exactly = 1) {
            metadataManager.onPackageBackupError(
                packageInfo,
                NO_DATA,
                metadataOutputStream,
                BackupType.FULL
            )
        }
        verify { metadataOutputStream.close() }
    }

    @Test
    fun `not allowed apps get their APKs backed up after @pm@ backup`() = runBlocking {
        val packageInfo = PackageInfo().apply { packageName = MAGIC_PACKAGE_MANAGER }
        val notAllowedPackages = listOf(
            PackageInfo().apply { packageName = "org.example.1" },
            PackageInfo().apply {
                packageName = "org.example.2"
                // the second package does not get backed up, because it is stopped
                applicationInfo = mockk {
                    flags = FLAG_STOPPED
                }
            }
        )
        val packageMetadata: PackageMetadata = mockk()

        every { settingsManager.canDoBackupNow() } returns true
        every { metadataManager.requiresInit } returns false
        every { settingsManager.getToken() } returns token
        every { metadataManager.salt } returns salt
        // do actual @pm@ backup
        coEvery {
            kv.performBackup(packageInfo, fileDescriptor, 0, token, salt)
        } returns TRANSPORT_OK

        assertEquals(
            TRANSPORT_OK,
            backup.performIncrementalBackup(packageInfo, fileDescriptor, 0)
        )

        // finish @pm@ backup
        every { kv.hasState() } returns true
        every { full.hasState() } returns false
        every { kv.getCurrentPackage() } returns pmPackageInfo
        every {
            metadataManager.onPackageBackedUp(pmPackageInfo, BackupType.KV, metadataOutputStream)
        } just Runs
        coEvery { kv.finishBackup() } returns TRANSPORT_OK

        // now check if we have opt-out apps that we need to back up APKs for
        every { packageService.notBackedUpPackages } returns notAllowedPackages
        // update notification
        every {
            notificationManager.onOptOutAppBackup(
                notAllowedPackages[0].packageName,
                1,
                notAllowedPackages.size
            )
        } just Runs
        // no backup needed
        coEvery {
            apkBackup.backupApkIfNecessary(notAllowedPackages[0], NOT_ALLOWED, any())
        } returns null
        // check old metadata for state changes, because we won't update it otherwise
        every {
            metadataManager.getPackageMetadata(notAllowedPackages[0].packageName)
        } returns packageMetadata
        every { packageMetadata.state } returns NOT_ALLOWED // no change

        // update notification for second package
        every {
            notificationManager.onOptOutAppBackup(
                notAllowedPackages[1].packageName,
                2,
                notAllowedPackages.size
            )
        } just Runs
        // was backed up, get new packageMetadata
        coEvery {
            apkBackup.backupApkIfNecessary(notAllowedPackages[1], WAS_STOPPED, any())
        } returns packageMetadata
        every { settingsManager.getToken() } returns token
        coEvery { plugin.getOutputStream(token, FILE_BACKUP_METADATA) } returns metadataOutputStream
        every {
            metadataManager.onApkBackedUp(
                notAllowedPackages[1],
                packageMetadata,
                metadataOutputStream
            )
        } just Runs
        every { metadataOutputStream.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.finishBackup())

        coVerify {
            apkBackup.backupApkIfNecessary(notAllowedPackages[0], NOT_ALLOWED, any())
            apkBackup.backupApkIfNecessary(notAllowedPackages[1], WAS_STOPPED, any())
            metadataOutputStream.close()
        }
    }

    @Test
    fun `APK backup of not allowed apps updates state even without new APK`() = runBlocking {
        val oldPackageMetadata: PackageMetadata = mockk()

        every { packageService.notBackedUpPackages } returns listOf(packageInfo)
        every {
            notificationManager.onOptOutAppBackup(packageInfo.packageName, 1, 1)
        } just Runs
        coEvery { apkBackup.backupApkIfNecessary(packageInfo, NOT_ALLOWED, any()) } returns null
        every {
            metadataManager.getPackageMetadata(packageInfo.packageName)
        } returns oldPackageMetadata
        // state differs now, was stopped before
        every { oldPackageMetadata.state } returns WAS_STOPPED
        every { settingsManager.getToken() } returns token
        coEvery { plugin.getOutputStream(token, FILE_BACKUP_METADATA) } returns metadataOutputStream
        every {
            metadataManager.onPackageBackupError(
                packageInfo,
                NOT_ALLOWED,
                metadataOutputStream
            )
        } just Runs
        every { metadataOutputStream.close() } just Runs

        backup.backUpApksOfNotBackedUpPackages()

        verify {
            metadataManager.onPackageBackupError(packageInfo, NOT_ALLOWED, metadataOutputStream)
            metadataOutputStream.close()
        }
    }

    @Test
    fun `APK backup of not allowed apps updates state even without old state`() = runBlocking {
        every { packageService.notBackedUpPackages } returns listOf(packageInfo)
        every {
            notificationManager.onOptOutAppBackup(packageInfo.packageName, 1, 1)
        } just Runs
        coEvery { apkBackup.backupApkIfNecessary(packageInfo, NOT_ALLOWED, any()) } returns null
        every {
            metadataManager.getPackageMetadata(packageInfo.packageName)
        } returns null
        every { settingsManager.getToken() } returns token
        coEvery { plugin.getOutputStream(token, FILE_BACKUP_METADATA) } returns metadataOutputStream
        every {
            metadataManager.onPackageBackupError(
                packageInfo,
                NOT_ALLOWED,
                metadataOutputStream
            )
        } just Runs
        every { metadataOutputStream.close() } just Runs

        backup.backUpApksOfNotBackedUpPackages()

        verify {
            metadataManager.onPackageBackupError(packageInfo, NOT_ALLOWED, metadataOutputStream)
            metadataOutputStream.close()
        }
    }

    private fun expectApkBackupAndMetadataWrite() {
        coEvery {
            apkBackup.backupApkIfNecessary(
                any(),
                UNKNOWN_ERROR,
                any()
            )
        } returns packageMetadata
        every { settingsManager.getToken() } returns token
        coEvery { plugin.getOutputStream(token, FILE_BACKUP_METADATA) } returns metadataOutputStream
        every {
            metadataManager.onApkBackedUp(
                any(),
                packageMetadata,
                metadataOutputStream
            )
        } just Runs
    }

}
