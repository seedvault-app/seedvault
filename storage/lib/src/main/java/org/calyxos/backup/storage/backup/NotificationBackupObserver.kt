/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.backup

import android.content.Context
import org.calyxos.backup.storage.R
import org.calyxos.backup.storage.api.BackupFile
import org.calyxos.backup.storage.api.BackupObserver
import org.calyxos.backup.storage.ui.Notifications

public open class NotificationBackupObserver internal constructor(private val n: Notifications) :
    BackupObserver {

    public constructor(context: Context) : this(Notifications(context))

    private var totalFiles = 0
    private var filesBackedUp = 0
    private var filesWithError = 0
    private var snapshotsToPrune = 0
    private var snapshotsPruned = 0

    override suspend fun onStartScanning() {
        n.updateBackupNotification(R.string.notification_backup_scanning)
    }

    override suspend fun onBackupStart(
        totalSize: Long,
        numFiles: Int,
        numSmallFiles: Int,
        numLargeFiles: Int,
    ) {
        totalFiles = numFiles
        n.updateBackupNotification(
            R.string.notification_backup_backup_files,
            filesBackedUp + filesWithError,
            totalFiles
        )
    }

    override suspend fun onFileBackedUp(
        file: BackupFile,
        wasUploaded: Boolean,
        reusedChunks: Int,
        bytesWritten: Long,
        tag: String,
    ) {
        filesBackedUp++
        n.updateBackupNotification(
            R.string.notification_backup_backup_files,
            filesBackedUp + filesWithError,
            totalFiles
        )
    }

    override suspend fun onFileBackupError(file: BackupFile, tag: String) {
        filesWithError++
        n.updateBackupNotification(
            R.string.notification_backup_backup_files,
            filesBackedUp + filesWithError,
            totalFiles
        )
    }

    override suspend fun onBackupComplete(backupDuration: Long?) {
        n.cancelBackupNotification()
    }

    override suspend fun onPruneStartScanning() {
        n.updatePruneNotification(R.string.notification_prune)
    }

    override suspend fun onPruneStart(snapshotsToDelete: List<Long>) {
        snapshotsToPrune = snapshotsToDelete.size
        n.updatePruneNotification(R.string.notification_prune, snapshotsPruned, snapshotsToPrune)
    }

    override suspend fun onPruneSnapshot(snapshot: Long, numChunksToDelete: Int, size: Long) {
        snapshotsPruned++
        n.updatePruneNotification(R.string.notification_prune, snapshotsPruned, snapshotsToPrune)
    }

    override suspend fun onPruneError(snapshot: Long?, e: Exception) {
        if (snapshot == null) {
            n.cancelPruneNotification()
        } else {
            snapshotsPruned++
            n.updatePruneNotification(
                R.string.notification_prune,
                snapshotsPruned,
                snapshotsToPrune
            )
        }
    }

    override suspend fun onPruneComplete(pruneDuration: Long) {
        n.cancelPruneNotification()
    }

}
