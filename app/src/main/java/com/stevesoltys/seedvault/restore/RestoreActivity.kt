package com.stevesoltys.seedvault.restore

import android.os.Bundle
import androidx.annotation.CallSuper
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_APPS
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_BACKUP
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_FILES
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_FILES_STARTED
import com.stevesoltys.seedvault.restore.install.InstallProgressFragment
import com.stevesoltys.seedvault.ui.RequireProvisioningActivity
import com.stevesoltys.seedvault.ui.RequireProvisioningViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class RestoreActivity : RequireProvisioningActivity() {

    private val viewModel: RestoreViewModel by viewModel()

    override fun getViewModel(): RequireProvisioningViewModel = viewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isSetupWizard) hideSystemUiNavigation()

        setContentView(R.layout.activity_fragment_container)

        viewModel.displayFragment.observeEvent(this, { fragment ->
            when (fragment) {
                RESTORE_APPS -> showFragment(InstallProgressFragment())
                RESTORE_BACKUP -> showFragment(RestoreProgressFragment())
                RESTORE_FILES -> showFragment(RestoreFilesFragment())
                RESTORE_FILES_STARTED -> showFragment(RestoreFilesStartedFragment())
                else -> throw AssertionError()
            }
        })

        if (savedInstanceState == null) {
            showFragment(RestoreSetFragment())
        }
    }

    @CallSuper
    override fun onResume() {
        super.onResume()
        // Activity results from the parent will get delivered before and might tell us to finish.
        // Don't start any new activities when that happens.
        // Note: onStart() can get called *before* results get delivered, so we use onResume() here
        if (isFinishing) return

        // check that backup is provisioned
        if (!viewModel.validLocationIsSet()) {
            showStorageActivity()
        } else if (!viewModel.recoveryCodeIsSet()) {
            showRecoveryCodeActivity()
        }
    }

}
