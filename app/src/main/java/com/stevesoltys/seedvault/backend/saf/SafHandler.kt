/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.backend.saf

import android.content.Context
import android.content.Context.USB_SERVICE
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.hardware.usb.UsbManager
import android.net.Uri
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.isMassStorage
import com.stevesoltys.seedvault.settings.FlashDrive
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.ui.storage.StorageOption
import org.calyxos.seedvault.core.backends.BackendFactory
import org.calyxos.seedvault.core.backends.saf.SafProperties
import java.io.IOException

private const val TAG = "SafHandler"

internal class SafHandler(
    private val context: Context,
    private val backendFactory: BackendFactory,
    private val settingsManager: SettingsManager,
    private val backendManager: BackendManager,
) {

    fun onConfigReceived(uri: Uri, safOption: StorageOption.SafOption): SafProperties {
        // persist permission to access backup folder across reboots
        val takeFlags = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, takeFlags)

        return SafProperties(
            config = uri,
            name = if (safOption.isInternal()) {
                val brackets = context.getString(R.string.settings_backup_location_internal)
                "${safOption.title} ($brackets)"
            } else {
                safOption.title
            },
            isUsb = safOption.isUsb,
            requiresNetwork = safOption.requiresNetwork,
            rootId = safOption.rootId,
        )
    }

    /**
     * Searches if there's really an app backup available in the given storage location.
     * Returns true if at least one was found and false otherwise.
     */
    @WorkerThread
    @Throws(IOException::class)
    suspend fun hasAppBackup(safProperties: SafProperties): Boolean {
        val backend = backendFactory.createSafBackend(safProperties)
        return backend.getAvailableBackupFileHandles().isNotEmpty()
    }

    fun save(safProperties: SafProperties) {
        settingsManager.setSafProperties(safProperties)

        if (safProperties.isUsb) {
            Log.d(TAG, "Selected storage is a removable USB device.")
            val wasSaved = saveUsbDevice()
            // reset stored flash drive, if we did not update it
            if (!wasSaved) settingsManager.setFlashDrive(null)
        } else {
            settingsManager.setFlashDrive(null)
        }
        Log.d(TAG, "New storage location saved: ${safProperties.uri}")
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

    fun setPlugin(safProperties: SafProperties) {
        backendManager.changePlugins(
            backend = backendFactory.createSafBackend(safProperties),
            storageProperties = safProperties,
        )
    }
}
