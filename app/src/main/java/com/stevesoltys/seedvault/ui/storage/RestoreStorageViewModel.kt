/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.storage

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.plugins.saf.DIRECTORY_ROOT
import com.stevesoltys.seedvault.plugins.saf.SafHandler
import com.stevesoltys.seedvault.plugins.saf.SafStorage
import com.stevesoltys.seedvault.plugins.webdav.WebDavHandler
import com.stevesoltys.seedvault.plugins.webdav.WebDavProperties
import com.stevesoltys.seedvault.plugins.webdav.WebDavStoragePlugin
import com.stevesoltys.seedvault.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

private val TAG = RestoreStorageViewModel::class.java.simpleName

internal class RestoreStorageViewModel(
    private val app: Application,
    safHandler: SafHandler,
    webDavHandler: WebDavHandler,
    settingsManager: SettingsManager,
    storagePluginManager: StoragePluginManager,
) : StorageViewModel(app, safHandler, webDavHandler, settingsManager, storagePluginManager) {

    override val isRestoreOperation = true

    override fun onSafUriSet(safStorage: SafStorage) {
        viewModelScope.launch(Dispatchers.IO) {
            val hasBackup = try {
                safHandler.hasAppBackup(safStorage)
            } catch (e: IOException) {
                Log.e(TAG, "Error reading URI: ${safStorage.uri}", e)
                false
            }
            if (hasBackup) {
                safHandler.save(safStorage)
                safHandler.setPlugin(safStorage)
                mLocationChecked.postEvent(LocationResult())
            } else {
                Log.w(TAG, "Location was rejected: ${safStorage.uri}")

                // notify the UI that the location was invalid
                val errorMsg =
                    app.getString(R.string.restore_invalid_location_message, DIRECTORY_ROOT)
                mLocationChecked.postEvent(LocationResult(errorMsg))
            }
        }
    }

    override fun onWebDavConfigSet(properties: WebDavProperties, plugin: WebDavStoragePlugin) {
        viewModelScope.launch(Dispatchers.IO) {
            val hasBackup = try {
                webdavHandler.hasAppBackup(plugin)
            } catch (e: IOException) {
                Log.e(TAG, "Error reading: ${properties.config.url}", e)
                false
            }
            if (hasBackup) {
                webdavHandler.save(properties)
                webdavHandler.setPlugin(properties, plugin)
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
