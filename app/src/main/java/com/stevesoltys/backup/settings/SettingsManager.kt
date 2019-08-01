package com.stevesoltys.backup.settings

import android.content.Context
import android.net.Uri
import android.preference.PreferenceManager.getDefaultSharedPreferences

private const val PREF_KEY_BACKUP_URI = "backupUri"
private const val PREF_KEY_DEVICE_NAME = "deviceName"
private const val PREF_KEY_BACKUP_PASSWORD = "backupLegacyPassword"

fun setBackupFolderUri(context: Context, uri: Uri) {
    getDefaultSharedPreferences(context)
            .edit()
            .putString(PREF_KEY_BACKUP_URI, uri.toString())
            .apply()
}

fun getBackupFolderUri(context: Context): Uri? {
    val uriStr = getDefaultSharedPreferences(context).getString(PREF_KEY_BACKUP_URI, null)
            ?: return null
    return Uri.parse(uriStr)
}

fun setDeviceName(context: Context, name: String) {
    getDefaultSharedPreferences(context)
            .edit()
            .putString(PREF_KEY_DEVICE_NAME, name)
            .apply()
}

fun getDeviceName(context: Context): String? {
    return getDefaultSharedPreferences(context).getString(PREF_KEY_DEVICE_NAME, null)
}

/**
 * This is insecure and not supposed to be part of a release,
 * but rather an intermediate step towards a generated passphrase.
 */
@Deprecated("Replaced by KeyManager#storeBackupKey()")
fun setBackupPassword(context: Context, password: String) {
    getDefaultSharedPreferences(context)
            .edit()
            .putString(PREF_KEY_BACKUP_PASSWORD, password)
            .apply()
}

@Deprecated("Replaced by KeyManager#getBackupKey()")
fun getBackupPassword(context: Context): String? {
    return getDefaultSharedPreferences(context).getString(PREF_KEY_BACKUP_PASSWORD, null)
}

@Deprecated("Our own scheduling will be removed")
fun setBackupsScheduled(context: Context) {
    getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(PREF_KEY_BACKUPS_SCHEDULED, true)
            .apply()
}

@Deprecated("Our own scheduling will be removed")
fun areBackupsScheduled(context: Context): Boolean {
    return getDefaultSharedPreferences(context).getBoolean(PREF_KEY_BACKUPS_SCHEDULED, false)
}
