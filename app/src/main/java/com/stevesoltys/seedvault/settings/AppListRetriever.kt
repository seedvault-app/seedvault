package com.stevesoltys.seedvault.settings

import android.annotation.StringRes
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
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
import com.stevesoltys.seedvault.ui.notification.getAppName
import java.util.Locale

private const val TAG = "AppListRetriever"

private const val PACKAGE_NAME_SMS = "com.android.providers.telephony"
private const val PACKAGE_NAME_SETTINGS = "com.android.providers.settings"
private const val PACKAGE_NAME_CALL_LOG = "com.android.calllogbackup"
private const val PACKAGE_NAME_CONTACTS = "org.calyxos.backup.contacts"

sealed class AppListItem

data class AppStatus(
    val packageName: String,
    var enabled: Boolean,
    val icon: Drawable,
    val name: String,
    val time: Long,
    val status: AppBackupState,
    val isSpecial: Boolean = false
) : AppListItem()

class AppSectionTitle(@StringRes val titleRes: Int) : AppListItem()

internal class AppListRetriever(
    private val context: Context,
    private val packageService: PackageService,
    private val settingsManager: SettingsManager,
    private val metadataManager: MetadataManager
) {

    private val pm: PackageManager = context.packageManager

    @WorkerThread
    fun getAppList(): List<AppListItem> {
        return listOf(AppSectionTitle(R.string.backup_section_system)) + getSpecialApps() +
            listOf(AppSectionTitle(R.string.backup_section_user)) + getUserApps() +
            listOf(AppSectionTitle(R.string.backup_section_not_allowed)) + getNotAllowedApps()
    }

    private fun getSpecialApps(): List<AppListItem> {
        val specialPackages = listOf(
            Pair(PACKAGE_NAME_SMS, R.string.backup_sms),
            Pair(PACKAGE_NAME_SETTINGS, R.string.backup_settings),
            Pair(PACKAGE_NAME_CALL_LOG, R.string.backup_call_log),
            Pair(PACKAGE_NAME_CONTACTS, R.string.backup_contacts)
        )
        return specialPackages.map { (packageName, stringId) ->
            val metadata = metadataManager.getPackageMetadata(packageName)
            val status = if (packageName == PACKAGE_NAME_CONTACTS && metadata?.state == null) {
                // handle local contacts backup specially as it might not be installed
                if (packageService.getVersionName(packageName) == null) FAILED_NOT_INSTALLED
                else NOT_YET_BACKED_UP
            } else metadata?.state.toAppBackupState()
            AppStatus(
                packageName = packageName,
                enabled = settingsManager.isBackupEnabled(packageName),
                icon = getIcon(packageName),
                name = context.getString(stringId),
                time = metadata?.time ?: 0,
                status = status,
                isSpecial = true
            )
        }
    }

    private fun getUserApps(): List<AppStatus> {
        val locale = Locale.getDefault()
        return packageService.userApps.map {
            val metadata = metadataManager.getPackageMetadata(it.packageName)
            val time = metadata?.time ?: 0
            val status = metadata?.state.toAppBackupState()
            if (status == NOT_YET_BACKED_UP) {
                Log.w(TAG, "No metadata available for: ${it.packageName}")
            }
            if (metadata?.hasApk() == false) {
                Log.w(TAG, "No APK stored for: ${it.packageName}")
            }
            AppStatus(
                packageName = it.packageName,
                enabled = settingsManager.isBackupEnabled(it.packageName),
                icon = getIcon(it.packageName),
                name = getAppName(context, it.packageName).toString(),
                time = time,
                status = status
            )
        }.sortedBy { it.name.toLowerCase(locale) }
    }

    private fun getNotAllowedApps(): List<AppStatus> {
        val locale = Locale.getDefault()
        return packageService.userNotAllowedApps.map {
            AppStatus(
                packageName = it.packageName,
                enabled = settingsManager.isBackupEnabled(it.packageName),
                icon = getIcon(it.packageName),
                name = getAppName(context, it.packageName).toString(),
                time = 0,
                status = FAILED_NOT_ALLOWED
            )
        }.sortedBy { it.name.toLowerCase(locale) }
    }

    private fun getIcon(packageName: String): Drawable = when (packageName) {
        MAGIC_PACKAGE_MANAGER -> context.getDrawable(R.drawable.ic_launcher_default)!!
        PACKAGE_NAME_SMS -> context.getDrawable(R.drawable.ic_message)!!
        PACKAGE_NAME_SETTINGS -> context.getDrawable(R.drawable.ic_settings)!!
        PACKAGE_NAME_CALL_LOG -> context.getDrawable(R.drawable.ic_call)!!
        PACKAGE_NAME_CONTACTS -> context.getDrawable(R.drawable.ic_contacts)!!
        else -> getIconFromPackageManager(packageName)
    }

    private fun getIconFromPackageManager(packageName: String): Drawable = try {
        pm.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        context.getDrawable(R.drawable.ic_launcher_default)!!
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
