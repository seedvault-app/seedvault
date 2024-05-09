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
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.plugins.isOutOfSpace
import com.stevesoltys.seedvault.plugins.saf.FILE_BACKUP_METADATA
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.transport.backup.isStopped
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.notification.getAppName
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.OutputStream

internal class ApkBackupManager(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val metadataManager: MetadataManager,
    private val packageService: PackageService,
    private val apkBackup: ApkBackup,
    private val pluginManager: StoragePluginManager,
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
            // Now, if APK backups are enabled by the user, we back those up.
            if (settingsManager.backupApks()) {
                backUpApks()
            }
        } finally {
            keepTrying {
                // upload all local changes only at the end,
                // so we don't have to re-upload the metadata
                pluginManager.appPlugin.getMetadataOutputStream().use { outputStream ->
                    metadataManager.uploadMetadata(outputStream)
                }
            }
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

    private fun recordNotBackedUpPackages() {
        nm.onAppsNotBackedUp()
        packageService.notBackedUpPackages.forEach { packageInfo ->
            val packageName = packageInfo.packageName
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
        }
    }

    /**
     * Backs up an APK for the given [PackageInfo].
     *
     * @return true if a backup was performed and false if no backup was needed or it failed.
     */
    private suspend fun backUpApk(packageInfo: PackageInfo): Boolean {
        val packageName = packageInfo.packageName
        return try {
            apkBackup.backupApkIfNecessary(packageInfo) { name ->
                val token = settingsManager.getToken() ?: throw IOException("no current token")
                pluginManager.appPlugin.getOutputStream(token, name)
            }?.let { packageMetadata ->
                metadataManager.onApkBackedUp(packageInfo, packageMetadata)
                true
            } ?: false
        } catch (e: IOException) {
            Log.e(TAG, "Error while writing APK for $packageName", e)
            if (e.isOutOfSpace()) nm.onInsufficientSpaceError()
            false
        }
    }

    private suspend fun keepTrying(n: Int = 3, block: suspend () -> Unit) {
        for (i in 1..n) {
            try {
                block()
            } catch (e: Exception) {
                if (i == n) throw e
                Log.e(TAG, "Error (#$i), we'll keep trying", e)
                delay(1000)
            }
        }
    }

    private suspend fun StoragePlugin<*>.getMetadataOutputStream(
        token: Long? = null,
    ): OutputStream {
        val t = token ?: settingsManager.getToken() ?: throw IOException("no current token")
        return getOutputStream(t, FILE_BACKUP_METADATA)
    }
}
