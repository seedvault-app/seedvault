package com.stevesoltys.seedvault.ui.storage

import android.app.Application
import android.app.backup.IBackupManager
import android.app.job.JobInfo
import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.storage.StorageBackupJobService
import com.stevesoltys.seedvault.transport.backup.BackupInitializer
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
    private val backupInitializer: BackupInitializer,
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
                if (backupManager.isBackupEnabled) {
                    val onError = {
                        Log.e(TAG, "Error starting new RestoreSet")
                        onInitializationError()
                    }
                    backupInitializer.initialize(onError) {
                        val requestBackup = isUsb && !isSetupWizard
                        if (requestBackup) {
                            Log.i(TAG, "Requesting a backup now, because we use USB storage")
                            AppBackupWorker.scheduleNow(app, reschedule = false)
                        }
                        // notify the UI that the location has been set
                        mLocationChecked.postEvent(LocationResult())
                    }
                } else {
                    // notify the UI that the location has been set
                    mLocationChecked.postEvent(LocationResult())
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
            if (backupManager.isBackupEnabled) {
                AppBackupWorker.schedule(app, settingsManager, CANCEL_AND_REENQUEUE)
            }
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

    private fun onInitializationError() {
        val errorMsg = app.getString(R.string.storage_check_fragment_backup_error)
        mLocationChecked.postEvent(LocationResult(errorMsg))
    }

}
