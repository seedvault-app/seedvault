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
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.stevesoltys.seedvault.backend.webdav.WebDavHandler.Companion.createWebDavProperties
import com.stevesoltys.seedvault.permitDiskReads
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.saf.SafBackend
import org.calyxos.seedvault.core.backends.saf.SafProperties
import org.calyxos.seedvault.core.backends.webdav.WebDavBackend
import org.calyxos.seedvault.core.backends.webdav.WebDavConfig
import org.calyxos.seedvault.core.backends.webdav.WebDavProperties
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
internal const val PREF_KEY_LAST_BACKUP = "lastBackup"

class SettingsManager(private val context: Context) {

    private val prefs = permitDiskReads {
        PreferenceManager.getDefaultSharedPreferences(context)
    }
    private val mLastBackupTime = MutableLiveData(prefs.getLong(PREF_KEY_LAST_BACKUP, -1))

    /**
     * Returns a LiveData of the last backup time in unix epoch milli seconds.
     */
    internal val lastBackupTime: LiveData<Long> = mLastBackupTime

    /**
     * This gets accessed by non-UI threads when saving with [PreferenceManager]
     * and when [isBackupEnabled] is called during a backup run.
     * Therefore, it is implemented with a thread-safe [ConcurrentSkipListSet].
     */
    private val blacklistedApps: MutableSet<String> by lazy {
        ConcurrentSkipListSet(prefs.getStringSet(PREF_KEY_BACKUP_APP_BLACKLIST, emptySet()))
    }

    @Volatile
    var token: Long? = null
        private set(newToken) {
            if (newToken == null) {
                prefs.edit()
                    .remove(PREF_KEY_TOKEN)
                    .apply()
            } else {
                prefs.edit()
                    .putLong(PREF_KEY_TOKEN, newToken)
                    .apply()
            }
            field = newToken
        }
        // we may be able to get this from latest snapshot,
        // but that is not always readily available
        get() = field ?: run {
            val value = prefs.getLong(PREF_KEY_TOKEN, 0L)
            if (value == 0L) null else value
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

    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun onSuccessfulBackupCompleted(token: Long) {
        this.token = token
        val now = System.currentTimeMillis()
        prefs.edit().putLong(PREF_KEY_LAST_BACKUP, now).apply()
        mLastBackupTime.postValue(now)
    }

    fun setStorageBackend(plugin: Backend) {
        val value = when (plugin) {
            is SafBackend -> StoragePluginType.SAF
            is WebDavBackend -> StoragePluginType.WEB_DAV
            else -> error("Unsupported plugin: ${plugin::class.java.simpleName}")
        }.name
        prefs.edit()
            .putString(PREF_KEY_STORAGE_PLUGIN, value)
            .apply()
    }

    fun setSafProperties(safProperties: SafProperties) {
        prefs.edit()
            .putString(PREF_KEY_STORAGE_URI, safProperties.uri.toString())
            .putString(PREF_KEY_STORAGE_ROOT_ID, safProperties.rootId)
            .putString(PREF_KEY_STORAGE_NAME, safProperties.name)
            .putBoolean(PREF_KEY_STORAGE_IS_USB, safProperties.isUsb)
            .putBoolean(PREF_KEY_STORAGE_REQUIRES_NETWORK, safProperties.requiresNetwork)
            .apply()
    }

    fun getSafProperties(): SafProperties? {
        val uriStr = prefs.getString(PREF_KEY_STORAGE_URI, null) ?: return null
        val uri = Uri.parse(uriStr)
        val name = prefs.getString(PREF_KEY_STORAGE_NAME, null)
            ?: throw IllegalStateException("no storage name")
        val isUsb = prefs.getBoolean(PREF_KEY_STORAGE_IS_USB, false)
        val requiresNetwork = prefs.getBoolean(PREF_KEY_STORAGE_REQUIRES_NETWORK, false)
        val rootId = prefs.getString(PREF_KEY_STORAGE_ROOT_ID, null)
        return SafProperties(uri, name, isUsb, requiresNetwork, rootId)
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

    val quota: Long = 1024 * 1024 * 1024 // 1 GiB for now

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
