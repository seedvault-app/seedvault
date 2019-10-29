package com.stevesoltys.seedvault.ui.storage

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.CallSuper
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProviders
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.ui.BackupActivity
import com.stevesoltys.seedvault.ui.INTENT_EXTRA_IS_RESTORE
import com.stevesoltys.seedvault.ui.INTENT_EXTRA_IS_SETUP_WIZARD
import com.stevesoltys.seedvault.ui.LiveEventHandler

private val TAG = StorageActivity::class.java.name

class StorageActivity : BackupActivity() {

    private lateinit var viewModel: StorageViewModel

    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isSetupWizard()) hideSystemUI()

        setContentView(R.layout.activity_fragment_container)

        viewModel = if (isRestore()) {
            ViewModelProviders.of(this).get(RestoreStorageViewModel::class.java)
        } else {
            ViewModelProviders.of(this).get(BackupStorageViewModel::class.java)
        }
        viewModel.isSetupWizard = isSetupWizard()

        viewModel.locationSet.observeEvent(this, LiveEventHandler {
            showFragment(StorageCheckFragment.newInstance(getCheckFragmentTitle()), true)
        })

        viewModel.locationChecked.observeEvent(this, LiveEventHandler { result ->
            val errorMsg = result.errorMsg
            if (errorMsg == null) {
                setResult(RESULT_OK)
                finishAfterTransition()
            } else {
                onInvalidLocation(errorMsg)
            }
        })

        if (savedInstanceState == null) {
            showFragment(StorageRootsFragment.newInstance(isRestore()))
        }
    }

    @CallSuper
    override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        if (resultCode != RESULT_OK) {
            Log.w(TAG, "Error in activity result: $requestCode")
            onInvalidLocation(getString(R.string.storage_check_fragment_permission_error))
        } else {
            super.onActivityResult(requestCode, resultCode, result)
        }
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            Log.d(TAG, "Blocking back button.")
        } else {
            super.onBackPressed()
        }
    }

    private fun onInvalidLocation(errorMsg: String) {
        if (viewModel.isRestoreOperation) {
            supportFragmentManager.popBackStack()
            AlertDialog.Builder(this)
                    .setTitle(getString(R.string.restore_invalid_location_title))
                    .setMessage(errorMsg)
                    .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
                    .show()
        } else {
            showFragment(StorageCheckFragment.newInstance(getCheckFragmentTitle(), errorMsg))
        }
    }

    private fun isRestore(): Boolean {
        return intent?.getBooleanExtra(INTENT_EXTRA_IS_RESTORE, false) ?: false
    }

    private fun isSetupWizard(): Boolean {
        return intent?.getBooleanExtra(INTENT_EXTRA_IS_SETUP_WIZARD, false) ?: false
    }

    private fun getCheckFragmentTitle() = if (viewModel.isRestoreOperation) {
        getString(R.string.storage_check_fragment_restore_title)
    } else {
        getString(R.string.storage_check_fragment_backup_title)
    }

}
