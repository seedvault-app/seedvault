package com.stevesoltys.backup.settings

import android.content.Context
import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import java.util.*

private const val PREF_KEY_STORAGE_URI = "storageUri"
private const val PREF_KEY_STORAGE_NAME = "storageName"
private const val PREF_KEY_STORAGE_EJECTABLE = "storageEjectable"

private const val PREF_KEY_FLASH_DRIVE_NAME = "flashDriveName"
private const val PREF_KEY_FLASH_DRIVE_SERIAL_NUMBER = "flashSerialNumber"
private const val PREF_KEY_FLASH_DRIVE_VENDOR_ID = "flashDriveVendorId"
private const val PREF_KEY_FLASH_DRIVE_PRODUCT_ID = "flashDriveProductId"

private const val PREF_KEY_BACKUP_TOKEN = "backupToken"
private const val PREF_KEY_BACKUP_PASSWORD = "backupLegacyPassword"

data class Storage(
        val uri: Uri,
        val name: String,
        val ejectable: Boolean) {
    fun getDocumentFile(context: Context) = DocumentFile.fromTreeUri(context, uri)
            ?: throw AssertionError("Should only happen on API < 21.")
}

fun setStorage(context: Context, storage: Storage) {
    PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(PREF_KEY_STORAGE_URI, storage.uri.toString())
            .putString(PREF_KEY_STORAGE_NAME, storage.name)
            .putBoolean(PREF_KEY_STORAGE_EJECTABLE, storage.ejectable)
            .apply()
}

fun getStorage(context: Context): Storage? {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val uriStr = prefs.getString(PREF_KEY_STORAGE_URI, null) ?: return null
    val uri = Uri.parse(uriStr)
    val name = prefs.getString(PREF_KEY_STORAGE_NAME, null) ?: throw IllegalStateException()
    val ejectable = prefs.getBoolean(PREF_KEY_STORAGE_EJECTABLE, false)
    return Storage(uri, name, ejectable)
}

data class FlashDrive(
        val name: String,
        val serialNumber: String?,
        val vendorId: Int,
        val productId: Int) {
    companion object {
        fun from(device: UsbDevice) = FlashDrive(
                name = "${device.manufacturerName} ${device.productName}",
                serialNumber = "", // device.serialNumber requires a permission since API 29
                vendorId = device.vendorId,
                productId = device.productId
        )
    }
}

fun setFlashDrive(context: Context, usb: FlashDrive?) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
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

fun getFlashDrive(context: Context): FlashDrive? {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
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
fun getAndSaveNewBackupToken(context: Context): Long = Date().time.apply {
    PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putLong(PREF_KEY_BACKUP_TOKEN, this)
            .apply()
}

/**
 * Returns the current backup token or 0 if none exists.
 */
fun getBackupToken(context: Context): Long {
    return PreferenceManager.getDefaultSharedPreferences(context).getLong(PREF_KEY_BACKUP_TOKEN, 0L)
}

@Deprecated("Replaced by KeyManager#getBackupKey()")
fun getBackupPassword(context: Context): String? {
    return PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY_BACKUP_PASSWORD, null)
}
