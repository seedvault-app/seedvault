package com.stevesoltys.backup.settings

import android.content.Context.BACKUP_SERVICE
import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import android.provider.Settings.Secure.BACKUP_AUTO_RESTORE
import android.text.format.DateUtils
import android.text.format.DateUtils.*
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.lifecycle.ViewModelProviders
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceChangeListener
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference
import com.stevesoltys.backup.Backup
import com.stevesoltys.backup.R
import com.stevesoltys.backup.restore.RestoreActivity
import java.util.*

private val TAG = SettingsFragment::class.java.name

class SettingsFragment : PreferenceFragmentCompat() {

    private val backupManager = Backup.backupManager

    private lateinit var viewModel: SettingsViewModel
    private lateinit var settingsManager: SettingsManager

    private lateinit var backup: TwoStatePreference
    private lateinit var autoRestore: TwoStatePreference
    private lateinit var backupLocation: Preference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)
        setHasOptionsMenu(true)

        viewModel = ViewModelProviders.of(requireActivity()).get(SettingsViewModel::class.java)
        settingsManager = (requireContext().applicationContext as Backup).settingsManager

        backup = findPreference<TwoStatePreference>("backup")!!
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

        backupLocation = findPreference<Preference>("backup_location")!!
        backupLocation.setOnPreferenceClickListener {
            viewModel.chooseBackupLocation()
            true
        }

        autoRestore = findPreference<TwoStatePreference>("auto_restore")!!
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
        val activity = requireActivity()
        activity.setTitle(R.string.app_name)

        try {
            backup.isChecked = backupManager.isBackupEnabled
            backup.isEnabled = true
        } catch (e: RemoteException) {
            Log.e(TAG, "Error communicating with BackupManager", e)
            backup.isEnabled = false
        }

        val resolver = activity.contentResolver
        autoRestore.isChecked = Settings.Secure.getInt(resolver, BACKUP_AUTO_RESTORE, 1) == 1

        // get name of storage location
        val storageName = settingsManager.getStorage()?.name
                ?: getString(R.string.settings_backup_location_none)

        // get time of last backup
        val lastBackupInMillis = settingsManager.getBackupTime()
        val lastBackup = if (lastBackupInMillis == 0L) {
            getString(R.string.settings_backup_last_backup_never)
        } else {
            getRelativeTimeSpanString(lastBackupInMillis, Date().time, MINUTE_IN_MILLIS, 0)
        }
        backupLocation.summary = getString(R.string.settings_backup_location_summary, storageName, lastBackup)
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
            viewModel.backupNow()
            true
        }
        item.itemId == R.id.action_restore -> {
            startActivity(Intent(requireContext(), RestoreActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

}
