package com.stevesoltys.seedvault.transport

import android.app.backup.IBackupManager
import android.content.pm.IPackageManager
import android.content.pm.PackageInfo
import android.os.RemoteException
import android.os.ServiceManager.getService
import android.os.UserHandle
import android.util.Log
import android.util.Log.INFO
import com.google.android.collect.Sets.newArraySet
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import org.koin.core.KoinComponent
import org.koin.core.inject

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
internal object PackageService : KoinComponent {

    private val backupManager: IBackupManager by inject()
    private val packageManager: IPackageManager = IPackageManager.Stub.asInterface(getService("package"))

    val eligiblePackages: Array<String>
        @Throws(RemoteException::class)
        get() {
            val packages: List<PackageInfo> = packageManager.getInstalledPackages(0, UserHandle.USER_SYSTEM).list as List<PackageInfo>
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
            val eligibleApps = backupManager.filterAppsEligibleForBackupForUser(UserHandle.myUserId(), packageList.toTypedArray())

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

}
