package com.stevesoltys.backup.settings

import android.content.Context
import android.net.Uri
import android.preference.PreferenceManager.getDefaultSharedPreferences
import androidx.documentfile.provider.DocumentFile
import java.util.*

private const val PREF_KEY_STORAGE_URI = "storageUri"
private const val PREF_KEY_STORAGE_NAME = "storageName"
private const val PREF_KEY_STORAGE_EJECTABLE = "storageEjectable"
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
    getDefaultSharedPreferences(context)
            .edit()
            .putString(PREF_KEY_STORAGE_URI, storage.uri.toString())
            .putString(PREF_KEY_STORAGE_NAME, storage.name)
            .putBoolean(PREF_KEY_STORAGE_EJECTABLE, storage.ejectable)
            .apply()
}

fun getStorage(context: Context): Storage? {
    val prefs = getDefaultSharedPreferences(context)
    val uriStr = prefs.getString(PREF_KEY_STORAGE_URI, null) ?: return null
    val uri = Uri.parse(uriStr)
    val name = prefs.getString(PREF_KEY_STORAGE_NAME, null) ?: throw IllegalStateException()
    val ejectable = prefs.getBoolean(PREF_KEY_STORAGE_EJECTABLE, false)
    return Storage(uri, name, ejectable)
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

@Deprecated("Replaced by KeyManager#getBackupKey()")
fun getBackupPassword(context: Context): String? {
    return getDefaultSharedPreferences(context).getString(PREF_KEY_BACKUP_PASSWORD, null)
}
