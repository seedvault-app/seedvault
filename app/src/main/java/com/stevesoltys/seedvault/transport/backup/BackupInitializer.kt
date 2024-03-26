/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupProgress
import android.app.backup.IBackupManager
import android.app.backup.IBackupObserver
import android.os.UserHandle
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.BackupMonitor
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.transport.TRANSPORT_ID

class BackupInitializer(
    private val backupManager: IBackupManager,
) {

    companion object {
        private val TAG = BackupInitializer::class.simpleName
    }

    fun initialize(onError: () -> Unit, onSuccess: () -> Unit) {
        val observer = BackupObserver("Initialization", onError) {
            // After successful initialization, we request a @pm@ backup right away,
            // because if this finds empty state, it asks us to do another initialization.
            // And then we end up with yet another restore set token.
            // Since we want the final token as soon as possible, we need to get it here.
            Log.d(TAG, "Requesting initial $MAGIC_PACKAGE_MANAGER backup...")
            backupManager.requestBackup(
                arrayOf(MAGIC_PACKAGE_MANAGER),
                BackupObserver("Initial backup of @pm@", onError, onSuccess),
                BackupMonitor(),
                0,
            )
        }
        backupManager.initializeTransportsForUser(
            UserHandle.myUserId(),
            arrayOf(TRANSPORT_ID),
            observer,
        )
    }

    @WorkerThread
    private inner class BackupObserver(
        private val operation: String,
        private val onError: () -> Unit,
        private val onSuccess: () -> Unit,
    ) : IBackupObserver.Stub() {
        override fun onUpdate(currentBackupPackage: String, backupProgress: BackupProgress) {
            // noop
        }

        override fun onResult(target: String, status: Int) {
            // noop
        }

        override fun backupFinished(status: Int) {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "$operation finished. Status: $status")
            }
            if (status == 0) {
                onSuccess()
            } else {
                onError()
            }
        }
    }

}
