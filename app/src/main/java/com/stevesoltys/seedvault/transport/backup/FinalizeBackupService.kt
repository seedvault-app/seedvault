/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
import android.os.IBinder
import android.util.Log
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.notification.NOTIFICATION_ID_OBSERVER
import org.koin.android.ext.android.inject

class FinalizeBackupService : Service() {

    companion object {
        private const val TAG = "FinalizeBackupService"
    }

    private val nm: BackupNotificationManager by inject()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand $intent $flags $startId")

        val str = applicationContext.getString(R.string.notification_finalizing_text)
        startForeground(
            NOTIFICATION_ID_OBSERVER,
            nm.getBackupNotification(str),
            FOREGROUND_SERVICE_TYPE_MANIFEST,
        )
        return START_STICKY_COMPATIBILITY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        nm.cancelBackupNotification()
        super.onDestroy()
    }
}
