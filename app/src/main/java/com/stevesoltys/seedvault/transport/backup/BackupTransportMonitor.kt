/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY
import android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_NO_DATA_TO_SEND
import android.os.Bundle
import com.stevesoltys.seedvault.BackupMonitor
import com.stevesoltys.seedvault.repo.AppBackupManager
import com.stevesoltys.seedvault.repo.SnapshotManager
import io.github.oshai.kotlinlogging.KotlinLogging

internal class BackupTransportMonitor(
    private val appBackupManager: AppBackupManager,
    private val snapshotManager: SnapshotManager,
) : BackupMonitor() {

    private val log = KotlinLogging.logger { }

    override fun onEvent(id: Int, category: Int, packageName: String?, bundle: Bundle) {
        super.onEvent(id, category, packageName, bundle)
        if (packageName != null && id == LOG_EVENT_ID_NO_DATA_TO_SEND &&
            category == LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY
        ) {
            sendNoDataChanged(packageName)
        }
    }

    private fun sendNoDataChanged(packageName: String) {
        log.info { "sendNoDataChanged($packageName)" }

        val snapshot = snapshotManager.latestSnapshot
        if (snapshot == null) {
            log.error { "No latest snapshot!" }
        } else {
            val snapshotCreator = appBackupManager.snapshotCreator ?: error("No SnapshotCreator")
            snapshotCreator.onNoDataInCurrentRun(snapshot, packageName)
        }
    }
}
