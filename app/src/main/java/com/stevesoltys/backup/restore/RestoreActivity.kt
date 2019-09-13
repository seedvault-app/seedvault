package com.stevesoltys.backup.restore

import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.stevesoltys.backup.R
import com.stevesoltys.backup.ui.RequireProvisioningActivity
import com.stevesoltys.backup.ui.RequireProvisioningViewModel

class RestoreActivity : RequireProvisioningActivity() {

    private lateinit var viewModel: RestoreViewModel

    override fun getViewModel(): RequireProvisioningViewModel = viewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        viewModel = ViewModelProviders.of(this).get(RestoreViewModel::class.java)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_fragment_container)

        viewModel.chosenRestoreSet.observe(this, Observer { set ->
            if (set != null) showFragment(RestoreProgressFragment())
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
