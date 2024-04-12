/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester

import android.app.Application
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import android.util.Log
import de.grobox.storagebackuptester.plugin.TestSafStoragePlugin
import de.grobox.storagebackuptester.settings.SettingsManager
import org.calyxos.backup.storage.api.StorageBackup

class App : Application() {

    val settingsManager: SettingsManager by lazy { SettingsManager(applicationContext) }
    val storageBackup: StorageBackup by lazy {
        val plugin = TestSafStoragePlugin(this) { settingsManager.getBackupLocation() }
        StorageBackup(this, { plugin })
    }

    override fun onCreate() {
        super.onCreate()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.e("TEST", "ON LOW MEMORY!!!")
    }

}
