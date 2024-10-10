/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.worker

import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.util.Log
import androidx.work.BackoffPolicy.EXPONENTIAL
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy.REPLACE
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stevesoltys.seedvault.repo.Pruner
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.notification.NOTIFICATION_ID_PRUNING
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Duration

class AppBackupPruneWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    companion object {
        private val TAG = AppBackupPruneWorker::class.simpleName
        internal const val UNIQUE_WORK_NAME = "com.stevesoltys.seedvault.APP_BACKUP_PRUNE"

        fun scheduleNow(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<AppBackupPruneWorker>()
                .setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(EXPONENTIAL, Duration.ofSeconds(10))
                .build()
            val workManager = WorkManager.getInstance(context)
            Log.i(TAG, "Asking to prune app backups now...")
            workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, REPLACE, workRequest)
        }
    }

    private val log = KotlinLogging.logger {}
    private val pruner: Pruner by inject()
    private val nm: BackupNotificationManager by inject()

    override suspend fun doWork(): Result {
        log.info { "Start worker $this ($id)" }
        try {
            setForeground(createForegroundInfo())
        } catch (e: Exception) {
            log.error(e) { "Error while running setForeground: " }
        }
        return try {
            pruner.removeOldSnapshotsAndPruneUnusedBlobs()
            Result.success()
        } catch (e: Exception) {
            log.error(e) { "Error while pruning: " }
            Result.retry()
        }
    }

    private fun createForegroundInfo() = ForegroundInfo(
        NOTIFICATION_ID_PRUNING,
        nm.getPruningNotification(),
        FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
}
