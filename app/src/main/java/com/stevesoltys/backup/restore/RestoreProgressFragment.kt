package com.stevesoltys.backup.restore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.stevesoltys.backup.R
import com.stevesoltys.backup.getAppName
import com.stevesoltys.backup.isDebugBuild
import kotlinx.android.synthetic.main.fragment_restore_progress.*

class RestoreProgressFragment : Fragment() {

    private lateinit var viewModel: RestoreViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_restore_progress, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        // decryption will fail when the device is locked, so keep the screen on to prevent locking
        requireActivity().window.addFlags(FLAG_KEEP_SCREEN_ON)

        viewModel = ViewModelProviders.of(requireActivity()).get(RestoreViewModel::class.java)

        viewModel.numPackages.observe(this, Observer { numPackages ->
            progressBar.min = 0
            progressBar.max = numPackages
        })

        viewModel.chosenRestoreSet.observe(this, Observer { set ->
            backupNameView.text = set.device
        })

        viewModel.restoreProgress.observe(this, Observer { progress ->
            progressBar.progress = progress.nowBeingRestored
            val appName = getAppName(requireActivity().packageManager, progress.currentPackage)
            val displayName = if (isDebugBuild()) "$appName (${progress.currentPackage})" else appName
            currentPackageView.text = getString(R.string.restore_current_package, displayName)
        })

        viewModel.restoreFinished.observe(this, Observer { finished ->
            progressBarIndefinite.visibility = INVISIBLE
            progressBar.progress = viewModel.numPackages.value ?: progressBar.max
            button.visibility = VISIBLE
            if (finished == 0) {
                // success
                currentPackageView.text = getString(R.string.restore_finished_success)
                warningView.visibility = VISIBLE
            } else {
                // error
                currentPackageView.text = getString(R.string.restore_finished_error)
                currentPackageView.setTextColor(warningView.textColors)
            }
            activity?.window?.clearFlags(FLAG_KEEP_SCREEN_ON)
        })

        button.setOnClickListener { requireActivity().finishAfterTransition() }
    }

}
