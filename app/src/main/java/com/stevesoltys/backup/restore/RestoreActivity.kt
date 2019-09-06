package com.stevesoltys.backup.restore

import android.os.Bundle
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.stevesoltys.backup.R
import com.stevesoltys.backup.ui.BackupActivity
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

    override fun onInvalidLocation() {
        // TODO alert dialog?
    }

}
