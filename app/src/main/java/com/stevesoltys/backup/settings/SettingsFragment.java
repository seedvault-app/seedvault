package com.stevesoltys.backup.settings;

import android.app.backup.IBackupManager;
import android.content.ContentResolver;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.TwoStatePreference;

import com.stevesoltys.backup.R;

import static android.content.Context.BACKUP_SERVICE;
import static android.os.ServiceManager.getService;
import static android.provider.Settings.Secure.BACKUP_AUTO_RESTORE;

public class SettingsFragment extends PreferenceFragmentCompat {

    private final static String TAG = SettingsFragment.class.getSimpleName();

    private IBackupManager backupManager;

    private TwoStatePreference backup;
    private TwoStatePreference autoRestore;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings, rootKey);

        backupManager = IBackupManager.Stub.asInterface(getService(BACKUP_SERVICE));

        backup = (TwoStatePreference) findPreference("backup");
        backup.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean enabled = (boolean) newValue;
            try {
                backupManager.setBackupEnabled(enabled);
                return true;
            } catch (RemoteException e) {
                e.printStackTrace();
                backup.setChecked(!enabled);
                return false;
            }
        });

        autoRestore = (TwoStatePreference) findPreference("auto_restore");
        autoRestore.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean enabled = (boolean) newValue;
            try {
                backupManager.setAutoRestore(enabled);
                return true;
            } catch (RemoteException e) {
                Log.e(TAG, "Error communicating with BackupManager", e);
                autoRestore.setChecked(!enabled);
                return false;
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();

        try {
            backup.setChecked(backupManager.isBackupEnabled());
            backup.setEnabled(true);
        } catch (RemoteException e) {
            Log.e(TAG, "Error communicating with BackupManager", e);
            backup.setEnabled(false);
        }

        ContentResolver resolver = requireContext().getContentResolver();
        autoRestore.setChecked(Settings.Secure.getInt(resolver, BACKUP_AUTO_RESTORE, 1) == 1);
    }

}
