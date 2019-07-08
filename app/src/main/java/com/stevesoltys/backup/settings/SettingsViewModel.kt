package com.stevesoltys.backup.settings

import android.app.Application
import android.content.Intent
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import androidx.lifecycle.AndroidViewModel
import com.stevesoltys.backup.security.KeyManager

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    fun recoveryCodeIsSet() = KeyManager.hasBackupKey()
    fun locationIsSet() = getBackupFolderUri(getApplication()) != null

    fun handleChooseFolderResult(result: Intent?) {
        val folderUri = result?.data ?: return

        // persist permission to access backup folder across reboots
        val takeFlags = result.flags and (FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
        app.contentResolver.takePersistableUriPermission(folderUri, takeFlags)

        // store backup folder location in settings
        setBackupFolderUri(app, folderUri)
    }

}
