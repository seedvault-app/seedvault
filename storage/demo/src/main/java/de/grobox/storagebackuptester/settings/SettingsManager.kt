package de.grobox.storagebackuptester.settings

import android.content.Context
import android.net.Uri

private const val PREFS = "prefs"
private const val PREF_BACKUP_LOCATION = "backupLocationUri"
private const val PREF_AUTOMATIC_BACKUP = "automaticBackups"

class SettingsManager(private val context: Context) {

    fun setBackupLocation(uri: Uri?) {
        context.getSharedPreferences(PREFS, 0)
            .edit()
            .putString(PREF_BACKUP_LOCATION, uri?.toString())
            .apply()
    }

    fun getBackupLocation(): Uri? {
        val uriStr = context.getSharedPreferences(PREFS, 0)
            .getString(PREF_BACKUP_LOCATION, null)
        return uriStr?.let { Uri.parse(it) }
    }

    fun setAutomaticBackupsEnabled(enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0)
            .edit()
            .putBoolean(PREF_AUTOMATIC_BACKUP, enabled)
            .apply()
    }

    fun areAutomaticBackupsEnabled(): Boolean {
        return context.getSharedPreferences(PREFS, 0)
            .getBoolean(PREF_AUTOMATIC_BACKUP, false)
    }

}
