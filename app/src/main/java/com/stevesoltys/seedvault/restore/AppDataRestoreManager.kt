/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore

import android.app.backup.BackupManager
import android.app.backup.BackupTransport
import android.app.backup.IBackupManager
import android.app.backup.IRestoreObserver
import android.app.backup.IRestoreSession
import android.app.backup.RestoreSet
import android.content.Context
import android.content.Intent
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.stevesoltys.seedvault.BackupMonitor
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.NO_DATA_END_SENTINEL
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.metadata.PackageMetadataMap
import com.stevesoltys.seedvault.metadata.PackageState
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.restore.install.isInstalled
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.TRANSPORT_ID
import com.stevesoltys.seedvault.transport.restore.RestoreCoordinator
import com.stevesoltys.seedvault.ui.AppBackupState
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED_NOT_ALLOWED
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED_NOT_INSTALLED
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED_NO_DATA
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED_QUOTA_EXCEEDED
import com.stevesoltys.seedvault.ui.AppBackupState.IN_PROGRESS
import com.stevesoltys.seedvault.ui.AppBackupState.NOT_YET_BACKED_UP
import com.stevesoltys.seedvault.ui.AppBackupState.SUCCEEDED
import com.stevesoltys.seedvault.ui.notification.getAppName
import java.util.LinkedList
import java.util.Locale

private val TAG = AppDataRestoreManager::class.simpleName

internal data class AppRestoreResult(
    val packageName: String,
    val name: String,
    val state: AppBackupState,
)

