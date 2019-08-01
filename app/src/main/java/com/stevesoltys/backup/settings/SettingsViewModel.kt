package com.stevesoltys.backup.settings

import android.app.Application
import android.content.Intent
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.stevesoltys.backup.Backup
import com.stevesoltys.backup.LiveEvent
import com.stevesoltys.backup.MutableLiveEvent
import com.stevesoltys.backup.transport.ConfigurableBackupTransportService
import com.stevesoltys.backup.transport.requestBackup

private val TAG = SettingsViewModel::class.java.simpleName

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    private val locationWasSet = MutableLiveEvent<Boolean>()
    /**
     * Will be set to true if this is the initial location.
     * It will be false if an existing location was changed.
     */
    internal val onLocationSet: LiveEvent<Boolean> = locationWasSet

    private val mChooseBackupLocation = MutableLiveEvent<Boolean>()
    internal val chooseBackupLocation: LiveEvent<Boolean> = mChooseBackupLocation
    internal fun chooseBackupLocation() = mChooseBackupLocation.setEvent(true)

    fun recoveryCodeIsSet() = Backup.keyManager.hasBackupKey()
    fun locationIsSet() = getBackupFolderUri(getApplication()) != null

    fun handleChooseFolderResult(result: Intent?) {
        val folderUri = result?.data ?: return

        // persist permission to access backup folder across reboots
        val takeFlags = result.flags and (FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
        app.contentResolver.takePersistableUriPermission(folderUri, takeFlags)

        // check if this is initial set-up or a later change
        val wasEmptyBefore = getBackupFolderUri(app) == null

        // store backup folder location in settings
        setBackupFolderUri(app, folderUri)

        // notify the UI that the location has been set
        locationWasSet.setEvent(wasEmptyBefore)

        // stop backup service to be sure the old location will get updated
        app.stopService(Intent(app, ConfigurableBackupTransportService::class.java))

        Log.d(TAG, "New storage location chosen: $folderUri")
    }

    fun backupNow() = Thread { requestBackup(app) }.start()

}
