/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore

import android.app.Application
import android.app.backup.BackupManager
import android.app.backup.BackupTransport
import android.app.backup.IBackupManager
import android.app.backup.IRestoreObserver
import android.app.backup.IRestoreSession
import android.app.backup.RestoreSet
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.stevesoltys.seedvault.BackupMonitor
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
import com.stevesoltys.seedvault.metadata.PackageState.NOT_ALLOWED
import com.stevesoltys.seedvault.metadata.PackageState.NO_DATA
import com.stevesoltys.seedvault.metadata.PackageState.QUOTA_EXCEEDED
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.metadata.PackageState.WAS_STOPPED
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_APPS
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_BACKUP
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_FILES
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_FILES_STARTED
import com.stevesoltys.seedvault.restore.DisplayFragment.SELECT_APPS
import com.stevesoltys.seedvault.restore.install.ApkRestore
import com.stevesoltys.seedvault.restore.install.InstallIntentCreator
import com.stevesoltys.seedvault.restore.install.InstallResult
import com.stevesoltys.seedvault.restore.install.isInstalled
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.storage.StorageRestoreService
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
import com.stevesoltys.seedvault.ui.LiveEvent
import com.stevesoltys.seedvault.ui.MutableLiveEvent
import com.stevesoltys.seedvault.ui.PACKAGE_NAME_SYSTEM
import com.stevesoltys.seedvault.ui.RequireProvisioningViewModel
import com.stevesoltys.seedvault.ui.notification.getAppName
import com.stevesoltys.seedvault.ui.systemData
import com.stevesoltys.seedvault.worker.FILE_BACKUP_ICONS
import com.stevesoltys.seedvault.worker.IconManager
import com.stevesoltys.seedvault.worker.NUM_PACKAGES_PER_TRANSACTION
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.calyxos.backup.storage.api.SnapshotItem
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.restore.RestoreService.Companion.EXTRA_TIMESTAMP_START
import org.calyxos.backup.storage.restore.RestoreService.Companion.EXTRA_USER_ID
import org.calyxos.backup.storage.ui.restore.SnapshotViewModel
import java.util.LinkedList
import java.util.Locale

private val TAG = RestoreViewModel::class.java.simpleName

internal const val PACKAGES_PER_CHUNK = NUM_PACKAGES_PER_TRANSACTION

internal class SelectedAppsState(
    val apps: List<SelectableAppItem>,
    val allSelected: Boolean,
    val iconsLoaded: Boolean,
)

