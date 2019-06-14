package com.stevesoltys.backup.settings;

import android.annotation.Nullable;
import android.content.Context;
import android.net.Uri;

import static android.preference.PreferenceManager.getDefaultSharedPreferences;

public class SettingsManager {

    private static final String PREF_KEY_BACKUP_URI = "backupUri";
    private static final String PREF_KEY_BACKUP_PASSWORD = "backupLegacyPassword";
    private static final String PREF_KEY_BACKUPS_SCHEDULED = "backupsScheduled";

    public static void setBackupFolderUri(Context context, Uri uri) {
        getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_KEY_BACKUP_URI, uri.toString())
                .apply();
    }

    @Nullable
    public static Uri getBackupFolderUri(Context context) {
        String uriStr = getDefaultSharedPreferences(context).getString(PREF_KEY_BACKUP_URI, null);
        if (uriStr == null) return null;
        return Uri.parse(uriStr);
    }

    /**
     * This is insecure and not supposed to be part of a release,
     * but rather an intermediate step towards a generated passphrase.
     */
    public static void setBackupPassword(Context context, String password) {
        getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_KEY_BACKUP_PASSWORD, password)
                .apply();
    }

    @Nullable
    public static String getBackupPassword(Context context) {
        return getDefaultSharedPreferences(context).getString(PREF_KEY_BACKUP_PASSWORD, null);
    }

    public static void setBackupsScheduled(Context context) {
        getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(PREF_KEY_BACKUPS_SCHEDULED, true)
                .apply();
    }

    @Nullable
    public static Boolean areBackupsScheduled(Context context) {
        return getDefaultSharedPreferences(context).getBoolean(PREF_KEY_BACKUPS_SCHEDULED, false);
    }

}
