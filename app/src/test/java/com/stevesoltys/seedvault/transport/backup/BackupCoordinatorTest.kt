/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import android.app.backup.BackupTransport.TRANSPORT_QUOTA_EXCEEDED
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.PackageState.NO_DATA
import com.stevesoltys.seedvault.metadata.PackageState.QUOTA_EXCEEDED
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.repo.AppBackupManager
import com.stevesoltys.seedvault.repo.SnapshotCreator
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.worker.ApkBackup
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.saf.SafProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.random.Random

internal class BackupCoordinatorTest : BackupTest() {

    private val backendManager = mockk<BackendManager>()
    private val appBackupManager = mockk<AppBackupManager>()
    private val kv = mockk<KVBackup>()
    private val full = mockk<FullBackup>()
    private val apkBackup = mockk<ApkBackup>()
    private val notificationManager = mockk<BackupNotificationManager>()
    private val packageService = mockk<PackageService>()

    private val backup = BackupCoordinator(
        context = context,
        backendManager = backendManager,
        appBackupManager = appBackupManager,
        kv = kv,
        full = full,
        packageService = packageService,
        metadataManager = metadataManager,
        settingsManager = settingsManager,
        nm = notificationManager,
    )

    private val backend = mockk<Backend>()
    private val fileDescriptor: ParcelFileDescriptor = mockk()
    private val safProperties = SafProperties(
        config = Uri.EMPTY,
        name = getRandomString(),
        isUsb = false,
        requiresNetwork = false,
        rootId = null,
    )

    init {
        every { backendManager.backend } returns backend
    }

    @Test
    fun `device initialization succeeds and delegates to plugin`() = runBlocking {
        every { kv.hasState } returns false
        every { full.hasState } returns false

        assertEquals(TRANSPORT_OK, backup.initializeDevice())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
    }

    @Test
    fun `getBackupQuota() delegates to right plugin`() = runBlocking {
        val isFullBackup = Random.nextBoolean()
        val quota = Random.nextLong()

        every { settingsManager.quota } returns quota
        if (!isFullBackup) { // hack for `adb shell bmgr` which starts with a K/V backup
            coEvery { appBackupManager.ensureBackupPrepared() } just Runs
        }

        assertEquals(quota, backup.getBackupQuota(packageInfo.packageName, isFullBackup))
    }

    @Test
    fun `clearing backup data does nothing`() = runBlocking {
        assertEquals(TRANSPORT_OK, backup.clearBackupData(packageInfo))

        every { kv.hasState } returns false
        every { full.hasState } returns false

        assertEquals(TRANSPORT_OK, backup.finishBackup())
    }

    @Test
    fun `finish backup delegates to KV plugin if it has state`() = runBlocking {
        val snapshotCreator: SnapshotCreator = mockk()
        val size = Random.nextLong()

        every { kv.hasState } returns true
        every { full.hasState } returns false
        every { kv.currentPackageInfo } returns packageInfo
        coEvery { kv.finishBackup() } returns apkBackupData
        every { appBackupManager.snapshotCreator } returns snapshotCreator
        every {
            snapshotCreator.onPackageBackedUp(packageInfo, BackupType.KV, apkBackupData)
        } just Runs
        every {
            metadataManager.onPackageBackedUp(packageInfo, BackupType.KV, apkBackupData.size)
        } just Runs

        assertEquals(TRANSPORT_OK, backup.finishBackup())
    }

    @Test
    fun `finish KV backup throws exception`() = runBlocking {
        every { kv.hasState } returns true
        every { full.hasState } returns false
        every { kv.currentPackageInfo } returns packageInfo
        coEvery { kv.finishBackup() } throws IOException()

        every {
            metadataManager.onPackageBackupError(
                packageInfo,
                UNKNOWN_ERROR,
                BackupType.KV,
            )
        } just Runs

        assertEquals(TRANSPORT_PACKAGE_REJECTED, backup.finishBackup())
    }

