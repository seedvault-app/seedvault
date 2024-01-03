package com.stevesoltys.seedvault.service.settings

import android.content.Context
import android.net.Uri
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.preference.PreferenceManager
import com.stevesoltys.seedvault.getStorageContext
import com.stevesoltys.seedvault.permitDiskReads
import com.stevesoltys.seedvault.service.app.backup.coordinator.BackupCoordinatorService
import com.stevesoltys.seedvault.ui.settings.AppStatus
import java.util.concurrent.ConcurrentSkipListSet

internal const val PREF_KEY_TOKEN = "token"
internal const val PREF_KEY_BACKUP_APK = "backup_apk"
internal const val PREF_KEY_AUTO_RESTORE = "auto_restore"

private const val PREF_KEY_STORAGE_URI = "storageUri"
private const val PREF_KEY_STORAGE_NAME = "storageName"
private const val PREF_KEY_STORAGE_IS_USB = "storageIsUsb"
private const val PREF_KEY_STORAGE_REQUIRES_NETWORK = "storageRequiresNetwork"

private const val PREF_KEY_FLASH_DRIVE_NAME = "flashDriveName"
private const val PREF_KEY_FLASH_DRIVE_SERIAL_NUMBER = "flashSerialNumber"
private const val PREF_KEY_FLASH_DRIVE_VENDOR_ID = "flashDriveVendorId"
private const val PREF_KEY_FLASH_DRIVE_PRODUCT_ID = "flashDriveProductId"

private const val PREF_KEY_BACKUP_APP_BLACKLIST = "backupAppBlacklist"

private const val PREF_KEY_BACKUP_STORAGE = "backup_storage"
private const val PREF_KEY_UNLIMITED_QUOTA = "unlimited_quota"
internal const val PREF_KEY_D2D_BACKUPS = "d2d_backups"

class SettingsService(
    private val context: Context,
) {

    private val prefs = permitDiskReads {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

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

    fun getToken(): Long? = token ?: run {
        val value = prefs.getLong(PREF_KEY_TOKEN, 0L)
        if (value == 0L) null else value
    }

    /**
     * Sets a new RestoreSet token.
     * Should only be called by the [BackupCoordinatorService]
     * to ensure that related work is performed after moving to a new token.
     */
    fun setNewToken(newToken: Long?) {
        if (newToken == null) {
            prefs.edit()
                .remove(PREF_KEY_TOKEN)
                .apply()
        } else {
            prefs.edit()
                .putLong(PREF_KEY_TOKEN, newToken)
                .apply()
        }

        token = newToken
    }

    // FIXME Storage is currently plugin specific and not generic
    fun setStorage(storage: Storage) {
        prefs.edit()
            .putString(PREF_KEY_STORAGE_URI, storage.uri.toString())
            .putString(PREF_KEY_STORAGE_NAME, storage.name)
            .putBoolean(PREF_KEY_STORAGE_IS_USB, storage.isUsb)
            .putBoolean(PREF_KEY_STORAGE_REQUIRES_NETWORK, storage.requiresNetwork)
            .apply()
    }

    fun getStorage(): Storage? {
        val uriStr = prefs.getString(PREF_KEY_STORAGE_URI, null) ?: return null
        val uri = Uri.parse(uriStr)
        val name = prefs.getString(PREF_KEY_STORAGE_NAME, null)
            ?: throw IllegalStateException("no storage name")
        val isUsb = prefs.getBoolean(PREF_KEY_STORAGE_IS_USB, false)
        val requiresNetwork = prefs.getBoolean(PREF_KEY_STORAGE_REQUIRES_NETWORK, false)
        return Storage(uri, name, isUsb, requiresNetwork)
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
     * Check if we are able to do backups now by examining possible pre-conditions
     * such as plugged-in flash drive or internet access.
     *
     * Should be run off the UI thread (ideally I/O) because of disk access.
     *
     * @return true if a backup is possible, false if not.
     */
    @WorkerThread
    fun canDoBackupNow(): Boolean {
        val storage = getStorage() ?: return false
        val systemContext = context.getStorageContext { storage.isUsb }
        return !storage.isUnavailableUsb(systemContext) && !storage.isUnavailableNetwork(context)
    }

    fun backupApks(): Boolean {
        return prefs.getBoolean(PREF_KEY_BACKUP_APK, true)
    }

    fun isBackupEnabled(packageName: String) = !blacklistedApps.contains(packageName)

    fun isStorageBackupEnabled() = prefs.getBoolean(PREF_KEY_BACKUP_STORAGE, false)

    @UiThread
    fun onAppBackupStatusChanged(status: AppStatus) {
        if (status.enabled) blacklistedApps.remove(status.packageName)
        else blacklistedApps.add(status.packageName)
        prefs.edit().putStringSet(PREF_KEY_BACKUP_APP_BLACKLIST, blacklistedApps).apply()
    }

    fun isQuotaUnlimited() = prefs.getBoolean(PREF_KEY_UNLIMITED_QUOTA, false)

    fun d2dBackupsEnabled() = prefs.getBoolean(PREF_KEY_D2D_BACKUPS, false)

    fun setD2dBackupsEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(PREF_KEY_D2D_BACKUPS, enabled)
            .apply()
    }
}
