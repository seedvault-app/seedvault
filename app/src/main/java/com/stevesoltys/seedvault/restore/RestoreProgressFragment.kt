package com.stevesoltys.seedvault.restore

import android.app.Activity.RESULT_OK
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.getAppName
import com.stevesoltys.seedvault.isDebugBuild
import com.stevesoltys.seedvault.settings.SettingsManager
import kotlinx.android.synthetic.main.fragment_restore_progress.*
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

class RestoreProgressFragment : Fragment() {

    private val viewModel: RestoreViewModel by sharedViewModel()
    private val settingsManager: SettingsManager by inject()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_restore_progress, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        // decryption will fail when the device is locked, so keep the screen on to prevent locking
        requireActivity().window.addFlags(FLAG_KEEP_SCREEN_ON)

        viewModel.chosenRestoreSet.observe(this, Observer { set ->
            backupNameView.text = set.device
        })

        viewModel.restoreProgress.observe(this, Observer { currentPackage ->
            val appName = getAppName(requireActivity().packageManager, currentPackage)
            val displayName = if (isDebugBuild()) "$appName (${currentPackage})" else appName
            currentPackageView.text = getString(R.string.restore_current_package, displayName)
        })

        viewModel.restoreFinished.observe(this, Observer { finished ->
            progressBar.visibility = INVISIBLE
            button.visibility = VISIBLE
            if (finished == 0) {
                // success
                currentPackageView.text = getString(R.string.restore_finished_success)
                warningView.text = if (settingsManager.getStorage()?.isUsb == true) {
                    getString(R.string.restore_finished_warning_only_installed, getString(R.string.restore_finished_warning_ejectable))
                } else {
                    getString(R.string.restore_finished_warning_only_installed, null)
                }
                warningView.visibility = VISIBLE
            } else {
                // error
                currentPackageView.text = getString(R.string.restore_finished_error)
                currentPackageView.setTextColor(warningView.textColors)
            }
            activity?.window?.clearFlags(FLAG_KEEP_SCREEN_ON)
        })

        button.setOnClickListener {
            requireActivity().setResult(RESULT_OK)
            requireActivity().finishAfterTransition()
        }
    }

}
