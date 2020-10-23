package com.stevesoltys.seedvault.ui.storage

import android.app.Application
import android.content.Context
import android.content.Context.USB_SERVICE
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.hardware.usb.UsbManager
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.isMassStorage
import com.stevesoltys.seedvault.permitDiskReads
import com.stevesoltys.seedvault.settings.BackupManagerSettings
import com.stevesoltys.seedvault.settings.FlashDrive
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.settings.Storage
import com.stevesoltys.seedvault.ui.LiveEvent
import com.stevesoltys.seedvault.ui.MutableLiveEvent

private val TAG = StorageViewModel::class.java.simpleName

internal abstract class StorageViewModel(
    private val app: Application,
    protected val settingsManager: SettingsManager
) : AndroidViewModel(app), RemovableStorageListener {

    private val mStorageRoots = MutableLiveData<List<StorageRoot>>()
    internal val storageRoots: LiveData<List<StorageRoot>> get() = mStorageRoots

    private val mLocationSet = MutableLiveEvent<Boolean>()
    internal val locationSet: LiveEvent<Boolean> get() = mLocationSet

    protected val mLocationChecked = MutableLiveEvent<LocationResult>()
    internal val locationChecked: LiveEvent<LocationResult> get() = mLocationChecked

    private val storageRootFetcher by lazy { StorageRootFetcher(app, isRestoreOperation) }
    private var storageRoot: StorageRoot? = null

    internal var isSetupWizard: Boolean = false
    abstract val isRestoreOperation: Boolean

    companion object {
        internal fun validLocationIsSet(
            context: Context,
            settingsManager: SettingsManager
        ): Boolean {
            val storage = settingsManager.getStorage() ?: return false
            if (storage.isUsb) return true
            return permitDiskReads {
                storage.getDocumentFile(context).isDirectory
            }
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

    fun onStorageRootChosen(root: StorageRoot) {
        storageRoot = root
    }

    internal fun onUriPermissionResultReceived(uri: Uri?) {
        if (uri == null) {
            val msg = app.getString(R.string.storage_check_fragment_permission_error)
            mLocationChecked.setEvent(LocationResult(msg))
            return
        }

        // inform UI that a location has been successfully selected
        mLocationSet.setEvent(true)

        // persist permission to access backup folder across reboots
        val takeFlags = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.takePersistableUriPermission(uri, takeFlags)

        onLocationSet(uri)
    }

    /**
     * Saves the storage behind the given [Uri] (and saved [storageRoot]).
     *
     * @return true if the storage is a USB flash drive, false otherwise.
     */
    protected fun saveStorage(uri: Uri): Boolean {
        // store backup storage location in settings
        val root = storageRoot ?: throw IllegalStateException("no storage root")
        val name = if (root.isInternal()) {
            "${root.title} (${app.getString(R.string.settings_backup_location_internal)})"
        } else {
            root.title
        }
        val storage = Storage(uri, name, root.isUsb, root.requiresNetwork)
        settingsManager.setStorage(storage)

        if (storage.isUsb) {
            Log.d(TAG, "Selected storage is a removable USB device.")
            val wasSaved = saveUsbDevice()
            // reset stored flash drive, if we did not update it
            if (!wasSaved) settingsManager.setFlashDrive(null)
        } else {
            settingsManager.setFlashDrive(null)
        }
        BackupManagerSettings.resetDefaults(app.contentResolver)

        Log.d(TAG, "New storage location saved: $uri")

        return storage.isUsb
    }

    private fun saveUsbDevice(): Boolean {
        val manager = app.getSystemService(USB_SERVICE) as UsbManager
        manager.deviceList.values.forEach { device ->
            if (device.isMassStorage()) {
                val flashDrive = FlashDrive.from(device)
                settingsManager.setFlashDrive(flashDrive)
                Log.d(TAG, "Saved flash drive: $flashDrive")
                return true
            }
        }
        Log.e(TAG, "No USB device found even though we were expecting one.")
        return false
    }

    abstract fun onLocationSet(uri: Uri)

    override fun onCleared() {
        storageRootFetcher.setRemovableStorageListener(null)
        super.onCleared()
    }

}

class LocationResult(val errorMsg: String? = null)
