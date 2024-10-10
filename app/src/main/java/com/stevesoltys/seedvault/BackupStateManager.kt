/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault

import android.content.Context
import android.provider.Settings
import android.provider.Settings.Secure.BACKUP_AUTO_RESTORE
import android.provider.Settings.Secure.BACKUP_SCHEDULING_ENABLED
import android.util.Log
import androidx.work.WorkInfo.State.RUNNING
import androidx.work.WorkManager
import com.stevesoltys.seedvault.storage.StorageBackupService
import com.stevesoltys.seedvault.transport.ConfigurableBackupTransportService
import com.stevesoltys.seedvault.worker.AppBackupPruneWorker
import com.stevesoltys.seedvault.worker.AppBackupWorker.Companion.UNIQUE_WORK_NAME
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

private const val TAG = "BackupStateManager"

class BackupStateManager(
    context: Context,
) {

    private val workManager = WorkManager.getInstance(context)
    private val contentResolver = context.contentResolver

    val isBackupRunning: Flow<Boolean> = combine(
        flow = ConfigurableBackupTransportService.isRunning,
        flow2 = StorageBackupService.isRunning,
        flow3 = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME),
        flow4 = workManager.getWorkInfosForUniqueWorkFlow(AppBackupPruneWorker.UNIQUE_WORK_NAME),
    ) { appBackupRunning, filesBackupRunning, workInfo1, workInfo2 ->
        val workInfoState1 = workInfo1.getOrNull(0)?.state
        val workInfoState2 = workInfo2.getOrNull(0)?.state
        Log.i(
            TAG, "appBackupRunning: $appBackupRunning, " +
                "filesBackupRunning: $filesBackupRunning, " +
                "appBackupWorker: ${workInfoState1?.name}, " +
                "pruneBackupWorker: ${workInfoState2?.name}"
        )
        appBackupRunning || filesBackupRunning ||
            workInfoState1 == RUNNING || workInfoState2 == RUNNING
    }

    val isAutoRestoreEnabled: Boolean
        get() = Settings.Secure.getInt(contentResolver, BACKUP_AUTO_RESTORE, 1) == 1

    val isFrameworkSchedulingEnabled: Boolean
        get() = Settings.Secure.getInt(contentResolver, BACKUP_SCHEDULING_ENABLED, 1) == 1

}
