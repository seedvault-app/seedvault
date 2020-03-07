package com.stevesoltys.seedvault.transport.backup

import android.app.backup.IBackupManager
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
        private val backupManager: IBackupManager) {

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

            val eligibleApps = backupManager.filterAppsEligibleForBackupForUser(myUserId, packages.toTypedArray())

            // log eligible packages
            if (Log.isLoggable(TAG, INFO)) {
                Log.i(TAG, "Filtering left ${eligibleApps.size} eligible packages:")
                eligibleApps.toList().chunked(LOG_MAX_PACKAGES).forEach {
                    Log.i(TAG, it.toString())
                }
            }

            // add magic @pm@ package (PACKAGE_MANAGER_SENTINEL) which holds package manager data
            val packageArray = eligibleApps.toMutableList()
            packageArray.add(MAGIC_PACKAGE_MANAGER)

            return packageArray.toTypedArray()
        }

    val notAllowedPackages: List<PackageInfo>
        @WorkerThread
        get() {
            val installed = packageManager.getInstalledPackages(GET_SIGNING_CERTIFICATES)
            val installedArray = installed.map { packageInfo ->
                packageInfo.packageName
            }.toTypedArray()

            val eligible = backupManager.filterAppsEligibleForBackupForUser(myUserId, installedArray)

            return installed.filter { packageInfo ->
                packageInfo.packageName !in eligible
            }.sortedBy { it.packageName }
        }

}
