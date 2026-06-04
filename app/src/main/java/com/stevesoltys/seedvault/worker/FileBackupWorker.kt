/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.worker

import android.content.Context
import android.text.format.DateUtils.formatElapsedTime
import android.util.Log
import androidx.work.BackoffPolicy.EXPONENTIAL
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stevesoltys.seedvault.settings.SettingsManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.calyxos.backup.storage.api.BackupObserver
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.backup.BackupWorker
import org.calyxos.backup.storage.backup.NotificationBackupObserver
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit.HOURS
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.MINUTES

/**
 * The worker performing file backup.
 * It also gets used to kick off [AppBackupWorker] when it finished its own work.
 * This happens for regular scheduled backups as well as manual backups.
 * First we run file backup and then app backup.
 * The reason is that app backup gets run by the system outside the worker,
 * so we don't have a clear point when app backup is done.
 */
class FileBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : BackupWorker(appContext, workerParams), KoinComponent {

    companion object {
        private val TAG = FileBackupWorker::class.simpleName
        const val UNIQUE_WORK_NAME = BackupWorker.UNIQUE_WORK_NAME
        private const val TAG_RESCHEDULE = "com.stevesoltys.seedvault.TAG_RESCHEDULE"

        fun scheduleNow(context: Context, reschedule: Boolean) {
            val builder = OneTimeWorkRequestBuilder<FileBackupWorker>()
                .apply { if (reschedule) addTag(TAG_RESCHEDULE) }
            scheduleNow(context, builder)
        }

        /**
         * (Re-)schedules the [FileBackupWorker].
         *
         * @param existingWorkPolicy usually you want to use [ExistingPeriodicWorkPolicy.UPDATE]
         * only if you are sure that work is still scheduled
         * and you don't want to mess with the scheduling time.
         * In most other cases, you want to use [ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE],
         * because it ensures work gets schedules, even if it wasn't scheduled before.
         * It will however reset the scheduling time.
         */
        fun schedule(
            context: Context,
            settingsManager: SettingsManager,
            existingWorkPolicy: ExistingPeriodicWorkPolicy,
        ) {
            val logFrequency = formatElapsedTime(settingsManager.backupFrequencyInMillis / 1000)
            Log.i(TAG, "Scheduling in $logFrequency...")
            val constraints = Constraints.Builder().apply {
                if (!settingsManager.useMeteredNetwork) {
                    Log.i(TAG, "  only on unmetered networks")
                    setRequiredNetworkType(NetworkType.UNMETERED)
                }
                if (settingsManager.backupOnlyWhenCharging) {
                    Log.i(TAG, "  only when the device is charging")
                    setRequiresCharging(true)
                }
            }.build()
            val workRequest = PeriodicWorkRequestBuilder<FileBackupWorker>(
                repeatInterval = settingsManager.backupFrequencyInMillis,
                repeatIntervalTimeUnit = MILLISECONDS,
                flexTimeInterval = 2,
                flexTimeIntervalUnit = HOURS,
            ).setConstraints(constraints)
                .setBackoffCriteria(EXPONENTIAL, 5, MINUTES)
                .build()
            val workManager = WorkManager.getInstance(context)
            Log.i(TAG, "  workRequest: ${workRequest.id}")
            workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, existingWorkPolicy, workRequest)
        }

        fun unschedule(context: Context) {
            Log.i(TAG, "Unscheduling backups...")
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    private val log = KotlinLogging.logger {}
    override val storageBackup: StorageBackup by inject()
    private val settingsManager: SettingsManager by inject()
    private val backupRequester: BackupRequester by inject()
    override val backupObserver: BackupObserver by lazy {
        // use lazy delegate because context isn't available during construction time
        NotificationBackupObserver(applicationContext)
    }

    override suspend fun doWork(): Result {
        log.info { "Start worker $this ($id)" }
        return try {
            val result = if (settingsManager.isFileBackupEnabled()) {
                super.doWork()
            } else {
                // file backup is disabled, but we still want to kick off app backup,
                // so just return success here
                Result.success()
            }
            // only allow retrying if rescheduling is allowed
            if (tags.contains(TAG_RESCHEDULE)) result
            else Result.success()
        } finally {
            // run app backup now
            if (backupRequester.isAppBackupEnabled) AppBackupWorker.scheduleNow(applicationContext)
            // we also don't use work-chaining to run the app backups afterwards,
            // because there we don't get proper WorkInfo.State reporting
            // which is needed by BackupStateManager for tracking when backup is running

            // schedule next backup, because the old one gets lost
            // when scheduling a OneTimeWorkRequest with the same unique name via scheduleNow()
            if (tags.contains(TAG_RESCHEDULE) && backupRequester.isBackupEnabled) {
                // needs to use CANCEL_AND_REENQUEUE otherwise it doesn't get scheduled
                schedule(applicationContext, settingsManager, CANCEL_AND_REENQUEUE)
            }
        }
    }
}
