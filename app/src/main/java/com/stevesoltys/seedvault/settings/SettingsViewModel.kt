/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.settings

import android.app.Application
import android.app.backup.IBackupManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.BadParcelableException
import android.os.Process.myUid
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.annotation.UiThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil.calculateDiff
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.WorkManager
import com.stevesoltys.seedvault.BackupStateManager
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.permitDiskReads
import com.stevesoltys.seedvault.repo.Checker
import com.stevesoltys.seedvault.settings.BackupPermission.BackupAllowed
import com.stevesoltys.seedvault.settings.BackupPermission.BackupRestricted
import com.stevesoltys.seedvault.ui.LiveEvent
import com.stevesoltys.seedvault.ui.MutableLiveEvent
import com.stevesoltys.seedvault.ui.RequireProvisioningViewModel
import com.stevesoltys.seedvault.worker.AppCheckerWorker
import com.stevesoltys.seedvault.worker.BackupRequester
import com.stevesoltys.seedvault.worker.BackupRequester.Companion.requestFilesAndAppBackup
import com.stevesoltys.seedvault.worker.FileBackupWorker
import com.stevesoltys.seedvault.worker.FileBackupWorker.Companion.UNIQUE_WORK_NAME
import com.stevesoltys.seedvault.worker.FileCheckerWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.calyxos.backup.storage.api.SnapshotRetention
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.seedvault.core.backends.saf.SafProperties
import java.io.IOException
import java.lang.Runtime.getRuntime

private const val TAG = "SettingsViewModel"
private const val USER_FULL_DATA_BACKUP_AWARE = "user_full_data_backup_aware"

sealed class BackupPermission {
    object BackupAllowed : BackupPermission()
    class BackupRestricted(val unavailableUsb: Boolean = false) : BackupPermission()
}

