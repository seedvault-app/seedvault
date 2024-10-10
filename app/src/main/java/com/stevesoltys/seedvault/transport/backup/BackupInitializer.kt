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
import com.stevesoltys.seedvault.transport.TRANSPORT_ID

class BackupInitializer(
    private val backupManager: IBackupManager,
) {

    companion object {
        private val TAG = BackupInitializer::class.simpleName
    }

    fun initialize(onError: () -> Unit, onSuccess: () -> Unit) {
        val observer = BackupObserver("Initialization", onError) {
            onSuccess()
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
