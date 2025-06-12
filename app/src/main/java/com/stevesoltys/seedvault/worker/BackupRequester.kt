/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.worker

import android.app.backup.BackupManager
import android.app.backup.IBackupManager
import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.work.WorkInfo.State.RUNNING
import androidx.work.WorkManager
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.ConfigurableBackupTransportService
import com.stevesoltys.seedvault.transport.backup.BackupTransportMonitor
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.notification.NotificationBackupObserver
import org.koin.core.component.KoinComponent
import org.koin.core.context.GlobalContext

private val TAG = BackupRequester::class.java.simpleName
internal const val NUM_PACKAGES_PER_TRANSACTION = 100

/**
 * Used for requesting a backup of all installed packages,
 * in chunks if there are more than [NUM_PACKAGES_PER_TRANSACTION].
 *
 * Can only be used once for one backup.
 * Make a new instance for subsequent backups.
 */
@WorkerThread
internal class BackupRequester(
    context: Context,
    private val backupManager: IBackupManager,
    private val settingsManager: SettingsManager,
    private val monitor: BackupTransportMonitor,
    val packageService: PackageService,
) : KoinComponent {

    companion object {
        @UiThread
        fun requestFilesAndAppBackup(
            context: Context,
            settingsManager: SettingsManager,
            backupManager: IBackupManager,
            reschedule: Boolean = false,
        ) {
            // Extra protection against running more than one backup at a time.
            // This may happen for example when entering a flash drive multiple times.
            if (isBackupRunning(context)) {
                Log.e(TAG, "Backup already running, not requesting again.")
                return
            }
            if (settingsManager.isFileBackupEnabled()) {
                // This launches file backup first,
                // because the app backup worker only kicks off the backup and finishes early.
                Log.i(TAG, "Starting FileBackupWorker...")
                FileBackupWorker.scheduleNow(context, reschedule)
            } else if (backupManager.isBackupEnabled) {
                Log.i(TAG, "Starting AppBackupWorker...")
                AppBackupWorker.scheduleNow(context)
            } else {
                Log.d(TAG, "Neither files nor app backup enabled, do nothing.")
            }
        }

        fun isBackupRunning(context: Context): Boolean {
            val workManager = WorkManager.getInstance(context)
            val appBackupRunning = ConfigurableBackupTransportService.isRunning.value
            return try {
                val appBackupState = workManager
                    .getWorkInfosForUniqueWork(AppBackupWorker.UNIQUE_WORK_NAME).get()
                    .getOrNull(0)?.state
                val fileBackupState = workManager
                    .getWorkInfosForUniqueWork(AppBackupWorker.UNIQUE_WORK_NAME).get()
                    .getOrNull(0)?.state
                Log.i(
                    TAG, "appBackupRunning: $appBackupRunning, " +
                        "filesBackupRunning: ${fileBackupState?.name}, " +
                        "appBackupWorker: ${appBackupState?.name}"
                )
                appBackupRunning || fileBackupState == RUNNING || appBackupState == RUNNING
            } catch (e: Exception) {
                Log.e(TAG, "Error determining current backup state: ", e)
                // not much we can do here, let's just assume no backup is running
                false
            }
        }
    }

    /**
     * Returns true if either app or file backup is enabled.
     */
    val isBackupEnabled: Boolean get() = isAppBackupEnabled || isFileBackupEnabled

    val isAppBackupEnabled: Boolean get() = backupManager.isBackupEnabled
    val isFileBackupEnabled: Boolean get() = settingsManager.isFileBackupEnabled()

    private val packages by lazy { packageService.eligiblePackages }
    private val observer by lazy {
        NotificationBackupObserver(context, this, packages.size)
    }

    /**
     * The current package index.
     *
     * Used for splitting the packages into chunks.
     */
    private var packageIndex: Int = 0

    /**
     * Request the backup to happen. Should be called short after constructing this object.
     */
    fun requestBackup(): Boolean {
        if (packageIndex != 0) error("requestBackup() called more than once!")

        return request(getNextChunk())
    }

    /**
     * Returns true, if there are more packages waiting to get backed up by calling [requestNext].
     */
    val hasNext: Boolean get() = packageIndex < packages.size

    /**
     * Backs up the next chunk of packages.
     *
     * @return true, if backup for all packages was already requested and false,
     * if there are more packages that we just have requested backup for.
     */
    fun requestNext(): Boolean {
        if (packageIndex <= 0) error("requestBackup() must be called first!")

        // Backup next chunk if there are more packages to back up.
        return if (packageIndex < packages.size) {
            request(getNextChunk())
            false
        } else {
            true
        }
    }

    private fun request(chunk: Array<String>): Boolean {
        Log.i(TAG, "${chunk.toList()}")
        val result = try {
            backupManager.requestBackup(chunk, observer, monitor, 0)
        } catch (e: RemoteException) {
            Log.e(TAG, "Error during backup: ", e)
            val nm: BackupNotificationManager = GlobalContext.get().get()
            nm.onFixableBackupError()
        }
        return if (result == BackupManager.SUCCESS) {
            Log.i(TAG, "Backup request succeeded")
            true
        } else {
            Log.e(TAG, "Backup request failed: $result")
            false
        }
    }

    private fun getNextChunk(): Array<String> {
        val nextChunkIndex =
            (packageIndex + NUM_PACKAGES_PER_TRANSACTION).coerceAtMost(packages.size)
        val packageChunk = packages.subList(packageIndex, nextChunkIndex).toTypedArray()
        val numBackingUp = packageIndex + packageChunk.size
        Log.i(TAG, "Requesting backup for $numBackingUp of ${packages.size} packages...")
        packageIndex += packageChunk.size
        return packageChunk
    }
}
