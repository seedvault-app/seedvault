package com.stevesoltys.backup.ui

import android.app.Application
import android.content.Intent
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import com.stevesoltys.backup.Backup
import com.stevesoltys.backup.isOnExternalStorage
import com.stevesoltys.backup.settings.getBackupFolderUri
import com.stevesoltys.backup.settings.setBackupFolderUri
import com.stevesoltys.backup.transport.ConfigurableBackupTransportService

private val TAG = BackupViewModel::class.java.simpleName

abstract class BackupViewModel(protected val app: Application) : AndroidViewModel(app) {

    private val locationWasSet = MutableLiveEvent<LocationResult>()
    /**
     * Will be set to true if this is the initial location.
     * It will be false if an existing location was changed.
     */
    internal val onLocationSet: LiveEvent<LocationResult> = locationWasSet

    private val mChooseBackupLocation = MutableLiveEvent<Boolean>()
    internal val chooseBackupLocation: LiveEvent<Boolean> = mChooseBackupLocation
    internal fun chooseBackupLocation() = mChooseBackupLocation.setEvent(true)

    internal fun recoveryCodeIsSet() = Backup.keyManager.hasBackupKey()

    internal fun validLocationIsSet(): Boolean {
        val uri = getBackupFolderUri(app) ?: return false
        if (uri.isOnExternalStorage()) return true  // might be a temporary failure
        val file = DocumentFile.fromTreeUri(app, uri) ?: return false
        return file.isDirectory
    }

    internal fun handleChooseFolderResult(result: Intent?) {
        val folderUri = result?.data ?: return

        // persist permission to access backup folder across reboots
        val takeFlags = result.flags and (FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
        app.contentResolver.takePersistableUriPermission(folderUri, takeFlags)

        // check if this is initial set-up or a later change
        val initialSetUp = !validLocationIsSet()

        if (acceptBackupLocation(folderUri)) {
            // store backup folder location in settings
            setBackupFolderUri(app, folderUri)

            // notify the UI that the location has been set
            locationWasSet.setEvent(LocationResult(true, initialSetUp))

            // stop backup service to be sure the old location will get updated
            app.stopService(Intent(app, ConfigurableBackupTransportService::class.java))

            Log.d(TAG, "New storage location chosen: $folderUri")
        } else {
            Log.w(TAG, "Location was rejected: $folderUri")

            // notify the UI that the location was invalid
            locationWasSet.setEvent(LocationResult(false, initialSetUp))
        }
    }

    protected open fun acceptBackupLocation(folderUri: Uri): Boolean {
        return true
    }

}

class LocationResult(val validLocation: Boolean, val initialSetup: Boolean)
