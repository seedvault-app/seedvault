package com.stevesoltys.seedvault.ui.storage

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.plugins.saf.DIRECTORY_ROOT
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.restore.RestorePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

private val TAG = RestoreStorageViewModel::class.java.simpleName

internal class RestoreStorageViewModel(
        private val app: Application,
        private val restorePlugin: RestorePlugin,
        settingsManager: SettingsManager) : StorageViewModel(app, settingsManager) {

    override val isRestoreOperation = true

    override fun onLocationSet(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val hasBackup = try {
                restorePlugin.hasBackup(uri)
            } catch (e: IOException) {
                Log.e(TAG, "Error reading URI: $uri", e)
                false
            }
            if (hasBackup) {
                saveStorage(uri)

                mLocationChecked.postEvent(LocationResult())
            } else {
                Log.w(TAG, "Location was rejected: $uri")

                // notify the UI that the location was invalid
                val errorMsg = app.getString(R.string.restore_invalid_location_message, DIRECTORY_ROOT)
                mLocationChecked.postEvent(LocationResult(errorMsg))
            }
        }
    }

}
