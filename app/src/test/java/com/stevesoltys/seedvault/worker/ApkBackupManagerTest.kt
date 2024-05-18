/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.worker

import android.content.pm.ApplicationInfo
import android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP
import android.content.pm.ApplicationInfo.FLAG_INSTALLED
import android.content.pm.ApplicationInfo.FLAG_STOPPED
import android.content.pm.PackageInfo
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageState.NOT_ALLOWED
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.metadata.PackageState.WAS_STOPPED
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.plugins.saf.FILE_BACKUP_METADATA
import com.stevesoltys.seedvault.transport.TransportTest
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.mockk.Runs
import io.mockk.andThenJust
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.OutputStream

internal class ApkBackupManagerTest : TransportTest() {

    private val packageService: PackageService = mockk()
    private val apkBackup: ApkBackup = mockk()
    private val storagePluginManager: StoragePluginManager = mockk()
    private val plugin: StoragePlugin<*> = mockk()
    private val nm: BackupNotificationManager = mockk()

    private val apkBackupManager = ApkBackupManager(
        context = context,
        settingsManager = settingsManager,
        metadataManager = metadataManager,
        packageService = packageService,
        apkBackup = apkBackup,
        pluginManager = storagePluginManager,
        nm = nm,
    )

    private val metadataOutputStream = mockk<OutputStream>()
    private val packageMetadata: PackageMetadata = mockk()

    init {
        every { storagePluginManager.appPlugin } returns plugin
    }

    @Test
    fun `Package state of app that is not stopped gets recorded as not-allowed`() = runBlocking {
        every { nm.onAppsNotBackedUp() } just Runs
        every { packageService.notBackedUpPackages } returns listOf(packageInfo)

        every {
            metadataManager.getPackageMetadata(packageInfo.packageName)
        } returns packageMetadata
        every { packageMetadata.state } returns UNKNOWN_ERROR
        every { metadataManager.onPackageDoesNotGetBackedUp(packageInfo, NOT_ALLOWED) } just Runs

        every { settingsManager.backupApks() } returns false
        expectFinalUpload()
        every { nm.onApkBackupDone() } just Runs

        apkBackupManager.backup()

        verify {
            metadataManager.onPackageDoesNotGetBackedUp(packageInfo, NOT_ALLOWED)
            metadataOutputStream.close()
        }
    }

    @Test
    fun `Package state of app gets recorded even if no previous state`() = runBlocking {
        every { nm.onAppsNotBackedUp() } just Runs
        every { packageService.notBackedUpPackages } returns listOf(packageInfo)

        every {
            metadataManager.getPackageMetadata(packageInfo.packageName)
        } returns null
        every { metadataManager.onPackageDoesNotGetBackedUp(packageInfo, NOT_ALLOWED) } just Runs

        every { settingsManager.backupApks() } returns false
        expectFinalUpload()
        every { nm.onApkBackupDone() } just Runs

        apkBackupManager.backup()

        verify {
            metadataManager.onPackageDoesNotGetBackedUp(packageInfo, NOT_ALLOWED)
            metadataOutputStream.close()
        }
    }

    @Test
    fun `Package state of app that is stopped gets recorded`() = runBlocking {
        val packageInfo = PackageInfo().apply {
            packageName = "org.example"
            applicationInfo = mockk<ApplicationInfo> {
                flags = FLAG_ALLOW_BACKUP or FLAG_INSTALLED or FLAG_STOPPED
            }
        }

        every { nm.onAppsNotBackedUp() } just Runs
        every { packageService.notBackedUpPackages } returns listOf(packageInfo)

        every {
            metadataManager.getPackageMetadata(packageInfo.packageName)
        } returns packageMetadata
        every { packageMetadata.state } returns UNKNOWN_ERROR
        every { metadataManager.onPackageDoesNotGetBackedUp(packageInfo, WAS_STOPPED) } just Runs

        every { settingsManager.backupApks() } returns false
        expectFinalUpload()
        every { nm.onApkBackupDone() } just Runs

        apkBackupManager.backup()

        verify {
            metadataManager.onPackageDoesNotGetBackedUp(packageInfo, WAS_STOPPED)
            metadataOutputStream.close()
        }
    }

