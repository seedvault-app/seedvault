package com.stevesoltys.seedvault.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.work.ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.permitDiskReads
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

class SchedulingFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val viewModel: SettingsViewModel by sharedViewModel()
    private val settingsManager: SettingsManager by inject()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        permitDiskReads {
            setPreferencesFromResource(R.xml.settings_scheduling, rootKey)
            PreferenceManager.setDefaultValues(requireContext(), R.xml.settings_scheduling, false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val storage = settingsManager.getStorage()
        if (storage?.isUsb == true) {
            findPreference<PreferenceCategory>("scheduling_category_conditions")?.isEnabled = false
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
