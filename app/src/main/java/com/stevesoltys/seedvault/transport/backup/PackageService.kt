package com.stevesoltys.seedvault.transport.backup

import android.app.backup.IBackupManager
import android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP
import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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
    private val packageManager: PackageManager,
    private val backupManager: IBackupManager
) {

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

    val notAllowedPackages: List<PackageInfo>
        @WorkerThread
        get() {
            // We need the GET_SIGNING_CERTIFICATES flag here,
            // because the package info is used by [ApkBackup] which needs signing info.
            return packageManager.getInstalledPackages(GET_SIGNING_CERTIFICATES)
                .filter { packageInfo ->
                    !packageInfo.isBackupAllowed() && // only apps that do not allow backup
                            !packageInfo.isNotUpdatedSystemApp() // and are not vanilla system apps
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

    private fun logPackages(packages: List<String>) {
        packages.chunked(LOG_MAX_PACKAGES).forEach {
            Log.i(TAG, it.toString())
        }
    }

}

internal fun PackageInfo.isSystemApp(): Boolean {
    if (packageName == MAGIC_PACKAGE_MANAGER || applicationInfo == null) return true
    return applicationInfo.flags and FLAG_SYSTEM != 0
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

internal fun PackageInfo.isBackupAllowed(): Boolean {
    if (packageName == MAGIC_PACKAGE_MANAGER || applicationInfo == null) return false
    return applicationInfo.flags and FLAG_ALLOW_BACKUP != 0
}
