/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_MAIN
import android.content.Intent.CATEGORY_LAUNCHER
import android.content.pm.ApplicationInfo.FLAG_STOPPED
import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.ApplicationInfo.FLAG_TEST_ONLY
import android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_INSTRUMENTATION
import android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
import android.content.pm.PackageManager.MATCH_SYSTEM_ONLY
import android.content.pm.ResolveInfo
import android.os.RemoteException
import android.util.Log
import android.util.Log.INFO
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.settings.SettingsManager
import org.calyxos.seedvault.core.backends.Backend

private val TAG = PackageService::class.java.simpleName

private const val LOG_MAX_PACKAGES = 100

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
internal class PackageService(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val backendManager: BackendManager,
) {

    private val packageManager: PackageManager = context.packageManager
    private val backend: Backend get() = backendManager.backend

    val eligiblePackages: List<String>
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

            val eligibleApps = packages.filter(::shouldIncludeAppInBackup).toMutableList()
            // log eligible packages
            if (Log.isLoggable(TAG, INFO)) {
                Log.i(TAG, "Filtering left ${eligibleApps.size} eligible packages:")
                logPackages(eligibleApps)
            }

            // add magic @pm@ package (PACKAGE_MANAGER_SENTINEL) which holds package manager data
            eligibleApps.add(0, MAGIC_PACKAGE_MANAGER)

            return eligibleApps
        }

    /**
     * A list of packages that is installed and that we need to re-install for restore,
     * such as user-installed packages or updated system apps.
     */
    val allUserPackages: List<PackageInfo>
        @WorkerThread
        get() {
            // We need the GET_SIGNING_CERTIFICATES flag here,
            // because the package info is used by [ApkBackup] which needs signing info.
            return packageManager.getInstalledPackages(GET_SIGNING_CERTIFICATES)
                .filter { packageInfo -> // only apps that are:
                    !packageInfo.isNotUpdatedSystemApp() && // not vanilla system apps
                        packageInfo.packageName != context.packageName // not this app
                }
        }

    /**
     * A list of packages that will not be backed up,
     * because they are currently force-stopped for example.
     */
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
                    // log packages that don't get backed up
                    if (Log.isLoggable(TAG, INFO)) {
                        Log.i(TAG, "${notAllowed.size} apps do not get backed up:")
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
                packageInfo.allowsBackup()
        }

    val launchableSystemApps: List<ResolveInfo>
        @WorkerThread
        get() {
            // filter intent for apps with a launcher activity
            val i = Intent(ACTION_MAIN).apply {
                addCategory(CATEGORY_LAUNCHER)
            }
            return packageManager.queryIntentActivities(i, MATCH_SYSTEM_ONLY)
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
        val enabled = settingsManager.isBackupEnabled(packageName)

        // We need to explicitly exclude DocumentsProvider and Seedvault.
        // Otherwise, they get killed while backing them up, terminating our backup.
        val excludedPackages = setOf(
            backend.providerPackageName,
            context.packageName
        )

        return enabled && !excludedPackages.contains(packageName)
    }

    private fun logPackages(packages: List<String>) {
        packages.chunked(LOG_MAX_PACKAGES).forEach {
            Log.i(TAG, it.toString())
        }
    }

    private fun PackageInfo.allowsBackup(): Boolean {
        /**
         * TODO: Consider ways of replicating the system's logic so that the user can have
         * advance knowledge of apps that the system will exclude, particularly apps targeting
         * SDK 30 or below.
         *
         * At backup time, the system will filter out any apps that *it* does not want to be
         * backed up. If the user has enabled D2D, *we* generally want to back up as much as
         * possible; part of the point of D2D is to ignore FLAG_ALLOW_BACKUP (allowsBackup).
         * So, we return true.
         * See frameworks/base/services/backup/java/com/android/server/backup/utils/
         * BackupEligibilityRules.java lines 74-81 and 163-167 (tag: android-13.0.0_r8).
         */
        val appInfo = applicationInfo
        return !(packageName == MAGIC_PACKAGE_MANAGER || appInfo == null)
    }

    /**
     * A flag indicating whether or not this package should _not_ be backed up.
     *
     * This happens when the app has opted-out of backup, or when it is stopped.
     */
    private fun PackageInfo.doesNotGetBackedUp(): Boolean {
        if (packageName == MAGIC_PACKAGE_MANAGER || applicationInfo == null) return true
        if (packageName == backend.providerPackageName) return true
        return !allowsBackup() || isStopped()
    }
}

internal fun PackageInfo.isUserVisible(context: Context): Boolean {
    if (packageName == MAGIC_PACKAGE_MANAGER || applicationInfo == null) return false
    val isInstrumentationApp = instrumentation.let { i ->
        // TikTok for example ships instrumentation, so do more checks. See #769
        !i.isNullOrEmpty() && packageName.endsWith(".test") && i[0].splitNames.isEmpty()
    }
    return !isNotUpdatedSystemApp() && !isInstrumentationApp && packageName != context.packageName
}

internal fun PackageInfo.isSystemApp(): Boolean {
    val appInfo = applicationInfo
    if (packageName == MAGIC_PACKAGE_MANAGER || appInfo == null) return true
    return appInfo.flags and FLAG_SYSTEM != 0
}

/**
 * Returns true if this is a system app that hasn't been updated.
 * We don't back up those APKs.
 */
internal fun PackageInfo.isNotUpdatedSystemApp(): Boolean {
    val appInfo = applicationInfo
    if (packageName == MAGIC_PACKAGE_MANAGER || appInfo == null) return true
    val isSystemApp = appInfo.flags and FLAG_SYSTEM != 0
    val isUpdatedSystemApp = appInfo.flags and FLAG_UPDATED_SYSTEM_APP != 0
    return isSystemApp && !isUpdatedSystemApp
}

internal fun PackageInfo.isStopped(): Boolean {
    val appInfo = applicationInfo
    if (packageName == MAGIC_PACKAGE_MANAGER || appInfo == null) return false
    return appInfo.flags and FLAG_STOPPED != 0
}

internal fun PackageInfo.isTestOnly(): Boolean {
    val appInfo = applicationInfo
    if (packageName == MAGIC_PACKAGE_MANAGER || appInfo == null) return false
    return appInfo.flags and FLAG_TEST_ONLY != 0
}
