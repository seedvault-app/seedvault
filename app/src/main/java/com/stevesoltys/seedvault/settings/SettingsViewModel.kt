package com.stevesoltys.seedvault.settings

import android.app.Application
import android.content.pm.PackageManager.NameNotFoundException
import android.util.Log
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
import com.stevesoltys.seedvault.getAppName
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
import com.stevesoltys.seedvault.metadata.PackageState.NOT_ALLOWED
import com.stevesoltys.seedvault.metadata.PackageState.NO_DATA
import com.stevesoltys.seedvault.metadata.PackageState.QUOTA_EXCEEDED
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED_NOT_ALLOWED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED_NO_DATA
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED_QUOTA_EXCEEDED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.SUCCEEDED
import com.stevesoltys.seedvault.transport.backup.isSystemApp
import com.stevesoltys.seedvault.transport.requestBackup
import com.stevesoltys.seedvault.ui.RequireProvisioningViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

private val TAG = SettingsViewModel::class.java.simpleName

class SettingsViewModel(
    app: Application,
    settingsManager: SettingsManager,
    keyManager: KeyManager,
    private val metadataManager: MetadataManager
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
        Thread { requestBackup(app) }.start()
    }

    private fun getAppStatusResult(): LiveData<AppStatusResult> = liveData {
        val pm = app.packageManager
        val locale = Locale.getDefault()
        val list = pm.getInstalledPackages(0)
            .filter { !it.isSystemApp() }
            .map {
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
                        FAILED
                    }
                    NO_DATA -> FAILED_NO_DATA
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

}
