package com.stevesoltys.backup.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.stevesoltys.backup.Backup
import com.stevesoltys.backup.R

const val REQUEST_CODE_OPEN_DOCUMENT_TREE = 1
const val REQUEST_CODE_RECOVERY_CODE = 2

private val TAG = BackupActivity::class.java.name

/**
 * An Activity that requires the recovery code and the backup location to be set up
 * before starting.
 */
abstract class BackupActivity : AppCompatActivity() {

    protected abstract fun getViewModel(): BackupViewModel

    protected abstract fun getInitialFragment(): Fragment

    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        getViewModel().onLocationSet.observeEvent(this, LiveEventHandler { result ->
            if (result.validLocation) {
                if (result.initialSetup) showFragment(getInitialFragment())
                else supportFragmentManager.popBackStack()
            } else onInvalidLocation()
        })
        getViewModel().chooseBackupLocation.observeEvent(this, LiveEventHandler { show ->
            if (show) showFragment(BackupLocationFragment(), true)
        })
    }

    @CallSuper
    override fun onStart() {
        super.onStart()
        if (isFinishing) return

        // check that backup is provisioned
        if (!getViewModel().recoveryCodeIsSet()) {
            showRecoveryCodeActivity()
        } else if (!getViewModel().validLocationIsSet()) {
            showFragment(BackupLocationFragment())
            // remove potential error notifications
            (application as Backup).notificationManager.onBackupErrorSeen()
        }
    }

    @CallSuper
    override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        if (resultCode == RESULT_OK && requestCode == REQUEST_CODE_OPEN_DOCUMENT_TREE) {
            getViewModel().handleChooseFolderResult(result)
        } else if (resultCode != RESULT_OK) {
            Log.w(TAG, "Error in activity result: $requestCode")
            finishAfterTransition()
        } else {
            super.onActivityResult(requestCode, resultCode, result)
        }
    }

    @CallSuper
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when {
        item.itemId == android.R.id.home -> {
            onBackPressed()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showRecoveryCodeActivity() {
        val intent = Intent(this, RecoveryCodeActivity::class.java)
        startActivityForResult(intent, REQUEST_CODE_RECOVERY_CODE)
    }

    protected open fun onInvalidLocation() {
        Toast.makeText(this, getString(R.string.settings_backup_location_invalid), LENGTH_LONG).show()
    }

    protected fun showFragment(f: Fragment, addToBackStack: Boolean = false) {
        val fragmentTransaction = supportFragmentManager.beginTransaction()
                .replace(R.id.fragment, f)
        if (addToBackStack) fragmentTransaction.addToBackStack(null)
        fragmentTransaction.commit()
    }

}