internal class SettingsViewModel(
    app: Application,
    settingsManager: SettingsManager,
    keyManager: KeyManager,
    backendManager: BackendManager,
    private val appListRetriever: AppListRetriever,
    private val storageBackup: StorageBackup,
    private val backupManager: IBackupManager,
    private val backupRequester: BackupRequester,
    private val checker: Checker,
    backupStateManager: BackupStateManager,
) : RequireProvisioningViewModel(app, settingsManager, keyManager, backendManager) {

    private val contentResolver = app.contentResolver
    private val connectivityManager: ConnectivityManager? =
        app.getSystemService(ConnectivityManager::class.java)
    private val workManager = WorkManager.getInstance(app)

    override val isRestoreOperation = false
    val isFirstStart get() = settingsManager.isFirstStart

    private val isBackupRunning: StateFlow<Boolean>
    private val isCheckOrPruneRunning: StateFlow<Boolean>
    private val mBackupPossible = MutableLiveData<BackupPermission>(BackupRestricted())
    val backupPossible: LiveData<BackupPermission> = mBackupPossible

    private val mBackupSize = MutableLiveData<Long>()
    val backupSize: LiveData<Long> = mBackupSize
    private val mFilesBackupSize = MutableLiveData<Long>()
    val filesBackupSize: LiveData<Long> = mFilesBackupSize

    internal val lastBackupTime = settingsManager.lastBackupTime
    internal val backupWorkInfo =
        workManager.getWorkInfosForUniqueWorkLiveData(UNIQUE_WORK_NAME).map {
            it.getOrNull(0)
        }

    private val mAppStatusList = lastBackupTime.switchMap {
        // updates app list when lastBackupTime changes
        getAppStatusResult()
    }
    internal val appStatusList: LiveData<AppStatusResult> = mAppStatusList

    private val mAppEditMode = MutableLiveData<Boolean>()
    internal val appEditMode: LiveData<Boolean> = mAppEditMode

    private val mFilesSummary = MutableLiveData<String>()
    internal val filesSummary: LiveData<String> = mFilesSummary

    private val mInitEvent = MutableLiveEvent<Boolean>()
    val initEvent: LiveEvent<Boolean> = mInitEvent

    private val storageObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean, uris: MutableCollection<Uri>, flags: Int) {
            onBackendPropertiesChanged()
        }
    }

    private inner class NetworkObserver : ConnectivityManager.NetworkCallback() {
        var registered = false
        override fun onAvailable(network: Network) {
            onBackendPropertiesChanged()
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            onBackendPropertiesChanged()
        }
    }

    private val networkCallback = NetworkObserver()

    init {
        val scope = permitDiskReads {
            // this shouldn't cause disk reads, but it still does
            viewModelScope
        }
        isBackupRunning = backupStateManager.isBackupRunning.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )
        isCheckOrPruneRunning = backupStateManager.isCheckOrPruneRunning.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )
        scope.launch {
            // update running state
            isBackupRunning.collect {
                onBackupRunningStateChanged()
            }
        }
        scope.launch {
            // update running state
            isCheckOrPruneRunning.collect {
                onBackupRunningStateChanged()
            }
        }
        onBackendPropertiesChanged()
        loadFilesSummary()
    }

    private suspend fun onBackupRunningStateChanged() = withContext(Dispatchers.IO) {
        val backupAllowed = !isBackupRunning.value && !isCheckOrPruneRunning.value
        if (backupAllowed) {
            if (backendManager.isOnUnavailableUsb()) {
                updateBackupPossible(BackupRestricted(unavailableUsb = true))
            } else {
                updateBackupPossible(BackupAllowed)
            }
        } else updateBackupPossible(BackupRestricted())
    }

    /**
     * Updates [mBackupPossible] on the UiThread to avoid race conditions.
     */
    private suspend fun updateBackupPossible(newValue: BackupPermission) {
        withContext(Dispatchers.Main) {
            mBackupPossible.value = newValue
        }
    }

    override fun onBackendPropertiesChanged() {
        val properties = backendManager.backendProperties ?: return

        Log.d(TAG, "onStoragePropertiesChanged")
        if (properties is SafProperties) {
            // register storage observer
            try {
                contentResolver.unregisterContentObserver(storageObserver)
                contentResolver.registerContentObserver(properties.uri, false, storageObserver)
            } catch (e: SecurityException) {
                // This can happen if the app providing the storage was uninstalled.
                // validLocationIsSet() gets called elsewhere
                // and prompts for a new storage location.
                Log.e(TAG, "Error registering content observer for ${properties.uri}", e)
            }
        }

        // register network observer if needed
        if (networkCallback.registered && !properties.requiresNetwork) {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            networkCallback.registered = false
        } else if (!networkCallback.registered && properties.requiresNetwork) {
            // TODO we may want to warn the user when they start a backup on a metered connection
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
            networkCallback.registered = true
        }
        // update whether we can do backups right now or not
        viewModelScope.launch { onBackupRunningStateChanged() }
    }

    override fun onCleared() {
        contentResolver.unregisterContentObserver(storageObserver)
        if (networkCallback.registered) {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            networkCallback.registered = false
        }
    }

    internal fun backupNow() {
        val reschedule = !backendManager.isOnRemovableDrive
        requestFilesAndAppBackup(app, settingsManager, backupManager, reschedule)
    }

    private fun getAppStatusResult(): LiveData<AppStatusResult> = liveData(Dispatchers.Default) {
        val list = try {
            Log.i(TAG, "Loading app list...")
            appListRetriever.getAppList()
        } catch (e: BadParcelableException) {
            Log.e(TAG, "Error getting app list: ", e)
            emptyList()
        }
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
        mFilesSummary.value = uriSummary.ifEmpty {
            app.getString(R.string.settings_backup_files_summary)
        }
        // set our own retention here to fix a historic bug with huge retention:
        val retention = SnapshotRetention(
            daily = 7,
            weekly = 4,
            monthly = 3,
            yearly = 2,
        )
        storageBackup.setSnapshotRetention(retention)
    }

    fun onBackupEnabled(enabled: Boolean) {
        if (enabled) {
            scheduleBackups()
            // enable call log backups for existing installs (added end of 2020)
            enableCallLogBackup()
        } else {
            unscheduleAppBackup()
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

    fun scheduleBackups(existingWorkPolicy: ExistingPeriodicWorkPolicy) {
        // disable framework scheduling, because another transport may have enabled it
        backupManager.setFrameworkSchedulingEnabledForUser(UserHandle.myUserId(), false)
        if (!backendManager.isOnRemovableDrive && backupRequester.isBackupEnabled) {
            FileBackupWorker.schedule(app, settingsManager, existingWorkPolicy)
        }
    }

    /**
     * Call only when app or file backup is actually enabled.
     */
    fun scheduleBackups() {
        if (backendManager.isOnRemovableDrive) {
            Log.i(TAG, "Not scheduling files backup, because using removable drive")
            return
        } else {
            Log.i(TAG, "Scheduling file backup...")
        }
        val workManager = WorkManager.getInstance(app)
        backupManager.setFrameworkSchedulingEnabledForUser(UserHandle.myUserId(), false)
        viewModelScope.launch {
            val workInfos = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).first()
            val workInfo = workInfos.getOrNull(0)
            Log.i(TAG, "Existing workInfo: $workInfo")
            if (workInfo == null) {
                FileBackupWorker.schedule(app, settingsManager, CANCEL_AND_REENQUEUE)
            } else {
                FileBackupWorker.schedule(app, settingsManager, UPDATE)
            }
        }
    }

    private fun unscheduleAppBackup() {
        if (!backupRequester.isFileBackupEnabled) FileBackupWorker.unschedule(app)
    }

    fun unscheduleFileBackup() {
        if (!backupRequester.isAppBackupEnabled) FileBackupWorker.unschedule(app)
    }

    fun loadBackupSize() {
        viewModelScope.launch(Dispatchers.IO) {
            mBackupSize.postValue(checker.getBackupSize())
        }
    }

    fun loadFileBackupSize() {
        viewModelScope.launch(Dispatchers.IO) {
            mFilesBackupSize.postValue(storageBackup.getBackupSize())
        }
    }

    fun checkAppBackups(percent: Int) {
        AppCheckerWorker.scheduleNow(app, percent)
    }

    fun checkFileBackups(percent: Int) {
        FileCheckerWorker.scheduleNow(app, percent)
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
                    // first log command, so we see if it is correct, e.g. has our own uid
                    outputStream.write("$command\n\n".toByteArray())
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
}
