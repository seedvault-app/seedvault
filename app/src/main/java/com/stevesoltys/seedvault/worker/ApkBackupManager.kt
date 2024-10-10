/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.worker

import android.content.Context
import android.content.pm.PackageInfo
import android.util.Log
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.PackageState.NOT_ALLOWED
import com.stevesoltys.seedvault.metadata.PackageState.WAS_STOPPED
import com.stevesoltys.seedvault.repo.AppBackupManager
import com.stevesoltys.seedvault.repo.SnapshotManager
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.transport.backup.isStopped
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.notification.getAppName
import org.calyxos.seedvault.core.backends.isOutOfSpace
import java.io.IOException

internal class ApkBackupManager(
    private val context: Context,
    private val appBackupManager: AppBackupManager,
    private val settingsManager: SettingsManager,
    private val snapshotManager: SnapshotManager,
    private val metadataManager: MetadataManager,
    private val packageService: PackageService,
    private val iconManager: IconManager,
    private val apkBackup: ApkBackup,
    private val nm: BackupNotificationManager,
) {

    companion object {
        private val TAG = ApkBackupManager::class.simpleName
    }

    suspend fun backup() {
        try {
            // We may be backing up APKs of packages that don't get their data backed up.
            // Since an APK backup does not change the [packageState], we first record it for all
            // packages that don't get backed up.
            recordNotBackedUpPackages()
            // Upload current icons, so we can show them to user before restore
            uploadIcons()
            // Now, if APK backups are enabled by the user, we back those up.
            if (settingsManager.backupApks()) {
                backUpApks()
            }
        } finally {
            nm.onApkBackupDone()
        }
    }

    /**
     * Goes through the list of all apps and uploads their APK, if needed.
     */
    private suspend fun backUpApks() {
        val apps = packageService.allUserPackages
        apps.forEachIndexed { i, packageInfo ->
            val packageName = packageInfo.packageName
            val name = getAppName(context, packageName)
            nm.onApkBackup(packageName, name, i, apps.size)
            backUpApk(packageInfo)
        }
    }

    // TODO we could use BackupMonitor for this. It emits LOG_EVENT_ID_PACKAGE_STOPPED
    private fun recordNotBackedUpPackages() {
        nm.onAppsNotBackedUp()
        packageService.notBackedUpPackages.forEach { packageInfo ->
            val packageName = packageInfo.packageName
            if (!settingsManager.isBackupEnabled(packageName)) return@forEach
            try {
                val packageState = if (packageInfo.isStopped()) WAS_STOPPED else NOT_ALLOWED
                val packageMetadata = metadataManager.getPackageMetadata(packageName)
                val oldPackageState = packageMetadata?.state
                if (oldPackageState != packageState) {
                    Log.i(
                        TAG, "Package $packageName was in $oldPackageState" +
                            ", update to $packageState"
                    )
                    metadataManager.onPackageDoesNotGetBackedUp(packageInfo, packageState)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error storing new metadata for $packageName: ", e)
            }
            // see if there's data in latest snapshot for this app and re-use it
            // this can be helpful for backing up recently STOPPED apps
            snapshotManager.latestSnapshot?.let { snapshot ->
                appBackupManager.snapshotCreator?.onNoDataInCurrentRun(
                    snapshot = snapshot,
                    packageName = packageName,
                    isStopped = true,
                )
            }
        }
    }

    private suspend fun uploadIcons() {
        try {
            iconManager.uploadIcons()
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading icons: ", e)
        }
    }

    /**
     * Backs up one (or more split) APK(s) for the given [PackageInfo], if needed.
     */
    private suspend fun backUpApk(packageInfo: PackageInfo) {
        val packageName = packageInfo.packageName
        try {
            apkBackup.backupApkIfNecessary(packageInfo, snapshotManager.latestSnapshot)
        } catch (e: IOException) {
            Log.e(TAG, "Error while writing APK for $packageName", e)
            if (e.isOutOfSpace()) nm.onInsufficientSpaceError()
        }
    }
}
