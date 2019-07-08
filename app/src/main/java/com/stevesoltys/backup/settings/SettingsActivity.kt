package com.stevesoltys.backup.settings

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProviders
import com.stevesoltys.backup.LiveEventHandler
import com.stevesoltys.backup.R

private val TAG = SettingsActivity::class.java.name

const val REQUEST_CODE_OPEN_DOCUMENT_TREE = 1
const val REQUEST_CODE_RECOVERY_CODE = 2

class SettingsActivity : AppCompatActivity() {

    private lateinit var viewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_settings)

        viewModel = ViewModelProviders.of(this).get(SettingsViewModel::class.java)
        viewModel.onLocationSet.observeEvent(this, LiveEventHandler { wasEmptyBefore ->
            if (wasEmptyBefore) showFragment(SettingsFragment())
            else supportFragmentManager.popBackStack()
        })
        viewModel.chooseBackupLocation.observeEvent(this, LiveEventHandler { show ->
            if (show) showFragment(BackupLocationFragment(), true)
        })

        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) showFragment(SettingsFragment())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        if (resultCode != RESULT_OK) {
            Log.w(TAG, "Error in activity result: $requestCode")
            finishAfterTransition()
        } else {
            super.onActivityResult(requestCode, resultCode, result)
        }
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return

        // check that backup is provisioned
        if (!viewModel.recoveryCodeIsSet()) {
            showRecoveryCodeActivity()
        } else if (!viewModel.locationIsSet()) {
            showFragment(BackupLocationFragment())
        }
    }

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

    private fun showFragment(f: Fragment, addToBackStack: Boolean = false) {
        val fragmentTransaction = supportFragmentManager.beginTransaction()
                .replace(R.id.fragment, f)
        if (addToBackStack) fragmentTransaction.addToBackStack(null)
        fragmentTransaction.commit()
    }

}
