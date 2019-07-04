package com.stevesoltys.backup.settings

import android.app.backup.IBackupManager
import android.content.ContentResolver
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import android.util.Log

import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference

import com.stevesoltys.backup.R

import android.content.Context.BACKUP_SERVICE
import android.os.ServiceManager.getService
import android.provider.Settings.Secure.BACKUP_AUTO_RESTORE
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceChangeListener

private val TAG = SettingsFragment::class.java.name

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var backupManager: IBackupManager

    private lateinit var backup: TwoStatePreference
    private lateinit var autoRestore: TwoStatePreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)

        backupManager = IBackupManager.Stub.asInterface(getService(BACKUP_SERVICE))

        backup = findPreference("backup") as TwoStatePreference
        backup.onPreferenceChangeListener = OnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            try {
                backupManager.isBackupEnabled = enabled
                return@OnPreferenceChangeListener true
            } catch (e: RemoteException) {
                e.printStackTrace()
                backup.isChecked = !enabled
                return@OnPreferenceChangeListener false
            }
        }

        autoRestore = findPreference("auto_restore") as TwoStatePreference
        autoRestore.onPreferenceChangeListener = OnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            try {
                backupManager.setAutoRestore(enabled)
                return@OnPreferenceChangeListener true
            } catch (e: RemoteException) {
                Log.e(TAG, "Error communicating with BackupManager", e)
                autoRestore.isChecked = !enabled
                return@OnPreferenceChangeListener false
            }
        }
    }

    override fun onStart() {
        super.onStart()

        try {
            backup.isChecked = backupManager.isBackupEnabled
            backup.isEnabled = true
        } catch (e: RemoteException) {
            Log.e(TAG, "Error communicating with BackupManager", e)
            backup.isEnabled = false
        }

        val resolver = requireContext().contentResolver
        autoRestore.isChecked = Settings.Secure.getInt(resolver, BACKUP_AUTO_RESTORE, 1) == 1
    }

}
