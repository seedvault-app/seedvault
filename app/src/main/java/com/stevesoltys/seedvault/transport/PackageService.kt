package com.stevesoltys.seedvault.transport

import android.content.pm.IPackageManager
import android.content.pm.PackageInfo
import android.os.RemoteException
import android.os.ServiceManager.getService
import android.os.UserHandle
import android.util.Log
import com.google.android.collect.Sets.newArraySet
import com.stevesoltys.seedvault.Backup
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import java.util.*

private val TAG = PackageService::class.java.simpleName

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
internal class PackageService {

    private val backupManager = Backup.backupManager
    private val packageManager: IPackageManager = IPackageManager.Stub.asInterface(getService("package"))

    val eligiblePackages: Array<String>
        @Throws(RemoteException::class)
        get() {
            val packages: List<PackageInfo> = packageManager.getInstalledPackages(0, UserHandle.USER_SYSTEM).list as List<PackageInfo>
            val packageList = packages
                    .map { packageInfo -> packageInfo.packageName }
                    .filter { packageName -> !IGNORED_PACKAGES.contains(packageName) }
                    .sorted()

            Log.d(TAG, "Got ${packageList.size} packages: $packageList")

            // TODO why is this filtering out so much?
            val eligibleApps = backupManager.filterAppsEligibleForBackupForUser(UserHandle.myUserId(), packageList.toTypedArray())

            Log.d(TAG, "Filtering left ${eligibleApps.size} eligible packages: ${Arrays.toString(eligibleApps)}")

            // add magic @pm@ package (PACKAGE_MANAGER_SENTINEL) which holds package manager data
            val packageArray = eligibleApps.toMutableList()
            packageArray.add(MAGIC_PACKAGE_MANAGER)

            return packageArray.toTypedArray()
        }

}
