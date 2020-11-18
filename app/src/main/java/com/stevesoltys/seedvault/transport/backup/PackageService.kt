package com.stevesoltys.seedvault.transport.backup

import android.app.backup.IBackupManager
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
import android.os.UserHandle
import android.util.Log
import android.util.Log.INFO
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER

private val TAG = PackageService::class.java.simpleName

private const val LOG_MAX_PACKAGES = 100

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
internal class PackageService(
    private val context: Context,
    private val backupManager: IBackupManager
) {

    private val packageManager: PackageManager = context.packageManager
    private val myUserId = UserHandle.myUserId()

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
                packages.chunked(LOG_MAX_PACKAGES).forEach {
                    Log.i(TAG, it.toString())
                }
            }

            val eligibleApps =
                backupManager.filterAppsEligibleForBackupForUser(myUserId, packages.toTypedArray())

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
            packageInfo.isUserVisible(context) && packageInfo.allowsBackup()
        }

    /**
     * A list of apps that does not allow backup.
     */
    val userNotAllowedApps: List<PackageInfo>
        @WorkerThread
        get() = packageManager.getInstalledPackages(0).filter { packageInfo ->
            !packageInfo.allowsBackup() && !packageInfo.isSystemApp()
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
    val appsOptOut: Int
)

internal fun PackageInfo.isUserVisible(context: Context): Boolean {
    if (packageName == MAGIC_PACKAGE_MANAGER || applicationInfo == null) return false
    return !isNotUpdatedSystemApp() && instrumentation == null && packageName != context.packageName
}

internal fun PackageInfo.isSystemApp(): Boolean {
    if (packageName == MAGIC_PACKAGE_MANAGER || applicationInfo == null) return true
    return applicationInfo.flags and FLAG_SYSTEM != 0
}

internal fun PackageInfo.allowsBackup(): Boolean {
    if (packageName == MAGIC_PACKAGE_MANAGER || applicationInfo == null) return false
    return applicationInfo.flags and FLAG_ALLOW_BACKUP != 0
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
