package com.stevesoltys.seedvault.settings

import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceFragmentCompat.OnPreferenceStartFragmentCallback
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.ui.RequireProvisioningActivity
import com.stevesoltys.seedvault.ui.RequireProvisioningViewModel
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

internal const val ACTION_APP_STATUS_LIST = "com.stevesoltys.seedvault.APP_STATUS_LIST"

class SettingsActivity : RequireProvisioningActivity(), OnPreferenceStartFragmentCallback {

    private val viewModel: SettingsViewModel by viewModel()
    private val notificationManager: BackupNotificationManager by inject()

    override fun getViewModel(): RequireProvisioningViewModel = viewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_fragment_container)

        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        if (intent?.action == ACTION_APP_STATUS_LIST) {
            showFragment(AppStatusFragment())
        } else if (savedInstanceState == null) {
            showFragment(SettingsFragment())
        }
    }

    @CallSuper
    override fun onStart() {
        super.onStart()
        if (isFinishing) return

        // check that backup is provisioned
        if (!viewModel.recoveryCodeIsSet()) {
            showRecoveryCodeActivity()
        } else if (!viewModel.validLocationIsSet()) {
            showStorageActivity()
            // remove potential error notifications
            notificationManager.onBackupErrorSeen()
        }
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, pref.fragment)
        fragment.setTargetFragment(caller, 0)
        supportFragmentManager.beginTransaction()
                .replace(R.id.fragment, fragment)
                .addToBackStack(null)
                .commit()
        return true
    }

}
