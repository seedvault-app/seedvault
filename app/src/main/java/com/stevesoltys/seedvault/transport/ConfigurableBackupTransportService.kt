/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport

import android.app.Service
import android.app.backup.IBackupManager
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private val TAG = ConfigurableBackupTransportService::class.java.simpleName

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
class ConfigurableBackupTransportService : Service(), KoinComponent {

    companion object {
        private val mIsRunning = MutableStateFlow(false)
        val isRunning = mIsRunning.asStateFlow()
    }

    private var transport: ConfigurableBackupTransport? = null

    private val keyManager: KeyManager by inject()
    private val backupManager: IBackupManager by inject()
    private val notificationManager: BackupNotificationManager by inject()

    override fun onCreate() {
        super.onCreate()
        transport = ConfigurableBackupTransport(applicationContext)
        mIsRunning.value = true
        Log.d(TAG, "Service created.")
    }

    override fun onBind(intent: Intent): IBinder? {
        // refuse to work until we have the main key
        val noMainKey = keyManager.hasBackupKey() && !keyManager.hasMainKey()
        if (noMainKey && backupManager.currentTransport == TRANSPORT_ID) {
            notificationManager.onNoMainKeyError()
            backupManager.isBackupEnabled = false
        }
        val transport = this.transport ?: throw IllegalStateException("no transport in onBind()")
        return transport.binder.apply {
            Log.d(TAG, "Transport bound.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationManager.onServiceDestroyed()
        transport = null
        mIsRunning.value = false
        Log.d(TAG, "Service destroyed.")
    }

}
