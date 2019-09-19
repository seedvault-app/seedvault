package com.stevesoltys.backup.ui.storage

import android.app.Application
import android.app.backup.BackupProgress
import android.app.backup.IBackupObserver
import android.net.Uri
import android.os.UserHandle
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.backup.Backup
import com.stevesoltys.backup.R
import com.stevesoltys.backup.transport.TRANSPORT_ID

private val TAG = BackupStorageViewModel::class.java.simpleName

internal class BackupStorageViewModel(private val app: Application) : StorageViewModel(app) {

    override val isRestoreOperation = false

    override fun onLocationSet(uri: Uri) {
        saveStorage(uri)

        // use a new backup token
        settingsManager.getAndSaveNewBackupToken()

        // initialize the new location
        val observer = InitializationObserver()
        Backup.backupManager.initializeTransportsForUser(UserHandle.myUserId(), arrayOf(TRANSPORT_ID), observer)
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
