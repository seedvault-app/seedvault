package com.stevesoltys.seedvault.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.permitDiskReads

class ExpertSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        permitDiskReads {
            setPreferencesFromResource(R.xml.settings_expert, rootKey)
        }
    }

    override fun onStart() {
        super.onStart()
        activity?.setTitle(R.string.settings_expert_title)
    }
}
