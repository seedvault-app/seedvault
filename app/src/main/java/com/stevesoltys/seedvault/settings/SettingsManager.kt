package com.stevesoltys.seedvault.settings

import android.content.Context
import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import java.util.*

private const val PREF_KEY_STORAGE_URI = "storageUri"
private const val PREF_KEY_STORAGE_NAME = "storageName"
private const val PREF_KEY_STORAGE_IS_USB = "storageIsUsb"

private const val PREF_KEY_FLASH_DRIVE_NAME = "flashDriveName"
private const val PREF_KEY_FLASH_DRIVE_SERIAL_NUMBER = "flashSerialNumber"
private const val PREF_KEY_FLASH_DRIVE_VENDOR_ID = "flashDriveVendorId"
private const val PREF_KEY_FLASH_DRIVE_PRODUCT_ID = "flashDriveProductId"

private const val PREF_KEY_BACKUP_TOKEN = "backupToken"
private const val PREF_KEY_BACKUP_TIME = "backupTime"
private const val PREF_KEY_BACKUP_PASSWORD = "backupLegacyPassword"

class SettingsManager(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    fun setStorage(storage: Storage) {
        prefs.edit()
                .putString(PREF_KEY_STORAGE_URI, storage.uri.toString())
                .putString(PREF_KEY_STORAGE_NAME, storage.name)
                .putBoolean(PREF_KEY_STORAGE_IS_USB, storage.isUsb)
                .apply()
    }

    fun getStorage(): Storage? {
        val uriStr = prefs.getString(PREF_KEY_STORAGE_URI, null) ?: return null
        val uri = Uri.parse(uriStr)
        val name = prefs.getString(PREF_KEY_STORAGE_NAME, null) ?: throw IllegalStateException("no storage name")
        val isUsb = prefs.getBoolean(PREF_KEY_STORAGE_IS_USB, false)
        return Storage(uri, name, isUsb)
    }

    fun setFlashDrive(usb: FlashDrive?) {
        if (usb == null) {
            prefs.edit()
                    .remove(PREF_KEY_FLASH_DRIVE_NAME)
                    .remove(PREF_KEY_FLASH_DRIVE_SERIAL_NUMBER)
                    .remove(PREF_KEY_FLASH_DRIVE_VENDOR_ID)
                    .remove(PREF_KEY_FLASH_DRIVE_PRODUCT_ID)
                    .apply()
        } else {
            prefs.edit()
                    .putString(PREF_KEY_FLASH_DRIVE_NAME, usb.name)
                    .putString(PREF_KEY_FLASH_DRIVE_SERIAL_NUMBER, usb.serialNumber)
                    .putInt(PREF_KEY_FLASH_DRIVE_VENDOR_ID, usb.vendorId)
                    .putInt(PREF_KEY_FLASH_DRIVE_PRODUCT_ID, usb.productId)
                    .apply()
        }
    }

    fun getFlashDrive(): FlashDrive? {
        val name = prefs.getString(PREF_KEY_FLASH_DRIVE_NAME, null) ?: return null
        val serialNumber = prefs.getString(PREF_KEY_FLASH_DRIVE_SERIAL_NUMBER, null)
        val vendorId = prefs.getInt(PREF_KEY_FLASH_DRIVE_VENDOR_ID, -1)
        val productId = prefs.getInt(PREF_KEY_FLASH_DRIVE_PRODUCT_ID, -1)
        return FlashDrive(name, serialNumber, vendorId, productId)
    }

    /**
     * Generates and returns a new backup token while saving it as well.
     * Subsequent calls to [getBackupToken] will return this new token once saved.
     */
    fun getAndSaveNewBackupToken(): Long = Date().time.apply {
        prefs.edit()
                .putLong(PREF_KEY_BACKUP_TOKEN, this)
                .apply()
    }

    /**
     * Returns the current backup token or 0 if none exists.
     */
    fun getBackupToken(): Long {
        return prefs.getLong(PREF_KEY_BACKUP_TOKEN, 0L)
    }

    /**
     * Sets the last backup time to "now".
     */
    fun saveNewBackupTime() {
        prefs.edit()
                .putLong(PREF_KEY_BACKUP_TIME, Date().time)
                .apply()
    }

    /**
     * Sets the last backup time to "never".
     */
    fun resetBackupTime() {
        prefs.edit()
                .putLong(PREF_KEY_BACKUP_TIME, 0L)
                .apply()
    }

    /**
     * Returns the last backup time in unix epoch milli seconds.
     */
    fun getBackupTime(): Long {
        return prefs.getLong(PREF_KEY_BACKUP_TIME, 0L)
    }

    @Deprecated("Replaced by KeyManager#getBackupKey()")
    fun getBackupPassword(): String? {
        return prefs.getString(PREF_KEY_BACKUP_PASSWORD, null)
    }

}

data class Storage(
        val uri: Uri,
        val name: String,
        val isUsb: Boolean) {
    fun getDocumentFile(context: Context) = DocumentFile.fromTreeUri(context, uri)
            ?: throw AssertionError("Should only happen on API < 21.")
}

data class FlashDrive(
        val name: String,
        val serialNumber: String?,
        val vendorId: Int,
        val productId: Int) {
    companion object {
        fun from(device: UsbDevice) = FlashDrive(
                name = "${device.manufacturerName} ${device.productName}",
                serialNumber = device.serialNumber,
                vendorId = device.vendorId,
                productId = device.productId
        )
    }
}
