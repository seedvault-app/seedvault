package com.stevesoltys.seedvault.ui.storage

import android.app.Application
import android.app.backup.BackupProgress
import android.app.backup.IBackupManager
import android.app.backup.IBackupObserver
import android.net.Uri
import android.os.UserHandle
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.TRANSPORT_ID
import com.stevesoltys.seedvault.transport.requestBackup

private val TAG = BackupStorageViewModel::class.java.simpleName

internal class BackupStorageViewModel(
        private val app: Application,
        private val backupManager: IBackupManager,
        settingsManager: SettingsManager) : StorageViewModel(app, settingsManager) {

    override val isRestoreOperation = false

    override fun onLocationSet(uri: Uri) {
        val isUsb = saveStorage(uri)
        settingsManager.forceStorageInitialization()

        // initialize the new location, will also generate a new backup token
        val observer = InitializationObserver()
        backupManager.initializeTransportsForUser(UserHandle.myUserId(), arrayOf(TRANSPORT_ID), observer)

        // if storage is on USB and this is not SetupWizard, do a backup right away
        if (isUsb && !isSetupWizard) Thread {
            requestBackup(app)
        }.start()
    }

    @WorkerThread
    private inner class InitializationObserver : IBackupObserver.Stub() {
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
            } else {
                // notify the UI that the location was invalid
                val errorMsg = app.getString(R.string.storage_check_fragment_backup_error)
                mLocationChecked.postEvent(LocationResult(errorMsg))
            }
        }
    }

}
