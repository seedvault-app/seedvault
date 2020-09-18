package com.stevesoltys.seedvault.settings

import android.app.Application
import android.content.pm.PackageManager.NameNotFoundException
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat.getDrawable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations.switchMap
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil.calculateDiff
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
import com.stevesoltys.seedvault.metadata.PackageState.NOT_ALLOWED
import com.stevesoltys.seedvault.metadata.PackageState.NO_DATA
import com.stevesoltys.seedvault.metadata.PackageState.QUOTA_EXCEEDED
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.metadata.PackageState.WAS_STOPPED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED_NOT_ALLOWED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED_NO_DATA
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED_QUOTA_EXCEEDED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED_WAS_STOPPED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.NOT_YET_BACKED_UP
import com.stevesoltys.seedvault.restore.AppRestoreStatus.SUCCEEDED
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.transport.requestBackup
import com.stevesoltys.seedvault.ui.RequireProvisioningViewModel
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.notification.getAppName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

private const val USER_FULL_DATA_BACKUP_AWARE = "user_full_data_backup_aware"

private val TAG = SettingsViewModel::class.java.simpleName

internal class SettingsViewModel(
    app: Application,
    settingsManager: SettingsManager,
    keyManager: KeyManager,
    private val notificationManager: BackupNotificationManager,
    private val metadataManager: MetadataManager,
    private val packageService: PackageService
) : RequireProvisioningViewModel(app, settingsManager, keyManager) {

    override val isRestoreOperation = false

    internal val lastBackupTime = metadataManager.lastBackupTime

    private val mAppStatusList = switchMap(lastBackupTime) {
        // updates app list when lastBackupTime changes
        getAppStatusResult()
    }
    internal val appStatusList: LiveData<AppStatusResult> = mAppStatusList

    private val mAppEditMode = MutableLiveData<Boolean>()
    internal val appEditMode: LiveData<Boolean> = mAppEditMode

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // ensures the lastBackupTime LiveData gets set
            metadataManager.getLastBackupTime()
        }
    }

    internal fun backupNow() {
        if (notificationManager.hasActiveBackupNotifications()) {
            Toast.makeText(app, R.string.notification_backup_already_running, LENGTH_LONG).show()
        } else {
            Thread { requestBackup(app) }.start()
        }
    }

    private fun getAppStatusResult(): LiveData<AppStatusResult> = liveData {
        val pm = app.packageManager
        val locale = Locale.getDefault()
        val list = packageService.userApps.map {
            val icon = if (it.packageName == MAGIC_PACKAGE_MANAGER) {
                getDrawable(app, R.drawable.ic_launcher_default)!!
            } else {
                try {
                    pm.getApplicationIcon(it.packageName)
                } catch (e: NameNotFoundException) {
                    getDrawable(app, R.drawable.ic_launcher_default)!!
                }
            }
            val metadata = metadataManager.getPackageMetadata(it.packageName)
            val time = metadata?.time ?: 0
            val status = when (metadata?.state) {
                null -> {
                    Log.w(TAG, "No metadata available for: ${it.packageName}")
                    NOT_YET_BACKED_UP
                }
                NO_DATA -> FAILED_NO_DATA
                WAS_STOPPED -> FAILED_WAS_STOPPED
                NOT_ALLOWED -> FAILED_NOT_ALLOWED
                QUOTA_EXCEEDED -> FAILED_QUOTA_EXCEEDED
                UNKNOWN_ERROR -> FAILED
                APK_AND_DATA -> SUCCEEDED
            }
            if (metadata?.hasApk() == false) {
                Log.w(TAG, "No APK stored for: ${it.packageName}")
            }
            AppStatus(
                packageName = it.packageName,
                enabled = settingsManager.isBackupEnabled(it.packageName),
                icon = icon,
                name = getAppName(app, it.packageName).toString(),
                time = time,
                status = status
            )
        }.sortedBy { it.name.toLowerCase(locale) }
        val oldList = mAppStatusList.value?.appStatusList ?: emptyList()
        val diff = calculateDiff(AppStatusDiff(oldList, list))
        emit(AppStatusResult(list, diff))
    }

    @UiThread
    fun setEditMode(enabled: Boolean) {
        mAppEditMode.value = enabled
    }

    @UiThread
    fun onAppStatusToggled(status: AppStatus) {
        settingsManager.onAppBackupStatusChanged(status)
    }

    /**
     * Ensures that the call log will be included in backups.
     *
     * An AOSP code search found that call log backups get disabled if [USER_FULL_DATA_BACKUP_AWARE]
     * is not set. This method sets this flag, if it is not already set.
     * No other apps were found to check for this, so this should affect only call log.
     */
    fun enableCallLogBackup() {
        // first check if the flag is already set
        if (Settings.Secure.getInt(app.contentResolver, USER_FULL_DATA_BACKUP_AWARE, 0) == 0) {
            Settings.Secure.putInt(app.contentResolver, USER_FULL_DATA_BACKUP_AWARE, 1)
        }
    }

}
