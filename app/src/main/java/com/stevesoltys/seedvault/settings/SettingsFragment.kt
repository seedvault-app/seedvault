package com.stevesoltys.seedvault.settings

import android.app.backup.IBackupManager
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.RemoteException
import android.provider.Settings
import android.provider.Settings.Secure.BACKUP_AUTO_RESTORE
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceChangeListener
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.permitDiskReads
import com.stevesoltys.seedvault.restore.RestoreActivity
import com.stevesoltys.seedvault.ui.toRelativeTime
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

private val TAG = SettingsFragment::class.java.name

class SettingsFragment : PreferenceFragmentCompat() {

    private val viewModel: SettingsViewModel by sharedViewModel()
    private val settingsManager: SettingsManager by inject()
    private val backupManager: IBackupManager by inject()

    private lateinit var backup: TwoStatePreference
    private lateinit var autoRestore: TwoStatePreference
    private lateinit var apkBackup: TwoStatePreference
    private lateinit var backupLocation: Preference
    private lateinit var backupStatus: Preference
    private lateinit var backupStorage: TwoStatePreference
    private lateinit var backupRecoveryCode: Preference

    private var menuBackupNow: MenuItem? = null
    private var menuRestore: MenuItem? = null

    private var storage: Storage? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        permitDiskReads {
            setPreferencesFromResource(R.xml.settings, rootKey)
        }
        setHasOptionsMenu(true)

