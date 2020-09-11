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
import android.provider.DocumentsContract
import android.util.Log
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.settings.FlashDrive
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.requestBackup
import com.stevesoltys.seedvault.ui.storage.AUTHORITY_STORAGE
import org.koin.core.context.KoinContextHandler.get
import java.util.concurrent.TimeUnit.HOURS

private val TAG = UsbIntentReceiver::class.java.simpleName

class UsbIntentReceiver : UsbMonitor() {

    // using KoinComponent would crash robolectric tests :(
    private val settingsManager: SettingsManager by lazy { get().get<SettingsManager>() }
    private val metadataManager: MetadataManager by lazy { get().get<MetadataManager>() }

    override fun shouldMonitorStatus(context: Context, action: String, device: UsbDevice): Boolean {
        if (action != ACTION_USB_DEVICE_ATTACHED) return false
        Log.d(TAG, "Checking if this is the current backup drive.")
        val savedFlashDrive = settingsManager.getFlashDrive() ?: return false
        val attachedFlashDrive = FlashDrive.from(device)
        return if (savedFlashDrive == attachedFlashDrive) {
            Log.d(TAG, "Matches stored device, checking backup time...")
            if (System.currentTimeMillis() - metadataManager.getLastBackupTime() >= HOURS.toMillis(24)) {
                Log.d(TAG, "Last backup older than 24 hours, requesting a backup...")
                true
            } else {
                Log.d(TAG, "We have a recent backup, not requesting a new one.")
                false
            }
        } else {
            Log.d(TAG, "Different device attached, ignoring...")
            false
        }
    }

    override fun onStatusChanged(context: Context, action: String, device: UsbDevice) {
        Thread {
            requestBackup(context)
        }.start()
    }

}

/**
 * When we get the [ACTION_USB_DEVICE_ATTACHED] broadcast, the storage is not yet available.
 * So we need to use a ContentObserver to request a backup only once available.
 */
abstract class UsbMonitor : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (intent.action == ACTION_USB_DEVICE_ATTACHED || intent.action == ACTION_USB_DEVICE_DETACHED) {
            val device = intent.extras?.getParcelable<UsbDevice>(EXTRA_DEVICE) ?: return
            Log.d(TAG, "New USB mass-storage device attached.")
            device.log()

            if (!shouldMonitorStatus(context, action, device)) return

            val rootsUri = DocumentsContract.buildRootsUri(AUTHORITY_STORAGE)
            val contentResolver = context.contentResolver
            val observer = object : ContentObserver(Handler()) {
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
    return interfaceClass == 8 && interfaceProtocol == 80 && interfaceSubclass == 6
}

private fun UsbDevice.log() {
    Log.d(TAG, "  name: $manufacturerName $productName")
    Log.d(TAG, "  serialNumber: $serialNumber")
    Log.d(TAG, "  productId: $productId")
    Log.d(TAG, "  vendorId: $vendorId")
    Log.d(TAG, "  isMassStorage: ${isMassStorage()}")
}
