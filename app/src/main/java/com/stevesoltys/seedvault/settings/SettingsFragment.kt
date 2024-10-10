/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.settings

import android.app.backup.IBackupManager
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.RemoteException
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceChangeListener
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference
import androidx.work.WorkInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stevesoltys.seedvault.BackupStateManager
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.permitDiskReads
import com.stevesoltys.seedvault.restore.RestoreActivity
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.toRelativeTime
import org.calyxos.seedvault.core.backends.BackendProperties
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.sharedViewModel
import java.util.concurrent.TimeUnit

private val TAG = SettingsFragment::class.java.name

class SettingsFragment : PreferenceFragmentCompat() {

    private val viewModel: SettingsViewModel by sharedViewModel()
    private val backendManager: BackendManager by inject()
    private val backupStateManager: BackupStateManager by inject()
    private val backupManager: IBackupManager by inject()
    private val notificationManager: BackupNotificationManager by inject()

    private lateinit var backup: TwoStatePreference
    private lateinit var autoRestore: TwoStatePreference
    private lateinit var backupLocation: Preference
    private lateinit var backupStatus: Preference
    private lateinit var backupScheduling: Preference
    private lateinit var backupStorage: TwoStatePreference
    private lateinit var backupRecoveryCode: Preference

    private var menuBackupNow: MenuItem? = null
    private var menuRestore: MenuItem? = null

    private val backendProperties: BackendProperties<*>?
        get() = backendManager.backendProperties

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
                    MaterialAlertDialogBuilder(requireContext())
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
            if (viewModel.isBackupRunning.value) {
                // don't allow changing backup destination while backup is running
                // TODO we could show toast or snackbar here
                false
            } else {
                viewModel.chooseBackupLocation()
                true
            }
        }

        autoRestore = findPreference(PREF_KEY_AUTO_RESTORE)!!
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

        backupStatus = findPreference("backup_status")!!
        backupScheduling = findPreference("backup_scheduling")!!

        backupStorage = findPreference("backup_storage")!!
        backupStorage.onPreferenceChangeListener = OnPreferenceChangeListener { _, newValue ->
            val disable = !(newValue as Boolean)
            // TODO this should really get moved out off the UI layer
            if (disable) {
                viewModel.cancelFilesBackup()
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
        viewModel.appBackupWorkInfo.observe(viewLifecycleOwner) { workInfo ->
            setAppBackupSchedulingSummary(workInfo)
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

        setBackupEnabledState()
        setBackupLocationSummary()
        setAutoRestoreState()
        setAppBackupStatusSummary(viewModel.lastBackupTime.value)
        setAppBackupSchedulingSummary(viewModel.appBackupWorkInfo.value)
    }

    override fun onResume() {
        super.onResume()
        // Activity results from the parent will get delivered before and might tell us to finish.
        // Don't start any new activities when that happens.
        // Note: onStart() can get called *before* results get delivered, so we use onResume() here
        if (requireActivity().isFinishing) return

        // check that backup is provisioned
        val activity = requireActivity() as SettingsActivity
        if (!viewModel.recoveryCodeIsSet()) {
            activity.showRecoveryCodeActivity()
        } else if (!viewModel.validLocationIsSet()) {
            activity.showStorageActivity()
            // remove potential error notifications
            notificationManager.onBackupErrorSeen()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.settings_menu, menu)
        menuBackupNow = menu.findItem(R.id.action_backup)
        menuRestore = menu.findItem(R.id.action_restore)
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
            viewModel.onBackupEnabled(enabled)
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
            autoRestore.isChecked = backupStateManager.isAutoRestoreEnabled
        }
        val storage = this.backendProperties
        if (storage?.isUsb == true) {
            autoRestore.summary = getString(R.string.settings_auto_restore_summary) + "\n\n" +
                getString(R.string.settings_auto_restore_summary_usb, storage.name)
        } else {
            autoRestore.setSummary(R.string.settings_auto_restore_summary)
        }
    }

    private fun setBackupLocationSummary() {
        // get name of storage location
        backupLocation.summary =
            backendProperties?.name ?: getString(R.string.settings_backup_location_none)
    }

    private fun setAppBackupStatusSummary(lastBackupInMillis: Long?) {
        if (lastBackupInMillis != null) {
            // set time of last backup
            val lastBackup = lastBackupInMillis.toRelativeTime(requireContext())
            backupStatus.summary = getString(R.string.settings_backup_status_summary, lastBackup)
        }
    }

    /**
     * Sets the summary for scheduling which is information about when the next backup is scheduled.
     *
     * It could be that it shows the backup as running,
     * gives an estimate about when the next run will be or
     * says that nothing is scheduled which can happen when backup destination is on flash drive.
     */
    private fun setAppBackupSchedulingSummary(workInfo: WorkInfo?) {
        if (backendProperties?.isUsb == true) {
            backupScheduling.summary = getString(R.string.settings_backup_status_next_backup_usb)
            return
        }

        val nextScheduleTimeMillis = workInfo?.nextScheduleTimeMillis ?: Long.MAX_VALUE
        if (workInfo != null && workInfo.state == WorkInfo.State.RUNNING) {
            val text = getString(R.string.notification_title)
            backupScheduling.summary = getString(R.string.settings_backup_status_next_backup, text)
        } else if (nextScheduleTimeMillis == Long.MAX_VALUE) {
            Log.i(TAG, "No backup scheduled! workInfo: $workInfo")
            val text = getString(R.string.settings_backup_last_backup_never)
            backupScheduling.summary = getString(R.string.settings_backup_status_next_backup, text)
        } else {
            val diff = System.currentTimeMillis() - nextScheduleTimeMillis
            val isPast = diff > TimeUnit.MINUTES.toMillis(1)
            if (isPast) {
                val text = getString(R.string.settings_backup_status_next_backup_past)
                backupScheduling.summary =
                    getString(R.string.settings_backup_status_next_backup, text)
            } else {
                val text = nextScheduleTimeMillis.toRelativeTime(requireContext())
                backupScheduling.summary =
                    getString(R.string.settings_backup_status_next_backup_estimate, text)
            }
        }
    }

    private fun onEnablingStorageBackup() {
        MaterialAlertDialogBuilder(requireContext())
            .setIcon(R.drawable.ic_warning)
            .setTitle(R.string.settings_backup_storage_dialog_title)
            .setMessage(R.string.settings_backup_storage_dialog_message)
            .setPositiveButton(R.string.settings_backup_storage_dialog_ok) { dialog, _ ->
                // warn if battery optimization is active
                // we don't bother with yet another dialog, because the ROM should handle it
                val context = requireContext()
                val powerManager: PowerManager? = context.getSystemService(PowerManager::class.java)
                if (powerManager != null &&
                    !powerManager.isIgnoringBatteryOptimizations(context.packageName)
                ) {
                    Toast.makeText(
                        context, R.string.settings_backup_storage_battery_optimization,
                        LENGTH_LONG
                    ).show()
                }
                viewModel.scheduleFilesBackup()
                backupStorage.isChecked = true
                dialog.dismiss()
            }
            .setNegativeButton(R.string.settings_backup_apk_dialog_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showCodeRegenerationNeededDialog() {
        MaterialAlertDialogBuilder(requireContext())
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
