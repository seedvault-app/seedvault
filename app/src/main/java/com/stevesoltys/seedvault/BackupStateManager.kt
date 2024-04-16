/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault

import android.content.Context
import android.util.Log
import androidx.work.WorkInfo.State.RUNNING
import androidx.work.WorkManager
import com.stevesoltys.seedvault.storage.StorageBackupService
import com.stevesoltys.seedvault.transport.ConfigurableBackupTransportService
import com.stevesoltys.seedvault.worker.AppBackupWorker.Companion.UNIQUE_WORK_NAME
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

private const val TAG = "BackupStateManager"

class BackupStateManager(
    context: Context,
) {

    private val workManager = WorkManager.getInstance(context)

    val isBackupRunning: Flow<Boolean> = combine(
        flow = ConfigurableBackupTransportService.isRunning,
        flow2 = StorageBackupService.isRunning,
        flow3 = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME),
    ) { appBackupRunning, filesBackupRunning, workInfos ->
        val workInfoState = workInfos.getOrNull(0)?.state
        Log.i(
            TAG, "appBackupRunning: $appBackupRunning, " +
                "filesBackupRunning: $filesBackupRunning, " +
                "workInfoState: ${workInfoState?.name}"
        )
        appBackupRunning || filesBackupRunning || workInfoState == RUNNING
    }

}