        backup = findPreference("backup")!!
        backup.onPreferenceChangeListener = OnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            // don't enable if we don't have the main key
            if (enabled && !viewModel.hasMainKey()) {
                showCodeRegenerationNeededDialog()
                backup.isChecked = false
                return@OnPreferenceChangeListener false
            }
            // main key is present, so enable or disable normally
            when (enabled) {
                true -> return@OnPreferenceChangeListener trySetBackupEnabled(true)
                false -> {
                    AlertDialog.Builder(requireContext())
                        .setIcon(R.drawable.ic_warning)
                        .setTitle(R.string.settings_backup_dialog_title)
                        .setMessage(R.string.settings_backup_dialog_message)
                        .setPositiveButton(R.string.settings_backup_dialog_disable) { dialog, _ ->
                            trySetBackupEnabled(false)
                            dialog.dismiss()
                        }
                        .setNegativeButton(R.string.settings_backup_apk_dialog_cancel) { dialog,
                            _ -> dialog.dismiss()
                        }
                        .show()
                    return@OnPreferenceChangeListener false
                }
            }
        }

        backupLocation = findPreference("backup_location")!!
        backupLocation.setOnPreferenceClickListener {
            viewModel.chooseBackupLocation()
            true
        }

        autoRestore = findPreference("auto_restore")!!
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

        apkBackup = findPreference(PREF_KEY_BACKUP_APK)!!
        apkBackup.onPreferenceChangeListener = OnPreferenceChangeListener { _, newValue ->
            val enable = newValue as Boolean
            if (enable) return@OnPreferenceChangeListener true
            AlertDialog.Builder(requireContext())
                .setIcon(R.drawable.ic_warning)
                .setTitle(R.string.settings_backup_apk_dialog_title)
                .setMessage(R.string.settings_backup_apk_dialog_message)
                .setPositiveButton(R.string.settings_backup_apk_dialog_cancel) { dialog, _ ->
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.settings_backup_apk_dialog_disable) { dialog, _ ->
                    apkBackup.isChecked = false
                    dialog.dismiss()
                }
                .show()
            return@OnPreferenceChangeListener false
        }
        backupStatus = findPreference("backup_status")!!

        backupStorage = findPreference("backup_storage")!!
        backupStorage.onPreferenceChangeListener = OnPreferenceChangeListener { _, newValue ->
            val disable = !(newValue as Boolean)
            if (disable) {
                viewModel.disableStorageBackup()
                return@OnPreferenceChangeListener true
            }
            onEnablingStorageBackup()
            return@OnPreferenceChangeListener false
        }

        backupRecoveryCode = findPreference("backup_recovery_code")!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.lastBackupTime.observe(viewLifecycleOwner) { time ->
            setAppBackupStatusSummary(time)
        }

        val backupFiles: Preference = findPreference("backup_files")!!
        viewModel.filesSummary.observe(viewLifecycleOwner) { summary ->
            backupFiles.summary = summary
        }
    }

    override fun onStart() {
        super.onStart()

        // we need to re-set the title when returning to this fragment
        activity?.setTitle(R.string.backup)

        storage = settingsManager.getStorage()
        setBackupEnabledState()
        setBackupLocationSummary()
        setAutoRestoreState()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.settings_menu, menu)
        menuBackupNow = menu.findItem(R.id.action_backup)
        menuRestore = menu.findItem(R.id.action_restore)
        if (resources.getBoolean(R.bool.show_restore_in_settings)) {
            menuRestore?.isVisible = true
        }
        viewModel.backupPossible.observe(viewLifecycleOwner) { possible ->
            menuBackupNow?.isEnabled = possible
            menuRestore?.isEnabled = possible
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_backup -> {
            viewModel.backupNow()
            true
        }
        R.id.action_restore -> {
            startActivity(Intent(requireContext(), RestoreActivity::class.java))
            true
        }
        R.id.action_settings_expert -> {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment, ExpertSettingsFragment())
                .addToBackStack(null)
                .commit()
            true
        }
        R.id.action_about -> {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment, AboutDialogFragment())
                .addToBackStack(AboutDialogFragment.TAG)
                .commit()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun trySetBackupEnabled(enabled: Boolean): Boolean {
        return try {
            backupManager.isBackupEnabled = enabled
            if (enabled) viewModel.enableCallLogBackup()
            backup.isChecked = enabled
            true
        } catch (e: RemoteException) {
            Log.e(TAG, "Error setting backup enabled to $enabled", e)
            backup.isChecked = !enabled
            false
        }
    }

    private fun setBackupEnabledState() {
        try {
            backup.isChecked = backupManager.isBackupEnabled
            backup.isEnabled = true
            // enable call log backups for existing installs (added end of 2020)
            if (backup.isChecked) viewModel.enableCallLogBackup()
        } catch (e: RemoteException) {
            Log.e(TAG, "Error communicating with BackupManager", e)
            backup.isEnabled = false
        }
    }

    private fun setAutoRestoreState() {
        activity?.contentResolver?.let {
            autoRestore.isChecked = Settings.Secure.getInt(it, BACKUP_AUTO_RESTORE, 1) == 1
        }
        val storage = this.storage
        if (storage?.isUsb == true) {
            autoRestore.summary = getString(R.string.settings_auto_restore_summary) + "\n\n" +
                getString(R.string.settings_auto_restore_summary_usb, storage.name)
        } else {
            autoRestore.setSummary(R.string.settings_auto_restore_summary)
        }
    }

    private fun setBackupLocationSummary() {
        // get name of storage location
        backupLocation.summary = storage?.name ?: getString(R.string.settings_backup_location_none)
    }

    private fun setAppBackupStatusSummary(lastBackupInMillis: Long) {
        // set time of last backup
        val lastBackup = lastBackupInMillis.toRelativeTime(requireContext())
        backupStatus.summary = getString(R.string.settings_backup_status_summary, lastBackup)
    }

    private fun onEnablingStorageBackup() {
        AlertDialog.Builder(requireContext())
            .setIcon(R.drawable.ic_warning)
            .setTitle(R.string.settings_backup_storage_dialog_title)
            .setMessage(R.string.settings_backup_storage_dialog_message)
            .setPositiveButton(R.string.settings_backup_storage_dialog_ok) { dialog, _ ->
                // warn if battery optimization is active
                // we don't bother with yet another dialog, because the ROM should handle it
                val context = requireContext()
                val powerManager = context.getSystemService(PowerManager::class.java)
                if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                    Toast.makeText(context, R.string.settings_backup_storage_battery_optimization,
                        LENGTH_LONG).show()
                }
                viewModel.enableStorageBackup()
                backupStorage.isChecked = true
                dialog.dismiss()
            }
            .setNegativeButton(R.string.settings_backup_apk_dialog_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showCodeRegenerationNeededDialog() {
        AlertDialog.Builder(requireContext())
            .setIcon(R.drawable.ic_vpn_key)
            .setTitle(R.string.settings_backup_new_code_dialog_title)
            .setMessage(R.string.settings_backup_new_code_dialog_message)
            .setPositiveButton(R.string.settings_backup_new_code_code_dialog_ok) { dialog, _ ->
                val callback = (requireActivity() as OnPreferenceStartFragmentCallback)
                callback.onPreferenceStartFragment(this, backupRecoveryCode)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.settings_backup_apk_dialog_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

}
