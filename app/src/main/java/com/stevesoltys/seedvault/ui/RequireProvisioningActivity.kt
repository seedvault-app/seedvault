package com.stevesoltys.seedvault.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.CallSuper
import com.stevesoltys.seedvault.ui.recoverycode.RecoveryCodeActivity
import com.stevesoltys.seedvault.ui.storage.StorageActivity

const val INTENT_EXTRA_IS_RESTORE = "isRestore"
const val INTENT_EXTRA_IS_SETUP_WIZARD = "isSetupWizard"

private const val ACTION_SETUP_WIZARD = "com.stevesoltys.seedvault.RESTORE_BACKUP"

private val TAG = RequireProvisioningActivity::class.java.name

/**
 * An Activity that requires the recovery code and the backup location to be set up
 * before starting.
 */
abstract class RequireProvisioningActivity : BackupActivity() {

    private val recoveryCodeRequest =
        registerForActivityResult(StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                Log.w(TAG, "Error in activity result for requesting recovery code")
                if (!getViewModel().recoveryCodeIsSet()) {
                    finishAfterTransition()
                }
            }
        }
    private val requestLocation =
        registerForActivityResult(StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                Log.w(TAG, "Error in activity result for requesting location")
                if (!getViewModel().validLocationIsSet()) {
                    finishAfterTransition()
                }
            } else getViewModel().onStorageLocationChanged()
        }

    protected val isSetupWizard: Boolean
        get() = intent?.action == ACTION_SETUP_WIZARD

    protected abstract fun getViewModel(): RequireProvisioningViewModel

    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        getViewModel().chooseBackupLocation.observeEvent(this, LiveEventHandler { show ->
            if (show) showStorageActivity()
        })
    }

    protected fun showStorageActivity() {
        val intent = Intent(this, StorageActivity::class.java)
        intent.putExtra(INTENT_EXTRA_IS_RESTORE, getViewModel().isRestoreOperation)
        intent.putExtra(INTENT_EXTRA_IS_SETUP_WIZARD, isSetupWizard)
        requestLocation.launch(intent)
    }

    protected fun showRecoveryCodeActivity() {
        val intent = Intent(this, RecoveryCodeActivity::class.java)
        intent.putExtra(INTENT_EXTRA_IS_RESTORE, getViewModel().isRestoreOperation)
        intent.putExtra(INTENT_EXTRA_IS_SETUP_WIZARD, isSetupWizard)
        recoveryCodeRequest.launch(intent)
    }

}
