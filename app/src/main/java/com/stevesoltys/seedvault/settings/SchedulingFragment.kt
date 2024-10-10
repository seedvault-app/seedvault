/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.work.ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.permitDiskReads
import com.stevesoltys.seedvault.settings.preference.M3ListPreference
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

class SchedulingFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val viewModel: SettingsViewModel by sharedViewModel()
    private val settingsManager: SettingsManager by inject()
    private val backendManager: BackendManager by inject()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        permitDiskReads {
            setPreferencesFromResource(R.xml.settings_scheduling, rootKey)
            PreferenceManager.setDefaultValues(requireContext(), R.xml.settings_scheduling, false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val storage = backendManager.backendProperties
        if (storage?.isUsb == true) {
            findPreference<PreferenceCategory>("scheduling_category_conditions")?.isEnabled = false
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        when (preference) {
            is ListPreference -> {
                val dialogFragment = M3ListPreference.newInstance(preference.getKey())
                dialogFragment.setTargetFragment(this, 0)
                dialogFragment.show(
                    parentFragmentManager,
                    M3ListPreference.PREFERENCE_DIALOG_FRAGMENT_TAG
                )
            }

            else -> super.onDisplayPreferenceDialog(preference)
        }
    }

    override fun onStart() {
        super.onStart()

        activity?.setTitle(R.string.settings_backup_scheduling_title)
    }

    override fun onResume() {
        super.onResume()
        settingsManager.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        settingsManager.unregisterOnSharedPreferenceChangeListener(this)
    }

    // we can not use setOnPreferenceChangeListener() because that gets called
    // before prefs were saved
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PREF_KEY_SCHED_FREQ -> viewModel.scheduleAppBackup(CANCEL_AND_REENQUEUE)
            PREF_KEY_SCHED_METERED -> viewModel.scheduleAppBackup(UPDATE)
            PREF_KEY_SCHED_CHARGING -> viewModel.scheduleAppBackup(UPDATE)
        }
    }

}
