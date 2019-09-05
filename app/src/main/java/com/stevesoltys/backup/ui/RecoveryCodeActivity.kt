package com.stevesoltys.backup.ui

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProviders
import com.stevesoltys.backup.R

class RecoveryCodeActivity : AppCompatActivity() {

    private lateinit var viewModel: RecoveryCodeViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_recovery_code)

        viewModel = ViewModelProviders.of(this).get(RecoveryCodeViewModel::class.java)
        viewModel.confirmButtonClicked.observeEvent(this, LiveEventHandler { clicked ->
            if (clicked) {
                val tag = "Confirm"
                supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment, RecoveryCodeInputFragment(), tag)
                        .addToBackStack(tag)
                        .commit()
            }
        })
        viewModel.recoveryCodeSaved.observeEvent(this, LiveEventHandler { saved ->
            if (saved) {
                setResult(RESULT_OK)
                finishAfterTransition()
            }
        })

        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                    .add(R.id.fragment, RecoveryCodeOutputFragment(), "Code")
                    .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when {
            item.itemId == android.R.id.home -> {
                onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

}
