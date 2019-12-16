package com.stevesoltys.seedvault.ui.storage

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.transport.backup.plugins.DIRECTORY_ROOT
import com.stevesoltys.seedvault.transport.backup.plugins.findFileBlocking
import com.stevesoltys.seedvault.transport.restore.plugins.DocumentsProviderRestorePlugin

private val TAG = RestoreStorageViewModel::class.java.simpleName

internal class RestoreStorageViewModel(private val app: Application) : StorageViewModel(app) {

    override val isRestoreOperation = true

    override fun onLocationSet(uri: Uri) = Thread {
        if (hasBackup(uri)) {
            saveStorage(uri)

            mLocationChecked.postEvent(LocationResult())
        } else {
            Log.w(TAG, "Location was rejected: $uri")

            // notify the UI that the location was invalid
            val errorMsg = app.getString(R.string.restore_invalid_location_message, DIRECTORY_ROOT)
            mLocationChecked.postEvent(LocationResult(errorMsg))
        }
    }.start()

    /**
     * Searches if there's really a backup available in the given location.
     * Returns true if at least one was found and false otherwise.
     *
     * This method is not plugin-agnostic and breaks encapsulation.
     * It is specific to the (currently only) DocumentsProvider plugin.
     *
     * TODO maybe move this to the RestoreCoordinator once we can inject it
     */
    @WorkerThread
    private fun hasBackup(folderUri: Uri): Boolean {
        val parent = DocumentFile.fromTreeUri(app, folderUri) ?: throw AssertionError()
        val rootDir = parent.findFileBlocking(app, DIRECTORY_ROOT) ?: return false
        val backupSets = DocumentsProviderRestorePlugin.getBackups(app, rootDir)
        return backupSets.isNotEmpty()
    }

}
