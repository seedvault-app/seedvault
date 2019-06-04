package com.stevesoltys.backup.settings;

import android.annotation.Nullable;
import android.content.Context;
import android.net.Uri;

import static android.preference.PreferenceManager.getDefaultSharedPreferences;

public class SettingsManager {

    private static final String PREF_KEY_BACKUP_URI = "backupUri";

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

}
