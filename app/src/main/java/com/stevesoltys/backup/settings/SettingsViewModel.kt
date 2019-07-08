package com.stevesoltys.backup.settings

import android.app.Application
import android.content.Intent
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import androidx.lifecycle.AndroidViewModel
import com.stevesoltys.backup.LiveEvent
import com.stevesoltys.backup.MutableLiveEvent
import com.stevesoltys.backup.security.KeyManager

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    private val mLocationWasSet = MutableLiveEvent<Boolean>()
    /**
     * Will be set to true if this is the initial location.
     * It will be false if an existing location was changed.
     */
    internal val onLocationSet: LiveEvent<Boolean> = locationWasSet

    private val mChooseBackupLocation = MutableLiveEvent<Boolean>()
    internal val chooseBackupLocation: LiveEvent<Boolean> = mChooseBackupLocation
    internal fun chooseBackupLocation() = mChooseBackupLocation.setEvent(true)

    fun recoveryCodeIsSet() = KeyManager.hasBackupKey()
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
        mLocationWasSet.setEvent(wasEmptyBefore)
    }

}
