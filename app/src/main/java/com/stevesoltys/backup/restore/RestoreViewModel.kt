package com.stevesoltys.backup.restore

import android.app.Application
import android.app.backup.IRestoreObserver
import android.app.backup.IRestoreSession
import android.app.backup.RestoreSet
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.stevesoltys.backup.Backup
import com.stevesoltys.backup.R
import com.stevesoltys.backup.session.backup.BackupMonitor
import com.stevesoltys.backup.settings.setBackupFolderUri
import com.stevesoltys.backup.transport.ConfigurableBackupTransportService
import com.stevesoltys.backup.transport.TRANSPORT_ID
import com.stevesoltys.backup.transport.backup.plugins.DIRECTORY_ROOT
import com.stevesoltys.backup.transport.restore.plugins.DocumentsProviderRestorePlugin.Companion.getBackups
import com.stevesoltys.backup.ui.BackupViewModel
import com.stevesoltys.backup.ui.LocationResult

private val TAG = RestoreViewModel::class.java.simpleName

class RestoreViewModel(app: Application) : BackupViewModel(app), RestoreSetClickListener {

    private val backupManager = Backup.backupManager

    override val isRestoreOperation = true

    private var session: IRestoreSession? = null
    private var observer: RestoreObserver? = null
    private val monitor = BackupMonitor()

    private val mRestoreSets = MutableLiveData<RestoreSetResult>()
    internal val restoreSets: LiveData<RestoreSetResult> get() = mRestoreSets

    private val mChosenRestoreSet = MutableLiveData<RestoreSet>()
    internal val chosenRestoreSet: LiveData<RestoreSet> get() = mChosenRestoreSet

    private var mNumPackages = MutableLiveData<Int>()
    internal val numPackages: LiveData<Int> get() = mNumPackages

    private val mRestoreProgress = MutableLiveData<RestoreProgress>()
    internal val restoreProgress: LiveData<RestoreProgress> get() = mRestoreProgress

    private val mRestoreFinished = MutableLiveData<Int>()
    // Zero on success; a nonzero error code if the restore operation as a whole failed.
    internal val restoreFinished: LiveData<Int> get() = mRestoreFinished

    override fun onLocationSet(folderUri: Uri, isInitialSetup: Boolean) {
        if (hasBackup(folderUri)) {
            // store backup folder location in settings
            setBackupFolderUri(app, folderUri)

            // stop backup service to be sure the old location will get updated
            app.stopService(Intent(app, ConfigurableBackupTransportService::class.java))

            Log.d(TAG, "New storage location chosen: $folderUri")

            mLocationSet.setEvent(LocationResult(false, isInitialSetup))
        } else {
            Log.w(TAG, "Location was rejected: $folderUri")

            // notify the UI that the location was invalid
            mLocationSet.setEvent(LocationResult(false, isInitialSetup))
        }
    }

    /**
     * Searches if there's really a backup available in the given location.
     * Returns true if at least one was found and false otherwise.
     *
     * This method is not plugin-agnostic and breaks encapsulation.
     * It is specific to the (currently only) DocumentsProvider plugin.
     *
     * TODO maybe move this to the RestoreCoordinator once we can inject it
     */
    private fun hasBackup(folderUri: Uri): Boolean {
        val parent = DocumentFile.fromTreeUri(app, folderUri) ?: throw AssertionError()
        val rootDir = parent.findFile(DIRECTORY_ROOT) ?: return false
        val backupSets = getBackups(rootDir)
        return backupSets.isNotEmpty()
    }

    internal fun loadRestoreSets() {
        val session = this.session ?: backupManager.beginRestoreSession(null, TRANSPORT_ID)
        this.session = session

        if (session == null) {
            Log.e(TAG, "beginRestoreSession() returned null session")
            mRestoreSets.value = RestoreSetResult(app.getString(R.string.restore_set_error))
            return
        }
        val observer = this.observer ?: RestoreObserver()
        this.observer = observer

        val setResult = session.getAvailableRestoreSets(observer, monitor)
        if (setResult != 0) {
            Log.e(TAG, "getAvailableRestoreSets() returned non-zero value")
            mRestoreSets.value = RestoreSetResult(app.getString(R.string.restore_set_error))
            return
        }
    }

    override fun onRestoreSetClicked(set: RestoreSet) {
        val session = this.session
        check(session != null)
        session.restoreAll(set.token, observer, monitor)

        mChosenRestoreSet.value = set
    }

    override fun onCleared() {
        super.onCleared()
        endSession()
    }

    private fun endSession() {
        session?.endRestoreSession()
        session = null
        observer = null
    }

    @WorkerThread
    private inner class RestoreObserver : IRestoreObserver.Stub() {

        private var correctedNow: Int = -1

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
            if (restoreSets == null || restoreSets.isEmpty()) {
                mRestoreSets.postValue(RestoreSetResult(app.getString(R.string.restore_set_empty_result)))
            } else {
                mRestoreSets.postValue(RestoreSetResult(restoreSets))
            }
        }

        /**
         * The restore operation has begun.
         *
         * @param numPackages The total number of packages being processed in this restore operation.
         */
        override fun restoreStarting(numPackages: Int) {
            mNumPackages.postValue(numPackages)
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
            if (nowBeingRestored <= correctedNow) {
                correctedNow += 1
            } else {
                correctedNow = nowBeingRestored
            }
            mRestoreProgress.postValue(RestoreProgress(correctedNow, currentPackage))
        }

        /**
         * The restore operation has completed.
         *
         * @param result Zero on success; a nonzero error code if the restore operation
         *   as a whole failed.
         */
        override fun restoreFinished(result: Int) {
            mRestoreFinished.postValue(result)
            endSession()
        }

    }

}

internal class RestoreSetResult(
        internal val sets: Array<out RestoreSet>,
        internal val errorMsg: String?) {

    internal constructor(sets: Array<out RestoreSet>) : this(sets, null)

    internal constructor(errorMsg: String) : this(emptyArray(), errorMsg)

    internal fun hasError(): Boolean = errorMsg != null
}

internal class RestoreProgress(
        internal val nowBeingRestored: Int,
        internal val currentPackage: String)
