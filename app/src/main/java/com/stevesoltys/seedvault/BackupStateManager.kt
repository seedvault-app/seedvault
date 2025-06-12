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
import com.stevesoltys.seedvault.transport.ConfigurableBackupTransportService
import com.stevesoltys.seedvault.worker.AppBackupPruneWorker
import com.stevesoltys.seedvault.worker.AppBackupWorker
import com.stevesoltys.seedvault.worker.AppCheckerWorker
import com.stevesoltys.seedvault.worker.FileBackupWorker
import com.stevesoltys.seedvault.worker.FileCheckerWorker
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
        flow2 = workManager.getWorkInfosForUniqueWorkFlow(AppBackupWorker.UNIQUE_WORK_NAME),
        flow3 = workManager.getWorkInfosForUniqueWorkFlow(FileBackupWorker.UNIQUE_WORK_NAME),
    ) { appBackupRunning, appBackupWorkInfos, fileBackupWorkInfos ->
        val appBackupState = appBackupWorkInfos.getOrNull(0)?.state
        val fileBackupState = fileBackupWorkInfos.getOrNull(0)?.state
        Log.i(
            TAG, "B - appBackupRunning: $appBackupRunning, " +
                "filesBackupRunning: ${fileBackupState?.name}, " +
                "appBackupWorker: ${appBackupState?.name}"
        )
        appBackupRunning || fileBackupState == RUNNING || appBackupState == RUNNING
    }

    val isCheckOrPruneRunning: Flow<Boolean> = combine(
        flow = workManager.getWorkInfosForUniqueWorkFlow(AppBackupPruneWorker.UNIQUE_WORK_NAME),
        flow2 = workManager.getWorkInfosForUniqueWorkFlow(AppCheckerWorker.UNIQUE_WORK_NAME),
        flow3 = workManager.getWorkInfosForUniqueWorkFlow(FileCheckerWorker.UNIQUE_WORK_NAME),
    ) { pruneInfo, appCheckInfo, fileCheckInfo ->
        val pruneInfoState = pruneInfo.getOrNull(0)?.state
        val appCheckState = appCheckInfo.getOrNull(0)?.state
        val fileCheckState = fileCheckInfo.getOrNull(0)?.state
        Log.i(
            TAG,
            "C - pruneBackupWorker: ${pruneInfoState?.name}, " +
                "appCheckerWorker: ${appCheckState?.name}, " +
                "fileCheckerWorker: ${fileCheckState?.name}"
        )
        pruneInfoState == RUNNING || appCheckState == RUNNING || fileCheckState == RUNNING
    }

    val isAutoRestoreEnabled: Boolean
        get() = Settings.Secure.getInt(contentResolver, BACKUP_AUTO_RESTORE, 1) == 1

    val isFrameworkSchedulingEnabled: Boolean
        get() = Settings.Secure.getInt(contentResolver, BACKUP_SCHEDULING_ENABLED, 1) == 1
}
