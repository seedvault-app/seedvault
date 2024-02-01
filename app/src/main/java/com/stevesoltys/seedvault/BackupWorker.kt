/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.stevesoltys.seedvault.transport.requestBackup
import java.util.concurrent.TimeUnit

class BackupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {

    companion object {
        private const val UNIQUE_WORK_NAME = "APP_BACKUP"

        fun schedule(appContext: Context) {
            val backupConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .build()
            val backupWorkRequest = PeriodicWorkRequestBuilder<BackupWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 2,
                flexTimeIntervalUnit = TimeUnit.HOURS,
            ).setConstraints(backupConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()
            val workManager = WorkManager.getInstance(appContext)
            workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, UPDATE, backupWorkRequest)
        }

        fun unschedule(appContext: Context) {
            val workManager = WorkManager.getInstance(appContext)
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    override fun doWork(): Result {
        // TODO once we make this the default, we should do storage backup here as well
        //  or have two workers and ensure they never run at the same time
        return if (requestBackup(applicationContext)) Result.success()
        else Result.retry()
    }
}
