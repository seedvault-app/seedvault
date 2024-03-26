package com.stevesoltys.seedvault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED
import android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED
import android.hardware.usb.UsbManager.EXTRA_DEVICE
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat.startForegroundService
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.settings.FlashDrive
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.storage.StorageBackupService
import com.stevesoltys.seedvault.storage.StorageBackupService.Companion.EXTRA_START_APP_BACKUP
import com.stevesoltys.seedvault.ui.storage.AUTHORITY_STORAGE
import com.stevesoltys.seedvault.worker.AppBackupWorker
import org.koin.core.context.GlobalContext.get
import java.util.Date

private val TAG = UsbIntentReceiver::class.java.simpleName

class UsbIntentReceiver : UsbMonitor() {

    // using KoinComponent would crash robolectric tests :(
    private val settingsManager: SettingsManager by lazy { get().get() }
    private val metadataManager: MetadataManager by lazy { get().get() }

    override fun shouldMonitorStatus(context: Context, action: String, device: UsbDevice): Boolean {
        if (action != ACTION_USB_DEVICE_ATTACHED) return false
        Log.d(TAG, "Checking if this is the current backup drive.")
        val savedFlashDrive = settingsManager.getFlashDrive() ?: return false
        val attachedFlashDrive = FlashDrive.from(device)
        return if (savedFlashDrive == attachedFlashDrive) {
            Log.d(TAG, "Matches stored device, checking backup time...")
            val backupMillis = System.currentTimeMillis() - metadataManager.getLastBackupTime()
            if (backupMillis >= settingsManager.backupFrequencyInMillis) {
                Log.d(TAG, "Last backup older than it should be, requesting a backup...")
                Log.d(TAG, "  ${Date(metadataManager.getLastBackupTime())}")
                true
            } else {
                Log.d(TAG, "We have a recent backup, not requesting a new one.")
                Log.d(TAG, "  ${Date(metadataManager.getLastBackupTime())}")
                false
            }
        } else {
            Log.d(TAG, "Different device attached, ignoring...")
            false
        }
    }

    override fun onStatusChanged(context: Context, action: String, device: UsbDevice) {
        if (settingsManager.isStorageBackupEnabled()) {
            val i = Intent(context, StorageBackupService::class.java)
            // this starts an app backup afterwards
            i.putExtra(EXTRA_START_APP_BACKUP, true)
            startForegroundService(context, i)
        } else {
            AppBackupWorker.scheduleNow(context, reschedule = false)
        }
    }

}

/**
 * When we get the [ACTION_USB_DEVICE_ATTACHED] broadcast, the storage is not yet available.
 * So we need to use a ContentObserver to request a backup only once available.
 */
abstract class UsbMonitor : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (intent.action == ACTION_USB_DEVICE_ATTACHED ||
            intent.action == ACTION_USB_DEVICE_DETACHED
        ) {
            val device = intent.extras?.getParcelable<UsbDevice>(EXTRA_DEVICE) ?: return
            Log.d(TAG, "New USB mass-storage device attached.")
            device.log()

            if (!shouldMonitorStatus(context, action, device)) return

            val rootsUri = DocumentsContract.buildRootsUri(AUTHORITY_STORAGE)
            val contentResolver = context.contentResolver
            val handler = Handler(Looper.getMainLooper())
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    onStatusChanged(context, action, device)
                    contentResolver.unregisterContentObserver(this)
                }
            }
            contentResolver.registerContentObserver(rootsUri, true, observer)
        }
    }

    abstract fun shouldMonitorStatus(context: Context, action: String, device: UsbDevice): Boolean

    abstract fun onStatusChanged(context: Context, action: String, device: UsbDevice)

}

internal fun UsbDevice.isMassStorage(): Boolean {
    for (i in 0 until interfaceCount) {
        if (getInterface(i).isMassStorage()) return true
    }
    return false
}

private fun UsbInterface.isMassStorage(): Boolean {
    @Suppress("MagicNumber")
    return interfaceClass == 8 && interfaceProtocol == 80 && interfaceSubclass == 6
}

private fun UsbDevice.log() {
    Log.d(TAG, "  name: $manufacturerName $productName")
    Log.d(TAG, "  serialNumber: $serialNumber")
    Log.d(TAG, "  productId: $productId")
    Log.d(TAG, "  vendorId: $vendorId")
    Log.d(TAG, "  isMassStorage: ${isMassStorage()}")
}
