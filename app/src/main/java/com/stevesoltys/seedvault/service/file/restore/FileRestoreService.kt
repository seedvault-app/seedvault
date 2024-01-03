/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.stevesoltys.seedvault.service.file.restore

import org.calyxos.backup.storage.api.RestoreObserver
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.restore.NotificationRestoreObserver
import org.calyxos.backup.storage.restore.RestoreService
import org.koin.android.ext.android.inject

internal class FileRestoreService : RestoreService() {
    override val storageBackup: StorageBackup by inject()

    // use lazy delegate because context isn't available during construction time
    override val restoreObserver: RestoreObserver by lazy {
        NotificationRestoreObserver(applicationContext)
    }
}
