/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.storage

import android.app.Application
import android.app.backup.IBackupManager
import android.app.job.JobInfo
import android.os.UserHandle
import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.backend.saf.SafHandler
import com.stevesoltys.seedvault.backend.webdav.WebDavHandler
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.storage.StorageBackupJobService
import com.stevesoltys.seedvault.transport.backup.BackupInitializer
import com.stevesoltys.seedvault.worker.AppBackupWorker
import com.stevesoltys.seedvault.worker.BackupRequester.Companion.requestFilesAndAppBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.backup.BackupJobService
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.saf.SafProperties
import org.calyxos.seedvault.core.backends.webdav.WebDavProperties
import java.io.IOException
import java.util.concurrent.TimeUnit

private val TAG = BackupStorageViewModel::class.java.simpleName

internal class BackupStorageViewModel(
    private val app: Application,
    private val backupManager: IBackupManager,
    private val backupInitializer: BackupInitializer,
    private val storageBackup: StorageBackup,
    safHandler: SafHandler,
    webDavHandler: WebDavHandler,
    settingsManager: SettingsManager,
    backendManager: BackendManager,
) : StorageViewModel(app, safHandler, webDavHandler, settingsManager, backendManager) {

    override val isRestoreOperation = false

    override fun onSafUriSet(safProperties: SafProperties) {
        safHandler.save(safProperties)
        safHandler.setPlugin(safProperties)
        if (safProperties.isUsb) {
            // disable storage backup if new storage is on USB
            cancelBackupWorkers()
        } else {
            // enable it, just in case the previous storage was on USB,
            // also to update the network requirement of the new storage
            scheduleBackupWorkers()
        }
        onStorageLocationSet(safProperties.isUsb)
    }

    override fun onWebDavConfigSet(properties: WebDavProperties, backend: Backend) {
        webdavHandler.save(properties)
        webdavHandler.setPlugin(properties, backend)
        scheduleBackupWorkers()
        onStorageLocationSet(isUsb = false)
    }

    private fun onStorageLocationSet(isUsb: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // remove old storage snapshots and clear cache
                storageBackup.init()
                // initialize the new location (if backups are enabled)
                if (backupManager.isBackupEnabled) {
                    val onError = {
                        Log.e(TAG, "Error starting new RestoreSet")
                        onInitializationError()
                    }
                    backupInitializer.initialize(onError) {
                        val requestBackup = isUsb && !isSetupWizard
                        if (requestBackup) {
                            Log.i(TAG, "Requesting a backup now, because we use USB storage")
                            requestFilesAndAppBackup(app, settingsManager, backupManager)
                        }
                        // notify the UI that the location has been set
                        mLocationChecked.postEvent(LocationResult())
                    }
                } else {
                    // notify the UI that the location has been set
                    mLocationChecked.postEvent(LocationResult())
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error starting new RestoreSet", e)
                onInitializationError()
            }
        }
    }

    private fun scheduleBackupWorkers() {
        val storage = backendManager.backendProperties ?: error("no storage available")
        // disable framework scheduling, because another transport may have enabled it
        backupManager.setFrameworkSchedulingEnabledForUser(UserHandle.myUserId(), false)
        if (!storage.isUsb) {
            if (backupManager.isBackupEnabled) {
                AppBackupWorker.schedule(app, settingsManager, CANCEL_AND_REENQUEUE)
            }
            if (settingsManager.isStorageBackupEnabled()) BackupJobService.scheduleJob(
                context = app,
                jobServiceClass = StorageBackupJobService::class.java,
                periodMillis = TimeUnit.HOURS.toMillis(24),
                networkType = if (storage.requiresNetwork) JobInfo.NETWORK_TYPE_UNMETERED
                else JobInfo.NETWORK_TYPE_NONE,
                deviceIdle = false,
                charging = true
            )
        }
    }

    private fun cancelBackupWorkers() {
        AppBackupWorker.unschedule(app)
        BackupJobService.cancelJob(app)
    }

    private fun onInitializationError() {
        val errorMsg = app.getString(R.string.storage_check_fragment_backup_error)
        mLocationChecked.postEvent(LocationResult(errorMsg))
    }

}
