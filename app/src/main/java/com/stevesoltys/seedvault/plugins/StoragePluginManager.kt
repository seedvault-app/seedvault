/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.getStorageContext
import com.stevesoltys.seedvault.permitDiskReads
import com.stevesoltys.seedvault.plugins.saf.DocumentsProviderStoragePlugin
import com.stevesoltys.seedvault.plugins.saf.DocumentsStorage
import com.stevesoltys.seedvault.plugins.saf.SafFactory
import com.stevesoltys.seedvault.plugins.webdav.WebDavFactory
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.settings.StoragePluginType

class StoragePluginManager(
    private val context: Context,
    private val settingsManager: SettingsManager,
    safFactory: SafFactory,
    webDavFactory: WebDavFactory,
) {

    private var mAppPlugin: StoragePlugin<*>?
    private var mFilesPlugin: org.calyxos.backup.storage.api.StoragePlugin?
    private var mStorageProperties: StorageProperties<*>?

    val appPlugin: StoragePlugin<*>
        @Synchronized
        get() {
            return mAppPlugin ?: error("App plugin was loaded, but still null")
        }

    val filesPlugin: org.calyxos.backup.storage.api.StoragePlugin
        @Synchronized
        get() {
            return mFilesPlugin ?: error("Files plugin was loaded, but still null")
        }

    val storageProperties: StorageProperties<*>?
        @Synchronized
        get() {
            return mStorageProperties
        }
    val isOnRemovableDrive: Boolean get() = storageProperties?.isUsb == true

    init {
        when (settingsManager.storagePluginType) {
            StoragePluginType.SAF -> {
                val safStorage = settingsManager.getSafStorage() ?: error("No SAF storage saved")
                val documentsStorage = DocumentsStorage(context, settingsManager, safStorage)
                mAppPlugin = safFactory.createAppStoragePlugin(safStorage, documentsStorage)
                mFilesPlugin = safFactory.createFilesStoragePlugin(safStorage, documentsStorage)
                mStorageProperties = safStorage
            }

            StoragePluginType.WEB_DAV -> {
                val webDavProperties =
                    settingsManager.webDavProperties ?: error("No WebDAV config saved")
                mAppPlugin = webDavFactory.createAppStoragePlugin(webDavProperties.config)
                mFilesPlugin = webDavFactory.createFilesStoragePlugin(webDavProperties.config)
                mStorageProperties = webDavProperties
            }

            null -> {
                mAppPlugin = null
                mFilesPlugin = null
                mStorageProperties = null
            }
        }
    }

    fun isValidAppPluginSet(): Boolean {
        if (mAppPlugin == null || mFilesPlugin == null) return false
        if (mAppPlugin is DocumentsProviderStoragePlugin) {
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
        mStorageProperties = storageProperties
        mAppPlugin = appPlugin
        mFilesPlugin = filesPlugin
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

    /**
     * Retrieves the amount of free space in bytes, or null if unknown.
     */
    @WorkerThread
    suspend fun getFreeSpace(): Long? {
        return try {
            appPlugin.getFreeSpace()
        } catch (e: Exception) {
            Log.e("StoragePluginManager", "Error getting free space: ", e)
            null
        }
    }

}
