package com.stevesoltys.backup.settings

import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.lifecycle.ViewModelProviders
import com.stevesoltys.backup.Backup
import com.stevesoltys.backup.R
import com.stevesoltys.backup.ui.BackupActivity
import com.stevesoltys.backup.ui.BackupLocationFragment
import com.stevesoltys.backup.ui.BackupViewModel

class SettingsActivity : BackupActivity() {

    private lateinit var viewModel: SettingsViewModel

    override fun getViewModel(): BackupViewModel = viewModel

    override fun getInitialFragment() = SettingsFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        viewModel = ViewModelProviders.of(this).get(SettingsViewModel::class.java)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_fragment_container)

        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) showFragment(getInitialFragment())
    }

    @CallSuper
    override fun onStart() {
        super.onStart()
        if (isFinishing) return

        // check that backup is provisioned
        if (!viewModel.recoveryCodeIsSet()) {
            showRecoveryCodeActivity()
        } else if (!viewModel.validLocationIsSet()) {
            showFragment(BackupLocationFragment())
            // remove potential error notifications
            (application as Backup).notificationManager.onBackupErrorSeen()
        }
    }

}
