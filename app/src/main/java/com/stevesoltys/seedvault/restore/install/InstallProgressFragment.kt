package com.stevesoltys.seedvault.restore.install

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager.VERTICAL
import androidx.recyclerview.widget.RecyclerView
import com.google.android.setupdesign.GlifLayout
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.restore.RestoreViewModel
import com.stevesoltys.seedvault.ui.getColorAccent
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

class InstallProgressFragment : Fragment(), InstallItemListener {

    private val viewModel: RestoreViewModel by sharedViewModel()

    private val layoutManager = LinearLayoutManager(context)
    private val adapter = InstallProgressAdapter(this)

    private lateinit var suwLayout: GlifLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var backupNameView: TextView
    private lateinit var appList: RecyclerView
    private lateinit var button: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v: View = inflater.inflate(R.layout.fragment_restore_progress, container, false)

        suwLayout = v.findViewById(R.id.setup_wizard_layout)
        progressBar = v.findViewById(R.id.progressBar)
        backupNameView = v.findViewById(R.id.backupNameView)
        appList = v.findViewById(R.id.appList)
        button = v.findViewById(R.id.button)

        val icon: Drawable = suwLayout.getIcon()
        icon.setTintList(getColorAccent(requireContext()))
        suwLayout.setIcon(icon)

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        suwLayout.setHeaderText(R.string.restore_installing_packages)

        appList.apply {
            layoutManager = this@InstallProgressFragment.layoutManager
            adapter = this@InstallProgressFragment.adapter
            addItemDecoration(DividerItemDecoration(context, VERTICAL))
        }
        button.setText(R.string.restore_next)
        button.setOnClickListener { viewModel.onNextClickedAfterInstallingApps() }

        viewModel.chosenRestorableBackup.observe(viewLifecycleOwner, { restorableBackup ->
            backupNameView.text = restorableBackup.name
        })

        viewModel.installResult.observe(viewLifecycleOwner, { result ->
            onInstallResult(result)
        })

        viewModel.nextButtonEnabled.observe(viewLifecycleOwner, { enabled ->
            button.isEnabled = enabled
        })
    }

    private fun onInstallResult(installResult: InstallResult) {
        // skip this screen, if there are no apps to install
        if (installResult.isEmpty) viewModel.onNextClickedAfterInstallingApps()

        // if finished, treat all still queued apps as failed and resort/redisplay adapter items
        if (installResult.isFinished) {
            installResult.queuedToFailed()
            adapter.setFinished()
        }

        // update progress bar
        progressBar.progress = installResult.progress
        progressBar.max = installResult.total

        // just update adapter, or perform final action, if finished
        if (installResult.isFinished) onFinished(installResult)
        else updateAdapter(installResult.getNotQueued())
    }

    private fun onFinished(installResult: InstallResult) {
        if (installResult.hasFailed) {
            AlertDialog.Builder(requireContext())
                .setIcon(R.drawable.ic_warning)
                .setTitle(R.string.restore_installing_error_title)
                .setMessage(R.string.restore_installing_error_message)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    dialog.dismiss()
                }
                .setOnDismissListener {
                    updateAdapter(installResult.getNotQueued())
                }
                .show()
        } else {
            updateAdapter(installResult.getNotQueued())
        }
    }

    private fun updateAdapter(items: Collection<ApkInstallResult>) {
        val position = layoutManager.findFirstVisibleItemPosition()
        adapter.update(items)
        if (position == 0) layoutManager.scrollToPosition(0)
    }

    override fun onFailedItemClicked(item: ApkInstallResult) {
        try {
            installAppLauncher.launch(item)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.error_no_store, LENGTH_LONG).show()
        }
    }

    private val installAppLauncher = registerForActivityResult(InstallApp()) { packageName ->
        val result = viewModel.installResult.value ?: return@registerForActivityResult
        if (result.isFinished) {
            val changed = result.reCheckFailedPackage(
                requireContext().packageManager,
                packageName.toString()
            )
            if (changed) adapter.update(result.getNotQueued())
        }
    }

    private inner class InstallApp : ActivityResultContract<ApkInstallResult, CharSequence>() {
        private lateinit var packageName: CharSequence
        override fun createIntent(context: Context, input: ApkInstallResult): Intent {
            packageName = input.packageName
            return viewModel.installIntentCreator.getIntent(
                input.packageName,
                input.installerPackageName
            )
        }

        override fun parseResult(resultCode: Int, intent: Intent?): CharSequence {
            return packageName
        }
    }

}
