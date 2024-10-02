/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore

import android.app.Application
import android.app.backup.IBackupManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.annotation.UiThread
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.repo.AppBackupManager
import com.stevesoltys.seedvault.restore.DisplayFragment.RECYCLE_BACKUP
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_APPS
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_BACKUP
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_FILES
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_FILES_STARTED
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_SELECT_FILES
import com.stevesoltys.seedvault.restore.DisplayFragment.SELECT_APPS
import com.stevesoltys.seedvault.restore.install.ApkRestore
import com.stevesoltys.seedvault.restore.install.InstallIntentCreator
import com.stevesoltys.seedvault.restore.install.InstallResult
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.storage.StorageRestoreService
import com.stevesoltys.seedvault.transport.restore.RestorableBackup
import com.stevesoltys.seedvault.transport.restore.RestorableBackupResult.ErrorResult
import com.stevesoltys.seedvault.transport.restore.RestorableBackupResult.SuccessResult
import com.stevesoltys.seedvault.transport.restore.RestoreCoordinator
import com.stevesoltys.seedvault.ui.LiveEvent
import com.stevesoltys.seedvault.ui.MutableLiveEvent
import com.stevesoltys.seedvault.ui.PACKAGE_NAME_SYSTEM
import com.stevesoltys.seedvault.ui.RequireProvisioningViewModel
import com.stevesoltys.seedvault.ui.systemData
import com.stevesoltys.seedvault.worker.IconManager
import com.stevesoltys.seedvault.worker.NUM_PACKAGES_PER_TRANSACTION
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.calyxos.backup.storage.api.SnapshotItem
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.api.StoredSnapshot
import org.calyxos.backup.storage.restore.RestoreService.Companion.EXTRA_TIMESTAMP_START
import org.calyxos.backup.storage.restore.RestoreService.Companion.EXTRA_USER_ID
import org.calyxos.backup.storage.ui.restore.FileSelectionManager
import org.calyxos.backup.storage.ui.restore.SnapshotViewModel
import java.util.LinkedList

private val TAG = RestoreViewModel::class.java.simpleName

internal const val PACKAGES_PER_CHUNK = NUM_PACKAGES_PER_TRANSACTION

