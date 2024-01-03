/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.stevesoltys.seedvault.service.file.backup

import android.content.Intent
import com.stevesoltys.seedvault.service.app.backup.requestBackup
import org.calyxos.backup.storage.api.BackupObserver
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.backup.BackupService
import org.calyxos.backup.storage.backup.NotificationBackupObserver
import org.koin.android.ext.android.inject

internal class FileBackupService : BackupService() {

    companion object {
        internal const val EXTRA_START_APP_BACKUP = "startAppBackup"
    }

    override val storageBackup: StorageBackup by inject()

    // use lazy delegate because context isn't available during construction time
    override val backupObserver: BackupObserver by lazy {
        NotificationBackupObserver(applicationContext)
    }

    override fun onBackupFinished(intent: Intent, success: Boolean) {
        if (intent.getBooleanExtra(EXTRA_START_APP_BACKUP, false)) {
            requestBackup(applicationContext)
        }
    }
}
