package com.stevesoltys.backup.settings

import android.content.Context
import android.content.Context.BACKUP_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED
import android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import android.provider.Settings.Secure.BACKUP_AUTO_RESTORE
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.text.format.DateUtils.getRelativeTimeSpanString
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
import com.stevesoltys.backup.UsbMonitor
import com.stevesoltys.backup.isMassStorage
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

    private var menuBackupNow: MenuItem? = null
    private var menuRestore: MenuItem? = null

    private var storage: Storage? = null
    private val usbFilter = IntentFilter(ACTION_USB_DEVICE_ATTACHED).apply {
        addAction(ACTION_USB_DEVICE_DETACHED)
    }
    private val usbReceiver = object : UsbMonitor() {
        override fun shouldMonitorStatus(context: Context, action: String, device: UsbDevice): Boolean {
            return device.isMassStorage()
        }

        override fun onStatusChanged(context: Context, action: String, device: UsbDevice) {
            setMenuItemStates()
        }
    }

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
        activity?.setTitle(R.string.app_name)

        storage = settingsManager.getStorage()
        setBackupState()
        setAutoRestoreState()
        setBackupLocationSummary()
        setMenuItemStates()

        if (storage?.isUsb == true) context?.registerReceiver(usbReceiver, usbFilter)
    }

    override fun onStop() {
        super.onStop()
        if (storage?.isUsb == true) context?.unregisterReceiver(usbReceiver)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.settings_menu, menu)
        menuBackupNow = menu.findItem(R.id.action_backup)
        menuRestore = menu.findItem(R.id.action_restore)
        if (resources.getBoolean(R.bool.show_restore_in_settings)) {
            menuRestore?.isVisible = true
        }
        setMenuItemStates()
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

    private fun setBackupState() {
        try {
            backup.isChecked = backupManager.isBackupEnabled
            backup.isEnabled = true
        } catch (e: RemoteException) {
            Log.e(TAG, "Error communicating with BackupManager", e)
            backup.isEnabled = false
        }
    }

    private fun setAutoRestoreState() {
        activity?.contentResolver?.let {
            autoRestore.isChecked = Settings.Secure.getInt(it, BACKUP_AUTO_RESTORE, 1) == 1
        }
    }

    private fun setBackupLocationSummary() {
        // get name of storage location
        val storageName = storage?.name ?: getString(R.string.settings_backup_location_none)

        // get time of last backup
        val lastBackupInMillis = settingsManager.getBackupTime()
        val lastBackup = if (lastBackupInMillis == 0L) {
            getString(R.string.settings_backup_last_backup_never)
        } else {
            getRelativeTimeSpanString(lastBackupInMillis, Date().time, MINUTE_IN_MILLIS, 0)
        }
        backupLocation.summary = getString(R.string.settings_backup_location_summary, storageName, lastBackup)
    }

    private fun setMenuItemStates() {
        val context = context ?: return
        if (menuBackupNow != null && menuRestore != null) {
            val storage = this.storage
            val enabled = storage != null &&
                    (!storage.isUsb || storage.getDocumentFile(context).isDirectory)
            menuBackupNow?.isEnabled = enabled
            menuRestore?.isEnabled = enabled
        }
    }

}
