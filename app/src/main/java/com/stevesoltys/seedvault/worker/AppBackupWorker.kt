/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.worker

import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.ExistingWorkPolicy.REPLACE
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.notification.NOTIFICATION_ID_OBSERVER
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

class AppBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    companion object {
        private val TAG = AppBackupWorker::class.simpleName
        internal const val UNIQUE_WORK_NAME = "com.stevesoltys.seedvault.APP_BACKUP"
        private const val TAG_RESCHEDULE = "com.stevesoltys.seedvault.TAG_RESCHEDULE"

        fun schedule(context: Context, existingWorkPolicy: ExistingPeriodicWorkPolicy = UPDATE) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .build()
            val workRequest = PeriodicWorkRequestBuilder<AppBackupWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 2,
                flexTimeIntervalUnit = TimeUnit.HOURS,
            ).setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            val workManager = WorkManager.getInstance(context)
            Log.i(TAG, "Scheduling app backup: $workRequest")
            workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, existingWorkPolicy, workRequest)
        }

        fun scheduleNow(context: Context, reschedule: Boolean) {
            val workRequest = OneTimeWorkRequestBuilder<AppBackupWorker>()
                .setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .apply { if (reschedule) addTag(TAG_RESCHEDULE) }
                .build()
            val workManager = WorkManager.getInstance(context)
            Log.i(TAG, "Asking to do app backup now...")
            workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, REPLACE, workRequest)
        }

        fun unschedule(context: Context) {
            Log.i(TAG, "Unscheduling app backup...")
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    private val backupRequester: BackupRequester by inject()
    private val apkBackupManager: ApkBackupManager by inject()
    private val nm: BackupNotificationManager by inject()

    override suspend fun doWork(): Result {
        Log.i(TAG, "Start worker  $this ($id)")
        try {
            setForeground(createForegroundInfo())
        } catch (e: Exception) {
            Log.e(TAG, "Error while running setForeground: ", e)
        }
        return try {
            if (isStopped) {
                Result.retry()
            } else {
                doBackup()
            }
        } finally {
            // schedule next backup, because the old one gets lost
            // when scheduling a OneTimeWorkRequest with the same unique name via scheduleNow()
            if (tags.contains(TAG_RESCHEDULE) && backupRequester.isBackupEnabled) {
                // needs to use CANCEL_AND_REENQUEUE otherwise it doesn't get scheduled
                schedule(applicationContext, CANCEL_AND_REENQUEUE)
            }
        }
    }

    private suspend fun doBackup(): Result {
        var result: Result = Result.success()
        try {
            Log.i(TAG, "Starting APK backup... (stopped: $isStopped)")
            if (!isStopped) apkBackupManager.backup()
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up APKs: ", e)
            result = Result.retry()
        } finally {
            Log.i(TAG, "Requesting app data backup... (stopped: $isStopped)")
            val requestSuccess = if (!isStopped && backupRequester.isBackupEnabled) {
                Log.d(TAG, "Backup is enabled, request backup...")
                backupRequester.requestBackup()
            } else true
            Log.d(TAG, "Have requested backup.")
            if (!requestSuccess) result = Result.retry()
        }
        return result
    }

    private fun createForegroundInfo() = ForegroundInfo(
        NOTIFICATION_ID_OBSERVER,
        nm.getBackupNotification(""),
        FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
}
