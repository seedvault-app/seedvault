package com.stevesoltys.backup.settings

import android.content.ContentResolver
import android.provider.Settings
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.DAYS

private val SETTING = Settings.Secure.BACKUP_MANAGER_CONSTANTS
private const val DELIMITER = ','

private const val KEY_VALUE_BACKUP_INTERVAL_MILLISECONDS = "key_value_backup_interval_milliseconds"
private const val FULL_BACKUP_INTERVAL_MILLISECONDS = "full_backup_interval_milliseconds"

object BackupManagerSettings {

    /**
     * This clears the backup settings, so that default values will be used.
     */
    fun enableAutomaticBackups(resolver: ContentResolver) {
        // setting this to null will cause the BackupManagerConstants to use default values
        setSettingValue(resolver, null)
    }

    /**
     * This sets the backup intervals to a longer than default value. Currently 30 days
     */
    fun disableAutomaticBackups(resolver: ContentResolver) {
        val value = DAYS.toMillis(30)
        val kv = "$KEY_VALUE_BACKUP_INTERVAL_MILLISECONDS=$value"
        val full = "$FULL_BACKUP_INTERVAL_MILLISECONDS=$value"
        setSettingValue(resolver, "$kv$DELIMITER$full")
    }

    private fun setSettingValue(resolver: ContentResolver, value: String?) {
        Settings.Secure.putString(resolver, SETTING, value)
    }

}
