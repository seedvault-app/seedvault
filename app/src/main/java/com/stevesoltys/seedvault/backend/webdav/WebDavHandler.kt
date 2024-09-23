/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.backend.webdav

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.settings.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.BackendFactory
import org.calyxos.seedvault.core.backends.webdav.WebDavConfig
import org.calyxos.seedvault.core.backends.webdav.WebDavProperties
import java.io.IOException

internal sealed interface WebDavConfigState {
    object Empty : WebDavConfigState
    object Checking : WebDavConfigState
    class Success(
        val properties: WebDavProperties,
        val backend: Backend,
    ) : WebDavConfigState

    class Error(val e: Exception?) : WebDavConfigState
}

private val TAG = WebDavHandler::class.java.simpleName

internal class WebDavHandler(
    private val context: Context,
    private val backendFactory: BackendFactory,
    private val settingsManager: SettingsManager,
    private val backendManager: BackendManager,
) {

    companion object {
        fun createWebDavProperties(context: Context, config: WebDavConfig): WebDavProperties {
            val host = Uri.parse(config.url).host
            return WebDavProperties(
                config = config,
                name = context.getString(R.string.storage_webdav_name, host),
            )
        }
    }

    private val mConfigState = MutableStateFlow<WebDavConfigState>(WebDavConfigState.Empty)
    val configState = mConfigState.asStateFlow()

    suspend fun onConfigReceived(config: WebDavConfig) {
        mConfigState.value = WebDavConfigState.Checking
        val backend = backendFactory.createWebDavBackend(config)
        try {
            if (backend.test()) {
                val properties = createWebDavProperties(context, config)
                mConfigState.value = WebDavConfigState.Success(properties, backend)
            } else {
                mConfigState.value = WebDavConfigState.Error(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error testing WebDAV config at ${config.url}", e)
            mConfigState.value = WebDavConfigState.Error(e)
        }
    }

    fun resetConfigState() {
        mConfigState.value = WebDavConfigState.Empty
    }

    /**
     * Searches if there's really an app backup available in the given storage location.
     * Returns true if at least one was found and false otherwise.
     */
    @WorkerThread
    @Throws(IOException::class)
    suspend fun hasAppBackup(backend: Backend): Boolean {
        return backend.getAvailableBackupFileHandles().isNotEmpty()
    }

    fun save(properties: WebDavProperties) {
        settingsManager.saveWebDavConfig(properties.config)
    }

    fun setPlugin(properties: WebDavProperties, backend: Backend) {
        backendManager.changePlugins(
            backend = backend,
            storageProperties = properties,
        )
    }

}
