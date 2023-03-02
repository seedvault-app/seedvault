/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.backup

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.calyxos.backup.storage.R
import org.calyxos.backup.storage.api.BackupObserver
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.ui.NOTIFICATION_ID_BACKUP
import org.calyxos.backup.storage.ui.NOTIFICATION_ID_PRUNE
import org.calyxos.backup.storage.ui.Notifications

private const val TAG = "BackupService"

public abstract class BackupService : Service() {

    private val n by lazy { Notifications(applicationContext) }
    protected abstract val storageBackup: StorageBackup
    protected abstract val backupObserver: BackupObserver?

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand $intent $flags $startId")
        startForeground(
            NOTIFICATION_ID_BACKUP,
            n.getBackupNotification(R.string.notification_backup_scanning)
        )
        GlobalScope.launch {
            val success = storageBackup.runBackup(backupObserver)
            if (success) {
                // only prune old backups when backup run was successful
                startForeground(
                    NOTIFICATION_ID_PRUNE,
                    n.getPruneNotification(R.string.notification_prune)
                )
                storageBackup.pruneOldBackups(backupObserver)
            }
            onBackupFinished(intent, success)
            stopSelf(startId)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    protected open fun onBackupFinished(intent: Intent, success: Boolean) {
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
    }

}
