package com.stevesoltys.backup.settings

import android.content.Context
import android.net.Uri
import android.preference.PreferenceManager.getDefaultSharedPreferences
import java.util.*

private const val PREF_KEY_BACKUP_URI = "backupUri"
private const val PREF_KEY_BACKUP_TOKEN = "backupToken"
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

/**
 * Generates and returns a new backup token while saving it as well.
 * Subsequent calls to [getBackupToken] will return this new token once saved.
 */
fun getAndSaveNewBackupToken(context: Context): Long = Date().time.apply {
    getDefaultSharedPreferences(context)
            .edit()
            .putLong(PREF_KEY_BACKUP_TOKEN, this)
            .apply()
}

/**
 * Returns the current backup token or 0 if none exists.
 */
fun getBackupToken(context: Context): Long {
    return getDefaultSharedPreferences(context).getLong(PREF_KEY_BACKUP_TOKEN, 0L)
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

@Deprecated("Replaced by KeyManager#getBackupKey()")
fun getBackupPassword(context: Context): String? {
    return getDefaultSharedPreferences(context).getString(PREF_KEY_BACKUP_PASSWORD, null)
}