    @Test
    fun `finish backup delegates to full plugin if it has state`() = runBlocking {
        val snapshotCreator: SnapshotCreator = mockk()
        val size: Long = 2345

        every { kv.hasState } returns false
        every { full.hasState } returns true
        every { full.currentPackageInfo } returns packageInfo
        coEvery { full.finishBackup() } returns apkBackupData
        every { appBackupManager.snapshotCreator } returns snapshotCreator
        every {
            snapshotCreator.onPackageBackedUp(packageInfo, BackupType.FULL, apkBackupData)
        } just Runs
        every {
            metadataManager.onPackageBackedUp(
                packageInfo = packageInfo,
                type = BackupType.FULL,
                size = apkBackupData.size,
            )
        } just Runs

        assertEquals(TRANSPORT_OK, backup.finishBackup())
    }

    @Test
    fun `metadata does not get updated when no APK was backed up`() = runBlocking {
        coEvery {
            full.performFullBackup(packageInfo, fileDescriptor, 0)
        } returns TRANSPORT_OK
        coEvery { apkBackup.backupApkIfNecessary(packageInfo, snapshot) } just Runs

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, fileDescriptor, 0))
    }

    @Test
    fun `app exceeding quota gets cancelled and reason written to metadata`() = runBlocking {
        coEvery {
            full.performFullBackup(packageInfo, fileDescriptor, 0)
        } returns TRANSPORT_OK
        every { settingsManager.quota } returns quota
        every {
            full.checkFullBackupSize(quota + 1)
        } returns TRANSPORT_QUOTA_EXCEEDED
        every { full.currentPackageInfo } returns packageInfo
        every {
            metadataManager.onPackageBackupError(
                packageInfo,
                QUOTA_EXCEEDED,
                BackupType.FULL
            )
        } just Runs
        coEvery { full.cancelFullBackup() } just Runs
        every { backendManager.backendProperties } returns safProperties
        every { settingsManager.useMeteredNetwork } returns false

        assertEquals(
            TRANSPORT_OK,
            backup.performFullBackup(packageInfo, fileDescriptor, 0)
        )
        assertEquals(quota, backup.getBackupQuota(packageInfo.packageName, true))
        assertEquals(TRANSPORT_QUOTA_EXCEEDED, backup.checkFullBackupSize(quota + 1))
        backup.cancelFullBackup()
        assertEquals(0L, backup.requestFullBackupTime())

        verify(exactly = 1) {
            metadataManager.onPackageBackupError(
                packageInfo,
                QUOTA_EXCEEDED,
                BackupType.FULL
            )
        }
    }

    @Test
    fun `app with no data gets cancelled and reason written to metadata`() = runBlocking {
        coEvery {
            full.performFullBackup(packageInfo, fileDescriptor, 0)
        } returns TRANSPORT_OK
        every { settingsManager.quota } returns quota
        every { full.checkFullBackupSize(0) } returns TRANSPORT_PACKAGE_REJECTED
        every { full.currentPackageInfo } returns packageInfo
        every {
            metadataManager.onPackageBackupError(
                packageInfo,
                NO_DATA,
                BackupType.FULL
            )
        } just Runs
        coEvery { full.cancelFullBackup() } just Runs
        every { backendManager.backendProperties } returns safProperties
        every { settingsManager.useMeteredNetwork } returns false

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, fileDescriptor, 0))
        assertEquals(quota, backup.getBackupQuota(packageInfo.packageName, true))
        assertEquals(TRANSPORT_PACKAGE_REJECTED, backup.checkFullBackupSize(0))
        backup.cancelFullBackup()
        assertEquals(0L, backup.requestFullBackupTime())

        verify(exactly = 1) {
            metadataManager.onPackageBackupError(
                packageInfo,
                NO_DATA,
                BackupType.FULL
            )
        }
    }

    @Test
    fun `not allowed apps get their APKs backed up after @pm@ backup`() = runBlocking {
    }

}
