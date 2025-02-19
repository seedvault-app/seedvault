/*
 * SPDX-FileCopyrightText: 2025 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault

import android.app.Service
import android.app.backup.IBackupManager
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.notification.NOTIFICATION_ID_USB_MONITOR
import com.stevesoltys.seedvault.worker.BackupRequester.Companion.requestFilesAndAppBackup
import org.koin.android.ext.android.inject

private const val TAG = "UsbMonitorService"

class UsbMonitorService : Service() {

    private val nm: BackupNotificationManager by inject()
    private val backupManager: IBackupManager by inject()
    private val settingsManager: SettingsManager by inject()

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand $intent $flags $startId")
        startForeground(
            NOTIFICATION_ID_USB_MONITOR,
            nm.getUsbMonitorNotification(),
            FOREGROUND_SERVICE_TYPE_MANIFEST,
        )
        val rootsUri = intent.data ?: error("No URI in start intent.")
        val contentResolver = contentResolver
        val handler = Handler(Looper.myLooper() ?: error("no looper"))
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                Log.i(TAG, "onChange() requesting backup now!")
                contentResolver.unregisterContentObserver(this)
                requestFilesAndAppBackup(applicationContext, settingsManager, backupManager)
                Log.i(TAG, "stopSelf($startId)")
                stopSelf(startId)
            }
        }
        contentResolver.registerContentObserver(rootsUri, true, observer)

        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        Log.i(TAG, "onTimeout($startId)")
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.i(TAG, "onTimeout($startId, $fgsType)")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
        nm.cancelUsbMonitorNotification()
    }
}
