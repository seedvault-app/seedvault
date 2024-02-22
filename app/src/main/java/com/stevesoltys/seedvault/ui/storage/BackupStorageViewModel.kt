package com.stevesoltys.seedvault.ui.storage

import android.app.Application
import android.app.backup.BackupProgress
import android.app.backup.IBackupManager
import android.app.backup.IBackupObserver
import android.app.job.JobInfo
import android.net.Uri
import android.os.UserHandle
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.lifecycle.viewModelScope
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.storage.StorageBackupJobService
import com.stevesoltys.seedvault.transport.TRANSPORT_ID
import com.stevesoltys.seedvault.worker.AppBackupWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.backup.BackupJobService
import java.io.IOException
import java.util.concurrent.TimeUnit

private val TAG = BackupStorageViewModel::class.java.simpleName

internal class BackupStorageViewModel(
    private val app: Application,
    private val backupManager: IBackupManager,
    private val storageBackup: StorageBackup,
    settingsManager: SettingsManager,
) : StorageViewModel(app, settingsManager) {

    override val isRestoreOperation = false

    override fun onLocationSet(uri: Uri) {
        val isUsb = saveStorage(uri)
        if (isUsb) {
            // disable storage backup if new storage is on USB
            cancelBackupWorkers()
        } else {
            // enable it, just in case the previous storage was on USB,
            // also to update the network requirement of the new storage
            scheduleBackupWorkers()
        }
        viewModelScope.launch(Dispatchers.IO) {
            // remove old storage snapshots and clear cache
            // TODO is this needed? It also does create all 255 chunk folders which takes time
            //  pass a flag to getCurrentBackupSnapshots() to not create missing folders?
            storageBackup.deleteAllSnapshots()
            storageBackup.clearCache()
            try {
                // initialize the new location (if backups are enabled)
                if (backupManager.isBackupEnabled) backupManager.initializeTransportsForUser(
                    UserHandle.myUserId(),
                    arrayOf(TRANSPORT_ID),
                    // if storage is on USB and this is not SetupWizard, do a backup right away
                    InitializationObserver(isUsb && !isSetupWizard)
                ) else {
                    InitializationObserver(false).backupFinished(0)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error starting new RestoreSet", e)
                onInitializationError()
            }
        }
    }

    private fun scheduleBackupWorkers() {
        val storage = settingsManager.getStorage() ?: error("no storage available")
        if (!storage.isUsb) {
            if (backupManager.isBackupEnabled) AppBackupWorker.schedule(app)
            if (settingsManager.isStorageBackupEnabled()) BackupJobService.scheduleJob(
                context = app,
                jobServiceClass = StorageBackupJobService::class.java,
                periodMillis = TimeUnit.HOURS.toMillis(24),
                networkType = if (storage.requiresNetwork) JobInfo.NETWORK_TYPE_UNMETERED
                else JobInfo.NETWORK_TYPE_NONE,
                deviceIdle = false,
                charging = true
            )
        }
    }

    private fun cancelBackupWorkers() {
        AppBackupWorker.unschedule(app)
        BackupJobService.cancelJob(app)
    }

    @WorkerThread
    private inner class InitializationObserver(val requestBackup: Boolean) :
        IBackupObserver.Stub() {
        override fun onUpdate(currentBackupPackage: String, backupProgress: BackupProgress) {
            // noop
        }

        override fun onResult(target: String, status: Int) {
            // noop
        }

        override fun backupFinished(status: Int) {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "Initialization finished. Status: $status")
            }
            if (status == 0) {
                // notify the UI that the location has been set
                mLocationChecked.postEvent(LocationResult())
                if (requestBackup) {
                    AppBackupWorker.scheduleNow(app)
                }
            } else {
                // notify the UI that the location was invalid
                onInitializationError()
            }
        }
    }

    private fun onInitializationError() {
        val errorMsg = app.getString(R.string.storage_check_fragment_backup_error)
        mLocationChecked.postEvent(LocationResult(errorMsg))
    }

}
