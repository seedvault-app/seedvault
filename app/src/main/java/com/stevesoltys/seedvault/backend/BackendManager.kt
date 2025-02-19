/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.backend

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.getStorageContext
import com.stevesoltys.seedvault.permitDiskReads
import com.stevesoltys.seedvault.repo.BlobCache
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.settings.StoragePluginType
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.BackendFactory
import org.calyxos.seedvault.core.backends.BackendId
import org.calyxos.seedvault.core.backends.BackendProperties
import org.calyxos.seedvault.core.backends.IBackendManager

class BackendManager(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val blobCache: BlobCache,
    backendFactory: BackendFactory,
) : IBackendManager {

    @Volatile
    private var mBackend: Backend?

    @Volatile
    private var mBackendProperties: BackendProperties<*>?

    override val backend: Backend
        @Synchronized
        get() {
            return mBackend ?: error("App plugin was loaded, but still null")
        }

    val backendProperties: BackendProperties<*>?
        @Synchronized
        get() {
            return mBackendProperties
        }
    override val isOnRemovableDrive: Boolean get() = backendProperties?.isUsb == true
    override val requiresNetwork: Boolean get() = backendProperties?.requiresNetwork == true

    init {
        when (settingsManager.storagePluginType) {
            StoragePluginType.SAF -> {
                val safProperties = settingsManager.getSafProperties()
                    ?: error("No SAF storage saved")
                val ctx = context.getStorageContext { safProperties.isUsb }
                mBackend = backendFactory.createSafBackend(ctx, safProperties)
                mBackendProperties = safProperties
            }

            StoragePluginType.WEB_DAV -> {
                val webDavProperties =
                    settingsManager.webDavProperties ?: error("No WebDAV config saved")
                mBackend = backendFactory.createWebDavBackend(webDavProperties.config)
                mBackendProperties = webDavProperties
            }

            null -> {
                mBackend = null
                mBackendProperties = null
            }
        }
    }

    fun isValidAppPluginSet(): Boolean {
        if (mBackend == null) return false
        if (mBackend?.id == BackendId.SAF) {
            val storage = settingsManager.getSafProperties() ?: return false
            if (storage.isUsb) return true
            return permitDiskReads {
                storage.getDocumentFile(context).isDirectory
            }
        }
        return true
    }

    /**
     * Changes the storage plugins and current [BackendProperties].
     *
     * IMPORTANT: Do no call this while current plugins are being used,
     *            e.g. while backup/restore operation is still running.
     */
    @WorkerThread
    @Synchronized
    fun <T> changePlugins(
        backend: Backend,
        storageProperties: BackendProperties<T>,
    ) {
        settingsManager.setStorageBackend(backend)
        mBackend = backend
        mBackendProperties = storageProperties
        blobCache.clearLocalCache()
        // TODO not critical, but nice to have: clear also local snapshot cache
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
    override fun canDoBackupNow(): Boolean {
        val storage = backendProperties ?: return false
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
        val storage = backendProperties ?: return false
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
