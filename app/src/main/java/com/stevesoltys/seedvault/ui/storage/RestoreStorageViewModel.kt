/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.storage

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.backend.saf.SafHandler
import com.stevesoltys.seedvault.backend.webdav.WebDavHandler
import com.stevesoltys.seedvault.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.Constants.DIRECTORY_ROOT
import org.calyxos.seedvault.core.backends.saf.SafProperties
import org.calyxos.seedvault.core.backends.webdav.WebDavProperties

private val TAG = RestoreStorageViewModel::class.java.simpleName

internal class RestoreStorageViewModel(
    private val app: Application,
    safHandler: SafHandler,
    webDavHandler: WebDavHandler,
    settingsManager: SettingsManager,
    backendManager: BackendManager,
) : StorageViewModel(app, safHandler, webDavHandler, settingsManager, backendManager) {

    override val isRestoreOperation = true

    override fun onSafUriSet(safProperties: SafProperties) {
        viewModelScope.launch(Dispatchers.IO) {
            val hasBackup = try {
                safHandler.hasAppBackup(safProperties)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading URI: ${safProperties.uri}", e)
                val errorMsg = app.getString(R.string.restore_set_error) + "\n\n$e"
                mLocationChecked.postEvent(LocationResult(errorMsg))
                return@launch
            }
            if (hasBackup) {
                safHandler.save(safProperties)
                safHandler.setPlugin(safProperties)
                mLocationChecked.postEvent(LocationResult())
            } else {
                Log.w(TAG, "Location was rejected: ${safProperties.uri}")

                // notify the UI that the location was invalid
                val errorMsg =
                    app.getString(R.string.restore_invalid_location_message, DIRECTORY_ROOT)
                mLocationChecked.postEvent(LocationResult(errorMsg))
            }
        }
    }

    override fun onWebDavConfigSet(properties: WebDavProperties, backend: Backend) {
        viewModelScope.launch(Dispatchers.IO) {
            val hasBackup = try {
                webdavHandler.hasAppBackup(backend)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading: ${properties.config.url}", e)
                val errorMsg = app.getString(R.string.restore_set_error) + "\n\n$e"
                mLocationChecked.postEvent(LocationResult(errorMsg))
                return@launch
            }
            if (hasBackup) {
                webdavHandler.save(properties)
                webdavHandler.setPlugin(properties, backend)
                mLocationChecked.postEvent(LocationResult())
            } else {
                Log.w(TAG, "Location was rejected: ${properties.config.url}")

                // notify the UI that the location was invalid
                val errorMsg =
                    app.getString(R.string.restore_invalid_location_message, DIRECTORY_ROOT)
                mLocationChecked.postEvent(LocationResult(errorMsg))
            }
        }
    }
}
