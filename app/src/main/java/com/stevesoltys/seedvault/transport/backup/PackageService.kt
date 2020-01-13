package com.stevesoltys.seedvault.transport.backup

import android.app.backup.IBackupManager
import android.content.pm.IPackageManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
import android.os.RemoteException
import android.os.ServiceManager.getService
import android.os.UserHandle
import android.util.Log
import android.util.Log.INFO
import androidx.annotation.WorkerThread
import com.google.android.collect.Sets.newArraySet
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER

private val TAG = PackageService::class.java.simpleName

private const val LOG_MAX_PACKAGES = 100
private val IGNORED_PACKAGES = newArraySet(
        "com.android.externalstorage",
        "com.android.providers.downloads.ui",
        "com.android.providers.downloads",
        "com.android.providers.media",
        "com.android.providers.calendar",
        "com.android.providers.contacts",
        "com.stevesoltys.seedvault"
)

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
internal class PackageService(
        private val packageManager: PackageManager,
        private val backupManager: IBackupManager) {

    // TODO This can probably be removed and PackageManager#getInstalledPackages() used instead
    private val packageManagerService: IPackageManager = IPackageManager.Stub.asInterface(getService("package"))
    private val myUserId = UserHandle.myUserId()

    val eligiblePackages: Array<String>
        @WorkerThread
        @Throws(RemoteException::class)
        get() {
            val packages: List<PackageInfo> = packageManagerService.getInstalledPackages(0, UserHandle.USER_SYSTEM).list as List<PackageInfo>
            val packageList = packages
                    .map { packageInfo -> packageInfo.packageName }
                    .filter { packageName -> !IGNORED_PACKAGES.contains(packageName) }
                    .sorted()

            // log packages
            if (Log.isLoggable(TAG, INFO)) {
                Log.i(TAG, "Got ${packageList.size} packages:")
                packageList.chunked(LOG_MAX_PACKAGES).forEach {
                    Log.i(TAG, it.toString())
                }
            }

            // TODO why is this filtering out so much?
            val eligibleApps = backupManager.filterAppsEligibleForBackupForUser(myUserId, packageList.toTypedArray())

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
            }
        }

}