internal class RestoreViewModel(
    app: Application,
    settingsManager: SettingsManager,
    keyManager: KeyManager,
    private val backupManager: IBackupManager,
    private val restoreCoordinator: RestoreCoordinator,
    private val apkRestore: ApkRestore,
    private val iconManager: IconManager,
    storageBackup: StorageBackup,
    pluginManager: StoragePluginManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RequireProvisioningViewModel(app, settingsManager, keyManager, pluginManager),
    RestorableBackupClickListener, SnapshotViewModel {

    override val isRestoreOperation = true

    private var session: IRestoreSession? = null
    private val monitor = BackupMonitor()

    private val mDisplayFragment = MutableLiveEvent<DisplayFragment>()
    internal val displayFragment: LiveEvent<DisplayFragment> = mDisplayFragment

    private val mRestoreSetResults = MutableLiveData<RestoreSetResult>()
    internal val restoreSetResults: LiveData<RestoreSetResult> get() = mRestoreSetResults

    private val mChosenRestorableBackup = MutableLiveData<RestorableBackup>()
    internal val chosenRestorableBackup: LiveData<RestorableBackup> get() = mChosenRestorableBackup

    private val mSelectedApps = MutableLiveData<SelectedAppsState>()
    internal val selectedApps: LiveData<SelectedAppsState> get() = mSelectedApps

    internal val installResult: LiveData<InstallResult> =
        mChosenRestorableBackup.switchMap { backup ->
            // TODO does this stay stable when re-observing this LiveData?
            // TODO pass in app selection done by user
            getInstallResult(backup)
        }
    internal val installIntentCreator by lazy { InstallIntentCreator(app.packageManager) }

    private val mNextButtonEnabled = MutableLiveData<Boolean>().apply { value = false }
    internal val nextButtonEnabled: LiveData<Boolean> = mNextButtonEnabled

    private val mRestoreProgress = MutableLiveData<LinkedList<AppRestoreResult>>().apply {
        value = LinkedList<AppRestoreResult>().apply {
            add(
                AppRestoreResult(
                    packageName = MAGIC_PACKAGE_MANAGER,
                    name = getAppName(app, MAGIC_PACKAGE_MANAGER),
                    state = IN_PROGRESS
                )
            )
        }
    }
    internal val restoreProgress: LiveData<LinkedList<AppRestoreResult>> get() = mRestoreProgress

    private val mRestoreBackupResult = MutableLiveData<RestoreBackupResult>()
    internal val restoreBackupResult: LiveData<RestoreBackupResult> get() = mRestoreBackupResult

    override val snapshots = storageBackup.getBackupSnapshots().asLiveData(ioDispatcher)

    @Throws(RemoteException::class)
    private fun getOrStartSession(): IRestoreSession {
        @Suppress("UNRESOLVED_REFERENCE")
        val session = this.session
            ?: backupManager.beginRestoreSessionForUser(UserHandle.myUserId(), null, TRANSPORT_ID)
            ?: throw RemoteException("beginRestoreSessionForUser returned null")
        this.session = session
        return session
    }

    internal fun loadRestoreSets() = viewModelScope.launch(ioDispatcher) {
        val backups = restoreCoordinator.getAvailableMetadata()?.mapNotNull { (token, metadata) ->
            when (metadata.time) {
                0L -> {
                    Log.d(TAG, "Ignoring RestoreSet with no last backup time: $token.")
                    null
                }

                else -> RestorableBackup(metadata)
            }
        }
        val result = when {
            backups == null -> RestoreSetResult(app.getString(R.string.restore_set_error))
            backups.isEmpty() -> RestoreSetResult(app.getString(R.string.restore_set_empty_result))
            else -> RestoreSetResult(backups)
        }
        mRestoreSetResults.postValue(result)
    }

    override fun onRestorableBackupClicked(restorableBackup: RestorableBackup) {
        mChosenRestorableBackup.value = restorableBackup
        // filter and sort app items for display
        val items = restorableBackup.packageMetadataMap.mapNotNull { (packageName, metadata) ->
            if (metadata.time == 0L && !metadata.hasApk()) null
            else if (metadata.isInternalSystem) null
            else SelectableAppItem(packageName, metadata, true)
        }.sortedBy {
            it.name.lowercase(Locale.getDefault())
        }.toMutableList()
        val systemDataItems = systemData.mapNotNull { (packageName, data) ->
            val metadata = restorableBackup.packageMetadataMap[packageName]
                ?: return@mapNotNull null
            if (metadata.time == 0L && !metadata.hasApk()) return@mapNotNull null
            val name = app.getString(data.nameRes)
            SelectableAppItem(packageName, metadata.copy(name = name), true)
        }
        val systemItem = SelectableAppItem(
            packageName = PACKAGE_NAME_SYSTEM,
            metadata = PackageMetadata(
                time = restorableBackup.packageMetadataMap.values.maxOf {
                    if (it.system) it.time else -1
                },
                system = true,
                name = app.getString(R.string.backup_system_apps),
            ),
            selected = true,
        )
        items.add(0, systemItem)
        items.addAll(0, systemDataItems)
        mSelectedApps.value =
            SelectedAppsState(apps = items, allSelected = true, iconsLoaded = false)
        // download icons
        viewModelScope.launch(Dispatchers.IO) {
            val plugin = pluginManager.appPlugin
            val token = restorableBackup.token
            val packagesWithIcons = try {
                plugin.getInputStream(token, FILE_BACKUP_ICONS).use {
                    iconManager.downloadIcons(restorableBackup.version, token, it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading icons:", e)
                emptySet()
            } + systemData.keys + setOf(PACKAGE_NAME_SYSTEM)
            // update state, so it knows that icons have loaded
            val updatedItems = items.map { item ->
                item.copy(hasIcon = item.packageName in packagesWithIcons)
            }
            val newState =
                SelectedAppsState(updatedItems, allSelected = true, iconsLoaded = true)
            mSelectedApps.postValue(newState)
        }
        mDisplayFragment.setEvent(SELECT_APPS)
    }

    suspend fun loadIcon(item: SelectableAppItem, callback: (Drawable) -> Unit) {
        if (item.packageName == PACKAGE_NAME_SYSTEM) {
            val drawable = getDrawable(app, R.drawable.ic_app_settings)!!
            callback(drawable)
        } else if (item.metadata.isInternalSystem && item.packageName in systemData.keys) {
            val drawable = getDrawable(app, systemData[item.packageName]!!.iconRes)!!
            callback(drawable)
        } else {
            iconManager.loadIcon(item.packageName, callback)
        }
    }

    fun onCheckAllAppsClicked() {
        val apps = selectedApps.value?.apps ?: return
        val allSelected = apps.all { it.selected }
        if (allSelected) {
            // unselect all
            val newApps = apps.map { if (it.selected) it.copy(selected = false) else it }
            mSelectedApps.value = SelectedAppsState(newApps, false, iconsLoaded = true)
        } else {
            // select all
            val newApps = apps.map { if (!it.selected) it.copy(selected = true) else it }
            mSelectedApps.value = SelectedAppsState(newApps, true, iconsLoaded = true)
        }
    }

    fun onAppSelected(item: SelectableAppItem) {
        val apps = selectedApps.value?.apps?.toMutableList() ?: return
        val iterator = apps.listIterator()
        var allSelected = true
        while (iterator.hasNext()) {
            val app = iterator.next()
            if (app.packageName == item.packageName) {
                iterator.set(item.copy(selected = !item.selected))
                allSelected = allSelected && !item.selected
            } else {
                allSelected = allSelected && app.selected
            }
        }
        mSelectedApps.value = SelectedAppsState(apps, allSelected, iconsLoaded = true)
    }

    internal fun onNextClickedAfterSelectingApps() {
        val backup = chosenRestorableBackup.value ?: error("No chosen backup")
        // map packages names to selection state
        val apps = selectedApps.value?.apps?.associate {
            Pair(it.packageName, it.selected)
        } ?: error("no selected apps")
        // filter out unselected packages
        // Attention: This code is complicated and hard to test, proceed with plenty of care!
        val restoreSystemApps = apps[PACKAGE_NAME_SYSTEM] != false
        val packages = backup.packageMetadataMap.filter { (packageName, metadata) ->
            val isSelected = apps[packageName]
            @Suppress("IfThenToElvis") // the code is more readable like this
            if (isSelected == null) { // was not in list
                // internal system apps were not in the list and are controlled by meta item
                if (metadata.isInternalSystem) restoreSystemApps // only if allowed by meta item
                else true // non-system packages that weren't found, won't get filtered
            } else { // was in list and either selected or not
                isSelected
            }
        } as PackageMetadataMap
        // replace original chosen backup with unselected packages removed
        mChosenRestorableBackup.value = backup.copy(
            backupMetadata = backup.backupMetadata.copy(packageMetadataMap = packages),
        )
        // tell UI to move to InstallFragment
        mDisplayFragment.setEvent(RESTORE_APPS)
    }

    private fun getInstallResult(backup: RestorableBackup): LiveData<InstallResult> {
        @Suppress("EXPERIMENTAL_API_USAGE")
        return apkRestore.restore(backup)
            .onStart {
                Log.d(TAG, "Start InstallResult Flow")
            }.catch { e ->
                Log.d(TAG, "Exception in InstallResult Flow", e)
            }.onCompletion { e ->
                Log.d(TAG, "Completed InstallResult Flow", e)
                mNextButtonEnabled.postValue(true)
            }
            .flowOn(ioDispatcher)
            // collect on the same thread, so concurrency issues don't mess up live data updates
            // e.g. InstallResult#isFinished isn't reported too early.
            .asLiveData(ioDispatcher)
    }

    internal fun onNextClickedAfterInstallingApps() {
        mDisplayFragment.postEvent(RESTORE_BACKUP)

        viewModelScope.launch(ioDispatcher) {
            startRestore()
        }
    }

    @WorkerThread
    private fun startRestore() {
        val token = mChosenRestorableBackup.value?.token
            ?: throw IllegalStateException("No chosen backup")

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
                RestoreBackupResult(app.getString(R.string.restore_set_error))
            )
            return
        }

        val restorableBackup = mChosenRestorableBackup.value
        val packages = restorableBackup?.packageMetadataMap?.keys?.toList()
            ?: run {
                Log.e(TAG, "Chosen backup has empty package metadata map")
                mRestoreBackupResult.postValue(
                    RestoreBackupResult(app.getString(R.string.restore_set_error))
                )
                return
            }

        val observer = RestoreObserver(
            restoreCoordinator = restoreCoordinator,
            restorableBackup = restorableBackup,
            session = session,
            packages = packages,
            monitor = monitor
        )

        // We need to retrieve the restore sets before starting the restore.
        // Otherwise, restorePackages() won't work as they need the restore sets cached internally.
        if (session.getAvailableRestoreSets(observer, monitor) != 0) {
            Log.e(TAG, "getAvailableRestoreSets() returned non-zero value")

            mRestoreBackupResult.postValue(
                RestoreBackupResult(app.getString(R.string.restore_set_error))
            )
        }
    }

    @WorkerThread
    // this should be called one package at a time and never concurrently for different packages
    private fun onRestoreStarted(packageName: String) {
        // list is never null and always has at least one package
        val list = mRestoreProgress.value!!

        // check previous package first and change status
        updateLatestPackage(list)

        // add current package
        list.addFirst(AppRestoreResult(packageName, getAppName(app, packageName), IN_PROGRESS))
        mRestoreProgress.postValue(list)
    }

    @WorkerThread
    private fun updateLatestPackage(list: LinkedList<AppRestoreResult>) {
        val latestResult = list[0]
        if (restoreCoordinator.isFailedPackage(latestResult.packageName)) {
            list[0] = latestResult.copy(state = getFailedStatus(latestResult.packageName))
        } else {
            list[0] = latestResult.copy(state = SUCCEEDED)
        }
    }

    @WorkerThread
    private fun getFailedStatus(
        packageName: String,
        restorableBackup: RestorableBackup = chosenRestorableBackup.value!!,
    ): AppBackupState {
        val metadata = restorableBackup.packageMetadataMap[packageName] ?: return FAILED
        return when (metadata.state) {
            NO_DATA -> FAILED_NO_DATA
            WAS_STOPPED -> NOT_YET_BACKED_UP
            NOT_ALLOWED -> FAILED_NOT_ALLOWED
            QUOTA_EXCEEDED -> FAILED_QUOTA_EXCEEDED
            UNKNOWN_ERROR -> FAILED
            APK_AND_DATA -> {
                if (app.packageManager.isInstalled(packageName)) FAILED else FAILED_NOT_INSTALLED
            }
        }
    }

    @WorkerThread
    private fun onRestoreComplete(result: RestoreBackupResult) {
        // update status of latest package
        val list = mRestoreProgress.value!!
        updateLatestPackage(list)

        // add missing packages as failed
        val seenPackages = list.map { it.packageName }
        val restorableBackup = chosenRestorableBackup.value!!
        val expectedPackages = restorableBackup.packageMetadataMap.keys
        expectedPackages.removeAll(seenPackages)
        for (packageName: String in expectedPackages) {
            // TODO don't add if it was a NO_DATA system app
            val failedStatus = getFailedStatus(packageName, restorableBackup)
            list.addFirst(AppRestoreResult(packageName, getAppName(app, packageName), failedStatus))
        }
        mRestoreProgress.postValue(list)

        mRestoreBackupResult.postValue(result)
    }

    override fun onCleared() {
        super.onCleared()
        closeSession()
    }

    private fun closeSession() {
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
            onRestoreStarted(currentPackage)
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
            onRestoreComplete(getRestoreResult())
            closeSession()
        }

        private fun getRestoreResult(): RestoreBackupResult {
            val failedChunks = chunkResults
                .filter { it.value != BackupManager.SUCCESS }
                .map { "chunk ${it.key} failed with error ${it.value}" }

            return if (failedChunks.isNotEmpty()) {
                Log.e(TAG, "Restore failed: $failedChunks")

                return RestoreBackupResult(
                    errorMsg = app.getString(R.string.restore_finished_error)
                )
            } else {
                RestoreBackupResult(errorMsg = null)
            }
        }
    }

    @UiThread
    internal fun onFinishClickedAfterRestoringAppData() {
        mDisplayFragment.setEvent(RESTORE_FILES)
    }

    @UiThread
    internal fun startFilesRestore(item: SnapshotItem) {
        val i = Intent(app, StorageRestoreService::class.java)
        i.putExtra(EXTRA_USER_ID, item.storedSnapshot.userId)
        i.putExtra(EXTRA_TIMESTAMP_START, item.time)
        app.startForegroundService(i)
        mDisplayFragment.setEvent(RESTORE_FILES_STARTED)
    }

}

internal class RestoreSetResult(
    internal val restorableBackups: List<RestorableBackup>,
    internal val errorMsg: String?,
) {

    internal constructor(restorableBackups: List<RestorableBackup>) : this(restorableBackups, null)

    internal constructor(errorMsg: String) : this(emptyList(), errorMsg)

    internal fun hasError(): Boolean = errorMsg != null
}

internal class RestoreBackupResult(val errorMsg: String? = null) {
    internal fun hasError(): Boolean = errorMsg != null
}

internal enum class DisplayFragment {
    SELECT_APPS, RESTORE_APPS, RESTORE_BACKUP, RESTORE_FILES, RESTORE_FILES_STARTED
}
