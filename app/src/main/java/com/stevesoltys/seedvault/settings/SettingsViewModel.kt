package com.stevesoltys.seedvault.settings

import android.app.Application
import android.app.backup.IBackupManager
import android.app.job.JobInfo.NETWORK_TYPE_NONE
import android.app.job.JobInfo.NETWORK_TYPE_UNMETERED
import android.content.Intent
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Process.myUid
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat.startForegroundService
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil.calculateDiff
import com.stevesoltys.seedvault.BackupWorker
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.permitDiskReads
import com.stevesoltys.seedvault.storage.StorageBackupJobService
import com.stevesoltys.seedvault.storage.StorageBackupService
import com.stevesoltys.seedvault.storage.StorageBackupService.Companion.EXTRA_START_APP_BACKUP
import com.stevesoltys.seedvault.transport.requestBackup
import com.stevesoltys.seedvault.ui.RequireProvisioningViewModel
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.backup.BackupJobService
import java.io.IOException
import java.lang.Runtime.getRuntime
import java.util.concurrent.TimeUnit.HOURS

private const val TAG = "SettingsViewModel"
private const val USER_FULL_DATA_BACKUP_AWARE = "user_full_data_backup_aware"

internal class SettingsViewModel(
    app: Application,
    settingsManager: SettingsManager,
    keyManager: KeyManager,
    private val notificationManager: BackupNotificationManager,
    private val metadataManager: MetadataManager,
    private val appListRetriever: AppListRetriever,
    private val storageBackup: StorageBackup,
    private val backupManager: IBackupManager,
) : RequireProvisioningViewModel(app, settingsManager, keyManager) {

    private val contentResolver = app.contentResolver
    private val connectivityManager: ConnectivityManager? =
        app.getSystemService(ConnectivityManager::class.java)

    override val isRestoreOperation = false

    private val mBackupPossible = MutableLiveData(false)
    val backupPossible: LiveData<Boolean> = mBackupPossible

    internal val lastBackupTime = metadataManager.lastBackupTime

    private val mAppStatusList = lastBackupTime.switchMap {
        // updates app list when lastBackupTime changes
        getAppStatusResult()
    }
    internal val appStatusList: LiveData<AppStatusResult> = mAppStatusList

    private val mAppEditMode = MutableLiveData<Boolean>()
    internal val appEditMode: LiveData<Boolean> = mAppEditMode

    private val _filesSummary = MutableLiveData<String>()
    internal val filesSummary: LiveData<String> = _filesSummary

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
        loadFilesSummary()
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
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            networkCallback.registered = false
        } else if (!networkCallback.registered && storage.requiresNetwork) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
            networkCallback.registered = true
        }

        if (settingsManager.isStorageBackupEnabled()) {
            // disable storage backup if new storage is on USB
            if (storage.isUsb) disableStorageBackup()
            // enable it, just in case the previous storage was on USB,
            // also to update the network requirement of the new storage
            else enableStorageBackup()
        }

        viewModelScope.launch(Dispatchers.IO) {
            val canDo = settingsManager.canDoBackupNow()
            mBackupPossible.postValue(canDo)
        }
    }

    override fun onCleared() {
        contentResolver.unregisterContentObserver(storageObserver)
        if (networkCallback.registered) {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            networkCallback.registered = false
        }
    }

    internal fun backupNow() {
        // maybe replace the check below with one that checks if our transport service is running
        if (notificationManager.hasActiveBackupNotifications()) {
            Toast.makeText(app, R.string.notification_backup_already_running, LENGTH_LONG).show()
        } else if (!backupManager.isBackupEnabled) {
            Toast.makeText(app, R.string.notification_backup_disabled, LENGTH_LONG).show()
        } else viewModelScope.launch(Dispatchers.IO) {
            if (settingsManager.isStorageBackupEnabled()) {
                val i = Intent(app, StorageBackupService::class.java)
                // this starts an app backup afterwards
                i.putExtra(EXTRA_START_APP_BACKUP, true)
                startForegroundService(app, i)
            } else {
                requestBackup(app)
            }
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

    @UiThread
    fun loadFilesSummary() = viewModelScope.launch {
        val uriSummary = storageBackup.getUriSummaryString()
        _filesSummary.value = uriSummary.ifEmpty {
            app.getString(R.string.settings_backup_files_summary)
        }
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

    fun hasMainKey(): Boolean {
        return keyManager.hasMainKey()
    }

    fun enableStorageBackup() {
        val storage = settingsManager.getStorage() ?: error("no storage available")
        if (!storage.isUsb) BackupJobService.scheduleJob(
            context = app,
            jobServiceClass = StorageBackupJobService::class.java,
            periodMillis = HOURS.toMillis(24),
            networkType = if (storage.requiresNetwork) NETWORK_TYPE_UNMETERED
            else NETWORK_TYPE_NONE,
            deviceIdle = false,
            charging = true
        )
    }

    fun disableStorageBackup() {
        BackupJobService.cancelJob(app)
    }

    fun onLogcatUriReceived(uri: Uri?) = viewModelScope.launch(Dispatchers.IO) {
        if (uri == null) {
            onLogcatError()
            return@launch
        }
        // 1000 is system uid, needed to get backup logs from the OS code.
        val command = "logcat -d --uid=1000,${myUid()} *:V"
        try {
            app.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                getRuntime().exec(command).inputStream.use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IOException("OutputStream was null")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving logcat ", e)
            onLogcatError()
        }
    }

    private suspend fun onLogcatError() = withContext(Dispatchers.Main) {
        val str = app.getString(R.string.settings_expert_logcat_error)
        Toast.makeText(app, str, LENGTH_LONG).show()
    }

    fun onD2dChanged(enabled: Boolean) {
        backupManager.setFrameworkSchedulingEnabledForUser(UserHandle.myUserId(), !enabled)
        if (enabled) {
            BackupWorker.schedule(app)
        } else {
            BackupWorker.unschedule(app)
        }
    }

}
