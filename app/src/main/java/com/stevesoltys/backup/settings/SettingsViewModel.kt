package com.stevesoltys.backup.settings

import android.app.Application
import android.app.backup.BackupProgress
import android.app.backup.IBackupObserver
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.backup.Backup
import com.stevesoltys.backup.transport.ConfigurableBackupTransportService
import com.stevesoltys.backup.transport.TRANSPORT_ID
import com.stevesoltys.backup.transport.requestBackup
import com.stevesoltys.backup.ui.BackupViewModel
import com.stevesoltys.backup.ui.LocationResult

private val TAG = SettingsViewModel::class.java.simpleName

class SettingsViewModel(app: Application) : BackupViewModel(app) {

    override val isRestoreOperation = false

    fun backupNow() = Thread { requestBackup(app) }.start()

    override fun onLocationSet(folderUri: Uri, isInitialSetup: Boolean) {
        // store backup folder location in settings
        setBackupFolderUri(app, folderUri)

        // stop backup service to be sure the old location will get updated
        app.stopService(Intent(app, ConfigurableBackupTransportService::class.java))

        Log.d(TAG, "New storage location chosen: $folderUri")

        // initialize the new location
        val observer = InitializationObserver(isInitialSetup)
        Backup.backupManager.initializeTransports(arrayOf(TRANSPORT_ID), observer)
    }

    @WorkerThread
    private inner class InitializationObserver(private val initialSetUp: Boolean) : IBackupObserver.Stub() {
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
                mLocationSet.postEvent(LocationResult(true, initialSetUp))
            } else {
                // notify the UI that the location was invalid
                mLocationSet.postEvent(LocationResult(false, initialSetUp))
            }
        }
    }

}
