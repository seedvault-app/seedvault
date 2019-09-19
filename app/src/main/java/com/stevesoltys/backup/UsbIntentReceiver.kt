package com.stevesoltys.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED
import android.hardware.usb.UsbManager.EXTRA_DEVICE
import android.net.Uri
import android.os.Handler
import android.provider.DocumentsContract
import android.util.Log
import com.stevesoltys.backup.settings.FlashDrive
import com.stevesoltys.backup.transport.requestBackup
import com.stevesoltys.backup.ui.storage.AUTHORITY_STORAGE
import java.util.*
import java.util.concurrent.TimeUnit.HOURS

private val TAG = UsbIntentReceiver::class.java.simpleName

class UsbIntentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_USB_DEVICE_ATTACHED) return

        val device = intent.extras?.getParcelable<UsbDevice>(EXTRA_DEVICE) ?: return
        Log.d(TAG, "New USB mass-storage device attached.")
        device.log()

        val settingsManager = (context.applicationContext as Backup).settingsManager
        val savedFlashDrive = settingsManager.getFlashDrive() ?: return
        val attachedFlashDrive = FlashDrive.from(device)
        if (savedFlashDrive == attachedFlashDrive) {
            Log.d(TAG, "Matches stored device, checking backup time...")
            if (Date().time - settingsManager.getBackupTime() >= HOURS.toMillis(24)) {
                Log.d(TAG, "Last backup older than 24 hours, requesting a backup...")
                startBackupOnceMounted(context)
            } else {
                Log.d(TAG, "We have a recent backup, not requesting a new one.")
            }
        }
    }

}

/**
 * When we get the [ACTION_USB_DEVICE_ATTACHED] broadcast, the storage is not yet available.
 * So we need to use a ContentObserver to request a backup only once available.
 */
private fun startBackupOnceMounted(context: Context) {
    val rootsUri = DocumentsContract.buildRootsUri(AUTHORITY_STORAGE)
    val contentResolver = context.contentResolver
    val observer = object : ContentObserver(Handler()) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            Thread {
                requestBackup(context)
            }.start()
            contentResolver.unregisterContentObserver(this)
        }
    }
    contentResolver.registerContentObserver(rootsUri, true, observer)
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
