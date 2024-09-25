/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.MemoryLogger
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.settings.SettingsManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.calyxos.seedvault.core.backends.AppBackupFileType.Blob
import org.calyxos.seedvault.core.backends.AppBackupFileType.Snapshot
import org.calyxos.seedvault.core.backends.FileInfo
import org.calyxos.seedvault.core.backends.TopLevelFolder
import java.io.IOException

/**
 * Manages the process of app data backups, especially related to work that needs to happen
 * before and after a backup run.
 * See [beforeBackup] and [afterBackupFinished].
 */
internal class AppBackupManager(
    private val crypto: Crypto,
    private val blobCache: BlobCache,
    private val backendManager: BackendManager,
    private val settingsManager: SettingsManager,
    private val snapshotManager: SnapshotManager,
    private val snapshotCreatorFactory: SnapshotCreatorFactory,
) {

    private val log = KotlinLogging.logger {}

    /**
     * A temporary [SnapshotCreator] that has a lifetime only valid during the backup run.
     */
    @Volatile
    var snapshotCreator: SnapshotCreator? = null
        private set

    @Volatile
    private var startedViaAdb = false

    /**
     * Call this method before doing any kind of backup work.
     * It will
     * * download the blobs available on the backend,
     * * assemble the chunk ID to blob mapping from previous snapshots and
     * * create a new instance of a [SnapshotCreator].
     *
     * @throws IOException or other exceptions.
     * These should be caught by the caller who may retry us on transient errors.
     */
    @WorkerThread
    @Throws(IOException::class)
    suspend fun beforeBackup() {
        log.info { "Loading existing snapshots and blobs..." }
        val blobInfos = mutableListOf<FileInfo>()
        val snapshotHandles = mutableListOf<Snapshot>()
        backendManager.backend.list(
            topLevelFolder = TopLevelFolder(crypto.repoId),
            Blob::class, Snapshot::class,
        ) { fileInfo ->
            when (fileInfo.fileHandle) {
                is Blob -> blobInfos.add(fileInfo)
                is Snapshot -> snapshotHandles.add(fileInfo.fileHandle as Snapshot)
                else -> error("Unexpected FileHandle: $fileInfo")
            }
        }
        log.info { "Found ${snapshotHandles.size} existing snapshots." }
        val snapshots = snapshotManager.onSnapshotsLoaded(snapshotHandles)
        blobCache.populateCache(blobInfos, snapshots)
        snapshotCreator = snapshotCreatorFactory.createSnapshotCreator()
    }

    /**
     * This must be called after the backup run has been completed.
     * It finalized the current snapshot and saves it to the backend.
     * Then, it clears up the [BlobCache] and the [SnapshotCreator].
     *
     * @param success true if the backup run was successful, false otherwise.
     *
     * @return the snapshot saved to the backend or null if there was an error saving it.
     */
    @WorkerThread
    suspend fun afterBackupFinished(success: Boolean): com.stevesoltys.seedvault.proto.Snapshot? {
        MemoryLogger.log()
        log.info { "After backup finished. Success: $success" }
        // free up memory by clearing blobs cache
        blobCache.clear()
        return try {
            if (success) {
                // only save snapshot when backup was successful,
                // otherwise we'd have partial snapshots
                val snapshot = snapshotCreator?.finalizeSnapshot()
                    ?: error("Had no snapshotCreator")
                keepTrying { // TODO remove when we have auto-retrying backends
                    // saving this is so important, we even keep trying
                    snapshotManager.saveSnapshot(snapshot)
                }
                // save token and time of last backup
                settingsManager.onSuccessfulBackupCompleted(snapshot.token)
                // after snapshot was written, we can clear local cache as its info is in snapshot
                blobCache.clearLocalCache()
                snapshot
            } else null
        } catch (e: Exception) {
            log.error(e) { "Error finishing backup" }
            null
        } finally {
            snapshotCreator = null
            MemoryLogger.log()
        }
    }

    /**
     * When doing backups with `adb shell bmgr backupnow`,
     * we don't get a chance to do our initialization in [beforeBackup],
     * so we use this opportunity to do it now.
     */
    suspend fun ensureBackupPrepared() = if (snapshotCreator == null) {
        log.warn { "Backup not prepared. If not started via `adb shell bmgr` that's a bug" }
        startedViaAdb = true
        beforeBackup()
    } else Unit

    /**
     * We don't get notified when backups ran from `adb shell bmgr backupnow` end,
     * so [afterBackupFinished] will not run, so we need to find a place
     */
    suspend fun finalizeBackupIfNeeded() {
        if (startedViaAdb) {
            log.warn { "Backup not finalized. If not started via `adb shell bmgr` that's a bug" }
            startedViaAdb = false
            afterBackupFinished(true) // is there a way to know if success or not?
        }
    }

    /**
     * Returns true if the repo identified by [repoId] can be transferred to this device.
     * This is the case when it isn't the same as the current repoId and the version is latest.
     */
    fun canRecycleBackupRepo(repoId: String?, version: Byte?): Boolean {
        if (repoId == null || version == null) return false
        return repoId != crypto.repoId && version == VERSION
    }

    /**
     * Transfers the ownership of the backup repository identified by the [oldRepoId]
     * to the current user and device
     * by renaming the [TopLevelFolder] of the repo to the current repoId.
     */
    @Throws(IOException::class)
    suspend fun recycleBackupRepo(oldRepoId: String) {
        val newRepoId = crypto.repoId
        if (oldRepoId == newRepoId) return
        val oldFolder = TopLevelFolder(oldRepoId)
        val newFolder = TopLevelFolder(newRepoId)
        backendManager.backend.rename(oldFolder, newFolder)
    }

    /**
     * Careful, this removes the entire backup repository from the backend
     * and clears local blob cache.
     */
    @WorkerThread
    @Throws(IOException::class)
    suspend fun removeBackupRepo() {
        blobCache.clearLocalCache()
        // TODO not critical, but nice to have: clear also local snapshot cache
        backendManager.backend.remove(TopLevelFolder(crypto.repoId))
    }

    private suspend fun keepTrying(n: Int = 3, block: suspend () -> Unit) {
        for (i in 1..n) {
            try {
                block()
                return
            } catch (e: Exception) {
                if (i == n) throw e
                log.error(e) { "Error (#$i), we'll keep trying" }
                delay(1000 * i.toLong())
            }
        }
    }

}
