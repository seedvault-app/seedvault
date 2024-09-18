/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.settings

import android.app.backup.IBackupManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceChangeListener
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.mms.ContentType.TEXT_PLAIN
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.permitDiskReads
import com.stevesoltys.seedvault.transport.backup.PackageService
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

class ExpertSettingsFragment : PreferenceFragmentCompat() {

    private val viewModel: SettingsViewModel by sharedViewModel()
    private val packageService: PackageService by inject()
    private val backupManager: IBackupManager by inject()

    private lateinit var apkBackup: TwoStatePreference

    private val createFileLauncher =
        registerForActivityResult(CreateDocument(TEXT_PLAIN)) { uri ->
            viewModel.onLogcatUriReceived(uri)
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        permitDiskReads {
            setPreferencesFromResource(R.xml.settings_expert, rootKey)
        }

        apkBackup = findPreference(PREF_KEY_BACKUP_APK)!!
        apkBackup.onPreferenceChangeListener = OnPreferenceChangeListener { _, newValue ->
            val enable = newValue as Boolean
            if (enable) return@OnPreferenceChangeListener true
            MaterialAlertDialogBuilder(requireContext())
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

        findPreference<Preference>("logcat")?.setOnPreferenceClickListener {
            val versionName = packageService.getVersionName(requireContext().packageName) ?: "ver"
            val timestamp = System.currentTimeMillis()
            val name = "seedvault-$versionName-$timestamp.txt"
            createFileLauncher.launch(name)
            true
        }
    }

    override fun onStart() {
        super.onStart()
        activity?.setTitle(R.string.settings_expert_title)
        apkBackup.isEnabled = backupManager.isBackupEnabled
    }
}
