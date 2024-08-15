/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import de.grobox.storagebackuptester.backup.BackupProgress
import de.grobox.storagebackuptester.backup.BackupStats
import de.grobox.storagebackuptester.restore.RestoreProgress
import de.grobox.storagebackuptester.restore.RestoreStats
import de.grobox.storagebackuptester.scanner.scanTree
import de.grobox.storagebackuptester.scanner.scanUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.calyxos.backup.storage.api.SnapshotItem
import org.calyxos.backup.storage.api.SnapshotResult
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.api.StoredSnapshot
import org.calyxos.backup.storage.backup.BackupJobService
import org.calyxos.backup.storage.scanner.DocumentScanner
import org.calyxos.backup.storage.scanner.MediaScanner
import org.calyxos.backup.storage.ui.backup.BackupContentViewModel
import org.calyxos.backup.storage.ui.restore.SnapshotViewModel

private val logEmptyState = """
    Press the button below to simulate a backup. Your files won't be changed and not uploaded anywhere. This is just to test code for a future real backup.

    Please come back to this app from time to time and run a backup again to see if it correctly identifies files that were added/changed.

    Note that after updating this app, it might need to re-backup all files again.

    Thanks for testing!
""".trimIndent()
private const val TAG = "MainViewModel"

class MainViewModel(application: Application) : BackupContentViewModel(application),
    SnapshotViewModel {

    private val app: App = application as App
    private val settingsManager = app.settingsManager
    override val storageBackup: StorageBackup = app.storageBackup
    override val fileSelectionManager = app.fileSelectionManager

    private val _backupLog = MutableLiveData(BackupProgress(0, 0, logEmptyState))
    val backupLog: LiveData<BackupProgress> = _backupLog

    private val _buttonEnabled = MutableLiveData<Boolean>()
    val backupButtonEnabled: LiveData<Boolean> = _buttonEnabled

    private val _restoreLog = MutableLiveData<RestoreProgress>()
    val restoreLog: LiveData<RestoreProgress> = _restoreLog

    private val _restoreProgressVisible = MutableLiveData<Boolean>()
    val restoreProgressVisible: LiveData<Boolean> = _restoreProgressVisible

    override val snapshots: LiveData<SnapshotResult>
        get() = storageBackup.getBackupSnapshots().asLiveData(Dispatchers.IO)
    private var storedSnapshot: StoredSnapshot? = null

    init {
        viewModelScope.launch { loadContent() }
    }

    fun simulateBackup() {
        _buttonEnabled.value = false
        val backupObserver = BackupStats(app, storageBackup, _backupLog)
        viewModelScope.launch(Dispatchers.IO) {
            val text = storageBackup.getUriSummaryString()
            _backupLog.postValue(BackupProgress(0, 0, "Scanning: $text\n"))
            // FIXME: This might get killed if we navigate away from the activity.
            //  A foreground service would avoid that.
            if (storageBackup.runBackup(backupObserver)) {
                // only prune old backups when backup run was successful
                storageBackup.pruneOldBackups(backupObserver)
            }
            _buttonEnabled.postValue(true)
        }
    }

    suspend fun scanMediaUri(uri: Uri): String = withContext(Dispatchers.Default) {
        scanUri(app, MediaScanner(app), uri)
    }

    suspend fun scanDocumentUri(uri: Uri): String = withContext(Dispatchers.Default) {
        scanTree(app, DocumentScanner(app), uri)
    }

    fun clearDb() {
        viewModelScope.launch(Dispatchers.IO) { storageBackup.clearCache() }
    }

    fun setBackupLocation(uri: Uri?) {
        if (uri != null) {
            viewModelScope.launch(Dispatchers.IO) {
                storageBackup.init()
            }
        }
        settingsManager.setBackupLocation(uri)
    }

    fun hasBackupLocation(): Boolean {
        return settingsManager.getBackupLocation() != null
    }

    fun setAutomaticBackupsEnabled(enabled: Boolean) {
        if (enabled) DemoBackupJobService.scheduleJob(app)
        else BackupJobService.cancelJob(app)
        settingsManager.setAutomaticBackupsEnabled(enabled)
    }

    fun areAutomaticBackupsEnabled(): Boolean {
        val enabled = settingsManager.areAutomaticBackupsEnabled()
        if (enabled && !BackupJobService.isScheduled(app)) {
            Log.w(TAG, "Automatic backups enabled, but job not scheduled. Scheduling...")
            DemoBackupJobService.scheduleJob(app)
        }
        return enabled
    }

    fun onSnapshotClicked(item: SnapshotItem) {
        val snapshot = item.snapshot ?: error("${item.storedSnapshot} had null snapshot")
        fileSelectionManager.onSnapshotChosen(snapshot)
        storedSnapshot = item.storedSnapshot
    }

    fun onFilesSelected() {
        val storedSnapshot = this.storedSnapshot ?: error("No snapshot stored")
        val snapshot = fileSelectionManager.getBackupSnapshotAndReset()

        // example for how to do restore via foreground service
//        app.startForegroundService(Intent(app, DemoRestoreService::class.java).apply {
//            putExtra(EXTRA_USER_ID, item.storedSnapshot.userId)
//            putExtra(EXTRA_TIMESTAMP_START, snapshot.timeStart)
//        })

        // example for how to do restore via fragment
        _restoreProgressVisible.value = true
        val restoreObserver = RestoreStats(app, _restoreLog)
        viewModelScope.launch {
            storageBackup.restoreBackupSnapshot(storedSnapshot, snapshot, restoreObserver)
            _restoreProgressVisible.value = false
            this@MainViewModel.storedSnapshot = null
        }
    }

}
