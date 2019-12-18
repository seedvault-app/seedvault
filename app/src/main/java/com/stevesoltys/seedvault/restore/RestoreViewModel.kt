package com.stevesoltys.seedvault.restore

import android.app.Application
import android.app.backup.IRestoreObserver
import android.app.backup.IRestoreSession
import android.app.backup.RestoreSet
import android.os.UserHandle
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.stevesoltys.seedvault.Backup
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.BackupMonitor
import com.stevesoltys.seedvault.transport.TRANSPORT_ID
import com.stevesoltys.seedvault.ui.RequireProvisioningViewModel

private val TAG = RestoreViewModel::class.java.simpleName

class RestoreViewModel(app: Application) : RequireProvisioningViewModel(app), RestoreSetClickListener {

    override val isRestoreOperation = true

    private val backupManager = Backup.backupManager

    private var session: IRestoreSession? = null
    private var observer: RestoreObserver? = null
    private val monitor = BackupMonitor()

    private val mRestoreSets = MutableLiveData<RestoreSetResult>()
    internal val restoreSets: LiveData<RestoreSetResult> get() = mRestoreSets

    private val mChosenRestoreSet = MutableLiveData<RestoreSet>()
    internal val chosenRestoreSet: LiveData<RestoreSet> get() = mChosenRestoreSet

    private val mRestoreProgress = MutableLiveData<String>()
    internal val restoreProgress: LiveData<String> get() = mRestoreProgress

    private val mRestoreFinished = MutableLiveData<Int>()
    // Zero on success; a nonzero error code if the restore operation as a whole failed.
    internal val restoreFinished: LiveData<Int> get() = mRestoreFinished

    internal fun loadRestoreSets() {
        val session = this.session ?: backupManager.beginRestoreSessionForUser(UserHandle.myUserId(), null, TRANSPORT_ID)
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
        check(session != null) { "Restore set clicked, but no session available" }
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
            mRestoreProgress.postValue(currentPackage)
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
