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
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.BackupMonitor
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
    val packageService: PackageService,
) : KoinComponent {

    val isBackupEnabled: Boolean get() = backupManager.isBackupEnabled

    private val packages = packageService.eligiblePackages
    private val observer = NotificationBackupObserver(
        context = context,
        backupRequester = this,
        requestedPackages = packages.size,
    )
    private val monitor = BackupMonitor()

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
            nm.onBackupError()
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
