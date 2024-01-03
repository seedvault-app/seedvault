package com.stevesoltys.seedvault.ui.storage

import android.app.Application
import android.app.backup.BackupProgress
import android.app.backup.IBackupManager
import android.app.backup.IBackupObserver
import android.net.Uri
import android.os.UserHandle
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.lifecycle.viewModelScope
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.service.settings.SettingsService
import com.stevesoltys.seedvault.transport.TRANSPORT_ID
import com.stevesoltys.seedvault.service.app.backup.coordinator.BackupCoordinatorService
import com.stevesoltys.seedvault.service.app.backup.requestBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.calyxos.backup.storage.api.StorageBackup
import java.io.IOException

private val TAG = BackupStorageViewModel::class.java.simpleName

internal class BackupStorageViewModel(
    private val app: Application,
    private val backupManager: IBackupManager,
    private val backupCoordinatorService: BackupCoordinatorService,
    private val storageBackup: StorageBackup,
    settingsService: SettingsService,
) : StorageViewModel(app, settingsService) {

    override val isRestoreOperation = false

    override fun onLocationSet(uri: Uri) {
        val isUsb = saveStorage(uri)
        viewModelScope.launch(Dispatchers.IO) {
            // remove old storage snapshots and clear cache
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
                    requestBackup(app)
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
