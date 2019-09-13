package com.stevesoltys.backup.settings

import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.lifecycle.ViewModelProviders
import com.stevesoltys.backup.Backup
import com.stevesoltys.backup.R
import com.stevesoltys.backup.ui.RequireProvisioningActivity
import com.stevesoltys.backup.ui.RequireProvisioningViewModel

class SettingsActivity : RequireProvisioningActivity() {

    private lateinit var viewModel: SettingsViewModel

    override fun getViewModel(): RequireProvisioningViewModel = viewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        viewModel = ViewModelProviders.of(this).get(SettingsViewModel::class.java)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_fragment_container)

        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) showFragment(SettingsFragment())
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
            (application as Backup).notificationManager.onBackupErrorSeen()
        }
    }

}
