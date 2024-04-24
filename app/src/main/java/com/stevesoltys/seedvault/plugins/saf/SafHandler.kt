/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import android.content.Context.USB_SERVICE
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.hardware.usb.UsbManager
import android.net.Uri
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.isMassStorage
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.settings.FlashDrive
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.ui.storage.StorageOption
import java.io.IOException

private const val TAG = "SafHandler"

internal class SafHandler(
    private val context: Context,
    private val safFactory: SafFactory,
    private val settingsManager: SettingsManager,
    private val storagePluginManager: StoragePluginManager,
) {

    fun onConfigReceived(uri: Uri, safOption: StorageOption.SafOption): SafStorage {
        // persist permission to access backup folder across reboots
        val takeFlags = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, takeFlags)

        val name = if (safOption.isInternal()) {
            "${safOption.title} (${context.getString(R.string.settings_backup_location_internal)})"
        } else {
            safOption.title
        }
        return SafStorage(uri, name, safOption.isUsb, safOption.requiresNetwork, safOption.rootId)
    }

    /**
     * Searches if there's really an app backup available in the given storage location.
     * Returns true if at least one was found and false otherwise.
     */
    @WorkerThread
    @Throws(IOException::class)
    suspend fun hasAppBackup(safStorage: SafStorage): Boolean {
        val storage = DocumentsStorage(context, settingsManager, safStorage)
        val appPlugin = safFactory.createAppStoragePlugin(safStorage, storage)
        val backups = appPlugin.getAvailableBackups()
        return backups != null && backups.iterator().hasNext()
    }

    fun save(safStorage: SafStorage) {
        settingsManager.setSafStorage(safStorage)

        if (safStorage.isUsb) {
            Log.d(TAG, "Selected storage is a removable USB device.")
            val wasSaved = saveUsbDevice()
            // reset stored flash drive, if we did not update it
            if (!wasSaved) settingsManager.setFlashDrive(null)
        } else {
            settingsManager.setFlashDrive(null)
        }
        Log.d(TAG, "New storage location saved: ${safStorage.uri}")
    }

    private fun saveUsbDevice(): Boolean {
        val manager = context.getSystemService(USB_SERVICE) as UsbManager
        manager.deviceList.values.forEach { device ->
            if (device.isMassStorage()) {
                val flashDrive = FlashDrive.from(device)
                settingsManager.setFlashDrive(flashDrive)
                Log.d(TAG, "Saved flash drive: $flashDrive")
                return true
            }
        }
        Log.e(TAG, "No USB device found even though we were expecting one.")
        return false
    }

    fun setPlugin(safStorage: SafStorage) {
        val storage = DocumentsStorage(context, settingsManager, safStorage)
        storagePluginManager.changePlugins(
            storageProperties = safStorage,
            appPlugin = safFactory.createAppStoragePlugin(safStorage, storage),
            filesPlugin = safFactory.createFilesStoragePlugin(safStorage, storage),
        )
    }
}