    @Test
    fun `Package state only updated when changed`() = runBlocking {
        every { nm.onAppsNotBackedUp() } just Runs
        every { packageService.notBackedUpPackages } returns listOf(packageInfo)

        every {
            metadataManager.getPackageMetadata(packageInfo.packageName)
        } returns packageMetadata
        every { packageMetadata.state } returns NOT_ALLOWED

        every { settingsManager.backupApks() } returns false
        expectFinalUpload()
        every { nm.onApkBackupDone() } just Runs

        apkBackupManager.backup()

        verifyAll(inverse = true) {
            metadataManager.onPackageDoesNotGetBackedUp(packageInfo, NOT_ALLOWED)
        }
    }

    @Test
    fun `two packages get backed up, one their APK uploaded`() = runBlocking {
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

        expectAllAppsWillGetBackedUp()
        every { settingsManager.backupApks() } returns true

        every { packageService.allUserPackages } returns notAllowedPackages
        // update notification
        every {
            nm.onApkBackup(notAllowedPackages[0].packageName, any(), 0, notAllowedPackages.size)
        } just Runs
        // no backup needed
        coEvery {
            apkBackup.backupApkIfNecessary(notAllowedPackages[0], any())
        } returns null
        // update notification for second package
        every {
            nm.onApkBackup(notAllowedPackages[1].packageName, any(), 1, notAllowedPackages.size)
        } just Runs
        // was backed up, get new packageMetadata
        coEvery {
            apkBackup.backupApkIfNecessary(notAllowedPackages[1], any())
        } returns packageMetadata
        every { metadataManager.onApkBackedUp(notAllowedPackages[1], packageMetadata) } just Runs

        expectFinalUpload()
        every { nm.onApkBackupDone() } just Runs

        apkBackupManager.backup()

        coVerify {
            apkBackup.backupApkIfNecessary(notAllowedPackages[0], any())
            apkBackup.backupApkIfNecessary(notAllowedPackages[1], any())
            metadataOutputStream.close()
        }
    }

    @Test
    fun `we keep trying to upload metadata at the end`() = runBlocking {
        every { nm.onAppsNotBackedUp() } just Runs
        every { packageService.notBackedUpPackages } returns listOf(packageInfo)

        every {
            metadataManager.getPackageMetadata(packageInfo.packageName)
        } returns packageMetadata
        every { packageMetadata.state } returns UNKNOWN_ERROR
        every { metadataManager.onPackageDoesNotGetBackedUp(packageInfo, NOT_ALLOWED) } just Runs

        every { settingsManager.backupApks() } returns false

        // final upload
        every { settingsManager.getToken() } returns token
        coEvery { plugin.getOutputStream(token, FILE_BACKUP_METADATA) } returns metadataOutputStream
        every {
            metadataManager.uploadMetadata(metadataOutputStream)
        } throws IOException() andThenThrows SecurityException() andThenJust Runs
        every { metadataOutputStream.close() } just Runs

        every { nm.onApkBackupDone() } just Runs

        apkBackupManager.backup()

        verify {
            metadataManager.onPackageDoesNotGetBackedUp(packageInfo, NOT_ALLOWED)
            metadataOutputStream.close()
        }
    }

    private fun expectAllAppsWillGetBackedUp() {
        every { nm.onAppsNotBackedUp() } just Runs
        every { packageService.notBackedUpPackages } returns emptyList()
    }

    private fun expectFinalUpload() {
        every { settingsManager.getToken() } returns token
        coEvery { plugin.getOutputStream(token, FILE_BACKUP_METADATA) } returns metadataOutputStream
        every { metadataManager.uploadMetadata(metadataOutputStream) } just Runs
        every { metadataOutputStream.close() } just Runs
    }

}