internal class AppDataRestoreManager(
    private val context: Context,
    private val backupManager: IBackupManager,
    private val settingsManager: SettingsManager,
    private val restoreCoordinator: RestoreCoordinator,
    private val storagePluginManager: StoragePluginManager,
) {

    private var session: IRestoreSession? = null
    private val monitor = BackupMonitor()
    private val foregroundServiceIntent = Intent(context, RestoreService::class.java)

    private val mRestoreProgress = MutableLiveData(
        LinkedList<AppRestoreResult>().apply {
            add(
                AppRestoreResult(
                    packageName = MAGIC_PACKAGE_MANAGER,
                    name = getAppName(context, MAGIC_PACKAGE_MANAGER).toString(),
                    state = IN_PROGRESS,
                )
            )
        }
    )
    val restoreProgress: LiveData<LinkedList<AppRestoreResult>> get() = mRestoreProgress
    private val mRestoreBackupResult = MutableLiveData<RestoreBackupResult>()
    val restoreBackupResult: LiveData<RestoreBackupResult> get() = mRestoreBackupResult

    @WorkerThread
    fun startRestore(restorableBackup: RestorableBackup) {
        val token = restorableBackup.token

        Log.d(TAG, "Starting new restore session to restore backup $token")

        // if we had no token before (i.e. restore from setup wizard),
        // use the token of the current restore set from now on
        if (settingsManager.getToken() == null) {
            settingsManager.setNewToken(token)
        }

        // start a new restore session
        val session = try {
            getOrStartSession()
        } catch (e: RemoteException) {
            Log.e(TAG, "Error starting new session", e)
            mRestoreBackupResult.postValue(
                RestoreBackupResult(context.getString(R.string.restore_set_error))
            )
            return
        }

        val providerPackageName = storagePluginManager.appPlugin.providerPackageName
        val observer = RestoreObserver(
            restoreCoordinator = restoreCoordinator,
            restorableBackup = restorableBackup,
            session = session,
            // sort packages (reverse) alphabetically, since we move from bottom to top
            packages = restorableBackup.packageMetadataMap.packagesSortedByNameDescending.filter {
                // filter out current plugin package name, so it doesn't kill our restore
                it != providerPackageName
            },
            monitor = monitor,
        )

        // We need to retrieve the restore sets before starting the restore.
        // Otherwise, restorePackages() won't work as they need the restore sets cached internally.
        if (session.getAvailableRestoreSets(observer, monitor) != 0) {
            Log.e(TAG, "getAvailableRestoreSets() returned non-zero value")

            mRestoreBackupResult.postValue(
                RestoreBackupResult(context.getString(R.string.restore_set_error))
            )
        } else {
            // don't use startForeground(), because we may stop it sooner than the system likes
            context.startService(foregroundServiceIntent)
        }
    }

    @Throws(RemoteException::class)
    private fun getOrStartSession(): IRestoreSession {
        @Suppress("UNRESOLVED_REFERENCE")
        val session = this.session
            ?: backupManager.beginRestoreSessionForUser(UserHandle.myUserId(), null, TRANSPORT_ID)
            ?: throw RemoteException("beginRestoreSessionForUser returned null")
        this.session = session
        return session
    }

    @WorkerThread
    // this should be called one package at a time and never concurrently for different packages
    private fun onRestoreStarted(packageName: String, backup: RestorableBackup) {
        // list is never null and always has at least one package
        val list = mRestoreProgress.value!!

        // check previous package first and change status
        updateLatestPackage(list, backup)

        // add current package
        val name = getAppName(
            context = context,
            packageName = packageName,
            fallback = backup.packageMetadataMap[packageName]?.name?.toString() ?: packageName,
        )
        list.addFirst(AppRestoreResult(packageName, name.toString(), IN_PROGRESS))
        mRestoreProgress.postValue(list)
    }

    @WorkerThread
    private fun updateLatestPackage(list: LinkedList<AppRestoreResult>, backup: RestorableBackup) {
        val latestResult = list[0]
        if (restoreCoordinator.isFailedPackage(latestResult.packageName)) {
            list[0] = latestResult.copy(state = getFailedStatus(latestResult.packageName, backup))
        } else {
            list[0] = latestResult.copy(state = SUCCEEDED)
        }
    }

    @WorkerThread
    private fun getFailedStatus(packageName: String, backup: RestorableBackup): AppBackupState {
        val metadata = backup.packageMetadataMap[packageName] ?: return FAILED
        return when (metadata.state) {
            PackageState.NO_DATA -> FAILED_NO_DATA
            PackageState.WAS_STOPPED -> NOT_YET_BACKED_UP
            PackageState.NOT_ALLOWED -> FAILED_NOT_ALLOWED
            PackageState.QUOTA_EXCEEDED -> FAILED_QUOTA_EXCEEDED
            PackageState.UNKNOWN_ERROR -> FAILED
            PackageState.APK_AND_DATA -> if (context.packageManager.isInstalled(packageName)) {
                FAILED
            } else FAILED_NOT_INSTALLED
        }
    }

    @WorkerThread
    private fun onRestoreComplete(result: RestoreBackupResult, backup: RestorableBackup) {
        // update status of latest package
        val list = mRestoreProgress.value!!
        updateLatestPackage(list, backup)

        // add missing packages as failed
        val seenPackages = list.map { it.packageName }.toSet()
        val expectedPackages =
            backup.packageMetadataMap.packagesSortedByNameDescending.toMutableSet()
        expectedPackages.removeAll(seenPackages)
        for (packageName in expectedPackages) {
            val failedStatus = getFailedStatus(packageName, backup)
            if (failedStatus == FAILED_NO_DATA &&
                backup.packageMetadataMap[packageName]?.isInternalSystem == true
            ) {
                // don't add internal system apps that had NO_DATA to backup
            } else {
                val name = getAppName(
                    context = context,
                    packageName = packageName,
                    fallback = backup.packageMetadataMap[packageName]?.name?.toString()
                        ?: packageName,
                )
                val appResult = AppRestoreResult(packageName, name.toString(), failedStatus)
                list.addFirst(appResult)
            }
        }
        mRestoreProgress.postValue(list)

        mRestoreBackupResult.postValue(result)

        context.stopService(foregroundServiceIntent)
    }

    fun closeSession() {
        session?.endRestoreSession()
        session = null
    }

    @WorkerThread
    private inner class RestoreObserver(
        private val restoreCoordinator: RestoreCoordinator,
        private val restorableBackup: RestorableBackup,
        private val session: IRestoreSession,
        private val packages: List<String>,
        private val monitor: BackupMonitor,
    ) : IRestoreObserver.Stub() {

        /**
         * The current package index.
         *
         * Used for splitting the packages into chunks.
         */
        private var packageIndex: Int = 0

        /**
         * Map of results for each chunk.
         *
         * The key is the chunk index, the value is the result.
         */
        private val chunkResults = mutableMapOf<Int, Int>()

        /**
         * Supply a list of the restore datasets available from the current transport.
         * This method is invoked as a callback following the application's use of the
         * [IRestoreSession.getAvailableRestoreSets] method.
         *
         * @param restoreSets An array of [RestoreSet] objects
         *   describing all of the available datasets that are candidates for restoring to
         *   the current device. If no applicable datasets exist, restoreSets will be null.
         */
        override fun restoreSetsAvailable(restoreSets: Array<out RestoreSet>?) {
            // this gets executed after we got the restore sets
            // now we can start the restore of all available packages
            restoreNextPackages()
        }

        /**
         * Restore the next chunk of packages.
         *
         * We need to restore in chunks, otherwise [BackupTransport.startRestore] in the
         * framework's [PerformUnifiedRestoreTask] may fail due to an oversize Binder
         * transaction, causing the entire restoration to fail.
         */
        private fun restoreNextPackages() {
            // Make sure metadata for selected backup is cached before starting each chunk.
            val backupMetadata = restorableBackup.backupMetadata
            restoreCoordinator.beforeStartRestore(backupMetadata)

            val nextChunkIndex = (packageIndex + PACKAGES_PER_CHUNK).coerceAtMost(packages.size)
            val packageChunk = packages.subList(packageIndex, nextChunkIndex).toTypedArray()
            packageIndex += packageChunk.size

            val token = backupMetadata.token
            val result = session.restorePackages(token, this, packageChunk, monitor)

            @Suppress("UNRESOLVED_REFERENCE") // BackupManager.SUCCESS
            if (result != BackupManager.SUCCESS) {
                Log.e(TAG, "restorePackages() returned non-zero value: $result")
            }
        }

        /**
         * The restore operation has begun.
         *
         * @param numPackages The total number of packages
         * being processed in this restore operation.
         */
        override fun restoreStarting(numPackages: Int) {
            // noop
        }

        /**
         * An indication of which package is being restored currently,
         * out of the total number provided in the [restoreStarting] callback.
         * This method is not guaranteed to be called.
         *
         * @param nowBeingRestored The index, between 1 and the numPackages parameter
         *   to the [restoreStarting] callback, of the package now being restored.
         * @param currentPackage The name of the package now being restored.
         */
        override fun onUpdate(nowBeingRestored: Int, currentPackage: String) {
            // nowBeingRestored reporting is buggy, so don't use it
            onRestoreStarted(currentPackage, restorableBackup)
        }

        /**
         * The restore operation has completed.
         *
         * @param result Zero on success; a nonzero error code if the restore operation
         *   as a whole failed.
         */
        override fun restoreFinished(result: Int) {
            val chunkIndex = packageIndex / PACKAGES_PER_CHUNK
            chunkResults[chunkIndex] = result

            // Restore next chunk if successful and there are more packages to restore.
            if (packageIndex < packages.size) {
                restoreNextPackages()
                return
            }

            // Restore finished, time to get the result.
            onRestoreComplete(getRestoreResult(), restorableBackup)
            closeSession()
        }

        private fun getRestoreResult(): RestoreBackupResult {
            @Suppress("UNRESOLVED_REFERENCE") // BackupManager.SUCCESS
            val failedChunks = chunkResults
                .filter { it.value != BackupManager.SUCCESS }
                .map { "chunk ${it.key} failed with error ${it.value}" }

            return if (failedChunks.isNotEmpty()) {
                Log.e(TAG, "Restore failed: $failedChunks")

                return RestoreBackupResult(
                    errorMsg = context.getString(R.string.restore_finished_error)
                )
            } else {
                RestoreBackupResult(errorMsg = null)
            }
        }
    }

    private val PackageMetadataMap.packagesSortedByNameDescending: List<String>
        get() {
            return asIterable().sortedByDescending { (packageName, metadata) ->
                // sort packages (reverse) alphabetically, since we move from bottom to top
                (metadata.name?.toString() ?: packageName).lowercase(Locale.getDefault())
            }.mapNotNull {
                // don't try to restore this helper package, as it doesn't really exist
                if (it.key == NO_DATA_END_SENTINEL) null else it.key
            }
        }
}
