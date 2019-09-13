package com.stevesoltys.backup.ui.storage

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.stevesoltys.backup.isOnExternalStorage
import com.stevesoltys.backup.settings.getBackupFolderUri
import com.stevesoltys.backup.ui.LiveEvent
import com.stevesoltys.backup.ui.MutableLiveEvent

internal abstract class StorageViewModel(private val app: Application) : AndroidViewModel(app), RemovableStorageListener {

    private val mStorageRoots = MutableLiveData<List<StorageRoot>>()
    internal val storageRoots: LiveData<List<StorageRoot>> get() = mStorageRoots

    private val mLocationSet = MutableLiveEvent<Boolean>()
    internal val locationSet: LiveEvent<Boolean> get() = mLocationSet

    protected val mLocationChecked = MutableLiveEvent<LocationResult>()
    internal val locationChecked: LiveEvent<LocationResult> get() = mLocationChecked

    private val storageRootFetcher by lazy { StorageRootFetcher(app) }

    abstract val isRestoreOperation: Boolean

    companion object {
        internal fun validLocationIsSet(context: Context): Boolean {
            val uri = getBackupFolderUri(context) ?: return false
            if (uri.isOnExternalStorage()) return true  // TODO use ejectable instead
            val file = DocumentFile.fromTreeUri(context, uri) ?: return false
            return file.isDirectory
        }
    }

    internal fun loadStorageRoots() {
        if (storageRootFetcher.getRemovableStorageListener() == null) {
            storageRootFetcher.setRemovableStorageListener(this)
        }
        Thread {
            mStorageRoots.postValue(storageRootFetcher.getStorageRoots())
        }.start()
    }

    override fun onStorageChanged() = loadStorageRoots()


    internal fun onUriPermissionGranted(result: Intent?) {
        val uri = result?.data ?: return

        // inform UI that a location has been successfully selected
        mLocationSet.setEvent(true)

        // persist permission to access backup folder across reboots
        val takeFlags = result.flags and (FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
        app.contentResolver.takePersistableUriPermission(uri, takeFlags)

        onLocationSet(uri)
    }

    abstract fun onLocationSet(uri: Uri)

    override fun onCleared() {
        storageRootFetcher.setRemovableStorageListener(null)
        super.onCleared()
    }

}

class LocationResult(val errorMsg: String? = null)
