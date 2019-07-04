package com.stevesoltys.backup.settings

import android.content.Context
import android.net.Uri
import android.preference.PreferenceManager.getDefaultSharedPreferences

private const val PREF_KEY_BACKUP_URI = "backupUri"
private const val PREF_KEY_BACKUP_PASSWORD = "backupLegacyPassword"
private const val PREF_KEY_BACKUPS_SCHEDULED = "backupsScheduled"

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
 * This is insecure and not supposed to be part of a release,
 * but rather an intermediate step towards a generated passphrase.
 */
fun setBackupPassword(context: Context, password: String) {
    getDefaultSharedPreferences(context)
            .edit()
            .putString(PREF_KEY_BACKUP_PASSWORD, password)
            .apply()
}

fun getBackupPassword(context: Context): String? {
    return getDefaultSharedPreferences(context).getString(PREF_KEY_BACKUP_PASSWORD, null)
}

fun setBackupsScheduled(context: Context) {
    getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(PREF_KEY_BACKUPS_SCHEDULED, true)
            .apply()
}

fun areBackupsScheduled(context: Context): Boolean {
    return getDefaultSharedPreferences(context).getBoolean(PREF_KEY_BACKUPS_SCHEDULED, false)
}
