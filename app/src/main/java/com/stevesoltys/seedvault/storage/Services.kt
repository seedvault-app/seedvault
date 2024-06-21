/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.storage

import android.content.Intent
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.worker.AppBackupWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.calyxos.backup.storage.api.BackupObserver
import org.calyxos.backup.storage.api.RestoreObserver
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.backup.BackupJobService
import org.calyxos.backup.storage.backup.BackupService
import org.calyxos.backup.storage.backup.NotificationBackupObserver
import org.calyxos.backup.storage.restore.NotificationRestoreObserver
import org.calyxos.backup.storage.restore.RestoreService
import org.calyxos.backup.storage.ui.restore.FileSelectionManager
import org.koin.android.ext.android.inject

/*
test and debug with

  adb shell dumpsys jobscheduler |
  grep -A 23 -B 4 "Service: com.stevesoltys.seedvault/.storage.StorageBackupJobService"

force running with:

  adb shell cmd jobscheduler run -f com.stevesoltys.seedvault 0

 */

internal class StorageBackupJobService : BackupJobService(StorageBackupService::class.java)

internal class StorageBackupService : BackupService() {

    companion object {
        internal const val EXTRA_START_APP_BACKUP = "startAppBackup"
        private val mIsRunning = MutableStateFlow(false)
        val isRunning = mIsRunning.asStateFlow()
    }

    override val storageBackup: StorageBackup by inject()
    private val storagePluginManager: StoragePluginManager by inject()

    // use lazy delegate because context isn't available during construction time
    override val backupObserver: BackupObserver by lazy {
        NotificationBackupObserver(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        mIsRunning.value = true
    }

    override fun onDestroy() {
        super.onDestroy()
        mIsRunning.value = false
    }

    override fun onBackupFinished(intent: Intent, success: Boolean) {
        if (intent.getBooleanExtra(EXTRA_START_APP_BACKUP, false)) {
            val isUsb = storagePluginManager.storageProperties?.isUsb ?: false
            AppBackupWorker.scheduleNow(applicationContext, reschedule = !isUsb)
        }
    }
}

internal class StorageRestoreService : RestoreService() {
    override val storageBackup: StorageBackup by inject()
    override val fileSelectionManager: FileSelectionManager by inject()

    // use lazy delegate because context isn't available during construction time
    override val restoreObserver: RestoreObserver by lazy {
        NotificationRestoreObserver(applicationContext)
    }
}
