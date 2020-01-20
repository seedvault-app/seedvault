package com.stevesoltys.seedvault.restore

import android.os.Bundle
import androidx.annotation.CallSuper
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_APPS
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_BACKUP
import com.stevesoltys.seedvault.ui.LiveEventHandler
import com.stevesoltys.seedvault.ui.RequireProvisioningActivity
import com.stevesoltys.seedvault.ui.RequireProvisioningViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class RestoreActivity : RequireProvisioningActivity() {

    private val viewModel: RestoreViewModel by viewModel()

    override fun getViewModel(): RequireProvisioningViewModel = viewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_fragment_container)

        viewModel.displayFragment.observeEvent(this, LiveEventHandler { fragment ->
            when (fragment) {
                RESTORE_APPS -> showFragment(InstallProgressFragment())
                RESTORE_BACKUP -> showFragment(RestoreProgressFragment())
                else -> throw AssertionError()
            }
        })

        if (savedInstanceState == null) {
            showFragment(RestoreSetFragment())
        }
    }

    @CallSuper
    override fun onStart() {
        super.onStart()
        if (isFinishing) return

        // check that backup is provisioned
        if (!viewModel.validLocationIsSet()) {
            showStorageActivity()
        } else if (!viewModel.recoveryCodeIsSet()) {
            showRecoveryCodeActivity()
        }
    }

}
