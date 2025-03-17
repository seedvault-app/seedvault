/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.settings

import android.app.backup.IBackupManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.worker.BackupRequester.Companion.requestFilesAndAppBackup
import org.koin.core.context.GlobalContext.get

internal const val ACTION_TRY_AGAIN = "com.stevesoltys.seedvault.action.TRY_AGAIN"

class TryAgainBroadcastReceiver : BroadcastReceiver() {

    // using KoinComponent would crash robolectric tests :(
    private val notificationManager: BackupNotificationManager by lazy { get().get() }
    private val backendManager: BackendManager by lazy { get().get() }
    private val settingsManager: SettingsManager by lazy { get().get() }
    private val backupManager: IBackupManager by lazy { get().get() }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRY_AGAIN) return

        notificationManager.onBackupErrorSeen()

        val reschedule = !backendManager.isOnRemovableDrive
        requestFilesAndAppBackup(context, settingsManager, backupManager, reschedule)
    }

}
