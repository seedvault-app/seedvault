package com.stevesoltys.seedvault.restore

import android.app.Application
import android.app.backup.IBackupManager
import android.app.backup.IRestoreObserver
import android.app.backup.IRestoreSession
import android.app.backup.RestoreSet
import android.content.Intent
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations.switchMap
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.stevesoltys.seedvault.BackupMonitor
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.metadata.BackupMetadata
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
import com.stevesoltys.seedvault.metadata.PackageState.NOT_ALLOWED
import com.stevesoltys.seedvault.metadata.PackageState.NO_DATA
import com.stevesoltys.seedvault.metadata.PackageState.QUOTA_EXCEEDED
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.metadata.PackageState.WAS_STOPPED
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_APPS
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_BACKUP
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_FILES
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_FILES_STARTED
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
import com.stevesoltys.seedvault.ui.RequireProvisioningViewModel
import com.stevesoltys.seedvault.ui.notification.getAppName
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private val TAG = RestoreViewModel::class.java.simpleName

internal class RestoreViewModel(
    app: Application,
    settingsManager: SettingsManager,
    keyManager: KeyManager,
    private val backupManager: IBackupManager,
    private val restoreCoordinator: RestoreCoordinator,
    private val apkRestore: ApkRestore,
    storageBackup: StorageBackup,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : RequireProvisioningViewModel(app, settingsManager, keyManager),
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

    internal val installResult: LiveData<InstallResult> =
        switchMap(mChosenRestorableBackup) { backup ->
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
        val session = this.session
            ?: backupManager.beginRestoreSessionForUser(UserHandle.myUserId(), null, TRANSPORT_ID)
            ?: throw RemoteException("beginRestoreSessionForUser returned null")
        this.session = session
        return session
    }

    internal fun loadRestoreSets() = viewModelScope.launch {
        mRestoreSetResults.value = getAvailableRestoreSets()
    }

    private suspend fun getAvailableRestoreSets() =
        suspendCoroutine<RestoreSetResult> { continuation ->
            val session = try {
                getOrStartSession()
            } catch (e: RemoteException) {
                Log.e(TAG, "Error starting new session", e)
                continuation.resume(RestoreSetResult(app.getString(R.string.restore_set_error)))
                return@suspendCoroutine
            }

            val observer = RestoreObserver(continuation)
            val setResult = session.getAvailableRestoreSets(observer, monitor)
            if (setResult != 0) {
                Log.e(TAG, "getAvailableRestoreSets() returned non-zero value")
                continuation.resume(RestoreSetResult(app.getString(R.string.restore_set_error)))
                return@suspendCoroutine
            }
        }

    override fun onRestorableBackupClicked(restorableBackup: RestorableBackup) {
        mChosenRestorableBackup.value = restorableBackup
        mDisplayFragment.setEvent(RESTORE_APPS)

        // re-installing apps will take some time and the session probably times out
        // so better close it cleanly and re-open it later
        closeSession()
    }

    private fun getInstallResult(backup: RestorableBackup): LiveData<InstallResult> {
        @Suppress("EXPERIMENTAL_API_USAGE")
        return apkRestore.restore(backup.token, backup.deviceName, backup.packageMetadataMap)
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
        val token = mChosenRestorableBackup.value?.token ?: throw AssertionError()
        viewModelScope.launch(ioDispatcher) {
            startRestore(token)
        }
    }

    @WorkerThread
    private suspend fun startRestore(token: Long) {
        Log.d(TAG, "Starting new restore session to restore backup $token")

        // if we had no token before (i.e. restore from setup wizard),
        // use the token of the current restore set from now on
        if (settingsManager.getToken() == null) {
            settingsManager.setNewToken(token)
        }

        // we need to start a new session and retrieve the restore sets before starting the restore
        val restoreSetResult = getAvailableRestoreSets()
        if (restoreSetResult.hasError()) {
            mRestoreBackupResult.postValue(
                RestoreBackupResult(app.getString(R.string.restore_finished_error))
            )
            return
        }

        // now we can start the restore of all available packages
        val observer = RestoreObserver()
        val restoreAllResult = session?.restoreAll(token, observer, monitor) ?: 1
        if (restoreAllResult != 0) {
            if (session == null) Log.e(TAG, "session was null")
            else Log.e(TAG, "restoreAll() returned non-zero value")
            mRestoreBackupResult.postValue(
                RestoreBackupResult(app.getString(R.string.restore_finished_error))
            )
            return
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
        restorableBackup: RestorableBackup = chosenRestorableBackup.value!!
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
        private val continuation: Continuation<RestoreSetResult>? = null
    ) : IRestoreObserver.Stub() {

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
            check(continuation != null) { "Getting restore sets without continuation" }

            val result = if (restoreSets == null || restoreSets.isEmpty()) {
                RestoreSetResult(app.getString(R.string.restore_set_empty_result))
            } else {
                val backupMetadata = restoreCoordinator.getAndClearBackupMetadata()
                if (backupMetadata == null) {
                    Log.e(TAG, "RestoreCoordinator#getAndClearBackupMetadata() returned null")
                    RestoreSetResult(app.getString(R.string.restore_set_error))
                } else {
                    val restorableBackups = restoreSets.mapNotNull { set ->
                        getRestorableBackup(set, backupMetadata[set.token])
                    }
                    if (restorableBackups.isEmpty()) {
                        RestoreSetResult(app.getString(R.string.restore_set_empty_result))
                    } else RestoreSetResult(restorableBackups)
                }
            }
            continuation.resume(result)
        }

        private fun getRestorableBackup(set: RestoreSet, metadata: BackupMetadata?) = when {
            metadata == null -> {
                Log.e(TAG, "No metadata for token ${set.token}.")
                null
            }
            metadata.time == 0L -> {
                Log.d(TAG, "Ignoring RestoreSet with no last backup time: ${set.token}.")
                null
            }
            else -> {
                RestorableBackup(set, metadata)
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
            val restoreResult = RestoreBackupResult(
                if (result == 0) null
                else app.getString(R.string.restore_finished_error)
            )
            onRestoreComplete(restoreResult)
            closeSession()
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
    internal val errorMsg: String?
) {

    internal constructor(restorableBackups: List<RestorableBackup>) : this(restorableBackups, null)

    internal constructor(errorMsg: String) : this(emptyList(), errorMsg)

    internal fun hasError(): Boolean = errorMsg != null
}

internal class RestoreBackupResult(val errorMsg: String? = null) {
    internal fun hasError(): Boolean = errorMsg != null
}

internal enum class DisplayFragment {
    RESTORE_APPS, RESTORE_BACKUP, RESTORE_FILES, RESTORE_FILES_STARTED
}
