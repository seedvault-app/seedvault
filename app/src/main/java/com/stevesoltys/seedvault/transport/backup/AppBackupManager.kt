/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.SnapshotManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.calyxos.seedvault.core.backends.AppBackupFileType.Blob
import org.calyxos.seedvault.core.backends.AppBackupFileType.Snapshot
import org.calyxos.seedvault.core.backends.FileInfo
import org.calyxos.seedvault.core.backends.TopLevelFolder

internal class AppBackupManager(
    private val crypto: Crypto,
    private val blobCache: BlobCache,
    private val backendManager: BackendManager,
    private val settingsManager: SettingsManager,
    private val snapshotManager: SnapshotManager,
    private val snapshotCreatorFactory: SnapshotCreatorFactory,
) {

    private val log = KotlinLogging.logger {}
    var snapshotCreator: SnapshotCreator? = null
        private set

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
        snapshotCreator = snapshotCreatorFactory.createSnapshotCreator()
        val snapshots = snapshotManager.onSnapshotsLoaded(snapshotHandles)
        blobCache.populateCache(blobInfos, snapshots)
    }

    suspend fun afterBackupFinished(success: Boolean) {
        log.info { "After backup finished. Success: $success" }
        // free up memory by clearing blobs cache
        blobCache.clear()
        try {
            if (success) {
                val snapshot =
                    snapshotCreator?.finalizeSnapshot() ?: error("Had no snapshotCreator")
                keepTrying {
                    snapshotManager.saveSnapshot(snapshot)
                }
                settingsManager.token = snapshot.token
                // after snapshot was written, we can clear local cache as its info is in snapshot
                blobCache.clearLocalCache()
            }
        } finally {
            snapshotCreator = null
        }
    }

    private suspend fun keepTrying(n: Int = 3, block: suspend () -> Unit) {
        for (i in 1..n) {
            try {
                block()
                return
            } catch (e: Exception) {
                if (i == n) throw e
                log.error(e) { "Error (#$i), we'll keep trying" }
                delay(1000)
            }
        }
    }

}
