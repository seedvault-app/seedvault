package com.stevesoltys.seedvault.settings

import android.app.Application
import android.content.pm.PackageManager.NameNotFoundException
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
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
import com.stevesoltys.seedvault.permitDiskReads
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.transport.requestBackup
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED_NOT_ALLOWED
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED_NO_DATA
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED_QUOTA_EXCEEDED
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED_WAS_STOPPED
import com.stevesoltys.seedvault.ui.AppBackupState.NOT_YET_BACKED_UP
import com.stevesoltys.seedvault.ui.AppBackupState.SUCCEEDED
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

    private val contentResolver = app.contentResolver
    private val connectivityManager = app.getSystemService(ConnectivityManager::class.java)

    override val isRestoreOperation = false

    private val mBackupPossible = MutableLiveData<Boolean>(false)
    val backupPossible: LiveData<Boolean> = mBackupPossible

    internal val lastBackupTime = metadataManager.lastBackupTime

    private val mAppStatusList = switchMap(lastBackupTime) {
        // updates app list when lastBackupTime changes
        getAppStatusResult()
    }
    internal val appStatusList: LiveData<AppStatusResult> = mAppStatusList

    private val mAppEditMode = MutableLiveData<Boolean>()
    internal val appEditMode: LiveData<Boolean> = mAppEditMode

    private val storageObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean, uris: MutableCollection<Uri>, flags: Int) {
            onStorageLocationChanged()
        }
    }

    private inner class NetworkObserver : ConnectivityManager.NetworkCallback() {
        var registered = false
        override fun onAvailable(network: Network) {
            onStorageLocationChanged()
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            onStorageLocationChanged()
        }
    }

    private val networkCallback = NetworkObserver()

    init {
        val scope = permitDiskReads {
            // this shouldn't cause disk reads, but it still does
            viewModelScope
        }
        scope.launch {
            // ensures the lastBackupTime LiveData gets set
            metadataManager.getLastBackupTime()
        }
        onStorageLocationChanged()
    }

    override fun onStorageLocationChanged() {
        val storage = settingsManager.getStorage() ?: return

        // register storage observer
        contentResolver.unregisterContentObserver(storageObserver)
        contentResolver.registerContentObserver(storage.uri, false, storageObserver)

        // register network observer if needed
        if (networkCallback.registered && !storage.requiresNetwork) {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallback.registered = false
        } else if (!networkCallback.registered && storage.requiresNetwork) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            networkCallback.registered = true
        }

        viewModelScope.launch(Dispatchers.IO) {
            val canDo = settingsManager.canDoBackupNow()
            mBackupPossible.postValue(canDo)
        }
    }

    override fun onCleared() {
        contentResolver.unregisterContentObserver(storageObserver)
        if (networkCallback.registered) {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallback.registered = false
        }
    }

    internal fun backupNow() {
        // maybe replace the check below with one that checks if our transport service is running
        if (notificationManager.hasActiveBackupNotifications()) {
            Toast.makeText(app, R.string.notification_backup_already_running, LENGTH_LONG).show()
        } else {
            Thread { requestBackup(app) }.start()
        }
    }

    private fun getAppStatusResult(): LiveData<AppStatusResult> = liveData(Dispatchers.Default) {
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
