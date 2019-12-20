package com.stevesoltys.seedvault.restore

import android.app.Activity.RESULT_OK
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.core.content.ContextCompat.getColor
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.getAppName
import com.stevesoltys.seedvault.isDebugBuild
import kotlinx.android.synthetic.main.fragment_restore_progress.*
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

class RestoreProgressFragment : Fragment() {

    private val viewModel: RestoreViewModel by sharedViewModel()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_restore_progress, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        // decryption will fail when the device is locked, so keep the screen on to prevent locking
        requireActivity().window.addFlags(FLAG_KEEP_SCREEN_ON)

        viewModel.chosenRestorableBackup.observe(this, Observer { restorableBackup ->
            backupNameView.text = restorableBackup.name
        })

        viewModel.restoreProgress.observe(this, Observer { currentPackage ->
            val appName = getAppName(requireActivity().packageManager, currentPackage)
            val displayName = if (isDebugBuild()) "$appName (${currentPackage})" else appName
            currentPackageView.text = getString(R.string.restore_current_package, displayName)
        })

        viewModel.restoreBackupResult.observe(this, Observer { finished ->
            progressBar.visibility = INVISIBLE
            button.visibility = VISIBLE
            if (finished.hasError()) {
                currentPackageView.text = finished.errorMsg
                currentPackageView.setTextColor(getColor(requireContext(), R.color.red))
            } else {
                currentPackageView.text = getString(R.string.restore_finished_success)
            }
            activity?.window?.clearFlags(FLAG_KEEP_SCREEN_ON)
        })

        button.setOnClickListener {
            requireActivity().setResult(RESULT_OK)
            requireActivity().finishAfterTransition()
        }
    }

}
