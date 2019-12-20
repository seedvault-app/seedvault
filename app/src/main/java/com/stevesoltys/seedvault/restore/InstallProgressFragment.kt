package com.stevesoltys.seedvault.restore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.transport.restore.InstallResult
import com.stevesoltys.seedvault.transport.restore.getInProgress
import kotlinx.android.synthetic.main.fragment_install_progress.*
import kotlinx.android.synthetic.main.fragment_restore_progress.backupNameView
import kotlinx.android.synthetic.main.fragment_restore_progress.currentPackageView
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

class InstallProgressFragment : Fragment() {

    private val viewModel: RestoreViewModel by sharedViewModel()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_install_progress, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        viewModel.chosenRestorableBackup.observe(this, Observer { restorableBackup ->
            backupNameView.text = restorableBackup.name
        })

        viewModel.installResult.observe(this, Observer { result ->
            onInstallResult(result)
        })
    }

    private fun onInstallResult(installResult: InstallResult) {
        installResult.getInProgress()?.let { result ->
            currentPackageView.text = result.name
            result.icon?.let { currentPackageImageView.setImageDrawable(it) }
            progressBar.progress = result.progress
            progressBar.max = result.total
        }
        // TODO add finished apps to list of (failed?) apps and continue on button press
    }

}
