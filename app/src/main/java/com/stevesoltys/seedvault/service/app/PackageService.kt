package com.stevesoltys.seedvault.service.app

import android.content.Context
import android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP
import android.content.pm.ApplicationInfo.FLAG_STOPPED
import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.ApplicationInfo.FLAG_TEST_ONLY
import android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_INSTRUMENTATION
import android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
import android.os.RemoteException
import android.util.Log
import android.util.Log.INFO
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.service.storage.StoragePlugin
import com.stevesoltys.seedvault.service.settings.SettingsService

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
internal class PackageService(
    private val context: Context,
    private val settingsService: SettingsService,
    private val plugin: StoragePlugin,
) {

    companion object {
        private val TAG = PackageService::class.java.simpleName

        private const val LOG_MAX_PACKAGES = 100
    }

    private val packageManager: PackageManager = context.packageManager

    val eligiblePackages: Array<String>
        @WorkerThread
        @Throws(RemoteException::class)
        get() {
            val packages = packageManager.getInstalledPackages(0)
                .map { packageInfo -> packageInfo.packageName }
                .sorted()

            // log packages
            if (Log.isLoggable(TAG, INFO)) {
                Log.i(TAG, "Got ${packages.size} packages:")
                logPackages(packages)
            }

            val eligibleApps = packages.filter(::shouldIncludeAppInBackup).toTypedArray()

            // log eligible packages
            if (Log.isLoggable(TAG, INFO)) {
                Log.i(TAG, "Filtering left ${eligibleApps.size} eligible packages:")
                logPackages(eligibleApps.toList())
            }

            // add magic @pm@ package (PACKAGE_MANAGER_SENTINEL) which holds package manager data
            val packageArray = eligibleApps.toMutableList()
            packageArray.add(MAGIC_PACKAGE_MANAGER)

            return packageArray.toTypedArray()
        }

    val notBackedUpPackages: List<PackageInfo>
        @WorkerThread
        get() {
            // We need the GET_SIGNING_CERTIFICATES flag here,
            // because the package info is used by [ApkBackup] which needs signing info.
            return packageManager.getInstalledPackages(GET_SIGNING_CERTIFICATES)
                .filter { packageInfo ->
                    packageInfo.doesNotGetBackedUp() && // only apps that do not allow backup
                        !packageInfo.isNotUpdatedSystemApp() && // and are not vanilla system apps
                        packageInfo.packageName != context.packageName // not this app
                }.sortedBy { packageInfo ->
                    packageInfo.packageName
                }.also { notAllowed ->
                    // log eligible packages
                    if (Log.isLoggable(TAG, INFO)) {
                        Log.i(TAG, "${notAllowed.size} apps do not allow backup:")
                        logPackages(notAllowed.map { it.packageName })
                    }
                }
        }

    /**
     * A list of non-system apps
     * (without instrumentation test apps and without apps that don't allow backup).
     */
    val userApps: List<PackageInfo>
        @WorkerThread
        get() = packageManager.getInstalledPackages(GET_INSTRUMENTATION).filter { packageInfo ->
            packageInfo.isUserVisible(context) &&
                packageInfo.allowsBackup(settingsService.d2dBackupsEnabled())
        }

    /**
     * A list of apps that does not allow backup.
     */
    val userNotAllowedApps: List<PackageInfo>
        @WorkerThread
        get() = packageManager.getInstalledPackages(0).filter { packageInfo ->
            !packageInfo.allowsBackup(settingsService.d2dBackupsEnabled()) &&
                !packageInfo.isSystemApp()
        }

    val expectedAppTotals: ExpectedAppTotals
        @WorkerThread
        get() {
            var appsTotal = 0
            var appsOptOut = 0
            packageManager.getInstalledPackages(GET_INSTRUMENTATION).forEach { packageInfo ->
                if (packageInfo.isUserVisible(context)) {
                    appsTotal++
                    if (packageInfo.doesNotGetBackedUp()) {
                        appsOptOut++
                    }
                }
            }
            return ExpectedAppTotals(appsTotal, appsOptOut)
        }

    fun getVersionName(packageName: String): String? = try {
        packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun shouldIncludeAppInBackup(packageName: String): Boolean {
        // We do not use BackupManager.filterAppsEligibleForBackupForUser for D2D because it
        // always makes its determinations based on OperationType.BACKUP, never based on
        // OperationType.MIGRATION, and there are no alternative publicly-available APIs.
        // We don't need to use it, here, either; during a backup or migration, the system
        // will perform its own eligibility checks regardless. We merely need to filter out
        // apps that we, or the user, want to exclude.

        // Check that the app is not excluded by user preference
        val enabled = settingsService.isBackupEnabled(packageName)

        // We need to explicitly exclude DocumentsProvider and Seedvault.
        // Otherwise, they get killed while backing them up, terminating our backup.
        val excludedPackages = setOf(
            plugin.providerPackageName,
            context.packageName
        )

        return enabled && !excludedPackages.contains(packageName)
    }

    private fun logPackages(packages: List<String>) {
        packages.chunked(LOG_MAX_PACKAGES).forEach {
            Log.i(TAG, it.toString())
        }
    }

}

internal data class ExpectedAppTotals(
    /**
     * The total number of non-system apps eligible for backup.
     */
    val appsTotal: Int,
    /**
     * The number of non-system apps that has opted-out of backup.
     */
    val appsOptOut: Int,
)

internal fun PackageInfo.isUserVisible(context: Context): Boolean {
    if (packageName == MAGIC_PACKAGE_MANAGER || applicationInfo == null) return false
    return !isNotUpdatedSystemApp() && instrumentation == null && packageName != context.packageName
}

internal fun PackageInfo.isSystemApp(): Boolean {
    if (packageName == MAGIC_PACKAGE_MANAGER || applicationInfo == null) return true
    return applicationInfo.flags and FLAG_SYSTEM != 0
}

internal fun PackageInfo.allowsBackup(d2dBackup: Boolean): Boolean {
    if (packageName == MAGIC_PACKAGE_MANAGER || applicationInfo == null) return false

    return if (d2dBackup) {
        // TODO: Consider ways of replicating the system's logic so that the user can have advance
        // knowledge of apps that the system will exclude, particularly apps targeting SDK 30 or
        // below.

        // At backup time, the system will filter out any apps that *it* does not want to be
        // backed up. If the user has enabled D2D, *we* generally want to back up as much as
        // possible; part of the point of D2D is to ignore FLAG_ALLOW_BACKUP (allowsBackup).
        // So, we return true.
        // See frameworks/base/services/backup/java/com/android/server/backup/utils/
        // BackupEligibilityRules.java lines 74-81 and 163-167 (tag: android-13.0.0_r8).
        true
    } else {
        applicationInfo.flags and FLAG_ALLOW_BACKUP != 0
    }
}

/**
 * Returns true if this is a system app that hasn't been updated.
 * We don't back up those APKs.
 */
internal fun PackageInfo.isNotUpdatedSystemApp(): Boolean {
    if (packageName == MAGIC_PACKAGE_MANAGER || applicationInfo == null) return true
    val isSystemApp = applicationInfo.flags and FLAG_SYSTEM != 0
    val isUpdatedSystemApp = applicationInfo.flags and FLAG_UPDATED_SYSTEM_APP != 0
    return isSystemApp && !isUpdatedSystemApp
}

internal fun PackageInfo.doesNotGetBackedUp(): Boolean {
    if (packageName == MAGIC_PACKAGE_MANAGER || applicationInfo == null) return true
    return applicationInfo.flags and FLAG_ALLOW_BACKUP == 0 || // does not allow backup
        applicationInfo.flags and FLAG_STOPPED != 0 // is stopped
}

internal fun PackageInfo.isStopped(): Boolean {
    if (packageName == MAGIC_PACKAGE_MANAGER || applicationInfo == null) return false
    return applicationInfo.flags and FLAG_STOPPED != 0
}

internal fun PackageInfo.isTestOnly(): Boolean {
    if (packageName == MAGIC_PACKAGE_MANAGER || applicationInfo == null) return false
    return applicationInfo.flags and FLAG_TEST_ONLY != 0
}
