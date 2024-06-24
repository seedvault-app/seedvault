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
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_APPS
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_BACKUP
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_FILES
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_FILES_STARTED
import com.stevesoltys.seedvault.restore.DisplayFragment.SELECT_APPS
import com.stevesoltys.seedvault.restore.install.ApkRestore
import com.stevesoltys.seedvault.restore.install.InstallIntentCreator
import com.stevesoltys.seedvault.restore.install.InstallResult
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.storage.StorageRestoreService
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
import org.calyxos.backup.storage.restore.RestoreService.Companion.EXTRA_TIMESTAMP_START
import org.calyxos.backup.storage.restore.RestoreService.Companion.EXTRA_USER_ID
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
    private val apkRestore: ApkRestore,
    private val iconManager: IconManager,
    storageBackup: StorageBackup,
    pluginManager: StoragePluginManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RequireProvisioningViewModel(app, settingsManager, keyManager, pluginManager),
    RestorableBackupClickListener, SnapshotViewModel {

    override val isRestoreOperation = true

    private val appSelectionManager =
        AppSelectionManager(app, pluginManager, iconManager, viewModelScope)
    private val appDataRestoreManager = AppDataRestoreManager(
        app, backupManager, settingsManager, restoreCoordinator, pluginManager
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
        appSelectionManager.onRestoreSetChosen(restorableBackup)
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
        appDataRestoreManager.closeSession()
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
