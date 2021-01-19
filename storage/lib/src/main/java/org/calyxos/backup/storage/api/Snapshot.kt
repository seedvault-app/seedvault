package org.calyxos.backup.storage.api

import org.calyxos.backup.storage.backup.BackupSnapshot

public data class SnapshotItem(
    public val time: Long,
    public val snapshot: BackupSnapshot?,
)

public sealed class SnapshotResult {
    public data class Success(val snapshots: List<SnapshotItem>) : SnapshotResult()
    public data class Error(val e: Exception) : SnapshotResult()
}

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
