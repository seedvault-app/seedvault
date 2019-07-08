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
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.lifecycle.ViewModelProviders
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceChangeListener

private val TAG = SettingsFragment::class.java.name

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var backupManager: IBackupManager

    private lateinit var viewModel: SettingsViewModel

    private lateinit var backup: TwoStatePreference
    private lateinit var autoRestore: TwoStatePreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)
        setHasOptionsMenu(true)

        backupManager = IBackupManager.Stub.asInterface(getService(BACKUP_SERVICE))

        viewModel = ViewModelProviders.of(requireActivity()).get(SettingsViewModel::class.java)

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

        val backupLocation = findPreference("backup_location")
        backupLocation.setOnPreferenceClickListener {
            viewModel.chooseBackupLocation()
            true
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

        // we need to re-set the title when returning to this fragment
        requireActivity().setTitle(R.string.app_name)

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

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.settings_menu, menu)
        if (resources.getBoolean(R.bool.show_restore_in_settings)) {
            menu.findItem(R.id.action_restore).isVisible = true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when {
        item.itemId == R.id.action_backup -> {
            Toast.makeText(requireContext(), "Not yet implemented", Toast.LENGTH_SHORT).show()
            true
        }
        item.itemId == R.id.action_restore -> {
            Toast.makeText(requireContext(), "Not yet implemented", Toast.LENGTH_SHORT).show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

}
