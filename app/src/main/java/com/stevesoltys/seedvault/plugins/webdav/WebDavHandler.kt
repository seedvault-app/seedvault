/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.webdav

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.settings.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

internal sealed interface WebDavConfigState {
    object Empty : WebDavConfigState
    object Checking : WebDavConfigState
    class Success(
        val properties: WebDavProperties,
        val plugin: WebDavStoragePlugin,
    ) : WebDavConfigState

    class Error(val e: Exception?) : WebDavConfigState
}

private val TAG = WebDavHandler::class.java.simpleName

internal class WebDavHandler(
    private val context: Context,
    private val webDavFactory: WebDavFactory,
    private val settingsManager: SettingsManager,
    private val storagePluginManager: StoragePluginManager,
) {

    companion object {
        fun createWebDavProperties(context: Context, config: WebDavConfig): WebDavProperties {
            val host = config.url.toHttpUrl().host
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
        val plugin = webDavFactory.createAppStoragePlugin(config) as WebDavStoragePlugin
        try {
            if (plugin.test()) {
                val properties = createWebDavProperties(context, config)
                mConfigState.value = WebDavConfigState.Success(properties, plugin)
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
    suspend fun hasAppBackup(appPlugin: WebDavStoragePlugin): Boolean {
        val backups = appPlugin.getAvailableBackups()
        return backups != null && backups.iterator().hasNext()
    }

    fun save(properties: WebDavProperties) {
        settingsManager.saveWebDavConfig(properties.config)
    }

    fun setPlugin(properties: WebDavProperties, plugin: WebDavStoragePlugin) {
        storagePluginManager.changePlugins(
            storageProperties = properties,
            appPlugin = plugin,
            filesPlugin = webDavFactory.createFilesStoragePlugin(properties.config),
        )
    }

}
