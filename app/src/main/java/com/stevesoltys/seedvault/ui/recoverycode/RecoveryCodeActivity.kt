package com.stevesoltys.seedvault.ui.recoverycode

import android.os.Bundle
import android.view.MenuItem
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.ui.BackupActivity
import com.stevesoltys.seedvault.ui.INTENT_EXTRA_IS_RESTORE
import org.koin.androidx.viewmodel.ext.android.viewModel

class RecoveryCodeActivity : BackupActivity() {

    private val viewModel: RecoveryCodeViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(FLAG_SECURE)

        setContentView(R.layout.activity_recovery_code)

        viewModel.isRestore = isRestore()
        viewModel.confirmButtonClicked.observeEvent(this, { clicked ->
            if (clicked) showInput(true)
        })
        viewModel.recoveryCodeSaved.observeEvent(this, { saved ->
            if (saved) {
                setResult(RESULT_OK)
                finishAfterTransition()
            }
        })

        if (savedInstanceState == null) {
            if (viewModel.isRestore) showInput(false)
            else showOutput()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showOutput() {
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment, RecoveryCodeOutputFragment(), "Code")
            .commit()
    }

    private fun showInput(addToBackStack: Boolean) {
        val tag = "Confirm"
        val fragmentTransaction = supportFragmentManager.beginTransaction()
            .replace(R.id.fragment, RecoveryCodeInputFragment(), tag)
        if (addToBackStack) fragmentTransaction.addToBackStack(tag)
        fragmentTransaction.commit()
    }

    private fun isRestore(): Boolean {
        return intent?.getBooleanExtra(INTENT_EXTRA_IS_RESTORE, false) ?: false
    }

}
