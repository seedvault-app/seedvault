package com.stevesoltys.seedvault.settings

import android.content.Context
import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.annotation.UiThread
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.stevesoltys.seedvault.transport.backup.BackupCoordinator
import java.util.concurrent.ConcurrentSkipListSet

internal const val PREF_KEY_TOKEN = "token"
internal const val PREF_KEY_BACKUP_APK = "backup_apk"

private const val PREF_KEY_STORAGE_URI = "storageUri"
private const val PREF_KEY_STORAGE_NAME = "storageName"
private const val PREF_KEY_STORAGE_IS_USB = "storageIsUsb"

private const val PREF_KEY_FLASH_DRIVE_NAME = "flashDriveName"
private const val PREF_KEY_FLASH_DRIVE_SERIAL_NUMBER = "flashSerialNumber"
private const val PREF_KEY_FLASH_DRIVE_VENDOR_ID = "flashDriveVendorId"
private const val PREF_KEY_FLASH_DRIVE_PRODUCT_ID = "flashDriveProductId"

private const val PREF_KEY_BACKUP_APP_BLACKLIST = "backupAppBlacklist"

class SettingsManager(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    @Volatile
    private var token: Long? = null

    /**
     * This gets accessed by non-UI threads when saving with [PreferenceManager]
     * and when [isBackupEnabled] is called during a backup run.
     * Therefore, it is implemented with a thread-safe [ConcurrentSkipListSet].
     */
    private val blacklistedApps: MutableSet<String> by lazy {
        ConcurrentSkipListSet(prefs.getStringSet(PREF_KEY_BACKUP_APP_BLACKLIST, emptySet()))
    }

    fun getToken(): Long? = token ?: {
        val value = prefs.getLong(PREF_KEY_TOKEN, 0L)
        if (value == 0L) null else value
    }()

    /**
     * Sets a new RestoreSet token.
     * Should only be called by the [BackupCoordinator]
     * to ensure that related work is performed after moving to a new token.
     */
    fun setNewToken(newToken: Long) {
        prefs.edit().putLong(PREF_KEY_TOKEN, newToken).apply()
        token = newToken
    }

    // FIXME Storage is currently plugin specific and not generic
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
        val name = prefs.getString(PREF_KEY_STORAGE_NAME, null)
            ?: throw IllegalStateException("no storage name")
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

    fun backupApks(): Boolean {
        return prefs.getBoolean(PREF_KEY_BACKUP_APK, true)
    }

    fun isBackupEnabled(packageName: String) = !blacklistedApps.contains(packageName)

    @UiThread
    fun onAppBackupStatusChanged(status: AppStatus) {
        if (status.enabled) blacklistedApps.remove(status.packageName)
        else blacklistedApps.add(status.packageName)
        prefs.edit().putStringSet(PREF_KEY_BACKUP_APP_BLACKLIST, blacklistedApps).apply()
    }

}

data class Storage(
    val uri: Uri,
    val name: String,
    val isUsb: Boolean
) {
    fun getDocumentFile(context: Context) = DocumentFile.fromTreeUri(context, uri)
        ?: throw AssertionError("Should only happen on API < 21.")
}

data class FlashDrive(
    val name: String,
    val serialNumber: String?,
    val vendorId: Int,
    val productId: Int
) {
    companion object {
        fun from(device: UsbDevice) = FlashDrive(
            name = "${device.manufacturerName} ${device.productName}",
            serialNumber = device.serialNumber,
            vendorId = device.vendorId,
            productId = device.productId
        )
    }
}