internal class RestoreViewModel(
    app: Application,
    settingsManager: SettingsManager,
    keyManager: KeyManager,
    backupManager: IBackupManager,
    private val restoreCoordinator: RestoreCoordinator,
    private val appBackupManager: AppBackupManager,
    private val apkRestore: ApkRestore,
    private val iconManager: IconManager,
    storageBackup: StorageBackup,
    backendManager: BackendManager,
    override val fileSelectionManager: FileSelectionManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RequireProvisioningViewModel(app, settingsManager, keyManager, backendManager),
    RestorableBackupClickListener, SnapshotViewModel {

    override val isRestoreOperation = true
    var isSetupWizard = false

    private val appSelectionManager =
        AppSelectionManager(app, backendManager, iconManager, viewModelScope)
    private val appDataRestoreManager = AppDataRestoreManager(
        app, backupManager, restoreCoordinator, backendManager
    )

    private val mDisplayFragment = MutableLiveEvent<DisplayFragment>()
    internal val displayFragment: LiveEvent<DisplayFragment> = mDisplayFragment

    private val mRestoreSetResults = MutableLiveData<RestoreSetResult>()
    internal val restoreSetResults: LiveData<RestoreSetResult> get() = mRestoreSetResults

    private val mChosenRestorableBackup = MutableLiveData<RestorableBackup>()
    internal val chosenRestorableBackup: LiveData<RestorableBackup> get() = mChosenRestorableBackup

    internal val selectedApps: LiveData<SelectedAppsState> =
        appSelectionManager.selectedAppsLiveData

    internal val installResult: LiveData<InstallResult> = apkRestore.installResult.asLiveData()

    internal val installIntentCreator by lazy { InstallIntentCreator(app.packageManager) }

    internal val restoreProgress: LiveData<LinkedList<AppRestoreResult>>
        get() = appDataRestoreManager.restoreProgress

    internal val restoreBackupResult: LiveData<RestoreBackupResult>
        get() = appDataRestoreManager.restoreBackupResult

    override val snapshots = storageBackup.getBackupSnapshots().asLiveData(ioDispatcher)
    private var storedSnapshot: StoredSnapshot? = null

    internal fun loadRestoreSets() = viewModelScope.launch(ioDispatcher) {
        val result = when (val backups = restoreCoordinator.getAvailableBackups()) {
            is ErrorResult -> RestoreSetResult(
                app.getString(R.string.restore_set_error) + "\n\n${backups.e}"
            )
            is SuccessResult -> RestoreSetResult(backups.backups)
        }
        mRestoreSetResults.postValue(result)
    }

    override fun onRestorableBackupClicked(restorableBackup: RestorableBackup) {
        mChosenRestorableBackup.value = restorableBackup
        appSelectionManager.onRestoreSetChosen(restorableBackup, isSetupWizard)
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

    suspend fun loadIcon(packageName: String, callback: (Drawable) -> Unit) {
        iconManager.loadIcon(packageName, callback)
    }

    fun onCheckAllAppsClicked() = appSelectionManager.onCheckAllAppsClicked()
    fun onAppSelected(item: SelectableAppItem) = appSelectionManager.onAppSelected(item)

    internal fun onNextClickedAfterSelectingApps() {
        val backup = chosenRestorableBackup.value ?: error("No chosen backup")
        // replace original chosen backup with unselected packages removed
        val filteredBackup = appSelectionManager.onAppSelectionFinished(backup)
        mChosenRestorableBackup.value = filteredBackup
        viewModelScope.launch(ioDispatcher) {
            apkRestore.restore(filteredBackup)
        }
        // tell UI to move to InstallFragment
        mDisplayFragment.setEvent(RESTORE_APPS)
    }

    fun reCheckFailedPackage(packageName: String) = apkRestore.reCheckFailedPackage(packageName)

    internal fun onNextClickedAfterInstallingApps() {
        mDisplayFragment.postEvent(RESTORE_BACKUP)

        viewModelScope.launch(ioDispatcher) {
            val backup = chosenRestorableBackup.value ?: error("No Backup chosen")
            appDataRestoreManager.startRestore(backup)
        }
    }

    override fun onCleared() {
        super.onCleared()
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(ioDispatcher) { iconManager.removeIcons() }
    }

    @UiThread
    internal fun onFinishClickedAfterRestoringAppData() {
        val backup = chosenRestorableBackup.value
        if (appBackupManager.canRecycleBackupRepo(backup?.repoId, backup?.version)) {
            mDisplayFragment.setEvent(RECYCLE_BACKUP)
        } else {
            mDisplayFragment.setEvent(RESTORE_FILES)
        }
    }

    @UiThread
    internal fun onRecycleBackupFinished(shouldRecycle: Boolean) {
        val repoId = chosenRestorableBackup.value?.repoId
        if (shouldRecycle && repoId != null) viewModelScope.launch(ioDispatcher) {
            try {
                appBackupManager.recycleBackupRepo(repoId)
            } catch (e: Exception) {
                Log.e(TAG, "Error transferring backup repo: ", e)
            }
        }
        mDisplayFragment.setEvent(RESTORE_FILES)
    }

    @UiThread
    internal fun selectFilesForRestore(item: SnapshotItem) {
        val snapshot = item.snapshot ?: error("${item.storedSnapshot} had null snapshot")
        fileSelectionManager.onSnapshotChosen(snapshot)
        storedSnapshot = item.storedSnapshot
        mDisplayFragment.setEvent(RESTORE_SELECT_FILES)
    }

    @UiThread
    internal fun startFilesRestore() {
        val storedSnapshot = this.storedSnapshot ?: error("No snapshot stored")
        val i = Intent(app, StorageRestoreService::class.java)
        i.putExtra(EXTRA_USER_ID, storedSnapshot.userId)
        i.putExtra(EXTRA_TIMESTAMP_START, storedSnapshot.timestamp)
        app.startForegroundService(i)
        mDisplayFragment.setEvent(RESTORE_FILES_STARTED)
        this.storedSnapshot = null
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
    SELECT_APPS,
    RESTORE_APPS,
    RESTORE_BACKUP,
    RECYCLE_BACKUP,
    RESTORE_FILES,
    RESTORE_SELECT_FILES,
    RESTORE_FILES_STARTED,
}
