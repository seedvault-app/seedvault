/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.getStorageContext
import com.stevesoltys.seedvault.permitDiskReads
import com.stevesoltys.seedvault.plugins.saf.DocumentsProviderStoragePlugin
import com.stevesoltys.seedvault.plugins.saf.DocumentsStorage
import com.stevesoltys.seedvault.plugins.saf.SafFactory
import com.stevesoltys.seedvault.plugins.webdav.WebDavFactory
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.settings.StoragePluginEnum

abstract class StorageProperties<T> {
    abstract val config: T
    abstract val name: String
    abstract val isUsb: Boolean
    abstract val requiresNetwork: Boolean

    @WorkerThread
    abstract fun isUnavailableUsb(context: Context): Boolean

    /**
     * Returns true if this is storage that requires network access,
     * but it isn't available right now.
     */
    fun isUnavailableNetwork(context: Context, allowMetered: Boolean): Boolean {
        return requiresNetwork && !hasUnmeteredInternet(context, allowMetered)
    }

    private fun hasUnmeteredInternet(context: Context, allowMetered: Boolean): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val isMetered = cm.isActiveNetworkMetered
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NET_CAPABILITY_INTERNET) &&
            (allowMetered || !isMetered)
    }
}

class StoragePluginManager(
    private val context: Context,
    private val settingsManager: SettingsManager,
    safFactory: SafFactory,
    webDavFactory: WebDavFactory,
) {

    private var _appPlugin: StoragePlugin<*>?
    private var _filesPlugin: org.calyxos.backup.storage.api.StoragePlugin?
    private var _storageProperties: StorageProperties<*>?

    val appPlugin: StoragePlugin<*>
        @Synchronized
        get() {
            return _appPlugin ?: error("App plugin was loaded, but still null")
        }

    val filesPlugin: org.calyxos.backup.storage.api.StoragePlugin
        @Synchronized
        get() {
            return _filesPlugin ?: error("Files plugin was loaded, but still null")
        }

    val storageProperties: StorageProperties<*>?
        @Synchronized
        get() {
            return _storageProperties
        }
    val isOnRemovableDrive: Boolean get() = storageProperties?.isUsb == true

    init {
        when (settingsManager.storagePluginType) {
            StoragePluginEnum.SAF -> {
                val safStorage = settingsManager.getSafStorage() ?: error("No SAF storage saved")
                val documentsStorage = DocumentsStorage(context, settingsManager, safStorage)
                _appPlugin = safFactory.createAppStoragePlugin(safStorage, documentsStorage)
                _filesPlugin = safFactory.createFilesStoragePlugin(safStorage, documentsStorage)
                _storageProperties = safStorage
            }

            StoragePluginEnum.WEB_DAV -> {
                val webDavProperties =
                    settingsManager.webDavProperties ?: error("No WebDAV config saved")
                _appPlugin = webDavFactory.createAppStoragePlugin(webDavProperties.config)
                _filesPlugin = webDavFactory.createFilesStoragePlugin(webDavProperties.config)
                _storageProperties = webDavProperties
            }

            null -> {
                _appPlugin = null
                _filesPlugin = null
                _storageProperties = null
            }
        }
    }

    fun isValidAppPluginSet(): Boolean {
        if (_appPlugin == null || _filesPlugin == null) return false
        if (_appPlugin is DocumentsProviderStoragePlugin) {
            val storage = settingsManager.getSafStorage() ?: return false
            if (storage.isUsb) return true
            return permitDiskReads {
                storage.getDocumentFile(context).isDirectory
            }
        }
        return true
    }

    /**
     * Changes the storage plugins and current [StorageProperties].
     *
     * IMPORTANT: Do no call this while current plugins are being used,
     *            e.g. while backup/restore operation is still running.
     */
    fun <T> changePlugins(
        storageProperties: StorageProperties<T>,
        appPlugin: StoragePlugin<T>,
        filesPlugin: org.calyxos.backup.storage.api.StoragePlugin,
    ) {
        settingsManager.setStoragePlugin(appPlugin)
        _storageProperties = storageProperties
        _appPlugin = appPlugin
        _filesPlugin = filesPlugin
    }

    /**
     * Check if we are able to do backups now by examining possible pre-conditions
     * such as plugged-in flash drive or internet access.
     *
     * Should be run off the UI thread (ideally I/O) because of disk access.
     *
     * @return true if a backup is possible, false if not.
     */
    @WorkerThread
    fun canDoBackupNow(): Boolean {
        val storage = storageProperties ?: return false
        return !isOnUnavailableUsb() &&
            !storage.isUnavailableNetwork(context, settingsManager.useMeteredNetwork)
    }

    /**
     * Checks if storage is on a flash drive.
     *
     * Should be run off the UI thread (ideally I/O) because of disk access.
     *
     * @return true if flash drive is not plugged in,
     * false if storage isn't on flash drive or it isn't plugged in.
     */
    @WorkerThread
    fun isOnUnavailableUsb(): Boolean {
        val storage = storageProperties ?: return false
        val systemContext = context.getStorageContext { storage.isUsb }
        return storage.isUnavailableUsb(systemContext)
    }

}
