package com.stevesoltys.seedvault.settings

import android.content.ContentResolver
import android.provider.Settings

private val SETTING = Settings.Secure.BACKUP_MANAGER_CONSTANTS

object BackupManagerSettings {

    /**
     * This clears the backup settings, so that default values will be used.
     *
     * Before end of 2020 (Android 11) we changed the settings in an attempt
     * to prevent automatic backups when flash drives are not plugged in.
     * This turned out to not work reliably, so reset to defaults again here.
     *
     * We can remove this code after the last users can be expected
     * to have changed storage at least once with this code deployed.
     */
    fun resetDefaults(resolver: ContentResolver) {
        if (Settings.Secure.getString(resolver, SETTING) != null) {
            // setting this to null will cause the BackupManagerConstants to use default values
            Settings.Secure.putString(resolver, SETTING, null)
        }
    }

}
