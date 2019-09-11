package com.stevesoltys.backup.ui

import android.app.Application
import android.content.Intent
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import com.stevesoltys.backup.Backup
import com.stevesoltys.backup.isOnExternalStorage
import com.stevesoltys.backup.settings.getBackupFolderUri

private val TAG = BackupViewModel::class.java.simpleName

abstract class BackupViewModel(protected val app: Application) : AndroidViewModel(app) {

    protected val mLocationSet = MutableLiveEvent<LocationResult>()
    /**
     * Will be set to true if this is the initial location.
     * It will be false if an existing location was changed.
     */
    internal val locationSet: LiveEvent<LocationResult> get() = mLocationSet

    private val mChooseBackupLocation = MutableLiveEvent<Boolean>()
    internal val chooseBackupLocation: LiveEvent<Boolean> get() = mChooseBackupLocation
    internal fun chooseBackupLocation() = mChooseBackupLocation.setEvent(true)

    internal fun recoveryCodeIsSet() = Backup.keyManager.hasBackupKey()

    internal fun validLocationIsSet(): Boolean {
        val uri = getBackupFolderUri(app) ?: return false
        if (uri.isOnExternalStorage()) return true  // might be a temporary failure
        val file = DocumentFile.fromTreeUri(app, uri) ?: return false
        return file.isDirectory
    }

    abstract val isRestoreOperation: Boolean

    internal fun handleChooseFolderResult(result: Intent?) {
        val folderUri = result?.data ?: return

        // persist permission to access backup folder across reboots
        val takeFlags = result.flags and (FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
        app.contentResolver.takePersistableUriPermission(folderUri, takeFlags)

        onLocationSet(folderUri, !validLocationIsSet())
    }

    abstract fun onLocationSet(folderUri: Uri, isInitialSetup: Boolean)

}

class LocationResult(val validLocation: Boolean, val initialSetup: Boolean)
