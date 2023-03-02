/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.api

public interface BackupObserver {

    public suspend fun onStartScanning()

    /**
     * Called after scanning files when starting the backup.
     *
     * @param totalSize the total size of all files to be backed up.
     * @param numFiles the number of all files to be backed up.
     * The sum of [numSmallFiles] and [numLargeFiles].
     * @param numSmallFiles the number of small files to be backed up.
     * @param numLargeFiles the number of large files to be backed up.
     */
    public suspend fun onBackupStart(
        totalSize: Long,
        numFiles: Int,
        numSmallFiles: Int,
        numLargeFiles: Int,
    )

    public suspend fun onFileBackedUp(
        file: BackupFile,
        wasUploaded: Boolean,
        reusedChunks: Int,
        bytesWritten: Long,
        tag: String,
    )

    public suspend fun onFileBackupError(
        file: BackupFile,
        tag: String,
    )

    /**
     * If backupDuration is null, the overall backup failed.
     */
    public suspend fun onBackupComplete(backupDuration: Long?)

    public suspend fun onPruneStartScanning()

    public suspend fun onPruneStart(snapshotsToDelete: List<Long>)

    public suspend fun onPruneSnapshot(snapshot: Long, numChunksToDelete: Int, size: Long)

    /**
     * If snapshot is null, the overall operation failed.
     */
    public suspend fun onPruneError(snapshot: Long?, e: Exception)

    public suspend fun onPruneComplete(pruneDuration: Long)

}
