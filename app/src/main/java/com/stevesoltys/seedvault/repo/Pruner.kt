/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.proto.Snapshot
import io.github.oshai.kotlinlogging.KotlinLogging
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.TopLevelFolder
import java.security.GeneralSecurityException
import java.time.LocalDate
import java.time.temporal.ChronoField
import java.time.temporal.TemporalAdjuster

/**
 * Cleans up old backups data that we do not need to retain.
 */
internal class Pruner(
    private val crypto: Crypto,
    private val backendManager: BackendManager,
    private val snapshotManager: SnapshotManager,
) {

    private val log = KotlinLogging.logger {}
    private val folder get() = TopLevelFolder(crypto.repoId)

    /**
     * Keeps the last 3 daily and 2 weekly snapshots (this and last week), removes all others.
     * Then removes all blobs from the backend
     * that are not referenced anymore by remaining snapshots.
     */
    suspend fun removeOldSnapshotsAndPruneUnusedBlobs() {
        // get snapshots currently available on backend
        val snapshotHandles = mutableListOf<AppBackupFileType.Snapshot>()
        backendManager.backend.list(folder, AppBackupFileType.Snapshot::class) { fileInfo ->
            snapshotHandles.add(fileInfo.fileHandle as AppBackupFileType.Snapshot)
        }
        // load and parse snapshots
        val snapshotMap = mutableMapOf<Long, AppBackupFileType.Snapshot>()
        val snapshots = mutableListOf<Snapshot>()
        snapshotHandles.forEach { handle ->
            try {
                val snapshot = snapshotManager.loadSnapshot(handle)
                snapshotMap[snapshot.token] = handle
                snapshots.add(snapshot)
            } catch (e: GeneralSecurityException) {
                log.error(e) { "Error loading snapshot $handle, will remove: " }
                snapshotManager.removeSnapshot(handle)
            } // other exceptions (like IOException) are allowed to bubble up, so we try again
        }
        // find out which snapshots to keep
        val toKeep = getTokenToKeep(snapshotMap.keys)
        log.info { "Found ${snapshots.size} snapshots, keeping ${toKeep.size}." }
        // remove snapshots we aren't keeping
        snapshotMap.forEach { (token, handle) ->
            if (token !in toKeep) {
                log.info { "Removing snapshot $token ${handle.name}" }
                snapshotManager.removeSnapshot(handle)
            }
        }
        // prune unused blobs
        val keptSnapshots = snapshots.filter { it.token in toKeep }
        pruneUnusedBlobs(keptSnapshots)
    }

    private suspend fun pruneUnusedBlobs(snapshots: List<Snapshot>) {
        val blobHandles = mutableListOf<AppBackupFileType.Blob>()
        backendManager.backend.list(folder, AppBackupFileType.Blob::class) { fileInfo ->
            blobHandles.add(fileInfo.fileHandle as AppBackupFileType.Blob)
        }
        val usedBlobIds = snapshots.flatMap { snapshot ->
            snapshot.blobsMap.values.map { blob ->
                blob.id.hexFromProto()
            }
        }.toSet()
        blobHandles.forEach { blobHandle ->
            if (blobHandle.name !in usedBlobIds) {
                log.info { "Removing blob ${blobHandle.name}" }
                backendManager.backend.remove(blobHandle)
            }
        }
    }

    private fun getTokenToKeep(tokenSet: Set<Long>): Set<Long> {
        if (tokenSet.size <= 3) return tokenSet // keep at least 3 snapshots
        val tokenList = tokenSet.sortedDescending()
        val toKeep = mutableSetOf<Long>()
        toKeep += getToKeep(tokenList, 3) // 3 daily
        toKeep += getToKeep(tokenList, 2) { temporal -> // keep one from this and last week
            temporal.with(ChronoField.DAY_OF_WEEK, 1)
        }
        // ensure we keep at least three snapshots
        val tokenIterator = tokenList.iterator()
        while (toKeep.size < 3 && tokenIterator.hasNext()) toKeep.add(tokenIterator.next())
        return toKeep
    }

    private fun getToKeep(
        tokenList: List<Long>,
        keep: Int,
        temporalAdjuster: TemporalAdjuster? = null,
    ): List<Long> {
        val toKeep = mutableListOf<Long>()
        if (keep == 0) return toKeep
        var last: LocalDate? = null
        for (token in tokenList) {
            val date = LocalDate.ofEpochDay(token / 1000 / 60 / 60 / 24)
            val period = if (temporalAdjuster == null) date else date.with(temporalAdjuster)
            if (period != last) {
                toKeep.add(token)
                if (toKeep.size >= keep) break
                last = period
            }
        }
        return toKeep
    }

}
