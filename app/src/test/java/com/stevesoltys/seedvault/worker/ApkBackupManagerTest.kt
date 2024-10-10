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
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageState.NOT_ALLOWED
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.metadata.PackageState.WAS_STOPPED
import com.stevesoltys.seedvault.repo.AppBackupManager
import com.stevesoltys.seedvault.repo.SnapshotCreator
import com.stevesoltys.seedvault.repo.SnapshotManager
import com.stevesoltys.seedvault.transport.TransportTest
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyAll
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.Backend
import org.junit.jupiter.api.Test

internal class ApkBackupManagerTest : TransportTest() {

    private val appBackupManager: AppBackupManager = mockk()
    private val snapshotManager: SnapshotManager = mockk()
    private val packageService: PackageService = mockk()
    private val apkBackup: ApkBackup = mockk()
    private val iconManager: IconManager = mockk()
    private val backendManager: BackendManager = mockk()
    private val backend: Backend = mockk()
    private val nm: BackupNotificationManager = mockk()

    private val apkBackupManager = ApkBackupManager(
        context = context,
        appBackupManager = appBackupManager,
        settingsManager = settingsManager,
        snapshotManager = snapshotManager,
        metadataManager = metadataManager,
        packageService = packageService,
        iconManager = iconManager,
        apkBackup = apkBackup,
        nm = nm,
    )

    private val packageMetadata: PackageMetadata = mockk()
    private val snapshotCreator: SnapshotCreator = mockk()

    init {
        every { backendManager.backend } returns backend
        every { appBackupManager.snapshotCreator } returns snapshotCreator
    }

    @Test
    fun `Package state of app that is not stopped gets recorded as not-allowed`() = runBlocking {
        every { nm.onAppsNotBackedUp() } just Runs
        every { packageService.notBackedUpPackages } returns listOf(packageInfo)
        every { settingsManager.isBackupEnabled(packageInfo.packageName) } returns true
        every { snapshotManager.latestSnapshot } returns snapshot
        every { snapshotCreator.onNoDataInCurrentRun(snapshot, packageName, true) } just Runs

        expectUploadIcons()

        every {
            metadataManager.getPackageMetadata(packageInfo.packageName)
        } returns packageMetadata
        every { packageMetadata.state } returns UNKNOWN_ERROR
        every { metadataManager.onPackageDoesNotGetBackedUp(packageInfo, NOT_ALLOWED) } just Runs

        every { settingsManager.backupApks() } returns false
        every { nm.onApkBackupDone() } just Runs

        apkBackupManager.backup()

        verify {
            metadataManager.onPackageDoesNotGetBackedUp(packageInfo, NOT_ALLOWED)
        }
    }

