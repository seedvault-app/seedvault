/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.settings

import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.annotation.UiThread
import androidx.preference.PreferenceManager
import com.stevesoltys.seedvault.permitDiskReads
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.saf.DocumentsProviderStoragePlugin
import com.stevesoltys.seedvault.plugins.saf.SafStorage
import com.stevesoltys.seedvault.plugins.webdav.WebDavConfig
import com.stevesoltys.seedvault.plugins.webdav.WebDavHandler.Companion.createWebDavProperties
import com.stevesoltys.seedvault.plugins.webdav.WebDavProperties
import com.stevesoltys.seedvault.plugins.webdav.WebDavStoragePlugin
import com.stevesoltys.seedvault.transport.backup.BackupCoordinator
import java.util.concurrent.ConcurrentSkipListSet

internal const val PREF_KEY_TOKEN = "token"
internal const val PREF_KEY_BACKUP_APK = "backup_apk"
internal const val PREF_KEY_AUTO_RESTORE = "auto_restore"
internal const val PREF_KEY_SCHED_FREQ = "scheduling_frequency"
internal const val PREF_KEY_SCHED_METERED = "scheduling_metered"
internal const val PREF_KEY_SCHED_CHARGING = "scheduling_charging"

private const val PREF_KEY_STORAGE_PLUGIN = "storagePlugin"

internal enum class StoragePluginType { // don't rename, will break existing installs
    SAF,
    WEB_DAV,
}

private const val PREF_KEY_STORAGE_URI = "storageUri"
private const val PREF_KEY_STORAGE_ROOT_ID = "storageRootId"
private const val PREF_KEY_STORAGE_NAME = "storageName"
private const val PREF_KEY_STORAGE_IS_USB = "storageIsUsb"
private const val PREF_KEY_STORAGE_REQUIRES_NETWORK = "storageRequiresNetwork"

private const val PREF_KEY_FLASH_DRIVE_NAME = "flashDriveName"
private const val PREF_KEY_FLASH_DRIVE_SERIAL_NUMBER = "flashSerialNumber"
private const val PREF_KEY_FLASH_DRIVE_VENDOR_ID = "flashDriveVendorId"
private const val PREF_KEY_FLASH_DRIVE_PRODUCT_ID = "flashDriveProductId"

private const val PREF_KEY_WEBDAV_URL = "webDavUrl"
private const val PREF_KEY_WEBDAV_USER = "webDavUser"
private const val PREF_KEY_WEBDAV_PASS = "webDavPass"

private const val PREF_KEY_BACKUP_APP_BLACKLIST = "backupAppBlacklist"

private const val PREF_KEY_BACKUP_STORAGE = "backup_storage"
internal const val PREF_KEY_UNLIMITED_QUOTA = "unlimited_quota"
internal const val PREF_KEY_D2D_BACKUPS = "d2d_backups"

class SettingsManager(private val context: Context) {

    private val prefs = permitDiskReads {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    @Volatile
    private var token: Long? = null

    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

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
     * Should only be called by the [BackupCoordinator]
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

    internal val storagePluginType: StoragePluginType?
        get() {
            val savedType = prefs.getString(PREF_KEY_STORAGE_PLUGIN, null)
            return if (savedType == null) {
                // check if this is an existing user that needs to be migrated
                // this check could be removed after a reasonable migration time (added 2024)
                if (prefs.getString(PREF_KEY_STORAGE_URI, null) != null) {
                    prefs.edit()
                        .putString(PREF_KEY_STORAGE_PLUGIN, StoragePluginType.SAF.name)
                        .apply()
                    StoragePluginType.SAF
                } else null
            } else savedType.let {
                try {
                    StoragePluginType.valueOf(it)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
        }

    fun setStoragePlugin(plugin: StoragePlugin<*>) {
        val value = when (plugin) {
            is DocumentsProviderStoragePlugin -> StoragePluginType.SAF
            is WebDavStoragePlugin -> StoragePluginType.WEB_DAV
            else -> error("Unsupported plugin: ${plugin::class.java.simpleName}")
        }.name
        prefs.edit()
            .putString(PREF_KEY_STORAGE_PLUGIN, value)
            .apply()
    }

    fun setSafStorage(safStorage: SafStorage) {
        prefs.edit()
            .putString(PREF_KEY_STORAGE_URI, safStorage.uri.toString())
            .putString(PREF_KEY_STORAGE_ROOT_ID, safStorage.rootId)
            .putString(PREF_KEY_STORAGE_NAME, safStorage.name)
            .putBoolean(PREF_KEY_STORAGE_IS_USB, safStorage.isUsb)
            .putBoolean(PREF_KEY_STORAGE_REQUIRES_NETWORK, safStorage.requiresNetwork)
            .apply()
    }

    fun getSafStorage(): SafStorage? {
        val uriStr = prefs.getString(PREF_KEY_STORAGE_URI, null) ?: return null
        val uri = Uri.parse(uriStr)
        val name = prefs.getString(PREF_KEY_STORAGE_NAME, null)
            ?: throw IllegalStateException("no storage name")
        val isUsb = prefs.getBoolean(PREF_KEY_STORAGE_IS_USB, false)
        val requiresNetwork = prefs.getBoolean(PREF_KEY_STORAGE_REQUIRES_NETWORK, false)
        val rootId = prefs.getString(PREF_KEY_STORAGE_ROOT_ID, null)
        return SafStorage(uri, name, isUsb, requiresNetwork, rootId)
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

    val webDavProperties: WebDavProperties?
        get() {
            val config = WebDavConfig(
                url = prefs.getString(PREF_KEY_WEBDAV_URL, null) ?: return null,
                username = prefs.getString(PREF_KEY_WEBDAV_USER, null) ?: return null,
                password = prefs.getString(PREF_KEY_WEBDAV_PASS, null) ?: return null,
            )
            return createWebDavProperties(context, config)
        }

    fun saveWebDavConfig(config: WebDavConfig) {
        prefs.edit()
            .putString(PREF_KEY_WEBDAV_URL, config.url)
            .putString(PREF_KEY_WEBDAV_USER, config.username)
            .putString(PREF_KEY_WEBDAV_PASS, config.password)
            .apply()
    }

    fun backupApks(): Boolean {
        return prefs.getBoolean(PREF_KEY_BACKUP_APK, true)
    }

    val backupFrequencyInMillis: Long
        get() {
            return prefs.getString(PREF_KEY_SCHED_FREQ, "86400000")?.toLongOrNull()
                ?: 86400000 // 24h
        }
    val useMeteredNetwork: Boolean
        get() = prefs.getBoolean(PREF_KEY_SCHED_METERED, false)
    val backupOnlyWhenCharging: Boolean
        get() = prefs.getBoolean(PREF_KEY_SCHED_CHARGING, true)

    fun isBackupEnabled(packageName: String) = !blacklistedApps.contains(packageName)

    /**
     * Disables backup for an app. Similar to [onAppBackupStatusChanged].
     */
    fun disableBackup(packageName: String) {
        if (blacklistedApps.add(packageName)) {
            prefs.edit().putStringSet(PREF_KEY_BACKUP_APP_BLACKLIST, blacklistedApps).apply()
        }
    }

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

    /**
     * This assumes that if there's no storage plugin set, it is the first start.
     * We enforce a storage plugin and don't allow unsetting one,
     * so this should be a safe assumption.
     */
    val isFirstStart get() = prefs.getString(PREF_KEY_STORAGE_PLUGIN, null) == null

}

data class FlashDrive(
    val name: String,
    val serialNumber: String?,
    val vendorId: Int,
    val productId: Int,
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
