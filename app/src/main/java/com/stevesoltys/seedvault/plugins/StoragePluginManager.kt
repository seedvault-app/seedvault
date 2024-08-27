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
import com.stevesoltys.seedvault.plugins.saf.SafFactory
import com.stevesoltys.seedvault.plugins.webdav.WebDavFactory
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.settings.StoragePluginType
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.saf.SafBackend

class StoragePluginManager(
    private val context: Context,
    private val settingsManager: SettingsManager,
    safFactory: SafFactory,
    webDavFactory: WebDavFactory,
) {

    private var mBackend: Backend?
    private var mFilesPlugin: org.calyxos.backup.storage.api.StoragePlugin?
    private var mStorageProperties: StorageProperties<*>?

    val backend: Backend
        @Synchronized
        get() {
            return mBackend ?: error("App plugin was loaded, but still null")
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
                mBackend = safFactory.createBackend(safStorage)
                mFilesPlugin = safFactory.createFilesStoragePlugin(safStorage)
                mStorageProperties = safStorage
            }

            StoragePluginType.WEB_DAV -> {
                val webDavProperties =
                    settingsManager.webDavProperties ?: error("No WebDAV config saved")
                mBackend = webDavFactory.createBackend(webDavProperties.config)
                mFilesPlugin = webDavFactory.createFilesStoragePlugin(webDavProperties.config)
                mStorageProperties = webDavProperties
            }

            null -> {
                mBackend = null
                mFilesPlugin = null
                mStorageProperties = null
            }
        }
    }

    fun isValidAppPluginSet(): Boolean {
        if (mBackend == null || mFilesPlugin == null) return false
        if (mBackend is SafBackend) {
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
        backend: Backend,
        filesPlugin: org.calyxos.backup.storage.api.StoragePlugin,
    ) {
        settingsManager.setStorageBackend(backend)
        mStorageProperties = storageProperties
        mBackend = backend
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
            backend.getFreeSpace()
        } catch (e: Throwable) { // NoClassDefFound isn't an [Exception], can get thrown by dav4jvm
            Log.e("StoragePluginManager", "Error getting free space: ", e)
            null
        }
    }

}