    @Test
    fun `Package state of app gets recorded even if no previous state`() = runBlocking {
        every { nm.onAppsNotBackedUp() } just Runs
        every { packageService.notBackedUpPackages } returns listOf(packageInfo)
        every { settingsManager.isBackupEnabled(packageInfo.packageName) } returns true
        every { snapshotManager.latestSnapshot } returns snapshot
        every { snapshotCreator.onNoDataInCurrentRun(snapshot, packageName, true) } just Runs

        expectUploadIcons()

        every {
            metadataManager.getPackageMetadata(packageInfo.packageName)
        } returns null
        every { metadataManager.onPackageDoesNotGetBackedUp(packageInfo, NOT_ALLOWED) } just Runs

        every { settingsManager.backupApks() } returns false
        every { nm.onApkBackupDone() } just Runs

        apkBackupManager.backup()

        verify {
            metadataManager.onPackageDoesNotGetBackedUp(packageInfo, NOT_ALLOWED)
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
        every { settingsManager.isBackupEnabled(packageInfo.packageName) } returns true
        every { snapshotManager.latestSnapshot } returns snapshot
        every { snapshotCreator.onNoDataInCurrentRun(snapshot, packageName, true) } just Runs

        expectUploadIcons()

        every {
            metadataManager.getPackageMetadata(packageInfo.packageName)
        } returns packageMetadata
        every { packageMetadata.state } returns UNKNOWN_ERROR
        every { metadataManager.onPackageDoesNotGetBackedUp(packageInfo, WAS_STOPPED) } just Runs

        every { settingsManager.backupApks() } returns false
        every { nm.onApkBackupDone() } just Runs

        apkBackupManager.backup()

        verify {
            metadataManager.onPackageDoesNotGetBackedUp(packageInfo, WAS_STOPPED)
        }
    }

    @Test
    fun `Package state only updated when changed`() = runBlocking {
        every { nm.onAppsNotBackedUp() } just Runs
        every { packageService.notBackedUpPackages } returns listOf(packageInfo)
        every { settingsManager.isBackupEnabled(packageInfo.packageName) } returns true
        every { snapshotManager.latestSnapshot } returns snapshot
        every { snapshotCreator.onNoDataInCurrentRun(snapshot, packageName, true) } just Runs

        expectUploadIcons()

        every {
            metadataManager.getPackageMetadata(packageInfo.packageName)
        } returns packageMetadata
        every { packageMetadata.state } returns NOT_ALLOWED

        every { settingsManager.backupApks() } returns false
        every { nm.onApkBackupDone() } just Runs

        apkBackupManager.backup()

        verifyAll(inverse = true) {
            metadataManager.onPackageDoesNotGetBackedUp(packageInfo, NOT_ALLOWED)
        }
    }

    @Test
    fun `Package state only updated if not excluded`() = runBlocking {
        every { nm.onAppsNotBackedUp() } just Runs
        every { packageService.notBackedUpPackages } returns listOf(packageInfo)
        every { settingsManager.isBackupEnabled(packageInfo.packageName) } returns false

        expectUploadIcons()

        every { settingsManager.backupApks() } returns false
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
        expectUploadIcons()
        expectAllAppsWillGetBackedUp()
        every { settingsManager.backupApks() } returns true

        every { packageService.allUserPackages } returns notAllowedPackages
        // update notification
        every {
            nm.onApkBackup(notAllowedPackages[0].packageName, any(), 0, notAllowedPackages.size)
        } just Runs
        every { snapshotManager.latestSnapshot } returns snapshot
        // no backup needed
        coEvery { apkBackup.backupApkIfNecessary(notAllowedPackages[0], snapshot) } just Runs
        // update notification for second package
        every {
            nm.onApkBackup(notAllowedPackages[1].packageName, any(), 1, notAllowedPackages.size)
        } just Runs
        // was backed up, get new packageMetadata
        coEvery { apkBackup.backupApkIfNecessary(notAllowedPackages[1], snapshot) } just Runs

        every { nm.onApkBackupDone() } just Runs

        apkBackupManager.backup()

        coVerify {
            apkBackup.backupApkIfNecessary(notAllowedPackages[0], snapshot)
            apkBackup.backupApkIfNecessary(notAllowedPackages[1], snapshot)
        }
    }

    @Test
    fun `we keep trying to upload metadata at the end`() = runBlocking {
        every { nm.onAppsNotBackedUp() } just Runs
        every { packageService.notBackedUpPackages } returns listOf(packageInfo)
        every { settingsManager.isBackupEnabled(packageInfo.packageName) } returns true
        every { snapshotManager.latestSnapshot } returns snapshot
        every { snapshotCreator.onNoDataInCurrentRun(snapshot, packageName, true) } just Runs

        expectUploadIcons()

        every {
            metadataManager.getPackageMetadata(packageInfo.packageName)
        } returns packageMetadata
        every { packageMetadata.state } returns UNKNOWN_ERROR
        every { metadataManager.onPackageDoesNotGetBackedUp(packageInfo, NOT_ALLOWED) } just Runs

        every { settingsManager.backupApks() } returns false

        every { nm.onApkBackupDone() } just Runs

        apkBackupManager.backup()

        verify {
            metadataManager.onPackageDoesNotGetBackedUp(packageInfo, NOT_ALLOWED)
        }
    }

    private suspend fun expectUploadIcons() {
        coEvery { iconManager.uploadIcons() } just Runs
    }

    private fun expectAllAppsWillGetBackedUp() {
        every { nm.onAppsNotBackedUp() } just Runs
        every { packageService.notBackedUpPackages } returns emptyList()
    }

}
