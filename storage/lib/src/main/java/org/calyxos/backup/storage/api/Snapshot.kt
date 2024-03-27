/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.api

import org.calyxos.backup.storage.backup.BackupSnapshot

public data class SnapshotItem(
    public val storedSnapshot: StoredSnapshot,
    public val snapshot: BackupSnapshot?,
) {
    val time: Long get() = storedSnapshot.timestamp
}

public sealed class SnapshotResult {
    public data class Success(val snapshots: List<SnapshotItem>) : SnapshotResult()
    public data class Error(val e: Exception) : SnapshotResult()
}

public data class StoredSnapshot(
    /**
     * The unique ID of the current device/user combination chosen by the [StoragePlugin].
     * It may include an '.sv' extension.
     */
    public val userId: String,
    /**
     * The timestamp identifying a snapshot of the [userId].
     */
    public val timestamp: Long,
)

/**
 * Defines which backup snapshots should be retained when pruning backups.
 *
 * If more than one snapshot exists in a given time frame,
 * only the latest one will be kept.
 */
public data class SnapshotRetention(
    /**
     * Keep this many days worth of snapshots when pruning backups.
     */
    public val daily: Int,
    /**
     * Keep this many weeks worth of snapshots when pruning backups.
     */
    public val weekly: Int,
    /**
     * Keep this many months worth of snapshots when pruning backups.
     */
    public val monthly: Int,
    /**
     * Keep this many years worth of snapshots when pruning backups.
     */
    public val yearly: Int,
)
