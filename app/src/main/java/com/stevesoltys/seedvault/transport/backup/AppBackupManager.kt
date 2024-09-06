/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import com.stevesoltys.seedvault.transport.SnapshotManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay

internal class AppBackupManager(
    private val blobsCache: BlobsCache,
    private val snapshotManager: SnapshotManager,
    private val snapshotCreatorFactory: SnapshotCreatorFactory,
) {

    private val log = KotlinLogging.logger {}
    var snapshotCreator: SnapshotCreator? = null
        private set

    suspend fun beforeBackup() {
        log.info { "Before backup" }
        snapshotCreator = snapshotCreatorFactory.createSnapshotCreator()
        blobsCache.populateCache()
    }

    suspend fun afterBackupFinished() {
        log.info { "After backup finished" }
        blobsCache.clear()
        val snapshot = snapshotCreator?.finalizeSnapshot() ?: error("Had no snapshotCreator")
        keepTrying {
            snapshotManager.saveSnapshot(snapshot)
        }
        snapshotCreator = null
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
