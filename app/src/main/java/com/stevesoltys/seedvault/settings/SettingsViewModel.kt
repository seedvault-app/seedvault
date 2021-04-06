package com.stevesoltys.seedvault.settings

import android.app.Application
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
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations.switchMap
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil.calculateDiff
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.permitDiskReads
import com.stevesoltys.seedvault.transport.requestBackup
import com.stevesoltys.seedvault.ui.RequireProvisioningViewModel
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "SettingsViewModel"
private const val USER_FULL_DATA_BACKUP_AWARE = "user_full_data_backup_aware"

internal class SettingsViewModel(
    app: Application,
    settingsManager: SettingsManager,
    keyManager: KeyManager,
    private val notificationManager: BackupNotificationManager,
    private val metadataManager: MetadataManager,
    private val appListRetriever: AppListRetriever
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
        try {
            contentResolver.unregisterContentObserver(storageObserver)
            contentResolver.registerContentObserver(storage.uri, false, storageObserver)
        } catch (e: SecurityException) {
            // This can happen if the app providing the storage was uninstalled.
            // validLocationIsSet() gets called elsewhere and prompts for a new storage location.
            Log.e(TAG, "Error registering content observer for ${storage.uri}", e)
        }

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
        val list = appListRetriever.getAppList()
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
     * Disables AOSP's call log backup
     *
     * AOSP's call log backup is disabled if [USER_FULL_DATA_BACKUP_AWARE]
     * is not set. This method unsets this flag, if it is set.
     * No other apps were found to check for this, so this should affect only call log.
     */
    fun disableCallLogKVBackup() {
        // first check if the flag is already set
        if (Settings.Secure.getInt(app.contentResolver, USER_FULL_DATA_BACKUP_AWARE, 0) == 1) {
            Settings.Secure.putInt(app.contentResolver, USER_FULL_DATA_BACKUP_AWARE, 0)
        }
    }

}
