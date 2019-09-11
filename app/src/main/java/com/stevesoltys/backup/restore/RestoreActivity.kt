package com.stevesoltys.backup.restore

import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.stevesoltys.backup.R
import com.stevesoltys.backup.transport.backup.plugins.DIRECTORY_ROOT
import com.stevesoltys.backup.ui.BackupActivity
import com.stevesoltys.backup.ui.BackupLocationFragment
import com.stevesoltys.backup.ui.BackupViewModel

class RestoreActivity : BackupActivity() {

    private lateinit var viewModel: RestoreViewModel

    override fun getViewModel(): BackupViewModel = viewModel

    override fun getInitialFragment() = RestoreSetFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        viewModel = ViewModelProviders.of(this).get(RestoreViewModel::class.java)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_fragment_container)

        viewModel.chosenRestoreSet.observe(this, Observer { set ->
            if (set != null) showFragment(RestoreProgressFragment())
        })

        if (savedInstanceState == null && viewModel.validLocationIsSet()) {
            showFragment(getInitialFragment())
        }
    }

    @CallSuper
    override fun onStart() {
        super.onStart()
        if (isFinishing) return

        // check that backup is provisioned
        if (!viewModel.validLocationIsSet()) {
            showFragment(BackupLocationFragment())
        } else if (!viewModel.recoveryCodeIsSet()) {
            showRecoveryCodeActivity()
        }
    }

    override fun onInvalidLocation() {
        AlertDialog.Builder(this)
                .setTitle(getString(R.string.restore_invalid_location_title))
                .setMessage(getString(R.string.restore_invalid_location_message, DIRECTORY_ROOT))
                .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
                .show()
    }

}
