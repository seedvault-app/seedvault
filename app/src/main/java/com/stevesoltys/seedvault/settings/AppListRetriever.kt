/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.settings

import android.annotation.StringRes
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.PackageState
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.ui.AppBackupState
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED_NOT_ALLOWED
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED_NOT_INSTALLED
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED_NO_DATA
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED_QUOTA_EXCEEDED
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED_WAS_STOPPED
import com.stevesoltys.seedvault.ui.AppBackupState.NOT_YET_BACKED_UP
import com.stevesoltys.seedvault.ui.AppBackupState.SUCCEEDED
import com.stevesoltys.seedvault.ui.PACKAGE_NAME_CONTACTS
import com.stevesoltys.seedvault.ui.notification.getAppName
import com.stevesoltys.seedvault.ui.systemData
import java.util.Locale

private const val TAG = "AppListRetriever"

sealed class AppListItem

data class AppStatus(
    val packageName: String,
    var enabled: Boolean,
    val icon: Drawable,
    val name: String,
    val time: Long,
    val size: Long?,
    val status: AppBackupState,
    val isSpecial: Boolean = false,
) : AppListItem()

class AppSectionTitle(@StringRes val titleRes: Int) : AppListItem()

internal class AppListRetriever(
    private val context: Context,
    private val packageService: PackageService,
    private val settingsManager: SettingsManager,
    private val metadataManager: MetadataManager,
) {

    private val pm: PackageManager = context.packageManager

    @WorkerThread
    fun getAppList(): List<AppListItem> {

        val appListSections = linkedMapOf(
            AppSectionTitle(R.string.backup_section_system) to getSpecialApps(),
            AppSectionTitle(R.string.backup_section_user) to getApps(),
            AppSectionTitle(R.string.backup_section_not_allowed) to getNotAllowedApps()
        ).filter { it.value.isNotEmpty() }

        return appListSections.flatMap { (sectionTitle, appList) ->
            listOf(sectionTitle) + appList
        }
    }

    private fun getSpecialApps(): List<AppListItem> {
        return systemData.map { (packageName, data) ->
            val metadata = metadataManager.getPackageMetadata(packageName)
            val status = if (packageName == PACKAGE_NAME_CONTACTS && metadata?.state == null) {
                // handle local contacts backup specially as it might not be installed
                if (packageService.getVersionName(packageName) == null) FAILED_NOT_INSTALLED
                else NOT_YET_BACKED_UP
            } else metadata?.state.toAppBackupState()
            AppStatus(
                packageName = packageName,
                enabled = settingsManager.isBackupEnabled(packageName),
                icon = data.iconRes?.let { getDrawable(context, it) }
                    ?: getIconFromPackageManager(packageName),
                name = context.getString(data.nameRes),
                time = metadata?.time ?: 0,
                size = metadata?.size,
                status = status,
                isSpecial = true,
            )
        }
    }

    private fun getApps(): List<AppStatus> {
        val userPackages = mutableSetOf<String>()
        val userApps = packageService.userApps.map {
            userPackages.add(it.packageName)
            val metadata = metadataManager.getPackageMetadata(it.packageName)
            val time = metadata?.time ?: 0
            val status = metadata?.state.toAppBackupState()
            if (status == NOT_YET_BACKED_UP) {
                Log.w(TAG, "No metadata available for: ${it.packageName}")
            }
            AppStatus(
                packageName = it.packageName,
                enabled = settingsManager.isBackupEnabled(it.packageName),
                icon = getIconFromPackageManager(it.packageName),
                name = getAppName(context, it.packageName).toString(),
                time = time,
                size = metadata?.size,
                status = status,
            )
        }
        val locale = Locale.getDefault()
        return (userApps + packageService.launchableSystemApps.mapNotNull {
            val packageName = it.activityInfo.packageName
            if (packageName in userPackages) return@mapNotNull null
            val metadata = metadataManager.getPackageMetadata(packageName)
            AppStatus(
                packageName = packageName,
                enabled = settingsManager.isBackupEnabled(packageName),
                icon = getIconFromPackageManager(packageName),
                name = it.loadLabel(context.packageManager).toString(),
                time = metadata?.time ?: 0,
                size = metadata?.size,
                status = metadata?.state.toAppBackupState(),
            )
        }).sortedBy { it.name.lowercase(locale) }
    }

    private fun getNotAllowedApps(): List<AppStatus> {
        val locale = Locale.getDefault()
        return packageService.userNotAllowedApps.map {
            AppStatus(
                packageName = it.packageName,
                enabled = settingsManager.isBackupEnabled(it.packageName),
                icon = getIconFromPackageManager(it.packageName),
                name = getAppName(context, it.packageName).toString(),
                time = 0,
                size = null,
                status = FAILED_NOT_ALLOWED,
            )
        }.sortedBy { it.name.lowercase(locale) }
    }

    private fun getIconFromPackageManager(packageName: String): Drawable = try {
        pm.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        getDrawable(context, R.drawable.ic_launcher_default)!!
    }

    private fun PackageState?.toAppBackupState(): AppBackupState = when (this) {
        null -> NOT_YET_BACKED_UP
        PackageState.NO_DATA -> FAILED_NO_DATA
        PackageState.WAS_STOPPED -> FAILED_WAS_STOPPED
        PackageState.NOT_ALLOWED -> FAILED_NOT_ALLOWED
        PackageState.QUOTA_EXCEEDED -> FAILED_QUOTA_EXCEEDED
        PackageState.UNKNOWN_ERROR -> FAILED
        PackageState.APK_AND_DATA -> SUCCEEDED
    }

}
